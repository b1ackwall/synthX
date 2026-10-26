package com.v1per.synthx.ui.theme

import androidx.compose.ui.graphics.Color

// Structure
val Background = Color(0xFF080C09) // HSL(135, 20%, 4%) RGB #080C09
val Surface = Color(0xFF101811)
val SurfaceVariant = Color(0xFF182519)

// Brand
val Accent = Color(0xFF32CC3F) // HSL(125, 60%, 50%) — electric neon green, the sacred color
val OnAccent = Color(0xFF0F2E12)

// Text
val TextPrimary = Color(0xFFF3F7F3)
val TextSecondary = Color(0xFFE7EFE7)
val TextTertiary = Color(0xFFDAE7DC)

// Structural
// Derived from Accent, never a standalone hex — it must follow the accent automatically.
val Hairline = Accent.copy(alpha = 0.08f) // neon at 8% alpha — 1dp dividers and borders

// Status
val Error = Color(0xFFEE6363) // HSL(360, 80%, 66%)
