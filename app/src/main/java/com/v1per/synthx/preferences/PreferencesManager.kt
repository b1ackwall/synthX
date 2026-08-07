package com.v1per.synthx.preferences

import android.content.Context

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var musicFolderUri: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_MUSIC_FOLDER_URI, value).apply()

    /** Separate tree from [musicFolderUri] — held with write access, unlike the music folder. */
    var backupFolderUri: String?
        get() = prefs.getString(KEY_BACKUP_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_BACKUP_FOLDER_URI, value).apply()

    /** Schema version seen on the previous launch. 0 means "never recorded" (fresh install). */
    var lastKnownDbVersion: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_DB_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_DB_VERSION, value).apply()

    /** Epoch millis of the last successful Daily backup. 0 means none taken yet. */
    var lastDailyBackupTimestamp: Long
        get() = prefs.getLong(KEY_LAST_DAILY_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DAILY_BACKUP, value).apply()

    /** Queue shuffle state, restored on launch alongside the queue itself. */
    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()

    /** Stored as media3's Player.REPEAT_MODE_* int; 0 (REPEAT_MODE_OFF) is the default. */
    var repeatMode: Int
        get() = prefs.getInt(KEY_REPEAT_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_REPEAT_MODE, value).apply()

    /** Index into the persisted queue of the item that was playing. -1 means none. */
    var currentQueueIndex: Int
        get() = prefs.getInt(KEY_CURRENT_QUEUE_INDEX, -1)
        set(value) = prefs.edit().putInt(KEY_CURRENT_QUEUE_INDEX, value).apply()

    /** Playback position within the item at [currentQueueIndex]. */
    var currentPositionMs: Long
        get() = prefs.getLong(KEY_CURRENT_POSITION_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_CURRENT_POSITION_MS, value).apply()

    companion object {
        private const val PREFS_NAME = "synthx_prefs"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_uri"
        private const val KEY_BACKUP_FOLDER_URI = "backup_folder_uri"
        private const val KEY_LAST_KNOWN_DB_VERSION = "last_known_db_version"
        private const val KEY_LAST_DAILY_BACKUP = "last_daily_backup"
        private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        private const val KEY_CURRENT_POSITION_MS = "current_position_ms"
    }
}
