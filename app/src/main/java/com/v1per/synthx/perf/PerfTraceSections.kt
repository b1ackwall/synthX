package com.v1per.synthx.perf

/**
 * Trace section names for the player, recorded with androidx.tracing and read by the benchmark
 * module's TraceSectionMetric. The benchmark module keeps its own copy of these strings; change
 * both together.
 *
 * Composable bodies bracket themselves with Trace.beginSection/endSection on their first and
 * last lines, so the composition structure is untouched; such a body must not gain an early
 * return. Draw and decode blocks use trace { }.
 */
internal object PerfTraceSections {
    /** PlayerSheetSkeleton's BoxWithConstraints content: the whole sheet's composition. */
    const val PlayerSheet = "synthX.PlayerSheet"
    const val OnProgressChanged = "synthX.PlayerSheet.onProgressChanged"

    const val Background = "synthX.PlayerBackground"
    const val BackgroundDraw = "synthX.PlayerBackground.draw"
    const val AlbumArtWithGlow = "synthX.PlayerAlbumArtWithGlow"
    const val MiniContent = "synthX.PlayerMiniContent"
    const val TitleRow = "synthX.PlayerTitleRow"
    const val ControlsOverlay = "synthX.PlayerControlsOverlay"

    const val QueueContent = "synthX.PlayerQueueContent"

    /** One section per queue row composition, not per frame. */
    const val QueueRow = "synthX.QueueRow"
    const val QueueMaskDraw = "synthX.QueueMaskLayer.draw"

    /** One section per decode source tried on a song change, on an IO thread. */
    const val ArtDecode = "synthX.PlayerArtDecode"
}
