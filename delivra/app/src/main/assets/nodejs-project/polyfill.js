import crypto from 'crypto';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

if (!globalThis.crypto) {
  globalThis.crypto = crypto.webcrypto;
}

const engineDir = path.dirname(fileURLToPath(import.meta.url));

// Android app sandboxes have no writable /tmp. Baileys media encryption writes
// <tmp>/document<id>-enc via os.tmpdir(); on nodejs-mobile the TMPDIR env var
// is NOT reliably visible to Node's internal safeGetenv() (os.tmpdir() reads
// the native environ, not process.env). Without a guaranteed-writable dir every
// attachment upload dies with ENOENT -> "Media upload failed on all hosts".
//
// Resolve the first candidate that actually exists and is writable:
//   1. TMPDIR env (set natively in native-lib.cpp before node::Start())
//   2. app cache dir derived from this file's location (files/nodejs-project -> ../../cache)
//   3. .tmp inside the engine dir (last resort, always app-writable)
function resolveWritableTmpDir() {
  const candidates = [
    process.env.TMPDIR,
    path.resolve(engineDir, '../../cache'),
    path.join(engineDir, '.tmp'),
  ].filter(Boolean);
  for (const dir of candidates) {
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.accessSync(dir, fs.constants.W_OK);
      return dir;
    } catch (_) { /* try next candidate */ }
  }
  return null;
}

const delivraTmp = resolveWritableTmpDir();
if (delivraTmp) {
  try { process.env.TMPDIR = delivraTmp; } catch (_) {}
}
// Consumed by the patched Baileys getTmpFilesDirectory() in
// Utils/messages-media.js and Utils/business.js — see
// node_modules/.patch-delivra-tmpdir.md. This bypasses os.tmpdir()/env
// propagation entirely, so media uploads work regardless of env quirks.
globalThis.__DELIVRA_TMP__ = delivraTmp;

let rawTmpdir = '(threw)';
try { rawTmpdir = os.tmpdir(); } catch (e) { rawTmpdir = 'ERR: ' + e.message; }
console.log('[Delivra Node] os.tmpdir() =', rawTmpdir);
console.log('[Delivra Node] effective temp dir =', delivraTmp || '(NONE FOUND — media uploads will fail)');
