package com.v1per.synthx

import android.app.Application
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreStartup

/** Establishes the ordered process gates before any Activity, Service, or Worker can own Room. */
class SynthXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RestoreStartup.begin(this)
        // Initialization performs no database I/O. The first asynchronous owner to await the
        // gate will join restore recovery, inspect the live version, and back up if required.
        DatabaseStartupGate.initialize(this)
    }
}
