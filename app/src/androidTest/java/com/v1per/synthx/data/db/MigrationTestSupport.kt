package com.v1per.synthx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Raw-SQL helpers shared by the slice 4 migration tests. They read tables by name through
 * `PRAGMA table_info`, so they follow the exported schemas rather than the Kotlin entities.
 * Older migration test files keep their own private copies.
 */
internal object MigrationTestSupport {
    /** Column names of [table], in table order. */
    fun SupportSQLiteDatabase.columnNames(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    /** Every row of [table] as text, ordered by [orderBy]. */
    fun SupportSQLiteDatabase.readRows(
        table: String,
        columns: List<String> = columnNames(table),
        orderBy: String = "rowid",
    ): List<List<String?>> =
        query("SELECT ${columns.joinToString()} FROM $table ORDER BY $orderBy").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    /** Full contents of each table, keyed by table name, with a stable order on [orderBy]. */
    fun SupportSQLiteDatabase.snapshot(tables: List<String>, orderBy: String = "rowid") =
        tables.associateWith { table -> readRows(table, orderBy = orderBy) }

    fun SupportSQLiteDatabase.tableNames(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    fun SupportSQLiteDatabase.singleInt(sql: String, vararg args: Any?): Int =
        query(sql, args).use { check(it.moveToFirst()) { "No row for: $sql" }; it.getInt(0) }

    /** Single text column of every row; NULL comes back as null. */
    fun SupportSQLiteDatabase.strings(sql: String, vararg args: Any?): List<String?> =
        query(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(if (cursor.isNull(0)) null else cursor.getString(0)) }
        }

    /** `id -> album_id` for every song. */
    fun SupportSQLiteDatabase.songAlbums(): Map<String, String?> =
        query("SELECT id, album_id FROM songs ORDER BY id").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1))
            }
        }

    /** `id -> (title, year, album_art_uri)` for every album. */
    fun SupportSQLiteDatabase.albums(): Map<String, Triple<String, Int?, String?>> =
        query("SELECT id, title, year, album_art_uri FROM albums ORDER BY id").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        Triple(
                            cursor.getString(1),
                            if (cursor.isNull(2)) null else cursor.getInt(2),
                            if (cursor.isNull(3)) null else cursor.getString(3),
                        ),
                    )
                }
            }
        }

    fun SupportSQLiteDatabase.foreignKeyViolations(): Int =
        query("PRAGMA foreign_key_check").use { it.count }

    /** Inserts a song using only columns that exist, unchanged, in every schema from v2 to v16. */
    fun SupportSQLiteDatabase.insertSong(
        id: String,
        albumId: String?,
        trackNumber: Int? = null,
        year: Int? = null,
    ) {
        execSQL(
            "INSERT INTO songs (id, title, album_id, duration, file_path, album_art_uri, year, " +
                "track_number, date_added, is_missing, missing_since) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, "Title $id", albumId, 180_000L, "/music/$id.flac", "file:///art/$id.jpg",
                year, trackNumber, 1_700_000_000_000L, 0, null,
            ),
        )
    }

    fun SupportSQLiteDatabase.insertAlbum(id: String, title: String, year: Int? = null, art: String? = null) {
        execSQL(
            "INSERT INTO albums (id, title, year, album_art_uri) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(id, title, year, art),
        )
    }

    fun SupportSQLiteDatabase.insertArtist(id: String, normalized: String) {
        execSQL(
            "INSERT INTO artists (id, name, name_normalized) VALUES (?, ?, ?)",
            arrayOf<Any?>(id, normalized.uppercase(), normalized),
        )
    }

    fun SupportSQLiteDatabase.linkArtist(songId: String, artistId: String, position: Int = 0) {
        execSQL(
            "INSERT INTO song_artists (song_id, artist_id, position) VALUES (?, ?, ?)",
            arrayOf<Any?>(songId, artistId, position),
        )
    }
}
