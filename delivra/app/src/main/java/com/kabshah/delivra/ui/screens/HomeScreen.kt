package com.kabshah.delivra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ripple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kabshah.delivra.data.MessageStatus
import com.kabshah.delivra.data.ScheduledMessage
import com.kabshah.delivra.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    messages: List<ScheduledMessage>,
    pendingCount: Int,
    sentCount: Int,
    failedCount: Int,
    reviewCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onNewMessage: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onResolveNeedsReview: (String) -> Unit = {},
    onResendNeedsReview: (String) -> Unit = {},
    onMessageClick: (String) -> Unit,
    onLinkWhatsApp: () -> Unit = {},
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ─────────────────────────────────────────────────────
            HomeHeader(
                pendingCount = pendingCount,
                sentCount = sentCount,
                failedCount = failedCount,
                reviewCount = reviewCount,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onLinkWhatsApp = onLinkWhatsApp,
                onSettings = onSettings
            )

            // ── Message list ───────────────────────────────────────────────
            val filtered = remember(messages, searchQuery) {
                if (searchQuery.isBlank()) messages
                else messages.filter {
                    it.contactName.contains(searchQuery, ignoreCase = true) ||
                            it.messageText?.contains(searchQuery, ignoreCase = true) == true
                }
            }

            if (filtered.isEmpty()) {
                EmptyQueuePlaceholder(
                    hasSearch = searchQuery.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            "UPCOMING",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    items(filtered, key = { it.id }) { msg ->
                        MessageCard(
                            message = msg,
                            onDelete = { onDeleteMessage(msg.id) },
                            onResolveReview = { onResolveNeedsReview(msg.id) },
                            onResendReview = { onResendNeedsReview(msg.id) },
                            onClick = { onMessageClick(msg.id) }
                        )
                    }
                }
            }
        }

        // ── FAB ─────────────────────────────────────────────────────────
        FloatingActionButton(
            onClick = onNewMessage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 28.dp)
                .size(56.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = RosePrimary.copy(alpha = 0.5f),
                    spotColor = RosePrimary.copy(alpha = 0.55f)
                ),
            shape = RoundedCornerShape(18.dp),
            containerColor = Color.Transparent,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(FabGradientStart, FabGradientEnd)
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Message", tint = Color.White)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    pendingCount: Int,
    sentCount: Int,
    failedCount: Int,
    reviewCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onLinkWhatsApp: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .padding(start = 20.dp, end = 20.dp, top = 52.dp, bottom = 4.dp)
    ) {
        // Eyebrow + title + bell row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "DELIVRA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RosePrimary,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Queue",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = (-0.3).sp
                )
            }
            // Action icon row — Link + Settings
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Link WhatsApp button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceTinted)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple()
                        ) { onLinkWhatsApp() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = "Link WhatsApp",
                        tint = RosePrimary, modifier = Modifier.size(17.dp))
                }
                // Settings / Diagnostics
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceTinted)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple()
                        ) { onSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings",
                        tint = RosePrimary, modifier = Modifier.size(17.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceInputBg, RoundedCornerShape(12.dp))
                .border(1.dp, BorderInput, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null,
                tint = TextMuted, modifier = Modifier.size(15.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                singleLine = true,
                decorationBox = { inner ->
                    if (searchQuery.isEmpty()) {
                        Text("Search scheduled messages",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted))
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Stats strip
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatCard("Pending", pendingCount, StatusPendingDot, Modifier.weight(1f))
            StatCard("Sent", sentCount, StatusSentDot, Modifier.weight(1f))
            StatCard("Failed", failedCount, StatusFailedDot, Modifier.weight(1f))
            StatCard("Review", reviewCount, StatusNeedsReviewDot, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, count: Int, accentColor: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, BorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(count.toString(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = TextCaption)
        Spacer(Modifier.height(6.dp))
        // Colored underline bar
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(3.dp)
                .background(accentColor, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun MessageCard(
    message: ScheduledMessage,
    onDelete: () -> Unit,
    onResolveReview: () -> Unit = {},
    onResendReview: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .border(1.dp, BorderSoft, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar square
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceTinted),
            contentAlignment = Alignment.Center
        ) {
            Text(
                message.contactName.firstOrNull()?.uppercase() ?: "?",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = RosePrimary
            )
        }

        // Content
        Column(modifier = Modifier.weight(1f)) {
            // Name + date/time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    message.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatScheduledTime(message),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(2.dp))

            // Preview line with type icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TypeIconSmall(message)
                Text(
                    getPreviewText(message),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(7.dp))

            // Status pill row + optional delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(message.status)

                when {
                    // Delete button only for PENDING (§2.4, §4.7)
                    message.status == MessageStatus.PENDING -> {
                        Box(
                            modifier = Modifier
                                .size(width = 26.dp, height = 26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DeleteButtonBg)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple()
                                ) { onDelete() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Cancel scheduled message",
                                tint = DeleteIconColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    // Needs Review resolution actions (§6.2): "it sent" → remove,
                    // "it didn't" → resend now with a fresh attempt budget.
                    message.status == MessageStatus.NEEDS_REVIEW -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(width = 26.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceTinted)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple()
                                    ) { onResendReview() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Replay,
                                    contentDescription = "Resend — it did not send",
                                    tint = RoseDeep,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 26.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusSentBg)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple()
                                    ) { onResolveReview() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "Mark resolved — it sent",
                                    tint = StatusSentFg,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeIconSmall(message: ScheduledMessage) {
    when {
        message.voiceNotePath != null -> Icon(
            Icons.Outlined.Mic, contentDescription = "Voice note",
            tint = TextMuted, modifier = Modifier.size(13.dp)
        )
        message.attachmentUri != null -> Icon(
            Icons.Outlined.AttachFile, contentDescription = "Attachment",
            tint = TextMuted, modifier = Modifier.size(13.dp)
        )
        else -> {}
    }
}

@Composable
fun StatusPill(status: MessageStatus, modifier: Modifier = Modifier) {
    val (bg, fg, dotColor, label, isNeedsReview) = when (status) {
        MessageStatus.PENDING -> StatusConfig(StatusPendingBg, StatusPendingFg, StatusPendingDot, "Pending", false)
        MessageStatus.CLAIMED, MessageStatus.CONNECTING,
        MessageStatus.SENDING -> StatusConfig(StatusSendingBg, StatusSendingFg, StatusSendingDot, "Sending", false)
        MessageStatus.SENT_CONFIRMED -> StatusConfig(StatusSentBg, StatusSentFg, StatusSentDot, "Sent", false)
        MessageStatus.RETRYABLE_FAILURE, MessageStatus.FINAL_FAILURE -> StatusConfig(StatusFailedBg, StatusFailedFg, StatusFailedDot, "Failed", false)
        MessageStatus.NEEDS_REVIEW -> StatusConfig(StatusNeedsReviewBg, StatusNeedsReviewFg, StatusNeedsReviewDot, "Needs Review", true)
    }

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isNeedsReview) {
            Icon(
                Icons.Outlined.Warning, contentDescription = null,
                tint = dotColor, modifier = Modifier.size(11.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg, letterSpacing = 0.2.sp)
    }
}

private data class StatusConfig(
    val bg: Color, val fg: Color, val dot: Color, val label: String, val isNeedsReview: Boolean
)

@Composable
private fun EmptyQueuePlaceholder(hasSearch: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = BorderSoft,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasSearch) "No messages match your search" else "No scheduled messages",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted),
            textAlign = TextAlign.Center
        )
        if (!hasSearch) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap + to schedule a WhatsApp message",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────
private fun formatScheduledTime(msg: ScheduledMessage): String {
    val zone = try { ZoneId.of(msg.timezoneId) } catch (e: Exception) { ZoneId.systemDefault() }
    val instant = Instant.ofEpochMilli(msg.resolvedEpochMs)
    val dt = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val tomorrow = today.plusDays(1)
    val timeStr = dt.format(DateTimeFormatter.ofPattern("h:mm a"))
    return when (dt.toLocalDate()) {
        today -> "Today, $timeStr"
        tomorrow -> "Tomorrow, $timeStr"
        else -> dt.format(DateTimeFormatter.ofPattern("MMM d")) + ", $timeStr"
    }
}

private fun getPreviewText(msg: ScheduledMessage): String = when {
    msg.voiceNotePath != null -> "Voice note"
    msg.attachmentDisplayName != null -> msg.attachmentDisplayName
    msg.messageText != null -> msg.messageText
    else -> "—"
}
