/**
 * sender.js — sendMessage(payload) implementation
 *
 * Handles:
 * - Plain text messages
 * - Voice notes as genuine WhatsApp PTT (ptt: true, Opus/OGG format) (§4.3)
 * - Images (png/jpg/webp) delivered as inline WhatsApp photos (caption = text)
 * - PDF/Word and other attachments as documents via content URI (§2.7)
 *
 * Error classification (§6.2):
 * - transient: network_timeout, connection_closed → RETRYABLE_FAILURE
 * - non-retryable: invalid_jid, media_too_large → FINAL_FAILURE (no retry waste)
 */

import { getSocket } from './whatsapp.js';
import fs from 'fs';

const NON_RETRYABLE_REASONS = new Set(['invalid_jid', 'media_too_large', 'source_file_unavailable']);

// A message can combine voice note + document + text, sent as separate
// WhatsApp messages. If part 2 fails after part 1 went out, a naive retry
// would DUPLICATE part 1. Track completed parts per messageId (in-memory —
// survives across retries within one engine process; a full process restart
// mid-retry remains an accepted §6.2 edge).
const completedParts = new Map(); // messageId -> Set<'voice'|'doc'|'text'>

function trackParts(messageId) {
  let done = completedParts.get(messageId);
  if (!done) {
    if (completedParts.size >= 50) {
      const oldest = completedParts.keys().next().value;
      completedParts.delete(oldest);
    }
    done = new Set();
    completedParts.set(messageId, done);
  }
  return done;
}

async function sendMessage(payload) {
  const { messageId, contactJid, messageText, voiceNotePath, attachmentPath, attachmentMimeType, attachmentDisplayName } = payload;
  const sock = getSocket();

  if (!sock) {
    return { success: false, reason: 'not_connected', retryable: true };
  }

  const done = trackParts(messageId);

  try {
    const jid = normalizeJid(contactJid);

    if (voiceNotePath && !done.has('voice')) {
      // Send as genuine WhatsApp PTT voice note (§4.3)
      // Audio must already be Opus/OGG (transcoded at attach time, §6.4a)
      const audio = fs.readFileSync(voiceNotePath);
      await sock.sendMessage(jid, {
        audio,
        ptt: true,  // PTT = Push-To-Talk = voice note bubble in WhatsApp
        mimetype: 'audio/ogg; codecs=opus',
      });
      done.add('voice');
    }

    if (attachmentPath && !done.has('doc')) {
      // Attachment from persistent URI (now accessible as a file path passed by Kotlin)
      if (!fs.existsSync(attachmentPath)) {
        return { success: false, reason: 'source_file_unavailable', retryable: false };
      }
      const fileSize = fs.statSync(attachmentPath).size;
      // WhatsApp practical media limit ≈ 100MB for documents
      if (fileSize > 100 * 1024 * 1024) {
        return { success: false, reason: 'media_too_large', retryable: false };
      }

      if (fileSize === 0) {
        return { success: false, reason: 'zero_byte_file_error', retryable: false };
      }

      const isImage = (attachmentMimeType || '').startsWith('image/');

      if (isImage) {
        // Deliver images (png/jpg/webp/screenshots) as real WhatsApp photos —
        // viewable inline in the chat, not as file tiles.
        const content = {
          image: { url: attachmentPath }, // Stream from file path (memory-safe)
          mimetype: attachmentMimeType || 'image/png',
        };
        // If the user also wrote a message, send it as the photo's caption
        // (one cohesive message instead of two). Only marked done AFTER the
        // send succeeds, so a failed send retries with caption intact.
        if (messageText && !done.has('text')) {
          content.caption = messageText;
        }
        await sock.sendMessage(jid, content);
        if (content.caption) done.add('text');
      } else {
        await sock.sendMessage(jid, {
          document: { url: attachmentPath }, // Stream directly from file path! Prevents Mobile memory crashes
          mimetype: attachmentMimeType || 'application/octet-stream',
          fileName: attachmentDisplayName || 'attachment',
        });
      }
      done.add('doc');
    }

    if (messageText && !done.has('text')) {
      await sock.sendMessage(jid, { text: messageText });
      done.add('text');
    }

    completedParts.delete(messageId); // fully delivered — nothing to remember
    return { success: true, messageId };

  } catch (err) {
    const reason = classifyError(err);
    return {
      success: false,
      reason,
      retryable: !NON_RETRYABLE_REASONS.has(reason),
    };
  }
}

function normalizeJid(jid) {
  // Ensure JID is in the format '1234567890@s.whatsapp.net'
  if (jid.includes('@')) return jid;
  return `${jid}@s.whatsapp.net`;
}

function classifyError(err) {
  const msg = (err?.message || '').toLowerCase();
  if (msg.includes('invalid') && msg.includes('jid')) return 'invalid_jid';
  if (msg.includes('too large') || msg.includes('413')) return 'media_too_large';
  if (msg.includes('timeout')) return 'network_timeout';
  if (msg.includes('connection')) return 'connection_closed';
  return `unknown_error: ${err?.message || 'no_message'}`;
}

export { sendMessage };
