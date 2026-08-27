package com.v1per.synthx.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The small Phase-B maintenance barrier. Normal database work registers for its actual
 * operation lifetime; restore first closes admission, then waits for the counter to drain.
 */
internal object RestoreCoordinator {
    enum class Terminal { NORMAL, RESTART_REQUIRED, ROLLED_BACK_RESTART_REQUIRED, EMERGENCY }
    enum class Mode { NORMAL, RESTORE_PREPARING, RESTORE_CLOSED, RESTART_REQUIRED, EMERGENCY }

    private val lock = Any()
    private var mode = Mode.NORMAL
    private var activeFinite = 0
    private var finiteDrained = completedSignal()
    private var activeReactive = 0
    private var reactiveDrained = completedSignal()
    private val reactiveJobs = mutableMapOf<Job, Int>()
    private var admissionChanged = CompletableDeferred<Unit>()
    private val _terminal = MutableStateFlow(Terminal.NORMAL)
    val terminal: StateFlow<Terminal> = _terminal.asStateFlow()
    private val _diagnosticOperationId = MutableStateFlow<String?>(null)
    val diagnosticOperationId: StateFlow<String?> = _diagnosticOperationId.asStateFlow()

    /** A retained admission lease for an operation that starts asynchronously later. */
    internal class OperationPermit internal constructor() {
        private var released = false
        fun release() {
            synchronized(lock) {
                check(!released) { "Restore operation permit released twice" }
                released = true
                activeFinite -= 1
                check(activeFinite >= 0) { "Restore operation counter underflow" }
                if (activeFinite == 0) finiteDrained.complete(Unit)
            }
        }
    }

    /** Tracks one Room-backed Flow upstream, not the lifetime of its downstream owner. */
    internal class ReactivePermit internal constructor(private val owner: Job) {
        private var released = false
        fun release() {
            synchronized(lock) {
                check(!released) { "Restore reactive permit released twice" }
                released = true
                val remainingForOwner = checkNotNull(reactiveJobs[owner]) - 1
                if (remainingForOwner == 0) reactiveJobs.remove(owner)
                else reactiveJobs[owner] = remainingForOwner
                activeReactive -= 1
                check(activeReactive >= 0) { "Restore reactive operation counter underflow" }
                if (activeReactive == 0) reactiveDrained.complete(Unit)
            }
        }
    }

    /** Must be acquired at the point an asynchronous DB command is accepted. */
    internal fun acquireOperation(): OperationPermit {
        synchronized(lock) {
            check(mode == Mode.NORMAL) { "Database operation is unavailable during restore" }
            activeFinite += 1
            if (activeFinite == 1) finiteDrained = CompletableDeferred()
            return OperationPermit()
        }
    }

    /** Admission and registration share [lock] with [exclusive], closing the start race. */
    internal suspend fun acquireReactiveOperation(owner: Job): ReactivePermit {
        while (true) {
            var admitted: ReactivePermit? = null
            val waitFor = synchronized(lock) {
                when (mode) {
                    Mode.NORMAL -> {
                        activeReactive += 1
                        if (activeReactive == 1) reactiveDrained = CompletableDeferred()
                        reactiveJobs[owner] = reactiveJobs.getOrDefault(owner, 0) + 1
                        admitted = ReactivePermit(owner)
                        null
                    }
                    Mode.RESTORE_PREPARING -> admissionChanged
                    Mode.RESTORE_CLOSED,
                    Mode.RESTART_REQUIRED,
                    Mode.EMERGENCY -> error("Reactive database access is unavailable after Room close")
                }
            }
            admitted?.let { return it }
            checkNotNull(waitFor).await()
        }
    }

    suspend fun <T> withDbOperation(block: suspend () -> T): T {
        val permit = acquireOperation()
        return try {
            block()
        } finally {
            permit.release()
        }
    }

    /** Closes normal admission. The restore owner explicitly drains queues, then calls awaitDrained. */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        synchronized(lock) {
            check(mode == Mode.NORMAL) { "Another restore or terminal state is active" }
            mode = Mode.RESTORE_PREPARING
            signalAdmissionChanged()
        }
        return block()
    }

    /** Used before the safety snapshot, while existing reactive observers may remain live. */
    suspend fun awaitFiniteDrained() = synchronized(lock) { finiteDrained }.await()

    /**
     * Permanently stops every admitted Room Flow upstream and proves its collection finally block
     * released the reactive permit. Admission is already closed, so no replacement can register.
     */
    suspend fun quiesceReactiveOperations() {
        val owners = synchronized(lock) {
            check(mode == Mode.RESTORE_PREPARING) { "Reactive quiescence requires restore ownership" }
            reactiveJobs.keys.toList()
        }
        owners.forEach { it.cancel() }
        owners.forEach { it.cancelAndJoin() }
        synchronized(lock) { reactiveDrained }.await()
    }

    /** Final close boundary: both finite commands and reactive Room upstreams are gone. */
    suspend fun awaitDrained() {
        val waits = synchronized(lock) { finiteDrained to reactiveDrained }
        waits.first.await()
        waits.second.await()
    }

    fun markClosed() {
        synchronized(lock) {
            mode = Mode.RESTORE_CLOSED
            signalAdmissionChanged()
            // Room has crossed the irreversible process boundary. Hide every normal UI
            // immediately; the eventual result only changes which restart message is shown.
            _terminal.value = Terminal.RESTART_REQUIRED
        }
    }
    fun current(): Terminal = _terminal.value
    fun currentMode(): Mode = synchronized(lock) { mode }
    fun markRestartRequired(rolledBack: Boolean) {
        synchronized(lock) {
            mode = Mode.RESTART_REQUIRED
            signalAdmissionChanged()
            _terminal.value = if (rolledBack) Terminal.ROLLED_BACK_RESTART_REQUIRED else Terminal.RESTART_REQUIRED
        }
    }
    fun markEmergency(operationId: String? = null) {
        synchronized(lock) {
            mode = Mode.EMERGENCY
            signalAdmissionChanged()
            _diagnosticOperationId.value = operationId
            _terminal.value = Terminal.EMERGENCY
        }
    }
    /** Only startup and a failure before Room close may reopen normal admission. */
    fun markNormal() {
        synchronized(lock) {
            check(mode == Mode.NORMAL || mode == Mode.RESTORE_PREPARING) {
                "A process that closed Room or entered a terminal restore state cannot resume normal use"
            }
            mode = Mode.NORMAL
            signalAdmissionChanged()
            _diagnosticOperationId.value = null
            _terminal.value = Terminal.NORMAL
        }
    }
    fun requireNormal() { check(currentMode() == Mode.NORMAL) { "Restore requires synthX to be closed and reopened" } }

    internal fun activeOperationCountForTest(): Int = synchronized(lock) {
        activeFinite + activeReactive
    }
    internal fun activeReactiveOperationCountForTest(): Int = synchronized(lock) { activeReactive }

    /** JVM/instrumentation isolation only; production must never reverse a terminal state. */
    internal fun resetForTest() {
        synchronized(lock) {
            check(activeFinite == 0 && activeReactive == 0) {
                "Cannot reset restore coordinator with active operations"
            }
            mode = Mode.NORMAL
            finiteDrained = completedSignal()
            reactiveDrained = completedSignal()
            reactiveJobs.clear()
            signalAdmissionChanged()
            _diagnosticOperationId.value = null
            _terminal.value = Terminal.NORMAL
        }
    }

    private fun completedSignal() = CompletableDeferred<Unit>().also { it.complete(Unit) }

    /** Caller holds [lock]. Wakes preparation waiters to re-check normal versus terminal mode. */
    private fun signalAdmissionChanged() {
        admissionChanged.complete(Unit)
        admissionChanged = CompletableDeferred()
    }
}
