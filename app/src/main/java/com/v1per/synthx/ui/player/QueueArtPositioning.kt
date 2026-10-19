package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.ui.theme.Spacing

/**
 * Everything PlayerSheetSkeleton positions off a single [computeArtPosition] call: the album
 * art's offset and size, the title row's Y (Player mode), and the two queue-mode anchors
 * ([artOffsetYQueue], [queueListOffsetY]) PlayerSkeletonQueueContent needs to place its header
 * and list under the same art.
 */
data class ArtPosition(
    val artOffsetX: Dp,
    val artOffsetY: Dp,
    val finalArtSize: Dp,
    val titleRowY: Dp,
    val artOffsetYQueue: Dp,
    val queueListOffsetY: Dp,
)

/**
 * Pure derivation of the album art's position/size and the title row's Y, lerped across two
 * independent axes — [progress] (collapsed↔expanded) and [queueProgressValue]
 * (player↔queue) — so the morph is continuous while matching each source layout exactly at
 * its own endpoint:
 *  - Mini (progress 0): leading element of the mini bar's Spacing.md-padded Row,
 *    vertically centered against the row's own height.
 *  - Expanded (progress 1, queueProgressValue 0): horizontally centered; leftover space
 *    splits above/below the art by [artWeightAbove]:[artWeightBelow]. The below-art share
 *    splits around the reserved title row without changing the art's position.
 *  - Queue (progress 1, queueProgressValue 1): the queue-mode header's art slot, centered on
 *    where the header Row's title/artist text actually renders (see [QueueColumnLeadingSlack]'s
 *    doc comment in PlayerSkeletonQueueMetrics.kt for why that isn't simply the header
 *    Column's top edge).
 *
 * No composable-scope APIs are involved in the actual math — every input is a plain value the
 * caller already has in hand (read out of its own `BoxWithConstraints`, `LocalDensity`, and
 * other local state), so this stays a plain function rather than a composable. [density] is one
 * of those plain values and not a measurement hook: its only job here is converting the header
 * row's `sp` text metrics to `dp` at the current font scale (see [QueueHeaderRowIntrinsicHeight]).
 */
fun computeArtPosition(
    progress: Float,
    queueProgressValue: Float,
    maxWidth: Dp,
    maxHeight: Dp,
    sheetHeight: Dp,
    titleRowReservedHeight: Dp,
    expandedTopMargin: Dp,
    artWeightAbove: Float,
    artWeightBelow: Float,
    titleGapWeightAboveTitle: Float,
    titleGapWeightBelowTitle: Float,
    controlsOverlayHeight: Dp,
    density: Density,
): ArtPosition {
    // 44dp mini thumbnail growing to the expanded player's full-width art, capped against
    // available height minus the title row's reserved space.
    val artSize = lerpDp(44.dp, maxWidth - Spacing.HorizontalPadding * 4, progress)
    val artMaxHeight = (maxHeight - titleRowReservedHeight - controlsOverlayHeight).coerceAtLeast(0.dp)
    val constrainedArtSize = min(artSize, artMaxHeight)

    // Art position, lerped between its two known-correct endpoints so the morph is continuous
    // while matching each source layout exactly at progress 0 / 1:
    //  - Mini (progress 0): leading element of the mini bar's Spacing.md-padded Row,
    //    vertically centered against the row's own height.
    //  - Expanded (progress 1): horizontally centered; the art weights split leftover space
    //    after accounting for expandedTopMargin, the title row, and the controls reservation.
    val artOffsetXMini = Spacing.md
    val artOffsetXExpanded = (maxWidth - constrainedArtSize) / 2
    // Queue mode's left margin is 32dp (Spacing.xl), a deliberate deviation from
    // Spacing.HorizontalPadding (16dp) used elsewhere — matches
    // PlayerSkeletonQueueContent's header Column's `padding(start = Spacing.xl, ...)`, which
    // this offset must stay in sync with (see that padding's own doc comment).
    val artOffsetXQueue = Spacing.xl
    val expandedTargetX = lerpDp(artOffsetXExpanded, artOffsetXQueue, queueProgressValue)
    val artOffsetX = lerpDp(artOffsetXMini, expandedTargetX, progress)

    val artOffsetYMini = (sheetHeight - constrainedArtSize) / 2
    val expandedRegionHeight =
        (sheetHeight - expandedTopMargin - titleRowReservedHeight - controlsOverlayHeight)
            .coerceAtLeast(0.dp)
    // Split 1 fixes the art position and the total flexible space available below it.
    val totalFlexSpace = (expandedRegionHeight - constrainedArtSize).coerceAtLeast(0.dp)
    val artOffsetYExpanded = expandedTopMargin +
        totalFlexSpace * (artWeightAbove / (artWeightAbove + artWeightBelow))
    val belowArtSpace = totalFlexSpace * (artWeightBelow / (artWeightAbove + artWeightBelow))
    // The queue header Column's top anchor. Reuses expandedTopMargin (32dp) rather than a new
    // constant, so it stays in sync with the expanded state's own top margin, plus the
    // half-difference between QueueHeaderRowHeight and QueueModeArtSize. That second term is a
    // fixed 4dp nudge; it does not position the art at all (artOffsetYQueueTarget below does
    // that), it just
    // shifts the whole header Column — and queueListOffsetY with it — down by that much.
    val artOffsetYQueue = expandedTopMargin + (QueueHeaderRowHeight - QueueModeArtSize) / 2
    // Where QueueList starts: right after the fixed-height header-region Column (header Row +
    // playback row, see QueueHeaderRegionHeight's doc comment) ends. Uses the Column's own
    // start offset (artOffsetYQueue) unchanged — not artOffsetYQueueTarget below, which serves
    // a different purpose (see its own doc comment).
    val queueListOffsetY = artOffsetYQueue + QueueHeaderRegionHeight
    // The art is a separate element from the header Row (see PlayerSkeletonQueueContent's doc
    // comment), so it can't just anchor at artOffsetYQueue: that's the Column's start
    // offset, but the header Row itself renders QueueColumnLeadingSlack lower than that,
    // pushed down by the Column's single leading weight(1f) spacer. artCenterYQueue
    // reconstructs the header Row's actual vertical center from the same slack math the
    // Column uses, so the art centers on where the title/artist text actually renders rather
    // than on the Column's top edge.
    val artCenterYQueue = artOffsetYQueue +
        with(density) { QueueColumnLeadingSlack + QueueHeaderRowIntrinsicHeight / 2 }
    val artOffsetYQueueTarget = artCenterYQueue - QueueModeArtSize / 2
    val expandedTargetY = lerpDp(artOffsetYExpanded, artOffsetYQueueTarget, queueProgressValue)
    val artOffsetY = lerpDp(artOffsetYMini, expandedTargetY, progress)

    val expandedTargetSize = lerpDp(constrainedArtSize, QueueModeArtSize, queueProgressValue)
    val finalArtSize = lerpDp(44.dp, expandedTargetSize, progress)

    // Split 2 places the title within the fixed below-art share. The remainder,
    // belowArtSpace - artToTitleGap, stays empty between the title and controls reservation.
    // Neither title weight feeds back into the art position or Queue anchors.
    val artToTitleGap = belowArtSpace *
        (titleGapWeightAboveTitle / (titleGapWeightAboveTitle + titleGapWeightBelowTitle))
    // The mini endpoint is invisible until expandedContentAlpha fades the title in, but
    // remains a continuous starting point directly under the mini art.
    val titleRowYMini = artOffsetYMini + constrainedArtSize
    val titleRowYExpanded = artOffsetYExpanded + constrainedArtSize + artToTitleGap
    val titleRowY = lerpDp(titleRowYMini, titleRowYExpanded, progress)

    return ArtPosition(
        artOffsetX = artOffsetX,
        artOffsetY = artOffsetY,
        finalArtSize = finalArtSize,
        titleRowY = titleRowY,
        artOffsetYQueue = artOffsetYQueue,
        queueListOffsetY = queueListOffsetY,
    )
}
