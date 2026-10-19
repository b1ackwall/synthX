package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the "no backup folder configured" carve-out: unlike a genuinely unreachable
 * folder (a real, logged, retryable failure — see [BackupManager.publishExistingSnapshot]'s
 * doc), a device with backups turned off entirely must not fail restore's cold-start cleanup.
 * [SafetySnapshotPromoter.promoteSafetyToAutoTier] is constructed directly against a hand-built
 * [RestoreTransaction.State] ("the interrupted state directly" technique other Phase B tests use)
 * rather than through the full restore state machine, since only this one leg is under test.
 */
@RunWith(AndroidJUnit4::class)
class SafetySnapshotPromoterInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "safety-promoter-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val dir = File(appContext.cacheDir, "safety-promoter-dir-${UUID.randomUUID()}").apply { mkdirs() }
    private val createdDatabaseNames = mutableSetOf<String>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
        createdDatabaseNames.forEach(context::deleteDatabase)
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
    }

    private fun operationId() = UUID.randomUUID().toString().replace("-", "")

    /** A real, valid current-schema snapshot — [BackupSnapshotValidator.validate] must accept it. */
    private fun validSafetySnapshot(name: String): File {
        val builderName = "safety-src-${UUID.randomUUID()}.db"
        createdDatabaseNames += builderName
        val database = SynthXDatabase.createStandalone(context, builderName)
        val destination = File(dir, name)
        try {
            database.openHelper.writableDatabase
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
        return destination
    }

    @Test
    fun noBackupFolderConfiguredSkipsPromotionSilentlyAndStillCleansUpLocalArtifacts() {
        PreferencesManager(context).backupFolderUri = null
        val op = operationId()
        val names = RestoreArtifactNames(op)
        val safety = validSafetySnapshot(names.safety)
        val digest = BackupHashing.hash(safety)
        val state = RestoreTransaction.State(
            operationId = op,
            sourceName = "test-source",
            sourceUri = "",
            candidateName = names.candidate,
            oldName = names.old,
            safetyName = names.safety,
            failedName = names.failed,
            candidateSha256 = digest.sha256,
            candidateSize = digest.sizeBytes,
            candidateVersion = SynthXDatabase.VERSION,
            safetySha256 = digest.sha256,
            safetySize = digest.sizeBytes,
            phase = RestoreTransaction.Phase.COMPLETE,
            createdAt = System.currentTimeMillis(),
            outcome = RestoreTransaction.Outcome.INSTALLED,
        )

        // Must not throw: a device with backups off entirely is a valid steady state, not a
        // retryable failure — see publishSafetyPromotion's doc.
        SafetySnapshotPromoter.promoteSafetyToAutoTier(context, dir, state)

        assertFalse(
            "Staged/renamed safety-promotion file must still be cleaned up locally",
            File(dir, names.safetyPromotion).exists(),
        )
        assertFalse("Original safety file must still be cleaned up locally", safety.exists())
    }
}
