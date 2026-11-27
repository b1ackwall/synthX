package com.v1per.synthx.ui.screens.replacesong

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.AudioFileRetryOutcome
import com.v1per.synthx.data.repository.OldAudioFile
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongMembershipFingerprint
import com.v1per.synthx.data.repository.ReplaceSongMembershipSnapshot
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongPreviewResult
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.ReplaceSongStage
import com.v1per.synthx.data.repository.ReplacementMove
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceSongViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val viewModels = mutableListOf<ReplaceSongViewModel>()

    @Before fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDownMain() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun aSecondConfirmWhileTheReplaceRunsIsIgnored() {
        val repository = FakeReplaceRepository(holdReplace = true)
        val viewModel = subject(repository)

        viewModel.replace(readyPreview())
        viewModel.replace(readyPreview())

        assertEquals(1, repository.replaceCalls)
        repository.finishHeldReplace(ReplaceSongResult.Success(OldAudioFile.Removed))
        assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
    }

    @Test
    fun aSecondConfirmAfterACompletedReplaceIsIgnored() {
        val repository = FakeReplaceRepository(replace = { ReplaceSongResult.Success(OldAudioFile.Removed) })
        val viewModel = subject(repository)

        viewModel.replace(readyPreview())
        val completed = viewModel.outcome.value
        viewModel.replace(readyPreview())

        assertEquals(1, repository.replaceCalls, "no second backup and no second replace")
        assertEquals(completed, viewModel.outcome.value)
    }

    @Test
    fun aFailedReplaceCanBeConfirmedAgain() {
        val repository = FakeReplaceRepository(replace = { ReplaceSongResult.OldSongMissing })
        val viewModel = subject(repository)

        viewModel.replace(readyPreview())
        viewModel.dismissOutcome()
        viewModel.replace(readyPreview())

        assertEquals(2, repository.replaceCalls)
    }

    @Test
    fun aCleanReplaceCompletesWithNoWarningSoTheScreenLeavesAtOnce() {
        val viewModel = subject(FakeReplaceRepository(replace = { ReplaceSongResult.Success(OldAudioFile.Removed) }))

        viewModel.replace(readyPreview())

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertNull(complete.warning)
        assertNull(complete.retryTarget)
        assertNull(complete.exitMessage, "the screen shows the standard confirmation")
    }

    @Test
    fun theOldFilesStateGetsItsOwnWarningWithRetry() {
        listOf(
            OldAudioFile.StillPresent to "cleanup pending",
            OldAudioFile.Unconfirmed to "cleanup uncertain",
        ).forEach { (state, expected) ->
            val viewModel = subject(FakeReplaceRepository(replace = { ReplaceSongResult.Success(state) }))

            viewModel.replace(readyPreview())

            val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
            assertEquals(expected, complete.warning)
            assertEquals(AudioCleanupRetryTarget("old", "test://old"), complete.retryTarget)
        }
    }

    @Test
    fun aFollowUpFailureIsAWarningNeverAFailedReplace() {
        val viewModel = subject(
            FakeReplaceRepository(
                replace = { ReplaceSongResult.Success(OldAudioFile.Removed, followUpIncomplete = true) },
            ),
        )

        viewModel.replace(readyPreview())

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertEquals("follow-up", complete.warning)
        assertNull(complete.retryTarget, "the old file is gone, so there is nothing to retry")
    }

    @Test
    fun theGenericFollowUpWarningGivesWayToTheSpecificOnes() {
        val viewModel = subject(
            FakeReplaceRepository(
                replace = {
                    ReplaceSongResult.Success(
                        OldAudioFile.StillPresent,
                        followUpIncomplete = true,
                        replacementMove = ReplacementMove.Incomplete,
                    )
                },
            ),
        )

        viewModel.replace(readyPreview())

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertEquals("cleanup pending\n\nmove incomplete", complete.warning)
    }

    @Test
    fun anIncompleteMoveWarnsWithOkOnly() {
        val viewModel = subject(
            FakeReplaceRepository(
                replace = {
                    ReplaceSongResult.Success(OldAudioFile.Removed, replacementMove = ReplacementMove.Incomplete)
                },
            ),
        )

        viewModel.replace(readyPreview())

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertEquals("move incomplete", complete.warning)
        assertNull(complete.retryTarget)
    }

    @Test
    fun theMoveStageShowsOnlyWhileTheReplacementIsBeingMoved() {
        val repository = FakeReplaceRepository(holdReplace = true)
        val viewModel = subject(repository)

        viewModel.replace(readyPreview())
        assertFalse(viewModel.movingReplacement.value)
        repository.reportStage(ReplaceSongStage.MovingReplacement)
        assertTrue(viewModel.movingReplacement.value)

        repository.finishHeldReplace(
            ReplaceSongResult.Success(OldAudioFile.Removed, replacementMove = ReplacementMove.Moved),
        )
        assertFalse(viewModel.movingReplacement.value)
    }

    @Test
    fun anUnreadableReplacementFailsWithItsOwnMessage() {
        val viewModel = subject(FakeReplaceRepository(replace = { ReplaceSongResult.NewSongUnreadable }))

        viewModel.replace(readyPreview())

        assertEquals("unreadable", assertIs<ReplaceSongOutcome.Failed>(viewModel.outcome.value).message)
    }

    @Test
    fun aRepositoryExceptionIsTheGenericNothingChangedFailure() {
        val viewModel = subject(FakeReplaceRepository(replace = { error("transaction failed") }))

        viewModel.replace(readyPreview())

        assertEquals("nothing changed", assertIs<ReplaceSongOutcome.Failed>(viewModel.outcome.value).message)
    }

    @Test
    fun aRetryThatFindsTheFileInTheLibraryLeavesWithItsOwnMessage() {
        val repository = FakeReplaceRepository(
            replace = { ReplaceSongResult.Success(OldAudioFile.StillPresent) },
            retry = AudioFileRetryOutcome.FileInLibrary,
        )
        val viewModel = subject(repository)
        viewModel.replace(readyPreview())
        val target = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value).retryTarget!!

        viewModel.retryAudioFileDeletion(target)

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertNull(complete.warning)
        assertEquals("file in library", complete.exitMessage)
        assertEquals(listOf("old" to "test://old"), repository.retries)
    }

    @Test
    fun aSuccessfulRetryKeepsAnyOtherWarning() {
        val viewModel = subject(
            FakeReplaceRepository(
                replace = {
                    ReplaceSongResult.Success(OldAudioFile.StillPresent, replacementMove = ReplacementMove.Incomplete)
                },
                retry = AudioFileRetryOutcome.Deleted,
            ),
        )
        viewModel.replace(readyPreview())

        viewModel.retryAudioFileDeletion(AudioCleanupRetryTarget("old", "test://old"))

        val complete = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
        assertEquals("move incomplete", complete.warning)
        assertNull(complete.retryTarget)
    }

    @Test
    fun stalePreviewRefreshesReviewAndReportsThatNothingChanged() {
        var previewCalls = 0
        val viewModel = subject(
            FakeReplaceRepository(
                preview = {
                    previewCalls++
                    readyPreviewResult(movesToMusic = previewCalls > 1)
                },
                replace = { ReplaceSongResult.PreviewStale },
            ),
        )
        viewModel.selectOldSong(song("old"))
        viewModel.selectNewSong(song("new"))
        val ready = assertIs<ReplaceSongPreviewState.Ready>(viewModel.preview.value)
        assertFalse(ready.preview.replacementMovesToMusic)

        viewModel.replace(ready)

        assertEquals(2, previewCalls)
        assertEquals("preview stale", assertIs<ReplaceSongOutcome.Failed>(viewModel.outcome.value).message)
        val refreshed = assertIs<ReplaceSongPreviewState.Ready>(viewModel.preview.value)
        assertTrue(refreshed.preview.replacementMovesToMusic, "the refresh carries the recomputed move flag")
    }

    @Test
    fun missingPreviewRowsUseSpecificMessagesAndAreNotRetryable() {
        listOf(
            ReplaceSongPreviewResult.OldSongMissing to "old missing",
            ReplaceSongPreviewResult.NewSongMissing to "new missing",
        ).forEach { (previewResult, expectedMessage) ->
            val viewModel = subject(FakeReplaceRepository(preview = { previewResult }))
            viewModel.selectOldSong(song("old"))
            viewModel.selectNewSong(song("new"))

            val failed = assertIs<ReplaceSongPreviewState.Failed>(viewModel.preview.value)
            assertEquals(expectedMessage, failed.message)
            assertFalse(failed.retryable)
        }
    }

    @Test
    fun conflictResolutionResetsWhenANewPreviewIsGenerated() {
        val viewModel = subject(FakeReplaceRepository())
        viewModel.selectOldSong(song("old"))
        viewModel.selectNewSong(song("new"))
        viewModel.setConflictResolution(PlaylistConflictResolution.KeepNew)

        viewModel.backToPickNew()
        viewModel.selectNewSong(song("newer"))

        assertEquals(PlaylistConflictResolution.KeepOld, viewModel.conflictResolution.value)
    }

    private fun subject(repository: FakeReplaceRepository) = ReplaceSongViewModel(
        repository = repository.proxy,
        appContext = Application(),
        stringResource = { id ->
            when (id) {
                R.string.replace_song_done_cleanup_pending -> "cleanup pending"
                R.string.replace_song_done_cleanup_uncertain -> "cleanup uncertain"
                R.string.replace_song_done_housekeeping -> "follow-up"
                R.string.replace_song_done_move_incomplete -> "move incomplete"
                R.string.replace_song_failed_replacement_unreadable -> "unreadable"
                R.string.audio_cleanup_retry_file_in_library -> "file in library"
                R.string.replace_song_failed_generic -> "nothing changed"
                R.string.replace_song_failed_old_missing -> "old missing"
                R.string.replace_song_failed_new_missing -> "new missing"
                R.string.replace_song_preview_stale -> "preview stale"
                else -> error("Unexpected string resource: $id")
            }
        },
    ).also { viewModels += it }

    /**
     * A [SongRepository] proxy for the calls the wizard makes. With [holdReplace], replaceSong
     * suspends until [finishHeldReplace], which is how a replace still in progress is held open.
     */
    private class FakeReplaceRepository(
        private val preview: () -> ReplaceSongPreviewResult = { readyPreviewResult() },
        private val replace: () -> ReplaceSongResult = { ReplaceSongResult.SameSong },
        private val retry: AudioFileRetryOutcome = AudioFileRetryOutcome.Deleted,
        private val holdReplace: Boolean = false,
    ) {
        var replaceCalls = 0
        val retries = mutableListOf<Pair<String, String>>()
        private var held: Continuation<Any?>? = null
        private var onStage: ((ReplaceSongStage) -> Unit)? = null
        private val songs = MutableStateFlow<List<Song>>(emptyList())

        fun finishHeldReplace(result: ReplaceSongResult) {
            checkNotNull(held) { "No replace is being held" }.resumeWith(Result.success(result))
            held = null
        }

        fun reportStage(stage: ReplaceSongStage) = checkNotNull(onStage).invoke(stage)

        @Suppress("UNCHECKED_CAST")
        val proxy: SongRepository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSongs" -> songs

                "previewReplaceSong" -> preview()

                "replaceSong" -> {
                    replaceCalls++
                    onStage = args[4] as (ReplaceSongStage) -> Unit
                    if (holdReplace) {
                        held = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    } else {
                        replace()
                    }
                }

                "retryAudioFileDeletion" -> {
                    retries += (args[0] as String) to (args[1] as String)
                    retry
                }

                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }

    private companion object {
        fun readyPreview(): ReplaceSongPreviewState.Ready {
            val result = readyPreviewResult()
            return ReplaceSongPreviewState.Ready(result.preview, result.membershipSnapshot)
        }

        fun readyPreviewResult(movesToMusic: Boolean = false): ReplaceSongPreviewResult.Ready =
            ReplaceSongPreviewResult.Ready(
                preview = ReplaceSongPreview(
                    oldSong = song("old"),
                    newSong = song("new"),
                    transferringPlaylists = listOf("Playlist"),
                    conflictingPlaylists = emptyList(),
                    newSongOnlyPlaylists = emptyList(),
                    replacementMovesToMusic = movesToMusic,
                ),
                membershipSnapshot = ReplaceSongMembershipSnapshot(
                    oldSongMemberships = listOf(ReplaceSongMembershipFingerprint("playlist", 0, 1L)),
                    newSongMemberships = emptyList(),
                ),
            )

        fun song(id: String) = Song(id, id, "Artist", "Album", 1L, "test://$id")
    }
}
