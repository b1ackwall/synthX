package com.v1per.synthx.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreBoundaryAndStagingInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val staging = File(context.noBackupFilesDir, "backup_staging")

    @After fun tearDown() {
        staging.listFiles().orEmpty().forEach(File::delete)
        File(context.filesDir, "restore_incidents").listFiles().orEmpty().forEach(File::delete)
        RestorePermitLeakCheck.assertDrainedThenReset()
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

    @Test fun unexpectedStartupThrowPersistsIncidentIdAndReport() {
        RestoreCoordinator.resetForTest()
        val incidentFile =
            File(context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile, "synthx.restore-incident.id")
        incidentFile.delete()
        try {
            val result = RestoreStartup.runProtectedRecovery(context, recovery = {
                error("unexpected boundary failure")
            })

            assertEquals(RestoreRecovery.EMERGENCY, result)
            val incidentId =
                requireNotNull(RestoreCoordinator.diagnosticOperationId.value) { "no incident id published" }
            assertEquals(incidentId, incidentFile.readText().trim())
            val report = RestoreIncidentReports.decode(RestoreIncidentReports.reportFile(context, incidentId))
            assertEquals("STARTUP_BOUNDARY_EXCEPTION", report.triggerCategory)
            assertTrue(report.exceptions.any { it.message.orEmpty().contains("unexpected boundary failure") })
        } finally {
            incidentFile.delete()
        }
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
