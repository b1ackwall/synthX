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
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the 10->11 leg specifically: MIGRATION_10_11 adds `shuffle_enabled`/`repeat_mode` to the
 * single-row `playback_cursor` table (see PlaybackCursorEntity's kdoc) — both previously
 * lived in SharedPreferences, which a restore never touches, so a restored queue could end up
 * paired with a shuffle/repeat mode left over from the pre-restore install.
 *
 * Additive ADD COLUMN, same shape as 7_8/8_9/9_10 — but unlike 9_10's brand-new table, here a
 * *pre-existing* `playback_cursor` row (as a real v10 install would already have, from actually
 * having played something) must survive with the exact defaults PlaybackCursorEntity declares.
 * This is a direct structural template of Migration8To9Test.kt/Migration9To10Test.kt.
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

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

    // ----------------------------------------------------------------- 10_11 schema

    @Test
    fun migration10To11AddsShuffleAndRepeatColumnsWithCorrectDefaults() {
        val name = databaseName("m1011-column")
        migrationHelper.createDatabase(name, 10).use { seedV10PlaybackCursor(it) }

        // validateDroppedTables = true: Room compares the migrated result against 11.json, so a
        // dropped table or a mismatched column definition fails here before the asserts below.
        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            11,
            true,
            SynthXDatabase.MIGRATION_10_11,
        )

        migrated.use { database ->
            val columns = database.columnInfo("playback_cursor")
            val shuffle = columns["shuffle_enabled"] ?: error("MIGRATION_10_11 did not add shuffle_enabled")
            assertEquals("INTEGER", shuffle.type)
            assertEquals("shuffle_enabled must be NOT NULL", 1, shuffle.notNull)
            assertEquals("shuffle_enabled must default to 0", "0", shuffle.defaultValue)

            val repeat = columns["repeat_mode"] ?: error("MIGRATION_10_11 did not add repeat_mode")
            assertEquals("INTEGER", repeat.type)
            assertEquals("repeat_mode must be NOT NULL", 1, repeat.notNull)
            assertEquals("repeat_mode must default to 0", "0", repeat.defaultValue)
        }
    }

    @Test
    fun migration10To11BackfillsExistingCursorRowWithDefaultsAndKeepsItsOwnColumns() {
        val name = databaseName("m1011-backfill")
        migrationHelper.createDatabase(name, 10).use { seedV10PlaybackCursor(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            11,
            true,
            SynthXDatabase.MIGRATION_10_11,
        )

        migrated.use { database ->
            database.query(
                "SELECT id, queue_index, position_ms, shuffle_enabled, repeat_mode FROM playback_cursor",
            ).use { cursor ->
                assertTrue("The seeded cursor row did not survive the migration", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(QUEUE_INDEX, cursor.getInt(1))
                assertEquals(POSITION_MS, cursor.getLong(2))
                // ALTER TABLE ADD COLUMN backfills existing rows with the column default — a
                // pre-existing v10 row had no opinion on shuffle/repeat, so it must land on the
                // same "off" defaults a brand-new row would get.
                assertEquals("A pre-existing row must backfill shuffle_enabled to 0 (off)", 0, cursor.getInt(3))
                assertEquals("A pre-existing row must backfill repeat_mode to 0 (off)", 0, cursor.getInt(4))
                assertEquals("The single-row table must still have exactly one row", 1, cursor.count)
            }
        }
    }

    @Test
    fun migration10To11DropsNoExistingColumnOnUnrelatedTables() {
        val name = databaseName("m1011-intact")
        migrationHelper.createDatabase(name, 10).use { seedV10PlaybackCursor(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            11,
            true,
            SynthXDatabase.MIGRATION_10_11,
        )

        migrated.use { database ->
            val songColumns = database.columnInfo("songs")
            assertEquals(
                "MIGRATION_10_11 must not touch the songs table at all",
                V10_SONG_COLUMNS,
                songColumns.keys,
            )
        }
    }

    @Test
    fun rowsInsertedAfterMigrationDefaultShuffleAndRepeatToOff() {
        val name = databaseName("m1011-new-row-default")
        migrationHelper.createDatabase(name, 10).use { seedV10PlaybackCursor(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            11,
            true,
            SynthXDatabase.MIGRATION_10_11,
        )

        migrated.use { database ->
            // Deliberately omits shuffle_enabled/repeat_mode so the column defaults supply them —
            // upsert semantics mean this replaces the single seeded row (id is the primary key).
            database.execSQL(
                "INSERT OR REPLACE INTO playback_cursor (id, queue_index, position_ms) " +
                    "VALUES (0, 3, 9000)",
            )
            database.query(
                "SELECT shuffle_enabled, repeat_mode FROM playback_cursor WHERE id = 0",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    // ------------------------------------------------ standalone v10 -> current candidate

    @Test
    fun standaloneBuilderMigratesV10CandidateToCurrent() {
        val name = databaseName("standalone-v10")
        migrationHelper.createDatabase(name, 10).use { seedV10PlaybackCursor(it) }

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
        "migration-10-11-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    /** A v10 database with an already-populated single-row playback_cursor, as a real install would have. */
    private fun seedV10PlaybackCursor(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO playback_cursor (id, queue_index, position_ms) VALUES (0, ?, ?)",
            arrayOf<Any?>(QUEUE_INDEX, POSITION_MS),
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
        const val QUEUE_INDEX = 4
        const val POSITION_MS = 123_456L

        /** Every column `songs` carries at v10 — MIGRATION_10_11 must not touch this table. */
        val V10_SONG_COLUMNS = setOf(
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
