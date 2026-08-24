package com.kabshah.delivra.bridge

import android.content.Context
import android.util.Log
import com.kabshah.delivra.data.MessageStatus
import com.kabshah.delivra.data.ScheduleRepository
import com.kabshah.delivra.diagnostics.EventRingBuffer
import com.kabshah.delivra.diagnostics.RingEvent
import com.kabshah.delivra.scheduling.Constants
import com.kabshah.delivra.scheduling.RetryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NodeBridge — typed, coroutine-based wrapper over the nodejs-mobile-android channel.
 *
 * Every outgoing call gets a unique correlationId (UUID). The response listener
 * matches incoming messages back to the waiting coroutine via a ConcurrentHashMap
 * of CompletableDeferred<JSONObject>. This gives us clean suspend-function semantics
 * over what is structurally a message-passing channel.
 *
 * Connection state is exposed as a StateFlow so the UI and PairingViewModel react
 * to connected/disconnected/loggedOut instantly without polling.
 *
 * API surface used by SchedulerService and ViewModels (§2.1, §8):
 *   connect(sessionDir)          → authenticates Baileys session (§4.1, §6.1)
 *   requestPairingCode(phone)    → gets 8-char code for WhatsApp Linked Devices (§4.1)
 *   getContacts()                → list of WhatsApp contacts synced by Baileys (§4.2)
 *   sendMessage(messageId)       → looks up Room row, sends via Baileys (§6.2 state machine)
 *   disconnect()                 → cleanly closes Baileys socket
 */
@Singleton
class NodeBridge @Inject constructor(
    private val repository: ScheduleRepository,
    private val ringBuffer: EventRingBuffer,
    private val failureNotifier: com.kabshah.delivra.diagnostics.FailureNotifier,
    @ApplicationContext private val context: Context
) {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, LOGGED_OUT, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Pending coroutine completions keyed by correlationId
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    // nodejs-mobile-android channel — lazily obtained once Node starts
    private var channel: NodeJsMobile.Channel? = null
    val isChannelInitialized: Boolean
        get() = channel != null

    companion object {
        private const val TAG = "NodeBridge"
        private const val CALL_TIMEOUT_MS = 60_000L  // 60 s default per bridge call
        private const val PAIRING_TIMEOUT_MS = 120_000L  // 120 s for pairing code (allows for slow device initialization + 75s Node timeouts)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel initialisation (called once from SchedulerService.onCreate)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nulls the channel so the next SchedulerService instance always re-establishes
     * a fresh TCP socket + read coroutine. Must be called from onDestroy() so the
     * stale channel reference (whose read loop was cancelled with serviceScope) is
     * not mistakenly treated as live by the next service invocation.
     */
    fun resetChannel() {
        channel = null
        Log.d(TAG, "Channel reset — next startup will reconnect TCP")
    }

    fun initChannel(ch: NodeJsMobile.Channel) {
        channel = ch
        ch.setMessageListener { raw ->
            try {
                val json = JSONObject(raw)
                val type = json.optString("type")
                val corrId = json.optString("correlationId", "")

                // Route back to waiting coroutine
                if (corrId.isNotEmpty()) {
                    pending[corrId]?.complete(json)
                }

                // Also handle unsolicited push events (connection state changes)
                when (type) {
                    "connection_event" -> handleConnectionEvent(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing bridge message: ${e.message}")
            }
        }
        Log.d(TAG, "Bridge channel initialised")
    }

    private fun handleConnectionEvent(json: JSONObject) {
        when (json.optString("state")) {
            "connected"    -> { _connectionState.value = ConnectionState.CONNECTED;    ringBuffer.record(RingEvent.Connected) }
            "connecting"   -> { _connectionState.value = ConnectionState.CONNECTING }
            "logged_out"   -> { _connectionState.value = ConnectionState.LOGGED_OUT;   Log.w(TAG, "WhatsApp logged out — re-linking required") }
            "reconnecting" -> { _connectionState.value = ConnectionState.CONNECTING }
            "error"        -> { _connectionState.value = ConnectionState.ERROR }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Kicks off the Baileys session — fire-and-forget, no ack expected.
     *
     * This is intentionally NOT a request/response call. Connection state
     * (CONNECTING → CONNECTED / LOGGED_OUT / ERROR) arrives asynchronously
     * via unsolicited `connection_event` push messages, which handleConnectionEvent()
     * processes. Waiting for an ack here would add a 60 s timeout that serves no
     * purpose — the caller should poll connectionState instead (see §2.3, §6.1).
     */
    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        ringBuffer.record(RingEvent.Connecting)
        val sessionDir = context.filesDir.absolutePath + "/wa_session"
        sendToNodeFireAndForget(
            action = "connect",
            payload = JSONObject().put("sessionDir", sessionDir)
        )
        Log.d(TAG, "connect() sent to Node (fire-and-forget)")
    }

    /**
     * Request an 8-character pairing code for the given phone number (§4.1).
     * Requires connect() to have been called first (socket must be open but not yet paired).
     */
    suspend fun requestPairingCode(phoneNumber: String): String {
        val cleanPhone = phoneNumber.filter { it.isDigit() }
        val response = call(
            action = "requestPairingCode",
            payload = JSONObject().put("phoneNumber", cleanPhone),
            timeoutMs = PAIRING_TIMEOUT_MS
        )
        if (response.optString("type") == "error") {
            throw Exception(response.optString("message", "Failed to get pairing code"))
        }
        if (response.optBoolean("success", false) || response.has("code") || response.has("payload")) {
            val code = response.optString("code").ifEmpty {
                response.optJSONObject("payload")?.optString("pairingCode") ?: ""
            }
            if (code.isNotEmpty()) return code
        }
        throw Exception(response.optString("message", response.optString("error", "Failed to get pairing code")))
    }

    /**
     * Return the list of WhatsApp contacts synced via Baileys (§4.2).
     * Returns an empty list if not connected yet rather than throwing.
     */
    suspend fun getContacts(): List<WhatsAppContact> {
        if (_connectionState.value != ConnectionState.CONNECTED) return emptyList()
        return try {
            val response = call(action = "getContacts", payload = JSONObject())
            val arr: JSONArray = response.optJSONArray("contacts") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                WhatsAppContact(
                    jid = c.getString("jid"),
                    name = c.optString("name").ifBlank { c.getString("jid") }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getContacts failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Full send flow for one message (§6.2 state machine):
     *   PENDING → CLAIMED → CONNECTING → SENDING → SENT_CONFIRMED | FINAL_FAILURE | RETRYABLE_FAILURE
     *
     * Atomic claim prevents double-dispatch when AlarmReceiver and WorkManager
     * backstop both fire in the same window.
     */
    suspend fun sendMessage(messageId: String): SendResult {
        // ── 1. Atomic claim (PENDING → CLAIMED) ──────────────────────────────
        val claimed = repository.atomicClaim(messageId)
        if (!claimed) {
            Log.d(TAG, "sendMessage: id=$messageId already claimed by another dispatch — skipping")
            return SendResult.Skipped
        }
        repository.updateStatus(messageId, MessageStatus.CLAIMED)
        ringBuffer.record(RingEvent.SendRequested(messageId))

        val msg = repository.getById(messageId)
            ?: return SendResult.Error("message_not_found").also {
                Log.e(TAG, "sendMessage: id=$messageId not found in DB after claim")
            }

        // ── 2. Validate attachment before attempting send (§2.7 point 5) ────
        if (msg.attachmentUri != null && !repository.validateAttachmentReadable(msg.attachmentUri)) {
            repository.updateStatus(messageId, MessageStatus.FINAL_FAILURE, "source_file_unavailable")
            ringBuffer.record(RingEvent.SendFailed(messageId, "source_file_unavailable"))
            failureNotifier.notifyFailed(msg.contactName, "source_file_unavailable", messageId)
            return SendResult.Error("source_file_unavailable")
        }

        // ── 3. Mark CONNECTING ────────────────────────────────────────────────
        repository.updateStatus(messageId, MessageStatus.CONNECTING)

        // ── 4. Mark SENDING (point of no return — outcome now ambiguous on crash) ──
        repository.updateStatus(messageId, MessageStatus.SENDING)
        Log.d(TAG, "sendMessage: id=$messageId to=${msg.contactJid} — entering SENDING")

        var stagedAttachmentPath: String? = null
        return try {
            val payload = JSONObject().apply {
                put("messageId", messageId)
                put("contactJid", msg.contactJid)
                if (!msg.messageText.isNullOrBlank()) put("messageText", msg.messageText)
                if (!msg.voiceNotePath.isNullOrBlank()) put("voiceNotePath", msg.voiceNotePath)
                if (!msg.attachmentUri.isNullOrBlank()) {
                    // Resolve content:// URI to a file path Node.js can read
                    val resolvedPath = resolveContentUriToTempPath(msg.attachmentUri)
                    if (resolvedPath != null) {
                        stagedAttachmentPath = resolvedPath
                        put("attachmentPath", resolvedPath)
                        put("attachmentMimeType", msg.attachmentMimeType ?: "application/octet-stream")
                        put("attachmentDisplayName", msg.attachmentDisplayName ?: "attachment")
                    }
                }
            }

            val response = call(action = "sendMessage", payload = payload)
            val success = response.optBoolean("success", false)
            val reason = response.optString("reason", "unknown_error")
            val retryable = response.optBoolean("retryable", true)

            if (success) {
                repository.updateStatus(messageId, MessageStatus.SENT_CONFIRMED)
                ringBuffer.record(RingEvent.AckReceived(messageId))
                // The voice note file has served its purpose — delete immediately
                // (§2.5). The DB row remains until §4.8's 12h cleanup so the Queue
                // still shows "Sent ✓". Retryable rows keep their file instead.
                msg.voiceNotePath?.let { path ->
                    try { java.io.File(path).delete() } catch (_: Exception) { /* best effort */ }
                }
                // Staged local attachment copy has served its purpose — delete on
                // confirmed success. (Failed rows keep the file so retries work;
                // legacy content:// rows never had a local copy to delete.)
                if (!msg.attachmentUri.isNullOrBlank() && !msg.attachmentUri.startsWith("content:")) {
                    try { java.io.File(msg.attachmentUri.removePrefix("file://")).delete() } catch (_: Exception) { /* best effort */ }
                }
                Log.d(TAG, "sendMessage: id=$messageId SENT_CONFIRMED")
                SendResult.Success
            } else {
                val newStatus = if (retryable && msg.attemptCount < Constants.MAX_RETRY_ATTEMPTS) {
                    MessageStatus.RETRYABLE_FAILURE
                } else {
                    MessageStatus.FINAL_FAILURE
                }
                repository.updateStatus(messageId, newStatus, reason)
                ringBuffer.record(RingEvent.SendFailed(messageId, reason))

                if (newStatus == MessageStatus.RETRYABLE_FAILURE) {
                    // §6.2 backoff: 5s after 1st failure, 20s after 2nd. Without
                    // scheduling this, RETRYABLE rows were never retried at all
                    // (the backstop's due query only selects PENDING).
                    val backoffMs = Constants.RETRY_BACKOFF_MS.getOrElse(msg.attemptCount - 1) { 60_000L }
                    RetryWorker.schedule(context, messageId, backoffMs)
                } else if (newStatus == MessageStatus.FINAL_FAILURE) {
                    failureNotifier.notifyFailed(msg.contactName, reason, messageId)
                }

                Log.w(TAG, "sendMessage: id=$messageId → $newStatus reason=$reason")
                SendResult.Error(reason)
            }
        } catch (e: Exception) {
            // Crash/timeout during SENDING → NEEDS_REVIEW (§6.7, §6.2)
            repository.updateStatus(messageId, MessageStatus.NEEDS_REVIEW, "bridge_exception: ${e.message}")
            ringBuffer.record(RingEvent.SendFailed(messageId, "bridge_exception"))
            failureNotifier.notifyNeedsReview(msg.contactName, messageId)
            Log.e(TAG, "sendMessage: id=$messageId exception during SENDING → NEEDS_REVIEW", e)
            SendResult.Error("bridge_exception")
        } finally {
            // Always delete the staged attachment copy — each retry re-stages a
            // fresh one from the original content:// URI (which step 2 already
            // re-validated), so keeping it buys nothing and leaks cache space.
            stagedAttachmentPath?.let { path ->
                try { java.io.File(path).delete() } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    fun disconnect() {
        try {
            sendToNodeFireAndForget(action = "disconnect", payload = JSONObject())
        } catch (e: Exception) { /* best effort */ }
        _connectionState.value = ConnectionState.DISCONNECTED
        ringBuffer.record(RingEvent.Disconnected)
        Log.d(TAG, "disconnect() called")
    }

    /**
     * Called when the TCP socket unexpectedly closes (EOF or read error).
     * This proves the Node process crashed or the socket died, so we should abort all pending bridge calls immediately.
     */
    fun handleTcpDisconnection() {
        Log.e(TAG, "Handling TCP Disconnection! Cancelling all pending calls.")
        _connectionState.value = ConnectionState.ERROR
        ringBuffer.record(RingEvent.Disconnected)
        val ex = Exception("Node.js process unexpectedly terminated or TCP socket dropped.")
        pending.values.forEach { it.completeExceptionally(ex) }
        pending.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal bridge helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Send a message and wait for the correlated response (suspend).
     * Throws on timeout or if the channel isn't initialised.
     */
    private suspend fun call(action: String, payload: JSONObject, timeoutMs: Long = CALL_TIMEOUT_MS): JSONObject {
        val corrId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[corrId] = deferred
        try {
            val msg = JSONObject().apply {
                put("action", action)
                put("correlationId", corrId)
                put("payload", payload)
            }
            sendRaw(msg.toString())
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(corrId)
        }
    }

    /** Send without waiting for a response (connect event comes back unsolicited). */
    private fun sendToNode(action: String, payload: JSONObject) {
        val msg = JSONObject().apply {
            put("action", action)
            put("correlationId", UUID.randomUUID().toString())
            put("payload", payload)
        }
        sendRaw(msg.toString())
    }

    private fun sendToNodeFireAndForget(action: String, payload: JSONObject) {
        try { sendToNode(action, payload) } catch (e: Exception) { Log.e(TAG, "sendToNodeFireAndForget error", e) }
    }

    private fun sendRaw(json: String) {
        val ch = channel ?: throw IllegalStateException("NodeBridge: channel not initialised — call initChannel() from SchedulerService first")
        ch.sendMessage(json)
        Log.v(TAG, "→ Node: $json")
    }

    /**
     * Returns a filesystem path Node.js can read for the given attachment.
     * - Local staged file (attach-time import): returned as-is — Node runs in
     *   the app's process/UID, so it reads app-internal storage directly.
     * - Legacy content:// URI: copied to a cache temp first.
     * Returns null if the source can't be opened.
     */
    private fun resolveContentUriToTempPath(uriString: String): String? {
        if (!uriString.startsWith("content://")) {
            val f = java.io.File(uriString.removePrefix("file://"))
            return if (f.exists()) f.absolutePath else null
        }
        return try {
            val uri = android.net.Uri.parse(uriString)
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')?.substringBefore('+') ?: "bin"
            val tmp = java.io.File(context.cacheDir, "attach_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "resolveContentUriToTempPath failed for $uriString: ${e.message}")
            null
        }
    }
}

// ─── nodejs-mobile-android API shim ──────────────────────────────────────────
// The real class is `io.nodejs.mobile.NodeMobile` / `io.nodejs.mobile.Bridge`.
// This thin object wraps it so the rest of the code stays decoupled from the
// exact library class names (which can shift across versions).
object NodeJsMobile {
    interface Channel {
        fun sendMessage(message: String)
        fun setMessageListener(listener: (String) -> Unit)
    }
}

// ─── Data classes ─────────────────────────────────────────────────────────────

data class WhatsAppContact(
    val jid: String,
    val name: String,
    val initial: String = name.firstOrNull()?.toString()?.uppercase() ?: "?",
    val photoUri: String? = null
)

sealed class SendResult {
    object Success : SendResult()
    object Skipped : SendResult()
    data class Error(val reason: String) : SendResult()
}
