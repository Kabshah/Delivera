package com.kabshah.delivra.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
// PowerManager not needed — app is battery-efficient by design (§2.5)
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kabshah.delivra.bridge.NodeBridge
import com.kabshah.delivra.diagnostics.EventRingBuffer
import com.kabshah.delivra.diagnostics.RingEvent
import com.kabshah.delivra.ui.theme.*
import java.io.File

/**
 * Settings / Diagnostics screen (§6.9).
 *
 * All information shown is derived from live state — no stored history, no log table.
 * Ring buffer snapshot is in-memory only, wiped on process death by design (§2.5).
 * "Export diagnostics" dumps the ring buffer to a temporary file, not retained afterward.
 */
@Composable
fun SettingsScreen(
    connectionState: NodeBridge.ConnectionState,
    ringBuffer: EventRingBuffer,
    onBack: () -> Unit,
    onLinkWhatsApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val events = remember { mutableStateOf(ringBuffer.snapshot()) }

    // Refresh ring buffer snapshot periodically while screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            events.value = ringBuffer.snapshot()
            kotlinx.coroutines.delay(2000)
        }
    }

    val hasExactAlarm = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
    }
    // No battery exemption check needed — app is battery-efficient by design (§2.5):
    // connect-on-demand, event-driven triggers, no polling, no always-on socket

    Column(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .padding(top = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(SurfaceTinted, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple()
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Back",
                    tint = RosePrimary, modifier = Modifier.size(18.dp))
            }
            Text("Settings & Diagnostics", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Divider(color = BorderSoft, thickness = 1.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── WhatsApp Link status ─────────────────────────────────────────
            item {
                DiagnosticCard(title = "WhatsApp Connection") {
                    val (stateLabel, stateColor) = when (connectionState) {
                        NodeBridge.ConnectionState.CONNECTED -> "Connected ✓" to StatusSentFg
                        NodeBridge.ConnectionState.CONNECTING -> "Connecting…" to StatusPendingFg
                        NodeBridge.ConnectionState.LOGGED_OUT -> "Logged Out — Re-link Required" to StatusFailedFg
                        NodeBridge.ConnectionState.ERROR -> "Error" to StatusFailedFg
                        NodeBridge.ConnectionState.DISCONNECTED -> "Disconnected (idle)" to TextMuted
                    }
                    DiagnosticRow(
                        icon = Icons.Outlined.Wifi,
                        label = "Session state",
                        value = stateLabel,
                        valueColor = stateColor
                    )
                    if (connectionState == NodeBridge.ConnectionState.LOGGED_OUT ||
                        connectionState == NodeBridge.ConnectionState.DISCONNECTED) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceTinted, RoundedCornerShape(10.dp))
                                .border(1.dp, BorderContact, RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple()
                                ) { onLinkWhatsApp() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Link WhatsApp",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RoseDeep
                            )
                        }
                    }
                }
            }

            // ── Exact alarm permission ─────────────────────────────────────
            item {
                DiagnosticCard(title = "Scheduling Permission") {
                    DiagnosticRow(
                        icon = Icons.Outlined.Alarm,
                        label = "Exact alarms",
                        value = if (hasExactAlarm) "Granted ✓" else "Not granted ⚠",
                        valueColor = if (hasExactAlarm) StatusSentFg else StatusPendingFg
                    )
                    if (!hasExactAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(Modifier.height(6.dp))
                        FixButton("Grant exact alarm permission") {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    }
                }
            }

            // ── Battery-efficient design info (§2.5) ─────────────────────────
            item {
                DiagnosticCard(title = "Battery-Efficient Design") {
                    DiagnosticRow(
                        icon = Icons.Outlined.BatteryFull,
                        label = "Architecture",
                        value = "Connect-on-demand ✓",
                        valueColor = StatusSentFg
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Delivra only connects to WhatsApp for a few minutes " +
                                "around each scheduled send, then disconnects. " +
                                "No always-on socket, no polling loops — your battery " +
                                "stays healthy while messages still send on time.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Live event ring buffer ──────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RECENT EVENTS (IN-MEMORY)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextCaption,
                        letterSpacing = 0.5.sp
                    )
                    // Export diagnostics button
                    Text(
                        "Export",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RosePrimary,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { exportDiagnostics(context, connectionState, events.value, hasExactAlarm) }
                            .padding(4.dp)
                    )
                }
            }

            if (events.value.isEmpty()) {
                item {
                    Text(
                        "No events yet — the ring buffer is empty.\nIt clears on process death (in-memory only, by design).",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .border(1.dp, BorderSoft, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    )
                }
            } else {
                items(events.value.reversed()) { event ->
                    EventRow(event)
                }
            }

            // ── OEM autostart note ──────────────────────────────────────────
            item {
                val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                if (isSamsung) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusNeedsReviewBg, RoundedCornerShape(12.dp))
                            .border(1.dp, StatusNeedsReviewDot.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Warning, contentDescription = null,
                                tint = StatusNeedsReviewFg, modifier = Modifier.size(14.dp))
                            Text("Samsung detected", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StatusNeedsReviewFg)
                        }
                        Text(
                            "Samsung's Device Care may still delay background wakeups. " +
                                    "Go to Settings → Device Care → Battery → Background usage limits → " +
                                    "make sure Delivra is not in the \"Sleeping\" or \"Deep sleeping\" list.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun DiagnosticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, BorderSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextCaption,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DiagnosticRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 12.5.sp, color = TextSecondary)
        }
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun FixButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceTinted, RoundedCornerShape(8.dp))
            .border(1.dp, BorderInput, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RoseDeep)
    }
}

@Composable
private fun EventRow(event: RingEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .border(1.dp, BorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            event.describe(),
            fontSize = 11.sp,
            color = TextSecondary,
            lineHeight = 16.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

// ── Export diagnostics (generate on demand, not retained — §6.9) ──────────────

private fun exportDiagnostics(
    context: Context,
    connectionState: NodeBridge.ConnectionState,
    events: List<RingEvent>,
    hasExactAlarm: Boolean
) {
    try {
        val sb = StringBuilder()
        sb.appendLine("=== Delivra Diagnostics ===")
        sb.appendLine("Generated: ${java.util.Date()}")
        sb.appendLine()
        sb.appendLine("-- Connection state: $connectionState")
        sb.appendLine("-- Exact alarm permission: $hasExactAlarm")
        sb.appendLine("-- Battery design: connect-on-demand (no always-on socket)")
        sb.appendLine("-- Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("-- Ring buffer (last ${events.size} events) --")
        events.reversed().forEach { sb.appendLine(it.describe()) }

        // Write to a temp file — not retained after sharing (§6.9)
        val tmpFile = File(context.cacheDir, "delivra_diag_${System.currentTimeMillis()}.txt")
        tmpFile.writeText(sb.toString())
        tmpFile.deleteOnExit()  // best-effort cleanup

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tmpFile
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share diagnostics"))
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Export failed: ${e.message}")
    }
}
