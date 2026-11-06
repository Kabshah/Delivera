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

        /**
         * Decodes an OGG/Opus voice note with the platform codec (MediaExtractor +
         * MediaCodec — no extra dependency, §2.5) and computes the 64-byte loudness
         * envelope WhatsApp renders as the voice-note waveform (values 0..100, same
         * shape WhatsApp Web's own sends carry).
         *
         * Why this lives in Kotlin and not in the Node engine: Baileys derives the
         * waveform via the `audio-decode` package, which isn't installed and can't
         * decode Opus anyway — so PTT messages went out with NO waveform, and
         * WhatsApp clients refuse to play a PTT bubble without one (live report
         * 2026-11-06: "voice note not playable on WhatsApp").
         *
         * Returns null when the file can't be decoded — caller sends without a
         * waveform (and the log line then tells us the container is broken, which
         * would point at MediaRecorder itself).
         */
        fun computeWaveform(path: String, points: Int = 64): ByteArray? {
            val extractor = android.media.MediaExtractor()
            var codec: android.media.MediaCodec? = null
            return try {
                extractor.setDataSource(path)
                var trackIndex = -1
                var format: android.media.MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                extractor.selectTrack(trackIndex)
                val mime = format!!.getString(android.media.MediaFormat.KEY_MIME)!!
                val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    format.getLong(android.media.MediaFormat.KEY_DURATION)
                } else 0L
                if (durationUs <= 0) return null
                val totalPcmEstimate = durationUs * sampleRate / 1_000_000L

                codec = android.media.MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
                val envelope = DoubleArray(points)
                val counts = IntArray(points)
                var totalPcmSamples = 0L
                val info = android.media.MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val out = codec.getOutputBuffer(outIdx)!!
                            out.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val shorts = out.asShortBuffer()
                            val n = shorts.remaining()
                            if (n > 0) {
                                var sumSquares = 0.0
                                for (i in 0 until n) {
                                    val v = shorts.get(i).toDouble()
                                    sumSquares += v * v
                                }
                                val block = ((totalPcmSamples * points) / totalPcmEstimate)
                                    .toInt().coerceIn(0, points - 1)
                                envelope[block] += sumSquares
                                counts[block] += n
                                totalPcmSamples += n
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                        outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* keep decoding */ }
                        else -> { /* try again */ }
                    }
                }

                var maxRms = 0.0
                val rms = DoubleArray(points)
                for (i in 0 until points) {
                    rms[i] = if (counts[i] > 0) Math.sqrt(envelope[i] / counts[i]) else 0.0
                    if (rms[i] > maxRms) maxRms = rms[i]
                }
                if (maxRms <= 0.0) return null
                ByteArray(points) { i -> ((rms[i] / maxRms) * 100).toInt().coerceIn(0, 100).toByte() }
            } catch (e: Exception) {
                Log.w(TAG, "Waveform decode failed for $path: ${e.message}")
                null
            } finally {
                try { codec?.stop() } catch (_: Exception) { }
                try { codec?.release() } catch (_: Exception) { }
                try { extractor.release() } catch (_: Exception) { }
            }
        }

        /** Duration in whole seconds via platform metadata reader (≥1), or null if unreadable. */
        fun readDurationSeconds(path: String): Int? {
            return try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(path)
                val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                mmr.release()
                if (ms != null && ms > 0) Math.ceil(ms / 1000.0).toInt().coerceAtLeast(1) else null
            } catch (e: Exception) {
                Log.w(TAG, "Duration read failed for $path: ${e.message}")
                null
            }
        }
    }

    private var opus: OpusRecorder? = null
    private var legacy: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = opus != null || legacy != null

    /**
     * Starts recording to app-private storage. Returns the target file path.
     * Throws on failure (caller surfaces the error); partial files are cleaned up.
     */
    fun start(): String {
        check(!isRecording) { "Already recording" }
        if (!isSupported()) error("Voice notes require Android 10+")

        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${System.currentTimeMillis()}.ogg")

        // Preferred path: AudioRecord → MediaCodec Opus → RFC 7845 muxer. This is
        // the byte-exact container WhatsApp's player accepts (MediaRecorder's OGG
        // muxer produces files Android can parse but WhatsApp refuses to play —
        // live-verified 2026-11-06). MediaRecorder stays as a fallback for OEMs
        // where the Opus encoder isn't available.
        try {
            val r = OpusRecorder(file)
            r.start()
            opus = r
            Log.i(TAG, "Recording started (RFC 7845 Opus) → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Opus recorder unavailable (${e.message}) — falling back to MediaRecorder")
            file.delete()
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
            } catch (e2: Exception) {
                r.release()
                file.delete()
                throw e2
            }
            legacy = r
            Log.i(TAG, "Recording started (MediaRecorder fallback) → ${file.absolutePath}")
        }

        outputFile = file
        startedAtMs = System.currentTimeMillis()
        return file.absolutePath
    }

    /**
     * Stops and finalizes the recording. Returns duration in ms, or null if the
     * recording was too short to produce valid output (file is deleted then).
     */
    fun stop(): Long? {
        val o = opus
        val r = legacy
        opus = null
        legacy = null
        val file = outputFile
        outputFile = null
        return try {
            val duration = if (o != null) {
                o.stopAndWait()
            } else {
                r?.stop()
                System.currentTimeMillis() - startedAtMs
            }
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
            try { r?.release() } catch (_: Exception) { }
        }
    }

    /** Cancels without producing a file (screen left / re-record). */
    fun cancel() {
        val o = opus
        val r = legacy
        opus = null
        legacy = null
        val file = outputFile
        outputFile = null
        if (o != null) {
            try { o.stopAndWait() } catch (_: Exception) { /* best effort */ }
        }
        if (r != null) {
            try {
                r.stop()
            } catch (_: Exception) { /* best effort */ }
            r.release()
        }
        file?.delete()
        Log.i(TAG, "Recording cancelled, partial file removed")
    }
}
