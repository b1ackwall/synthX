package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTypography

/**
 * Queue-mode header art size (44dp) — same size as the mini-player thumbnail, matching the
 * other layout constants declared in PlayerSheetSkeleton.kt (TitleRowReservedHeight,
 * ExpandedTopMargin, ControlsOverlayHeight).
 */
val QueueModeArtSize: Dp = 44.dp

/**
 * Approximate height of the (not yet ported) queue-mode header row, used only to vertically
 * center the 44dp queue art within it. The old QueueModeHeader's height was content-driven
 * (sized by its tallest child — effectively the two-line title/artist column), not a fixed
 * constant, so there is no exact source value to mirror here. [TitleRowReservedHeight] (52dp)
 * is reused as the closest existing approximation already declared in PlayerSheetSkeleton.kt
 * for a two-line title/artist block, rather than inventing a new number.
 */
val QueueHeaderRowHeight: Dp = TitleRowReservedHeight

/**
 * Height of the queue-mode header-to-playback-row region — a real Column (header Row +
 * a single leading weight(1f) spacer + [com.v1per.synthx.ui.components.QueuePlaybackModeRow],
 * built in PlayerSkeletonQueueContent) is given exactly this much bounded height so that
 * spacer has real slack to distribute. Composed from existing [Spacing] tokens rather than a
 * bespoke literal: [Spacing.xxl] for the header row's own footprint, [Spacing.RowHeight] for
 * the playback row's, and [Spacing.xl] as the slack absorbed entirely by the one leading
 * spacer. Still an approximation of the two children's real intrinsic heights — same as
 * [QueueHeaderRowHeight] above — just sourced from named tokens instead of an untethered dp
 * literal, and tunable the same way.
 */
val QueueHeaderRegionHeight: Dp = Spacing.xxl + Spacing.RowHeight + Spacing.xl

/**
 * Queue-mode header Row's real intrinsic height, as rendered in PlayerSkeletonQueueContent —
 * driven by its tallest child, the title/artist text column: one `bodyLarge` line above one
 * `bodyMedium` line, so the column is exactly those two `lineHeight`s summed.
 *
 * Read off [SynthXTypography]'s own `lineHeight` values through the supplied [Density], rather
 * than the fixed `44.dp` literal this used to be. Both line heights are declared in `sp`, so
 * the rendered column genuinely grows with the user's system font-scale setting, while a dp
 * literal froze it at whatever fontScale 1.0 happens to produce — leaving the art's queue-mode
 * position (via [QueueColumnLeadingSlack]) drifting off the text it is meant to centre against.
 * `TextUnit.toDp()` does the conversion rather than a manual `value * fontScale`, since it also
 * honours the non-linear font-scale curves Android 14+ applies to larger text. At the default
 * font scale this still resolves to exactly 44dp (24sp + 20sp), so the common case is unchanged.
 *
 * Still a derivation, not a runtime measurement: nothing here asks Compose where the Row
 * actually landed — see [QueueColumnLeadingSlack]'s doc comment for why that stays off the table.
 *
 * MUST be kept in sync with PlayerSkeletonQueueContent's actual header Row: if its content
 * changes (e.g. a third text line, different text styles), update this to match, or
 * [QueueColumnLeadingSlack] — and therefore the art's queue-mode position — silently drifts
 * out of alignment with where the header Row actually renders. One gap this deliberately does
 * not model: the 40dp MoreOptionsButton beside the text column becomes the Row's tallest child
 * once the font scale drops below roughly 0.91, so below that the real Row stops shrinking
 * while this keeps going — a bounded few-dp residual, still smaller than the frozen literal's
 * own error was at those scales.
 */
val Density.QueueHeaderRowIntrinsicHeight: Dp
    get() = SynthXTypography.bodyLarge.lineHeight.toDp() +
        SynthXTypography.bodyMedium.lineHeight.toDp()

/**
 * [com.v1per.synthx.ui.components.QueuePlaybackModeRow]'s real intrinsic height, as rendered
 * in PlayerSkeletonQueueContent — its own `Spacing.sm` (8dp) vertical padding on both sides of
 * its default-sized (48dp) `IconButton`s: 8 + 48 + 8 = 64dp. Not measured at runtime, same
 * reasoning as [QueueHeaderRowIntrinsicHeight] above.
 *
 * MUST be kept in sync with QueuePlaybackModeRow.kt's actual layout: if its padding or icon
 * size changes, update this to match, for the same reason as [QueueHeaderRowIntrinsicHeight].
 */
val QueuePlaybackModeRowIntrinsicHeight: Dp = 64.dp

/**
 * The queue-mode Column's single leading weight(1f) spacer's real height — [QueueHeaderRegionHeight]
 * minus the two children it contains, [QueueHeaderRowIntrinsicHeight] and
 * [QueuePlaybackModeRowIntrinsicHeight]. This is parallel math, not a measurement: it exists
 * so the art (positioned independently by `computeArtPosition` in QueueArtPositioning.kt) can
 * compute where the header Row actually renders inside the Column, without the Column itself
 * reporting its real layout back via onGloballyPositioned/onSizeChanged (which would
 * reintroduce a one-frame measurement-lag dependency — see the earlier discovery audit on
 * why that approach was rejected for this exact region).
 *
 * [Density]-scoped because [QueueHeaderRowIntrinsicHeight] is: the spacer gets whatever the
 * fixed region has left over, so it must shrink by exactly as much as the text row grows.
 * Scaling only the header height while leaving this frozen would be worse than scaling
 * neither — `computeArtPosition` adds the two together, so the halves that currently cancel
 * would instead push the art the same way twice.
 *
 * Floored at zero, matching what a `weight(1f)` spacer actually measures to: past a large
 * enough font scale the header Row and QueuePlaybackModeRow fill the bounded region outright,
 * the spacer collapses, and the header Row simply starts at the Column's top edge. Without the
 * floor this would go negative there and place the art above the region entirely.
 *
 * MUST be kept in sync with the Column's actual structure in PlayerSkeletonQueueContent.kt:
 * if the spacer arrangement changes (e.g. a spacer is reintroduced between the header Row and
 * QueuePlaybackModeRow), this formula needs to change to match — otherwise the art's computed
 * target Y silently stops matching where the header Row visually renders, exactly the bug this
 * value now fixes.
 */
val Density.QueueColumnLeadingSlack: Dp
    get() = (QueueHeaderRegionHeight - QueueHeaderRowIntrinsicHeight -
        QueuePlaybackModeRowIntrinsicHeight).coerceAtLeast(0.dp)
