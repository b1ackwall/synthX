package com.v1per.synthx.ui.screens.replacesong

import android.content.Context
import com.v1per.synthx.data.repository.ReplaceSongMembershipFingerprint
import com.v1per.synthx.data.repository.ReplaceSongMembershipSnapshot
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongPreviewResult
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [SongRepository] proxy answering only the Replace Song wizard's calls. No database and no
 * files: the replace result is whatever [replace] returns, or, with [holdReplace], replaceSong
 * stays suspended so the screen stays in its working state.
 */
internal class ReplaceSongTestRepository(
    private val conflicts: Boolean = false,
    private val holdReplace: Boolean = false,
    private val replace: () -> ReplaceSongResult = { error("No replace expected") },
) {
    var replaceCalls = 0
        private set
    private var held: Continuation<Any?>? = null

    val oldSong = Song("old", "Old Song", "Artist", "Album", 1L, "test://old")
    val newSong = Song("new", "New Song", "Artist", "Album", 1L, "test://new")

    /** Lets a held replace finish, so nothing is left suspended after the test. */
    fun finishHeldReplace(result: ReplaceSongResult) {
        held?.resumeWith(Result.success(result))
        held = null
    }

    private val songs = MutableStateFlow(listOf(oldSong, newSong))

    @Suppress("UNCHECKED_CAST")
    val proxy: SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSongs" -> songs

            "previewReplaceSong" -> ReplaceSongPreviewResult.Ready(preview(conflicts), snapshot(conflicts))

            "replaceSong" -> {
                replaceCalls++
                if (holdReplace) {
                    held = args.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else {
                    replace()
                }
            }

            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

    fun viewModel(context: Context) = ReplaceSongViewModel(proxy, context.applicationContext)

    fun preview(conflicts: Boolean, movesToMusic: Boolean = false) = ReplaceSongPreview(
        oldSong = oldSong,
        newSong = newSong,
        transferringPlaylists = listOf("Road Trip"),
        conflictingPlaylists = if (conflicts) listOf("Favourites") else emptyList(),
        newSongOnlyPlaylists = emptyList(),
        replacementMovesToMusic = movesToMusic,
    )

    private fun snapshot(conflicts: Boolean) = ReplaceSongMembershipSnapshot(
        oldSongMemberships = listOf(ReplaceSongMembershipFingerprint("road", 0, 1L)) +
            if (conflicts) listOf(ReplaceSongMembershipFingerprint("fav", 1, 1L)) else emptyList(),
        newSongMemberships = if (conflicts) listOf(ReplaceSongMembershipFingerprint("fav", 4, 2L)) else emptyList(),
    )
}
