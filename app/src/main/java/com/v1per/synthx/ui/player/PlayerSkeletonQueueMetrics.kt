package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Spacing

/**
 * Queue-mode header art size, mirrored from PlayerQueueModeContent.kt's private
 * QueueModeArtSize (44dp) — same cross-package-boundary duplication as the mirrored constants
 * in PlayerSheetSkeleton.kt (TitleRowReservedHeight, ExpandedTopMargin, ControlsOverlayHeight).
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
 * driven by its tallest child, the title/artist text column (bodyLarge + bodyMedium line
 * heights, 24sp + 20sp = 44sp ≈ 44dp), taller than the 40dp MoreOptionsButton beside it. Not
 * measured at runtime (see [QueueColumnLeadingSlack]'s doc comment for why) — this is the
 * same ~44dp figure confirmed by the earlier text-metrics audit, duplicated here as a named
 * constant instead of a bare literal so [QueueColumnLeadingSlack] below can be read without
 * re-deriving it.
 *
 * MUST be kept in sync with PlayerSkeletonQueueContent's actual header Row: if its content
 * changes (e.g. a third text line, a taller trailing element), update this to match, or
 * [QueueColumnLeadingSlack] — and therefore the art's queue-mode position — silently drifts
 * out of alignment with where the header Row actually renders.
 */
val QueueHeaderRowIntrinsicHeight: Dp = 44.dp

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
 * minus the two fixed-height children it contains, [QueueHeaderRowIntrinsicHeight] and
 * [QueuePlaybackModeRowIntrinsicHeight]. This is parallel math, not a measurement: it exists
 * so the art (positioned independently by `computeArtPosition` in QueueArtPositioning.kt) can
 * compute where the header Row actually renders inside the Column, without the Column itself
 * reporting its real layout back via onGloballyPositioned/onSizeChanged (which would
 * reintroduce a one-frame measurement-lag dependency — see the earlier discovery audit on
 * why that approach was rejected for this exact region).
 *
 * MUST be kept in sync with the Column's actual structure in PlayerSkeletonQueueContent.kt:
 * if the spacer arrangement changes (e.g. a spacer is reintroduced between the header Row and
 * QueuePlaybackModeRow), this formula needs to change to match — otherwise the art's computed
 * target Y silently stops matching where the header Row visually renders, exactly the bug this
 * value now fixes.
 */
val QueueColumnLeadingSlack: Dp =
    QueueHeaderRegionHeight - QueueHeaderRowIntrinsicHeight - QueuePlaybackModeRowIntrinsicHeight
