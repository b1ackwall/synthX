package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide, launch-once check of whether the persisted backup folder (if any) is still
 * usable. Purely informational: it never gates app usability the way DatabaseStartupGate does —
 * MainActivity fires it once after normal startup, and MainScaffold observes [grantLost] to show
 * a small non-blocking notice pointing at Backups & Restore, where the same [BackupFolderHealth]
 * check re-runs (BackupsAndRestoreViewModel's init block) and the repair flow lives.
 *
 * The [checked] guard (mirroring DatabaseStartupGate's `ready` flag) is what keeps this from
 * re-firing on every Activity recreation within the same process — MainActivity has no equivalent
 * "already ran this process" state of its own.
 */
object BackupFolderLaunchCheck {
    private val _grantLost = MutableStateFlow(false)
    val grantLost: StateFlow<Boolean> = _grantLost.asStateFlow()

    @Volatile private var checked = false

    fun runOnce(context: Context, scope: CoroutineScope) {
        if (checked) return
        checked = true

        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val uri = PreferencesManager(appContext).backupFolderUri?.let(Uri::parse)
                ?: return@launch
            // A thrown exception is "couldn't verify this is healthy," not "verified healthy" —
            // it must read the same as an unhealthy result and show the banner, not suppress it.
            val result = runCatching {
                BackupFolderHealth.check(appContext, BackupManager.create(appContext), uri)
            }.getOrNull()
            _grantLost.value = result != BackupFolderHealth.Result.Healthy
        }
    }

    /**
     * Called when the user taps the notice and is taken to Backups & Restore, where the
     * authoritative re-check (BackupsAndRestoreViewModel's init block) and the repair flow
     * live. Clearing here just avoids a stale notice reappearing after they've fixed it —
     * the flag stays process-scoped and never re-runs the actual check until next launch.
     */
    fun dismiss() {
        _grantLost.value = false
    }

    /** Test-only: lets instrumentation reset the once-per-process guard between cases. */
    internal fun resetForTest() {
        checked = false
        _grantLost.value = false
    }
}
