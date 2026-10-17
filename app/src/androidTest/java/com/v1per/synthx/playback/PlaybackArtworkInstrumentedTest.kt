package com.v1per.synthx.playback

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Artwork in the session (what the notification and lock screen show): the first song has it from
 * the start, the following song gets it shortly after. Songs without album art get generated
 * "glitch" art, which must be a decodable image.
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
}
