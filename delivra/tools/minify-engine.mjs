/**
 * minify-engine.mjs — postinstall size optimization for the Delivra Node engine.
 *
 * Baileys ships machine-generated JavaScript that is enormous in source form:
 *   - WAProto/index.js  (~8 MB of generated protobuf code)
 *   - lib folder        (socket/protocol code, some files >100 KB)
 * None of it is hand-edited except the Delivra tmpdir patch, which is plain
 * logic that survives a semantics-preserving minify. Terser (-c -m, ESM mode)
 * shrinks these ~50-60%, cutting both APK assets and on-device storage.
 *
 * Idempotent: writes .delivra-minified marker; skips if already done.
 * Runs via "postinstall" so `npm install --production` produces identical
 * output locally and on CI.
 */

import { execFileSync } from 'child_process';
import { existsSync, readdirSync, statSync, writeFileSync } from 'fs';
import { join } from 'path';
import { fileURLToPath } from 'url';

const here = fileURLToPath(new URL('.', import.meta.url));
const engineDir = join(here, '..', 'app', 'src', 'main', 'assets', 'nodejs-project');
const baileys = join(engineDir, 'node_modules', '@whiskeysockets', 'baileys');
const marker = join(engineDir, 'node_modules', '.delivra-minified');

if (existsSync(marker)) {
  console.log('[minify-engine] already done — skipping');
  process.exit(0);
}
if (!existsSync(baileys)) {
  console.error('[minify-engine] baileys not installed — run npm install first');
  process.exit(1);
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
      const { renameSync, unlinkSync, copyFileSync } = await import('fs');
      copyFileSync(tmp, file);
      unlinkSync(tmp);
      after += statSync(file).size;
      before += sizeBefore;
    } else {
      if (existsSync(tmp)) unlinkSync(tmp);
      after += sizeBefore; before += sizeBefore;
    }
  } catch (e) {
    // Minify failed for this file — keep original, never break the install.
    console.warn(`[minify-engine] skipped ${file}: ${e.message?.split('\n')[0]}`);
    if (existsSync(tmp)) { try { (await import('fs')).unlinkSync(tmp); } catch {} }
    after += sizeBefore; before += sizeBefore;
  }
}

console.log(`[minify-engine] ${targets.length} files: ${(before / 1048576).toFixed(2)} MB -> ${(after / 1048576).toFixed(2)} MB`);
writeFileSync(marker, new Date().toISOString() + '\n');
