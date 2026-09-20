package com.v1per.synthx.ui.screens.playlistdetail

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.theme.SurfaceVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * P8-T6: the detail screen's 160dp cover is generated art for a playlist that has it and the flat
 * [com.v1per.synthx.ui.components.ArtPlaceholder] for one that does not, at the same size either way.
 * Songs are left empty (which also puts the cover above the empty message) because [SongRow] needs
 * playback plumbing this test has no reason to build.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDetailCoverInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(playlist: Playlist?) {
        composeRule.setContent {
            PlaylistDetailContent(
                uiState = UiState.Success(PlaylistDetailData(playlist = playlist, songs = emptyList())),
                currentlyPlayingSongId = null,
                playlistId = "p1",
                onSongTap = { _, _ -> },
            )
        }
    }

    private val withArt = Playlist(
        id = "p1", name = "One", groupId = null, albumArtUri = null, artHue = 200, artStructureSeed = 1234L,
    )

    @Test
    fun aPlaylistWithArtRendersGeneratedCoverAt160dp() {
        show(withArt)

        val cover = composeRule.onNodeWithTag(PLAYLIST_DETAIL_COVER_TAG)
        cover.assertIsDisplayed()
        val pixels = cover.captureToImage().toPixelMap()
        // The tagged box is full width; the art is the centred 160dp square within it. Sample only
        // its interior, inset well clear of the 2dp rounded-corner clip: antialiased edge pixels
        // differ from the fill even on a flat placeholder. Generated art tints every interior
        // pixel (the hue gradient behind the strokes) while a placeholder is exactly SurfaceVariant
        // throughout, so "most pixels differ" separates them with a wide margin.
        val density = composeRule.density.density
        val side = (160 * density).toInt()
        val inset = (16 * density).toInt()
        val left = (pixels.width - side) / 2
        var differing = 0
        var sampled = 0
        for (x in left + inset until left + side - inset) for (y in inset until side - inset) {
            sampled++
            if (pixels[x, y].toArgb() != SurfaceVariant.toArgb()) differing++
        }
        assertTrue(
            "expected generated art in the 160dp square, but only $differing of $sampled interior pixels differ from the placeholder fill",
            differing > sampled / 2,
        )
    }

    @Test
    fun aPlaylistWithoutArtRendersTheFlatPlaceholderAt160dp() {
        show(withArt.copy(artHue = null, artStructureSeed = null))

        val pixels = composeRule.onNodeWithTag(PLAYLIST_DETAIL_COVER_TAG).captureToImage().toPixelMap()
        assertEquals(SurfaceVariant.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    @Test
    fun aPlaylistThatIsGoneRendersThePlaceholderToo() {
        show(null)

        val pixels = composeRule.onNodeWithTag(PLAYLIST_DETAIL_COVER_TAG).captureToImage().toPixelMap()
        assertEquals(SurfaceVariant.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    @Test
    fun theCoverIs160dpTallAndTheEmptyMessageSitsBelowIt() {
        show(withArt)

        // The tag is inside the box's vertical padding, so this is the art's own height.
        val cover = composeRule.onNodeWithTag(PLAYLIST_DETAIL_COVER_TAG)
        cover.assertHeightIsEqualTo(160.dp)
        val message = composeRule.onNodeWithText(context.getString(R.string.empty_playlist_no_songs))
        message.assertIsDisplayed()
        assertTrue(
            "the empty message must be below the cover",
            message.getUnclippedBoundsInRoot().top >= cover.getUnclippedBoundsInRoot().bottom,
        )
    }
}
