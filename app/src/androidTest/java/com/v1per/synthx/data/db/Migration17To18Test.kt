package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.BackupSnapshotValidator
import com.v1per.synthx.data.db.MigrationTestSupport.columnNames
import com.v1per.synthx.data.db.MigrationTestSupport.readRows
import com.v1per.synthx.data.db.MigrationTestSupport.singleInt
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 17->18 adds the customised-queue flag and the current slot's queue position to
 * `playback_cursor`. Purely additive: every v17 row survives unchanged, including the shuffle extra
 * tags and the reorder flag; the saved queue reads as untouched with no saved position.
 */
@RunWith(AndroidJUnit4::class)
class Migration17To18Test {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableSetOf<String>()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun tearDown() = names.forEach(context::deleteDatabase)

    private fun databaseName(label: String) = "migration-17-18-$label-${UUID.randomUUID()}.db".also(names::add)

    @Test
    fun migrationAddsTheFlagAndThePositionAndKeepsEverySavedRow() {
        val name = databaseName("rows")
        val before = helper.createDatabase(name, 17).use { database ->
            seedV17Queue(database)
            QUEUE_TABLES.associateWith { database.readRows(it, orderBy = ORDER_BY.getValue(it)) }
        }

        helper.runMigrationsAndValidate(name, 18, true, SynthXDatabase.MIGRATION_17_18).use { database ->
            assertEquals(
                listOf(
                    "id",
                    "current_slot_id",
                    "position_ms",
                    "shuffle_enabled",
                    "repeat_mode",
                    "shuffle_reordered",
                    "queue_customized",
                    "current_queue_position",
                ),
                database.columnNames("playback_cursor"),
            )
            assertEquals(
                listOf("slot_id", "position", "song_id", "extra_kind", "extra_sequence"),
                database.columnNames("queue_items"),
            )
            assertEquals(before.getValue("queue_items"), database.readRows("queue_items", orderBy = "position"))
            assertEquals(
                before.getValue("queue_baseline_items"),
                database.readRows("queue_baseline_items", orderBy = "position"),
            )
            assertEquals(
                before.getValue("playback_cursor"),
                database.readRows("playback_cursor", V17_CURSOR_COLUMNS, orderBy = "id"),
            )
            assertEquals("the reorder flag is kept", 1, database.singleInt(cursorValue("shuffle_reordered")))
            assertEquals("a saved queue reads as untouched", 0, database.singleInt(cursorValue("queue_customized")))
            assertEquals(
                "no saved queue position",
                1,
                database.singleInt("SELECT COUNT(*) FROM playback_cursor WHERE current_queue_position IS NULL"),
            )
            assertEquals(2, database.singleInt("SELECT COUNT(*) FROM songs"))
        }
    }

    @Test
    fun migratedCursorAcceptsTheFlagAndThePosition() {
        val name = databaseName("write")
        helper.createDatabase(name, 17).use(::seedV17Queue)

        helper.runMigrationsAndValidate(name, 18, true, SynthXDatabase.MIGRATION_17_18).use { database ->
            database.execSQL(
                "UPDATE playback_cursor SET queue_customized = 1, current_queue_position = 1 WHERE id = 0",
            )

            assertEquals(1, database.singleInt(cursorValue("queue_customized")))
            assertEquals(1, database.singleInt(cursorValue("current_queue_position")))
        }
    }

    @Test
    fun aDatabaseWithoutACursorRowMigratesToo() {
        val name = databaseName("no-cursor")
        helper.createDatabase(name, 17).use { database ->
            database.execSQL(
                "INSERT INTO songs (id, title, duration, file_path, date_added) VALUES ('s', 'S', 1, '/s', 1)",
            )
        }

        helper.runMigrationsAndValidate(name, 18, true, SynthXDatabase.MIGRATION_17_18).use { database ->
            assertEquals(0, database.singleInt("SELECT COUNT(*) FROM playback_cursor"))
            assertTrue("queue_customized" in database.columnNames("playback_cursor"))
        }
    }

    @Test
    fun standaloneBuilderMigratesAV17CandidateToCurrent() {
        val name = databaseName("standalone")
        helper.createDatabase(name, 17).use(::seedV17Queue)

        val standalone = SynthXDatabase.createStandalone(context, name)
        try {
            standalone.openHelper.writableDatabase
        } finally {
            standalone.close()
        }

        val validated = BackupSnapshotValidator.validate(context.getDatabasePath(name), migrationSnapshot = false)
        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    private fun cursorValue(column: String) = "SELECT $column FROM playback_cursor WHERE id = 0"

    /** A shuffled, reordered v17 queue: three live slots (two tagged extras), two in baseline. */
    private fun seedV17Queue(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO songs (id, title, duration, file_path, date_added) VALUES (?, 'A', 1000, '/a.flac', 1), " +
                "(?, 'B', 1000, '/b.flac', 1)",
            arrayOf<Any?>(SONG_A, SONG_B),
        )
        database.execSQL(
            "INSERT INTO queue_items (slot_id, position, song_id, extra_kind, extra_sequence) VALUES " +
                "('slot-1', 0, ?, NULL, NULL), ('slot-2', 1, ?, 1, 4), ('slot-3', 2, ?, 2, 5)",
            arrayOf<Any?>(SONG_B, SONG_A, SONG_B),
        )
        database.execSQL(
            "INSERT INTO queue_baseline_items (slot_id, position, song_id) VALUES ('slot-1', 0, ?), ('slot-9', 1, ?)",
            arrayOf<Any?>(SONG_B, SONG_A),
        )
        database.execSQL(
            "INSERT INTO playback_cursor (id, current_slot_id, position_ms, shuffle_enabled, repeat_mode, " +
                "shuffle_reordered) VALUES (0, 'slot-2', 4321, 1, 2, 1)",
        )
    }

    private companion object {
        const val SONG_A = "song-a"
        const val SONG_B = "song-b"
        val QUEUE_TABLES = listOf("queue_items", "queue_baseline_items", "playback_cursor")
        val ORDER_BY = mapOf(
            "queue_items" to "position",
            "queue_baseline_items" to "position",
            "playback_cursor" to "id",
        )
        val V17_CURSOR_COLUMNS =
            listOf("id", "current_slot_id", "position_ms", "shuffle_enabled", "repeat_mode", "shuffle_reordered")
    }
}
