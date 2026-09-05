package com.v1per.synthx.ui.theme

import androidx.compose.ui.graphics.Color

// Structure
val Background = Color(0xFF050F06)
val Surface = Color(0xFF0A1F0C)
val SurfaceVariant = Color(0xFF0F2E12)

// Brand
val Accent = Color(0xFF1AE62B)   // HSL(125, 80%, 50%) — electric neon green, the sacred color
val OnAccent = Color(0xFF0F2E12)

// Text
val TextPrimary = Color(0xFFF0FAF1)
val TextSecondary = Color(0xFFE0F5E2)
val TextTertiary = Color(0xFFD1F0D4)

// Structural
// Derived from Accent, never a standalone hex — it must follow the accent automatically.
val Hairline = Accent.copy(alpha = 0.08f)  // neon at 8% alpha — 1dp dividers and borders

// Status
val Error = Color(0xFFEE6363)  // HSL(360, 80%, 66%)
