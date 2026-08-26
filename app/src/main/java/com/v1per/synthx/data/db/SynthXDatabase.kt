package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        SongArtistEntity::class,
        PlaylistGroupEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueItemEntity::class,
        BaselineQueueItemEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class SynthXDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songArtistDao(): SongArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao

    companion object {
        /** Must track the `version` in the @Database annotation above. */
        const val VERSION = 9

        const val DB_NAME = "synthx.db"

        @Volatile private var INSTANCE: SynthXDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN track_number INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Wipe all song-derived data — hash strategy changed, full rescan required
                database.execSQL("DELETE FROM listen_history")
                database.execSQL("DELETE FROM playlist_songs")
                database.execSQL("DELETE FROM song_artists")
                database.execSQL("DELETE FROM songs")
                database.execSQL("DELETE FROM artists")
                database.execSQL("DELETE FROM albums")

                // Add missing indices
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_artists_name_normalized " +
                        "ON artists(name_normalized)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_albums_title ON albums(title)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Artist splitting delimiter changed — wipe song/artist data for full rescan
                database.execSQL("DELETE FROM listen_history")
                database.execSQL("DELETE FROM playlist_songs")
                database.execSQL("DELETE FROM song_artists")
                database.execSQL("DELETE FROM songs")
                database.execSQL("DELETE FROM artists")
                database.execSQL("DELETE FROM albums")
            }
        }

        // Purely additive: queue_items is new, so there is nothing to migrate into it and
        // no reason to wipe song data the way 2_3 and 3_4 had to.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queue_items (
                        position INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        PRIMARY KEY(position),
                        FOREIGN KEY(song_id) REFERENCES songs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queue_items_song_id ON queue_items(song_id)"
                )
            }
        }

        // Purely additive, same shape as 4_5: queue_baseline_items is new, app-managed
        // ordering state with nothing to migrate into it.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queue_baseline_items (
                        position INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        PRIMARY KEY(position),
                        FOREIGN KEY(song_id) REFERENCES songs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queue_baseline_items_song_id " +
                        "ON queue_baseline_items(song_id)"
                )
            }
        }

        // Drops dead schema, confirmed via audit to have zero writers/readers anywhere in
        // the app: listen_history (whole table — ListenHistoryDao was always an empty
        // interface, never written to) and songs.verified/verified_at (declared,
        // queried once via the never-called getUnverifiedSongs, never written).
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS listen_history")

                // SQLite's ALTER TABLE DROP COLUMN needs 3.35+, not guaranteed at this
                // app's minSdk — rebuild the table via the standard create/copy/drop/rename
                // pattern instead. Room disables FK enforcement while migrations run, so
                // the other tables' FOREIGN KEY(song_id) REFERENCES songs(id) don't block
                // dropping/recreating this one.
                database.execSQL(
                    """
                    CREATE TABLE songs_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        album_id TEXT,
                        duration INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        album_art_uri TEXT,
                        year INTEGER,
                        track_number INTEGER,
                        date_added INTEGER NOT NULL,
                        is_missing INTEGER NOT NULL DEFAULT 0,
                        missing_since INTEGER,
                        FOREIGN KEY(album_id) REFERENCES albums(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO songs_new (
                        id, title, album_id, duration, file_path, album_art_uri, year,
                        track_number, date_added, is_missing, missing_since
                    )
                    SELECT
                        id, title, album_id, duration, file_path, album_art_uri, year,
                        track_number, date_added, is_missing, missing_since
                    FROM songs
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE songs")
                database.execSQL("ALTER TABLE songs_new RENAME TO songs")

                // Dropping the old table drops its indices too — recreate them.
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_album_id ON songs(album_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)"
                )
            }
        }

        // Adds songs.in_inbox, backing the Starred screen ("songs not yet organized into a
        // playlist"). Additive, so none of 2_3/3_4's wipe-and-rescan is warranted — and
        // unlike 6_7, no table rebuild either: it was DROP COLUMN that needed SQLite 3.35+,
        // while ADD COLUMN with a NOT NULL DEFAULT works at this app's minSdk.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Default 1 (starred) is the right answer for a *new* song, and must match
                // SongEntity's @ColumnInfo(defaultValue = "1") exactly or Room's open-time
                // schema validation throws.
                database.execSQL(
                    "ALTER TABLE songs ADD COLUMN in_inbox INTEGER NOT NULL DEFAULT 1"
                )

                // ...but an *existing* library has already been organized, so backfill the
                // songs that are in at least one playlist to 0. This is the first migration
                // here to compute a column from another table; it stays a single set-based
                // UPDATE rather than a per-row loop, and playlist_songs already carries
                // Index("song_id") (see PlaylistSongEntity), so the correlated EXISTS is
                // index-backed and costs milliseconds even on a multi-thousand-song library.
                database.execSQL(
                    """
                    UPDATE songs SET in_inbox = 0
                    WHERE EXISTS (
                        SELECT 1 FROM playlist_songs WHERE playlist_songs.song_id = songs.id
                    )
                    """.trimIndent()
                )
            }
        }

        // Adds songs.album_art_full_uri, holding the untouched-original-bytes full-resolution
        // art copy alongside the existing compressed album_art_uri. Additive, same shape as
        // 7_8's ADD COLUMN — no backfill possible since the original bytes for already-scanned
        // songs were never retained, so existing rows simply keep this column NULL until a
        // fresh scan re-extracts art for them.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Default NULL must match SongEntity's @ColumnInfo(defaultValue = "NULL")
                // exactly or Room's open-time schema validation throws.
                database.execSQL(
                    "ALTER TABLE songs ADD COLUMN album_art_full_uri TEXT DEFAULT NULL"
                )
            }
        }

        fun getInstance(context: Context): SynthXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SynthXDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Closes the database and drops the singleton so the next [getInstance] rebuilds it.
         *
         * Only safe when nothing else is going to touch the old handle — every repository
         * and ViewModel built before this call still holds DAOs bound to the closed
         * instance and will throw on use. The restore flow therefore terminates the
         * process immediately afterwards rather than trying to keep the session alive.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
