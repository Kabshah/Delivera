/**
 * crash-guard.js — MUST be the first import in index.js.
 *
 * Why this file exists (LIVE-CONFIRMED 2026-11-13 AND 2026-11-15 on the
 * Galaxy A03): Android/Samsung evicts individual files from the engine's
 * cacheDir tree at any time. One evicted node_modules file (both live cases:
 * Baileys' lib/Socket/index.js, with the version marker + all sentinels
 * intact) fails the whole ESM import graph during bootstrap — BEFORE
 * index.js's own process.on('uncaughtException') handler could ever register.
 * Node's default fatal handler then calls process.exit(1), which in
 * nodejs-mobile kills the ENTIRE Android app process with NO tombstone,
 * NO crash-buffer entry and NO data_app_crash (crash_dump can't attach —
 * "No child processes"). Because the crash happens before any row status
 * write, the message stayed PENDING and the backstop + every app open
 * re-dispatched it: all-day crash loop until the 6h grace window failed the
 * row (the "day 2 is always broken" incident).
 *
 * The .boot-ok contract with SchedulerService.setupNodeRuntime:
 *   - This module UNLINKS .boot-ok at import (a fresh start is not yet
 *     proven healthy) and index.js calls markBootOk() only after the ENTIRE
 *     ESM import graph has executed.
 *   - If bootstrap linking fails while .boot-ok is absent (the normal case —
 *     we just invalidated it), this is a broken tree: log loudly, then
 *     process.exit(1). Yes, the app process may die with it — but the NEXT
 *     service start now sees .boot-ok missing, force-refreshes the tree and
 *     boots healthy: ONE visible crash + self-heal instead of the old
 *     invisible all-day loop.
 *   - If an exception arrives AFTER boot succeeded (.boot-ok present), it is
 *     a late runtime error, not tree corruption: log it and stay alive so a
 *     single command bug can't take the runtime down for the whole session.
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

// Engine dir = this file's directory (crash-guard.js sits next to index.js).
const engineDir = path.dirname(fileURLToPath(import.meta.url));
const BOOT_OK = path.join(engineDir, '.boot-ok');

// Invalidate at import: this run of the engine has not proven itself yet.
// If the previous run died for any reason, a stale marker would make the
// Kotlin side trust a broken tree (it only re-extracts when the marker is
// missing).
try { fs.unlinkSync(BOOT_OK); } catch (_) { /* already gone */ }

function bootOkAbsent() {
  try { return !fs.existsSync(BOOT_OK); } catch (_) { return true; }
}

process.on('uncaughtException', (err) => {
  const detail = (err?.stack || err?.message || String(err));
  if (bootOkAbsent()) {
    // Bootstrap failure (evicted file, corrupted tree) — mark + exit so the
    // next service start re-extracts and heals. Small delay lets the native
    // stdout pipe logger flush the trace before the process tears down.
    console.error('[Delivra Node CRASH] BOOT BROKEN — .boot-ok absent, forcing exit for tree refresh. Cause: ' + detail);
    setTimeout(() => process.exit(1), 500);
  } else {
    // Late runtime error after a healthy boot — log + stay alive (previous
    // index.js behavior); Kotlin re-dispatches failed rows itself.
    console.error('[Delivra Node CRASH] uncaughtException: ' + detail);
  }
});

process.on('unhandledRejection', (reason) => {
  // Rejections don't abort Node by themselves, but log + invalidate so a
  // repeated pattern (e.g. the 428 wedge class) stays diagnosable.
  console.error('[Delivra Node CRASH] unhandledRejection:', reason);
});

/** Called by index.js after the full ESM graph + entry body ran clean. */
export function markBootOk() {
  try {
    fs.writeFileSync(BOOT_OK, String(Date.now()));
  } catch (e) {
    // Never crash the engine over bookkeeping — but say it loudly.
    console.error('[Delivra Node] Could not write .boot-ok: ' + (e?.message || e));
  }
}

console.log('[Delivra Node] crash-guard armed (handlers registered before any engine import)');
