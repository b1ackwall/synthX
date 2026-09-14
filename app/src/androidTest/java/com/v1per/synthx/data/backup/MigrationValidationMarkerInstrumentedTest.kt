package com.v1per.synthx.data.backup

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-T3 (R1-F08 + R1-F09): the migration/startup durability gap.
 *
 * Real process death cannot be simulated directly in an instrumented test, so — following the
 * same technique established for P3-T1's pending-cleanup tests — each test here constructs the
 * exact on-disk/preference state a real death at the relevant point would have left behind,
 * rather than racing real coroutine cancellation timing.
 */
@RunWith(AndroidJUnit4::class)
class MigrationValidationMarkerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val live: File get() = context.getDatabasePath(SynthXDatabase.DB_NAME)
    private val fromVersion = SynthXDatabase.VERSION - 1

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        RestoreStartup.await()
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        RestoreCoordinator.resetForTest()
        deleteLiveSet()
        clearMigrationSafetyState()
        DatabaseStartupGate.resetForTest(context)
    }

    @After
    fun tearDown() {
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) {
            RestoreCoordinator.resetForTest()
        }
        deleteLiveSet()
        clearMigrationSafetyState()
        DatabaseStartupGate.resetForTest(context)
    }

    // ---------------------------------------------------------------- R1-F08: death before verify

    @Test
    fun deathBetweenRoomCommitAndVerifyIsDetectedAsPendingRecoveryNotSilentlyTrusted() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, fromVersion).close()
        MigrationSnapshotStore.createSnapshot(context, fromVersion, SynthXDatabase.VERSION)
        MigrationValidationMarker.write(context, fromVersion, SynthXDatabase.VERSION)
        migrateLiveToCurrentViaRoom()
        // Deliberately no MigrationInvariantCheck.verify() and no MigrationValidationMarker.clear()
        // call here — this is exactly what a death between Room's commit and verify() leaves
        // behind. The snapshot is then removed so this assertion is driven ONLY by the marker's
        // own presence, not by MigrationSnapshotStore's independent "a snapshot still exists"
        // signal, which would force the same true result on its own and mask a broken marker check.
        MigrationSnapshotStore.clearAfterRecovery(context)

        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
        assertTrue(
            "A leftover validation marker must be treated the same as a validation failure, " +
                "not silently proceeded past just because the live file now reads as current",
            MigrationRecoveryGate.isRecoveryStillRequired(context),
        )
    }

    @Test
    fun recordSuccessfulLaunchCannotAdvanceWhileValidationMarkerPresent() = runBlocking {
        // Snapshot + its metadata are left INTACT here (unlike the test above): recordSuccessfulLaunch's
        // own forVersion==CURRENT check would otherwise happily let it proceed, so only the new
        // marker guard is what should stop it — isolating that guard's own effect.
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, fromVersion).close()
        MigrationSnapshotStore.createSnapshot(context, fromVersion, SynthXDatabase.VERSION)
        MigrationValidationMarker.write(context, fromVersion, SynthXDatabase.VERSION)
        migrateLiveToCurrentViaRoom()

        repeat(MigrationSnapshotStore.CONFIRMED_LAUNCHES_TO_RETIRE + 2) {
            MigrationSnapshotStore.recordSuccessfulLaunch(context)
        }

        assertEquals(0, PreferencesManager(context).migrationSnapshotConfirmedLaunches)
        assertNotNull(
            "The snapshot this marker still protects must not be retired while validation " +
                "was never provably completed",
            MigrationSnapshotStore.snapshotForCurrentVersion(context),
        )
    }

    // ------------------------------------------------------- recovery ordering: safe interruption

    @Test
    fun deathAfterDurableFlagClearButBeforeSnapshotDeletionIsHandledSafely() = runBlocking {
        // A real migration + snapshot, taken to completion (unlike the F08 tests above): the
        // live database is genuinely valid and current, and the failure flag/marker are already
        // correctly clear — only the snapshot's own deletion never ran before the (simulated)
        // process death right after the durable flag-clear in the new recovery ordering.
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, fromVersion).close()
        MigrationSnapshotStore.createSnapshot(context, fromVersion, SynthXDatabase.VERSION)
        migrateLiveToCurrentViaRoom()
        check(PreferencesManager(context).setMigrationInvariantFailurePendingDurably(false))
        MigrationValidationMarker.clear(context)
        assertNotNull(
            "Precondition: the snapshot must still be sitting there, undeleted",
            MigrationSnapshotStore.snapshotForCurrentVersion(context),
        )

        assertFalse(
            "Flag and marker are already correctly clear; a leftover stale snapshot alone " +
                "must never force the recovery-required prompt",
            MigrationRecoveryGate.isRecoveryStillRequired(context),
        )

        // The stale snapshot is inert, not dangerous, and still retires normally afterward.
        repeat(MigrationSnapshotStore.CONFIRMED_LAUNCHES_TO_RETIRE) {
            MigrationSnapshotStore.recordSuccessfulLaunch(context)
        }
        assertNull(MigrationSnapshotStore.snapshotForCurrentVersion(context))
    }

    // ------------------------------------------------------------ R1-F09: the original lockout

    @Test
    fun selfHealResolvesFlagSetWithNoSnapshotAndValidLiveDatabase() = runBlocking {
        // Simulates the OLD ordering's exact death window: the snapshot is already gone (as if
        // MigrationSnapshotStore.clearAfterRecovery ran) but the failure flag never got cleared —
        // yet the live database itself is genuinely fine.
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, fromVersion).close()
        MigrationSnapshotStore.createSnapshot(context, fromVersion, SynthXDatabase.VERSION)
        migrateLiveToCurrentViaRoom()
        MigrationSnapshotStore.clearAfterRecovery(context)
        check(PreferencesManager(context).setMigrationInvariantFailurePendingDurably(true))
        assertNull(MigrationSnapshotStore.snapshotForCurrentVersion(context))
        assertTrue(PreferencesManager(context).migrationInvariantFailurePending)

        assertFalse(
            "A genuinely valid live database with no snapshot to recover from is a structurally " +
                "impossible recovery, not a real failure — it must self-heal, not lock out forever",
            MigrationRecoveryGate.isRecoveryStillRequired(context),
        )
        assertFalse(
            "Self-heal must actually clear the stale flag, not just bypass it once",
            PreferencesManager(context).migrationInvariantFailurePending,
        )
    }

    @Test
    fun doesNotSelfHealWhenSnapshotStillExists() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, fromVersion).close()
        MigrationSnapshotStore.createSnapshot(context, fromVersion, SynthXDatabase.VERSION)
        migrateLiveToCurrentViaRoom()
        check(PreferencesManager(context).setMigrationInvariantFailurePendingDurably(true))
        assertNotNull(MigrationSnapshotStore.snapshotForCurrentVersion(context))

        assertTrue(
            "A snapshot still exists — real recovery must still be offered, not self-healed away",
            MigrationRecoveryGate.isRecoveryStillRequired(context),
        )
    }

    @Test
    fun doesNotSelfHealWhenLiveDatabaseIsNotValid() = runBlocking {
        // No live database at all is the simplest "not provably valid" case.
        check(PreferencesManager(context).setMigrationInvariantFailurePendingDurably(true))
        assertNull(MigrationSnapshotStore.snapshotForCurrentVersion(context))
        assertFalse(live.exists())

        assertTrue(
            "An unverifiable live database must never be waved through by self-heal",
            MigrationRecoveryGate.isRecoveryStillRequired(context),
        )
    }

    private fun migrateLiveToCurrentViaRoom() {
        SynthXDatabase.getInstanceForMigrationGate(context).openHelper.writableDatabase
        SynthXDatabase.closeInstance()
    }

    private fun deleteLiveSet() {
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm"), File(live.path + "-journal"))
            .forEach { it.delete() }
    }

    private fun clearMigrationSafetyState() {
        MigrationSnapshotStore.clearAfterRecovery(context)
        MigrationValidationMarker.clear(context)
        PreferencesManager(context).setMigrationInvariantFailurePendingDurably(false)
    }
}
