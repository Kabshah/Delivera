package com.kabshah.delivra.attachments

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Records a WhatsApp-ready Opus/OGG voice note entirely on-device:
 *
 *   AudioRecord (48 kHz mono PCM, VOICE_COMMUNICATION)
 *     → MediaCodec "audio/opus" encoder (32 kbps, 20 ms frames)
 *     → hand-rolled RFC 7845-compliant Ogg muxer
 *       (OpusHead BOS page with the encoder's own csd-0, OpusTags, pages
 *        with exact cumulative granule positions, EOS on the final page).
 *
 * Why not MediaRecorder(OutputFormat.OGG, AudioEncoder.OPUS): its muxer emits
 * Ogg Opus files that Android's own (lenient) MediaExtractor parses fine — we
 * compute waveforms from them — but WhatsApp's stricter player refuses to play
 * them (live-tested 2026-11-06: seconds + waveform present, still unplayable).
 * Owning the muxer gives byte-exact control over the page structure, which is
 * exactly the surface WhatsApp trips over. Zero external dependencies (§2.5).
 */
internal class OpusRecorder(private val outputFile: File) {

    companion object {
        private const val TAG = "OpusRecorder"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960          // 20 ms @ 48 kHz — Opus encoder frame
        private const val FRAME_US = 20_000L
        private const val BIT_RATE = 32_000
        private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)
    }

    private val running = AtomicBoolean(false)
    private val durationMs = AtomicLong(0)
    private var thread: Thread? = null

    @android.annotation.SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before start()
    fun start() {
        check(!running.get()) { "Already recording" }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "AudioRecord.getMinBufferSize failed: $minBuf" }
        // Mic permission (RECORD_AUDIO) is requested by the UI before this call.
        val audioRecord = AudioRecord(
            // MIC, not VOICE_COMMUNICATION: the latter is meant for in-call audio
            // paths and several Samsung ROMs gate it for third-party apps — reads
            // go silent after ~300ms while the thread stays alive (live-tested
            // 2026-11-06). Plain MIC is the reliable, universally-working source.
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, FRAME_SAMPLES * 2 * 8)
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

        val encoder = MediaCodec.createEncoderByType("audio/opus")
        val format = MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = OggOpusMuxer(FileOutputStream(outputFile), kotlin.random.Random.nextInt())
        val done = AtomicBoolean(false)

        running.set(true)
        thread = Thread {
            var headWritten = false
            var inputEos = false
            var framesIn = 0L
            var framesOut = 0L
            // A 20 ms mic frame already read but waiting for a free codec input
            // buffer. Reading FIRST (with no buffer held) keeps the feed at full
            // realtime — holding the buffer across the blocking read (the earlier
            // draft) can starve the encoder on some OEM codecs.
            var framePending = false
            val info = MediaCodec.BufferInfo()
            val pcm = ShortArray(FRAME_SAMPLES)
            var csdBytes: ByteArray? = null

            /** Writes the BOS + OpusTags pages exactly once, with a normalized OpusHead. */
            fun writeHeaderOnce() {
                if (headWritten) return
                muxer.writeHeader(normalizeOpusHead(csdBytes))
                muxer.writeTags()
                headWritten = true
            }

            fun handleFormatChanged() {
                val csd = encoder.outputFormat.getByteBuffer("csd-0")
                if (csd != null && !headWritten) {
                    val raw = ByteArray(csd.remaining())
                    csd.get(raw)
                    csdBytes = raw
                    writeHeaderOnce()
                }
            }

            /** Writes one encoded packet. Returns true only when the packet carries EOS. */
            fun handlePacket(outIdx: Int): Boolean {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                // Two degenerate output buffers must never enter the muxer:
                //   1. CSD (BUFFER_FLAG_CODEC_CONFIG) — the encoder re-emits its
                //      OpusHead as a flagged buffer; written as an "audio packet"
                //      it lands a SECOND OpusHead inside the stream. Android's
                //      OggExtractor init then trips ("found no audio track",
                //      computeWaveform null → no seconds/waveform sent) and
                //      WhatsApp's player rejects the PTT outright ("something is
                //      wrong with audio file", 2026-11-07). The header already
                //      comes from handleFormatChanged()'s csd-0 — skip this copy.
                //   2. size == 0 — Samsung's c2 encoder emits an empty EOS-flagged
                //      buffer (every take logs out == in + 1..2); a 0-byte Opus
                //      packet is illegal in Ogg (RFC 7845).
                val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isCodecConfig) {
                    // Capture the CSD bytes for header normalization, but never
                    // mux them as audio (see note above).
                    if (csdBytes == null && info.size > 0) {
                        val raw = ByteArray(info.size)
                        encoder.getOutputBuffer(outIdx)!!.get(raw)
                        csdBytes = raw
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    return eos
                }
                if (info.size > 0) {
                    val packet = ByteArray(info.size)
                    encoder.getOutputBuffer(outIdx)!!.get(packet)
                    // Defensive: csd should always arrive first (format change or
                    // flagged buffer); synthesize a spec-default OpusHead if the
                    // OEM codec somehow skipped both.
                    writeHeaderOnce()
                    muxer.writePacket(packet)
                    durationMs.set(muxer.totalSamples * 1000 / SAMPLE_RATE)
                }
                encoder.releaseOutputBuffer(outIdx, false)
                return eos
            }

            try {
                audioRecord.startRecording()
                // Cap check in MICROSECONDS: framesIn*FRAME_US counts µs while
                // MAX_DURATION_MS is milliseconds — multiply it out or the loop
                // dies at frame 15 (300,000µs == 300,000), i.e. every recording
                // silently ended at exactly 300ms (live-caught 2026-11-07: logcat
                // showed "recording thread done: in=15 out=17 (340ms)" for a
                // 40-second take).
                while (!done.get() && framesIn * FRAME_US < VoiceNoteRecorder.MAX_DURATION_MS * 1000L) {
                    // ── 1. mic read (never while holding a codec buffer) ──────
                    if (!inputEos) {
                        if (framePending) {
                            val inIdx = encoder.dequeueInputBuffer(100_000)
                            if (inIdx >= 0) {
                                val inputBuf = encoder.getInputBuffer(inIdx)!!
                                inputBuf.clear()
                                val shorts = inputBuf.asShortBuffer()
                                for (i in 0 until FRAME_SAMPLES) shorts.put(i, pcm[i])
                                encoder.queueInputBuffer(inIdx, 0, FRAME_SAMPLES * 2, framesIn * FRAME_US, 0)
                                framesIn++
                                // Progress beacon — one line every ~5 s (§6.9 diagnostics)
                                if (framesIn % 250L == 0L) {
                                    Log.i(TAG, "recording… in=$framesIn out=$framesOut")
                                }
                                framePending = false
                            }
                        } else if (running.get()) {
                            val read = audioRecord.read(pcm, 0, FRAME_SAMPLES)
                            if (read > 0) {
                                framePending = true
                            } else {
                                Log.w(TAG, "audioRecord.read returned $read (state=${audioRecord.state}, recording=${audioRecord.recordingState}) — ending stream")
                                inputEos = true
                            }
                        } else {
                            // stop() requested — flush EOS
                            val inIdx = encoder.dequeueInputBuffer(100_000)
                            if (inIdx >= 0) {
                                encoder.queueInputBuffer(inIdx, 0, 0, framesIn * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                        }
                    }

                    // ── 2. drain encoder output ───────────────────────────────
                    // NOTE: FORMAT_CHANGED is NOT an end-of-stream — only a packet
                    // carrying the EOS flag may set done. (Bug fixed 2026-11-06:
                    // treating FORMAT_CHANGED as done killed every recording after
                    // ~180 ms, right after the codec finished starting up.)
                    var outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleFormatChanged()
                        } else if (handlePacket(outIdx)) {
                            done.set(true)
                        }
                        outIdx = encoder.dequeueOutputBuffer(info, 0)
                    }
                }

                // Loop exit without EOS (max duration reached, or a pending frame
                // left) — flush the tail so the last packets are in the file.
                if (!inputEos) {
                    val inIdx = encoder.dequeueInputBuffer(100_000)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(inIdx, 0, 0, framesIn * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                var outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        handleFormatChanged()
                    } else {
                        handlePacket(outIdx)
                    }
                    outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                }
                Log.i(TAG, "recording thread done: in=$framesIn out=$framesOut (${durationMs.get()}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop failed: ${e.message}", e)
            } finally {
                try { audioRecord.stop() } catch (_: Exception) { }
                try { audioRecord.release() } catch (_: Exception) { }
                try {
                    encoder.stop()
                    encoder.release()
                } catch (_: Exception) { }
                try {
                    muxer.finish()
                } catch (_: Exception) { }
                done.set(true)
                running.set(false)
            }
        }.also { it.start() }
    }

    /** Blocks until the recording thread finishes. Returns duration in ms. */
    fun stopAndWait(): Long {
        running.set(false)
        val t = thread
        if (t != null) {
            // Bounded join — a thread stuck in a blocking audioRecord.read (mic
            // source gone silent) must not hang the UI; report what we captured.
            t.join(5_000)
            if (t.isAlive) {
                Log.w(TAG, "recording thread still alive after 5s join (mic read stuck?) — returning ${durationMs.get()}ms")
            }
        }
        return durationMs.get()
    }

    /**
     * Returns a standard 19-byte RFC 7845 OpusHead from the encoder's raw csd-0.
     *
     * Why this exists: Samsung's c2.android.opus.encoder wraps its OpusHead in a
     * proprietary "SAOPUSHDR" blob (9-byte magic + size byte + padding + the real
     * 19-byte header) instead of a plain ID header — live oggdiag 2026-11-07:
     * page#0 body=83 starting `53 41 4f 50 …`. Muxing that blob as the stream's
     * first packet makes every strict parser fail: Android's OggExtractor
     * memcmp's "OpusHead" → MALFORMED → "found no audio track" (so Kotlin
     * silently sent NO seconds/waveform), and WhatsApp's player rejects the PTT
     * ("something is wrong with audio file"). Extract the embedded header; if
     * the codec's csd is absent/foreign, synthesize a spec-default one.
     */
    private fun normalizeOpusHead(csd: ByteArray?): ByteArray {
        if (csd != null && csd.size >= 19) {
            val idx = indexOf(csd, OPUS_HEAD_MAGIC)
            if (idx >= 0 && idx + 19 <= csd.size) {
                val head = csd.copyOfRange(idx, idx + 19)
                val version = head[8].toInt() and 0xFF
                val channels = head[9].toInt() and 0xFF
                if (version <= 1 && channels in 1..8) return head
            }
        }
        // Fallback: spec-default header. libopus-family encoders (incl. c2)
        // use a 312-sample (6.5 ms @ 48 kHz) lookahead for 20 ms frames.
        return ByteArray(19).apply {
            "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(this)
            this[8] = 1   // version
            this[9] = 1   // channels
            this[10] = 0x38   // pre-skip = 312 (LE16)
            this[11] = 0x01
            val rate = SAMPLE_RATE
            this[12] = (rate and 0xFF).toByte()
            this[13] = ((rate shr 8) and 0xFF).toByte()
            this[14] = ((rate shr 16) and 0xFF).toByte()
            this[15] = ((rate shr 24) and 0xFF).toByte()
            // output gain 0, mapping family 0 — already zero
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * Minimal RFC 7845-compliant Ogg Opus muxer. Correct page structure and CRC
 * are what WhatsApp's player needs and MediaRecorder's output lacks.
 */
internal class OggOpusMuxer(private val out: OutputStream, private val streamSerial: Int) {

    private var sequence = 0
    private var granule = 0L
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0
    private var finished = false

    fun writeHeader(opusHead: ByteArray) = writePage(headerType = 2, granule = 0L, packets = listOf(opusHead))

    fun writeTags() {
        val vendor = "Delivra".toByteArray(Charsets.UTF_8)
        val payload = java.io.ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray(Charsets.US_ASCII))
            write(vendor.size and 0xFF); write((vendor.size shr 8) and 0xFF)
            write((vendor.size shr 16) and 0xFF); write((vendor.size shr 24) and 0xFF)
            write(vendor)
            write(0); write(0); write(0); write(0)   // zero user comments
        }.toByteArray()
        writePage(headerType = 0, granule = 0L, packets = listOf(payload))
    }

    /** Total decoded samples written so far (granule accounting, RFC 7845 §4). */
    var totalSamples = 0L
        private set

    fun writePacket(packet: ByteArray) {
        check(!finished) { "Muxer already finished" }
        pending.add(packet)
        pendingBytes += packet.size
        // Advance the granule by the packet's ACTUAL sample count, decoded from
        // its TOC byte — the encoder emits variable-duration frames (10/20/40 ms,
        // sometimes 2 frames per packet), so a fixed 960/packet drifts the
        // timeline and players compute a wrong duration.
        totalSamples += opusPacketSamples(packet)
        granule = totalSamples
        if (pendingBytes >= PAGE_FLUSH_THRESHOLD) flush(eos = false)
    }

    /**
     * Samples a single Opus packet decodes to (RFC 6716 §3.1): the TOC byte's
     * config field picks the frame duration, the code field the frame count.
     */
    private fun opusPacketSamples(packet: ByteArray): Long {
        if (packet.isEmpty()) return 0L
        val toc = packet[0].toInt() and 0xFF
        val config = (toc shr 3) and 0x1F
        val code = toc and 0x03
        // Samples per frame @ 48 kHz: SILK 10/20/40/60 ms, hybrid 10/20 ms,
        // CELT 2.5/5/10/20 ms.
        val frameSamples = when (config) {
            in 0..11 -> longArrayOf(480L, 960L, 1920L, 2880L)[config and 3]
            in 12..15 -> if (config and 1 == 0) 480L else 960L
            else -> longArrayOf(120L, 240L, 480L, 960L)[config and 3]
        }
        val frames = when (code) {
            0 -> 1L
            1, 2 -> 2L
            else -> if (packet.size >= 2) (packet[1].toInt() and 0x3F).toLong() else 0L
        }
        return frameSamples * frames
    }

    fun finish() {
        if (finished) return
        finished = true
        flush(eos = true)
        out.flush()
        out.close()
    }

    private fun flush(eos: Boolean) {
        if (pending.isEmpty() && !eos) return
        writePage(headerType = if (eos) 4 else 0, granule = granule, packets = pending.toList())
        pending.clear()
        pendingBytes = 0
    }

    private fun writePage(headerType: Int, granule: Long, packets: List<ByteArray>) {
        // ── segment (lacing) table ────────────────────────────────────────────
        val segments = ArrayList<Byte>()
        for (p in packets) {
            var remaining = p.size
            while (remaining >= 255) {
                segments.add(255.toByte())
                remaining -= 255
            }
            // Final segment < 255 marks the packet boundary (a 0 terminator is
            // required when the size is an exact multiple of 255).
            segments.add(remaining.toByte())
        }
        val bodySize = packets.sumOf { it.size }
        val page = ByteArray(27 + segments.size + bodySize)

        // ── 27-byte page header ───────────────────────────────────────────────
        page[0] = 'O'.toByte(); page[1] = 'g'.toByte(); page[2] = 'g'.toByte(); page[3] = 'S'.toByte()
        page[4] = 0          // stream structure version
        page[5] = headerType.toByte()
        for (i in 0 until 8) page[6 + i] = ((granule shr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) page[14 + i] = ((streamSerial shr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) page[18 + i] = ((sequence shr (8 * i)) and 0xFF).toByte()
        // CRC field (bytes 22-25) stays zero while computing
        page[26] = segments.size.toByte()
        var offset = 27
        for (s in segments) page[offset++] = s
        for (p in packets) {
            p.copyInto(page, offset)
            offset += p.size
        }
        sequence++

        // ── CRC over the whole page with the field zeroed ─────────────────────
        var crc = 0
        for (b in page) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        for (i in 0 until 4) page[22 + i] = ((crc shr (8 * i)) and 0xFF).toByte()

        out.write(page)
    }

    private companion object {
        const val PAGE_FLUSH_THRESHOLD = 16 * 1024

        /** Ogg CRC-32: polynomial 0x04c11db7, init 0, MSB-first, no final XOR (Ogg spec). */
        val CRC_TABLE = IntArray(256).also { table ->
            for (i in 0..255) {
                var r = i shl 24
                repeat(8) {
                    r = if (r and -0x80000000 != 0) (r shl 1) xor 0x04c11db7 else r shl 1
                }
                table[i] = r
            }
        }
    }
}
