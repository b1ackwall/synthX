package com.v1per.synthx.playback

import androidx.media3.common.MediaMetadata
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [buildMediaItem]: what every item handed to the player carries. The slot id is the item's
 * identity in the session; title, artist and artwork are what the notification and lock screen
 * show. (The file URI is not checked: `Uri.parse` returns null on the JVM.)
 */
class MediaItemBuildTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val song = Song("song-1", "A Title", "An Artist", "An Album", 1_000L, "file:///music/a.wav")

    @Test fun theItemCarriesTheSlotIdTitleArtistAndArtwork() {
        val artwork = byteArrayOf(1, 2, 3)

        val item = buildMediaItem("slot-7", song, artwork)

        assertEquals("slot-7", item.mediaId)
        assertEquals("A Title", item.mediaMetadata.title.toString())
        assertEquals("An Artist", item.mediaMetadata.artist.toString())
        assertContentEquals(artwork, item.mediaMetadata.artworkData)
        assertEquals(MediaMetadata.PICTURE_TYPE_FRONT_COVER, item.mediaMetadata.artworkDataType)
    }

    @Test fun withoutArtworkTheItemHasNone() {
        val item = buildMediaItem("slot-8", song, artworkData = null)

        assertEquals("slot-8", item.mediaId)
        assertNull(item.mediaMetadata.artworkData)
    }
}
