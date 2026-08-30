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

    companion object {
        private const val PREFS_NAME = "synthx_prefs"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_uri"
        private const val KEY_BACKUP_FOLDER_URI = "backup_folder_uri"
        private const val KEY_NEW_ARRIVALS_FOLDER_URI = "new_arrivals_folder_uri"
        private const val KEY_LAST_DAILY_BACKUP = "last_daily_backup"
    }
}
