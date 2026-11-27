package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.scanner.GraduationCommit
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.data.scanner.commitGraduatedPath
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A replacement under the New Arrivals folder is graduated into the Music folder after the replace
 * commits, and the review is told so beforehand.
 *
 * A real graduation needs persisted SAF grants on two trees, which an instrumentation test cannot
 * obtain (see SongGraduationCoordinationInstrumentedTest). The graduation seam here therefore
 * runs graduation's own database step, [commitGraduatedPath], against the same Room database: the
 * row ends up exactly as a real graduation leaves it, under its Music-folder path and un-starred.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceSongGraduationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: ReplaceSongTestFixture
    private val musicPath = "test://music/new"

    @Before fun setUp() = runBlocking {
        fx = ReplaceSongTestFixture(ApplicationProvider.getApplicationContext())
        fx.seed()
    }

    @After fun tearDown() = fx.close()

    @Test fun aNewArrivalsReplacementIsGraduatedOnceTheReplaceHasCommitted() = runBlocking {
        val stages = mutableListOf<ReplaceSongStage>()
        var oldRowGoneWhenGraduating = false
        var lockedSongsWhenGraduating = -1
        val repository = fx.repository(
            audioFileDelete = { AudioFileDeletion.Deleted },
            replacementRoot = { KnownAudioRoot.NewArrivals },
            graduateReplacement = { songId ->
                oldRowGoneWhenGraduating = fx.songDao.getSongById("old") == null
                lockedSongsWhenGraduating = FileOperationCoordinator.songLockEntryCountForTest()
                commitGraduatedPath(fx.database, fx.songDao, songId, musicPath) is GraduationCommit.Updated
            },
        )

        val result = fx.replace(repository) { stages += it }

        assertEquals(
            ReplaceSongResult.Success(OldAudioFile.Removed, replacementMove = ReplacementMove.Moved),
            result,
        )
        assertTrue("graduation runs after the commit", oldRowGoneWhenGraduating)
        assertEquals("the old song's lock is released before graduation", 0, lockedSongsWhenGraduating)
        assertEquals(listOf(ReplaceSongStage.MovingReplacement), stages)
        val replacement = requireNotNull(fx.songDao.getSongById("new"))
        assertEquals(musicPath, replacement.filePath)
        assertFalse(replacement.isStarred)
        assertEquals(111L, replacement.dateAdded)
    }

    @Test fun aFailedGraduationLeavesItStarredInNewArrivalsAndTheReplaceCommitted() = runBlocking {
        val repository = fx.repository(
            audioFileDelete = { AudioFileDeletion.Deleted },
            replacementRoot = { KnownAudioRoot.NewArrivals },
            graduateReplacement = { false },
        )

        val result = fx.replace(repository)

        assertEquals(
            ReplaceSongResult.Success(OldAudioFile.Removed, replacementMove = ReplacementMove.Incomplete),
            result,
        )
        assertNull(fx.songDao.getSongById("old"))
        assertEquals(2, fx.playlistDao.getPlaylistMembershipsForSong("new").single().position)
        val replacement = requireNotNull(fx.songDao.getSongById("new"))
        assertTrue("it stays starred until graduation un-stars it", replacement.isStarred)
        assertEquals("test://new", replacement.filePath)
    }

    @Test fun aThrowingGraduationIsReportedAsIncomplete() = runBlocking {
        val repository = fx.repository(
            audioFileDelete = { AudioFileDeletion.Deleted },
            replacementRoot = { KnownAudioRoot.NewArrivals },
            graduateReplacement = { error("graduation threw") },
        )

        assertEquals(ReplacementMove.Incomplete, (fx.replace(repository) as ReplaceSongResult.Success).replacementMove)
        assertTrue(requireNotNull(fx.songDao.getSongById("new")).isStarred)
    }

    @Test fun aReplacementAlreadyUnderMusicIsNotMovedAndIsUnstarred() = runBlocking {
        val repository = fx.repository(audioFileDelete = { AudioFileDeletion.Deleted })

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed), result)
        val replacement = requireNotNull(fx.songDao.getSongById("new"))
        assertFalse(replacement.isStarred)
        assertEquals("test://new", replacement.filePath)
    }

    @Test fun aNewArrivalsReplacementInNoPlaylistIsNeitherMovedNorUnstarred() = runBlocking {
        fx.playlistDao.removePlaylistSong("p", "old")
        val repository = fx.repository(
            audioFileDelete = { AudioFileDeletion.Deleted },
            replacementRoot = { KnownAudioRoot.NewArrivals },
        )

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed), fx.replace(repository))
        assertTrue(requireNotNull(fx.songDao.getSongById("new")).isStarred)
    }

    @Test fun thePreviewFlagsTheMoveOnlyForANewArrivalsReplacementThatEndsInAPlaylist() = runBlocking {
        assertFalse(previewMoves(KnownAudioRoot.Music))
        assertFalse(previewMoves(null))
        assertTrue(previewMoves(KnownAudioRoot.NewArrivals))

        fx.playlistDao.removePlaylistSong("p", "old")
        assertFalse("no playlist afterwards, so nothing to move", previewMoves(KnownAudioRoot.NewArrivals))
    }

    @Test fun eachPreviewRecomputesTheMoveFromTheReplacementsCurrentFolder() = runBlocking {
        var root: KnownAudioRoot? = KnownAudioRoot.Music
        val repository = fx.repository(replacementRoot = { root })

        assertFalse(preview(repository).replacementMovesToMusic)
        root = KnownAudioRoot.NewArrivals
        assertTrue(preview(repository).replacementMovesToMusic)
    }

    private suspend fun previewMoves(root: KnownAudioRoot?): Boolean =
        preview(fx.repository(replacementRoot = { root })).replacementMovesToMusic

    private suspend fun preview(repository: SongRepositoryImpl): ReplaceSongPreview =
        (repository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready).preview
}
