package com.v1per.synthx.data.db

import android.content.Context
import android.database.Cursor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the 8→9 leg specifically: the schema shape MIGRATION_8_9 produces, and the fact that
 * [SynthXDatabase.createStandalone] — the builder the offline restore candidate is migrated
 * through — actually carries a v8 database all the way to v9.
 *
 * 8_9 is purely additive with no backfill, so nothing is asserted about `album_art_full_uri`
 * *preserving* data: no writer for it existed before this migration. What is asserted is that
 * the column arrives nullable and NULL-defaulting, and that every pre-existing column and row
 * crosses the migration untouched.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

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

    // ------------------------------------------------------------------ 8_9 schema

    @Test
    fun migration8To9AddsNullableAlbumArtFullUriColumn() {
        val name = databaseName("m89-column")
        migrationHelper.createDatabase(name, 8).use { seedV8Song(it) }

        // validateDroppedTables = true: Room compares the migrated result against 9.json, so a
        // dropped table or a mismatched column definition fails here before the asserts below.
        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            9,
            true,
            SynthXDatabase.MIGRATION_8_9,
        )

        migrated.use { database ->
            val column = database.columnInfo("songs")["album_art_full_uri"]
                ?: error("MIGRATION_8_9 did not add songs.album_art_full_uri")
            assertEquals("TEXT", column.type)
            assertEquals("album_art_full_uri must be nullable", 0, column.notNull)
            assertEquals("album_art_full_uri must default to NULL", "NULL", column.defaultValue)
        }
    }

    @Test
    fun migration8To9DropsNoExistingColumnAndLeavesRowsIntact() {
        val name = databaseName("m89-intact")
        migrationHelper.createDatabase(name, 8).use { seedV8Song(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            9,
            true,
            SynthXDatabase.MIGRATION_8_9,
        )

        migrated.use { database ->
            val columns = database.columnInfo("songs")
            val missing = V8_SONG_COLUMNS - columns.keys
            assertTrue("MIGRATION_8_9 dropped columns: ${missing.sorted()}", missing.isEmpty())

            database.query(
                "SELECT id, title, album_id, duration, file_path, album_art_uri, " +
                    "album_art_full_uri, year, track_number, date_added, is_missing, " +
                    "missing_since, in_inbox FROM songs",
            ).use { cursor ->
                assertTrue("The seeded song did not survive the migration", cursor.moveToFirst())
                assertEquals(SONG_ID, cursor.getString(0))
                assertEquals("Pre-migration title", cursor.getString(1))
                assertEquals(ALBUM_ID, cursor.getString(2))
                assertEquals(214_000L, cursor.getLong(3))
                assertEquals("/music/pre-migration.flac", cursor.getString(4))
                assertEquals("file:///art/compressed.jpg", cursor.getString(5))
                // Additive with no backfill: an already-scanned song keeps this NULL until a
                // fresh scan re-extracts its art.
                assertTrue("album_art_full_uri must be NULL after an additive migration", cursor.isNull(6))
                assertEquals(1998, cursor.getInt(7))
                assertEquals(7, cursor.getInt(8))
                assertEquals(1_700_000_000_000L, cursor.getLong(9))
                assertEquals(1, cursor.getInt(10))
                assertEquals(1_700_000_500_000L, cursor.getLong(11))
                assertEquals(0, cursor.getInt(12))
                assertEquals("Exactly one song row was expected", 1, cursor.count)
            }

            // The album row (and its FK target) is untouched too.
            database.query("SELECT title FROM albums WHERE id = ?", arrayOf(ALBUM_ID)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Pre-migration album", cursor.getString(0))
            }
        }
    }

    @Test
    fun rowsInsertedAfterMigrationDefaultAlbumArtFullUriToNull() {
        val name = databaseName("m89-default")
        migrationHelper.createDatabase(name, 8).use { seedV8Song(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            9,
            true,
            SynthXDatabase.MIGRATION_8_9,
        )

        migrated.use { database ->
            // Deliberately omits album_art_full_uri so the column default is what supplies it.
            database.execSQL(
                "INSERT INTO songs (id, title, album_id, duration, file_path, date_added) " +
                    "VALUES ('post-migration', 'Post', NULL, 1000, '/music/post.flac', 1)",
            )
            database.query(
                "SELECT album_art_full_uri FROM songs WHERE id = 'post-migration'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }
        }
    }

    // ------------------------------------------------- standalone v8 -> v9 candidate

    @Test
    fun standaloneBuilderMigratesV8CandidateToV9AndValidatesAsCurrent() {
        val name = databaseName("standalone-v8")
        migrationHelper.createDatabase(name, 8).use { seedV8Song(it) }

        // The exact path RestoreTransaction.migrateCandidate uses for an older source backup.
        val standalone = SynthXDatabase.createStandalone(context, name)
        try {
            standalone.openHelper.writableDatabase
        } finally {
            standalone.close()
        }

        val validated = BackupSnapshotValidator.validate(
            context.getDatabasePath(name),
            migrationSnapshot = false,
        )

        assertEquals(9, validated.schema.userVersion)
        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    // ------------------------------------------------------------------------ helpers

    private fun databaseName(label: String): String =
        "migration-8-9-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    /** A fully populated v8 song plus the album it points at, so the FK stays satisfiable. */
    private fun seedV8Song(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO albums (id, title, year, album_art_uri) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(ALBUM_ID, "Pre-migration album", 1998, "file:///art/album.jpg"),
        )
        database.execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, album_art_uri, year, " +
                "track_number, date_added, is_missing, missing_since, in_inbox) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                SONG_ID,
                "Pre-migration title",
                ALBUM_ID,
                214_000L,
                "/music/pre-migration.flac",
                "file:///art/compressed.jpg",
                1998,
                7,
                1_700_000_000_000L,
                1,
                1_700_000_500_000L,
                0,
            ),
        )
    }

    private data class ColumnInfo(val type: String, val notNull: Int, val defaultValue: String?)

    private fun SupportSQLiteDatabase.columnInfo(table: String): Map<String, ColumnInfo> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(nameIndex),
                        ColumnInfo(
                            type = cursor.getString(typeIndex),
                            notNull = cursor.getInt(notNullIndex),
                            defaultValue = cursor.stringOrNull(defaultIndex),
                        ),
                    )
                }
            }
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val SONG_ID = "song-pre-migration"
        const val ALBUM_ID = "album-pre-migration"

        /** Every column `songs` carries at v8 — none of them may disappear at v9. */
        val V8_SONG_COLUMNS = setOf(
            "id",
            "title",
            "album_id",
            "duration",
            "file_path",
            "album_art_uri",
            "year",
            "track_number",
            "date_added",
            "is_missing",
            "missing_since",
            "in_inbox",
        )
    }
}
