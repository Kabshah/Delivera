/**
 * minify-engine.mjs — postinstall patching & size optimization for the Delivra Node engine.
 *
 * 1. Delivra Patches:
 *    - Baileys TMPDIR patch (messages-media.js, business.js): Android sandboxes
 *      have no writable /tmp; redirects to guaranteed-writable app cacheDir.
 *    - Baileys stream finish flush (messages-media.js): waits for encFileWriteStream
 *      to finish before upload begins (prevents truncated upload errors).
 *    - Baileys Axios buffer upload (messages-media.js): reads encrypted file into
 *      Buffer and sends with explicit Content-Length instead of createReadStream,
 *      preventing chunked upload failures on mobile networks to mmg.whatsapp.net.
 *
 * 2. Minification:
 *    - Terser (-c -m, ESM mode) shrinks large JS files ~50-60%.
 *
 * Runs via "postinstall" so `npm install --production` produces identical,
 * fully-patched output locally and on GitHub Actions CI.
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

// ── Step 1: Apply Delivra Baileys Patches (ALWAYS RUNS) ───────────────────────
function applyDelivraPatches() {
  console.log('[delivra-patch] Verifying and applying Baileys runtime patches...');
  const mediaFile = join(baileys, 'lib', 'Utils', 'messages-media.js');
  const businessFile = join(baileys, 'lib', 'Utils', 'business.js');

  if (existsSync(mediaFile)) {
    let content = readFileSync(mediaFile, 'utf-8');
    let modified = false;

    // 1. DELIVRA TMPDIR patch
    if (!content.includes('__DELIVRA_TMP__')) {
      content = content.replace(
        'const getTmpFilesDirectory = () => tmpdir();',
        'const getTmpFilesDirectory = () => globalThis.__DELIVRA_TMP__ || tmpdir();'
      );
      modified = true;
      console.log('[delivra-patch] -> Patched messages-media.js getTmpFilesDirectory');
    }

    // 2. Stream flush before return in encryptedStream (prevents reading half-written enc file)
    if (!content.includes('await Promise.all([once(encFileWriteStream')) {
      const targetStreamEnd = `        encFileWriteStream.write(mac);
        encFileWriteStream.end();
        originalFileStream?.end?.();
        stream.destroy();`;

      const patchedStreamEnd = `        encFileWriteStream.write(mac);
        encFileWriteStream.end();
        originalFileStream?.end?.();
        await Promise.all([
            once(encFileWriteStream, 'finish'),
            originalFileStream ? once(originalFileStream, 'finish') : Promise.resolve()
        ]);
        stream.destroy();`;

      if (content.includes(targetStreamEnd)) {
        content = content.replace(targetStreamEnd, patchedStreamEnd);
        modified = true;
        console.log('[delivra-patch] -> Patched messages-media.js stream flush (LF)');
      } else {
        const targetCRLF = targetStreamEnd.replace(/\n/g, '\r\n');
        const patchedCRLF = patchedStreamEnd.replace(/\n/g, '\r\n');
        if (content.includes(targetCRLF)) {
          content = content.replace(targetCRLF, patchedCRLF);
          modified = true;
          console.log('[delivra-patch] -> Patched messages-media.js stream flush (CRLF)');
        }
      }
    }

    // 3. Axios upload as Buffer with exact Content-Length instead of createReadStream
    if (content.includes('createReadStream(filePath)')) {
      const targetAxios = `            try {
                const body = await axios.post(url, createReadStream(filePath), {
                    ...options,
                    maxRedirects: 0,
                    headers: {
                        ...(options.headers || {}),
                        'Content-Type': 'application/octet-stream',
                        Origin: DEFAULT_ORIGIN
                    },`;

      const patchedAxios = `            try {
                const fileBuffer = await fs.readFile(filePath);
                const body = await axios.post(url, fileBuffer, {
                    ...options,
                    maxRedirects: 0,
                    headers: {
                        ...(options.headers || {}),
                        'Content-Type': 'application/octet-stream',
                        'Content-Length': fileBuffer.length,
                        Origin: DEFAULT_ORIGIN
                    },`;

      if (content.includes(targetAxios)) {
        content = content.replace(targetAxios, patchedAxios);
        modified = true;
        console.log('[delivra-patch] -> Patched messages-media.js axios upload as Buffer (LF)');
      } else {
        const targetCRLF = targetAxios.replace(/\n/g, '\r\n');
        const patchedCRLF = patchedAxios.replace(/\n/g, '\r\n');
        if (content.includes(targetCRLF)) {
          content = content.replace(targetCRLF, patchedCRLF);
          modified = true;
          console.log('[delivra-patch] -> Patched messages-media.js axios upload as Buffer (CRLF)');
        }
      }
    }

    if (modified) {
      writeFileSync(mediaFile, content, 'utf-8');
      console.log('[delivra-patch] Saved patched messages-media.js');
    } else {
      console.log('[delivra-patch] messages-media.js already fully patched');
    }
  }

  if (existsSync(businessFile)) {
    let content = readFileSync(businessFile, 'utf-8');
    if (!content.includes('__DELIVRA_TMP__')) {
      const target = "const filePath = join(tmpdir(), 'img' + generateMessageIDV2());";
      const replacement = "const filePath = join(globalThis.__DELIVRA_TMP__ || tmpdir(), 'img' + generateMessageIDV2());";
      if (content.includes(target)) {
        content = content.replace(target, replacement);
        writeFileSync(businessFile, content, 'utf-8');
        console.log('[delivra-patch] Saved patched business.js');
      }
    } else {
      console.log('[delivra-patch] business.js already fully patched');
    }
  }
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
