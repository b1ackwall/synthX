package com.v1per.synthx.ui.screens.replacesong

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.includePlaylistExportOutcome
import com.v1per.synthx.data.repository.runPostCommitStep
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceSongViewModelTest {

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
                    playlistsAffected = 1,
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

    private fun replacementRepository(replace: () -> ReplaceSongResult): SongRepository {
        val songs = MutableStateFlow<List<Song>>(emptyList())
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongs" -> songs
                "replaceSong" -> replace()
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }

    private fun readyPreview(): ReplaceSongPreviewState.Ready {
        fun song(id: String) = Song(id, id, "Artist", "Album", 1L, "test://$id")
        return ReplaceSongPreviewState.Ready(
            ReplaceSongPreview(
                oldSong = song("old"),
                newSong = song("new"),
                transferringPlaylists = listOf("Playlist"),
                conflictingPlaylists = emptyList(),
                newSongOnlyPlaylists = emptyList(),
            ),
        )
    }
}
