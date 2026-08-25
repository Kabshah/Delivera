package com.kabshah.delivra.viewmodel

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kabshah.delivra.bridge.NodeBridge
import com.kabshah.delivra.bridge.WhatsAppContact
import com.kabshah.delivra.data.MessageStatus
import com.kabshah.delivra.data.PhoneContactsRepository
import com.kabshah.delivra.data.ScheduleRepository
import com.kabshah.delivra.data.ScheduledMessage
import com.kabshah.delivra.ui.screens.NewMessageFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

// ─── UI State ────────────────────────────────────────────────────────────────
data class HomeUiState(
    val messages: List<ScheduledMessage> = emptyList(),
    val pendingCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    val reviewCount: Int = 0,
    val searchQuery: String = "",
    val hasExactAlarmPermission: Boolean = true,
    val hasBatteryOptExemption: Boolean = true
)

data class NewMessageUiState(
    val formState: NewMessageFormState = NewMessageFormState(),
    val contactSuggestions: List<WhatsAppContact> = emptyList(),
    val isScheduling: Boolean = false,
    val errorMessage: String? = null,
    val scheduledSuccess: Boolean = false
)

data class PairingUiState(
    val phoneNumber: String = "",
    val pairingCode: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLinked: Boolean = false
)

// ── HomeViewModel ─────────────────────────────────────────────────────────────
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _permissionTick = MutableStateFlow(0)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAll(),
        repository.observePendingCount(),
        repository.observeSentCount(),
        repository.observeFailedCount(),
        repository.observeNeedsReviewCount(),
        _searchQuery,
        _permissionTick
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val messages = arr[0] as List<ScheduledMessage>
        val pending = arr[1] as Int
        val sent = arr[2] as Int
        val failed = arr[3] as Int
        val review = arr[4] as Int
        val query = arr[5] as String
        HomeUiState(
            messages = messages,
            pendingCount = pending,
            sentCount = sent,
            failedCount = failed,
            reviewCount = review,
            searchQuery = query,
            hasExactAlarmPermission = checkExactAlarmPermission(),
            hasBatteryOptExemption = checkBatteryOptExemption()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun onSearchQueryChange(q: String) { _searchQuery.value = q }

    /** Called when Home comes back to the foreground — re-checks permission
     *  flags so the battery banner disappears right after the user grants. */
    fun onScreenResumed() { _permissionTick.value += 1 }

    /**
     * Toggle handler from the Home screen.
     * ON  → opens the one-tap system dialog to exempt Delivra from Doze/battery
     *       optimization (what makes scheduled sends survive a locked screen).
     * OFF → Android gives an app no API to revoke its own exemption, so open
     *       the system list where the user can remove it themselves.
     */
    fun onToggleReliableDelivery(enable: Boolean) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (enable && pm.isIgnoringBatteryOptimizations(context.packageName)) return
        try {
            context.startActivity(
                android.content.Intent(
                    if (enable) android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    else android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    if (enable) android.net.Uri.parse("package:${context.packageName}") else null
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Some OEM ROMs strip the direct dialog — fall back to the list UI.
            try {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* nothing else we can do */ }
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch {
            repository.cancelPendingMessage(id)
        }
    }

    /** §6.2: user confirmed the Needs Review message actually sent — remove it. */
    fun resolveNeedsReview(id: String) {
        viewModelScope.launch {
            repository.resolveNeedsReview(id)
        }
    }

    /** §6.2: user confirmed the Needs Review message did NOT send — resend now. */
    fun resendNeedsReview(id: String) {
        viewModelScope.launch {
            repository.resendNeedsReview(id)
        }
    }

    private fun checkExactAlarmPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    private fun checkBatteryOptExemption(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

// ── NewMessageViewModel ─────────────────────────────────────────────────────
@HiltViewModel
class NewMessageViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val nodeBridge: NodeBridge,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewMessageUiState())
    val uiState: StateFlow<NewMessageUiState> = _uiState.asStateFlow()

    private var allContacts: List<WhatsAppContact> = emptyList()
    private var phoneContacts: List<WhatsAppContact> = emptyList()

    init {
        // Fetch contacts immediately and re-fetch whenever the connection becomes live
        viewModelScope.launch {
            nodeBridge.connectionState.collect { state ->
                if (state == NodeBridge.ConnectionState.CONNECTED) {
                    allContacts = nodeBridge.getContacts()
                }
            }
        }
    }

    /** Called by the UI once READ_CONTACTS has been granted. */
    fun onContactsPermissionGranted() {
        if (phoneContacts.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            phoneContacts = com.kabshah.delivra.data.PhoneContactsRepository(context).load()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateContactSuggestions(_uiState.value.formState.contactSearchQuery)
            }
        }
    }

    fun onFormChange(newState: NewMessageFormState) {
        _uiState.update { it.copy(formState = newState, errorMessage = null) }
        updateContactSuggestions(newState.contactSearchQuery)
    }

    /**
     * Merged suggestion source: phone contacts (every saved person + photo)
     * overlaid with Baileys-synced WhatsApp contacts. Matching is done on
     * normalized digits so "+92 3XX…" and "9233…@s.whatsapp.net" dedupe.
     */
    private fun mergedContacts(): List<WhatsAppContact> {
        if (phoneContacts.isEmpty()) return allContacts
        val merged = LinkedHashMap<String, WhatsAppContact>()
        phoneContacts.forEach { c -> merged[PhoneContactsRepository.matchKey(c.jid)] = c }
        allContacts.forEach { b ->
            val key = PhoneContactsRepository.matchKey(b.jid)
            val existing = merged[key]
            merged[key] = when {
                existing == null -> b
                existing.name.isBlank() -> b.copy(photoUri = existing.photoUri ?: b.photoUri)
                else -> existing.copy(photoUri = existing.photoUri ?: b.photoUri)
            }
        }
        return merged.values.toList()
    }

    private fun updateContactSuggestions(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(contactSuggestions = emptyList()) }
            return
        }

        val filtered = mergedContacts().filter {
            it.name.contains(trimmed, ignoreCase = true) || it.jid.contains(trimmed)
        }

        val resultList = filtered.toMutableList()
        val digitsOnly = trimmed.filter { it.isDigit() }

        if (digitsOnly.length >= 7 && resultList.none { PhoneContactsRepository.samePerson(it.jid, digitsOnly) }) {
            val formattedJid = "$digitsOnly@s.whatsapp.net"
            resultList.add(0, WhatsAppContact(name = "Use number: $trimmed", jid = formattedJid, initial = "📱"))
        } else if (resultList.isEmpty() && trimmed.length >= 2) {
            resultList.add(WhatsAppContact(name = "Use: $trimmed", jid = "${trimmed.filter { it.isLetterOrDigit() }}@s.whatsapp.net", initial = "👤"))
        }

        _uiState.update { it.copy(contactSuggestions = resultList) }
    }

    fun scheduleMessage() {
        val form = _uiState.value.formState
        // Validation
        if (form.selectedContact == null) {
            _uiState.update { it.copy(errorMessage = "Please select a contact") }
            return
        }
        if (form.messageText.isBlank() && form.voiceNotePath == null && form.attachmentUri == null) {
            _uiState.update { it.copy(errorMessage = "Add a message, voice note, or attachment") }
            return
        }

        val zone = ZoneId.systemDefault()
        val localDt = LocalDateTime.of(form.selectedDate, form.selectedTime)
        val epochMs = localDt.atZone(zone).toInstant().toEpochMilli()

        if (epochMs <= System.currentTimeMillis()) {
            val formattedTime = form.selectedTime.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            _uiState.update { 
                it.copy(errorMessage = "Selected time ($formattedTime) is in the past! Please check AM/PM or pick a future time.") 
            }
            return
        }

        _uiState.update { it.copy(isScheduling = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val msg = ScheduledMessage(
                    contactName = form.selectedContact.name,
                    contactJid = form.selectedContact.jid,
                    messageText = form.messageText.ifBlank { null },
                    voiceNotePath = form.voiceNotePath,
                    attachmentUri = form.attachmentUri,
                    attachmentDisplayName = form.attachmentDisplayName,
                    attachmentMimeType = form.attachmentMimeType,
                    attachmentSizeBytes = form.attachmentSizeBytes,
                    scheduledLocalDateTime = localDt.toString(),
                    timezoneId = zone.id,
                    resolvedEpochMs = epochMs,
                    status = MessageStatus.PENDING,
                    createdAtEpochMs = System.currentTimeMillis()
                )
                repository.scheduleMessage(msg)
                _uiState.update { it.copy(isScheduling = false, scheduledSuccess = true) }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleMessage error: ${e.message}", e)
                _uiState.update { it.copy(isScheduling = false, errorMessage = "Failed to schedule: ${e.message}") }
            }
        }
    }

    fun resetSuccess() {
        _uiState.update { it.copy(scheduledSuccess = false, formState = NewMessageFormState()) }
    }

    companion object { private const val TAG = "NewMessageViewModel" }
}

// ── PairingViewModel ──────────────────────────────────────────────────────────
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val nodeBridge: NodeBridge,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            nodeBridge.connectionState.collect { state ->
                _uiState.update { it.copy(isLinked = state == NodeBridge.ConnectionState.CONNECTED) }
            }
        }
    }

    fun onPhoneNumberChange(num: String) {
        _uiState.update { it.copy(phoneNumber = num, errorMessage = null) }
    }

    fun requestPairingCode() {
        val rawPhone = _uiState.value.phoneNumber.trim()
        val cleanPhone = rawPhone.filter { it.isDigit() }
        if (cleanPhone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter phone number") }
            return
        }
        if (cleanPhone.startsWith("0")) {
            _uiState.update { it.copy(errorMessage = "Please include country code without '+' (e.g. 92300... instead of 0300...)") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                // Bring up Node.js engine and keep it alive while UI is pairing
                val intent = android.content.Intent(context, com.kabshah.delivra.service.SchedulerService::class.java).apply {
                    putExtra("keep_alive", true)
                }
                context.startForegroundService(intent)
                
                // Wait for Node TCP channel to initialize
                val channelDeadline = System.currentTimeMillis() + 60_000L
                while (!nodeBridge.isChannelInitialized && System.currentTimeMillis() < channelDeadline) {
                    kotlinx.coroutines.delay(200)
                }
                if (!nodeBridge.isChannelInitialized) {
                    throw IllegalStateException("Node.js engine initializing... Please try again in 5 seconds.")
                }

                // connect() now returns immediately (non-blocking ack).
                // It kicks off the Baileys WebSocket connection in the background.
                nodeBridge.connect()

                // connect() is fire-and-forget — it tells Node to start the Baileys
                // WebSocket connection. Give Baileys a few seconds to initialize the
                // WebSocket before calling requestPairingCode. sock.requestPairingCode()
                // handles its own socket readiness internally, but needs the socket object
                // to exist first. 4s is safe — Baileys connects in 1-3s on good networks.
                kotlinx.coroutines.delay(4000)

                val code = nodeBridge.requestPairingCode(cleanPhone)
                _uiState.update { it.copy(isLoading = false, pairingCode = code) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to get code") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        context.stopService(android.content.Intent(context, com.kabshah.delivra.service.SchedulerService::class.java))
    }
}
