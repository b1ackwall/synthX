package com.v1per.synthx.ui.screens.restore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.RestoreRequestTracking
import com.v1per.synthx.preferences.PreferencesManager
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed class RestoreAppStatus {
    object Idle : RestoreAppStatus()
    data class Working(val requestId: UUID) : RestoreAppStatus()
    data class WaitingForStorage(val requestId: UUID) : RestoreAppStatus()
    sealed class Terminal(open val requestId: UUID) : RestoreAppStatus()
    data class Succeeded(override val requestId: UUID) : Terminal(requestId)
    data class Failed(override val requestId: UUID, val message: String) : Terminal(requestId)
}

/**
 * Activity-scoped projection of durable restore work. WorkManager owns execution state;
 * PreferencesManager contributes only the exact request identity, submission clock and whether
 * a terminal result has actually been shown. No Room-backed object is created here.
 */
class RestoreStatusViewModel internal constructor(
    context: Context,
    private val preferences: PreferencesManager,
    private val pendingGraceMillis: Long = DEFAULT_PENDING_GRACE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val _status = MutableStateFlow<RestoreAppStatus>(RestoreAppStatus.Idle)
    val status: StateFlow<RestoreAppStatus> = _status.asStateFlow()
    private val dismissedThisProcess = mutableSetOf<UUID>()

    init {
        viewModelScope.launch {
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(RestoreWorker.UNIQUE_WORK_NAME),
                RestoreRequestTracking.revision,
            ) { infos, _ -> infos }
                .collectLatest { infos ->
                    val info = selectTrackedWork(infos)
                    if (info == null || info.id in dismissedThisProcess) {
                        _status.value = RestoreAppStatus.Idle
                        return@collectLatest
                    }
                    if (preferences.restoreRequestId != info.id.toString()) {
                        check(preferences.trackRestoreRequestDurably(info.id.toString(), now())) {
                            "Could not persist discovered restore request identity"
                        }
                        RestoreRequestTracking.changed()
                    }
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> {
                            _status.value = RestoreAppStatus.Working(info.id)
                            val submittedAt = preferences.restoreRequestSubmittedAt.takeIf { it > 0L } ?: now()
                            delay((submittedAt + pendingGraceMillis - now()).coerceAtLeast(0L))
                            _status.value = RestoreAppStatus.WaitingForStorage(info.id)
                        }
                        WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                            _status.value = RestoreAppStatus.Working(info.id)
                        WorkInfo.State.SUCCEEDED ->
                            _status.value = RestoreAppStatus.Succeeded(info.id)
                        WorkInfo.State.FAILED -> _status.value = RestoreAppStatus.Failed(
                            info.id,
                            info.outputData.getString(RestoreWorker.KEY_ERROR)
                                ?: appContext.getString(R.string.restore_error_unknown),
                        )
                        WorkInfo.State.CANCELLED -> _status.value = RestoreAppStatus.Failed(
                            info.id,
                            appContext.getString(R.string.restore_error_cancelled),
                        )
                    }
                }
        }
    }

    /** Prefer the durable UUID. Fallback is deliberately limited to an unambiguous generation. */
    private fun selectTrackedWork(infos: List<WorkInfo>): WorkInfo? {
        preferences.restoreRequestId
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?.let { tracked -> infos.firstOrNull { it.id == tracked } }
            ?.let { tracked ->
                return tracked.takeUnless {
                    it.state.isFinished && preferences.restoreOutcomeAcknowledged
                }
            }
        infos.singleOrNull { !it.state.isFinished }?.let { return it }
        if (!preferences.restoreOutcomeAcknowledged) {
            infos.singleOrNull { it.state.isFinished }?.let { return it }
        }
        return null
    }

    fun terminalWasShown(requestId: UUID) {
        check(preferences.acknowledgeRestoreOutcomeDurably(requestId.toString())) {
            "Could not acknowledge restore outcome"
        }
    }

    fun dismissTerminal(requestId: UUID) {
        dismissedThisProcess += requestId
        _status.value = RestoreAppStatus.Idle
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RestoreStatusViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return RestoreStatusViewModel(
                context.applicationContext,
                PreferencesManager(context.applicationContext),
            ) as T
        }
    }

    companion object {
        internal const val DEFAULT_PENDING_GRACE_MILLIS = 5_000L
    }
}
