package com.v1per.synthx.data.playlistart

import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.repository.PlaylistMembership
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule

class PlaylistArtBackfillTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun `a library with nothing missing costs one query and no writes`() = runTest {
        val dao = FakePlaylistDao(missing = emptyList(), existingHues = listOf(0, 120, 240))

        assertEquals(0, PlaylistArtBackfill.backfill(dao, Random(1)))

        assertEquals(0, dao.existingHueQueries)
        assertTrue(dao.writes.isEmpty())
    }

    @Test
    fun `every playlist missing art is written exactly once, in the order given`() = runTest {
        val dao = FakePlaylistDao(missing = listOf("oldest", "middle", "newest"))

        assertEquals(3, PlaylistArtBackfill.backfill(dao, Random(1)))

        assertContentEquals(listOf("oldest", "middle", "newest"), dao.writes.map { it.id })
        assertTrue(dao.writes.all { it.hue in PlaylistArtHues })
    }

    @Test
    fun `a batch spreads against itself, not all against the same starting set`() = runTest {
        // The regression this guards: reading the used hues once and distancing every row in
        // the batch against that same snapshot makes selectDistancedHue return the identical
        // answer every time, and a whole library ends up one colour. The seed hue matters —
        // distancing against a *non-empty* set is the deterministic branch, so without the
        // running accumulation every one of these twenty rows gets the identical answer.
        val dao = FakePlaylistDao(missing = (1..20).map { "playlist-$it" }, existingHues = listOf(0))

        PlaylistArtBackfill.backfill(dao, Random(1))

        val assigned = dao.writes.map { it.hue }
        assertEquals(20, assigned.size)
        assertEquals(20, assigned.toSet().size, "Backfilled hues repeated: $assigned")
    }

    @Test
    fun `hues already in the library are avoided, not handed out again`() = runTest {
        val existing = listOf(0, 4, 8, 12)
        val dao = FakePlaylistDao(missing = listOf("a", "b", "c"), existingHues = existing)

        PlaylistArtBackfill.backfill(dao, Random(1))

        val assigned = dao.writes.map { it.hue }
        assertEquals(1, dao.existingHueQueries, "The used-hue set must be read once, not per row")
        assertTrue(assigned.none { it in existing }, "Reused an in-use hue: $assigned")
        assertEquals(3, assigned.toSet().size, "Backfilled hues repeated each other: $assigned")
        assertTrue(
            assigned.all { hue -> existing.minOf { circularDistanceDegrees(hue, it) } > 20 },
            "Backfilled hues should sit clear of the crowded 0..12 block: $assigned",
        )
    }

    @Test
    fun `structure seeds are drawn per playlist rather than shared`() = runTest {
        val dao = FakePlaylistDao(missing = (1..20).map { "playlist-$it" })

        PlaylistArtBackfill.backfill(dao, Random(99))

        val seeds = dao.writes.map { it.seed }
        assertEquals(20, seeds.toSet().size, "Structure seeds repeated: $seeds")
    }

    // ---------------------------------------------------------------- overlapping passes (mutex half)

    /**
     * The corruption half of the overlapping-pass problem is covered at the DAO/SQL level by
     * PlaylistArtDaoInstrumentedTest's `setPlaylistArtIsANoOpOnceAlreadyAssigned` (needs real
     * SQLite for the `IS NULL` guard). This covers the OTHER half — that PlaylistArtBackfill's
     * own single-flight [kotlinx.coroutines.sync.Mutex] genuinely serializes two overlapping
     * calls rather than merely making their outcome harmless. Pure-Kotlin coroutines, no Room
     * needed, so this lives here as a fast JVM test rather than an instrumented one.
     */
    @Test
    fun `a second overlapping pass does not start its own work until the first releases the lock`() = runTest {
        val order = mutableListOf<String>()
        val releaseA = CompletableDeferred<Unit>()
        val daoA = FakePlaylistDao(
            missing = listOf("only"),
            onGetMissingArtIds = {
                order += "A:start"
                releaseA.await()
            },
        )
        val daoB = FakePlaylistDao(
            missing = emptyList(),
            onGetMissingArtIds = { order += "B:start" },
        )

        val passA = async { PlaylistArtBackfill.backfill(daoA, Random(1)) }
        yield()
        val passB = async { PlaylistArtBackfill.backfill(daoB, Random(2)) }
        yield()

        assertEquals(
            listOf("A:start"),
            order,
            "Pass B must not begin its own work while pass A still holds the lock",
        )

        releaseA.complete(Unit)
        assertEquals(1, passA.await())
        assertEquals(0, passB.await())
        assertEquals(listOf("A:start", "B:start"), order)
    }

    /** Shortest distance between two hues on the 360-degree wheel, in raw HSL degrees. */
    private fun circularDistanceDegrees(a: Int, b: Int): Int {
        val d = abs(normalizeArtHue(a) - normalizeArtHue(b))
        return minOf(d, 360 - d)
    }

    private data class ArtWrite(val id: String, val hue: Int, val seed: Long)

    /**
     * Only the three art members carry behaviour — everything else on [PlaylistDao] is outside
     * this routine's reach, and a call to one of them would be the bug worth failing on.
     */
    private class FakePlaylistDao(
        private val missing: List<String>,
        private val existingHues: List<Int> = emptyList(),
        private val onGetMissingArtIds: suspend () -> Unit = {},
    ) : PlaylistDao {
        val writes = mutableListOf<ArtWrite>()
        var existingHueQueries = 0
            private set

        override suspend fun getPlaylistIdsMissingArt(): List<String> {
            onGetMissingArtIds()
            return missing
        }

        override suspend fun getExistingArtHues(): List<Int> {
            existingHueQueries++
            return existingHues
        }

        override suspend fun setPlaylistArt(id: String, hue: Int, seed: Long) {
            writes += ArtWrite(id, hue, seed)
        }

        override fun getAllGroups(): Flow<List<PlaylistGroupEntity>> = unused()
        override suspend fun insertGroup(group: PlaylistGroupEntity) = unused()
        override suspend fun deleteGroup(groupId: String) = unused()
        override suspend fun renameGroup(id: String, name: String) = unused()
        override fun getAllPlaylists(): Flow<List<PlaylistEntity>> = unused()
        override fun getUngroupedPlaylists(): Flow<List<PlaylistEntity>> = unused()
        override fun getPlaylistsForGroup(groupId: String): Flow<List<PlaylistEntity>> = unused()
        override suspend fun insertPlaylist(playlist: PlaylistEntity) = unused()
        override suspend fun renamePlaylist(id: String, name: String, updatedAt: Long) = unused()
        override suspend fun movePlaylistToGroup(id: String, groupId: String?, updatedAt: Long) = unused()
        override suspend fun deletePlaylist(playlistId: String) = unused()
        override suspend fun getSongIdsForPlaylist(playlistId: String): List<String> = unused()
        override suspend fun getPlaylistIdsForSong(songId: String): List<String> = unused()
        override suspend fun getPlaylistMembershipsForSong(songId: String): List<PlaylistMembership> = unused()
        override suspend fun getPlaylistMemberCounts(
            ids: List<String>,
        ): List<com.v1per.synthx.data.db.PlaylistMemberCount> = unused()
        override suspend fun getPlaylistsByIds(ids: List<String>): List<PlaylistEntity> = unused()
        override suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity) = unused()
        override suspend fun removePlaylistSong(playlistId: String, songId: String) = unused()
        override suspend fun getMaxPosition(playlistId: String): Int? = unused()
        override suspend fun containsSong(playlistId: String, songId: String): Boolean = unused()

        private fun unused(): Nothing = error("PlaylistArtBackfill must only touch the playlist art queries")
    }
}
