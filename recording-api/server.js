// Call Recording API (stateless / deploy-safe)
// --------------------------------------------
// A small Express server that:
//   POST /recordings   -> accepts an audio file, uploads it to Cloudinary
//   GET  /recordings   -> lists recordings read back FROM Cloudinary (newest first)
//   GET  /recordings/:id -> returns one recording's metadata
//   GET  /health       -> quick health check
//
// There is NO local database. The list is reconstructed from Cloudinary itself,
// so it survives server restarts / redeploys (important on hosts like Render).
// Per-recording info (phone number, direction, duration) is stored in the
// Cloudinary asset's "context" metadata at upload time.

require("dotenv").config();
const crypto = require("crypto");
const express = require("express");
const cors = require("cors");
const multer = require("multer");
const { v2: cloudinary } = require("cloudinary");

const PORT = process.env.PORT || 3001;
const API_KEY = process.env.API_KEY || ""; // empty = auth disabled (fine for testing)
const CLOUDINARY_FOLDER = "call-recordings";

// --- Cloudinary config -----------------------------------------------------
const cloudName = process.env.CLOUDINARY_CLOUD_NAME;
const apiKey = process.env.CLOUDINARY_API_KEY;
const apiSecret = process.env.CLOUDINARY_API_SECRET;

const cloudinaryConfigured =
  cloudName &&
  apiKey &&
  apiSecret &&
  !cloudName.startsWith("paste_") &&
  !apiKey.startsWith("paste_") &&
  !apiSecret.startsWith("paste_");

if (cloudinaryConfigured) {
  cloudinary.config({
    cloud_name: cloudName,
    api_key: apiKey,
    api_secret: apiSecret,
    secure: true,
  });
  console.log(`[cloudinary] configured for cloud "${cloudName}"`);
} else {
  console.warn(
    "[cloudinary] NOT configured. Set CLOUDINARY_CLOUD_NAME / _API_KEY / _API_SECRET.\n" +
      "            The server still runs, but uploads and listing will return an error."
  );
}

// --- Helpers ---------------------------------------------------------------
// Turn a Cloudinary resource into our recording shape.
function toRecording(resource) {
  const ctx = (resource.context && resource.context.custom) || {};
  return {
    id: (resource.public_id || "").split("/").pop(),
    phoneNumber: ctx.phoneNumber || "unknown",
    direction: ctx.direction || "unknown",
    durationMs: parseInt(ctx.durationMs || "0", 10) || 0,
    url: resource.secure_url,
    bytes: resource.bytes,
    format: resource.format,
    cloudinaryPublicId: resource.public_id,
    createdAt: resource.created_at,
  };
}

async function listFromCloudinary() {
  // Cloudinary treats audio as "video" resource type.
  const res = await cloudinary.api.resources({
    resource_type: "video",
    type: "upload",
    prefix: `${CLOUDINARY_FOLDER}/`,
    context: true,
    max_results: 100,
  });
  const list = (res.resources || []).map(toRecording);
  // Newest first
  list.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  return list;
}

// --- Express setup ---------------------------------------------------------
const app = express();
app.use(cors());
app.use(express.json());

function requireApiKey(req, res, next) {
  if (!API_KEY) return next();
  if (req.get("x-api-key") === API_KEY) return next();
  return res.status(401).json({ error: "Invalid or missing x-api-key header" });
}

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 100 * 1024 * 1024 }, // 100 MB cap
});

// Friendly landing page so you can confirm the server is up from a browser.
app.get("/", (req, res) => {
  res
    .type("html")
    .send(`<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Call Recording API</title>
    <style>
      body { font-family: system-ui, sans-serif; max-width: 640px; margin: 40px auto; padding: 0 16px; color: #222; }
      code { background: #f2f2f2; padding: 2px 6px; border-radius: 4px; }
      .ok { color: #0a7d28; font-weight: 600; }
      .warn { color: #b00020; font-weight: 600; }
      li { margin: 6px 0; }
    </style>
  </head>
  <body>
    <h1>Call Recording API</h1>
    <p>The server is <span class="ok">up and running</span>.</p>
    <p>Cloudinary: ${
      cloudinaryConfigured
        ? '<span class="ok">connected</span>'
        : '<span class="warn">NOT configured</span> (set the CLOUDINARY_* env vars)'
    }</p>
    <h3>Endpoints</h3>
    <ul>
      <li><code>GET /health</code> &mdash; JSON health check</li>
      <li><code>GET /recordings</code> &mdash; list all recordings</li>
      <li><code>POST /recordings</code> &mdash; upload an audio file (multipart field <code>file</code>)</li>
    </ul>
    <p><a href="/health">/health</a> &middot; <a href="/recordings">/recordings</a></p>
  </body>
</html>`);
});

// Health check
app.get("/health", (req, res) => {
  res.json({
    ok: true,
    cloudinaryConfigured,
    time: new Date().toISOString(),
  });
});

// Upload a recording.
// multipart/form-data:
//   file        (required) the audio file
//   phoneNumber (optional)
//   direction   (optional) "incoming" | "outgoing"
//   durationMs  (optional)
app.post("/recordings", requireApiKey, upload.single("file"), async (req, res) => {
  try {
    if (!cloudinaryConfigured) {
      return res.status(503).json({
        error:
          "Cloudinary is not configured. Set the CLOUDINARY_* environment variables and restart.",
      });
    }
    if (!req.file) {
      return res.status(400).json({ error: "No file uploaded (field name must be 'file')" });
    }

    const id = crypto.randomUUID();
    const phoneNumber = (req.body.phoneNumber || "unknown").toString();
    const direction = (req.body.direction || "unknown").toString();
    const durationMs = (parseInt(req.body.durationMs || "0", 10) || 0).toString();

    const uploaded = await new Promise((resolve, reject) => {
      const stream = cloudinary.uploader.upload_stream(
        {
          resource_type: "video",
          folder: CLOUDINARY_FOLDER,
          public_id: id,
          // Store our metadata on the asset so GET /recordings can read it back.
          context: { phoneNumber, direction, durationMs },
        },
        (err, result) => (err ? reject(err) : resolve(result))
      );
      stream.end(req.file.buffer);
    });

    res.status(201).json(toRecording(uploaded));
  } catch (err) {
    console.error("[upload] failed:", err);
    res.status(500).json({ error: "Upload failed", detail: String(err.message || err) });
  }
});

// List recordings (read back from Cloudinary), newest first.
app.get("/recordings", requireApiKey, async (req, res) => {
  try {
    if (!cloudinaryConfigured) {
      return res.status(503).json({ error: "Cloudinary is not configured." });
    }
    res.json(await listFromCloudinary());
  } catch (err) {
    console.error("[list] failed:", err);
    res.status(500).json({ error: "List failed", detail: String(err.message || err) });
  }
});

// Get one recording by id.
app.get("/recordings/:id", requireApiKey, async (req, res) => {
  try {
    if (!cloudinaryConfigured) {
      return res.status(503).json({ error: "Cloudinary is not configured." });
    }
    const list = await listFromCloudinary();
    const record = list.find((r) => r.id === req.params.id);
    if (!record) return res.status(404).json({ error: "Not found" });
    res.json(record);
  } catch (err) {
    res.status(500).json({ error: "Lookup failed", detail: String(err.message || err) });
  }
});

app.listen(PORT, () => {
  console.log(`Call Recording API listening on port ${PORT}`);
  console.log(`  Health: GET /health   Upload: POST /recordings   List: GET /recordings`);
});
