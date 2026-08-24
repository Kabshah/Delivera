package com.kabshah.delivra.attachments

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * VoiceNoteRecorder — records in-app voice notes as genuine WhatsApp-ready
 * Opus/OGG (§4.3, §6.4a).
 *
 * MediaRecorder supports OutputFormat.OGG + AudioEncoder.OPUS from API 29,
 * which means the recording is ALREADY in WhatsApp's PTT voice-note format at
 * attach time — no transcode step, no temp artifacts (§2.5 lightweight rule).
 * On API < 29 voice recording is unsupported; [isSupported] gates the UI.
 */
class VoiceNoteRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceNoteRecorder"
        const val MAX_DURATION_MS = 300_000L // hard cap: 5 minutes per note

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** Deletes an app-owned voice note file (used on re-record/delete/cleanup). */
        fun deleteFile(path: String?) {
            if (path == null) return
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete voice note $path: ${e.message}")
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording to app-private storage. Returns the target file path.
     * Throws on failure (caller surfaces the error); partial files are cleaned up.
     */
    fun start(): String {
        check(!isRecording) { "Already recording" }
        if (!isSupported()) error("Voice notes require Android 10+")

        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${System.currentTimeMillis()}.ogg")

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_DURATION_MS.toInt())
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw e
        }

        recorder = r
        outputFile = file
        startedAtMs = System.currentTimeMillis()
        Log.i(TAG, "Recording started → ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * Stops and finalizes the recording. Returns duration in ms, or null if the
     * recording was too short to produce valid output (file is deleted then).
     */
    fun stop(): Long? {
        val r = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        return try {
            r.stop()
            val duration = System.currentTimeMillis() - startedAtMs
            // Reject accidental taps — WhatsApp would show a broken bubble
            if (duration < 1000 || file == null || !file.exists() || file.length() == 0L) {
                file?.delete()
                Log.i(TAG, "Recording discarded (too short/empty, ${duration}ms)")
                null
            } else {
                Log.i(TAG, "Recording finished: ${duration}ms, ${file.length()} bytes")
                duration
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recording discarded: ${e.message}")
            file?.delete()
            null
        } finally {
            r.release()
        }
    }

    /** Cancels without producing a file (screen left / re-record). */
    fun cancel() {
        val r = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        try {
            r.stop()
        } catch (_: Exception) { /* best effort */ }
        r.release()
        file?.delete()
        Log.i(TAG, "Recording cancelled, partial file removed")
    }
}
