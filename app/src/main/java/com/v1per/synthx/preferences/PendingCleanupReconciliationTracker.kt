package com.v1per.synthx.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many leftover [com.v1per.synthx.data.db.PendingCleanupEntity] records the most
 * recent startup reconciliation pass resolved — surfaced on the Issues screen so a process death
 * mid-delete/replace/graduate leaves visible evidence instead of silently fixing itself. Same
 * persisted-StateFlow-from-a-singleton shape as [DestructiveActionTracker].
 */
object PendingCleanupReconciliationTracker {
    private val _lastCount = MutableStateFlow(0)
    val lastCount: StateFlow<Int> = _lastCount.asStateFlow()

    private val _lastTimestamp = MutableStateFlow(0L)
    val lastTimestamp: StateFlow<Long> = _lastTimestamp.asStateFlow()

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = PreferencesManager(context.applicationContext)
        _lastCount.value = prefs.lastReconciledCleanupCount
        _lastTimestamp.value = prefs.lastReconciledCleanupTimestamp
    }

    fun recordReconciliation(context: Context, count: Int) {
        val prefs = PreferencesManager(context.applicationContext)
        val timestamp = System.currentTimeMillis()
        prefs.lastReconciledCleanupCount = count
        prefs.lastReconciledCleanupTimestamp = timestamp
        _lastCount.value = count
        _lastTimestamp.value = timestamp
    }

    /** Test-only: lets instrumentation reset the once-per-process guard between cases. */
    internal fun resetForTest() {
        initialized = false
        _lastCount.value = 0
        _lastTimestamp.value = 0L
    }
}
