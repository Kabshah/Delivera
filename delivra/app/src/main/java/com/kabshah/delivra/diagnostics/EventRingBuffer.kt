package com.kabshah.delivra.diagnostics

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory only ring buffer for fine-grained diagnostic events (§6.9).
 *
 * Fixed size — oldest events dropped when full. NEVER written to disk.
 * Wiped on process death by design (not an oversight — persisting would
 * undermine the §2.5 lightweight/minimal-footprint requirement).
 */
class EventRingBuffer(private val capacity: Int = 50) {

    private val buffer = ConcurrentLinkedDeque<RingEvent>()

    fun record(event: RingEvent) {
        buffer.addLast(event)
        while (buffer.size > capacity) {
            buffer.pollFirst()  // drop oldest
        }
    }

    fun snapshot(): List<RingEvent> = buffer.toList()

    fun clear() = buffer.clear()
}

sealed class RingEvent {
    data class Timestamp(val ms: Long = System.currentTimeMillis()) {
        override fun toString() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(ms))
    }

    abstract val ts: Timestamp

    data object Connecting : RingEvent() { override val ts = Timestamp() }
    data object Connected : RingEvent() { override val ts = Timestamp() }
    data object Disconnected : RingEvent() { override val ts = Timestamp() }
    data class SendRequested(val msgId: String) : RingEvent() { override val ts = Timestamp() }
    data class AckReceived(val msgId: String) : RingEvent() { override val ts = Timestamp() }
    data class SendFailed(val msgId: String, val reason: String) : RingEvent() { override val ts = Timestamp() }
    data class Reconciled(val reverted: Int, val needsReview: Int) : RingEvent() { override val ts = Timestamp() }
    data class AlarmFired(val msgId: String) : RingEvent() { override val ts = Timestamp() }
    data class BootRecovery(val pendingCount: Int) : RingEvent() { override val ts = Timestamp() }

    fun describe(): String = when (this) {
        is Connecting -> "[${ts}] Connecting to WhatsApp…"
        is Connected -> "[${ts}] Connected ✓"
        is Disconnected -> "[${ts}] Disconnected"
        is SendRequested -> "[${ts}] Send requested: $msgId"
        is AckReceived -> "[${ts}] Ack received ✓ $msgId"
        is SendFailed -> "[${ts}] Send failed: $msgId — $reason"
        is Reconciled -> "[${ts}] Reconcile: reverted $reverted, needs_review $needsReview"
        is AlarmFired -> "[${ts}] Alarm fired: $msgId"
        is BootRecovery -> "[${ts}] Boot recovery: $pendingCount alarms re-registered"
    }
}
