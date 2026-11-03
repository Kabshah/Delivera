package com.kabshah.delivra.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Rich Dusty Rose palette (deep rose like the README banner) ─────────────
// Primary rose accent range
val RosePrimary = Color(0xFFA85B6E)        // deep warm rose — primary accent
val RoseLight = Color(0xFFCC8A97)          // lighter for gradients & highlights
val RoseDark = Color(0xFF8E4358)           // darker for gradients/pressed states
val RoseDeep = Color(0xFF7A3748)           // deep rose, secondary labels

// Surface colors — warm rose-tinted, never white.
val SurfaceBase = Color(0xFFE8C4C0)        // warm dusty rose background — clearly rose-tinted
val SurfaceCard = Color(0xFFF0D4CF)        // cards — slightly lighter than background for lift
val SurfaceTinted = Color(0xFFDDB1AC)      // deepest surface — for avatars/icons/chips
val SurfaceInputBg = Color(0xFFF2D9D4)     // inputs — soft rose, slightly lighter than card
// Borders — warm rose lines to frame elements
val BorderSoft = Color(0xFFD9A8A0)         // card borders
val BorderInput = Color(0xFFCF9991)        // input field borders — more visible
val BorderContact = Color(0xFFCF9991)
val BorderDash = Color(0xFFD49E97)

// Text colors — warm tinted darks (NO pure black)
val TextPrimary = Color(0xFF3D2C2A)        // deep warm brown — primary text/icons
val TextSecondary = Color(0xFF6E5652)      // secondary body
val TextMuted = Color(0xFF9A8581)          // placeholder / muted labels
val TextCaption = Color(0xFFAD9B97)        // section labels, captions

// Status colors — within the dusty rose palette
// Pending — warm amber
val StatusPendingBg = Color(0xFFFBEDD9)
val StatusPendingFg = Color(0xFFB8792E)
val StatusPendingDot = Color(0xFFE8A54B)

// Sending — soft rose
val StatusSendingBg = Color(0xFFF0DAD6)
val StatusSendingFg = Color(0xFF9E524A)
val StatusSendingDot = Color(0xFFC98F8A)

// Sent — sage/muted green
val StatusSentBg = Color(0xFFE2EDDF)
val StatusSentFg = Color(0xFF527049)
val StatusSentDot = Color(0xFF8FA88C)

// Failed — muted brick-red
val StatusFailedBg = Color(0xFFF3DAD6)
val StatusFailedFg = Color(0xFFB05145)
val StatusFailedDot = Color(0xFFB05145)

// Needs Review — muted mustard/ochre
val StatusNeedsReviewBg = Color(0xFFF6EFD9)
val StatusNeedsReviewFg = Color(0xFF96771A)
val StatusNeedsReviewDot = Color(0xFFC9A227)

// Cancel/delete button (Pending card only, §2.4)
val DeleteButtonBg = Color(0xFFF3DAD6)
val DeleteIconColor = Color(0xFFB05145)

// FAB and Schedule Msg buttons — deep rose gradient for premium feel
val FabGradientStart = Color(0xFFA85B6E)
val FabGradientEnd = Color(0xFF8E4358)

