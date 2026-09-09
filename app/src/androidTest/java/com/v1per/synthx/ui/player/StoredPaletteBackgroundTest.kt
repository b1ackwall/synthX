package com.v1per.synthx.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongPaletteSwatch
import com.v1per.synthx.ui.theme.SurfaceVariant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredPaletteBackgroundTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun nullPaletteIsFlatAndSameSongBackfillUpdatesRememberedColors() {
        // No art URI: the renderer must be able to use stored colors without decoding.
        val song = mutableStateOf(Song("song", "Title", "Artist", "Album", 1000L, "audio"))
        var rendered: PlayerAmbientBackgroundState? = null
        composeRule.setContent {
            val state = rememberPlayerAmbientBackgroundState(song.value)
            SideEffect { rendered = state }
            Box(Modifier.size(96.dp).testTag("background")) {
                PlayerAmbientBackground(state, fullHeight = 96.dp, progress = 0f)
            }
        }
        composeRule.runOnIdle { assertNull(rendered) }
        assertFlatFallback()

        val raw = listOf(
            SongPaletteSwatch(20f, 0.4f, 0.3f),
            SongPaletteSwatch(140f, 0.5f, 0.4f),
            SongPaletteSwatch(260f, 0.6f, 0.5f),
        )
        composeRule.runOnIdle { song.value = song.value.copy(paletteSwatches = raw) }
        composeRule.runOnIdle {
            assertEquals(raw.map { it.hue }, requireNotNull(rendered).artworkSwatches.map { it.hue })
        }
        val assigned = rendered
        composeRule.runOnIdle { song.value = song.value.copy(title = "Retagged") }
        composeRule.runOnIdle { assertSame("Unrelated updates must not reshuffle", assigned, rendered) }

        composeRule.runOnIdle { song.value = song.value.copy(id = "no-palette", paletteSwatches = null) }
        composeRule.runOnIdle { assertNull(rendered) }
        assertFlatFallback()
    }

    private fun assertFlatFallback() {
        val pixels = composeRule.onNodeWithTag("background").captureToImage().toPixelMap()
        // Full interior coverage catches a leftover hue scrim or a stale blob layer.
        for (x in 1 until pixels.width - 1) {
            for (y in 1 until pixels.height - 1) {
                assertEquals("Pixel ($x, $y)", SurfaceVariant.toArgb(), pixels[x, y].toArgb())
            }
        }
    }
}
