package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.MigrationTestSupport.albums
import com.v1per.synthx.data.db.MigrationTestSupport.foreignKeyViolations
import com.v1per.synthx.data.db.MigrationTestSupport.insertAlbum
import com.v1per.synthx.data.db.MigrationTestSupport.insertArtist
import com.v1per.synthx.data.db.MigrationTestSupport.insertSong
import com.v1per.synthx.data.db.MigrationTestSupport.linkArtist
import com.v1per.synthx.data.db.MigrationTestSupport.readRows
import com.v1per.synthx.data.db.MigrationTestSupport.strings
import com.v1per.synthx.data.db.MigrationTestSupport.tableNames
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Companion to [MigrationFullChainTest] with a richer, version-aware seed. For every start
 * version from 4 (right after the planned 3->4 wipe) to 15, real user rows walk the whole
 * chain: several songs (one outside every playlist), ordered playlist entries, groups,
 * case-duplicate albums (folded by 15->16), listen history (dropped by 6->7), queue rows
 * (emptied by 11->12), the playback cursor (rebuilt by 11->12) and a pending cleanup.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFullChainUserDataTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableSetOf<String>()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun tearDown() = names.forEach(context::deleteDatabase)

    @Test
    fun libraryAndPlaylistDataSurviveTheChainFromEveryStartVersion() {
        for (start in FIRST_START until SynthXDatabase.VERSION) {
            val name = databaseName("library-v$start")
            val before = helper.createDatabase(name, start).use { db ->
                seed(db, start)
                UNCHANGED_TABLES.associateWith { db.readRows(it, orderBy = ORDER.getValue(it)) } +
                    ("songs" to db.readRows("songs", SONG_COLUMNS, "id")) +
                    ("playlists" to db.readRows("playlists", PLAYLIST_COLUMNS, "id"))
            }
            migrateToCurrent(name).use { db ->
                val label = "start v$start"
                UNCHANGED_TABLES.forEach { table ->
                    assertEquals("$label: $table", before.getValue(table), db.readRows(table, orderBy = ORDER.getValue(table)))
                }
                val expectedSongs = before.getValue("songs").map { row ->
                    row.toMutableList().also { if (it[ALBUM_ID_INDEX] == "b-chain") it[ALBUM_ID_INDEX] = "a-chain" }
                }
                assertEquals("$label: songs", expectedSongs, db.readRows("songs", SONG_COLUMNS, "id"))
                assertEquals("$label: playlists", before.getValue("playlists"), db.readRows("playlists", PLAYLIST_COLUMNS, "id"))
                assertEquals(
                    "$label: playlist art",
                    if (start >= 14) listOf("210", "9") else listOf(null, null),
                    db.query("SELECT art_hue, art_structure_seed FROM playlists WHERE id = 'p1'").use {
                        it.moveToFirst(); listOf(it.getString(0), it.getString(1))
                    },
                )
                assertEquals(
                    "$label: playlist order",
                    listOf("s2", "s1", "s3"),
                    db.strings("SELECT song_id FROM playlist_songs WHERE playlist_id = 'p1' ORDER BY position"),
                )
                assertEquals(
                    "$label: albums after the 15->16 fold",
                    mapOf(
                        "a-chain" to Triple("Chain Album", 1998, "file:///art/a.jpg"),
                        "c-solo" to Triple("Solo", 1977, null),
                    ),
                    db.albums(),
                )
                assertEquals(
                    "$label: in_inbox (playlist members 0, the loner 1)",
                    listOf("0", "0", "0", "1"),
                    db.strings("SELECT in_inbox FROM songs ORDER BY id"),
                )
                assertFalse("$label: listen_history must be gone", "listen_history" in db.tableNames())
                assertEquals("$label: foreign keys", 0, db.foreignKeyViolations())
            }
        }
    }

    @Test
    fun queueCursorAndCleanupRowsFollowEachStartVersionsRules() {
        for (start in FIRST_START until SynthXDatabase.VERSION) {
            val name = databaseName("queue-v$start")
            val before = helper.createDatabase(name, start).use { db ->
                seed(db, start)
                if (start >= 12) {
                    QUEUE_TABLES.associateWith { db.readRows(it, orderBy = "slot_id") }
                } else {
                    emptyMap()
                }
            }
            migrateToCurrent(name).use { db ->
                val label = "start v$start"
                QUEUE_TABLES.forEach { table ->
                    assertEquals(
                        "$label: $table (emptied by 11->12, kept after)",
                        before[table] ?: emptyList<List<String?>>(),
                        db.readRows(table, orderBy = "slot_id"),
                    )
                }
                val expectedCursor = when {
                    start < 10 -> emptyList()
                    start == 10 -> listOf(listOf("1", null, "9876", "0", "0"))
                    start == 11 -> listOf(listOf("1", null, "9876", "1", "2"))
                    else -> listOf(listOf("1", "slot-2", "9876", "1", "2"))
                }
                assertEquals(
                    "$label: playback_cursor",
                    expectedCursor,
                    db.readRows("playback_cursor", listOf("id", "current_slot_id", "position_ms", "shuffle_enabled", "repeat_mode")),
                )
                assertEquals(
                    "$label: pending_cleanups",
                    if (start >= 15) listOf(listOf("op-1", "s4", "DELETE", "/music/s4.flac", null, "Committed", "500")) else emptyList(),
                    db.readRows("pending_cleanups"),
                )
                assertEquals("$label: foreign keys", 0, db.foreignKeyViolations())
            }
        }
    }

    private fun migrateToCurrent(name: String): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(name, SynthXDatabase.VERSION, true, *SynthXDatabase.ALL_MIGRATIONS)

    private fun databaseName(label: String) = "migration-chain-user-data-$label-${UUID.randomUUID()}.db".also(names::add)

    /** Rows shaped for [start]'s exported schema. */
    private fun seed(db: SupportSQLiteDatabase, start: Int) {
        db.insertArtist("one", "artist one")
        db.insertArtist("two", "artist two")
        db.insertAlbum("a-chain", "Chain Album", 1998, "file:///art/a.jpg")
        db.insertAlbum("b-chain", "CHAIN ALBUM", 2003, null)
        db.insertAlbum("c-solo", "Solo", 1977, null)
        db.insertSong("s1", "a-chain", trackNumber = 1, year = 1998)
        db.insertSong("s2", "b-chain", trackNumber = 2, year = 2003)
        db.insertSong("s3", "c-solo", trackNumber = 7)
        db.insertSong("s4", null)
        db.linkArtist("s1", "one")
        db.linkArtist("s2", "one")
        db.linkArtist("s2", "two", position = 1)
        db.linkArtist("s3", "two")
        db.linkArtist("s4", "two")
        // From v8 the 7->8 backfill has already run, so seed its result.
        if (start >= 8) db.execSQL("UPDATE songs SET in_inbox = 0 WHERE id IN ('s1', 's2', 's3')")
        db.execSQL("INSERT INTO playlist_groups VALUES ('g1', 'Group one', 0, 100)")
        db.execSQL("INSERT INTO playlist_groups VALUES ('g2', 'Group two', 1, 101)")
        db.execSQL("INSERT INTO playlists (${PLAYLIST_COLUMNS.joinToString()}) VALUES ('p1', 'First', 'notes', 'g1', 0, 100, 200, NULL)")
        db.execSQL("INSERT INTO playlists (${PLAYLIST_COLUMNS.joinToString()}) VALUES ('p2', 'Second', NULL, 'g1', 1, 101, 201, 'cover')")
        db.execSQL("INSERT INTO playlists (${PLAYLIST_COLUMNS.joinToString()}) VALUES ('p3', 'Loose', NULL, NULL, 2, 102, 202, NULL)")
        if (start >= 14) db.execSQL("UPDATE playlists SET art_hue = 210, art_structure_seed = 9 WHERE id = 'p1'")
        listOf(
            "('p1', 's2', 0, 300)", "('p1', 's1', 1, 301)", "('p1', 's3', 2, 302)",
            "('p2', 's3', 0, 303)", "('p2', 's2', 5, 304)", "('p3', 's1', 0, 305)",
        ).forEach { db.execSQL("INSERT INTO playlist_songs VALUES $it") }
        if (start <= 6) {
            db.execSQL("INSERT INTO listen_history VALUES (1, 's1', 1000, 180000, 1)")
            db.execSQL("INSERT INTO listen_history VALUES (2, 's4', 2000, 5000, 0)")
        }
        when (start) {
            in 5..11 -> {
                db.execSQL("INSERT INTO queue_items VALUES (0, 's2')")
                db.execSQL("INSERT INTO queue_items VALUES (1, 's4')")
                if (start >= 6) db.execSQL("INSERT INTO queue_baseline_items VALUES (0, 's4')")
            }
            in 12..15 -> {
                db.execSQL("INSERT INTO queue_items VALUES ('slot-1', 0, 's2')")
                db.execSQL("INSERT INTO queue_items VALUES ('slot-2', 1, 's4')")
                db.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-2', 0, 's4')")
                db.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-1', 1, 's2')")
            }
        }
        when (start) {
            10 -> db.execSQL("INSERT INTO playback_cursor VALUES (1, 1, 9876)")
            11 -> db.execSQL("INSERT INTO playback_cursor VALUES (1, 1, 9876, 1, 2)")
            in 12..15 -> db.execSQL("INSERT INTO playback_cursor VALUES (1, 'slot-2', 9876, 1, 2)")
        }
        if (start >= 15) {
            db.execSQL("INSERT INTO pending_cleanups VALUES ('op-1', 's4', 'DELETE', '/music/s4.flac', NULL, 'Committed', 500)")
        }
    }

    private companion object {
        const val FIRST_START = 4
        /** Song columns that exist, unchanged, in every schema from v4 to v16. */
        val SONG_COLUMNS = listOf(
            "id", "title", "album_id", "duration", "file_path", "album_art_uri", "year",
            "track_number", "date_added", "is_missing", "missing_since",
        )
        val ALBUM_ID_INDEX = SONG_COLUMNS.indexOf("album_id")
        val PLAYLIST_COLUMNS = listOf(
            "id", "name", "description", "group_id", "position", "created_at", "updated_at", "cover_art_uri",
        )
        val UNCHANGED_TABLES = listOf("artists", "song_artists", "playlist_groups", "playlist_songs")
        val ORDER = mapOf(
            "artists" to "id",
            "song_artists" to "song_id, position",
            "playlist_groups" to "id",
            "playlist_songs" to "playlist_id, position",
        )
        val QUEUE_TABLES = listOf("queue_items", "queue_baseline_items")
    }
}
