package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTypography

/**
 * Queue-mode header art size (44dp) — same size as the mini-player thumbnail.
 */
val QueueModeArtSize: Dp = 44.dp

/**
 * Queue-mode-only positioning height used to establish the header Column's top anchor. This
 * deliberately remains independent of the regular player's title-row reservation: the two
 * layouts have separate spacing requirements and must be free to change without moving each
 * other. Restoring 68dp preserves the queue thumbnail's intended 72dp resting position.
 */
val QueueHeaderRowHeight: Dp = 68.dp

/**
 * Height of the queue-mode header-to-playback-row region — a real Column (header Row +
 * a single leading weight(1f) spacer + [com.v1per.synthx.ui.components.QueuePlaybackModeRow],
 * built in PlayerSkeletonQueueContent) is given exactly this much bounded height so that
 * spacer has real slack to distribute. Composed from existing [Spacing] tokens rather than a
 * bespoke literal: [Spacing.xxl] for the header row's own footprint, [Spacing.RowHeight] for
 * the playback row's, and [Spacing.xl] as the slack absorbed entirely by the one leading
 * spacer. It is independent of both [QueueHeaderRowHeight] and the regular player's title-row
 * reservation, so those concerns can be tuned without changing this region's height.
 */
val QueueHeaderRegionHeight: Dp = Spacing.xxl + Spacing.RowHeight + Spacing.xl

/**
 * Queue-mode header Row's real intrinsic height, as rendered in PlayerSkeletonQueueContent —
 * driven by its tallest child, the title/artist text column: one `bodyLarge` line above one
 * `bodyMedium` line, so the column is exactly those two `lineHeight`s summed.
 *
 * Read off [SynthXTypography]'s own `lineHeight` values through the supplied [Density], rather
 * than a fixed `44.dp` literal. Both line heights are declared in `sp`, so
 * the rendered column genuinely grows with the user's system font-scale setting, while a dp
 * literal would freeze it at whatever fontScale 1.0 happens to produce — leaving the art's queue-mode
 * position (via [QueueColumnLeadingSlack]) drifting off the text it is meant to centre against.
 * `TextUnit.toDp()` does the conversion rather than a manual `value * fontScale`, since it also
 * honours the non-linear font-scale curves Android 14+ applies to larger text. At the default
 * font scale this resolves to exactly 44dp (24sp + 20sp).
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
 * while this keeps going — a bounded few-dp residual, still smaller than a fixed literal's
 * error would be at those scales.
 */
val Density.QueueHeaderRowIntrinsicHeight: Dp
    get() = SynthXTypography.bodyLarge.lineHeight.toDp() +
        SynthXTypography.bodyMedium.lineHeight.toDp()

/**
 * [com.v1per.synthx.ui.components.QueuePlaybackModeRow]'s real intrinsic height, as rendered
 * in PlayerSkeletonQueueContent — its own `Spacing.sm` (8dp) vertical padding on both sides of
 * its default-sized (40dp) `DragResistantIconButton`s: 8 + 40 + 8 = 56dp. Not measured at
 * runtime, same
 * reasoning as [QueueHeaderRowIntrinsicHeight] above.
 *
 * MUST be kept in sync with QueuePlaybackModeRow.kt's actual layout: if its padding or icon
 * size changes, update this to match, for the same reason as [QueueHeaderRowIntrinsicHeight].
 */
val QueuePlaybackModeRowIntrinsicHeight: Dp = 56.dp

/**
 * The queue-mode Column's single leading weight(1f) spacer's real height — [QueueHeaderRegionHeight]
 * minus the two children it contains, [QueueHeaderRowIntrinsicHeight] and
 * [QueuePlaybackModeRowIntrinsicHeight]. This is parallel math, not a measurement: it exists
 * so the art (positioned independently by `computeArtPosition` in QueueArtPositioning.kt) can
 * compute where the header Row actually renders inside the Column, without the Column itself
 * reporting its real layout back via onGloballyPositioned/onSizeChanged (which would
 * introduce a one-frame measurement-lag dependency).
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
 * target Y silently stops matching where the header Row visually renders.
 */
val Density.QueueColumnLeadingSlack: Dp
    get() = (
        QueueHeaderRegionHeight - QueueHeaderRowIntrinsicHeight -
            QueuePlaybackModeRowIntrinsicHeight
        ).coerceAtLeast(0.dp)
