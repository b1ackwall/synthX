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
    version = 7,
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
        const val VERSION = 7

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

        fun getInstance(context: Context): SynthXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SynthXDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7,
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
