package com.v1per.synthx

import android.app.Application
import com.v1per.synthx.data.backup.RestoreRecovery
import com.v1per.synthx.data.backup.RestoreStartup

/** Starts restore recovery before any Activity or Worker is allowed to obtain synthX's Room DB. */
class SynthXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RestoreStartup.begin(this)
    }
}
