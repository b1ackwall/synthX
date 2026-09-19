package com.v1per.synthx.data.playlistseed

import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaylistSeedBootstrapperTest {

    @org.junit.After
    fun resetRestoreCoordinator() = RestoreCoordinator.resetForTest()

    @Test
    fun emptyDatabaseWithAllSongsCreatesCanonicalPlaylistsAndMemberships() = runBlocking {
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())

        val result = bootstrapper(database).seed()

        val complete = assertIs<PlaylistSeedBootstrapResult.Complete>(result)
        assertEquals(68, complete.playlistsCreated)
        assertEquals(1_934, complete.membershipsRestored)
        assertEquals(1_934, complete.totalMemberships)
        assertEquals(0, complete.uniqueMissingSongs)
        assertEquals(68, database.playlists.size)
        assertEquals(1_934, database.memberships.size)
        assertEquals(
            approvedManifest.playlists.map { it.playlistId },
            database.playlists.keys.toList(),
            "Fixed manifest playlist UUIDs must be preserved",
        )

        approvedManifest.playlists.forEach { playlist ->
            val restored = database.memberships
                .filter { it.playlistId == playlist.playlistId }
                .sortedBy { it.position }
            assertEquals(playlist.songHashes, restored.map { it.songId })
            assertEquals(restored.indices.toList(), restored.map { it.position })
        }
    }

    @Test
    fun missingSongsAreSkippedCountedUniquelyAndRelativeOrderIsPreserved() = runBlocking {
        val missingSongHashes = approvedSongHashes.distinct().take(12).toSet()
        val database = FakePlaylistSeedDatabase(
            availableSongIds = approvedSongHashes.toSet() - missingSongHashes,
        )
        val expectedRestoredMemberships = approvedSongHashes.count { it !in missingSongHashes }

        val result = bootstrapper(database).seed()

        val complete = assertIs<PlaylistSeedBootstrapResult.Complete>(result)
        assertEquals(68, complete.playlistsCreated)
        assertEquals(expectedRestoredMemberships, complete.membershipsRestored)
        assertEquals(1_934, complete.totalMemberships)
        assertEquals(12, complete.uniqueMissingSongs)
        approvedManifest.playlists.forEach { playlist ->
            val restored = database.memberships
                .filter { it.playlistId == playlist.playlistId }
                .sortedBy { it.position }
            assertEquals(
                playlist.songHashes.filterNot { it in missingSongHashes },
                restored.map { it.songId },
            )
            assertEquals(restored.indices.toList(), restored.map { it.position })
        }
    }

    @Test
    fun playlistWithNoMatchedSongsIsStillCreatedEmpty() = runBlocking {
        val missingA = "a".repeat(64)
        val missingB = "b".repeat(64)
        val available = "c".repeat(64)
        val emptyPlaylistId = "11111111-1111-4111-8111-111111111111"
        val manifest = PlaylistSeedManifest(
            schemaVersion = PlaylistSeedManifestV1.SCHEMA_VERSION,
            hashStrategy = PlaylistSeedManifestV1.HASH_STRATEGY,
            playlists = listOf(
                PlaylistSeedPlaylist(
                    playlistId = emptyPlaylistId,
                    name = "No Matches",
                    songHashes = listOf(missingA, missingB),
                ),
                PlaylistSeedPlaylist(
                    playlistId = "22222222-2222-4222-8222-222222222222",
                    name = "One Match",
                    songHashes = listOf(available),
                ),
            ),
        )
        val database = FakePlaylistSeedDatabase(setOf(available))

        val result = bootstrapper(database, manifestBytes(manifest)).seed()

        val complete = assertIs<PlaylistSeedBootstrapResult.Complete>(result)
        assertEquals(2, complete.playlistsCreated)
        assertEquals(1, complete.membershipsRestored)
        assertTrue(emptyPlaylistId in database.playlists)
        assertTrue(database.memberships.none { it.playlistId == emptyPlaylistId })
    }

    @Test
    fun existingPlaylistRefusesWithoutChangingExistingState() = runBlocking {
        val existingPlaylist = playlistEntity(
            id = "33333333-3333-4333-8333-333333333333",
            name = "Existing",
        )
        val existingMembership = PlaylistSongEntity(
            playlistId = existingPlaylist.id,
            songId = "d".repeat(64),
            position = 0,
            addedAt = 42L,
        )
        val database = FakePlaylistSeedDatabase(
            availableSongIds = approvedSongHashes.toSet(),
            initialPlaylists = listOf(existingPlaylist),
            initialMemberships = listOf(existingMembership),
        )
        val playlistsBefore = database.playlists.toMap()
        val membershipsBefore = database.memberships.toList()

        val result = bootstrapper(database).seed()

        assertEquals(PlaylistSeedBootstrapResult.PlaylistsAlreadyExist, result)
        assertEquals(playlistsBefore, database.playlists)
        assertEquals(membershipsBefore, database.memberships)
        assertEquals(0, database.playlistInsertAttempts)
        assertEquals(0, database.membershipInsertAttempts)
    }

    @Test
    fun invalidManifestPerformsNoDatabaseWrites() = runBlocking {
        val firstPlaylist = approvedManifest.playlists.first()
        val invalidManifests = listOf(
            approvedManifest.copy(schemaVersion = PlaylistSeedManifestV1.SCHEMA_VERSION + 1),
            approvedManifest.copy(hashStrategy = "unsupported"),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.replaceFirst(
                    firstPlaylist.copy(playlistId = " "),
                ),
            ),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.replaceFirst(
                    firstPlaylist.copy(playlistId = "not-a-uuid"),
                ),
            ),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.replaceFirst(
                    firstPlaylist.copy(name = " "),
                ),
            ),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.replaceFirst(
                    firstPlaylist.copy(songHashes = listOf("INVALID")),
                ),
            ),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.replaceFirst(
                    firstPlaylist.copy(
                        songHashes = firstPlaylist.songHashes + firstPlaylist.songHashes.first(),
                    ),
                ),
            ),
            approvedManifest.copy(
                playlists = approvedManifest.playlists.toMutableList().apply {
                    this[1] = this[1].copy(playlistId = firstPlaylist.playlistId)
                },
            ),
        )

        invalidManifests.forEach { invalidManifest ->
            val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())

            val result = bootstrapper(database, manifestBytes(invalidManifest)).seed()

            assertEquals(PlaylistSeedBootstrapResult.InvalidManifest, result)
            assertEquals(0, database.transactionsStarted)
            assertTrue(database.playlists.isEmpty())
            assertTrue(database.memberships.isEmpty())
        }
    }

    @Test
    fun databaseFailureRollsBackEveryPlaylistAndMembership() = runBlocking {
        val database = FakePlaylistSeedDatabase(
            availableSongIds = approvedSongHashes.toSet(),
            failAfterMembershipInsert = 10,
        )

        val result = bootstrapper(database).seed()

        assertEquals(PlaylistSeedBootstrapResult.DatabaseFailure, result)
        assertEquals(1, database.transactionsRolledBack)
        assertTrue(database.playlists.isEmpty())
        assertTrue(database.memberships.isEmpty())
    }

    @Test
    fun secondInvocationAfterSuccessRefusesWithoutChangingSeededState() = runBlocking {
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())
        val seed = bootstrapper(database)
        assertIs<PlaylistSeedBootstrapResult.Complete>(seed.seed())
        val playlistsAfterFirstSeed = database.playlists.toMap()
        val membershipsAfterFirstSeed = database.memberships.toList()

        val secondResult = seed.seed()

        assertEquals(PlaylistSeedBootstrapResult.PlaylistsAlreadyExist, secondResult)
        assertEquals(playlistsAfterFirstSeed, database.playlists)
        assertEquals(membershipsAfterFirstSeed, database.memberships)
    }

    @Test
    fun admittedSeedDrainsBeforeRestoreProceeds() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val database = FakePlaylistSeedDatabase(
            availableSongIds = approvedSongHashes.toSet(),
            transactionEntered = entered,
            finishTransaction = finish,
        )
        val seed = async { bootstrapper(database).seed() }
        entered.await()
        val drained = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                drained.complete(Unit)
            }
        }
        yield()
        assertTrue(!drained.isCompleted)
        finish.complete(Unit)
        assertIs<PlaylistSeedBootstrapResult.Complete>(seed.await())
        restore.await()
        assertTrue(drained.isCompleted)
    }

    @Test
    fun preparingRejectsSeedBeforeDatabaseTransaction() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive { entered.complete(Unit); hold.await() }
        }
        entered.await()
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())
        assertEquals(PlaylistSeedBootstrapResult.DatabaseFailure, bootstrapper(database).seed())
        assertEquals(0, database.transactionsStarted)
        hold.complete(Unit)
        restore.await()
    }

    @Test
    fun closedRejectsSeedBeforeDatabaseTransaction() = runBlocking {
        RestoreCoordinator.markClosed()
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())
        assertEquals(PlaylistSeedBootstrapResult.DatabaseFailure, bootstrapper(database).seed())
        assertEquals(0, database.transactionsStarted)
    }

    // ---------------------------------------------------------------- P8-T1/R6-C3

    @Test
    fun successfulSeedTriggersBackfillArtExactlyOnce() = runBlocking {
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())
        var backfillCalls = 0
        val seed = bootstrapper(database, backfillArt = { backfillCalls++; 68 })

        assertIs<PlaylistSeedBootstrapResult.Complete>(seed.seed())

        assertEquals(1, backfillCalls, "backfillArt must run exactly once after a successful seed")
    }

    @Test
    fun refusedSeedNeverTriggersBackfillArt() = runBlocking {
        val existingPlaylist = playlistEntity(
            id = "33333333-3333-4333-8333-333333333333",
            name = "Existing",
        )
        val database = FakePlaylistSeedDatabase(
            availableSongIds = approvedSongHashes.toSet(),
            initialPlaylists = listOf(existingPlaylist),
        )
        var backfillCalls = 0
        val seed = bootstrapper(database, backfillArt = { backfillCalls++; 0 })

        assertEquals(PlaylistSeedBootstrapResult.PlaylistsAlreadyExist, seed.seed())

        assertEquals(0, backfillCalls, "A refused seed inserted nothing, so there is nothing to backfill")
    }

    @Test
    fun backfillArtFailureDoesNotTurnASuccessfulSeedIntoAReportedFailure() = runBlocking {
        val database = FakePlaylistSeedDatabase(approvedSongHashes.toSet())
        val seed = bootstrapper(database, backfillArt = { error("simulated backfill failure") })

        val result = seed.seed()

        assertIs<PlaylistSeedBootstrapResult.Complete>(
            result,
            "A cosmetic backfillArt failure must not mask an otherwise-successful seed",
        )
        assertEquals(68, database.playlists.size)
    }

    private fun bootstrapper(
        database: PlaylistSeedDatabase,
        bytes: ByteArray = approvedManifestBytes,
        backfillArt: suspend () -> Int = { 0 },
    ) = PlaylistSeedBootstrapper(
        readManifestBytes = { bytes },
        database = database,
        currentTimeMillis = { 123_456L },
        backfillArt = backfillArt,
    )

    private fun manifestBytes(manifest: PlaylistSeedManifest): ByteArray =
        Json.encodeToString(PlaylistSeedManifest.serializer(), manifest).encodeToByteArray()

    private fun List<PlaylistSeedPlaylist>.replaceFirst(
        replacement: PlaylistSeedPlaylist,
    ): List<PlaylistSeedPlaylist> = toMutableList().apply { this[0] = replacement }

    private companion object {
        val approvedManifestBytes: ByteArray by lazy {
            Files.readAllBytes(resolveAssetPath())
        }
        val approvedManifest: PlaylistSeedManifest by lazy {
            Json.decodeFromString(
                PlaylistSeedManifest.serializer(),
                approvedManifestBytes.decodeToString(),
            )
        }
        val approvedSongHashes: List<String> by lazy {
            approvedManifest.playlists.flatMap { it.songHashes }
        }

        fun resolveAssetPath(): Path {
            val moduleRelativePath = Path.of(
                "src",
                "main",
                "assets",
                "playlist_seed_manifest_v1.json",
            )
            return listOf(moduleRelativePath, Path.of("app").resolve(moduleRelativePath))
                .firstOrNull { Files.isRegularFile(it) }
                ?: error("Canonical playlist seed asset not found")
        }

        fun playlistEntity(id: String, name: String) = PlaylistEntity(
            id = id,
            name = name,
            description = null,
            groupId = null,
            position = 0,
            createdAt = 42L,
            updatedAt = 42L,
            coverArtUri = null,
        )
    }
}

private class FakePlaylistSeedDatabase(
    private val availableSongIds: Set<String>,
    initialPlaylists: List<PlaylistEntity> = emptyList(),
    initialMemberships: List<PlaylistSongEntity> = emptyList(),
    private val failAfterMembershipInsert: Int? = null,
    private val transactionEntered: CompletableDeferred<Unit>? = null,
    private val finishTransaction: CompletableDeferred<Unit>? = null,
) : PlaylistSeedDatabase {
    val playlists = linkedMapOf<String, PlaylistEntity>().apply {
        initialPlaylists.forEach { put(it.id, it) }
    }
    val memberships = initialMemberships.toMutableList()

    var transactionsStarted = 0
        private set
    var transactionsRolledBack = 0
        private set
    var playlistInsertAttempts = 0
        private set
    var membershipInsertAttempts = 0
        private set

    override suspend fun <T> inTransaction(block: suspend PlaylistSeedDatabase.() -> T): T {
        transactionsStarted++
        transactionEntered?.complete(Unit)
        finishTransaction?.await()
        val playlistSnapshot = playlists.toMap()
        val membershipSnapshot = memberships.toList()
        return try {
            block(this)
        } catch (error: Exception) {
            playlists.clear()
            playlists.putAll(playlistSnapshot)
            memberships.clear()
            memberships.addAll(membershipSnapshot)
            transactionsRolledBack++
            throw error
        }
    }

    override suspend fun getPlaylistCount(): Int = playlists.size

    override suspend fun getAllSongIds(): List<String> = availableSongIds.toList()

    override suspend fun insertPlaylist(playlist: PlaylistEntity) {
        playlistInsertAttempts++
        check(playlists.putIfAbsent(playlist.id, playlist) == null) {
            "Playlist already exists: ${playlist.id}"
        }
    }

    override suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity) {
        membershipInsertAttempts++
        memberships += playlistSong
        if (membershipInsertAttempts == failAfterMembershipInsert) {
            error("Simulated playlist membership insert failure")
        }
    }
}
