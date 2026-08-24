package com.kabshah.delivra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Manrope font family (matches the JSX reference: 'Manrope', 'Inter', system-ui)
// Using system default here - add font files to res/font/ for production
val ManropeFamily = FontFamily.Default  // replace with FontFamily(Font(R.font.manrope_*)) once files added

val DelivraTypography = Typography(
    // Large heading: "Queue" — 25sp, bold
    headlineLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        letterSpacing = (-0.3).sp,
        color = TextPrimary
    ),
    // Eyebrow: "DELIVRA" — 11sp, semibold, uppercase
    labelSmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = RosePrimary
    ),
    // Section labels: "TO", "MESSAGE"
    labelMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        letterSpacing = 0.4.sp,
        color = TextCaption
    ),
    // Contact name in card
    titleMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        color = TextPrimary
    ),
    // Body text / message preview
    bodyMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        color = TextPrimary
    ),
    // Small preview / date text
    bodySmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        color = TextSecondary
    ),
    // CTA button text
    labelLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        letterSpacing = 0.2.sp,
        color = TextPrimary
    ),
)
