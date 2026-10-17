package com.v1per.synthx.data

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coordinator-level races, same CompletableDeferred shape as FolderScannerAdmissionTest: one
 * side is held open at an exact point while the other side is driven against it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationCoordinatorTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @BeforeTest fun setUp() = FileOperationCoordinator.resetForTest()
    @AfterTest fun tearDown() = FileOperationCoordinator.resetForTest()

    @Test fun songLockEntryIsRemovedOnceNobodyHoldsOrAwaitsIt() = runTest {
        FileOperationCoordinator.withSongLock("a") {
            assertEquals(1, FileOperationCoordinator.songLockRefCountForTest("a"))
        }
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())

        val lease = assertNotNull(FileOperationCoordinator.tryAcquireSong("b"))
        assertEquals(1, FileOperationCoordinator.songLockEntryCountForTest())
        lease.release()
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
    }

    @Test fun refusedTryAcquireLeavesNoEntryBehind() = runTest {
        val held = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val holder = launch {
            FileOperationCoordinator.withSongLock("a") {
                held.complete(Unit)
                finish.await()
            }
        }
        held.await()
        assertNull(FileOperationCoordinator.tryAcquireSong("a"))
        assertEquals(1, FileOperationCoordinator.songLockRefCountForTest("a"))
        finish.complete(Unit)
        holder.join()
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
    }

    /** delete/replace semantics: a second mutator of the same song waits, it doesn't race. */
    @Test fun secondMutatorOfSameSongWaitsForTheFirstToRelease() = runTest {
        val graduationHolding = CompletableDeferred<Unit>()
        val finishGraduation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val graduation = launch {
            FileOperationCoordinator.withSongLock("song") {
                events += "graduation start"
                graduationHolding.complete(Unit)
                finishGraduation.await()
                events += "graduation end"
            }
        }
        graduationHolding.await()
        val deletion = async {
            FileOperationCoordinator.withSongLock("song") { events += "delete" }
        }
        runCurrent()
        assertFalse(deletion.isCompleted)
        assertEquals(2, FileOperationCoordinator.songLockRefCountForTest("song"))

        finishGraduation.complete(Unit)
        graduation.join()
        deletion.await()
        assertEquals(listOf("graduation start", "graduation end", "delete"), events)
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
    }

    @Test fun differentSongsDoNotBlockEachOther() = runTest {
        val finish = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val holder = launch {
            FileOperationCoordinator.withSongLock("a") { held.complete(Unit); finish.await() }
        }
        held.await()
        var ranB = false
        FileOperationCoordinator.withSongLock("b") { ranB = true }
        assertTrue(ranB)
        finish.complete(Unit)
        holder.join()
    }

    @Test fun scanClaimIsRefusedWhileAGraduationHoldsTheSong() = runTest {
        val graduationHolding = CompletableDeferred<Unit>()
        val finishGraduation = CompletableDeferred<Unit>()
        val claims = ScanRowClaims()
        val graduation = launch {
            FileOperationCoordinator.withSongLock("song") {
                graduationHolding.complete(Unit)
                finishGraduation.await()
            }
        }
        graduationHolding.await()

        assertFalse(claims.claim("song"))
        assertTrue(claims.claim("other"))

        finishGraduation.complete(Unit)
        graduation.join()
        claims.close()
        assertEquals(1, claims.deferred)
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
    }

    /**
     * The stale-extraction race: the scan read the file before graduation, graduation starts and
     * finishes entirely before the scan's commit. The lock is free by then — only the observation
     * stamp can refuse the claim.
     */
    @Test fun scanClaimIsRefusedForASongMutatedAfterTheScanBegan() = runTest {
        val claims = ScanRowClaims()
        FileOperationCoordinator.withSongLock("song") { /* graduation, start to finish */ }

        assertNotNull(FileOperationCoordinator.tryAcquireSong("song")).release()
        assertFalse(claims.claim("song"))
        claims.close()

        // A scan that started after the mutation sees nothing stale.
        val laterClaims = ScanRowClaims()
        assertTrue(laterClaims.claim("song"))
        laterClaims.close()
    }

    @Test fun mutationBeforeAnyScanBeganIsNotRemembered() = runTest {
        FileOperationCoordinator.withSongLock("song") { }
        val claims = ScanRowClaims()
        assertTrue(claims.claim("song"))
        claims.close()
    }

    @Test fun mutationStampsAreClearedWhenTheLastObservationCloses() = runTest {
        val first = ScanRowClaims()
        FileOperationCoordinator.withSongLock("song") { }
        assertFalse(first.claim("song"))
        first.close()

        val second = ScanRowClaims()
        assertTrue(second.claim("song"))
        second.close()
    }

    /** A claimed row stays claimed across the whole commit: a mutator waits for close(). */
    @Test fun scanLeaseIsHeldUntilClaimsClose() = runTest {
        val claims = ScanRowClaims()
        assertTrue(claims.claim("song"))
        assertTrue(claims.claim("song")) // re-claim within one scan is free, not a self-deadlock

        val deletion = async { FileOperationCoordinator.withSongLock("song") { "deleted" } }
        runCurrent()
        assertFalse(deletion.isCompleted)

        claims.close()
        assertEquals("deleted", deletion.await())
        // The scan's own lease records no mutation, so a concurrent scan isn't made to skip it.
        val next = ScanRowClaims()
        assertTrue(next.claim("song"))
        next.close()
    }

    @Test fun rootRetirementWaitsForUsersAndRefusesNewOnes() = runTest {
        val usage = assertNotNull(FileOperationCoordinator.acquireRoot("music"))
        var released = false
        val retirement = async {
            FileOperationCoordinator.retireRootWhenIdle("music") { released = true }
        }
        runCurrent()
        assertFalse(released)
        // Closed to new users while it drains; other roots unaffected.
        assertNull(FileOperationCoordinator.acquireRoot("music"))
        assertNotNull(FileOperationCoordinator.acquireRoot("arrivals")).release()

        usage.release()
        retirement.await()
        assertTrue(released)
        // Reopened once the release ran.
        assertNotNull(FileOperationCoordinator.acquireRoot("music")).release()
        assertEquals(0, FileOperationCoordinator.rootUserCountForTest("music"))
    }

    @Test fun idleRootRetiresImmediately() = runTest {
        assertEquals(7, FileOperationCoordinator.retireRootWhenIdle("music") { 7 })
    }

    @Test fun concurrentRetirementsOfOneRootRunOneAtATime() = runTest {
        val events = mutableListOf<String>()
        val usage = assertNotNull(FileOperationCoordinator.acquireRoot("music"))
        val first = async {
            FileOperationCoordinator.retireRootWhenIdle("music") { events += "first" }
        }
        runCurrent()
        val second = async {
            FileOperationCoordinator.retireRootWhenIdle("music") { events += "second" }
        }
        runCurrent()
        assertTrue(events.isEmpty())
        usage.release()
        first.await()
        second.await()
        assertEquals(listOf("first", "second"), events)
    }
}
