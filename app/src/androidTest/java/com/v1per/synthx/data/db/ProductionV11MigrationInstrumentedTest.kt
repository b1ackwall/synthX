package com.v1per.synthx.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.BackupSchemaContracts
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in verification against a byte-for-byte staged copy of a real production v11 database.
 *
 * Supply the external fixture with `-PproductionDbPath=<absolute-host-path>`. Gradle copies it
 * into generated androidTest assets; this test then copies that read-only asset into the test
 * app's database directory. Neither SQLite nor Room ever receives the golden source path.
 *
 * PERMANENT SKIP, BY DECISION: no v11 production fixture exists or is expected to exist. This is
 * single-user software with no historical population of v11 installs to source one from, so the
 * [assumeTrue] guard below will always skip this test. That is intentional, not an open gap
 * awaiting a fixture. The test is kept only as a ready harness should a real v11 database ever
 * be supplied.
 */
@RunWith(AndroidJUnit4::class)
class ProductionV11MigrationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private var disposableDatabaseName: String? = null

    @After
    fun tearDown() {
        disposableDatabaseName?.let(context::deleteDatabase)
    }

    @Test
    fun realProductionV11DatabaseMigratesToCurrentWithoutDurableDataChanges() {
        assumeTrue(
            "Production fixture is opt-in; pass -PproductionDbPath=<absolute-host-path>",
            testAssets.list("")?.contains(PRODUCTION_DB_ASSET) == true,
        )

        val databaseName = "production-v11-migration-${UUID.randomUUID()}.db"
        disposableDatabaseName = databaseName
        val disposableFile = context.getDatabasePath(databaseName)
        val sourceSha256 = copyFixtureToDisposableDatabase(disposableFile)

        val before = SQLiteDatabase.openDatabase(
            disposableFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use(::captureBeforeMigration)

        assertEquals(SOURCE_VERSION, before.schema.userVersion)
        assertEquals(
            BackupSchemaContracts.forVersion(SOURCE_VERSION)?.roomIdentityHash,
            before.schema.roomIdentityHash,
        )

        // This is the production registration path: createStandalone and getOrCreate both use
        // SynthXDatabase.ALL_MIGRATIONS. Opening writableDatabase forces the whole 11->current chain.
        val migratingRoom = SynthXDatabase.createStandalone(context, databaseName)
        try {
            migratingRoom.openHelper.writableDatabase
        } finally {
            migratingRoom.close()
        }

        val after = SQLiteDatabase.openDatabase(
            disposableFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database -> captureAndVerifyAfterMigration(database, before) }

        // A second Room open at the current version proves the already-migrated file is accepted
        // as the current schema, independently of the migration transaction's own open-time
        // validation.
        val validatingRoom = SynthXDatabase.createStandalone(context, databaseName)
        try {
            validatingRoom.openHelper.writableDatabase
        } finally {
            validatingRoom.close()
        }

        reportResult(sourceSha256, before, after)
    }

    private fun copyFixtureToDisposableDatabase(destination: File): String {
        check(!destination.exists()) { "Disposable database already exists: $destination" }
        val parent = destination.parentFile
        check(parent != null && (parent.mkdirs() || parent.isDirectory)) {
            "Could not create disposable database directory"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        testAssets.open(PRODUCTION_DB_ASSET).use { input ->
            FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun captureBeforeMigration(database: SQLiteDatabase): BeforeMigration {
        assertEquals(listOf("ok"), database.singleColumnStrings("PRAGMA integrity_check"))
        assertEquals(0, database.queryRows("PRAGMA foreign_key_check").size)

        val schema = database.schemaSnapshot()
        val durableTables = DURABLE_TABLES.associateWith { table ->
            database.completeTableSnapshot(table)
        }
        val cursorColumns = listOf(
            "id",
            "queue_index",
            "position_ms",
            "shuffle_enabled",
            "repeat_mode",
        )
        return BeforeMigration(
            schema = schema,
            durableTables = durableTables,
            allTableCounts = database.allTableCounts(),
            queueItems = database.rowCount("queue_items"),
            queueBaselineItems = database.rowCount("queue_baseline_items"),
            playbackCursor = database.queryTableRows("playback_cursor", cursorColumns),
            playbackCursorPreserved = database.tableSnapshot(
                "playback_cursor",
                PLAYBACK_CURSOR_PRESERVED_COLUMNS,
            ),
        )
    }

    private fun captureAndVerifyAfterMigration(database: SQLiteDatabase, before: BeforeMigration): AfterMigration {
        val integrity = database.singleColumnStrings("PRAGMA integrity_check")
        val foreignKeyViolations = database.queryRows("PRAGMA foreign_key_check")
        val schema = database.schemaSnapshot()

        assertEquals(SynthXDatabase.VERSION, schema.userVersion)
        assertEquals(listOf("ok"), integrity)
        assertTrue("Foreign-key violations after migration: $foreignKeyViolations", foreignKeyViolations.isEmpty())
        assertEquals(BackupSchemaContracts.current.roomIdentityHash, schema.roomIdentityHash)

        val durableAfter = DURABLE_TABLES.associateWith { table ->
            val expected = before.durableTables.getValue(table)
            val actualColumns = database.columnNames(table)
            val missingColumns = expected.columns - actualColumns.toSet()
            assertTrue("$table lost pre-existing columns: $missingColumns", missingColumns.isEmpty())
            database.tableSnapshot(table, expected.columns)
        }
        DURABLE_TABLES.forEach { table ->
            assertTableExactlyPreserved(
                table,
                before.durableTables.getValue(table),
                durableAfter.getValue(table),
            )
        }

        SONG_SWATCH_COLUMNS.forEach { column ->
            assertTrue("Missing current songs column $column", column in database.columnNames("songs"))
            assertEquals(
                "Existing songs must initially have NULL $column",
                0L,
                database.whereCount("songs", "${quote(column)} IS NOT NULL"),
            )
        }
        PLAYLIST_ART_COLUMNS.forEach { column ->
            assertTrue("Missing current playlists column $column", column in database.columnNames("playlists"))
            assertEquals(
                "Existing playlists must initially have NULL $column",
                0L,
                database.whereCount("playlists", "${quote(column)} IS NOT NULL"),
            )
        }

        val queueItems = database.rowCount("queue_items")
        val queueBaselineItems = database.rowCount("queue_baseline_items")
        assertEquals("11->12 must clear queue_items", 0L, queueItems)
        assertEquals("11->12 must clear queue_baseline_items", 0L, queueBaselineItems)

        val playbackCursorPreserved = database.tableSnapshot(
            "playback_cursor",
            PLAYBACK_CURSOR_PRESERVED_COLUMNS,
        )
        assertTableExactlyPreserved(
            "playback_cursor preserved fields",
            before.playbackCursorPreserved,
            playbackCursorPreserved,
        )
        assertEquals(
            "Every migrated playback cursor must have a NULL current_slot_id",
            0L,
            database.whereCount("playback_cursor", "current_slot_id IS NOT NULL"),
        )

        return AfterMigration(
            schema = schema,
            durableTables = durableAfter,
            allTableCounts = database.allTableCounts(),
            integrity = integrity,
            foreignKeyViolations = foreignKeyViolations,
            queueItems = queueItems,
            queueBaselineItems = queueBaselineItems,
            playbackCursor = database.queryTableRows(
                "playback_cursor",
                PLAYBACK_CURSOR_PRESERVED_COLUMNS + "current_slot_id",
            ),
        )
    }

    private fun assertTableExactlyPreserved(table: String, before: TableSnapshot, after: TableSnapshot) {
        assertEquals("$table comparison columns changed", before.columns, after.columns)
        assertEquals("$table row count changed", before.rowCount, after.rowCount)
        if (before.rows != after.rows) {
            val missing = multisetDifference(before.rows, after.rows)
            val extra = multisetDifference(after.rows, before.rows)
            throw AssertionError(
                "$table durable data changed; missing=${missing.take(DIFFERENCE_REPORT_LIMIT)}, " +
                    "extra=${extra.take(DIFFERENCE_REPORT_LIMIT)}",
            )
        }
    }

    private fun reportResult(sourceSha256: String, before: BeforeMigration, after: AfterMigration) {
        println("PRODUCTION_MIGRATION_SOURCE_SHA256=$sourceSha256")
        println("PRODUCTION_MIGRATION_SOURCE_SCHEMA=${before.schema}")
        println("PRODUCTION_MIGRATION_MIGRATED_SCHEMA=${after.schema}")
        println("PRODUCTION_MIGRATION_SOURCE_ROW_COUNTS=${before.allTableCounts}")
        println("PRODUCTION_MIGRATION_MIGRATED_ROW_COUNTS=${after.allTableCounts}")
        println(
            "PRODUCTION_MIGRATION_QUEUE_COUNTS=" +
                "queue_items:${before.queueItems}->${after.queueItems}," +
                "queue_baseline_items:${before.queueBaselineItems}->${after.queueBaselineItems}",
        )
        println("PRODUCTION_MIGRATION_PLAYBACK_CURSOR_BEFORE=${before.playbackCursor}")
        println("PRODUCTION_MIGRATION_PLAYBACK_CURSOR_AFTER=${after.playbackCursor}")
        println("PRODUCTION_MIGRATION_INTEGRITY=${after.integrity}")
        println("PRODUCTION_MIGRATION_FOREIGN_KEY_VIOLATIONS=${after.foreignKeyViolations.size}")
        println("PRODUCTION_MIGRATION_DATA_DIFFERENCES=none")
    }

    private fun SQLiteDatabase.schemaSnapshot(): SchemaSnapshot {
        val tables = tableNames()
        return SchemaSnapshot(
            userVersion = singleLong("PRAGMA user_version").toInt(),
            roomIdentityHash = rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null },
            tableColumns = tables.associateWith { table -> columnNames(table) },
        )
    }

    private fun SQLiteDatabase.completeTableSnapshot(table: String): TableSnapshot =
        tableSnapshot(table, columnNames(table))

    private fun SQLiteDatabase.tableSnapshot(table: String, columns: List<String>): TableSnapshot {
        val rows = queryTableRows(table, columns)
            .map(::canonicalRow)
            .sorted()
        return TableSnapshot(columns = columns, rows = rows)
    }

    private fun SQLiteDatabase.queryTableRows(table: String, columns: List<String>): List<List<CellValue>> {
        val projection = columns.joinToString(", ", transform = ::quote)
        return queryRows("SELECT $projection FROM ${quote(table)}")
    }

    private fun SQLiteDatabase.queryRows(sql: String): List<List<CellValue>> = rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(List(cursor.columnCount) { index -> cursor.cellValue(index) })
            }
        }
    }

    private fun Cursor.cellValue(index: Int): CellValue = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> CellValue.Null
        Cursor.FIELD_TYPE_INTEGER -> CellValue.Integer(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> CellValue.Real(java.lang.Double.doubleToRawLongBits(getDouble(index)))
        Cursor.FIELD_TYPE_STRING -> CellValue.Text(getString(index))
        Cursor.FIELD_TYPE_BLOB -> CellValue.Blob(Base64.encodeToString(getBlob(index), Base64.NO_WRAP))
        else -> error("Unknown SQLite field type ${getType(index)}")
    }

    private fun canonicalRow(row: List<CellValue>): String = row.joinToString(separator = "") { value ->
        val encoded = value.encode()
        "${encoded.length}:$encoded"
    }

    private fun CellValue.encode(): String = when (this) {
        CellValue.Null -> "N"
        is CellValue.Integer -> "I$value"
        is CellValue.Real -> "R$rawBits"
        is CellValue.Text -> "T$value"
        is CellValue.Blob -> "B$base64"
    }

    private fun SQLiteDatabase.columnNames(table: String): List<String> =
        rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            buildList {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SQLiteDatabase.tableNames(): List<String> = rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun SQLiteDatabase.allTableCounts(): Map<String, Long> =
        tableNames().associateWith { table -> rowCount(table) }

    private fun SQLiteDatabase.rowCount(table: String): Long = singleLong("SELECT COUNT(*) FROM ${quote(table)}")

    private fun SQLiteDatabase.whereCount(table: String, where: String): Long =
        singleLong("SELECT COUNT(*) FROM ${quote(table)} WHERE $where")

    private fun SQLiteDatabase.singleLong(sql: String): Long = rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "No result for $sql" }
        cursor.getLong(0)
    }

    private fun SQLiteDatabase.singleColumnStrings(sql: String): List<String> = rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun multisetDifference(left: List<String>, right: List<String>): List<String> {
        val remaining = right.groupingBy { it }.eachCount().toMutableMap()
        return buildList {
            left.forEach { row ->
                val count = remaining[row] ?: 0
                if (count == 0) add(row) else remaining[row] = count - 1
            }
        }
    }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private data class SchemaSnapshot(
        val userVersion: Int,
        val roomIdentityHash: String?,
        val tableColumns: Map<String, List<String>>,
    )

    private data class TableSnapshot(val columns: List<String>, val rows: List<String>) {
        val rowCount: Int get() = rows.size
    }

    private data class BeforeMigration(
        val schema: SchemaSnapshot,
        val durableTables: Map<String, TableSnapshot>,
        val allTableCounts: Map<String, Long>,
        val queueItems: Long,
        val queueBaselineItems: Long,
        val playbackCursor: List<List<CellValue>>,
        val playbackCursorPreserved: TableSnapshot,
    )

    private data class AfterMigration(
        val schema: SchemaSnapshot,
        val durableTables: Map<String, TableSnapshot>,
        val allTableCounts: Map<String, Long>,
        val integrity: List<String>,
        val foreignKeyViolations: List<List<CellValue>>,
        val queueItems: Long,
        val queueBaselineItems: Long,
        val playbackCursor: List<List<CellValue>>,
    )

    private sealed interface CellValue {
        data object Null : CellValue
        data class Integer(val value: Long) : CellValue
        data class Real(val rawBits: Long) : CellValue
        data class Text(val value: String) : CellValue
        data class Blob(val base64: String) : CellValue
    }

    private companion object {
        const val PRODUCTION_DB_ASSET = "production-v11-migration.db"
        const val SOURCE_VERSION = 11
        const val DIFFERENCE_REPORT_LIMIT = 20

        val DURABLE_TABLES = listOf(
            "songs",
            "albums",
            "artists",
            "song_artists",
            "playlists",
            "playlist_songs",
            "playlist_groups",
        )
        val PLAYBACK_CURSOR_PRESERVED_COLUMNS = listOf(
            "id",
            "position_ms",
            "shuffle_enabled",
            "repeat_mode",
        )
        val SONG_SWATCH_COLUMNS = listOf(
            "swatch1_hue",
            "swatch1_saturation",
            "swatch1_lightness",
            "swatch2_hue",
            "swatch2_saturation",
            "swatch2_lightness",
            "swatch3_hue",
            "swatch3_saturation",
            "swatch3_lightness",
        )
        val PLAYLIST_ART_COLUMNS = listOf("art_hue", "art_structure_seed")
    }
}
