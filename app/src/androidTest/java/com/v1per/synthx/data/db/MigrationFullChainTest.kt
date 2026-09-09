package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupSchemaContracts
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The N7 "full chain" gap: every other migration test here (Migration8To9Test,
 * Migration9To10Test, Migration10To11Test) validates one adjacent-version leg at a time. None of
 * them prove the *entire* chain composes correctly end to end the way a real, long-lived install
 * actually experiences it — one process opening a years-old database and Room silently walking
 * every step in [SynthXDatabase.ALL_MIGRATIONS] in a single pass.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFullChainTest {

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
    fun fullChainMigratesFromOldestSupportedVersionToCurrentInOnePass() {
        // ALL_MIGRATIONS' earliest entry is MIGRATION_1_2 (startVersion = 1) — this assertion
        // documents that assumption so the test fails loudly, not silently at the wrong start
        // version, if an even-older migration is ever added or version 1 support is ever dropped.
        val oldestSupportedVersion = SynthXDatabase.ALL_MIGRATIONS.minOf { it.startVersion }
        assertEquals(1, oldestSupportedVersion)

        val name = databaseName("full-chain-schema")
        migrationHelper.createDatabase(name, oldestSupportedVersion).close()

        // validateDroppedTables = true: Room compares the fully-migrated result against the
        // current version's exported schema JSON in one shot — a single missing migration
        // anywhere in the chain, or a step applied out of order, fails here.
        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            SynthXDatabase.VERSION,
            true,
            *SynthXDatabase.ALL_MIGRATIONS,
        )

        migrated.use { database ->
            val schema = BackupSchemaContracts.current
            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            val missing = schema.expectedTables - tables
            assertTrue(
                "Full chain migration from v$oldestSupportedVersion is missing tables: " +
                    missing.sorted().toString(),
                missing.isEmpty(),
            )

            val identity = database.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            assertEquals(
                "Full chain migration must land on the exact same Room identity as current",
                schema.roomIdentityHash,
                identity,
            )
        }
    }

    @Test
    fun fullChainPreservesRealDataFromTheLastDestructiveMigrationOnward() {
        // MIGRATION_2_3 and MIGRATION_3_4 are the only destructive legs for library data.
        // MIGRATION_11_12 deliberately wipes transient queue rows, but must still preserve every
        // song and playlist table. Seeding at v4, right where 3_4 lands, and walking the rest of
        // the chain in one pass proves what every real v4+ install actually depends on: a song
        // surviving every later migration. Seeding at v1 instead would make this a test of
        // 2_3/3_4's *intentional* wipe behavior, which is the wrong thing to assert here.
        val name = databaseName("full-chain-data")
        migrationHelper.createDatabase(name, 4).use { seedV4Song(it) }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            SynthXDatabase.VERSION,
            true,
            *SynthXDatabase.ALL_MIGRATIONS,
        )

        migrated.use { database ->
            database.query(
                "SELECT id, title, album_id, duration, file_path FROM songs",
            ).use { cursor ->
                assertTrue("The song seeded at v4 did not survive the full chain", cursor.moveToFirst())
                assertEquals(SONG_ID, cursor.getString(0))
                assertEquals("Pre-chain title", cursor.getString(1))
                assertEquals(ALBUM_ID, cursor.getString(2))
                assertEquals(214_000L, cursor.getLong(3))
                assertEquals("/music/pre-chain.flac", cursor.getString(4))
                assertEquals("Exactly one song row was expected", 1, cursor.count)
            }
            database.query("SELECT title FROM albums WHERE id = ?", arrayOf(ALBUM_ID)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Pre-chain album", cursor.getString(0))
            }
            // The v12->v13 leg must also compose with the complete historical chain.
            database.query(
                "SELECT swatch1_hue, swatch1_saturation, swatch1_lightness, " +
                    "swatch2_hue, swatch2_saturation, swatch2_lightness, " +
                    "swatch3_hue, swatch3_saturation, swatch3_lightness FROM songs",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(9) { index -> assertTrue("New swatch component $index must be NULL", cursor.isNull(index)) }
            }
        }
    }

    private fun databaseName(label: String): String =
        "migration-full-chain-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    /** A fully populated v4 song plus its album — v4 songs still has verified/verified_at (6_7 drops them). */
    private fun seedV4Song(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO albums (id, title, year, album_art_uri) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(ALBUM_ID, "Pre-chain album", 1998, "file:///art/album.jpg"),
        )
        database.execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, album_art_uri, year, " +
                "track_number, date_added, is_missing, missing_since, verified, verified_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                SONG_ID,
                "Pre-chain title",
                ALBUM_ID,
                214_000L,
                "/music/pre-chain.flac",
                "file:///art/compressed.jpg",
                1998,
                7,
                1_700_000_000_000L,
                0,
                null,
                0,
                null,
            ),
        )
    }

    private companion object {
        const val SONG_ID = "song-pre-chain"
        const val ALBUM_ID = "album-pre-chain"
    }
}
