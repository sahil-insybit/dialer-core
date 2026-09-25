package com.fayyaztech.dialer_app.recording

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to the Node.js recording API:
 *   POST /recordings  -> upload an audio file
 *   GET  /recordings  -> list recordings
 *
 * baseUrl example: "http://192.168.1.42:3001" (your PC on the same Wi-Fi)
 * or an ngrok https URL. No trailing slash.
 */
class RecordingApi(
    private val baseUrl: String,
    private val apiKey: String? = null
) {
    companion object {
        private const val TAG = "RecordingApi"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Upload a recording. Returns the server's JSON body on success. Throws on failure. */
    fun upload(
        file: File,
        phoneNumber: String,
        direction: String,
        durationMs: Long
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType())
            )
            .addFormDataPart("phoneNumber", phoneNumber)
            .addFormDataPart("direction", direction)
            .addFormDataPart("durationMs", durationMs.toString())
            .build()

        val requestBuilder = Request.Builder()
            .url("$baseUrl/recordings")
            .post(body)
        if (!apiKey.isNullOrBlank()) requestBuilder.header("x-api-key", apiKey)

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Upload failed: ${resp.code} $text")
                throw RuntimeException("Upload failed (${resp.code}): $text")
            }
            Log.d(TAG, "Upload ok: $text")
            return text
        }
    }

    /** Fetch the list of recordings. */
    fun list(): List<Recording> {
        val requestBuilder = Request.Builder()
            .url("$baseUrl/recordings")
            .get()
        if (!apiKey.isNullOrBlank()) requestBuilder.header("x-api-key", apiKey)

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("List failed (${resp.code}): $text")
            }
            return parse(text)
        }
    }

    private fun parse(json: String): List<Recording> {
        val arr = JSONArray(json)
        val out = ArrayList<Recording>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Recording(
                    id = o.optString("id"),
                    phoneNumber = o.optString("phoneNumber"),
                    direction = o.optString("direction"),
                    durationMs = o.optLong("durationMs"),
                    url = o.optString("url"),
                    createdAt = o.optString("createdAt")
                )
            )
        }
        return out
    }
}
