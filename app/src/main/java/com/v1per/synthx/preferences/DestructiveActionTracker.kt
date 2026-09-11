package com.v1per.synthx.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P3.3: counts delete-song/delete-playlist/replace-song mutations — the mutations this codebase
 * already treats as destructive enough to back up before performing (see BackupReason.DELETE_SONG
 * / DELETE_PLAYLIST / REPLACE_SONG) — since the last successful backup of any tier. A replace
 * counts once, for the complete delete it ends in. Read directly by
 * DeleteSongDialog/DeletePlaylistDialog (same StateFlow-from-a-singleton pattern MainScaffold
 * already uses for BackupFolderLaunchCheck.grantLost) and by BackupsAndRestoreViewModel.
 *
 * Persisted, unlike [DeleteSongsGate]: losing this count on a process restart would silently
 * erase exactly the state a stale-backup warning exists to preserve.
 */
object DestructiveActionTracker {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        _count.value = PreferencesManager(context.applicationContext).destructiveActionsSinceBackup
    }

    fun recordDestructiveAction(context: Context) {
        val prefs = PreferencesManager(context.applicationContext)
        val next = prefs.destructiveActionsSinceBackup + 1
        prefs.destructiveActionsSinceBackup = next
        _count.value = next
    }

    /** Called by BackupOutcomeStore whenever a backup attempt of ANY tier succeeds. */
    fun resetOnSuccessfulBackup(context: Context) {
        PreferencesManager(context.applicationContext).destructiveActionsSinceBackup = 0
        _count.value = 0
    }

    /** Test-only: lets instrumentation reset the once-per-process guard between cases. */
    internal fun resetForTest() {
        initialized = false
        _count.value = 0
    }
}
