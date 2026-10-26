package com.v1per.synthx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreStartup

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
        PlaybackCursorEntity::class,
        PendingCleanupEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class SynthXDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songArtistDao(): SongArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun pendingCleanupDao(): PendingCleanupDao

    companion object {
        /** Must track the `version` in the @Database annotation above. */
        const val VERSION = 16

        const val DB_NAME = "synthx.db"

        @Volatile private var INSTANCE: SynthXDatabase? = null

        // MIGRATION_1_2 through MIGRATION_7_8 live in SynthXLegacyMigrations.kt.

        // Adds songs.album_art_full_uri, holding the untouched-original-bytes full-resolution
        // art copy alongside the existing compressed album_art_uri. Additive, same shape as
        // 7_8's ADD COLUMN — no backfill possible since the original bytes for already-scanned
        // songs were never retained, so existing rows simply keep this column NULL until a
        // fresh scan re-extracts art for them.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Default NULL must match SongEntity's @ColumnInfo(defaultValue = "NULL")
                // exactly or Room's open-time schema validation throws.
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN album_art_full_uri TEXT DEFAULT NULL",
                )
            }
        }

        // Adds the single-row playback_cursor table (see PlaybackCursorEntity's kdoc) so the
        // resume cursor can be committed in the same transaction as queue_items instead of
        // living independently in SharedPreferences. Purely additive, same shape as 4_5/5_6:
        // nothing to migrate into a brand-new table.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_cursor (
                        id INTEGER NOT NULL,
                        queue_index INTEGER NOT NULL,
                        position_ms INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        // Adds shuffle_enabled/repeat_mode to the single-row playback_cursor table (see
        // PlaybackCursorEntity's kdoc): both previously lived in SharedPreferences,
        // which a restore never touches, so a restored queue could end up paired with a
        // shuffle/repeat mode left over from the pre-restore install. Additive ADD COLUMN,
        // same shape as 7_8/8_9 — defaults must match PlaybackCursorEntity's
        // @ColumnInfo(defaultValue = ...) exactly or Room's open-time schema validation throws.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playback_cursor ADD COLUMN shuffle_enabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE playback_cursor ADD COLUMN repeat_mode INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        // Queue occurrences gain their own stable identity. There is no meaningful value to
        // backfill for old rows because song_id identifies content, not an occurrence, so the
        // two transient queue-order tables are deliberately recreated empty. The cursor table
        // is rebuilt to replace its ambiguous raw queue index with an exact nullable slot id;
        // playback position and the independent shuffle/repeat preferences are retained.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS queue_items")
                db.execSQL("DROP TABLE IF EXISTS queue_baseline_items")

                db.execSQL(
                    """
                    CREATE TABLE queue_items (
                        slot_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        PRIMARY KEY(slot_id),
                        FOREIGN KEY(song_id) REFERENCES songs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX index_queue_items_position ON queue_items(position)",
                )
                db.execSQL(
                    "CREATE INDEX index_queue_items_song_id ON queue_items(song_id)",
                )

                db.execSQL(
                    """
                    CREATE TABLE queue_baseline_items (
                        slot_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        PRIMARY KEY(slot_id),
                        FOREIGN KEY(song_id) REFERENCES songs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX index_queue_baseline_items_position " +
                        "ON queue_baseline_items(position)",
                )
                db.execSQL(
                    "CREATE INDEX index_queue_baseline_items_song_id " +
                        "ON queue_baseline_items(song_id)",
                )

                db.execSQL(
                    """
                    CREATE TABLE playback_cursor_new (
                        id INTEGER NOT NULL,
                        current_slot_id TEXT,
                        position_ms INTEGER NOT NULL,
                        shuffle_enabled INTEGER NOT NULL DEFAULT 0,
                        repeat_mode INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO playback_cursor_new (
                        id, current_slot_id, position_ms, shuffle_enabled, repeat_mode
                    )
                    SELECT id, NULL, position_ms, shuffle_enabled, repeat_mode
                    FROM playback_cursor
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE playback_cursor")
                db.execSQL("ALTER TABLE playback_cursor_new RENAME TO playback_cursor")
            }
        }

        // Raw artwork swatches only. Nothing in the app writes or reads these columns, so
        // their values stay unused (see the note above swatch1_hue in SongEntity).
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch1_hue REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch1_saturation REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch1_lightness REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch2_hue REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch2_saturation REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch2_lightness REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch3_hue REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch3_saturation REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN swatch3_lightness REAL DEFAULT NULL",
                )
            }
        }

        // Playlist art hue + structure seed. Existing rows stay NULL until the startup backfill routine runs.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlists ADD COLUMN art_hue INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE playlists ADD COLUMN art_structure_seed INTEGER DEFAULT NULL",
                )
            }
        }

        // Adds pending_cleanups (see PendingCleanupEntity's kdoc): a durable record of a
        // destructive operation's post-commit physical cleanup, so an interruption between the
        // DB commit and file cleanup (delete/replace) or between source deletion and the DB
        // commit (graduation) can be reconciled on next launch. Purely additive, same shape as
        // 4_5/5_6/9_10: a brand-new table with nothing to migrate into it. Deliberately no
        // foreign key to songs — see the entity's kdoc for why.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_cleanups (
                        operation_id TEXT NOT NULL PRIMARY KEY,
                        song_id TEXT NOT NULL,
                        operation_type TEXT NOT NULL,
                        file_path TEXT,
                        published_path TEXT,
                        phase TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Makes album identity's case-insensitive title comparison index-backed. Before the
         * rebuild, confidently equivalent rows (same NOCASE title and derived primary artist)
         * are folded into the lexicographically first id and every song is repointed first.
         * A survivor missing its year or art takes it from the lowest-id duplicate that has one.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuilding albums with foreign keys on would set every songs.album_id to NULL.
                // Room runs migrations with them off; a caller that did not fails here, not silently.
                val foreignKeysOn = db.query("PRAGMA foreign_keys").use { it.moveToFirst() && it.getInt(0) == 1 }
                check(!foreignKeysOn) {
                    "MIGRATION_15_16 must run with PRAGMA foreign_keys = OFF; refusing to rebuild albums " +
                        "because that would set songs.album_id to NULL for every song"
                }
                db.execSQL(
                    """
                    CREATE TEMP TABLE album_case_identity AS
                    SELECT albums.id, albums.title,
                        COALESCE((
                            SELECT artists.name_normalized
                            FROM songs
                            INNER JOIN song_artists ON song_artists.song_id = songs.id
                                AND song_artists.position = 0
                            INNER JOIN artists ON artists.id = song_artists.artist_id
                            WHERE songs.album_id = albums.id
                            ORDER BY songs.track_number IS NULL, songs.track_number, songs.rowid
                            LIMIT 1
                        ), '') AS primary_artist
                    FROM albums
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TEMP TABLE album_case_merges AS
                    SELECT identity.id AS duplicate_id, grouped.survivor_id
                    FROM album_case_identity AS identity
                    INNER JOIN (
                        SELECT title COLLATE NOCASE AS title_key, primary_artist, MIN(id) AS survivor_id
                        FROM album_case_identity
                        GROUP BY title COLLATE NOCASE, primary_artist
                        HAVING COUNT(*) > 1
                    ) AS grouped
                    ON identity.title = grouped.title_key COLLATE NOCASE
                        AND identity.primary_artist = grouped.primary_artist
                    WHERE identity.id != grouped.survivor_id
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE songs SET album_id = (
                        SELECT survivor_id FROM album_case_merges
                        WHERE duplicate_id = songs.album_id
                    )
                    WHERE album_id IN (SELECT duplicate_id FROM album_case_merges)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE albums SET
                        year = COALESCE(year, (
                            SELECT duplicate.year FROM albums AS duplicate
                            INNER JOIN album_case_merges AS merge
                                ON merge.duplicate_id = duplicate.id
                            WHERE merge.survivor_id = albums.id AND duplicate.year IS NOT NULL
                            ORDER BY duplicate.id
                            LIMIT 1
                        )),
                        album_art_uri = COALESCE(album_art_uri, (
                            SELECT duplicate.album_art_uri FROM albums AS duplicate
                            INNER JOIN album_case_merges AS merge
                                ON merge.duplicate_id = duplicate.id
                            WHERE merge.survivor_id = albums.id
                                AND duplicate.album_art_uri IS NOT NULL
                            ORDER BY duplicate.id
                            LIMIT 1
                        ))
                    WHERE id IN (SELECT survivor_id FROM album_case_merges)
                    """.trimIndent(),
                )
                db.execSQL(
                    "DELETE FROM albums WHERE id IN (SELECT duplicate_id FROM album_case_merges)",
                )
                db.execSQL("DROP TABLE album_case_merges")
                db.execSQL("DROP TABLE album_case_identity")

                db.execSQL(
                    """
                    CREATE TABLE albums_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT COLLATE NOCASE NOT NULL,
                        year INTEGER,
                        album_art_uri TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO albums_new (id, title, year, album_art_uri) " +
                        "SELECT id, title, year, album_art_uri FROM albums",
                )
                db.execSQL("DROP TABLE albums")
                db.execSQL("ALTER TABLE albums_new RENAME TO albums")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_albums_title ON albums(title)",
                )
            }
        }

        /**
         * The canonical migration chain, in order. Single source of truth for every Room
         * builder in this file — the live singleton in [getOrCreate] and the offline
         * restore candidate in [createStandalone] must migrate identically, so neither
         * builds its own list. Append new migrations here and nowhere else.
         *
         * MIGRATION_2_3 and MIGRATION_3_4 wiped song-derived data for hash-strategy and
         * artist-splitting changes before this app held real user data. MIGRATION_6_7 drops
         * the listen_history table and the two never-written songs columns verified and
         * verified_at. MIGRATION_11_12 only
         * wipes the two transient queue-order tables because old rows have no stable slot id;
         * it preserves the library and playlist data. MIGRATION_15_16 merges albums whose
         * titles match ignoring case and that share a derived primary artist: it repoints their
         * songs to the surviving row, fills that row's missing year or art from a duplicate,
         * then deletes the duplicate album rows. Destructive treatment must remain an explicit
         * exception going forward: this app manages a large library built from years of hand
         * curation, with no external backup of future changes beyond what this app itself
         * provides. A migration that deletes or overwrites existing data with no backfill
         * path is an exceptional, deliberately and carefully reviewed decision — never
         * routine practice.
         * See CLAUDE.md's Architecture section for the full pre-migration checklist.
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16,
        )

        fun getInstance(context: Context): SynthXDatabase {
            // Fail closed synchronously. Callers must asynchronously await DatabaseStartupGate;
            // this factory never blocks a main thread on SAF and never opens Room prematurely.
            DatabaseStartupGate.requireReady()
            RestoreStartup.requireNormal()
            return getOrCreate(context)
        }

        /**
         * The startup gate's own access, used to force a pending migration to actually run (and
         * be validated) before [DatabaseStartupGate] grants process-wide readiness. Deliberately
         * skips [DatabaseStartupGate.requireReady] — calling it here, from inside the gate that
         * readiness depends on, would always throw.
         */
        internal fun getInstanceForMigrationGate(context: Context): SynthXDatabase {
            RestoreStartup.requireNormal()
            return getOrCreate(context)
        }

        /** Restore-owned access after normal admission has closed but before Room is closed. */
        internal fun getInstanceForRestore(context: Context): SynthXDatabase {
            DatabaseStartupGate.requireReady()
            RestoreStartup.await()
            check(
                com.v1per.synthx.data.backup.RestoreCoordinator.currentMode() ==
                    com.v1per.synthx.data.backup.RestoreCoordinator.Mode.RESTORE_PREPARING,
            ) { "Restore-internal Room access requires restore preparation ownership" }
            return getOrCreate(context)
        }

        private fun getOrCreate(context: Context): SynthXDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                SynthXDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { INSTANCE = it }
        }

        /** Builds an isolated Room instance for an offline restore candidate, never the singleton. */
        internal fun createStandalone(context: Context, databaseName: String): SynthXDatabase {
            require(databaseName != DB_NAME) {
                "Standalone Room instances cannot target the live database name"
            }
            return Room.databaseBuilder(
                context.applicationContext,
                SynthXDatabase::class.java,
                databaseName,
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
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
