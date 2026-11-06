#!/usr/bin/env node
/**
 * test-ogg-muxer.mjs — validates the OggOpusMuxer container logic that
 * OpusRecorder.kt (app/src/main/java/.../attachments/OpusRecorder.kt) uses to
 * produce WhatsApp-ready voice notes.
 *
 * The muxer itself runs on Android inside MediaCodec's output loop, but it is
 * pure java.io + arithmetic — so this harness ports it VERBATIM to JS and
 * validates the algorithm end-to-end on the host:
 *
 *   1. ffmpeg produces a reference Ogg/Opus file (48 kHz mono 32 kbps — same
 *      settings as the recorder).
 *   2. The reference is parsed page-by-page; every page's stored CRC is
 *      re-computed with the ported Ogg CRC table — a mismatch means the CRC
 *      implementation (and therefore the Kotlin one) is wrong.
 *   3. The reference's REAL Opus packets are re-muxed through the ported
 *      muxer (same OpusHead/OpusTags, same 20 ms granule progression).
 *   4. The remuxed file is re-parsed and its logical packet stream is compared
 *      byte-for-byte with the reference's packet stream, plus structural
 *      invariants (page body limits, ≤255 segments, monotone granule, EOS flag
 *      on the last page, BOS on the first).
 *   5. ffprobe / ffmpeg full-decode both files externally (strict parser,
 *      close to what WhatsApp's player does).
 *
 * Usage: node test-ogg-muxer.mjs <reference.ogg> <remuxed-output.ogg>
 *
 * Exit code 0 = all checks passed; 1 = validation failure.
 */

import fs from 'fs';
import { execFileSync } from 'child_process';

const [refPath, outPath] = process.argv.slice(2);
if (!refPath || !outPath) {
  console.error('Usage: node test-ogg-muxer.mjs <reference.ogg> <remuxed-output.ogg>');
  process.exit(1);
}

// ════════════════════════════════════════════════════════════════════════════
// 1. Verbatim port of OggOpusMuxer's CRC (OpusRecorder.kt, CRC_TABLE + usage)
// ════════════════════════════════════════════════════════════════════════════

/** Ogg CRC-32: polynomial 0x04c11db7, init 0, MSB-first, no final XOR (Ogg spec). */
const CRC_TABLE = new Int32Array(256);
for (let i = 0; i <= 255; i++) {
  let r = i << 24;
  for (let k = 0; k < 8; k++) {
    r = (r & -0x80000000) !== 0 ? ((r << 1) ^ 0x04c11db7) : (r << 1);
  }
  CRC_TABLE[i] = r;
}

/** Port of OggOpusMuxer.writePage's CRC loop (field must be zeroed first). */
function oggCrc(page) {
  let crc = 0;
  for (const b of page) {
    crc = ((crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF]) | 0;
  }
  return crc;
}

// ════════════════════════════════════════════════════════════════════════════
// 2. Verbatim port of OggOpusMuxer (OpusRecorder.kt lines ~242-345)
// ════════════════════════════════════════════════════════════════════════════

const FRAME_SAMPLES = 960n;              // 20 ms @ 48 kHz, per packet
const PAGE_FLUSH_THRESHOLD = 16 * 1024;  // same as Kotlin

class OggOpusMuxer {
  constructor(out, streamSerial) {
    this.out = out;
    this.streamSerial = streamSerial | 0;
    this.sequence = 0;
    this.granule = 0n;
    this.pending = [];
    this.pendingBytes = 0;
    this.finished = false;
  }

  writeHeader(opusHead) { this.writePage(2, 0n, [opusHead]); }

  writeTags() {
    const vendor = Buffer.from('Delivra', 'utf-8');
    const payload = Buffer.concat([
      Buffer.from('OpusTags', 'ascii'),
      Buffer.from([vendor.length & 0xFF, (vendor.length >> 8) & 0xFF, (vendor.length >> 16) & 0xFF, (vendor.length >> 24) & 0xFF]),
      vendor,
      Buffer.from([0, 0, 0, 0]),   // zero user comments
    ]);
    this.writePage(0, 0n, [payload]);
  }

  writePacket(packet) {
    if (this.finished) throw new Error('Muxer already finished');
    this.pending.push(packet);
    this.pendingBytes += packet.length;
    this.granule += FRAME_SAMPLES;   // 20 ms per packet @ 48 kHz
    if (this.pendingBytes >= PAGE_FLUSH_THRESHOLD) this.flush(false);
  }

  finish() {
    if (this.finished) return;
    this.finished = true;
    this.flush(true);
  }

  flush(eos) {
    if (this.pending.length === 0 && !eos) return;
    this.writePage(eos ? 4 : 0, this.granule, this.pending);
    this.pending = [];
    this.pendingBytes = 0;
  }

  writePage(headerType, granule, packets) {
    const segments = [];
    for (const p of packets) {
      let remaining = p.length;
      while (remaining >= 255) { segments.push(255); remaining -= 255; }
      segments.push(remaining);
    }
    const bodySize = packets.reduce((s, p) => s + p.length, 0);
    const page = Buffer.alloc(27 + segments.length + bodySize);

    page.write('OggS', 0, 'ascii');
    page[4] = 0;                    // stream structure version
    page[5] = headerType;
    page.writeBigUInt64LE(granule, 6);
    page.writeInt32LE(this.streamSerial, 14);
    page.writeInt32LE(this.sequence, 18);
    // CRC field (bytes 22-25) stays zero while computing
    page[26] = segments.length;
    let offset = 27;
    for (const s of segments) page[offset++] = s;
    for (const p of packets) { p.copy(page, offset); offset += p.length; }
    this.sequence++;

    const crc = oggCrc(page);
    for (let i = 0; i < 4; i++) page[22 + i] = (crc >>> (8 * i)) & 0xFF;

    this.out.write(page);
  }
}

// ════════════════════════════════════════════════════════════════════════════
// 3. Ogg page parser (read-only) — extracts pages and logical packets
// ════════════════════════════════════════════════════════════════════════════

function parseOgg(buf) {
  const pages = [];
  let pos = 0;
  while (pos < buf.length) {
    if (buf.readUInt32BE(pos) !== 0x4f676753) throw new Error(`Bad page sync at ${pos}`);
    const version = buf[pos + 4];
    const headerType = buf[pos + 5];
    const granule = buf.readBigUInt64LE(pos + 6);
    const serial = buf.readInt32LE(pos + 14);
    const sequence = buf.readInt32LE(pos + 18);
    const storedCrc = buf.readUInt32LE(pos + 22);
    const nSegs = buf[pos + 26];
    const segTable = [];
    for (let i = 0; i < nSegs; i++) segTable.push(buf[pos + 27 + i]);
    let bodySize = 0;
    for (const s of segTable) bodySize += s;
    const pageEnd = pos + 27 + nSegs + bodySize;

    const page = buf.subarray(pos, pageEnd);
    // Recompute CRC with the field zeroed — validates our CRC implementation
    // against the producer's (ffmpeg's) on real data.
    const zeroed = Buffer.from(page);
    zeroed[22] = 0; zeroed[23] = 0; zeroed[24] = 0; zeroed[25] = 0;
    const computed = oggCrc(zeroed) >>> 0;
    if (computed !== storedCrc) {
      throw new Error(`CRC mismatch on page ${sequence}: stored=0x${storedCrc.toString(16)} computed=0x${computed.toString(16)}`);
    }

    // Reassemble logical packets (255-continuation rule)
    const packets = [];
    let cur = [];
    for (let i = 0; i < nSegs; i++) {
      const segLen = segTable[i];
      const start = pos + 27 + nSegs + bodySizeOffset(segTable, i);
      cur.push(buf.subarray(start, start + segLen));
      if (segLen < 255) { packets.push(Buffer.concat(cur)); cur = []; }
    }
    if (cur.length > 0) throw new Error(`Page ${sequence} ends mid-packet (unterminated lacing)`);

    pages.push({ version, headerType, granule, serial, sequence, nSegs, bodySize, packets });
    pos = pageEnd;
  }
  return pages;
}
function bodySizeOffset(segTable, upto) {
  let o = 0;
  for (let i = 0; i < upto; i++) o += segTable[i];
  return o;
}

// ════════════════════════════════════════════════════════════════════════════
// 4. Run the validation pipeline
// ════════════════════════════════════════════════════════════════════════════

let failures = 0;
const check = (ok, label, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
};

console.log('[1/5] Parsing reference + verifying CRC implementation against ffmpeg pages…');
const refBuf = fs.readFileSync(refPath);
const refPages = parseOgg(refBuf);   // throws on any CRC mismatch
check(refPages.length > 0, `reference parsed, ${refPages.length} pages, all CRCs match our CRC implementation`);

const first = refPages[0];
const last = refPages[refPages.length - 1];
check(first.headerType === 2, 'reference first page has BOS flag');
check(last.headerType === 4, 'reference last page has EOS flag');

const refPackets = refPages.flatMap(p => p.packets);
check(refPackets[0].subarray(0, 8).toString('ascii') === 'OpusHead', 'packet 1 is OpusHead');
check(refPackets[1].subarray(0, 8).toString('ascii') === 'OpusTags', 'packet 2 is OpusTags');
const refAudio = refPackets.slice(2);
console.log(`        (reference: ${refAudio.length} audio packets, final granule ${last.granule})`);

console.log('[2/5] Re-muxing the same packets through the ported OggOpusMuxer…');
const outBufChunks = [];
const muxer = new OggOpusMuxer({ write: c => outBufChunks.push(Buffer.from(c)) }, refPages[0].serial);
muxer.writeHeader(refPackets[0]);   // real OpusHead (carries pre-skip), like the app uses csd-0
muxer.writeTags();
for (const p of refAudio) muxer.writePacket(p);
muxer.finish();

console.log('[3/5] Re-parsing remuxed file + structural invariants…');
const outBuf = Buffer.concat(outBufChunks);
fs.writeFileSync(outPath, outBuf);
const outPages = parseOgg(outBuf);  // CRC re-verified again on every page

check(outPages[0].headerType === 2, 'remux first page has BOS flag');
check(outPages[outPages.length - 1].headerType === 4, 'remux last page has EOS flag');
check(outPages.every(p => p.nSegs <= 255), 'every page has ≤ 255 segments');
check(outPages.every(p => p.bodySize <= 65025), 'every page body ≤ 65025 bytes (Ogg limit)');
check(outPages.every((p, i) => i === 0 || p.granule >= outPages[i - 1].granule), 'granule positions are monotone');
// ffmpeg end-trims its last frame (granule = decoded − preskip − trim); the app's
// MediaCodec encoder emits no padded tail, so its exact N×960 accounting is
// correct per RFC 7845. Allow up to (pre-skip + one frame) of divergence.
const preskip = refPackets[0].length >= 12 ? refPackets[0].readUInt16LE(10) : 0;
const granuleSlack = BigInt(preskip + 960);
const granuleDelta = outPages[outPages.length - 1].granule - last.granule;
check(granuleDelta >= 0n && granuleDelta <= granuleSlack,
  `final granule within tolerance of reference (${outPages[outPages.length - 1].granule} vs ${last.granule}, slack ${granuleSlack})`);

console.log('[4/5] Byte-level logical stream comparison…');
const outPackets = outPages.flatMap(p => p.packets);
const outAudio = outPackets.slice(2);
check(Buffer.compare(Buffer.concat(refAudio), Buffer.concat(outAudio)) === 0,
  `audio packet stream identical to reference (${outAudio.length} packets)`);
// OpusHead must be byte-identical (it carries the encoder pre-skip). OpusTags
// is intentionally our own minimal "Delivra" tag, not ffmpeg's — validate the
// structure (magic + vendor length + zero comments) instead of byte equality.
check(outPackets[0].equals(refPackets[0]), 'OpusHead carried verbatim (pre-skip preserved)');
const tags = outPackets[1];
const vendorLen = tags.readUInt32LE(8);
check(tags.subarray(0, 8).toString('ascii') === 'OpusTags' &&
      8 + 4 + vendorLen + 4 === tags.length, `OpusTags structurally valid (vendor "${tags.subarray(12, 12 + vendorLen).toString('utf-8')}", zero comments)`);

console.log('[5/5] External strict-parser checks (ffprobe / full decode)…');
const probe = (f) => JSON.parse(execFileSync('ffprobe', [
  '-v', 'error', '-print_format', 'json',
  '-show_entries', 'format=format_name,duration',
  '-show_entries', 'stream=codec_name,sample_rate,channels',
  f
]).toString());
const refMeta = probe(refPath);
const outMeta = probe(outPath);
console.log(`        reference: ${JSON.stringify(refMeta.format)} ${JSON.stringify(refMeta.streams)}`);
console.log(`        remuxed:   ${JSON.stringify(outMeta.format)} ${JSON.stringify(outMeta.streams)}`);

check(outMeta.format.format_name.includes('ogg'), 'ffprobe: remuxed recognized as ogg');
check(outMeta.streams[0]?.codec_name === 'opus', 'ffprobe: remuxed codec is opus');
check(outMeta.streams[0]?.sample_rate === '48000' && outMeta.streams[0]?.channels === 1, 'ffprobe: 48 kHz mono');
const dRef = parseFloat(refMeta.format.duration), dOut = parseFloat(outMeta.format.duration);
check(Math.abs(dRef - dOut) < 0.05, `durations match within 50 ms (ref ${dRef}s vs remux ${dOut}s)`);

// Full decode — any container/codec corruption shows up as errors here.
let decodeOk = true, decodeErr = '';
try {
  execFileSync('ffmpeg', ['-v', 'error', '-i', outPath, '-f', 'null', '-'], { stdio: 'pipe' });
} catch (e) {
  decodeOk = false;
  decodeErr = e.stderr?.toString().slice(0, 500) || e.message;
}
check(decodeOk, 'ffmpeg full decode of remuxed file completes with zero errors', decodeErr);

console.log(failures === 0 ? '\nALL CHECKS PASSED ✅' : `\n${failures} CHECK(S) FAILED ❌`);
process.exit(failures === 0 ? 0 : 1);
