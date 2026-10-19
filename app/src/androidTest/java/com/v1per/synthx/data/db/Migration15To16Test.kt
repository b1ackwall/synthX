package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {
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

    @Test
    fun migrationUsesNoCaseIndexAndSafelyFoldsExistingCaseDuplicates() {
        val name = "migration-15-16-${UUID.randomUUID()}.db".also(names::add)
        helper.createDatabase(name, 15).use(::seed)
        helper.runMigrationsAndValidate(
            name,
            16,
            true,
            SynthXDatabase.MIGRATION_15_16,
        ).use { database ->
            val albumSql = database.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='albums'",
            ).use {
                it.moveToFirst()
                it.getString(0)
            }
            assertTrue(albumSql.contains("COLLATE NOCASE", ignoreCase = true))

            assertEquals(2, database.singleInt("SELECT COUNT(*) FROM albums"))
            assertEquals(
                listOf("album-a", "album-a", "album-c"),
                database.query("SELECT album_id FROM songs ORDER BY id").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                },
            )
            database.query("SELECT year, album_art_uri FROM albums WHERE id='album-a'").use {
                assertTrue(it.moveToFirst())
                assertEquals(2001, it.getInt(0))
                assertEquals("content://duplicate-art", it.getString(1))
            }
            assertEquals(0, database.query("PRAGMA foreign_key_check").use { it.count })

            val plan = database.query(
                SimpleSQLiteQuery(
                    """
                    EXPLAIN QUERY PLAN
                    SELECT albums.* FROM albums
                    WHERE albums.title = ? COLLATE NOCASE
                    AND EXISTS (
                        SELECT 1 FROM songs
                        INNER JOIN song_artists ON song_artists.song_id = songs.id
                            AND song_artists.position = 0
                        INNER JOIN artists ON artists.id = song_artists.artist_id
                        WHERE songs.album_id = albums.id AND artists.name_normalized = ?
                    ) LIMIT 1
                    """.trimIndent(),
                    arrayOf("CASE ALBUM", "artist one"),
                ),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
            assertTrue(
                "Query plan did not use title index: $plan",
                plan.any {
                    it.contains("index_albums_title", ignoreCase = true)
                },
            )
            assertFalse(
                "Query plan still scans albums: $plan",
                plan.any {
                    it.contains("SCAN albums", ignoreCase = true)
                },
            )
        }
    }

    private fun seed(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO artists VALUES ('artist-1','Artist One','artist one')")
        database.execSQL("INSERT INTO artists VALUES ('artist-2','Artist Two','artist two')")
        database.execSQL("INSERT INTO albums VALUES ('album-a','Case Album',NULL,NULL)")
        database.execSQL("INSERT INTO albums VALUES ('album-b','CASE ALBUM',2001,'content://duplicate-art')")
        database.execSQL("INSERT INTO albums VALUES ('album-c','case album',1999,NULL)")
        insertSong(database, "song-a", "album-a", "/a.mp3")
        insertSong(database, "song-b", "album-b", "/b.mp3")
        insertSong(database, "song-c", "album-c", "/c.mp3")
        database.execSQL("INSERT INTO song_artists VALUES ('song-a','artist-1',0)")
        database.execSQL("INSERT INTO song_artists VALUES ('song-b','artist-1',0)")
        database.execSQL("INSERT INTO song_artists VALUES ('song-c','artist-2',0)")
    }

    private fun insertSong(database: SupportSQLiteDatabase, id: String, albumId: String, path: String) {
        database.execSQL(
            "INSERT INTO songs (id,title,album_id,duration,file_path,date_added) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(id, id, albumId, 1L, path, 100L),
        )
    }

    private fun SupportSQLiteDatabase.singleInt(sql: String): Int = query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }
}
