package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSnapshotInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<Pair<SynthXDatabase, String>>()
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        databases.forEach { (database, name) ->
            database.close()
            context.deleteDatabase(name)
        }
        temporaryFiles.forEach(::deleteDatabaseFileSet)
    }

    @Test
    fun committedPreSnapshotWriteIsCaptured() = runBlocking {
        val database = createDatabase(RoomDatabase.JournalMode.TRUNCATE)
        withContext(Dispatchers.IO) {
            database.playlistDao().insertGroup(group("marker", "committed"))
        }

        val snapshot = snapshot(database)

        assertEquals("committed", readName(snapshot, "playlist_groups", "marker"))
    }

    @Test
    fun walCommittedDataIsCapturedWithoutCheckpoint() = runBlocking {
        val database = createDatabase(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").use {
                assertTrue(it.moveToFirst())
            }
            database.playlistDao().insertGroup(group("wal-marker", "committed-in-wal"))
        }
        val source = context.getDatabasePath(databases.last().second)
        val wal = File("${source.path}-wal")
        assertTrue("The test must have uncheckpointed WAL state", wal.isFile && wal.length() > 0)

        val snapshot = snapshot(database)

        assertEquals("committed-in-wal", readName(snapshot, "playlist_groups", "wal-marker"))
        BackupSnapshotValidator.validate(snapshot, migrationSnapshot = false)
        Unit
    }

    @Test
    fun concurrentTransactionIsNeverTorn() = runBlocking {
        repeat(8) { iteration ->
            val database = createDatabase(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            val groupId = "group-$iteration"
            val playlistId = "playlist-$iteration"
            withContext(Dispatchers.IO) {
                database.playlistDao().insertGroup(group(groupId, "A"))
                database.playlistDao().insertPlaylist(playlist(playlistId, groupId, "A"))
            }

            val firstMutationCommitted = CompletableDeferred<Unit>()
            val permitCommit = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                database.withTransaction {
                    database.playlistDao().renameGroup(groupId, "B")
                    firstMutationCommitted.complete(Unit)
                    permitCommit.await()
                    database.playlistDao().renamePlaylist(playlistId, "B", iteration.toLong())
                }
            }
            firstMutationCommitted.await()

            val snapshotDeferred = async(Dispatchers.IO) { snapshot(database) }
            delay(25)
            permitCommit.complete(Unit)
            writer.await()
            val staged = snapshotDeferred.await()

            val groupState = readName(staged, "playlist_groups", groupId)
            val playlistState = readName(staged, "playlists", playlistId)
            assertTrue(
                "Snapshot was torn: $groupState/$playlistState",
                (groupState == "A" && playlistState == "A") ||
                    (groupState == "B" && playlistState == "B"),
            )
        }
    }

    @Test
    fun productionSchemaSnapshotPassesFullLocalValidation() = runBlocking {
        val database = createDatabase(RoomDatabase.JournalMode.TRUNCATE)
        val staged = snapshot(database)

        val validated = BackupSnapshotValidator.validate(staged, migrationSnapshot = false)

        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
        // Pinned literal, not sourced from validated.schema itself — this independently
        // confirms BackupSchemaContracts' current entry matches the real Room-computed
        // identity hash for a freshly built database, not just internal self-consistency.
        // Update this to the new version's exported app/schemas/.../<N>.json identityHash on
        // every SynthXDatabase.VERSION bump (see CLAUDE.md's migration checklist) — this
        // literal previously went stale (pinned to v9's hash while VERSION had moved to 11)
        // without being caught, which is exactly what this comment exists to prevent.
        assertEquals("3e0e2f38ca17807335cfa847b095f2fd", validated.schema.roomIdentityHash)
        assertTrue(
            validated.schema.expectedTables.containsAll(
                setOf(
                    "songs", "queue_items", "queue_baseline_items", "playback_cursor",
                    "pending_cleanups",
                ),
            ),
        )
    }

    @Test
    fun nonSQLiteStageIsRejected() {
        val staged = temporaryFile("not-a-database.db")
        staged.writeText("not sqlite")

        try {
            BackupSnapshotValidator.validate(staged, migrationSnapshot = false)
            fail("A non-SQLite staged file must be rejected")
        } catch (_: BackupSnapshotException) {
            // Expected.
        }
    }

    @Test
    fun migrationSnapshotPreservesActualVersionInValidationAndMetadata() {
        val source = temporaryFile("schema-7-source.db")
        createKnownSchemaFixture(source, version = 7)
        val staged = temporaryFile("schema-7-stage.db", mustNotExist = true)

        BackupSnapshotter.createForMigration(source, staged)
        BackupSnapshotter.forceToStorage(staged)
        val validated = BackupSnapshotValidator.validate(staged, migrationSnapshot = true)
        val identity = BackupFileNames.create(randomId = "0123abcd")
        val digest = BackupHashing.hash(staged)
        val metadata = BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Auto.name,
            reason = BackupReason.MIGRATION,
            dbVersion = validated.schema.userVersion,
            file = identity.databaseFileName,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
        val decoded = BackupMetadataCodec.decodeOrNull(BackupMetadataCodec.encode(metadata))

        assertEquals(7, validated.schema.userVersion)
        assertEquals(7, decoded?.dbVersion)
        assertEquals(7, readUserVersion(staged))
        assertEquals(7, readUserVersion(source))
    }

    @Test
    fun productionCoordinatorSerializesSnapshotsAndNamesRemainDistinct() = runBlocking {
        val database = createDatabase(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        suspend fun attempt(): String = BackupCreationCoordinator.serialize {
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            try {
                snapshot(database)
                delay(20)
                BackupFileNames.create().stem
            } finally {
                active.decrementAndGet()
            }
        }

        val first = async(Dispatchers.IO) { attempt() }
        val second = async(Dispatchers.IO) { attempt() }
        val identities = setOf(first.await(), second.await())

        assertEquals(1, maximum.get())
        assertEquals(2, identities.size)
    }

    private suspend fun snapshot(database: SynthXDatabase): File = withContext(Dispatchers.IO) {
        val staged = temporaryFile("snapshot-${UUID.randomUUID()}.partial.db", mustNotExist = true)
        BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, staged)
        BackupSnapshotter.forceToStorage(staged)
        staged
    }

    private fun createDatabase(journalMode: RoomDatabase.JournalMode): SynthXDatabase {
        val name = "backup-test-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, SynthXDatabase::class.java, name)
            .setJournalMode(journalMode)
            .build()
        database.openHelper.writableDatabase
        databases += database to name
        return database
    }

    private fun temporaryFile(name: String, mustNotExist: Boolean = false): File {
        val directory = File(context.noBackupFilesDir, "phase-a-instrumentation").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}-$name")
        if (!mustNotExist) file.createNewFile()
        temporaryFiles += file
        return file
    }

    private fun createKnownSchemaFixture(file: File, version: Int) {
        val contract = requireNotNull(BackupSchemaContracts.forVersion(version))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            contract.expectedTables.forEach { table ->
                database.execSQL("CREATE TABLE `$table` (id INTEGER PRIMARY KEY)")
            }
            database.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            database.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(contract.roomIdentityHash),
            )
            database.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun readName(file: File, table: String, id: String): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT name FROM `$table` WHERE id = ?", arrayOf(id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun readUserVersion(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun group(id: String, name: String) = PlaylistGroupEntity(
        id = id,
        name = name,
        position = 0,
        createdAt = 1,
    )

    private fun playlist(id: String, groupId: String, name: String) = PlaylistEntity(
        id = id,
        name = name,
        description = null,
        groupId = groupId,
        position = 0,
        createdAt = 1,
        updatedAt = 1,
        coverArtUri = null,
    )

    private fun deleteDatabaseFileSet(file: File) {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .forEach { it.delete() }
    }
}
