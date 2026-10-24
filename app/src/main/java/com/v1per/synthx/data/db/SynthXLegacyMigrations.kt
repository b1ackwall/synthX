package com.v1per.synthx.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Frozen legacy migrations, reached only through SynthXDatabase.ALL_MIGRATIONS.

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN track_number INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Wipe all song-derived data — hash strategy changed, full rescan required
        db.execSQL("DELETE FROM listen_history")
        db.execSQL("DELETE FROM playlist_songs")
        db.execSQL("DELETE FROM song_artists")
        db.execSQL("DELETE FROM songs")
        db.execSQL("DELETE FROM artists")
        db.execSQL("DELETE FROM albums")

        // Add missing indices
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_artists_name_normalized " +
                "ON artists(name_normalized)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_albums_title ON albums(title)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Artist splitting delimiter changed — wipe song/artist data for full rescan
        db.execSQL("DELETE FROM listen_history")
        db.execSQL("DELETE FROM playlist_songs")
        db.execSQL("DELETE FROM song_artists")
        db.execSQL("DELETE FROM songs")
        db.execSQL("DELETE FROM artists")
        db.execSQL("DELETE FROM albums")
    }
}

// Purely additive: queue_items is new, so there is nothing to migrate into it and
// no reason to wipe song data the way 2_3 and 3_4 had to.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queue_items (
                position INTEGER NOT NULL,
                song_id TEXT NOT NULL,
                PRIMARY KEY(position),
                FOREIGN KEY(song_id) REFERENCES songs(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_queue_items_song_id ON queue_items(song_id)",
        )
    }
}

// Purely additive, same shape as 4_5: queue_baseline_items is new, app-managed
// ordering state with nothing to migrate into it.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queue_baseline_items (
                position INTEGER NOT NULL,
                song_id TEXT NOT NULL,
                PRIMARY KEY(position),
                FOREIGN KEY(song_id) REFERENCES songs(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_queue_baseline_items_song_id " +
                "ON queue_baseline_items(song_id)",
        )
    }
}

// Drops dead schema that nothing in the app writes or reads: listen_history (whole table)
// and songs.verified/verified_at.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS listen_history")

        // SQLite's ALTER TABLE DROP COLUMN needs 3.35+, not guaranteed at this
        // app's minSdk — rebuild the table via the standard create/copy/drop/rename
        // pattern instead. Room disables FK enforcement while migrations run, so
        // the other tables' FOREIGN KEY(song_id) REFERENCES songs(id) don't block
        // dropping/recreating this one.
        db.execSQL(
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
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO songs_new (
                id, title, album_id, duration, file_path, album_art_uri, year,
                track_number, date_added, is_missing, missing_since
            )
            SELECT
                id, title, album_id, duration, file_path, album_art_uri, year,
                track_number, date_added, is_missing, missing_since
            FROM songs
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE songs")
        db.execSQL("ALTER TABLE songs_new RENAME TO songs")

        // Dropping the old table drops its indices too — recreate them.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_songs_album_id ON songs(album_id)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)",
        )
    }
}

// Adds songs.in_inbox, backing the Starred screen ("songs not yet organized into a
// playlist"). Additive, so none of 2_3/3_4's wipe-and-rescan is warranted — and
// unlike 6_7, no table rebuild either: it was DROP COLUMN that needed SQLite 3.35+,
// while ADD COLUMN with a NOT NULL DEFAULT works at this app's minSdk.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Default 1 (starred) is the right answer for a *new* song, and must match
        // SongEntity's @ColumnInfo(defaultValue = "1") exactly or Room's open-time
        // schema validation throws.
        db.execSQL(
            "ALTER TABLE songs ADD COLUMN in_inbox INTEGER NOT NULL DEFAULT 1",
        )

        // ...but an *existing* library has already been organized, so backfill the
        // songs that are in at least one playlist to 0. This is the first migration
        // here to compute a column from another table; it stays a single set-based
        // UPDATE rather than a per-row loop, and playlist_songs already carries
        // Index("song_id") (see PlaylistSongEntity), so the correlated EXISTS is
        // index-backed and costs milliseconds even on a multi-thousand-song library.
        db.execSQL(
            """
            UPDATE songs SET in_inbox = 0
            WHERE EXISTS (
                SELECT 1 FROM playlist_songs WHERE playlist_songs.song_id = songs.id
            )
            """.trimIndent(),
        )
    }
}
