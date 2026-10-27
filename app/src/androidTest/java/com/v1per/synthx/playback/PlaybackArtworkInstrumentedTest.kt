package com.v1per.synthx.playback

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.theme.SurfaceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Artwork in the session (what the notification and lock screen show): the first song has it from
 * the start, the following song gets it shortly after. Songs without album art get a flat
 * SurfaceVariant square, the same look as the in-app placeholder.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackArtworkInstrumentedTest : PlaybackServiceTestBase() {

    private fun artworkAt(index: Int): ByteArray? = onMain {
        extraController.takeIf { it.mediaItemCount > index }?.getMediaItemAt(index)?.mediaMetadata?.artworkData
    }

    @Test fun theFirstSongHasArtworkAtOnceAndTheNextOneIsPrefetched() {
        playQueueAndAwait(songs(3, "art"), 0)

        val first = artworkAt(0)
        assertNotNull("first song's artwork", first)
        assertNotNull("generated art decodes", BitmapFactory.decodeByteArray(first, 0, first!!.size))
        await("next song's artwork") { artworkAt(1) != null }
    }

    @Test fun artlessSongsGetAFlatSurfaceVariantSquare() {
        playQueueAndAwait(songs(1, "flat"), 0)

        val bytes = artworkAt(0)
        assertNotNull("artwork bytes", bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
        val edge = bitmap.width - 1
        val samples = listOf(0 to 0, edge to 0, 0 to edge, edge to edge, edge / 2 to edge / 2, edge / 4 to edge * 3 / 4)
        samples.forEach { (x, y) ->
            assertEquals("pixel ($x, $y)", SurfaceVariant.toArgb(), bitmap[x, y])
        }
    }
}
