package com.kabshah.delivra.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Dusty Rose palette (user-directed: Soft/dusty pink combo, no bright white) ───
// Primary rose accent range (Dark button color like in the screenshot)
val RosePrimary = Color(0xFFBE8787)        // muted blush rose — primary accent
val RoseLight = Color(0xFFD4A3A3)          // lighter for gradients
val RoseDark = Color(0xFFA87171)           // darker for gradients/pressed
val RoseDeep = Color(0xFF905959)           // deep rose, secondary labels

// Surface colors — strictly no white surfaces.
val SurfaceBase = Color(0xFFF0D6D0)        // base app background — exact requested shade
val SurfaceCard = Color(0xFFF0D6D0)        // cards — same as background per user request
val SurfaceTinted = Color(0xFFE8C8C3)      // deepest surface — just enough tint for avatars/icons to show
val SurfaceInputBg = Color(0xFFF0D6D0)     // inputs — same as background
// Borders — a soft, delicate, complementary line to frame the flat elements
val BorderSoft = Color(0xFFE4BEB7)         // very light, delicate border for cards
val BorderInput = Color(0xFFDBABA3)        // slightly more visible for inputs
val BorderContact = Color(0xFFDBABA3)
val BorderDash = Color(0xFFDFAFA9)

// Text colors — warm tinted darks (NO pure black)
val TextPrimary = Color(0xFF4A3B39)        // warm dark neutral — primary text/icons
val TextSecondary = Color(0xFF7D6B68)      // secondary body
val TextMuted = Color(0xFFA1908D)          // placeholder / muted labels
val TextCaption = Color(0xFFB09F9C)        // section labels, captions

// Status colors — within the dusty rose palette
// Pending — warm amber
val StatusPendingBg = Color(0xFFFBEDD9)
val StatusPendingFg = Color(0xFFB8792E)
val StatusPendingDot = Color(0xFFE8A54B)

// Sending — soft rose
val StatusSendingBg = Color(0xFFF3E4E1)
val StatusSendingFg = Color(0xFFA8645D)
val StatusSendingDot = Color(0xFFC98F8A)

// Sent — sage/muted green
val StatusSentBg = Color(0xFFE7EFE4)
val StatusSentFg = Color(0xFF5E7A57)
val StatusSentDot = Color(0xFF8FA88C)

// Failed — muted brick-red
val StatusFailedBg = Color(0xFFF6E4E1)
val StatusFailedFg = Color(0xFFB85C4F)
val StatusFailedDot = Color(0xFFB85C4F)

// Needs Review — muted mustard/ochre
val StatusNeedsReviewBg = Color(0xFFF6EFD9)
val StatusNeedsReviewFg = Color(0xFF96771A)
val StatusNeedsReviewDot = Color(0xFFC9A227)

// Cancel/delete button (Pending card only, §2.4)
val DeleteButtonBg = Color(0xFFF6E4E1)
val DeleteIconColor = Color(0xFFB85C4F)

// FAB and Schedule Msg buttons — User wants solid dark button exactly like "Get Pairing Code"
val FabGradientStart = Color(0xFFBE8787)
val FabGradientEnd = Color(0xFFBE8787)
