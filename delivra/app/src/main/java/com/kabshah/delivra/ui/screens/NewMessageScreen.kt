package com.kabshah.delivra.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ripple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kabshah.delivra.attachments.AttachmentAccess
import com.kabshah.delivra.attachments.VoiceNoteRecorder
import com.kabshah.delivra.bridge.WhatsAppContact
import com.kabshah.delivra.ui.theme.*

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

import android.text.format.DateFormat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class NewMessageFormState(
    val selectedContact: WhatsAppContact? = null,
    val messageText: String = "",
    val voiceNotePath: String? = null,
    val attachmentUri: String? = null,
    val attachmentDisplayName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedTime: LocalTime = LocalTime.now().plusMinutes(15).withSecond(0).withNano(0),
    val contactSearchQuery: String = ""
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NewMessageScreen(
    formState: NewMessageFormState,
    contactSuggestions: List<WhatsAppContact>,
    onFormChange: (NewMessageFormState) -> Unit,
    onSchedule: () -> Unit,
    onBack: () -> Unit,
    onContactsPermissionReady: () -> Unit = {},
    isScheduling: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── Contact photos (READ_CONTACTS) ────────────────────────────────
    val contactsPermission = rememberPermissionState(android.Manifest.permission.READ_CONTACTS)
    LaunchedEffect(Unit) {
        if (!contactsPermission.status.isGranted) contactsPermission.launchPermissionRequest()
    }
    LaunchedEffect(contactsPermission.status) {
        if (contactsPermission.status.isGranted) onContactsPermissionReady()
    }

    // ── Voice note recording state (§4.3) ─────────────────────────────
    var voiceError by remember { mutableStateOf<String?>(null) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingElapsedSec by remember { mutableIntStateOf(0) }
    var voiceDurationSec by remember { mutableIntStateOf(0) }
    var pendingVoicePath by remember { mutableStateOf<String?>(null) }
    var showVoiceNoteDialog by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceNoteRecorder(context) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                pendingVoicePath = voiceRecorder.start()
                isRecordingVoice = true
                recordingElapsedSec = 0
            } catch (e: Exception) {
                voiceError = "Couldn't start recording: ${e.message ?: "unknown error"}"
            }
        } else {
            voiceError = "Microphone permission is needed to record voice notes"
        }
    }

    // Stops, validates, and attaches the finished note to the form.
    fun stopVoiceRecording() {
        val path = pendingVoicePath
        val duration = voiceRecorder.stop()
        pendingVoicePath = null
        if (duration == null || path == null) {
            voiceError = "Recording too short — nothing saved. Tap the mic and record again."
            return
        }
        voiceDurationSec = (duration / 1000).toInt()
        VoiceNoteRecorder.deleteFile(formState.voiceNotePath) // replace any earlier take
        onFormChange(formState.copy(voiceNotePath = path))
    }

    fun onVoiceCardTap() {
        when {
            isRecordingVoice -> {
                isRecordingVoice = false
                stopVoiceRecording()
                recordingElapsedSec = 0
            }
            formState.voiceNotePath != null -> showVoiceNoteDialog = true
            !VoiceNoteRecorder.isSupported() ->
                voiceError = "Voice notes require Android 10 or newer"
            else -> micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Recording ticker; also enforces the 5-minute hard cap (recorder's
    // setMaxDuration is the safety net underneath this).
    LaunchedEffect(isRecordingVoice) {
        val capSec = (VoiceNoteRecorder.MAX_DURATION_MS / 1000).toInt()
        while (isRecordingVoice && recordingElapsedSec < capSec) {
            kotlinx.coroutines.delay(1000)
            if (!isRecordingVoice) break
            recordingElapsedSec += 1
            if (recordingElapsedSec >= capSec) {
                isRecordingVoice = false
                stopVoiceRecording()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen mid-recording must never leak a live mic
            voiceRecorder.cancel()
        }
    }

    if (showVoiceNoteDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceNoteDialog = false },
            title = { Text("Voice note", fontWeight = FontWeight.Bold) },
            text = { Text("You've already recorded a voice note. What would you like to do?") },
            confirmButton = {
                TextButton(onClick = {
                    showVoiceNoteDialog = false
                    voiceDurationSec = 0
                    VoiceNoteRecorder.deleteFile(formState.voiceNotePath)
                    onFormChange(formState.copy(voiceNotePath = null))
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }) { Text("Re-record", color = StatusFailedFg) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showVoiceNoteDialog = false
                        voiceDurationSec = 0
                        VoiceNoteRecorder.deleteFile(formState.voiceNotePath)
                        onFormChange(formState.copy(voiceNotePath = null))
                    }) { Text("Delete") }
                    TextButton(onClick = { showVoiceNoteDialog = false }) { Text("Keep") }
                }
            }
        )
    }

    // File picker — GET_CONTENT chooser (shows My Files, Photos, Drive, etc.
    // like other apps). The picked file is IMPORTED into app storage at attach
    // time (AttachmentAccess.importAndStage), so the send never depends on the
    // temporary URI grant, even days later.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val meta = AttachmentAccess.importAndStage(context, uri)
            if (meta != null) {
                onFormChange(
                    formState.copy(
                        attachmentUri = meta.uri,
                        attachmentDisplayName = meta.displayName,
                        attachmentMimeType = meta.mimeType,
                        attachmentSizeBytes = meta.sizeBytes
                    )
                )
            }
            // If meta == null: file was unreadable at attach time — silently ignore,
            // as AttachmentAccess already logged the failure.
        }
    }

    Column(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        // ── Header ────────────────────────────────────────────────────────
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
            Text("New Message", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Divider(color = BorderSoft, thickness = 1.dp)

        // ── Scrollable form body ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // ── TO section ────────────────────────────────────────────────
            FormSection(label = "TO") {
                if (formState.selectedContact != null) {
                    // Selected contact chip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceTinted, RoundedCornerShape(14.dp))
                            .border(1.5.dp, BorderContact, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(RosePrimary, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                formState.selectedContact.initial,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            formState.selectedContact.name,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove contact",
                            tint = RoseDeep,
                            modifier = Modifier
                                .size(15.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onFormChange(formState.copy(selectedContact = null, contactSearchQuery = "")) }
                        )
                    }
                } else {
                    // Contact search input
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceTinted, RoundedCornerShape(14.dp))
                                .border(1.5.dp, BorderContact, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null,
                                tint = TextMuted, modifier = Modifier.size(15.dp))
                            BasicTextField(
                                value = formState.contactSearchQuery,
                                onValueChange = { onFormChange(formState.copy(contactSearchQuery = it)) },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                                singleLine = true,
                                decorationBox = { inner ->
                                    if (formState.contactSearchQuery.isEmpty()) {
                                        Text("Search contacts…",
                                            style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted))
                                    }
                                    inner()
                                }
                            )
                        }
                        // Contact suggestions dropdown
                        if (contactSuggestions.isNotEmpty() && formState.contactSearchQuery.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceCard, RoundedCornerShape(14.dp))
                                    .border(1.dp, BorderSoft, RoundedCornerShape(14.dp))
                            ) {
                                contactSuggestions.take(5).forEach { contact ->
                                    ContactSuggestionRow(contact) {
                                        onFormChange(formState.copy(selectedContact = contact, contactSearchQuery = ""))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── MESSAGE section ────────────────────────────────────────────
            FormSection(label = "MESSAGE") {
                BasicTextField(
                    value = formState.messageText,
                    onValueChange = { onFormChange(formState.copy(messageText = it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceInputBg, RoundedCornerShape(14.dp))
                        .border(1.dp, BorderInput, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .heightIn(min = 70.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    decorationBox = { inner ->
                        if (formState.messageText.isEmpty()) {
                            Text("Type your message…",
                                style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted))
                        }
                        inner()
                    }
                )
            }

            // ── ATTACH section ─────────────────────────────────────────────
            FormSection(label = "ATTACH") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Voice note card (dashed border when empty; live state while recording)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.5.dp,
                                if (isRecordingVoice) StatusFailedFg else BorderDash,
                                RoundedCornerShape(14.dp)
                            )
                            .background(if (isRecordingVoice) StatusFailedBg else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
                            ) { onVoiceCardTap() }
                            .padding(vertical = 14.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isRecordingVoice) {
                                // Pulsing red dot + elapsed time
                                val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
                                val pulseAlpha by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.25f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(600),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "recPulseAlpha"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .alpha(pulseAlpha)
                                        .background(StatusFailedFg, RoundedCornerShape(9.dp))
                                )
                                Text(
                                    String.format("%d:%02d", recordingElapsedSec / 60, recordingElapsedSec % 60) + " — tap to stop",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusFailedFg
                                )
                            } else {
                                Icon(Icons.Outlined.Mic, contentDescription = "Voice note",
                                    tint = RosePrimary, modifier = Modifier.size(18.dp))
                                Text(
                                    if (formState.voiceNotePath != null) {
                                        "Recorded ✓ " + String.format("(%d:%02d)", voiceDurationSec / 60, voiceDurationSec % 60)
                                    } else "Voice note",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (formState.voiceNotePath != null) StatusSentFg else RoseDeep
                                )
                            }
                        }
                    }

                    // File attachment card — solid soft-rose per wp_brain §2.4
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (formState.attachmentUri != null) SurfaceTinted else SurfaceInputBg,
                                RoundedCornerShape(14.dp)
                            )
                            .border(1.dp, BorderContact, RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
                            ) {
                                filePicker.launch("*/*")
                            }
                            .padding(vertical = 14.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach file",
                                tint = RosePrimary, modifier = Modifier.size(18.dp))
                            Text(
                                formState.attachmentDisplayName ?: "Attach file",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RoseDeep,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ── SEND AT section ────────────────────────────────────────────
            FormSection(label = "SEND AT") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Date picker chip
                    val dateText = formatDateLabel(formState.selectedDate)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(SurfaceInputBg, RoundedCornerShape(14.dp))
                            .border(1.dp, BorderInput, RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
                            ) {
                                val now = formState.selectedDate
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        onFormChange(formState.copy(selectedDate = LocalDate.of(y, m + 1, d)))
                                    },
                                    now.year, now.monthValue - 1, now.dayOfMonth
                                ).show()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null,
                            tint = RosePrimary, modifier = Modifier.size(15.dp))
                        Text(dateText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }

                    // Time picker chip
                    val timeText = formState.selectedTime.format(DateTimeFormatter.ofPattern("h:mm a"))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(SurfaceInputBg, RoundedCornerShape(14.dp))
                            .border(1.dp, BorderInput, RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
                            ) {
                                TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        onFormChange(formState.copy(selectedTime = LocalTime.of(h, m)))
                                    },
                                    formState.selectedTime.hour,
                                    formState.selectedTime.minute,
                                    DateFormat.is24HourFormat(context)
                                ).show()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null,
                            tint = RosePrimary, modifier = Modifier.size(15.dp))
                        Text(timeText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                }
            }

            // Error message
            if (errorMessage != null) {
                Text(
                    errorMessage,
                    fontSize = 13.sp,
                    color = StatusFailedFg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusFailedBg, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            // Voice-note specific error (recording failures, permission denial)
            val voiceErrorMsg = voiceError
            if (voiceErrorMsg != null && errorMessage == null) {
                Text(
                    voiceErrorMsg,
                    fontSize = 13.sp,
                    color = StatusFailedFg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusFailedBg, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // ── Bottom CTA ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 22.dp)
        ) {
            Divider(color = BorderSoft, thickness = 1.dp, modifier = Modifier.padding(bottom = 14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(14.dp),
                        ambientColor = RosePrimary.copy(alpha = 0.45f),
                        spotColor = RosePrimary.copy(alpha = 0.6f)
                    )
                    .background(
                        Brush.linearGradient(
                            if (isRecordingVoice) listOf(SurfaceTinted, SurfaceTinted)
                            else listOf(FabGradientStart, FabGradientEnd)
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(
                        enabled = !isScheduling && !isRecordingVoice,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple()
                    ) { onSchedule() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecordingVoice -> Text(
                        "Stop the recording first — tap the mic card",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusFailedFg
                    )
                    isScheduling -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Text(
                        "Schedule Message",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
@Composable
private fun FormSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextCaption,
            letterSpacing = 0.4.sp
        )
        content()
    }
}

@Composable
private fun ContactSuggestionRow(contact: WhatsAppContact, onClick: () -> Unit) {
    val context = LocalContext.current
    val photoBitmap = remember(contact.photoUri) {
        contact.photoUri?.let { uriString ->
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(uriString),
                    android.util.Size(96, 96),
                    null
                )
            }.getOrElse {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(SurfaceTinted, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(contact.initial, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RosePrimary)
            }
        }
        Text(contact.name, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
