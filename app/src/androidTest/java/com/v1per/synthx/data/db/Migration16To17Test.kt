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
 * 16->17 adds the shuffle session-extra tags to `queue_items` and the reorder flag to
 * `playback_cursor`. Purely additive: every v16 row survives unchanged, untagged and unflagged.
 */
@RunWith(AndroidJUnit4::class)
class Migration16To17Test {
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

    private fun databaseName(label: String) = "migration-16-17-$label-${UUID.randomUUID()}.db".also(names::add)

    @Test
    fun migrationAddsTheTagColumnsAndTheFlagAndKeepsEverySavedRow() {
        val name = databaseName("rows")
        val before = helper.createDatabase(name, 16).use { database ->
            seedV16Queue(database)
            QUEUE_TABLES.associateWith { database.readRows(it, orderBy = ORDER_BY.getValue(it)) }
        }

        helper.runMigrationsAndValidate(name, 17, true, SynthXDatabase.MIGRATION_16_17).use { database ->
            assertEquals(
                listOf("slot_id", "position", "song_id", "extra_kind", "extra_sequence"),
                database.columnNames("queue_items"),
            )
            assertEquals(listOf("slot_id", "position", "song_id"), database.columnNames("queue_baseline_items"))
            assertTrue("shuffle_reordered" in database.columnNames("playback_cursor"))

            val items = database.readRows("queue_items", orderBy = "position")
            assertEquals(before.getValue("queue_items"), items.map { it.take(3) })
            assertTrue("saved rows come out untagged", items.all { it[3] == null && it[4] == null })
            assertEquals(
                before.getValue("queue_baseline_items"),
                database.readRows("queue_baseline_items", orderBy = "position"),
            )
            val cursorColumns = listOf("id", "current_slot_id", "position_ms", "shuffle_enabled", "repeat_mode")
            assertEquals(
                before.getValue("playback_cursor"),
                database.readRows("playback_cursor", cursorColumns, orderBy = "id"),
            )
            assertEquals(0, database.singleInt("SELECT shuffle_reordered FROM playback_cursor WHERE id = 0"))
            assertEquals(2, database.singleInt("SELECT COUNT(*) FROM songs"))
        }
    }

    @Test
    fun migratedTablesAcceptTaggedRowsAndTheFlag() {
        val name = databaseName("write")
        helper.createDatabase(name, 16).use(::seedV16Queue)

        helper.runMigrationsAndValidate(name, 17, true, SynthXDatabase.MIGRATION_16_17).use { database ->
            database.execSQL(
                "INSERT INTO queue_items (slot_id, position, song_id, extra_kind, extra_sequence) " +
                    "VALUES ('slot-x', 3, ?, 1, 7)",
                arrayOf<Any?>(SONG_B),
            )
            database.execSQL("UPDATE playback_cursor SET shuffle_reordered = 1 WHERE id = 0")

            assertEquals(1, database.singleInt("SELECT extra_kind FROM queue_items WHERE slot_id = 'slot-x'"))
            assertEquals(7, database.singleInt("SELECT extra_sequence FROM queue_items WHERE slot_id = 'slot-x'"))
            assertEquals(1, database.singleInt("SELECT shuffle_reordered FROM playback_cursor WHERE id = 0"))
        }
    }

    @Test
    fun standaloneBuilderMigratesAV16CandidateToCurrent() {
        val name = databaseName("standalone")
        helper.createDatabase(name, 16).use(::seedV16Queue)

        val standalone = SynthXDatabase.createStandalone(context, name)
        try {
            standalone.openHelper.writableDatabase
        } finally {
            standalone.close()
        }

        val validated = BackupSnapshotValidator.validate(context.getDatabasePath(name), migrationSnapshot = false)
        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    /** A shuffled v16 queue: three live slots, two of them in baseline, cursor on the second. */
    private fun seedV16Queue(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO songs (id, title, duration, file_path, date_added) VALUES (?, 'A', 1000, '/a.flac', 1), " +
                "(?, 'B', 1000, '/b.flac', 1)",
            arrayOf<Any?>(SONG_A, SONG_B),
        )
        database.execSQL(
            "INSERT INTO queue_items (slot_id, position, song_id) VALUES " +
                "('slot-1', 0, ?), ('slot-2', 1, ?), ('slot-3', 2, ?)",
            arrayOf<Any?>(SONG_B, SONG_A, SONG_B),
        )
        database.execSQL(
            "INSERT INTO queue_baseline_items (slot_id, position, song_id) VALUES ('slot-2', 0, ?), ('slot-1', 1, ?)",
            arrayOf<Any?>(SONG_A, SONG_B),
        )
        database.execSQL(
            "INSERT INTO playback_cursor (id, current_slot_id, position_ms, shuffle_enabled, repeat_mode) " +
                "VALUES (0, 'slot-2', 4321, 1, 2)",
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
    }
}
