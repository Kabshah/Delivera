/**
 * test-media-pipeline.mjs — local end-to-end smoke test for the Delivra media pipeline.
 *
 * Runs the EXACT shipped Baileys code path that every media send uses on device:
 *   prepareWAMessageMedia (encryptedStream → temp files via __DELIVRA_TMP__)
 *     → sharp thumbnail (image) / music-metadata duration (voice note)
 *     → getWAUploadToServer (axios Buffer upload, patch 3)
 * against a local HTTPS server with a SELF-SIGNED certificate.
 *
 * The self-signed cert is the discriminator: axios only succeeds when the Delivra
 * fetchAgent (rejectUnauthorized:false — the same wiring used for uploads to
 * mmg.whatsapp.net on device) is actually honored by the upload request.
 *
 * Also demonstrates the Baileys "mediaConn poison" bug (see minify-engine.mjs
 * Patch 4): one failed media_conn query used to poison the cached promise so
 * every later media upload failed while text kept working.
 *
 * Usage:
 *   node tools/test-media-pipeline.mjs
 *   node tools/test-media-pipeline.mjs --engine <path to nodejs-project dir>
 *
 * Exit code 0 = all checks passed. Run this before every release.
 */

import { execFileSync } from 'child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs';
import https from 'https';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

// ── Engine dir resolution ─────────────────────────────────────────────────────
const scriptDir = path.dirname(fileURLToPath(import.meta.url));
let engineDir = path.join(scriptDir, '..', 'app', 'src', 'main', 'assets', 'nodejs-project');
const engineArgIdx = process.argv.indexOf('--engine');
if (engineArgIdx !== -1 && process.argv[engineArgIdx + 1]) {
  engineDir = path.resolve(process.argv[engineArgIdx + 1]);
}
const baileysDir = path.join(engineDir, 'node_modules', '@whiskeysockets', 'baileys');

console.log(`[test] engine dir: ${engineDir}`);
for (const f of ['lib/Utils/messages-media.js', 'lib/Utils/messages.js', 'lib/Socket/messages-send.js']) {
  if (!existsSync(path.join(baileysDir, f))) {
    console.error(`[test] FATAL: ${f} not found — is node_modules installed?`);
    process.exit(1);
  }
}

// Patch / runtime report
const mediaJs = readFileSync(path.join(baileysDir, 'lib/Utils/messages-media.js'), 'utf-8');
const sendJs = readFileSync(path.join(baileysDir, 'lib/Socket/messages-send.js'), 'utf-8');
const baileysPkg = JSON.parse(readFileSync(path.join(baileysDir, 'package.json'), 'utf-8'));
console.log(`[test] Baileys version: ${baileysPkg.version}`);
console.log(`[test] patch 1 (TMPDIR):          ${mediaJs.includes('__DELIVRA_TMP__') ? 'present' : 'MISSING'}`);
console.log(`[test] patch 3 (axios Buffer):    ${mediaJs.includes("'Content-Length': fileBuffer.length") ? 'present' : 'MISSING'}`);
console.log(`[test] patch 4 (mediaConn guard): ${sendJs.includes('mediaConn = undefined;') ? 'present' : 'MISSING'}`);
const bundledVersion = JSON.parse(readFileSync(path.join(baileysDir, 'lib', 'Defaults', 'baileys-version.json'), 'utf-8')).version;
console.log(`[test] bundled WA version: ${bundledVersion.join('.')}`);

// Dynamic imports from the shipped tree (deep file URLs — relative imports inside
// the package resolve normally).
const messagesUrl = pathToFileURL(path.join(baileysDir, 'lib/Utils/messages.js')).href;
const { prepareWAMessageMedia } = await import(messagesUrl);
const { getWAUploadToServer } = await import(
  pathToFileURL(path.join(baileysDir, 'lib/Utils/messages-media.js')).href
);

// ── Fake WhatsApp upload server (self-signed TLS) ─────────────────────────────
const tmp = mkdtempSync(path.join(os.tmpdir(), 'delivra-mediatest-'));
let server = null;
let port = 0;
let lastUpload = null;

async function startUploadServer() {
  // Generate a self-signed cert with openssl (bundled with Git Bash / most dev boxes)
  const keyFile = path.join(tmp, 'key.pem');
  const certFile = path.join(tmp, 'cert.pem');
  try {
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
      '-subj', '/CN=127.0.0.1',
      '-keyout', keyFile, '-out', certFile,
    ], { stdio: 'pipe' });
  } catch (e) {
    console.error('[test] FATAL: openssl unavailable — cannot build the TLS upload server: ' + e.message);
    process.exit(1);
  }

  server = https.createServer(
    { key: readFileSync(keyFile), cert: readFileSync(certFile) },
    (req, res) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        const body = Buffer.concat(chunks);
        lastUpload = {
          url: req.url,
          contentLengthHeader: req.headers['content-length'],
          bodyLength: body.length,
          chunked: req.headers['transfer-encoding'] === 'chunked',
        };
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ url: 'https://mmg.test/fake-url', direct_path: '/fake-path' }));
      });
    }
  );
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  port = server.address().port;
  console.log(`[test] upload server: https://127.0.0.1:${port} (self-signed)`);
}

// refreshMediaConn stub — feeds our local host exactly like the real iq query does
const refreshMediaConnStub = async () => ({
  hosts: [{ hostname: `127.0.0.1:${port}`, maxContentLengthBytes: 1024 * 1024 * 1024 }],
  auth: 'test-auth',
  ttl: 3600,
  fetchDate: new Date(),
});

const logger = { debug: () => {}, info: () => {}, warn: () => {}, error: () => {}, child: () => logger };

// ── Minimal test fixtures ─────────────────────────────────────────────────────
// 1×1 red PNG (no dependency needed)
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
// Minimal valid PDF
const PDF_MIN = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');
// Small WAV (44-byte header + 2s of 8kHz silence) — music-metadata parses it.
// NOTE: must be ≥1s because AudioMessage.seconds is a protobuf uint32 (0.2s → 0).
function makeWav() {
  const sampleRate = 8000, seconds = 2, channels = 1, bitsPerSample = 16;
  const dataSize = Math.floor(sampleRate * seconds) * channels * (bitsPerSample / 8);
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + dataSize, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(channels, 22); buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * channels * bitsPerSample / 8, 28);
  buf.writeUInt16LE(channels * bitsPerSample / 8, 32); buf.writeUInt16LE(bitsPerSample, 34);
  buf.write('data', 36); buf.writeUInt32LE(dataSize, 40);
  return buf;
}

// ── Test runner ───────────────────────────────────────────────────────────────
let failures = 0;
function check(name, cond, detail = '') {
  if (cond) console.log(`[test] ✓ ${name}`);
  else { console.error(`[test] ✗ FAIL: ${name}${detail ? ' — ' + detail : ''}`); failures++; }
}

async function runMediaTest(content, label, extraAssertions = (r) => {}) {
  lastUpload = null;
  const upload = getWAUploadToServer(
    { customUploadHosts: [], fetchAgent: new https.Agent({ rejectUnauthorized: false }), logger, options: {} },
    refreshMediaConnStub
  );
  const result = await prepareWAMessageMedia(content, { upload, logger });
  const msgKey = Object.keys(result).find((k) => k.endsWith('Message'));
  const mediaMsg = result[msgKey];
  if (process.env.DELIVRA_TEST_DEBUG) {
    console.log(`[debug] ${label}: keys=${Object.keys(mediaMsg || {}).join(',')}`);
  }
  check(`${label}: message assembled`, !!mediaMsg, `got keys: ${Object.keys(result).join(',')}`);
  check(`${label}: uploaded (url set)`, !!mediaMsg?.url, 'no url — upload failed');
  check(`${label}: directPath set`, !!mediaMsg?.directPath);
  check(`${label}: mediaKey present`, !!mediaMsg?.mediaKey);
  check(
    `${label}: Buffer upload w/ Content-Length (patch 3 + agent honored)`,
    lastUpload?.contentLengthHeader !== undefined && Number(lastUpload?.contentLengthHeader) === lastUpload?.bodyLength && lastUpload?.bodyLength > 0,
    lastUpload ? `header=${lastUpload.contentLengthHeader} body=${lastUpload.bodyLength} chunked=${lastUpload.chunked}` : 'no request reached the server'
  );
  extraAssertions(mediaMsg);
}

await startUploadServer();

// ── 1. Document (PDF) ─────────────────────────────────────────────────────────
await runMediaTest(
  { document: PDF_MIN, mimetype: 'application/pdf', fileName: 'test.pdf' },
  'document(PDF)'
);

// ── 2. Image (PNG, thumbnail path via sharp) ──────────────────────────────────
await runMediaTest({ image: PNG_1X1, mimetype: 'image/png' }, 'image(PNG)', (m) => {
  check('image(PNG): jpegThumbnail generated', !!m?.jpegThumbnail,
    'missing — sharp/jimp could not load (warning-level on device, but unexpected on dev box)');
});

// ── 3. Voice note (PTT audio, duration via music-metadata) ────────────────────
await runMediaTest({ audio: makeWav(), ptt: true, mimetype: 'audio/ogg; codecs=opus' }, 'voiceNote(ptt)', (m) => {
  if (process.env.DELIVRA_TEST_DEBUG) console.log(`[debug] seconds value:`, JSON.stringify(m?.seconds));
  check('voiceNote(ptt): seconds computed', typeof m?.seconds === 'number' && m.seconds >= 1,
    'seconds missing/0 — music-metadata failed or WAV fixture too short (on-device this is caught non-fatally)');
});

// ── 4. mediaConn poison demo (Bug #1 mechanics + Patch 4 fix pattern) ─────────
{
  // UNPATCHED pattern — exactly what pristine messages-send.js does:
  let mediaConn = undefined;
  const query = async () => { throw new Error('Timed Out'); };
  const refreshUnpatched = async (force = false) => {
    const media = await mediaConn;
    if (!media || force) {
      mediaConn = (async () => {
        await query({ tag: 'iq' });
        return { hosts: ['x'] };
      })();
    }
    return mediaConn;
  };
  let unpatchedError = '';
  try { await refreshUnpatched(); } catch (e) { unpatchedError = 'first call failed (expected)'; }
  try { await refreshUnpatched(); } catch { unpatchedError = 'poisoned: every later call throws'; }
  check('poison demo: unpatched pattern permanently broken', unpatchedError.includes('poisoned'),
    unpatchedError || 'second call unexpectedly succeeded');

  // PATCHED pattern — what Patch 4 in minify-engine.mjs injects:
  let mediaConn2 = undefined;
  const refreshPatched = async (force = false) => {
    const media = await mediaConn2;
    if (!media || force) {
      mediaConn2 = (async () => {
        await query({ tag: 'iq' });
        return { hosts: ['x'] };
      })().catch((error) => { mediaConn2 = undefined; throw error; });
    }
    return mediaConn2;
  };
  let patchedRecovers = false;
  try { await refreshPatched(); } catch { /* first fails, expected */ }
  try { await refreshPatched(); } catch { patchedRecovers = false; }
  // Now let the query succeed to prove recovery
  const okQuery = async () => ({ ok: true });
  const refreshOk = async () => {
    const media = await mediaConn2;
    if (!media) {
      mediaConn2 = (async () => { await okQuery(); return { hosts: ['real-wa-host'] }; })()
        .catch((error) => { mediaConn2 = undefined; throw error; });
    }
    return mediaConn2;
  };
  const result = await refreshOk();
  patchedRecovers = Array.isArray(result.hosts);
  check('poison demo: patched pattern recovers on next call', patchedRecovers);
}

server.close();
rmSync(tmp, { recursive: true, force: true });

console.log('');
if (failures === 0) {
  console.log('[test] ALL MEDIA PIPELINE CHECKS PASSED ✓');
} else {
  console.error(`[test] ${failures} check(s) FAILED ✗`);
  process.exit(1);
}
