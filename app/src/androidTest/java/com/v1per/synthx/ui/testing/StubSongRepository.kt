package com.v1per.synthx.ui.testing

import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [SongRepository] answering only the group/playlist reads the playlist screens' ViewModels make
 * on construction; any other call fails loudly. Wrap it with `object : SongRepository by stub` to
 * override a suspend method for real (a Proxy handler cannot suspend) — the same shape
 * IssuesViewModelInstrumentedTest uses.
 */
internal fun stubSongRepository(
    groups: List<PlaylistGroup> = emptyList(),
    ungroupedPlaylists: List<Playlist> = emptyList(),
): SongRepository {
    val flows: Map<String, Flow<*>> = mapOf(
        "getAllGroups" to flowOf(groups),
        "getUngroupedPlaylists" to flowOf(ungroupedPlaylists),
    )
    return Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        flows[method.name] ?: error("Unexpected repository call: ${method.name}")
    } as SongRepository
}
