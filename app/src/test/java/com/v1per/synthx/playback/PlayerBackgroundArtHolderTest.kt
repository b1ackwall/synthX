package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerBackgroundArtHolderTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun request(id: String, uri: String? = "content://$id") = PlayerBackgroundArtRequest(id, uri, null)

    @Test fun previousArtStaysUntilTheNextDecodeFinishesThenSwaps() = runTest {
        val pending = mutableMapOf<String, CompletableDeferred<String?>>()
        val holder =
            PlayerBackgroundArtHolder<String>(backgroundScope) {
                pending.getOrPut(it.songId) { CompletableDeferred() }.await()
            }

        holder.request(request("a"))
        runCurrent()
        pending.getValue("a").complete("image-a")
        runCurrent()
        assertEquals(PlayerBackgroundArt("a", "image-a"), holder.art.value)

        holder.request(request("b"))
        runCurrent()
        assertEquals(PlayerBackgroundArt("a", "image-a"), holder.art.value)
        pending.getValue("b").complete("image-b")
        runCurrent()
        assertEquals(PlayerBackgroundArt("b", "image-b"), holder.art.value)
    }

    @Test fun songChangeCancelsTheInFlightDecode() = runTest {
        val pending = mutableMapOf<String, CompletableDeferred<String?>>()
        val holder =
            PlayerBackgroundArtHolder<String>(backgroundScope) {
                pending.getOrPut(it.songId) { CompletableDeferred() }.await()
            }

        holder.request(request("a"))
        runCurrent()
        holder.request(request("b"))
        runCurrent()
        // A late result for the abandoned song must never land.
        pending.getValue("a").complete("image-a")
        runCurrent()
        assertNull(holder.art.value)
        pending.getValue("b").complete("image-b")
        runCurrent()
        assertEquals(PlayerBackgroundArt("b", "image-b"), holder.art.value)
    }

    @Test fun songWithoutArtClearsAtOnceAndUndecodableArtClearsWhenDone() = runTest {
        val holder = PlayerBackgroundArtHolder<String>(backgroundScope) {
            if (it.songId ==
                "broken"
            ) {
                null
            } else {
                "image-${it.songId}"
            }
        }

        holder.request(request("a"))
        runCurrent()
        assertEquals(PlayerBackgroundArt("a", "image-a"), holder.art.value)
        holder.request(request("none", uri = null))
        assertNull(holder.art.value)

        holder.request(request("a"))
        runCurrent()
        holder.request(request("broken"))
        runCurrent()
        assertNull(holder.art.value)
    }

    @Test fun repeatingTheCurrentRequestDoesNotDecodeAgain() = runTest {
        var loads = 0
        val holder = PlayerBackgroundArtHolder<String>(backgroundScope) {
            loads++
            "image"
        }
        holder.request(request("a"))
        runCurrent()
        holder.request(request("a"))
        runCurrent()
        assertEquals(1, loads)
        // Changed art URIs for the same song are a new request.
        holder.request(request("a", uri = "content://repaired"))
        runCurrent()
        assertEquals(2, loads)
    }
}
