package com.fayyaztech.dialer_app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Records audio from the microphone into an .m4a file.
 *
 * IMPORTANT (honest limitation): On Android 10+ regular apps cannot capture the
 * true two-way call audio. This records from the MIC. To pick up the other
 * party's voice you must be on speakerphone so the mic hears the earpiece
 * loudspeaker. Your own voice is always captured clearly.
 *
 * We use MediaRecorder.AudioSource.VOICE_COMMUNICATION when available (better
 * for calls, applies echo cancellation) and fall back to MIC.
 */
class CallRecorder(private val context: Context) {

    companion object {
        private const val TAG = "CallRecorder"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    var isRecording: Boolean = false
        private set

    /**
     * Start recording. Returns true if recording started.
     * @param phoneNumber used only to make the file name readable.
     */
    fun start(phoneNumber: String?): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }
        return try {
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val safeNumber = (phoneNumber ?: "unknown").replace(Regex("[^0-9+]"), "")
            val file = File(dir, "call_${safeNumber}_${System.currentTimeMillis()}.m4a")

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Prefer VOICE_COMMUNICATION (tuned for calls); fall back to MIC.
            val source = try {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } catch (e: Throwable) {
                MediaRecorder.AudioSource.MIC
            }

            rec.setAudioSource(source)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)

            rec.prepare()
            rec.start()

            recorder = rec
            outputFile = file
            startedAtMs = System.currentTimeMillis()
            isRecording = true
            Log.d(TAG, "Recording started -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            safeReleaseAfterError()
            false
        }
    }

    /**
     * Stop recording.
     * @return the recorded file and its duration, or null if nothing was recorded.
     */
    fun stop(): Result? {
        if (!isRecording) return null
        val file = outputFile
        val durationMs = System.currentTimeMillis() - startedAtMs
        return try {
            recorder?.apply {
                try { stop() } catch (e: RuntimeException) {
                    // stop() throws if stopped too quickly / no data; the file may be invalid.
                    Log.w(TAG, "MediaRecorder.stop() threw: ${e.message}")
                }
                release()
            }
            recorder = null
            isRecording = false
            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording stopped -> ${file.absolutePath} (${file.length()} bytes)")
                Result(file, durationMs)
            } else {
                Log.w(TAG, "No valid recording produced")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording: ${e.message}", e)
            safeReleaseAfterError()
            null
        } finally {
            outputFile = null
        }
    }

    private fun safeReleaseAfterError() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }

    /** The recorded file plus how long it ran, in milliseconds. */
    data class Result(val file: File, val durationMs: Long)
}
