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

// All other helpers are named exports directly on the namespace
const DisconnectReason = rawBaileys.DisconnectReason;
const fetchLatestBaileysVersion = rawBaileys.fetchLatestBaileysVersion;

// §6.1 — atomic auth state: temp-file + rename on every write so a
// process kill mid-write can never leave a torn session behind.
import { useAtomicFileAuthState, AuthStateCorruptError } from './atomic-auth.js';

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

let globalConnectError = 'None';

async function connectToWhatsApp(sesDir, onEvent) {
  sessionDir = sesDir;
  connectionCallback = onEvent;

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

    // §6.1 — use atomic auth state (temp+rename writes, corrupt-creds detection)
    let authResult;
    try {
      authResult = await useAtomicFileAuthState(sessionDir);
    } catch (e) {
      if (e instanceof AuthStateCorruptError) {
        // Session file is present but unparseable — a torn write from a
        // previous process kill. Surface as logged_out so Kotlin prompts
        // the user to re-link instead of entering a silent crash loop.
        console.error('[Delivra Node] Auth state corrupt — treating as logged out:', e.message);
        connectionCallback?.({ state: 'logged_out' });
        return;
      }
      throw e;
    }
    authState = authResult.state;
    saveCreds = authResult.saveCreds;

    createSocket();
  } catch (err) {
    globalConnectError = err.message || err.toString();
    console.error('[Delivra Node] connectToWhatsApp FATAL crash:', err);
    throw err;
  }
}

let resolvePairingReady = null;
let pairingReadyPromise = null;

function createSocket() {
  console.log('[Delivra Node] Creating WASocket with version:', currentVersion);

  // Reset pairing readiness state for the new socket
  pairingReadyPromise = new Promise((resolve) => {
    resolvePairingReady = resolve;
  });

  sock = makeWASocket({
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

  sock.ev.on('creds.update', saveCreds);
  sock.ev.on('connection.update', async (update) => {
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
      const reason = new Boom(lastDisconnect?.error)?.output?.statusCode;
      console.log('[Delivra Node] Socket closed, reason code:', reason);
      if (reason === DisconnectReason.loggedOut) {
        connectionCallback?.({ state: 'logged_out' });
      } else {
        const backoff = Math.min(2 ** reconnectAttempt * 2000, MAX_BACKOFF_MS);
        reconnectAttempt++;
        connectionCallback?.({ state: 'reconnecting', backoffMs: backoff });
        setTimeout(createSocket, backoff);
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
  if (!sock) return [];
  const store = sock.store || {};
  const contacts = Object.values(store.contacts || {}).map(c => ({
    jid: c.id,
    name: c.notify || c.name || c.id,
  }));
  return contacts;
}

function disconnectWhatsApp() {
  sock?.end(undefined);
  sock = null;
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
