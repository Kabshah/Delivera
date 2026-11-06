/**
 * minify-engine.mjs — postinstall patching & size optimization for the Delivra Node engine.
 *
 * 1. Delivra Patches (applied to PRISTINE npm sources, i.e. before minification):
 *    - Baileys TMPDIR patch (messages-media.js, business.js): Android sandboxes
 *      have no writable /tmp; redirects to guaranteed-writable app cacheDir.
 *    - Baileys stream finish flush (messages-media.js): waits for encFileWriteStream
 *      to finish before upload begins (prevents truncated upload errors).
 *      NOTE: Baileys >= 6.7.24 has this upstream — the patch auto-detects it.
 *    - Baileys Axios buffer upload (messages-media.js): reads encrypted file into
 *      Buffer and sends with explicit Content-Length instead of createReadStream,
 *      preventing chunked upload failures on mobile networks to mmg.whatsapp.net.
 *    - Baileys mediaConn poison guard (messages-send.js): refreshMediaConn caches
 *      the fetch promise in the `mediaConn` closure variable. If the media_conn
 *      iq query EVER fails, the rejected promise stays cached and `await
 *      mediaConn` throws on every later call — every media upload then fails for
 *      the rest of the socket session while text keeps working. The guard resets
 *      the cache on rejection so the next call re-fetches.
 *
 * 2. VERIFICATION (always enforced): after patching, every required patch must
 *    report "applied" or "already present". A patch that matches NEITHER its
 *    marker NOR its target (i.e. Baileys upgraded and the exact strings drifted)
 *    makes this script exit non-zero — npm install fails and CI goes red, so a
 *    silently unpatched (media-breaking) engine can never ship again.
 *
 * 3. Minification:
 *    - Terser (-c -m, ESM mode) shrinks large JS files ~50-60%.
 *    - Files containing a DELIVRA patch marker are NEVER minified, so patch
 *      markers stay text-verifiable at any time (and dev tree == CI tree for
 *      those files). Currently that only costs ~20KB (messages-send.js).
 *
 * Runs via "postinstall" so `npm install --production` produces identical,
 * fully-patched output locally and on GitHub Actions CI. CI additionally runs
 * `node tools/minify-engine.mjs --patch-only` as a second gate.
 */

import { execFileSync } from 'child_process';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'fs';
import { join } from 'path';
import { fileURLToPath } from 'url';

const here = fileURLToPath(new URL('.', import.meta.url));
const engineDir = join(here, '..', 'app', 'src', 'main', 'assets', 'nodejs-project');
const baileys = join(engineDir, 'node_modules', '@whiskeysockets', 'baileys');
const marker = join(engineDir, 'node_modules', '.delivra-minified');

if (!existsSync(baileys)) {
  console.error('[minify-engine] baileys not installed — run npm install first');
  process.exit(1);
}

// ── Patch helpers ─────────────────────────────────────────────────────────────
// Every patch is { name, file, required, marker, target, replacement } where:
//   marker     = string that proves the patch (or its upstream equivalent) is in place
//   target     = exact pristine string to replace (LF; CRLF variant tried too)
//   replacement= what to replace it with
// Status: 'applied' (patched now) | 'present' (marker already found) |
//         'upstream' (upstream code already provides the behaviour) |
//         'not-found' (NEITHER marker NOR target — hard failure if required)

const CRLF = (s) => s.replace(/\n/g, '\r\n');

function applyPatch(content, patch) {
  if (content.includes(patch.marker)) return { content, status: 'present' };
  if (patch.upstreamMarker && content.includes(patch.upstreamMarker)) return { content, status: 'upstream' };
  if (content.includes(patch.target)) {
    return { content: content.replace(patch.target, patch.replacement), status: 'applied' };
  }
  if (content.includes(CRLF(patch.target))) {
    return { content: content.replace(CRLF(patch.target), CRLF(patch.replacement)), status: 'applied' };
  }
  return { content, status: 'not-found' };
}

const PATCHES = [
  // ── 1. TMPDIR: Baileys writes encrypted media to os.tmpdir() → /tmp → ENOENT on Android
  {
    name: '1. TMPDIR (messages-media.js)',
    file: join(baileys, 'lib', 'Utils', 'messages-media.js'),
    required: true,
    marker: '__DELIVRA_TMP__',
    target: 'const getTmpFilesDirectory = () => tmpdir();',
    replacement:
      '// DELIVRA PATCH (tmpdir): os.tmpdir() resolves to /tmp on Android sandboxes\n' +
      '// (ENOENT on write). Prefer the guaranteed-writable dir injected by polyfill.js.\n' +
      'const getTmpFilesDirectory = () => globalThis.__DELIVRA_TMP__ || tmpdir();',
  },
  {
    name: '1b. TMPDIR (business.js)',
    file: join(baileys, 'lib', 'Utils', 'business.js'),
    required: false, // file/pattern may shift across Baileys versions
    marker: '__DELIVRA_TMP__',
    target: "const filePath = join(tmpdir(), 'img' + generateMessageIDV2());",
    replacement:
      "const filePath = join(globalThis.__DELIVRA_TMP__ || tmpdir(), 'img' + generateMessageIDV2());",
  },
  // ── 2. Stream flush before upload (upstream in Baileys >= 6.7.24 — auto-detected)
  {
    name: '2. encrypted-stream flush (messages-media.js)',
    file: join(baileys, 'lib', 'Utils', 'messages-media.js'),
    required: true,
    marker: "once(encFileWriteStream, 'finish')",
    upstreamMarker: "once(encFileWriteStream, 'finish')",
    target:
      '        encFileWriteStream.write(mac);\n' +
      '        encFileWriteStream.end();\n' +
      '        originalFileStream?.end?.();\n' +
      '        stream.destroy();',
    replacement:
      '        encFileWriteStream.write(mac);\n' +
      '        encFileWriteStream.end();\n' +
      '        originalFileStream?.end?.();\n' +
      '        await Promise.all([\n' +
      "            once(encFileWriteStream, 'finish'),\n" +
      "            originalFileStream ? once(originalFileStream, 'finish') : Promise.resolve()\n" +
      '        ]);\n' +
      '        stream.destroy();',
  },
  // ── 3. Upload as Buffer with explicit Content-Length (mobile-network reliability)
  {
    name: '3. axios Buffer upload (messages-media.js)',
    file: join(baileys, 'lib', 'Utils', 'messages-media.js'),
    required: true,
    marker: "'Content-Length': fileBuffer.length",
    target:
      '            try {\n' +
      '                const body = await axios.post(url, createReadStream(filePath), {\n' +
      '                    ...options,\n' +
      '                    maxRedirects: 0,\n' +
      '                    headers: {\n' +
      '                        ...(options.headers || {}),\n' +
      "                        'Content-Type': 'application/octet-stream',\n" +
      '                        Origin: DEFAULT_ORIGIN\n' +
      '                    },',
    replacement:
      '            try {\n' +
      '                const fileBuffer = await fs.readFile(filePath);\n' +
      '                const body = await axios.post(url, fileBuffer, {\n' +
      '                    ...options,\n' +
      '                    maxRedirects: 0,\n' +
      '                    headers: {\n' +
      '                        ...(options.headers || {}),\n' +
      "                        'Content-Type': 'application/octet-stream',\n" +
      "                        'Content-Length': fileBuffer.length,\n" +
      '                        Origin: DEFAULT_ORIGIN\n' +
      '                    },',
  },
  // ── 4. mediaConn poison guard (THE all-media-fails-while-text-works bug)
  {
    name: '4. mediaConn poison guard (messages-send.js)',
    file: join(baileys, 'lib', 'Socket', 'messages-send.js'),
    required: true,
    marker: 'mediaConn = undefined;',
    target:
      '                return node;\n' +
      '            })();',
    replacement:
      '                return node;\n' +
      '            })().catch(error => {\n' +
      '                // DELIVRA PATCH (mediaConn poison guard): a failed media_conn\n' +
      '                // query used to stay cached as a rejected promise forever, so\n' +
      '                // EVERY later media upload failed for the rest of the socket\n' +
      '                // session while text kept working. Reset on failure so the\n' +
      '                // next call re-fetches.\n' +
      '                mediaConn = undefined;\n' +
      '                throw error;\n' +
      '            });',
  },
  // ── 5. Waveform passthrough (playable voice notes: Kotlin supplies the
  //    waveform; Baileys' own audio-decode path can't decode Opus and isn't
  //    installed, so it would stomp the provided waveform back to undefined)
  {
    name: '5. waveform passthrough (messages.js)',
    file: join(baileys, 'lib', 'Utils', 'messages.js'),
    required: true,
    marker: 'uploadData.waveform = uploadData.waveform ?? await getAudioWaveform',
    target:
      '                    uploadData.waveform = await getAudioWaveform(originalFilePath, logger);',
    replacement:
      '                    // DELIVRA PATCH (waveform passthrough): Kotlin computes the\n' +
      '                    // waveform with the platform Opus decoder (see\n' +
      '                    // VoiceNoteRecorder.computeWaveform) because the Node-side\n' +
      '                    // audio-decode path cannot decode OGG/Opus. Keep it when\n' +
      '                    // present instead of overwriting with undefined.\n' +
      '                    uploadData.waveform = uploadData.waveform ?? await getAudioWaveform(originalFilePath, logger);',
  },
];

// ── Step 1: Apply Delivra patches + verify ───────────────────────────────────
function applyDelivraPatches() {
  console.log('[delivra-patch] Verifying and applying Baileys runtime patches...');
  const fileCache = new Map(); // path -> content (patches 1/2/3 share messages-media.js)

  const statuses = [];
  for (const patch of PATCHES) {
    if (!existsSync(patch.file)) {
      statuses.push({ patch, status: 'not-found', missingFile: true });
      continue;
    }
    let content = fileCache.has(patch.file)
      ? fileCache.get(patch.file)
      : readFileSync(patch.file, 'utf-8');
    const result = applyPatch(content, patch);
    if (result.status === 'applied') {
      writeFileSync(patch.file, result.content, 'utf-8');
      console.log(`[delivra-patch] -> Patched ${patch.name}`);
    } else if (result.status === 'present') {
      console.log(`[delivra-patch] -> ${patch.name}: already patched`);
    } else if (result.status === 'upstream') {
      console.log(`[delivra-patch] -> ${patch.name}: behaviour present upstream (no patch needed)`);
    }
    fileCache.set(patch.file, result.content);
    statuses.push({ patch, status: result.status });
  }

  // ── Hard verification: never ship a silently unpatched engine ──────────────
  const broken = statuses.filter(
    (s) => s.patch.required && (s.status === 'not-found' || s.missingFile)
  );
  if (broken.length > 0) {
    console.error('');
    console.error('[delivra-patch] ═══ PATCH VERIFICATION FAILED ═══');
    for (const b of broken) {
      console.error(
        `[delivra-patch] REQUIRED patch missing: ${b.patch.name} ` +
          `(${b.missingFile ? 'file not found' : 'neither marker nor target matched'})`
      );
    }
    console.error('[delivra-patch] The installed Baileys version likely changed its source.');
    console.error('[delivra-patch] Update the targets above for the new version — shipping');
    console.error('[delivra-patch] an unpatched engine BREAKS ALL MEDIA SENDS on device.');
    console.error('');
    process.exit(1);
  }
  console.log('[delivra-patch] All required patches verified ✓');
}

applyDelivraPatches();

if (process.argv.includes('--patch-only')) {
  process.exit(0);
}

// ── Step 2: Minification ──────────────────────────────────────────────────────
if (existsSync(marker)) {
  console.log('[minify-engine] Minification already done — skipping');
  process.exit(0);
}

const terserBin = join(engineDir, 'node_modules', 'terser', 'bin', 'terser');

function collectJs(dir, out) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) collectJs(p, out);
    else if (entry.name.endsWith('.js') && !entry.name.endsWith('.min.js') && statSync(p).size > 30 * 1024) out.push(p);
  }
  return out;
}

let before = 0, after = 0;
const targets = collectJs(baileys, []);
for (const file of targets) {
  const sizeBefore = statSync(file).size;
  // Never minify files carrying Delivra patches — their markers must remain
  // text-verifiable, and terser would rename the patched closure variables.
  const content = readFileSync(file, 'utf-8');
  if (content.includes('DELIVRA PATCH')) {
    console.log(`[minify-engine] skipped ${file.replace(baileys + '/', '')}: carries DELIVRA patch`);
    after += sizeBefore; before += sizeBefore;
    continue;
  }
  const tmp = file + '.min.tmp';
  try {
    execFileSync(process.execPath, [terserBin, file, '--compress', '--mangle', '--module', '--output', tmp], { stdio: 'pipe' });
    if (existsSync(tmp) && statSync(tmp).size > 0 && statSync(tmp).size < sizeBefore) {
      const { unlinkSync, copyFileSync } = await import('fs');
      copyFileSync(tmp, file);
      unlinkSync(tmp);
      after += statSync(file).size;
      before += sizeBefore;
    } else {
      if (existsSync(tmp)) (await import('fs')).unlinkSync(tmp);
      after += sizeBefore; before += sizeBefore;
    }
  } catch (e) {
    console.warn(`[minify-engine] skipped ${file}: ${e.message?.split('\n')[0]}`);
    if (existsSync(tmp)) { try { (await import('fs')).unlinkSync(tmp); } catch {} }
    after += sizeBefore; before += sizeBefore;
  }
}

console.log(`[minify-engine] ${targets.length} files: ${(before / 1048576).toFixed(2)} MB -> ${(after / 1048576).toFixed(2)} MB`);
writeFileSync(marker, new Date().toISOString() + '\n');
