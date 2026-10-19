package com.v1per.synthx.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.expectFailureOf
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards in the SynthXDatabase companion. Neither test opens a database: a refused call
 * returns nothing, and a call that wrongly succeeds only builds a lazy Room instance,
 * which is closed without touching the live synthx.db file.
 */
@RunWith(AndroidJUnit4::class)
class SynthXDatabaseBuilderGuardInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var liveFileExistedBefore = false

    @Before
    fun setUp() {
        RestoreStartup.await()
        SynthXDatabase.closeInstance()
        RestoreStartup.resetForTest()
        RestoreCoordinator.resetForTest()
        liveFileExistedBefore = context.getDatabasePath(SynthXDatabase.DB_NAME).exists()
    }

    @After
    fun tearDown() {
        SynthXDatabase.closeInstance()
        DatabaseStartupGate.resetForTest(context)
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @Test
    fun standaloneBuilderRefusesTheLiveDatabaseName() {
        expectFailureOf(IllegalArgumentException::class, "cannot target the live database name") {
            SynthXDatabase.createStandalone(context, SynthXDatabase.DB_NAME).close()
        }
        assertLiveFileUntouched()
    }

    @Test
    fun restoreRoomAccessRefusesOutsideRestorePreparation() {
        DatabaseStartupGate.markReadyForTest()
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
        expectFailureOf(IllegalStateException::class, "requires restore preparation ownership") {
            SynthXDatabase.getInstanceForRestore(context)
        }
        assertLiveFileUntouched()
    }

    private fun assertLiveFileUntouched() {
        if (!liveFileExistedBefore) {
            assertFalse("synthx.db was created", context.getDatabasePath(SynthXDatabase.DB_NAME).exists())
        }
    }
}
