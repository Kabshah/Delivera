package com.kabshah.delivra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kabshah.delivra.ui.theme.*

/**
 * WhatsApp pairing/linking screen (§4.1).
 *
 * User enters their WhatsApp number → app requests pairing code from Baileys →
 * displays the 8-char code → user enters it via WhatsApp > Linked Devices.
 * One-time manual step; session persists locally after this.
 */
@Composable
fun PairingScreen(
    phoneNumber: String,
    pairingCode: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onPhoneNumberChange: (String) -> Unit,
    onRequestCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBase)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo area
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SurfaceTinted, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = RosePrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Link WhatsApp",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your WhatsApp number to get a pairing code. Then go to WhatsApp → Settings → Linked Devices → Link with phone number.",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(28.dp))

        // Phone number input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceInputBg, RoundedCornerShape(14.dp))
                .border(1.5.dp, BorderContact, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Phone, contentDescription = null, tint = RosePrimary, modifier = Modifier.size(18.dp))
            BasicTextField(
                value = phoneNumber,
                onValueChange = onPhoneNumberChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                decorationBox = { inner ->
                    if (phoneNumber.isEmpty()) {
                        Text("+92 3XX XXX XXXX", style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted))
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(14.dp))

        // Request code button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(14.dp),
                    ambientColor = RosePrimary.copy(alpha = 0.4f),
                    spotColor = RosePrimary.copy(alpha = 0.5f))
                .background(
                    Brush.linearGradient(listOf(FabGradientStart, FabGradientEnd)),
                    RoundedCornerShape(14.dp)
                )
                .clickable(
                    enabled = !isLoading && phoneNumber.isNotBlank(),
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple()
                ) { onRequestCode() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Get Pairing Code", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        // Pairing code display
        if (pairingCode != null) {
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceTinted, RoundedCornerShape(16.dp))
                    .border(1.dp, BorderContact, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Your Pairing Code", fontSize = 12.sp, color = TextCaption, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    pairingCode,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Enter this code in WhatsApp on your phone",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Error display
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                errorMessage,
                fontSize = 13.sp,
                color = StatusFailedFg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StatusFailedBg, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
