package com.v1per.synthx.playback

import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.Song
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4-T1..T4 against the real service, a real [ExoPlayerEngine], and the real process-wide event
 * buses. Songs mirror into the real `.debug` database through [RealDbSongSeed] wherever the test
 * reaches the reconcile path, because that path reads the live table, not the stubbed persistence.
 *
 * These tests document what the engine *actually* does:
 *  - [aRemapArrivingAfterItsTargetsDeletionIsNotRejectedAndIsRepairedOnlyByReconciliation]: the
 *    events carry a revision, but no consumer rejects a stale/out-of-order one. That ordering
 *    corrupts the cache, and the next `reconcileWithDatabase()` (MainActivity.onStart) repairs it.
 *  - The two `queueLoadSuspended...` tests: the revision guard `reconcileIfRevisionChanged` is what
 *    protects a queue load whose suspension straddles an event. Those are the tests a
 *    bypass-the-guard mutation must fail.
 *
 * Known follow-ups, found while writing these and deliberately NOT fixed or tested here:
 *  - Deletion-barrier race: `SongRepositoryImpl.finishSongDeletion` calls
 *    `SongDeletionAcknowledgment.register(id)` *after* `notifyDeleted(id)`. If the engine's
 *    collector already ran `signalCleared(id)`, `register` creates a waiter nobody completes, so
 *    the file delete stalls for the full DELETION_HANDOFF_TIMEOUT_MS (1.5s) and the map entry
 *    leaks. Costs delay, not correctness.
 *  - Disconnect/retry interplay: the service stops playback when its last owning controller
 *    disconnects, so a reconnect after a mid-session disconnect finds a stopped player. Also a
 *    `playQueue` issued between a failed connection attempt and its retry landing is dropped
 *    (`awaitController()` returns null once readiness resolved false). Neither is exercised.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCacheInvalidationInstrumentedTest : PlaybackServiceTestBase() {

    private lateinit var seed: RealDbSongSeed

    // Superclass @Before has already opened the (test-ready) startup gate by the time this runs.
    @Before fun setUpSeed() {
        seed = RealDbSongSeed(context)
    }

    @After fun cleanUpSeed() {
        seed.cleanUp()
    }

    // Every test builds its own engine: the suspended-load tests need one with a gated connection.
    override val createEngineInSetUp = false

    // --- P4-T1: the recorded A->B / delete-B ordering ------------------------------------------

    /**
     * The recorded failing order: a scan commits A->B; B's deletion is handled while the engine
     * still caches A (nothing to do, so ignored); the delayed A->B remap then arrives and caches
     * the already-deleted B.
     *
     * Asserts the CURRENT behaviour, not the P4-T1 wording ("engine rejects an event older than
     * what it has applied"): nothing rejects the stale remap, so the cache is wrong until the
     * foregrounding reconcile repairs it against the database.
     */
    @Test fun aRemapArrivingAfterItsTargetsDeletionIsNotRejectedAndIsRepairedOnlyByReconciliation() {
        engine = newEngine()
        val a = song("order-A")
        val b = song("order-B")
        val c = song("order-C")
        // A and C exist. B is the song whose deletion committed: it is deliberately absent.
        seed.seed(a)
        seed.seed(c)
        playQueueAndAwait(listOf(a, c), 0)

        runBlocking { SongDeletionEvents.notifyDeleted(b.id) }
        settle() // the deletion is handled against a cache that has no B, and ignored
        assertEquals("the ignored deletion changed nothing", listOf(a.id, c.id), liveSongIds())

        runBlocking { SongRemapEvents.notifyRemap(a.id, b) }
        await("the remap was applied") { onMain { engine._currentSongId.value == b.id } }
        settle()

        // Current behaviour: the deleted song is now cached, current, and stays that way — no
        // revision/tombstone check on the event path rejects it, and nothing self-repairs.
        assertEquals("stale remap accepted; deleted B is queued", listOf(b.id, c.id), liveSongIds())
        assertEquals(b.id, onMain { engine._currentSongId.value })

        reconcileOnMain()

        await("reconciliation stripped the deleted B") { onMain { liveSongIds() == listOf(c.id) } }
        await("the cursor moved off the deleted song onto C") { onMain { engine._currentSongId.value == c.id } }
        assertEquals("only C's item remains loaded", 1, onMain { engine.controller!!.mediaItemCount })
    }

    // --- P4-T2: the revision guard on a suspended queue load ------------------------------------

    /**
     * A fresh play whose suspension straddles a remap. The engine's connection is gated, so
     * `playQueue` is *deterministically* parked at `awaitController()` (after it captured the
     * revision, before it installs). The remap and the database change land in that window: the
     * remap is discarded against the still-empty cache, then the queue installs its stale snapshot.
     * Only the post-install revision check notices and re-verifies against the database.
     */
    @Test fun queueLoadSuspendedAcrossARemapIsRepairedByTheRevisionCheck() {
        val a = song("gate-remap-A")
        seed.seed(a)
        val moved = a.copy(filePath = a.filePath + "&moved=1")

        withQueueLoadSuspendedAtConnection(listOf(a)) {
            seed.seed(moved) // the scan's committed change: same id, new path
            runBlocking { SongRemapEvents.notifyRemap(a.id, moved) }
        }

        await("cache repaired to the moved path", timeoutMs = 5_000) {
            onMain { engine.songsBySlotId.values.singleOrNull()?.filePath == moved.filePath }
        }
        assertEquals(
            "the loaded MediaItem points at the moved file too",
            moved.filePath,
            onMain { engine.controller!!.getMediaItemAt(0).localConfiguration!!.uri.toString() },
        )
    }

    /** Same window, but a deletion: the queue must not keep (and play) a song that no longer exists. */
    @Test fun queueLoadSuspendedAcrossADeletionIsRepairedByTheRevisionCheck() {
        val a = song("gate-delete-A")
        val b = song("gate-delete-B")
        seed.seed(a)
        seed.seed(b)

        withQueueLoadSuspendedAtConnection(listOf(a, b)) {
            seed.delete(b.id) // the deleting transaction committed
            runBlocking { SongDeletionEvents.notifyDeleted(b.id) }
        }

        await("deleted B stripped from the installed queue", timeoutMs = 5_000) {
            onMain { liveSongIds() == listOf(a.id) }
        }
        assertEquals(1, onMain { engine.controller!!.mediaItemCount })
    }

    // --- P4-T4: [A, B, B] -----------------------------------------------------------------------

    /**
     * The audit's acceptance case: queue `[A, B, B]`, playing the last B, repeat off, B deleted.
     * With no surviving successor the engine must end stopped with a null cursor — not republish
     * the older history slot Media3 resolves as current once the B items are removed.
     * In-memory engine state only: the stub repository rejects the durable cursor write.
     */
    @Test fun deletingTheFinalCurrentSongWithDuplicateOccurrencesEndsStoppedWithANullCursor() {
        engine = newEngine()
        val a = song("dup-A")
        val b = song("dup-B")
        playQueueAndAwait(listOf(a, b, b), startIndex = 2)
        assertEquals("precondition: repeat off", RepeatMode.Off, onMain { engine._repeatMode.value })
        assertEquals("precondition: on the last B", 2, onMain { engine._currentIndex.value })

        runBlocking { SongDeletionEvents.notifyDeleted(b.id) }
        await("both B occurrences left the engine's cache") {
            onMain { engine.songsBySlotId.values.none { it.id == b.id } }
        }
        // "After all callbacks settle": the onMediaItemTransition / state-change callbacks the
        // removals provoke arrive asynchronously, and are exactly what could republish history.
        settle()

        assertNull("current slot", onMain { engine._currentSlotId.value })
        assertNull("current song id", onMain { engine._currentSongId.value })
        assertNull("current song", onMain { engine._currentSong.value })
        assertEquals("cursor index", -1, onMain { engine._currentIndex.value })
        assertEquals("nothing left loaded", 0, onMain { engine.controller!!.mediaItemCount })
        assertEquals("A is still queued, just not playing", listOf(a.id), onMain { liveSongIds() })
    }

    // --- P4-T3: adopting an already-playing session ---------------------------------------------

    /**
     * A second engine attaching to a service that is already playing (what the Activity does on
     * relaunch). Adding a listener to a connected controller does not replay its current state, and
     * steady playback fires no further `onIsPlayingChanged`, so unless the connect path seeds
     * `_isPlaying` from the controller it reads false for the rest of the session.
     */
    @Test fun adoptingAnAlreadyPlayingSessionSeedsIsPlayingFromTheConnectedController() {
        engine = newEngine()
        startPlaying()
        assertTrue("precondition: the session is genuinely playing", onMain { extraController.isPlaying })

        val adopter = newEngine()
        await("the adopting engine connected") { onMain { adopter.controller != null } }

        val atConnection = onMain { adopter._isPlaying.value }
        settle()
        val afterSettling = onMain { adopter._isPlaying.value }
        assertTrue(
            "seeded from the connected controller and still true once settled " +
                "(at connection=$atConnection, after settling=$afterSettling)",
            atConnection && afterSettling,
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun liveSongIds(): List<String> = engine.orderStore.live.map { it.songId }

    /** `reconcileWithDatabase` as production calls it: from the main thread (viewModelScope). */
    private fun reconcileOnMain() = runBlocking(Dispatchers.Main.immediate) { engine.reconcileWithDatabase() }

    /**
     * Builds [engine] with its session connection held open, starts `playQueue([queue], 0)` and
     * runs [duringSuspension] while that call is parked at `awaitController()`, then lets the
     * connection through and waits for the load to finish.
     *
     * Deterministic in the direction that matters: the queue load cannot install before the gate
     * opens, so the events always land after it captured its revision and before it installs. The
     * sleep only lets it *reach* the suspension; if it were ever too short the events would land
     * before the revision capture, the guard would be legitimately inert, and the test would fail
     * (never silently pass).
     */
    private fun withQueueLoadSuspendedAtConnection(
        queue: List<Song>,
        duringSuspension: () -> Unit,
    ) {
        val gate = SettableFuture.create<MediaController>()
        var captured: MediaController.Listener? = null
        engine = newEngine { listener ->
            captured = listener
            gate
        }

        val loader = thread(name = "gated-queue-load") { runBlocking { engine.playQueue(queue, 0) } }
        Thread.sleep(1_000) // artwork build + hop to main + park in awaitController()
        assertTrue("the load is still suspended on the gated connection", loader.isAlive)
        assertTrue("nothing installed while gated", onMain { engine.songsBySlotId.isEmpty() })

        duringSuspension()
        settle() // the collectors have consumed the events against the empty cache

        gate.setFuture(onMain { realConnection(checkNotNull(captured)) })
        loader.join(15_000)
        assertTrue("queue load finished", !loader.isAlive)
    }
}
