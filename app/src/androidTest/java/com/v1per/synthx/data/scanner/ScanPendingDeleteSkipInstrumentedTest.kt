package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.bytes
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.uriOf
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An old audio file that a delete or replace is still waiting to remove (a kept pending DELETE
 * record) is never imported again by a scan of either folder, and is imported normally once the
 * record is gone.
 */
@RunWith(AndroidJUnit4::class)
class ScanPendingDeleteSkipInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File
    private lateinit var arrivals: File

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
        arrivals = fx.root("arrivals")
    }

    @After fun tearDown() = fx.close()

    private suspend fun keepDeleteRecordFor(file: File) {
        fx.database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = "op-${file.name}",
                songId = "replaced",
                operationType = PendingCleanupType.DELETE.name,
                filePath = uriOf(file),
                publishedPath = null,
                phase = PendingCleanupPhase.AudioFileRemains.name,
                createdAt = 1,
            ),
        )
    }

    @Test fun aMusicScanSkipsAFileWaitingToBeDeleted() = runBlocking {
        val surviving = write(music, "old.mp3", bytes(2_000, seed = 1))
        val other = write(music, "other.mp3", bytes(2_000, seed = 2))
        keepDeleteRecordFor(surviving)

        val result = fx.scan(music, otherRoots = listOf(arrivals))

        assertEquals(1, result.added)
        assertNull(fx.songAt(surviving))
        assertNotNull(fx.songAt(other))
    }

    @Test fun aNewArrivalsScanSkipsItToo() = runBlocking {
        val surviving = write(arrivals, "old.mp3", bytes(2_000, seed = 3))
        keepDeleteRecordFor(surviving)

        val result = fx.scan(arrivals, newArrivals = true, otherRoots = listOf(music))

        assertEquals(0, result.added)
        assertNull(fx.songAt(surviving))
    }

    @Test fun onceTheRecordIsClearedTheFileIsImportedAgain() = runBlocking {
        val surviving = write(music, "old.mp3", bytes(2_000, seed = 4))
        keepDeleteRecordFor(surviving)
        fx.scan(music, otherRoots = listOf(arrivals))
        assertNull(fx.songAt(surviving))

        fx.database.pendingCleanupDao().clearDeletesForPath(uriOf(surviving))
        fx.scan(music, otherRoots = listOf(arrivals))

        assertNotNull(fx.songAt(surviving))
    }
}
