package com.v1per.synthx.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the collapsed mini-player bar. Exposed so MainScaffold can reserve the
 * matching bottom inset on scrollable content behind the sheet.
 */
val CollapsedPlayerHeight: Dp = 64.dp

/**
 * Footprint of the bottom nav (70dp NavigationBar + 1dp divider) the collapsed sheet clears.
 * Public so BottomChromeOverlay's nav slide-offset and MainScaffold's content padding share
 * this exact value instead of duplicating the magic number — any drift between them shows up
 * as a visible seam or overlap between the mini player and the nav.
 */
val BottomNavHeight: Dp = 71.dp

enum class PlayerSheetValue { Collapsed, Expanded }
