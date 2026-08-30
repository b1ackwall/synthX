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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the 9->10 leg specifically: MIGRATION_9_10 creates the single-row `playback_cursor`
 * table (see PlaybackCursorEntity's kdoc, data 2.4) so the resume cursor can be committed in the
 * same transaction as `queue_items` instead of living independently in SharedPreferences.
 *
 * Purely additive with a brand-new table — there is nothing to migrate data *into* it from, so
 * (unlike 7_8) there is no backfill to assert. What is asserted is the new table's exact shape,
 * that it starts empty, and that every pre-existing table/row from a real v9 database crosses the
 * migration untouched. This is a direct structural template of Migration8To9Test.kt.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

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

    // ----------------------------------------------------------------- 9_10 schema

    @Test
    fun migration9To10CreatesEmptyPlaybackCursorTableWithExpectedColumns() {
        val name = databaseName("m910-column")
        migrationHelper.createDatabase(name, 9).use { seedV9Song(it) }

        // validateDroppedTables = true: Room compares the migrated result against 10.json, so a
        // dropped table or a mismatched column definition fails here before the asserts below.
        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            10,
            true,
            SynthXDatabase.MIGRATION_9_10,
        )

        migrated.use { database ->
            val columns = database.columnInfo("playback_cursor")
            assertEquals(
                "playback_cursor must have exactly id/queue_index/position_ms at v10",
                setOf("id", "queue_index", "position_ms"),
                columns.keys,
            )
            assertEquals("INTEGER", columns.getValue("id").type)
            assertEquals("playback_cursor.id must be NOT NULL (it is the primary key)", 1, columns.getValue("id").notNull)
            assertEquals("INTEGER", columns.getValue("queue_index").type)
            assertEquals("playback_cursor.queue_index must be NOT NULL", 1, columns.getValue("queue_index").notNull)
            assertEquals("INTEGER", columns.getValue("position_ms").type)
            assertEquals("playback_cursor.position_ms must be NOT NULL", 1, columns.getValue("position_ms").notNull)

            database.query("SELECT COUNT(*) FROM playback_cursor").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A brand-new table must start empty", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration9To10DropsNoExistingColumnAndLeavesRowsIntact() {
        val name = databaseName("m910-intact")
        migrationHelper.createDatabase(name, 9).use { seedV9Song(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            10,
            true,
            SynthXDatabase.MIGRATION_9_10,
        )

        migrated.use { database ->
            val columns = database.columnInfo("songs")
            val missing = V9_SONG_COLUMNS - columns.keys
            assertTrue("MIGRATION_9_10 dropped columns: ${missing.sorted()}", missing.isEmpty())

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
                assertEquals("file:///art/full.jpg", cursor.getString(6))
                assertEquals(1998, cursor.getInt(7))
                assertEquals(7, cursor.getInt(8))
                assertEquals(1_700_000_000_000L, cursor.getLong(9))
                assertEquals(1, cursor.getInt(10))
                assertEquals(1_700_000_500_000L, cursor.getLong(11))
                assertEquals(0, cursor.getInt(12))
                assertEquals("Exactly one song row was expected", 1, cursor.count)
            }

            database.query("SELECT title FROM albums WHERE id = ?", arrayOf(ALBUM_ID)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Pre-migration album", cursor.getString(0))
            }
        }
    }

    @Test
    fun rowsInsertedAfterMigrationDefaultShuffleAndRepeatColumnsDoNotYetExist() {
        // Guards the *ordering* of the two Phase 5 migrations: shuffle_enabled/repeat_mode must
        // not exist until 10_11 runs. If this ever starts passing for the wrong reason (columns
        // already present at v10), Migration10To11Test's own column-added assertions would also
        // start failing — this test exists so a v9->v10-only regression fails here first.
        val name = databaseName("m910-no-shuffle-yet")
        migrationHelper.createDatabase(name, 9).use { seedV9Song(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            10,
            true,
            SynthXDatabase.MIGRATION_9_10,
        )

        migrated.use { database ->
            val columns = database.columnInfo("playback_cursor")
            assertTrue("shuffle_enabled must not exist before 10_11", "shuffle_enabled" !in columns)
            assertTrue("repeat_mode must not exist before 10_11", "repeat_mode" !in columns)
        }
    }

    // ------------------------------------------------- standalone v9 -> v10 candidate

    @Test
    fun standaloneBuilderMigratesV9CandidateToV10() {
        val name = databaseName("standalone-v9")
        migrationHelper.createDatabase(name, 9).use { seedV9Song(it) }

        // The exact path RestoreTransaction.migrateCandidate uses for an older source backup —
        // exercised here up to v10 en route, since createStandalone always migrates all the way
        // to SynthXDatabase.VERSION via the shared ALL_MIGRATIONS chain.
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

        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    // ------------------------------------------------------------------------ helpers

    private fun databaseName(label: String): String =
        "migration-9-10-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    /** A fully populated v9 song plus the album it points at, so the FK stays satisfiable. */
    private fun seedV9Song(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO albums (id, title, year, album_art_uri) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(ALBUM_ID, "Pre-migration album", 1998, "file:///art/album.jpg"),
        )
        database.execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, album_art_uri, " +
                "album_art_full_uri, year, track_number, date_added, is_missing, missing_since, " +
                "in_inbox) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                SONG_ID,
                "Pre-migration title",
                ALBUM_ID,
                214_000L,
                "/music/pre-migration.flac",
                "file:///art/compressed.jpg",
                "file:///art/full.jpg",
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

        /** Every column `songs` carries at v9 — none of them may disappear at v10. */
        val V9_SONG_COLUMNS = setOf(
            "id",
            "title",
            "album_id",
            "duration",
            "file_path",
            "album_art_uri",
            "album_art_full_uri",
            "year",
            "track_number",
            "date_added",
            "is_missing",
            "missing_since",
            "in_inbox",
        )
    }
}
