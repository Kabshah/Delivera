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

const NON_RETRYABLE_REASONS = new Set(['invalid_jid', 'media_too_large', 'source_file_unavailable', 'zero_byte_file_error']);

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

    // ── Voice note ──────────────────────────────────────────────────────
    if (voiceNotePath && !done.has('voice')) {
      console.log('[Delivra sender] Sending voice note:', voiceNotePath);
      if (!fs.existsSync(voiceNotePath)) {
        console.error('[Delivra sender] Voice note file missing:', voiceNotePath);
        return { success: false, reason: 'source_file_unavailable', retryable: false };
      }
      const vnSize = fs.statSync(voiceNotePath).size;
      if (vnSize === 0) {
        console.error('[Delivra sender] Voice note file is zero bytes');
        return { success: false, reason: 'zero_byte_file_error', retryable: false };
      }
      // Use file-path streaming (url:) instead of readFileSync — avoids V8
      // heap pressure on nodejs-mobile and lets Baileys stream the file
      // directly through its encrypt→upload pipeline.
      console.log('[Delivra sender] Voice note size:', vnSize, 'bytes — sending as PTT');
      await sock.sendMessage(jid, {
        audio: { url: voiceNotePath },
        ptt: true,  // PTT = Push-To-Talk = voice note bubble in WhatsApp
        mimetype: 'audio/ogg; codecs=opus',
      });
      done.add('voice');
      console.log('[Delivra sender] Voice note sent successfully');
    }

    // ── File attachment (image / document) ───────────────────────────────
    if (attachmentPath && !done.has('doc')) {
      console.log('[Delivra sender] Sending attachment:', attachmentPath, 'mime:', attachmentMimeType);
      if (!fs.existsSync(attachmentPath)) {
        console.error('[Delivra sender] Attachment file missing:', attachmentPath);
        return { success: false, reason: 'source_file_unavailable', retryable: false };
      }
      const fileSize = fs.statSync(attachmentPath).size;
      if (fileSize > 100 * 1024 * 1024) {
        return { success: false, reason: 'media_too_large', retryable: false };
      }
      if (fileSize === 0) {
        return { success: false, reason: 'zero_byte_file_error', retryable: false };
      }

      const isImage = (attachmentMimeType || '').startsWith('image/');
      console.log('[Delivra sender] Attachment size:', fileSize, 'bytes, isImage:', isImage);

      if (isImage) {
        const content = {
          image: { url: attachmentPath },
          mimetype: attachmentMimeType || 'image/png',
        };
        if (messageText && !done.has('text')) {
          content.caption = messageText;
        }
        await sock.sendMessage(jid, content);
        if (content.caption) done.add('text');
        console.log('[Delivra sender] Image sent successfully');
      } else {
        await sock.sendMessage(jid, {
          document: { url: attachmentPath },
          mimetype: attachmentMimeType || 'application/octet-stream',
          fileName: attachmentDisplayName || 'attachment',
        });
        console.log('[Delivra sender] Document sent successfully');
      }
      done.add('doc');
    }

    // ── Plain text ──────────────────────────────────────────────────────
    if (messageText && !done.has('text')) {
      await sock.sendMessage(jid, { text: messageText });
      done.add('text');
      console.log('[Delivra sender] Text sent successfully');
    }

    completedParts.delete(messageId);
    return { success: true, messageId };

  } catch (err) {
    console.error('[Delivra sender] sendMessage error:', err.message, err.stack || '');
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
  if (msg.includes('enoent')) return 'source_file_unavailable';
  if (msg.includes('media upload failed')) return 'media_upload_failed';
  if (msg.includes('certificate') || msg.includes('cert') || msg.includes('ssl')) return 'tls_certificate_error';
  return `unknown_error: ${err?.message || 'no_message'}`;
}

export { sendMessage };
