# Call Recording API

A tiny Node.js + Express server that stores call audio recordings in
[Cloudinary](https://cloudinary.com) and lets your app list them back.

## Endpoints

| Method | Path              | What it does                                   |
| ------ | ----------------- | ---------------------------------------------- |
| GET    | `/health`         | Quick check that the server is up              |
| POST   | `/recordings`     | Upload an audio file (multipart, field `file`) |
| GET    | `/recordings`     | List all recordings (newest first)             |
| GET    | `/recordings/:id` | Get one recording's metadata                   |

## Setup (one time)

1. **Install Node.js** (v18+). Check with `node -v`.
2. **Create a free Cloudinary account:** https://cloudinary.com/users/register_free
3. On the Cloudinary **Dashboard**, copy your **Cloud name**, **API Key**, and **API Secret**.
4. In this folder, copy `.env.example` to `.env` and paste those three values in.
5. Install dependencies:
   ```
   npm install
   ```

## Run

```
npm start
```

You should see `Call Recording API listening on http://localhost:3001`.

Test it in a browser: open http://localhost:3001/health — you should get JSON
with `"ok": true`. If `"cloudinaryConfigured"` is `false`, your `.env` keys aren't
set correctly yet.

## Uploading a test file with curl

```
curl -F "file=@sample.m4a" -F "phoneNumber=+15551234567" -F "direction=outgoing" http://localhost:3001/recordings
```

## Reaching the API from your phone

Your phone can't reach `localhost` on your PC. Two options:

- **Same Wi-Fi:** find your PC's local IP (run `ipconfig`, look for IPv4 like
  `192.168.1.42`). In the app, set the API URL to `http://192.168.1.42:3001`.
  Make sure your firewall allows Node.js.
- **Anywhere (recommended for testing):** use a tunnel such as
  [ngrok](https://ngrok.com): run `ngrok http 3001`, then use the
  `https://...ngrok...` URL it prints as the API URL in the app.

## Optional: require an API key

Set `API_KEY=somesecret` in `.env`. Then every request must include the header
`x-api-key: somesecret`. Leave it blank to disable while testing.

## Notes

- Recording metadata is stored in `data/recordings.json` (created automatically).
  No database needed for testing.
- Cloudinary stores audio under its "video" resource type — that's expected.
