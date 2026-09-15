package com.v1per.synthx

import android.app.Application
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.scavengeBackupStagesAtProcessStart
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PendingCleanupReconciliationTracker

/** Establishes the ordered process gates before any Activity, Service, or Worker can own Room. */
class SynthXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No operation from a prior process can still own these files. Do this synchronously
        // before opening any gate that may admit backup, preview, verification, or restore work.
        scavengeBackupStagesAtProcessStart(this)
        RestoreStartup.begin(this)
        // Initialization performs no database I/O. The first asynchronous owner to await the
        // gate will join restore recovery, inspect the live version, and back up if required.
        DatabaseStartupGate.initialize(this)
        // N13/P3.3: hydrate the process-wide outcome/counter StateFlows from SharedPreferences
        // once, up front — both are read from Compose (DeleteSongDialog, MainScaffold-style
        // observers) before any ViewModel would otherwise have triggered a load.
        BackupOutcomeStore.initialize(this)
        DestructiveActionTracker.initialize(this)
        PendingCleanupReconciliationTracker.initialize(this)
    }
}
