package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.MigrationTestSupport.albums
import com.v1per.synthx.data.db.MigrationTestSupport.insertAlbum
import com.v1per.synthx.data.db.MigrationTestSupport.insertArtist
import com.v1per.synthx.data.db.MigrationTestSupport.insertSong
import com.v1per.synthx.data.db.MigrationTestSupport.linkArtist
import com.v1per.synthx.data.db.MigrationTestSupport.singleInt
import com.v1per.synthx.data.db.MigrationTestSupport.songAlbums
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PINNED current behaviour of MIGRATION_15_16. These tests assert what the step does today,
 * not what it should do. Each one names its filed question; a deliberate change to the step
 * must update the matching test.
 */
@RunWith(AndroidJUnit4::class)
class Migration15To16PinnedBehaviourTest {
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

    /** PINNED (question "songless albums merge"): no position-0 artist means primary artist ''. */
    @Test
    fun pinnedSonglessAndArtistlessAlbumsMergeByTitleAlone() {
        val name = databaseName("songless")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertArtist("two", "artist two")
            db.insertAlbum("a-live", "Live", 2001, null)
            db.insertAlbum("b-live", "live", null, "content://b-art")
            // Has a song, but its only artist link is at position 1, so it also counts as ''.
            db.insertAlbum("d-live", "LIVE")
            db.insertSong("d-song", "d-live")
            db.linkArtist("d-song", "two", position = 1)
            // A real position-0 artist keeps this one apart.
            db.insertAlbum("c-live", "Live")
            db.insertSong("c-song", "c-live")
            db.linkArtist("c-song", "one")
        }
        migrate(name).use { db ->
            assertEquals(
                mapOf(
                    "a-live" to Triple("Live", 2001, "content://b-art"),
                    "c-live" to Triple("Live", null, null),
                ),
                db.albums(),
            )
            assertEquals(mapOf("c-song" to "c-live", "d-song" to "a-live"), db.songAlbums())
        }
    }

    /**
     * PINNED (suspected "LIMIT 1 ordering"): with two duplicates that both have a year, the
     * survivor's year comes from whichever duplicate SQLite reaches first, not from a rule.
     * The same albums inserted in the opposite order give the survivor the other year.
     */
    @Test
    fun pinnedYearDonorDependsOnRowOrderWhenSeveralDuplicatesHaveOne() {
        val forward = mergedYear(listOf("b-dup" to 2001, "c-dup" to 2005))
        val reverse = mergedYear(listOf("c-dup" to 2005, "b-dup" to 2001))
        assertEquals("forward insertion order donor", 2001, forward)
        assertEquals("reverse insertion order donor", 2005, reverse)
    }

    /** PINNED (filed "foreign keys off"): Room runs the step with foreign keys off. */
    @Test
    fun pinnedRoomRunsTheStepWithForeignKeysOffAndAlbumLinksSurvive() {
        val name = databaseName("fk-room")
        helper.createDatabase(name, 15).use(::seedLinkedSongs)
        var foreignKeysDuringMigration = -1
        val spy = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                foreignKeysDuringMigration = db.singleInt("PRAGMA foreign_keys")
                SynthXDatabase.MIGRATION_15_16.migrate(db)
            }
        }
        val room = Room.databaseBuilder(context, SynthXDatabase::class.java, name).addMigrations(spy).build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals("foreign_keys during 15->16", 0, foreignKeysDuringMigration)
            assertEquals("foreign_keys after open", 1, db.singleInt("PRAGMA foreign_keys"))
            assertEquals(mapOf("s1" to "a-one", "s2" to "a-one", "s3" to "c-two"), db.songAlbums())
        } finally {
            room.close()
        }
    }

    /** PINNED (filed "foreign keys off"): the same step with foreign keys on unlinks every song. */
    @Test
    fun pinnedStepWithForeignKeysOnWouldUnlinkEverySongFromItsAlbum() {
        val name = databaseName("fk-on")
        helper.createDatabase(name, 15).use(::seedLinkedSongs)
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openHelper.use { helperForFile ->
            val db = helperForFile.writableDatabase
            db.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(1, db.singleInt("PRAGMA foreign_keys"))
            db.beginTransaction()
            try {
                SynthXDatabase.MIGRATION_15_16.migrate(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertEquals(mapOf("s1" to null, "s2" to null, "s3" to null), db.songAlbums())
            assertEquals(2, db.singleInt("SELECT COUNT(*) FROM albums"))
        }
    }

    private fun mergedYear(duplicates: List<Pair<String, Int>>): Int? {
        val name = databaseName("donor")
        helper.createDatabase(name, 15).use { db ->
            db.insertArtist("one", "artist one")
            db.insertAlbum("a-dup", "Donor", null, null)
            db.insertSong("song-a", "a-dup")
            db.linkArtist("song-a", "one")
            duplicates.forEach { (id, year) ->
                db.insertAlbum(id, "DONOR", year, null)
                db.insertSong("song-$id", id)
                db.linkArtist("song-$id", "one")
            }
        }
        return migrate(name).use { db ->
            assertEquals(setOf("a-dup"), db.albums().keys)
            db.albums().getValue("a-dup").second
        }
    }

    private fun seedLinkedSongs(db: SupportSQLiteDatabase) {
        db.insertArtist("one", "artist one")
        db.insertArtist("two", "artist two")
        db.insertAlbum("a-one", "Linked")
        db.insertAlbum("b-one", "LINKED")
        db.insertAlbum("c-two", "Other")
        db.insertSong("s1", "a-one")
        db.insertSong("s2", "b-one")
        db.insertSong("s3", "c-two")
        db.linkArtist("s1", "one")
        db.linkArtist("s2", "one")
        db.linkArtist("s3", "two")
    }

    private fun migrate(name: String): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(name, 16, true, SynthXDatabase.MIGRATION_15_16)

    private fun databaseName(label: String) = "migration-15-16-pinned-$label-${UUID.randomUUID()}.db".also(names::add)
}
