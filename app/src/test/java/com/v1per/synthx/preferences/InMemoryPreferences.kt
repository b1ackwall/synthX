package com.v1per.synthx.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Plain-map SharedPreferences for JVM tests, where the Android one is a stub. [raw] is the
 * stored file as key to value, so a test can check the exact key strings on disk. Reading a key
 * with the wrong type throws ClassCastException, like the real one.
 */
class InMemoryPreferences : SharedPreferences {
    val raw = linkedMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = raw.toMap()
    override fun getString(key: String, defValue: String?): String? = if (key in raw) raw[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        if (key in raw) raw[key] as Set<String>? else defValues
    override fun getInt(key: String, defValue: Int): Int = if (key in raw) raw[key] as Int else defValue
    override fun getLong(key: String, defValue: Long): Long = if (key in raw) raw[key] as Long else defValue
    override fun getFloat(key: String, defValue: Float): Float = if (key in raw) raw[key] as Float else defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = if (key in raw) raw[key] as Boolean else defValue
    override fun contains(key: String): Boolean = key in raw
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class Editor : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clearAll = false

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = also { puts[key] = value }

        override fun putString(key: String, value: String?): SharedPreferences.Editor = stage(key, value)
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = stage(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)
        override fun remove(key: String): SharedPreferences.Editor = also { removes += key }
        override fun clear(): SharedPreferences.Editor = also { clearAll = true }
        override fun commit(): Boolean {
            if (clearAll) raw.clear()
            removes.forEach { raw.remove(it) }
            // A null put removes the key, as in the real implementation.
            puts.forEach { (k, v) -> if (v == null) raw.remove(k) else raw[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

/** A Context whose only working call is getSharedPreferences; records the file name asked for. */
class PreferencesContext(val prefs: InMemoryPreferences = InMemoryPreferences()) : ContextWrapper(null) {
    val requested = mutableListOf<Pair<String?, Int>>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        requested += name to mode
        return prefs
    }
}

fun preferencesManager(context: PreferencesContext = PreferencesContext()): Pair<PreferencesManager, InMemoryPreferences> =
    PreferencesManager(context as Context) to context.prefs
