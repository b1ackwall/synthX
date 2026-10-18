package com.v1per.synthx.testing

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/**
 * Saves the debug app's real `synthx_prefs` before a test, starts the test from an empty file,
 * and puts the saved entries back afterwards. For tests whose code under test builds
 * `PreferencesManager(applicationContext)` itself (MainActivity, the shell's backup notice), so
 * there is no way to hand it an isolated file.
 */
// check() needs the Boolean from commit() that KTX edit(commit = true) would drop.
@SuppressLint("UseKtx")
class DebugPreferencesSnapshot : ExternalResource() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var saved: Map<String, *> = emptyMap<String, Any?>()

    private val prefs get() = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun before() {
        saved = HashMap(prefs.all)
        check(prefs.edit().clear().commit()) { "Could not clear $NAME before the test" }
    }

    @SuppressLint("ApplySharedPref")
    @Suppress("UNCHECKED_CAST")
    override fun after() {
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        check(editor.commit()) { "Could not restore $NAME after the test" }
    }

    private companion object {
        const val NAME = "synthx_prefs"
    }
}
