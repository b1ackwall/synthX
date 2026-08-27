package com.v1per.synthx.preferences

import android.content.Context
import android.net.Uri

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var musicFolderUri: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_MUSIC_FOLDER_URI, value).apply()

    /** Separate tree from [musicFolderUri] — held with write access, unlike the music folder. */
    var backupFolderUri: String?
        get() = prefs.getString(KEY_BACKUP_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_BACKUP_FOLDER_URI, value).apply()

    /**
     * Second read-only tree, scanned in full on every launch — where the  companion
     * app drops downloads. Separate from [musicFolderUri]: songs found here become ordinary
     * library songs, but the tree is never used to reconcile `is_missing`.
     */
    var newArrivalsFolderUri: String?
        get() = prefs.getString(KEY_NEW_ARRIVALS_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_NEW_ARRIVALS_FOLDER_URI, value).apply()

    /**
     * Every tree that can hold library audio, in no particular order — currently
     * [musicFolderUri] and [newArrivalsFolderUri], skipping whichever aren't set.
     *
     * [backupFolderUri] is deliberately excluded and must stay excluded: it's a write-access
     * tree holding database snapshots, never scanned for audio. Including it would make the
     * scanner walk `.db` files and, worse, imply that songs could legitimately live there.
     *
     * Exists so missing-detection can reconcile against *all* configured roots rather than
     * only the one being walked — see FolderScanner.scan's `otherRootUris`.
     */
    fun configuredAudioRootUris(): List<Uri> =
        listOfNotNull(musicFolderUri, newArrivalsFolderUri).map(Uri::parse)

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
        private const val KEY_NEW_ARRIVALS_FOLDER_URI = "new_arrivals_folder_uri"
        private const val KEY_LAST_DAILY_BACKUP = "last_daily_backup"
        private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        private const val KEY_CURRENT_POSITION_MS = "current_position_ms"
    }
}
