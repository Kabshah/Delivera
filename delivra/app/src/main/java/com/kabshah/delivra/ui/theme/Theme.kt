package com.kabshah.delivra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Delivra custom Material 3 color scheme — Dusty Rose + White (§3).
 * Defined once here, applied everywhere. Never use hardcoded colors outside Color.kt.
 */
private val DelivraColorScheme = lightColorScheme(
    primary = RosePrimary,
    onPrimary = Color.White,
    primaryContainer = SurfaceTinted,
    onPrimaryContainer = TextPrimary,

    secondary = RoseDeep,
    onSecondary = Color.White,
    secondaryContainer = SurfaceTinted,
    onSecondaryContainer = TextPrimary,

    background = SurfaceBase,
    onBackground = TextPrimary,

    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceTinted,
    onSurfaceVariant = TextSecondary,

    outline = BorderSoft,
    outlineVariant = BorderInput,

    error = StatusFailedFg,
    onError = Color.White,

    // No tertiary in this design — map to rose for consistency
    tertiary = RoseLight,
    onTertiary = Color.White,
)

@Composable
fun DelivraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DelivraColorScheme,
        typography = DelivraTypography,
        content = content
    )
}
