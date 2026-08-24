/**
 * whatsapp.js — Baileys connection, auth state management, reconnect logic
 *
 * Key requirements from wp_brain:
 * - Pairing code flow (§4.1) — not QR, since both app+WhatsApp are on same phone
 * - Auth-state writes: atomic temp-file + rename to survive process kills (§6.1)
 * - Reconnect with exponential backoff on non-loggedOut disconnect (§6.1)
 * - loggedOut → surface to Kotlin, do NOT auto-retry (§6.1)
 * - Expose connection state to Kotlin via the bridge callback
 *
 * Import notes (confirmed via node -e inspection of the installed package):
 *   rawBaileys.default          → makeWASocket function
 *   rawBaileys.useMultiFileAuthState → directly on namespace (NOT on .default)
 *   rawBaileys.DisconnectReason → directly on namespace
 *   rawBaileys.fetchLatestBaileysVersion → directly on namespace
 */

import * as rawBaileys from '@whiskeysockets/baileys';

// makeWASocket is the .default export
const makeWASocket = rawBaileys.default;

// Atomic auth state (§6.1) — temp+rename writes, corrupt-session detection.
const useAtomicFileAuthState = (await import('./atomic-auth.js')).useAtomicFileAuthState;

// All other helpers are named exports directly on the namespace
const DisconnectReason = rawBaileys.DisconnectReason;
const fetchLatestBaileysVersion = rawBaileys.fetchLatestBaileysVersion;

import { Boom } from '@hapi/boom';
import fs from 'fs';
import pino from 'pino';
import https from 'https';

let sock = null;
let authState = null;
let saveCreds = null;
let connectionCallback = null;
let sessionDir = './session';

let currentVersion = [2, 3000, 1015901307];
let reconnectAttempt = 0;
const MAX_BACKOFF_MS = 30000;
// Cap total reconnect attempts per connect() session. Without this, an offline
// device (e.g. DNS failure) loops forever every ≤30s — battery drain + logcat
// flood. The Kotlin backstop worker re-invokes connect() later, which resets
// the counter.
const MAX_RECONNECT_ATTEMPTS = 8;
let reconnectTimer = null;
// Set while we're deliberately tearing a socket down (user/dispatcher asked).
// Its 'close' event must NOT trigger auto-reconnect — otherwise the engine
// leaves a zombie session connected, and the NEXT dispatch creates a second
// live socket on the same WhatsApp account → server kills both with
// stream:error conflict type="replaced" (reason 440) → sends fail.
let intentionalDisconnect = false;

let globalConnectError = 'None';

// ── Contact store (§4.2) ─────────────────────────────────────────────────
// Baileys does NOT populate sock.store by itself — getContacts() used to read
// an always-empty object. Collect contacts from the events that carry them and
// persist a small cache so the list survives process restarts (bounded file,
// one entry per WhatsApp contact — consistent with §2.5's no-unbounded-growth
// rule since it never grows beyond the user's actual contact count).
const contactsCache = new Map(); // jid -> name

function upsertContact(c) {
    if (!c?.id || typeof c.id !== 'string') return;
    if (c.id.endsWith('@g.us') || c.id.endsWith('@broadcast')) return; // groups/lists aren't contacts
    const name = c.notify || c.name || c.verifiedName;
    if (name && name !== contactsCache.get(c.id)) {
        contactsCache.set(c.id, name);
        scheduleContactsPersist();
    }
}

let persistTimer = null;
function scheduleContactsPersist() {
    if (persistTimer) return; // coalesce bursts (history sync can deliver hundreds)
    persistTimer = setTimeout(() => {
        persistTimer = null;
        try {
            const path = new URL('./contacts_cache.json', import.meta.url);
            fs.writeFileSync(path, JSON.stringify([...contactsCache]));
        } catch (e) {
            console.error('[Delivra Node] contacts cache write failed:', e.message);
        }
    }, 3000);
}

try {
    const cached = fs.readFileSync(new URL('./contacts_cache.json', import.meta.url), 'utf8');
    for (const [jid, name] of JSON.parse(cached)) contactsCache.set(jid, name);
} catch (_e) { /* first run / no cache yet */ }

/**
 * Tears down the current socket (if any) WITHOUT triggering auto-reconnect:
 * detaches its event listeners first so late events can't respawn sockets,
 * then ends it. Used by disconnectWhatsApp() AND connectToWhatsApp() — any
 * new connection must never coexist with an old one (single-session rule).
 */
function endCurrentSocket() {
  const s = sock;
  sock = null;
  intentionalDisconnect = true;
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  if (s) {
    try { s.ev.removeAllListeners('connection.update'); } catch (_e) { /* best effort */ }
    try { s.ev.removeAllListeners('creds.update'); } catch (_e) { /* best effort */ }
    try { s.end(undefined); } catch (_e) { /* best effort */ }
  }
}

async function connectToWhatsApp(sesDir, onEvent) {
  sessionDir = sesDir;
  connectionCallback = onEvent;
  reconnectAttempt = 0;
  // Kill any existing/zombie socket FIRST. Creating a second live socket on
  // the same auth state makes WhatsApp reject both with conflict/replaced.
  endCurrentSocket();
  intentionalDisconnect = false;
  
  try {
    fs.mkdirSync(sessionDir, { recursive: true });

    try {
      // Race with a 5s timeout — fetchLatestBaileysVersion() makes an HTTP call
      // to WhatsApp's servers that can hang indefinitely on Android's nodejs-mobile.
      const { version } = await Promise.race([
        fetchLatestBaileysVersion(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('version fetch timed out')), 5000))
      ]);
      if (version) {
        currentVersion = version;
        console.log('[Delivra Node] Dynamic Baileys version:', currentVersion);
      }
    } catch (e) {
      console.log('[Delivra Node] Using default Baileys version fallback:', e.message);
    }
  
    const { state, saveCreds: sc } = await useAtomicFileAuthState(sessionDir);
    authState = state;
    saveCreds = sc;

    createSocket();
  } catch (err) {
    globalConnectError = err.message || err.toString();
    console.error('[Delivra Node] connectToWhatsApp FATAL crash:', err);
    // §6.1 startup health check: a corrupt auth state must surface as
    // "re-link required", never as a silent fresh-session reset.
    if (String(err.message).includes('AUTH_STATE_CORRUPT')) {
      connectionCallback?.({ state: 'logged_out' });
      return;
    }
    throw err;
  }
}

let resolvePairingReady = null;
let pairingReadyPromise = null;

function createSocket() {
  // A socket already exists (e.g. a reconnect timer raced a newer connect()
  // call) — never run two live sockets on one auth state.
  if (sock) {
    console.log('[Delivra Node] createSocket skipped — socket already active');
    return;
  }
  console.log('[Delivra Node] Creating WASocket with version:', currentVersion);
  
  // Reset pairing readiness state for the new socket
  pairingReadyPromise = new Promise((resolve) => {
    resolvePairingReady = resolve;
  });

  const thisSock = makeWASocket({
    version: currentVersion,
    auth: authState,
    printQRInTerminal: false,
    logger: pino({ level: 'warn' }),
    // Avoid relying on the Baileys Browsers object which might be structured differently/undefined
    browser: ['Mac OS', 'Chrome', '121.0.0'],
    generateHighQualityLinkPreview: false,
    syncFullHistory: false,
    keepAliveIntervalMs: 30000,  // Keep WebSocket alive on Android background
    defaultQueryTimeoutMs: undefined, // Fixes internal hangs on certain WA queries
    options: {
      axios: {
        httpsAgent: new https.Agent({ rejectUnauthorized: false }) // Explicitly bypass nodejs-mobile CA issues
      }
    }
  });

  sock = thisSock;

  thisSock.ev.on('creds.update', saveCreds);

  // Contact collection (§4.2) — these are the only events that ever carry names
  thisSock.ev.on('messaging-history.set', ({ chats = [] } = {}) => chats.forEach(upsertContact));
  thisSock.ev.on('contacts.upsert', (chats = []) => chats.forEach(upsertContact));
  thisSock.ev.on('contacts.update', (chats = []) => chats.forEach(upsertContact));

  thisSock.ev.on('connection.update', async (update) => {
    // Events from a superseded/dead socket must be ignored entirely — acting
    // on them is what used to spawn parallel sessions (conflict/replaced).
    if (sock !== thisSock) return;

    const { connection, lastDisconnect, qr } = update;
    console.log('[Delivra Node] Connection update:', JSON.stringify(update));

    // When Baileys emits a QR, it means the WebSocket is fully connected and WA 
    // is waiting for auth. This is the absolute safest time to call requestPairingCode!
    if (qr) {
      console.log('[Delivra Node] Socket is ready for pairing (QR event received)');
      resolvePairingReady?.(true);
    }

    if (connection === 'open') {
      reconnectAttempt = 0;
      connectionCallback?.({ state: 'connected' });
    } else if (connection === 'close') {
      sock = null; // this socket is dead — free the slot before deciding next step
      const reason = new Boom(lastDisconnect?.error)?.output?.statusCode;
      console.log('[Delivra Node] Socket closed, reason code:', reason);
      if (reason === DisconnectReason.loggedOut) {
        connectionCallback?.({ state: 'logged_out' });
      } else if (intentionalDisconnect) {
        console.log('[Delivra Node] Intentional disconnect — staying offline');
        connectionCallback?.({ state: 'disconnected' });
      } else if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
        console.log('[Delivra Node] Reconnect limit reached (' + MAX_RECONNECT_ATTEMPTS + ') — giving up until next dispatch');
        connectionCallback?.({ state: 'error', message: 'reconnect_gave_up' });
      } else {
        const backoff = Math.min(2 ** reconnectAttempt * 2000, MAX_BACKOFF_MS);
        reconnectAttempt++;
        connectionCallback?.({ state: 'reconnecting', backoffMs: backoff });
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(createSocket, backoff);
      }
    } else if (connection === 'connecting') {
      connectionCallback?.({ state: 'connecting' });
    }
  });
}


function startPairing(phoneNumber, onResult) {
  console.log('[Delivra Node] startPairing called for number:', phoneNumber);

  const cleanNumber = String(phoneNumber || '').replace(/[^0-9]/g, '');
  if (!cleanNumber || cleanNumber.length < 7) {
    onResult({ error: 'Please enter full phone number with country code (e.g. 923001234567)' });
    return;
  }

  (async () => {
    try {
      if (!sock) {
        console.log('[Delivra Node] WASocket is null — waiting for connectToWhatsApp to finish...');
        for (let i = 0; i < 20; i++) {
          if (sock) break;
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
        if (!sock) {
          throw new Error('Socket failed to initialize in time. Internal crash: ' + globalConnectError);
        }
      }

      if (sock.authState?.creds?.registered) {
        onResult({ error: 'Device already linked. Please unlink it in WhatsApp first.' });
        return;
      }

      console.log('[Delivra Node] Waiting for Baileys WebSocket to be fully ready for pairing...');
      
      // Strict Promise wrapper that hooks DIRECTLY into the current socket's event emitter,
      // avoiding any reliance on global/re-assigned promises that can strand execution.
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('no_qr_event')), 30000);
        pairingReadyPromise.then(() => {
          clearTimeout(timeout);
          resolve();
        });
      });

      console.log('[Delivra Node] Requesting pairing code from WhatsApp...');
      // Wrapped socket call
      const code = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Code request timed out. Check internet.')), 45000);
        sock.requestPairingCode(cleanNumber).then((c) => {
          clearTimeout(timeout);
          resolve(c);
        }).catch((e) => {
          clearTimeout(timeout);
          reject(e);
        });
      });

      console.log('[Delivra Node] Pairing code received:', code);
      onResult({ code });
    } catch (err) {
      console.error('[Delivra Node] startPairing failed:', err.message);
      onResult({ error: err.message });
    }
  })();
}

function getContacts() {
    // Collected from messaging-history/contacts events + persisted cache (§4.2).
    // sock.store fallback kept for safety but is normally empty by itself.
    const live = [...contactsCache.entries()].map(([jid, name]) => ({ jid, name }));
    if (live.length > 0) return live;
    const store = sock?.store || {};
    return Object.values(store.contacts || {}).map(c => ({
        jid: c.id,
        name: c.notify || c.name || c.id,
    }));
}

function disconnectWhatsApp() {
  // Full teardown — listeners removed first so the 'close' event can't spawn
  // a reconnect (that zombie socket is what caused conflict/replaced failures).
  endCurrentSocket();
}

function getConnectionState() {
  return sock ? 'connected' : 'disconnected';
}

export {
  connectToWhatsApp,
  startPairing,
  getContacts,
  disconnectWhatsApp,
  getConnectionState,
};
export function getSocket() { return sock; }
