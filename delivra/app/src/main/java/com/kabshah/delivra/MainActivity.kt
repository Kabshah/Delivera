package com.kabshah.delivra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kabshah.delivra.bridge.NodeBridge
import com.kabshah.delivra.diagnostics.EventRingBuffer
import com.kabshah.delivra.ui.screens.*
import com.kabshah.delivra.ui.theme.DelivraTheme
import com.kabshah.delivra.ui.theme.SurfaceBase
import com.kabshah.delivra.viewmodel.HomeViewModel
import com.kabshah.delivra.viewmodel.NewMessageViewModel
import com.kabshah.delivra.viewmodel.PairingViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// ─── Navigation routes ───────────────────────────────────────────────────────
object NavRoutes {
    const val HOME = "home"
    const val NEW_MESSAGE = "new_message"
    const val PAIRING = "pairing"
    const val SETTINGS = "settings"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Injected here so SettingsScreen can access live bridge/buffer state
    @Inject lateinit var nodeBridge: NodeBridge
    @Inject lateinit var ringBuffer: EventRingBuffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DelivraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SurfaceBase) {
                    DelivraNavGraph(nodeBridge = nodeBridge, ringBuffer = ringBuffer)
                }
            }
        }
    }
}

@Composable
fun DelivraNavGraph(
    nodeBridge: NodeBridge,
    ringBuffer: EventRingBuffer
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = NavRoutes.HOME) {

        composable(NavRoutes.HOME) {
            val vm: HomeViewModel = hiltViewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            // Re-check permission flags every time Home resumes, so the
            // battery banner clears immediately after the user grants it.
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.onScreenResumed()
            }

            HomeScreen(
                messages = state.messages,
                pendingCount = state.pendingCount,
                sentCount = state.sentCount,
                failedCount = state.failedCount,
                reviewCount = state.reviewCount,
                searchQuery = state.searchQuery,
                onSearchQueryChange = vm::onSearchQueryChange,
                onNewMessage = { navController.navigate(NavRoutes.NEW_MESSAGE) },
                onDeleteMessage = vm::deleteMessage,
                onResolveNeedsReview = vm::resolveNeedsReview,
                onResendNeedsReview = vm::resendNeedsReview,
                onMessageClick = { /* TODO: detail screen */ },
                onLinkWhatsApp = { navController.navigate(NavRoutes.PAIRING) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                hasBatteryOptExemption = state.hasBatteryOptExemption,
                onToggleReliableDelivery = vm::onToggleReliableDelivery,
                modifier = Modifier.fillMaxSize()
            )
        }

        composable(NavRoutes.NEW_MESSAGE) {
            val vm: NewMessageViewModel = hiltViewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            // Navigate back after success
            LaunchedEffect(state.scheduledSuccess) {
                if (state.scheduledSuccess) {
                    vm.resetSuccess()
                    navController.popBackStack()
                }
            }

            NewMessageScreen(
                formState = state.formState,
                contactSuggestions = state.contactSuggestions,
                onFormChange = vm::onFormChange,
                onSchedule = vm::scheduleMessage,
                onBack = { navController.popBackStack() },
                onContactsPermissionReady = vm::onContactsPermissionGranted,
                isScheduling = state.isScheduling,
                errorMessage = state.errorMessage,
                modifier = Modifier.fillMaxSize()
            )
        }

        composable(NavRoutes.PAIRING) {
            val vm: PairingViewModel = hiltViewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.isLinked) {
                if (state.isLinked) navController.navigate(NavRoutes.HOME) {
                    popUpTo(NavRoutes.PAIRING) { inclusive = true }
                }
            }

            PairingScreen(
                phoneNumber = state.phoneNumber,
                pairingCode = state.pairingCode,
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                onPhoneNumberChange = vm::onPhoneNumberChange,
                onRequestCode = vm::requestPairingCode,
                modifier = Modifier.fillMaxSize()
            )
        }

        composable(NavRoutes.SETTINGS) {
            val connectionState by nodeBridge.connectionState.collectAsStateWithLifecycle()
            SettingsScreen(
                connectionState = connectionState,
                ringBuffer = ringBuffer,
                onBack = { navController.popBackStack() },
                onLinkWhatsApp = {
                    navController.navigate(NavRoutes.PAIRING)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
