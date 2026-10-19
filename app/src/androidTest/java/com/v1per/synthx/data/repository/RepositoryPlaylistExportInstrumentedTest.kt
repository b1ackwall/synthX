package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every playlist or group change asks for a playlist export.
 *
 * Seen through the real export manager: a backup folder is set (unreachable), so each request
 * runs after the 800 ms quiet period and records a failed attempt in [BackupOutcomeStore].
 */
@RunWith(AndroidJUnit4::class)
class RepositoryPlaylistExportInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        fx.pointExportsAtUnreachableFolder()
        fx.song("a")
        fx.song("b")
        fx.group("g")
        fx.playlist("p", groupId = "g", songIds = arrayOf("a"))
    }

    @After fun tearDown() = fx.close()

    private suspend fun assertExportRunsAfter(what: String, change: suspend () -> Unit) {
        BackupOutcomeStore.resetForTest()
        change()
        val outcome = awaitEmission(BackupOutcomeStore.lastExportOutcome, "export after $what", timeoutMs = 4_000) {
            it != null
        }!!
        assertFalse("the unreachable folder makes the attempt fail", outcome.succeeded)
    }

    @Test fun createPlaylistExports() = runBlocking {
        assertExportRunsAfter("create playlist") { repository.createPlaylist("New", null) }
    }

    @Test fun renamePlaylistExports() = runBlocking {
        assertExportRunsAfter("rename playlist") { repository.renamePlaylist("p", "Renamed") }
    }

    @Test fun movePlaylistExports() = runBlocking {
        assertExportRunsAfter("move playlist") { repository.movePlaylistToGroup("p", null) }
    }

    @Test fun deletePlaylistExports() = runBlocking {
        assertExportRunsAfter("delete playlist") { repository.deletePlaylist("p") }
    }

    @Test fun addSongExports() = runBlocking {
        assertExportRunsAfter("add song") { repository.addSongToPlaylist("p", "b") }
    }

    @Test fun removeSongExports() = runBlocking {
        assertExportRunsAfter("remove song") { repository.removeSongFromPlaylist("p", "a") }
    }

    @Test fun createGroupExports() = runBlocking {
        assertExportRunsAfter("create group") { repository.createGroup("New group") }
    }

    @Test fun renameGroupExports() = runBlocking {
        assertExportRunsAfter("rename group") { repository.renameGroup("g", "Renamed group") }
    }

    @Test fun deleteGroupExports() = runBlocking {
        assertExportRunsAfter("delete group") { repository.deleteGroup("g") }
    }

    @Test fun addingASongAlreadyThereDoesNotExport() = runBlocking {
        BackupOutcomeStore.resetForTest()

        assertEquals(false, repository.addSongToPlaylist("p", "a"))
        delay(2_000)

        assertNull("no export after a refused add", BackupOutcomeStore.lastExportOutcome.value)
    }
}
