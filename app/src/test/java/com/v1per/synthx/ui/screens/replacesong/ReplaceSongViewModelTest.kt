package com.v1per.synthx.ui.screens.replacesong

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongMembershipFingerprint
import com.v1per.synthx.data.repository.ReplaceSongMembershipSnapshot
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongPreviewResult
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.includePlaylistExportOutcome
import com.v1per.synthx.data.repository.runPostCommitStep
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceSongViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test
    fun postCommitThrowProducesCompletedReplacementWithUncertainCleanupWarning() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var oldRowGone = false
        try {
            val repository = replacementRepository {
                oldRowGone = true
                val exportSucceeded = runBlocking {
                    runPostCommitStep(
                        step = "schedule playlist export",
                        logFailure = { _, _ -> },
                    ) {
                        error("export scheduling failed after commit")
                    }
                }
                ReplaceSongResult.Success(
                    oldAudioCleanup = includePlaylistExportOutcome(
                        deletionOutcome = SongDeletionOutcome.Deleted,
                        exportSucceeded = exportSucceeded,
                    ),
                )
            }
            val viewModel = ReplaceSongViewModel(
                repository = repository,
                appContext = Application(),
                stringResource = { id ->
                    when (id) {
                        R.string.replace_song_done_cleanup_uncertain -> "cleanup uncertain"
                        R.string.replace_song_failed_generic -> "nothing changed"
                        else -> error("Unexpected string resource: $id")
                    }
                },
            )
            try {
                viewModel.replace(readyPreview())

                assertTrue(oldRowGone)
                val outcome = assertIs<ReplaceSongOutcome.Complete>(viewModel.outcome.value)
                assertEquals("cleanup uncertain", outcome.warning)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stalePreviewRefreshesReviewAndReportsThatNothingChanged() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var previewCalls = 0
        val repository = replacementRepository(
            preview = {
                previewCalls++
                readyPreviewResult()
            },
            replace = { ReplaceSongResult.PreviewStale },
        )
        val viewModel = subject(repository)
        try {
            viewModel.selectOldSong(song("old"))
            viewModel.selectNewSong(song("new"))
            val ready = assertIs<ReplaceSongPreviewState.Ready>(viewModel.preview.value)

            viewModel.replace(ready)

            assertEquals(2, previewCalls)
            assertEquals(
                "preview stale",
                assertIs<ReplaceSongOutcome.Failed>(viewModel.outcome.value).message,
            )
            assertIs<ReplaceSongPreviewState.Ready>(viewModel.preview.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun missingPreviewRowsUseSpecificMessagesAndAreNotRetryable() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            listOf(
                ReplaceSongPreviewResult.OldSongMissing to "old missing",
                ReplaceSongPreviewResult.NewSongMissing to "new missing",
            ).forEach { (previewResult, expectedMessage) ->
                val viewModel = subject(replacementRepository(preview = { previewResult }))
                try {
                    viewModel.selectOldSong(song("old"))
                    viewModel.selectNewSong(song("new"))

                    val failed = assertIs<ReplaceSongPreviewState.Failed>(viewModel.preview.value)
                    assertEquals(expectedMessage, failed.message)
                    assertFalse(failed.retryable)
                } finally {
                    viewModel.viewModelScope.cancel()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun conflictResolutionResetsWhenANewPreviewIsGenerated() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val viewModel = subject(replacementRepository(preview = { readyPreviewResult() }))
        try {
            viewModel.selectOldSong(song("old"))
            viewModel.selectNewSong(song("new"))
            viewModel.setConflictResolution(PlaylistConflictResolution.KeepNew)

            viewModel.backToPickNew()
            viewModel.selectNewSong(song("newer"))

            assertEquals(PlaylistConflictResolution.KeepOld, viewModel.conflictResolution.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun subject(repository: SongRepository) = ReplaceSongViewModel(
        repository = repository,
        appContext = Application(),
        stringResource = { id ->
            when (id) {
                R.string.replace_song_done_cleanup_uncertain -> "cleanup uncertain"
                R.string.replace_song_failed_generic -> "nothing changed"
                R.string.replace_song_failed_old_missing -> "old missing"
                R.string.replace_song_failed_new_missing -> "new missing"
                R.string.replace_song_preview_stale -> "preview stale"
                else -> error("Unexpected string resource: $id")
            }
        },
    )

    private fun replacementRepository(
        preview: () -> ReplaceSongPreviewResult = { readyPreviewResult() },
        replace: () -> ReplaceSongResult = { ReplaceSongResult.SameSong },
    ): SongRepository {
        val songs = MutableStateFlow<List<Song>>(emptyList())
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongs" -> songs
                "previewReplaceSong" -> preview()
                "replaceSong" -> replace()
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }

    private fun readyPreview(): ReplaceSongPreviewState.Ready {
        val result = readyPreviewResult()
        return ReplaceSongPreviewState.Ready(result.preview, result.membershipSnapshot)
    }

    private fun readyPreviewResult(): ReplaceSongPreviewResult.Ready =
        ReplaceSongPreviewResult.Ready(
            preview = ReplaceSongPreview(
                oldSong = song("old"),
                newSong = song("new"),
                transferringPlaylists = listOf("Playlist"),
                conflictingPlaylists = emptyList(),
                newSongOnlyPlaylists = emptyList(),
            ),
            membershipSnapshot = ReplaceSongMembershipSnapshot(
                oldSongMemberships = listOf(
                    ReplaceSongMembershipFingerprint("playlist", 0, 1L),
                ),
                newSongMemberships = emptyList(),
            ),
        )

    private fun song(id: String) = Song(id, id, "Artist", "Album", 1L, "test://$id")
}
