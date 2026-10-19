package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupSchemaContracts
import com.v1per.synthx.ui.testing.TestTimeouts
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-chain coverage: the per-leg migration tests here (Migration8To9Test through
 * Migration15To16Test) each validate one adjacent-version leg at a time. None of
 * them prove the *entire* chain composes correctly end to end the way a real, long-lived install
 * actually experiences it — one process opening a years-old database and Room silently walking
 * every step in [SynthXDatabase.ALL_MIGRATIONS] in a single pass.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFullChainTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

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
        // MIGRATION_2_3 and MIGRATION_3_4 are the only legs that wipe library data;
        // MIGRATION_15_16 deletes only duplicate album rows after repointing their songs.
        // MIGRATION_11_12 deliberately wipes transient queue rows, but must still preserve every
        // song and playlist table. Seeding at v4, right where 3_4 lands, and walking the rest of
        // the chain in one pass proves what every real v4+ install actually depends on: a song
        // surviving every later migration. Seeding at v1 instead would make this a test of
        // 2_3/3_4's *intentional* wipe behavior, which is the wrong thing to assert here.
        val name = databaseName("full-chain-data")
        migrationHelper.createDatabase(name, 4).use {
            seedRepresentativeLibrary(it, 4)
        }

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
            // ...and so must the v13->v14 leg: a playlist seeded at v4 keeps its name and
            // group, and picks up both art columns as NULL for PlaylistArtBackfill to fill.
            database.query(
                "SELECT name, group_id, art_hue, art_structure_seed FROM playlists WHERE id = ?",
                arrayOf(PLAYLIST_ID),
            ).use { cursor ->
                assertTrue("The playlist seeded at v4 did not survive the full chain", cursor.moveToFirst())
                assertEquals("Pre-chain playlist", cursor.getString(0))
                assertEquals(GROUP_ID, cursor.getString(1))
                assertTrue("art_hue must be NULL", cursor.isNull(2))
                assertTrue("art_structure_seed must be NULL", cursor.isNull(3))
            }
            assertRepresentativeRelationships(database)
        }
    }

    @Test
    fun everyHistoricalStartingVersionMigratesPopulatedDataToCurrent() {
        for (startVersion in 1 until SynthXDatabase.VERSION) {
            val name = databaseName("populated-v$startVersion")
            migrationHelper.createDatabase(name, startVersion).use {
                seedRepresentativeLibrary(it, startVersion)
                seedSongOutsideEveryPlaylist(it)
            }
            migrationHelper.runMigrationsAndValidate(
                name,
                SynthXDatabase.VERSION,
                true,
                *SynthXDatabase.ALL_MIGRATIONS,
            ).use { database ->
                if (startVersion <= 3) {
                    assertEquals(0, database.singleInt("SELECT COUNT(*) FROM songs"))
                    assertEquals(0, database.singleInt("SELECT COUNT(*) FROM song_artists"))
                    assertEquals(0, database.singleInt("SELECT COUNT(*) FROM playlist_songs"))
                } else {
                    assertRepresentativeRelationships(database)
                    assertSongOutsideEveryPlaylistStaysInInbox(database, startVersion)
                }
                assertEquals(0, database.query("PRAGMA foreign_key_check").use { it.count })
            }
        }
    }

    @Test
    fun failedMidMigrationSqlRollsBackAndRealChainRetriesCleanly() {
        val name = databaseName("mid-sql-rollback")
        migrationHelper.createDatabase(name, 6).use { seedRepresentativeLibrary(it, 6) }
        val failMidRebuild = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS listen_history")
                db.execSQL(
                    """
                    CREATE TABLE songs_new (
                        id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, album_id TEXT,
                        duration INTEGER NOT NULL, file_path TEXT NOT NULL, album_art_uri TEXT,
                        year INTEGER, track_number INTEGER, date_added INTEGER NOT NULL,
                        is_missing INTEGER NOT NULL DEFAULT 0, missing_since INTEGER,
                        FOREIGN KEY(album_id) REFERENCES albums(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO songs_new (
                        id,title,album_id,duration,file_path,album_art_uri,year,track_number,
                        date_added,is_missing,missing_since
                    ) SELECT id,title,album_id,duration,file_path,album_art_uri,year,track_number,
                        date_added,is_missing,missing_since FROM songs
                    """.trimIndent(),
                )
                error("injected crash during 6->7 songs rebuild")
            }
        }
        try {
            migrationHelper.runMigrationsAndValidate(name, 7, true, failMidRebuild).close()
            fail("Expected injected migration failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("injected crash"))
        }

        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { rolledBack ->
            assertEquals(6, rolledBack.version)
            assertEquals(
                1,
                rolledBack.rawQuery("SELECT COUNT(*) FROM songs", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                rolledBack.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='songs_new'",
                    null,
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }
        migrationHelper.runMigrationsAndValidate(
            name,
            SynthXDatabase.VERSION,
            true,
            *SynthXDatabase.ALL_MIGRATIONS,
        ).use(::assertRepresentativeRelationships)
    }

    private fun databaseName(label: String): String =
        "migration-full-chain-$label-${UUID.randomUUID()}.db".also { createdDatabaseNames += it }

    /** A fully populated v4 song plus its album — v4 songs still has verified/verified_at (6_7 drops them). */
    private fun seedRepresentativeLibrary(database: SupportSQLiteDatabase, startVersion: Int) {
        database.execSQL(
            "INSERT INTO albums (id, title, year, album_art_uri) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(ALBUM_ID, "Pre-chain album", 1998, "file:///art/album.jpg"),
        )
        database.execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, album_art_uri, year, " +
                "date_added, is_missing, missing_since) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                SONG_ID,
                "Pre-chain title",
                ALBUM_ID,
                214_000L,
                "/music/pre-chain.flac",
                "file:///art/compressed.jpg",
                1998,
                1_700_000_000_000L,
                0,
                null,
            ),
        )
        // v8+ fixtures start after the membership-derived backfill, so seed the invariant that
        // migration would already have established. v7 and earlier must earn this via 7->8.
        if (startVersion >= 8) {
            database.execSQL(
                "UPDATE songs SET in_inbox = 0 WHERE id = ?",
                arrayOf<Any?>(SONG_ID),
            )
        }
        database.execSQL(
            "INSERT INTO artists (id, name, name_normalized) VALUES (?, ?, ?)",
            arrayOf<Any?>(ARTIST_ID, "Pre-chain artist", "pre-chain artist"),
        )
        database.execSQL(
            "INSERT INTO song_artists (song_id, artist_id, position) VALUES (?, ?, ?)",
            arrayOf<Any?>(SONG_ID, ARTIST_ID, 0),
        )
        seedPlaylistRelationship(database)
    }

    /** A grouped v4 playlist — the playlists/playlist_groups shape is unchanged from v4 to v13. */
    private fun seedPlaylistRelationship(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO playlist_groups (id, name, position, created_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(GROUP_ID, "Pre-chain group", 0, 1_700_000_000_000L),
        )
        database.execSQL(
            "INSERT INTO playlists (id, name, description, group_id, position, created_at, " +
                "updated_at, cover_art_uri) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                PLAYLIST_ID,
                "Pre-chain playlist",
                "Pre-chain notes",
                GROUP_ID,
                0,
                1_700_000_000_000L,
                1_700_000_000_000L,
                null,
            ),
        )
        database.execSQL(
            "INSERT INTO playlist_songs (playlist_id, song_id, position, added_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(PLAYLIST_ID, SONG_ID, 4, 1_650_000_000_000L),
        )
    }

    private fun assertRepresentativeRelationships(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT artist_id, position FROM song_artists WHERE song_id = ?",
            arrayOf(SONG_ID),
        ).use {
            assertTrue("Positioned artist link did not survive", it.moveToFirst())
            assertEquals(ARTIST_ID, it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        database.query(
            "SELECT position, added_at FROM playlist_songs WHERE playlist_id = ? AND song_id = ?",
            arrayOf(PLAYLIST_ID, SONG_ID),
        ).use {
            assertTrue("Playlist membership did not survive", it.moveToFirst())
            assertEquals(4, it.getInt(0))
            assertEquals(1_650_000_000_000L, it.getLong(1))
        }
        assertEquals(
            "7->8 must dismiss an existing playlist member from inbox",
            0,
            database.query("SELECT in_inbox FROM songs WHERE id = ?", arrayOf(SONG_ID)).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            },
        )
        assertEquals(0, database.query("PRAGMA foreign_key_check").use { it.count })
    }

    /** A song in no playlist. From v8 it takes the column default in_inbox = 1. */
    private fun seedSongOutsideEveryPlaylist(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, date_added) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(LONER_SONG_ID, "Loner title", null, 90_000L, "/music/loner.flac", 1_700_000_000_000L),
        )
    }

    /** Up to v7, 7->8 must leave the loner at 1; from v8, later steps must keep the seeded 1. */
    private fun assertSongOutsideEveryPlaylistStaysInInbox(database: SupportSQLiteDatabase, startVersion: Int) {
        val rule = if (startVersion <= 7) {
            "7->8 must keep a song outside every playlist in the inbox"
        } else {
            "steps after 7->8 must not change in_inbox"
        }
        assertEquals(
            "$rule (start v$startVersion)",
            1,
            database.query("SELECT in_inbox FROM songs WHERE id = ?", arrayOf(LONER_SONG_ID)).use {
                assertTrue("The song outside every playlist did not survive", it.moveToFirst())
                it.getInt(0)
            },
        )
    }

    private fun SupportSQLiteDatabase.singleInt(sql: String): Int = query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }

    private companion object {
        const val SONG_ID = "song-pre-chain"
        const val ALBUM_ID = "album-pre-chain"
        const val ARTIST_ID = "artist-pre-chain"
        const val GROUP_ID = "group-pre-chain"
        const val PLAYLIST_ID = "playlist-pre-chain"
        const val LONER_SONG_ID = "song-outside-playlists"
    }
}
