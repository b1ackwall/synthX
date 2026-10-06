package com.v1per.synthx.ui.player

// DEAD CODE: tests the retired palette-driven player blob background. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.foundation.layout.Box
// import androidx.compose.foundation.layout.size
// import androidx.compose.runtime.SideEffect
// import androidx.compose.runtime.mutableStateOf
// import androidx.compose.ui.Modifier
// import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.graphics.PixelMap
// import androidx.compose.ui.graphics.toArgb
// import androidx.compose.ui.graphics.toPixelMap
// import androidx.compose.ui.platform.testTag
// import androidx.compose.ui.test.captureToImage
// import androidx.compose.ui.test.junit4.v2.createComposeRule
// import androidx.compose.ui.test.onNodeWithTag
// import androidx.compose.ui.unit.dp
// import androidx.test.ext.junit.runners.AndroidJUnit4
// import com.v1per.synthx.data.repository.Song
// import com.v1per.synthx.data.repository.SongPaletteSwatch
// import com.v1per.synthx.ui.components.DefaultInitialBlobs
// import com.v1per.synthx.ui.components.DerivedBlobColors
// import com.v1per.synthx.ui.components.ExtractedSwatchHsl
// import com.v1per.synthx.ui.components.rememberSharedSongBlobColors
// import com.v1per.synthx.ui.theme.SurfaceVariant
// import org.junit.Assert.*
// import org.junit.Rule
// import org.junit.Test
// import org.junit.runner.RunWith
//
// @RunWith(AndroidJUnit4::class)
// class StoredPaletteBackgroundTest {
//     @get:Rule val composeRule = createComposeRule()
//
//     @Test fun sameSongPaletteUpdatesColorsWithoutReplacingItsSharedArrangement() {
//         // No art URI: the renderer must be able to use stored colors without decoding.
//         val song = mutableStateOf(Song("song", "Title", "Artist", "Album", 1000L, "audio"))
//         var rendered: PlayerAmbientBackgroundState? = null
//         var sharedColors: DerivedBlobColors? = null
//         composeRule.setContent {
//             val colors = rememberSharedSongBlobColors(song.value)
//             val state = rememberPlayerAmbientBackgroundState(colors)
//             SideEffect {
//                 sharedColors = colors
//                 rendered = state
//             }
//             Box(Modifier.size(96.dp).testTag("background")) {
//                 PlayerAmbientBackground(state, fullHeight = 96.dp, progress = 0f)
//             }
//         }
//         composeRule.runOnIdle { assertNull(rendered) }
//         assertFlatFallback()
//
//         val raw = listOf(
//             SongPaletteSwatch(20f, 0.4f, 0.3f),
//             SongPaletteSwatch(140f, 0.5f, 0.4f),
//             SongPaletteSwatch(260f, 0.6f, 0.5f),
//         )
//         composeRule.runOnIdle { song.value = song.value.copy(paletteSwatches = raw) }
//         composeRule.runOnIdle {
//             assertEquals(raw.map { it.hue }, requireNotNull(rendered).artworkSwatches.map { it.hue })
//         }
//         val arrangement = requireNotNull(sharedColors).arrangement
//         val changed = listOf(
//             SongPaletteSwatch(40f, 0.45f, 0.35f),
//             SongPaletteSwatch(160f, 0.55f, 0.45f),
//             SongPaletteSwatch(280f, 0.65f, 0.55f),
//         )
//         composeRule.runOnIdle { song.value = song.value.copy(paletteSwatches = changed) }
//         composeRule.runOnIdle {
//             val updated = requireNotNull(sharedColors)
//             assertSame("Same-song palette changes must retain the arrangement object", arrangement, updated.arrangement)
//             val expectedHues = arrangement.swatchIndicesBySlot.map { changed[it].hue }
//             assertEquals(expectedHues, listOf(updated.baseSurfaceHue) + updated.blobs.map { it.hue })
//             assertEquals(changed.map { it.hue }, requireNotNull(rendered).artworkSwatches.map { it.hue })
//         }
//         val assigned = rendered
//         composeRule.runOnIdle { song.value = song.value.copy(title = "Retagged") }
//         composeRule.runOnIdle { assertSame("Unrelated updates must not reshuffle", assigned, rendered) }
//
//         composeRule.runOnIdle { song.value = song.value.copy(id = "no-palette", paletteSwatches = null) }
//         composeRule.runOnIdle { assertNull(rendered) }
//         assertFlatFallback()
//     }
//
//     @Test fun paletteToNullToPalettePreservesThePausedBlobFrame() {
//         composeRule.mainClock.autoAdvance = false
//         val paletteState = PlayerAmbientBackgroundState(
//             blobs = DefaultInitialBlobs.mapIndexed { index, blob ->
//                 blob.copy(
//                     hue = listOf(15f, 145f, 265f)[index],
//                     saturation = 0.9f,
//                     lightness = 0.45f,
//                     opacity = 0.9f,
//                     speedFraction = 0.2f,
//                     wobbleAmountFraction = 0.4f,
//                     wobbleSpeed = 1f,
//                 )
//             },
//             baseSurfaceColor = Color.hsl(210f, 0.25f, 0.1f),
//             artworkSwatches = listOf(
//                 ExtractedSwatchHsl(15f, 0.9f, 0.45f),
//                 ExtractedSwatchHsl(145f, 0.9f, 0.45f),
//                 ExtractedSwatchHsl(265f, 0.9f, 0.45f),
//             ),
//         )
//         val state = mutableStateOf<PlayerAmbientBackgroundState?>(paletteState)
//         val progress = mutableStateOf(1f)
//         composeRule.setContent {
//             Box(Modifier.size(320.dp).testTag("background")) {
//                 PlayerAmbientBackground(state.value, fullHeight = 320.dp, progress = progress.value)
//             }
//         }
//
//         // Let position and wobble phase advance, then freeze the exact rendered frame.
//         composeRule.mainClock.advanceTimeBy(1_000L)
//         composeRule.runOnUiThread { progress.value = 0f }
//         composeRule.mainClock.advanceTimeByFrame()
//         composeRule.waitForIdle()
//         val beforeNull = captureBackground()
//
//         composeRule.runOnUiThread { state.value = null }
//         composeRule.mainClock.advanceTimeByFrame()
//         composeRule.waitForIdle()
//         assertFlatFallback()
//
//         composeRule.runOnUiThread { state.value = paletteState }
//         composeRule.mainClock.advanceTimeByFrame()
//         composeRule.waitForIdle()
//         assertSamePixels(beforeNull, captureBackground())
//     }
//
//     private fun assertFlatFallback() {
//         val pixels = captureBackground()
//         // Full interior coverage catches a leftover hue scrim or a stale blob layer.
//         for (x in 1 until pixels.width - 1) {
//             for (y in 1 until pixels.height - 1) {
//                 assertEquals("Pixel ($x, $y)", SurfaceVariant.toArgb(), pixels[x, y].toArgb())
//             }
//         }
//     }
//
//     private fun captureBackground(): PixelMap =
//         composeRule.onNodeWithTag("background").captureToImage().toPixelMap()
//
//     private fun assertSamePixels(expected: PixelMap, actual: PixelMap) {
//         assertEquals(expected.width, actual.width)
//         assertEquals(expected.height, actual.height)
//         for (x in 0 until expected.width) {
//             for (y in 0 until expected.height) {
//                 assertEquals("Pixel ($x, $y)", expected[x, y].toArgb(), actual[x, y].toArgb())
//             }
//         }
//     }
// }
