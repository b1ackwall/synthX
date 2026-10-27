package com.v1per.synthx.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height reserved below the art for the regular player's title/artist row. Canonical
 * declaration used by PlayerSkeletonExpandedContent.kt (the title row's current home) and
 * the regular-player geometry in QueueArtPositioning.kt's computeArtPosition.
 *
 * Internal rather than private: `internal` in Kotlin is module-wide, not package-scoped, so
 * this is importable from ui/components/ despite the different package.
 */
internal val TitleRowReservedHeight: Dp = 52.dp

/**
 * Gap between the expanded sheet's top edge and the art region.
 */
internal val ExpandedTopMargin: Dp = 32.dp

/**
 * Fixed footprint of the bottom controls overlay (seek bar, transport controls, queue
 * placeholder). Used *without* adding a navigation-bar inset on top — PlayerSheetSkeleton
 * is hosted inside SynthXBottomNav's Box, which already applies `.navigationBarsPadding()`
 * around both the nav and the sheet, so the inset is already excluded from this composable's
 * own `maxHeight`. Re-adding it here would double-count it.
 */
internal val ControlsOverlayHeight: Dp = 300.dp

/**
 * How far below its resting position an alpha-faded block starts before sliding up into
 * place. Queue mode's header/list block (PlayerSkeletonQueueContent) travels this distance
 * as its slide term runs 0→1; the controls overlay travels with the sheet instead and uses
 * this only until the sheet's anchors exist (see PlayerSheetMotion.kt's controlsOverlaySlide).
 *
 * Internal rather than private for the same reason [TitleRowReservedHeight] above is —
 * top-level `private` in Kotlin is file-private, and both consumers live in other files.
 * Declared here, alongside the sheet's other layout constants, rather than duplicated as a
 * bare `40.dp` literal in each consumer.
 *
 * Deliberately not applied to the album art (its own mini↔expanded↔queue morph already owns
 * its position and size) or to the player-mode title row (whose slide is emergent from the
 * art/sheet geometry — see QueueArtPositioning.kt's titleRowY derivation).
 */
internal val ContentSlideDistance: Dp = 40.dp

/**
 * 20% of the full collapsed↔expanded travel, not the 0.5f used by QueueRow. Here the travel is
 * nearly the whole screen, so a larger fraction would make a slow, non-flick drag cover
 * ~294dp before it snaps — far more than a deliberate drag feels like it should need.
 * Top-level so [AnchoredDraggableDefaults.flingBehavior]'s `remember` key stays stable.
 *
 * The velocity threshold is [AnchoredDraggableDefaults.flingBehavior]'s hardcoded 125.dp/s
 * (above it, the positional test is skipped), and it never decays. Changing either needs a
 * custom fling behavior.
 */
internal val PlayerSheetPositionalThreshold: (Float) -> Float = { totalDistance -> totalDistance * 0.2f }
