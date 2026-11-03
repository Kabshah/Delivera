/**
 * Delivra — index.js (Node.js bridge listener)
 *
 * Architecture note (§2.1, §8):
 * - Kotlin side owns all data + scheduling
 * - Node side is a thin, stateless send engine
 * - ALL async operations (connect, pairing) are FIRE-AND-FORGET with push results
 * - No request/response timeouts for long async ops — results arrive as push events
 */

import './polyfill.js';
import { connectToWhatsApp, getConnectionState, disconnectWhatsApp, getContacts, startPairing } from './whatsapp.js';
import { sendMessage } from './sender.js';

import net from 'net';

// TEMPORARY workaround — pending re-verification after the TMPDIR fix.
// The earlier "Media upload failed on all hosts" was actually an ENOENT on
// /tmp (see polyfill.js), NOT a certificate failure. Once PDF sending is
// confirmed working, remove this and the rejectUnauthorized agent in
// whatsapp.js, replacing them with NODE_EXTRA_CA_CERTS + a bundled cacert.pem
// if the CA store turns out to be genuinely incomplete.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

process.on('uncaughtException', (err) => {
  console.error('[Delivra Node CRASH] uncaughtException:', err.message || err, err.stack || '');
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('[Delivra Node CRASH] unhandledRejection:', reason);
});

let clientSocket = null;

// ── Dynamic port selection ────────────────────────────────────────────────
// Kotlin passes --port=XXXX as a CLI argument. Fall back to 3000.
function getRequestedPort() {
  for (const arg of process.argv) {
    if (arg.startsWith('--port=')) {
      const p = parseInt(arg.split('=')[1], 10);
      if (p > 0 && p < 65536) return p;
    }
  }
  return 3000;
}

const REQUESTED_PORT = getRequestedPort();
const MAX_PORT_ATTEMPTS = 10;

// The TCP server that Kotlin connects to
const server = net.createServer((socket) => {
  // Bug 2 fix: if a previous Kotlin connection is still open (Day-2 re-connect),
  // destroy it cleanly before wiring up the new socket. Without this, the old
  // socket's 'data' listener stays attached and corrupts the new connection's
  // line-buffer parsing.
  if (clientSocket && !clientSocket.destroyed) {
    console.log('[Delivra Node] Destroying previous Kotlin socket before accepting new connection');
    clientSocket.destroy();
  }
  clientSocket = socket;
  console.log('[Delivra Node] Kotlin connected');

  // Clean up on socket close/error so sendResponse() doesn't try to write
  // to a dead socket on future calls.
  socket.on('close', () => {
    if (clientSocket === socket) {
      clientSocket = null;
      console.log('[Delivra Node] Kotlin socket closed');
    }
  });
  socket.on('error', (err) => {
    console.error('[Delivra Node] Socket error:', err.message);
    if (clientSocket === socket) {
      clientSocket = null;
    }
  });

  // Reset per-connection line buffer — never carry over a partial line from
  // a previous connection (would cause JSON parse errors on the new session).
  let buffer = '';
  socket.on('data', async (data) => {
    buffer += data.toString();
    
    // Process stream delimited by newlines
    let lineIdx;
    while ((lineIdx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.substring(0, lineIdx).trim();
      buffer = buffer.substring(lineIdx + 1);
      
      if (!line) continue;

      let parsed;
      try { parsed = JSON.parse(line); }
      catch (e) { sendError('parse_error', e.message); continue; }

      const { action, correlationId, payload } = parsed;

      try {
        switch (action) {

          // ── CONNECT ──────────────────────────────────────────────────────────
          // Fire-and-forget. WhatsApp connection state is pushed via connection_event.
          case 'connect': {
            const sessionDir = payload?.sessionDir || './session';
            sendResponse({ type: 'ack', correlationId, status: 'connecting' });
            connectToWhatsApp(sessionDir, (event) => {
              sendResponse({ type: 'connection_event', ...event });
            }).catch((err) => {
              console.error('[Delivra Node] connectToWhatsApp error:', err.message);
              sendResponse({ type: 'connection_event', state: 'error', message: err.message });
            });
            break;
          }

          // ── REQUEST PAIRING ─────────────────────────────────────────────────────
          // Calls startPairing which is async. We send the response with the correlationId
          // when it finishes, allowing Kotlin's NodeBridge.call to complete naturally.
          case 'requestPairingCode': {
            const phoneNumber = payload?.phoneNumber || '';
            let responded = false;
            const failsafe = setTimeout(() => {
              if (!responded) {
                responded = true;
                sendError('pairing_error', 'Failsafe 60s timeout hit inside Node.js! The WhatsApp socket heavily hung.', correlationId);
              }
            }, 60000);

            startPairing(phoneNumber, (result) => {
              if (responded) return;
              responded = true;
              clearTimeout(failsafe);
              if (result.error) {
                sendError('pairing_error', result.error, correlationId);
              } else {
                sendResponse({ type: 'ack', correlationId, success: true, payload: { pairingCode: result.code }, code: result.code });
              }
            });
            break;
          }

          // ── GET CONTACTS ──────────────────────────────────────────────────────
          case 'getContacts': {
            const contacts = await getContacts();
            sendResponse({ type: 'contacts', correlationId, contacts });
            break;
          }

          // ── SEND MESSAGE ──────────────────────────────────────────────────────
          case 'sendMessage': {
            const result = await sendMessage(payload);
            sendResponse({ type: 'send_result', correlationId, ...result });
            break;
          }

          // ── DISCONNECT ────────────────────────────────────────────────────────
          case 'disconnect': {
            disconnectWhatsApp();
            sendResponse({ type: 'ack', correlationId, status: 'disconnected' });
            break;
          }

          default:
            sendError('unknown_action', `Unknown action: ${action}`, correlationId);
        }
      } catch (err) {
        sendError('action_error', err.message, correlationId);
      }
    } // End while
  }); // End socket.on('data')
}); // End net.createServer

// ── Listen with error handling + port fallback ──────────────────────────────
// EADDRINUSE from TIME_WAIT sockets (force-stop race) was a Day-2 crash vector.
// Try the requested port first, then scan upward. Kotlin scans the same range
// when probing for an already-running Node process.
function tryListen(port, attemptsLeft) {
  if (attemptsLeft <= 0) {
    console.error('[Delivra Node engine] FATAL: Could not bind any port in range. Exiting.');
    // Don't process.exit — let the uncaughtException handler + Kotlin timeout deal with it.
    return;
  }

  server.once('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.warn(`[Delivra Node engine] Port ${port} busy (EADDRINUSE), trying ${port + 1}...`);
      tryListen(port + 1, attemptsLeft - 1);
    } else {
      console.error('[Delivra Node engine] Server error:', err.message);
    }
  });

  server.listen({ port, host: '127.0.0.1', exclusive: true }, () => {
    const actualPort = server.address().port;
    console.log(`[Delivra Node engine] TCP Server ready on port ${actualPort}`);
  });
}

tryListen(REQUESTED_PORT, MAX_PORT_ATTEMPTS);

function sendResponse(obj) {
  if (clientSocket && !clientSocket.destroyed) {
    try {
      clientSocket.write(JSON.stringify(obj) + '\n');
    } catch (e) {
      console.error('[Delivra Node] TCP write error:', e.message);
    }
  }
}

function sendError(code, message, correlationId) {
  sendResponse({ type: 'error', code, message, correlationId });
}

console.log('[Delivra Node engine] Ready');
