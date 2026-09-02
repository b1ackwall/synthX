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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the queue-slot identity and slot-based resume cursor introduced by 11->12. */
@RunWith(AndroidJUnit4::class)
class Migration11To12Test {

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
    fun migration11To12RecreatesTransientQueuesWithStableSlotPrimaryKeys() {
        val name = databaseName("slot-schema")
        migrationHelper.createDatabase(name, 11).use(::seedV11State)

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            12,
            true,
            SynthXDatabase.MIGRATION_11_12,
        )

        migrated.use { database ->
            assertEquals(0, database.rowCount("queue_items"))
            assertEquals(0, database.rowCount("queue_baseline_items"))

            listOf("queue_items", "queue_baseline_items").forEach { table ->
                val columns = database.columnInfo(table)
                assertEquals(setOf("slot_id", "position", "song_id"), columns.keys)
                assertEquals(1, columns.getValue("slot_id").primaryKeyPosition)
                assertEquals(0, columns.getValue("position").primaryKeyPosition)
                assertTrue("index_${table}_position" in database.indexNames(table))
                assertTrue("index_${table}_song_id" in database.indexNames(table))
            }

            database.execSQL(
                "INSERT INTO queue_items (slot_id, position, song_id) VALUES (?, 0, ?)",
                arrayOf<Any?>("slot-a", SONG_ID),
            )
            database.execSQL(
                "INSERT INTO queue_items (slot_id, position, song_id) VALUES (?, 1, ?)",
                arrayOf<Any?>("slot-b", SONG_ID),
            )
            assertEquals(
                "Two slots for the same content song must coexist",
                2,
                database.rowCount("queue_items"),
            )
        }
    }

    @Test
    fun migration11To12ReplacesRawIndexCursorAndPreservesIndependentState() {
        val name = databaseName("slot-cursor")
        migrationHelper.createDatabase(name, 11).use(::seedV11State)

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            12,
            true,
            SynthXDatabase.MIGRATION_11_12,
        )

        migrated.use { database ->
            val columns = database.columnInfo("playback_cursor")
            assertFalse("queue_index" in columns)
            assertTrue("current_slot_id" in columns)
            database.query(
                "SELECT current_slot_id, position_ms, shuffle_enabled, repeat_mode " +
                    "FROM playback_cursor WHERE id = 0",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("Migrated cursor must not retain an ambiguous raw index", cursor.isNull(0))
                assertEquals(POSITION_MS, cursor.getLong(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(2, cursor.getInt(3))
            }
            database.query("SELECT title FROM songs WHERE id = ?", arrayOf(SONG_ID)).use { cursor ->
                assertTrue("Library rows must survive this queue-only wipe", cursor.moveToFirst())
                assertEquals("Duplicate song", cursor.getString(0))
            }
        }
    }

    @Test
    fun standaloneBuilderMigratesV11CandidateToCurrent() {
        val name = databaseName("standalone-v11")
        migrationHelper.createDatabase(name, 11).use(::seedV11State)

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

    private fun seedV11State(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO songs (id, title, duration, file_path, date_added) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(SONG_ID, "Duplicate song", 180_000L, "/music/duplicate.flac", 1L),
        )
        database.execSQL(
            "INSERT INTO queue_items (position, song_id) VALUES (0, ?), (1, ?)",
            arrayOf<Any?>(SONG_ID, SONG_ID),
        )
        database.execSQL(
            "INSERT INTO queue_baseline_items (position, song_id) VALUES (0, ?), (1, ?)",
            arrayOf<Any?>(SONG_ID, SONG_ID),
        )
        database.execSQL(
            "INSERT INTO playback_cursor " +
                "(id, queue_index, position_ms, shuffle_enabled, repeat_mode) VALUES (0, 1, ?, 1, 2)",
            arrayOf<Any?>(POSITION_MS),
        )
    }

    private fun databaseName(label: String): String =
        "migration-11-12-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    private data class ColumnInfo(val primaryKeyPosition: Int)

    private fun SupportSQLiteDatabase.columnInfo(table: String): Map<String, ColumnInfo> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    put(cursor.getString(nameIndex), ColumnInfo(cursor.getInt(primaryKeyIndex)))
                }
            }
        }

    private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val SONG_ID = "duplicate-song"
        const val POSITION_MS = 123_456L
    }
}
