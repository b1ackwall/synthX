package com.v1per.synthx.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.BackupSnapshotValidator
import com.v1per.synthx.data.db.MigrationTestSupport.albums
import com.v1per.synthx.data.db.MigrationTestSupport.columnNames
import com.v1per.synthx.data.db.MigrationTestSupport.foreignKeyViolations
import com.v1per.synthx.data.db.MigrationTestSupport.insertAlbum
import com.v1per.synthx.data.db.MigrationTestSupport.insertArtist
import com.v1per.synthx.data.db.MigrationTestSupport.insertSong
import com.v1per.synthx.data.db.MigrationTestSupport.linkArtist
import com.v1per.synthx.data.db.MigrationTestSupport.readRows
import com.v1per.synthx.data.db.MigrationTestSupport.snapshot
import com.v1per.synthx.data.db.MigrationTestSupport.songAlbums
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_15_16 folds albums whose titles match ignoring ASCII case and whose primary artist
 * (position-0 artist of the album's first track) matches. The survivor is the lowest id, songs
 * are repointed, and the survivor's empty year and art are filled from a duplicate. These cases
 * extend [Migration15To16Test]; pinned edge cases live in [Migration15To16PinnedBehaviourTest].
 */
@RunWith(AndroidJUnit4::class)
class Migration15To16ScenariosTest {
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
    fun threeOrMoreDuplicatesIncludingSameCaseFoldIntoTheLowestId() {
        val name = databaseName("many")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertArtist("two", "artist two")
            // Inserted out of id order so the lowest id is neither the first nor the last row.
            listOf("m" to "Night Drive", "z" to "night drive", "b" to "NIGHT DRIVE", "k" to "Night Drive")
                .forEach { (id, title) ->
                    db.insertAlbum(id, title)
                    db.insertSong("song-$id", id)
                    db.linkArtist("song-$id", "one")
                }
            db.insertAlbum("a-other", "Night Drive")
            db.insertSong("song-other", "a-other")
            db.linkArtist("song-other", "two")
        }
        migrate(name).use { db ->
            assertEquals(setOf("a-other", "b"), db.albums().keys)
            assertEquals("NIGHT DRIVE", db.albums().getValue("b").first)
            assertEquals(
                mapOf(
                    "song-b" to "b", "song-k" to "b", "song-m" to "b", "song-z" to "b",
                    "song-other" to "a-other",
                ),
                db.songAlbums(),
            )
            assertEquals(0, db.foreignKeyViolations())
        }
    }

    @Test
    fun survivorKeepsItsOwnYearAndArt() {
        val name = databaseName("own-year")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertAlbum("a-echo", "Echo", 1990, "content://own-art")
            db.insertAlbum("b-echo", "ECHO", 2001, "content://duplicate-art")
            db.insertSong("song-a", "a-echo")
            db.insertSong("song-b", "b-echo")
            db.linkArtist("song-a", "one")
            db.linkArtist("song-b", "one")
        }
        migrate(name).use { db ->
            assertEquals(mapOf("a-echo" to Triple("Echo", 1990, "content://own-art")), db.albums())
            assertEquals(mapOf("song-a" to "a-echo", "song-b" to "a-echo"), db.songAlbums())
        }
    }

    @Test
    fun survivorFillsEachMissingFieldFromTheOnlyDuplicateThatHasIt() {
        val name = databaseName("fill")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertAlbum("a-fill", "Fill", null, null)
            db.insertAlbum("b-fill", "FILL", 2005, null)
            db.insertAlbum("c-fill", "fill", null, "content://c-art")
            listOf("a", "b", "c").forEach {
                db.insertSong("song-$it", "$it-fill")
                db.linkArtist("song-$it", "one")
            }
        }
        migrate(name).use { db ->
            assertEquals(mapOf("a-fill" to Triple("Fill", 2005, "content://c-art")), db.albums())
        }
    }

    @Test
    fun primaryArtistIsThePositionZeroArtistOfTheFirstNumberedTrack() {
        val name = databaseName("first-track")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("art-a", "artist a")
            db.insertArtist("art-b", "artist b")
            // "Mix": track 2 by B, track 1 by A (B also at position 1), an unnumbered track by B.
            db.insertAlbum("x-mix", "Mix")
            db.insertSong("x2", "x-mix", trackNumber = 2)
            db.linkArtist("x2", "art-b")
            db.insertSong("x1", "x-mix", trackNumber = 1)
            db.linkArtist("x1", "art-a")
            db.linkArtist("x1", "art-b", position = 1)
            db.insertSong("x0", "x-mix", trackNumber = null)
            db.linkArtist("x0", "art-b")
            db.insertAlbum("y-mix", "MIX")
            db.insertSong("y1", "y-mix", trackNumber = 1)
            db.linkArtist("y1", "art-a")
            db.insertAlbum("z-mix", "mix")
            db.insertSong("z1", "z-mix", trackNumber = 1)
            db.linkArtist("z1", "art-b")
            // "Tape": an unnumbered track by B comes first in rowid order; track 5 by A wins.
            db.insertAlbum("p-tape", "Tape")
            db.insertSong("p0", "p-tape", trackNumber = null)
            db.linkArtist("p0", "art-b")
            db.insertSong("p5", "p-tape", trackNumber = 5)
            db.linkArtist("p5", "art-a")
            db.insertAlbum("q-tape", "TAPE")
            db.insertSong("q1", "q-tape", trackNumber = 1)
            db.linkArtist("q1", "art-a")
        }
        migrate(name).use { db ->
            assertEquals(setOf("p-tape", "x-mix", "z-mix"), db.albums().keys)
            assertEquals(
                mapOf(
                    "p0" to "p-tape", "p5" to "p-tape", "q1" to "p-tape",
                    "x0" to "x-mix", "x1" to "x-mix", "x2" to "x-mix", "y1" to "x-mix",
                    "z1" to "z-mix",
                ),
                db.songAlbums(),
            )
        }
    }

    @Test
    fun songsWithoutAnAlbumAndAlbumsWithoutDuplicatesAreUntouched() {
        val name = databaseName("untouched")
        val before = helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertAlbum("solo", "Solo Album", 1977, "content://solo")
            db.insertAlbum("other", "Solo Album Two", null, null)
            db.insertSong("in-solo", "solo", trackNumber = 3, year = 1977)
            db.insertSong("loose", null)
            db.linkArtist("in-solo", "one")
            db.linkArtist("loose", "one")
            db.readRows("songs", orderBy = "id") to db.readRows("albums", orderBy = "id")
        }
        migrate(name).use { db ->
            val songColumns = db.columnNames("songs")
            assertEquals(before.first, db.readRows("songs", songColumns, orderBy = "id"))
            assertEquals(before.second, db.readRows("albums", orderBy = "id"))
            assertEquals(null, db.songAlbums().getValue("loose"))
        }
    }

    @Test
    fun everyOtherTableAndEverySongColumnExceptMergedAlbumIdsIsUnchanged() {
        val name = databaseName("other-tables")
        val before = helper.createDatabase(name, 15).use { db ->
            seedFullLibrary(db)
            db.snapshot(OTHER_TABLES) to db.readRows("songs", orderBy = "id")
        }
        migrate(name).use { db ->
            OTHER_TABLES.forEach { table ->
                assertEquals("15->16 changed $table", before.first.getValue(table), db.readRows(table))
            }
            val albumIdIndex = db.columnNames("songs").indexOf("album_id")
            val merged = mapOf("b-dup" to "a-dup")
            val expectedSongs = before.second.map { row ->
                row.toMutableList().also { it[albumIdIndex] = merged[it[albumIdIndex]] ?: it[albumIdIndex] }
            }
            assertEquals("15->16 changed song rows beyond album_id", expectedSongs, db.readRows("songs", orderBy = "id"))
            assertEquals(setOf("a-dup", "single"), db.albums().keys)
            assertEquals(0, db.foreignKeyViolations())
        }
    }

    @Test
    fun standaloneBuilderUsedByRestoreRunsTheSameMerge() {
        val name = databaseName("standalone")
        helper.createDatabase(name, 15).use(::seedFullLibrary)
        val standalone = SynthXDatabase.createStandalone(context, name)
        try {
            standalone.openHelper.writableDatabase
        } finally {
            standalone.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(16, db.version)
            assertEquals(listOf("a-dup", "single"), db.rawQuery("SELECT id FROM albums ORDER BY id", null).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            })
            assertEquals(listOf("a-dup", "a-dup", "single"), db.rawQuery(
                "SELECT album_id FROM songs WHERE album_id IS NOT NULL ORDER BY id", null,
            ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } })
            assertEquals(4, db.rawQuery("SELECT COUNT(*) FROM playlist_songs", null).use { it.moveToFirst(); it.getInt(0) })
            val albumSql = db.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'albums'", null)
                .use { it.moveToFirst(); it.getString(0) }
            assertTrue(albumSql, albumSql.contains("COLLATE NOCASE", ignoreCase = true))
        }
        val validated = BackupSnapshotValidator.validate(context.getDatabasePath(name), migrationSnapshot = false)
        assertEquals(SynthXDatabase.VERSION, validated.schema.userVersion)
    }

    private fun migrate(name: String): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(name, 16, true, SynthXDatabase.MIGRATION_15_16)

    private fun databaseName(label: String) = "migration-15-16-scenario-$label-${UUID.randomUUID()}.db".also(names::add)

    /** Every v15 table populated: one merge pair, one single album, playlists, queue, cursor, cleanup. */
    private fun seedFullLibrary(db: SupportSQLiteDatabase) {
        db.insertArtist("one", "artist one")
        db.insertArtist("two", "artist two")
        db.insertAlbum("a-dup", "Duplicate", null, null)
        db.insertAlbum("b-dup", "DUPLICATE", 2010, "content://b-art")
        db.insertAlbum("single", "Single", 1999, "content://single-art")
        db.insertSong("s1", "a-dup", trackNumber = 1)
        db.insertSong("s2", "b-dup", trackNumber = 2)
        db.insertSong("s3", "single", trackNumber = 1)
        db.insertSong("s4", null)
        db.linkArtist("s1", "one")
        db.linkArtist("s2", "one")
        db.linkArtist("s2", "two", position = 1)
        db.linkArtist("s3", "two")
        db.linkArtist("s4", "two")
        db.execSQL("UPDATE songs SET in_inbox = 0, album_art_full_uri = 'file:///full/' || id WHERE id IN ('s1','s2')")
        db.execSQL("UPDATE songs SET swatch1_hue = 12.5, swatch3_lightness = 0.75 WHERE id = 's3'")
        db.execSQL("INSERT INTO playlist_groups VALUES ('g1', 'Group', 0, 100)")
        db.execSQL("INSERT INTO playlists VALUES ('p1', 'Mine', 'notes', 'g1', 0, 100, 200, NULL, 210, 9)")
        db.execSQL("INSERT INTO playlists VALUES ('p2', 'Loose', NULL, NULL, 1, 101, 201, 'cover', NULL, NULL)")
        db.execSQL("INSERT INTO playlist_songs VALUES ('p1', 's2', 0, 300)")
        db.execSQL("INSERT INTO playlist_songs VALUES ('p1', 's1', 1, 301)")
        db.execSQL("INSERT INTO playlist_songs VALUES ('p1', 's3', 2, 302)")
        db.execSQL("INSERT INTO playlist_songs VALUES ('p2', 's2', 0, 303)")
        db.execSQL("INSERT INTO queue_items VALUES ('slot-1', 0, 's2')")
        db.execSQL("INSERT INTO queue_items VALUES ('slot-2', 1, 's1')")
        db.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-2', 0, 's1')")
        db.execSQL("INSERT INTO queue_baseline_items VALUES ('slot-1', 1, 's2')")
        db.execSQL("INSERT INTO playback_cursor VALUES (1, 'slot-1', 4321, 1, 2)")
        db.execSQL("INSERT INTO pending_cleanups VALUES ('op-1', 's4', 'DELETE', '/music/s4.flac', NULL, 'Committed', 500)")
    }

    private companion object {
        val OTHER_TABLES = listOf(
            "artists", "song_artists", "playlist_groups", "playlists", "playlist_songs",
            "queue_items", "queue_baseline_items", "playback_cursor", "pending_cleanups",
        )
    }
}
