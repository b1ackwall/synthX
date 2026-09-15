package com.v1per.synthx.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreBoundaryAndStagingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val staging = File(context.noBackupFilesDir, "backup_staging")

    @After fun tearDown() {
        staging.listFiles().orEmpty().forEach(File::delete)
        File(context.filesDir, "restore_incidents").listFiles().orEmpty().forEach(File::delete)
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
    }

    @Test fun unexpectedStartupThrowAlwaysPublishesEmergencyAndSignalsReady() {
        RestoreCoordinator.resetForTest()
        var ready = false
        val result = RestoreStartup.runProtectedRecovery(
            context,
            recovery = { error("unexpected boundary failure") },
            onReady = { ready = true },
        )
        assertEquals(RestoreRecovery.EMERGENCY, result)
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        assertTrue(ready)
    }

    @Test fun startupScavengerRemovesRecognizedOrphansOnly() {
        staging.mkdirs()
        val orphan = File(staging, "snapshot-${UUID.randomUUID()}.partial.db").apply { writeText("orphan") }
        val unrelated = File(staging, "keep-me.db").apply { writeText("owned elsewhere") }
        scavengeBackupStagesAtProcessStart(context)
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun scavengerPreservesAnActiveStage() {
        val lease = BackupStagingCoordinator.acquire(context, "preview")
        lease.file.writeText("in flight")
        BackupStagingCoordinator.scavenge(context)
        assertTrue(lease.file.exists())
        lease.close()
        assertFalse(lease.file.exists())
    }
}
