package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.BackupSnapshotValidator
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Additive raw-palette migration: no existing library, playlist or playback data changes. */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdDatabaseNames = mutableSetOf<String>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        createdDatabaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun migration12To13AddsNineNullableRealColumns() {
        val name = databaseName("columns")
        migrationHelper.createDatabase(name, 12).use(::seedV12Library)
        migrate(name).use { database ->
            val columns = database.columnInfo("songs")
            assertEquals(V12_SONG_COLUMNS.toSet() + SWATCH_COLUMNS, columns.keys)
            SWATCH_COLUMNS.forEach { name ->
                val column = columns.getValue(name)
                assertEquals("$name affinity", "REAL", column.type)
                assertEquals("$name must be nullable", 0, column.notNull)
                assertEquals("$name must default to NULL", "NULL", column.defaultValue)
            }
            assertNullSwatches(database, "song")
        }
    }

    @Test
    fun migration12To13PreservesEveryExistingColumnAndRelatedRow() {
        val name = databaseName("preservation")
        val original = migrationHelper.createDatabase(name, 12).use { database ->
            seedV12Library(database)
            TABLES.associateWith { table ->
                val columns = database.columnInfo(table).keys.toList()
                columns to database.readRows(table, columns)
            }
        }
        migrate(name).use { database ->
            original.forEach { (table, snapshot) ->
                val (columns, rows) = snapshot
                assertTrue("Dropped columns from $table", database.columnInfo(table).keys.containsAll(columns))
                assertEquals("Changed pre-existing data in $table", rows, database.readRows(table, columns))
            }
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals("Foreign keys must remain valid", 0, cursor.count)
            }
        }
    }

    @Test
    fun rowsInsertedAfterMigrationDefaultAllNineSwatchesToNull() {
        val name = databaseName("defaults")
        migrationHelper.createDatabase(name, 12).use(::seedV12Library)
        migrate(name).use { database ->
            database.execSQL(
                "INSERT INTO songs (id, title, duration, file_path, date_added) " +
                    "VALUES ('new', 'New song', 1000, '/music/new.flac', 2)",
            )
            assertNullSwatches(database, "new")
        }
    }

    @Test
    fun standaloneBuilderMigratesV12CandidateAndValidatesAsCurrent() {
        val name = databaseName("standalone")
        migrationHelper.createDatabase(name, 12).use(::seedV12Library)
        val standalone = SynthXDatabase.createStandalone(context, name)
        try {
            standalone.openHelper.writableDatabase
        } finally {
            standalone.close()
        }
        val validated = BackupSnapshotValidator.validate(context.getDatabasePath(name), migrationSnapshot = false)
        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    private fun migrate(name: String): SupportSQLiteDatabase = migrationHelper.runMigrationsAndValidate(
        name,
        13,
        true,
        SynthXDatabase.MIGRATION_12_13,
    )

    private fun assertNullSwatches(database: SupportSQLiteDatabase, songId: String) {
        database.query(
            "SELECT ${SWATCH_COLUMNS.joinToString()} FROM songs WHERE id = ?",
            arrayOf(songId),
        ).use { cursor ->
            assertTrue("Missing seeded song $songId", cursor.moveToFirst())
            SWATCH_COLUMNS.forEachIndexed { index, name ->
                assertTrue("$name must be NULL for $songId", cursor.isNull(index))
            }
            assertEquals(1, cursor.count)
        }
    }

    private fun seedV12Library(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO albums VALUES ('album', 'Album', 1998, 'file:///art/album.jpg')")
        database.execSQL("INSERT INTO artists VALUES ('artist', 'Artist', 'artist')")
        database.execSQL(
            "INSERT INTO songs (${V12_SONG_COLUMNS.joinToString()}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "song", "Pre-migration title", "album", 214_000L, "/music/original.flac",
                "file:///art/compressed.jpg", "file:///art/full.png", 1998, 7,
                1_700_000_000_000L, 1, 1_700_000_500_000L, 0,
            ),
        )
        database.execSQL("INSERT INTO song_artists VALUES ('song', 'artist', 0)")
        database.execSQL("INSERT INTO playlist_groups VALUES ('group', 'Group', 2, 100)")
        database.execSQL("INSERT INTO playlists VALUES ('playlist', 'Playlist', 'Notes', 'group', 3, 100, 200, 'cover')")
        database.execSQL("INSERT INTO playlist_songs VALUES ('playlist', 'song', 4, 300)")
        database.execSQL("INSERT INTO queue_items VALUES ('slot-a', 0, 'song')")
        database.execSQL("INSERT INTO queue_items VALUES ('slot-b', 1, 'song')")
        database.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-b', 0, 'song')")
        database.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-a', 1, 'song')")
        database.execSQL("INSERT INTO playback_cursor VALUES (1, 'slot-b', 4321, 1, 2)")
    }

    private fun databaseName(label: String): String =
        "migration-12-13-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    private data class ColumnInfo(val type: String, val notNull: Int, val defaultValue: String?)

    private fun SupportSQLiteDatabase.columnInfo(table: String): Map<String, ColumnInfo> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        ColumnInfo(
                            cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                            cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.readRows(table: String, columns: List<String>): List<List<String?>> =
        query("SELECT ${columns.joinToString()} FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private companion object {
        val V12_SONG_COLUMNS = listOf(
            "id", "title", "album_id", "duration", "file_path", "album_art_uri", "album_art_full_uri",
            "year", "track_number", "date_added", "is_missing", "missing_since", "in_inbox",
        )
        val SWATCH_COLUMNS = listOf(
            "swatch1_hue", "swatch1_saturation", "swatch1_lightness",
            "swatch2_hue", "swatch2_saturation", "swatch2_lightness",
            "swatch3_hue", "swatch3_saturation", "swatch3_lightness",
        )
        val TABLES = listOf(
            "songs", "artists", "albums", "song_artists", "playlist_groups", "playlists",
            "playlist_songs", "queue_items", "queue_baseline_items", "playback_cursor",
        )
    }
}
