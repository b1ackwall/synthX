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
        ListenHistoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SynthXDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songArtistDao(): SongArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun listenHistoryDao(): ListenHistoryDao

    companion object {
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

        fun getInstance(context: Context): SynthXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SynthXDatabase::class.java,
                    "synthx.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
