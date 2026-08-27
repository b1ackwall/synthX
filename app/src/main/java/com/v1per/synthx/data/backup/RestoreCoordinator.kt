package com.v1per.synthx.data.backup

import kotlinx.coroutines.CompletableDeferred
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
    private var active = 0
    private var drained = CompletableDeferred<Unit>().also { it.complete(Unit) }
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
                active -= 1
                check(active >= 0) { "Restore operation counter underflow" }
                if (active == 0) drained.complete(Unit)
            }
        }
    }

    /** Must be acquired at the point an asynchronous DB command is accepted. */
    internal fun acquireOperation(): OperationPermit {
        synchronized(lock) {
            check(mode == Mode.NORMAL) { "Database operation is unavailable during restore" }
            active += 1
            if (active == 1) drained = CompletableDeferred()
            return OperationPermit()
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
        }
        return block()
    }

    suspend fun awaitDrained() {
        val waitFor = synchronized(lock) { drained }
        waitFor.await()
    }

    fun markClosed() {
        synchronized(lock) {
            mode = Mode.RESTORE_CLOSED
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
            _terminal.value = if (rolledBack) Terminal.ROLLED_BACK_RESTART_REQUIRED else Terminal.RESTART_REQUIRED
        }
    }
    fun markEmergency(operationId: String? = null) {
        synchronized(lock) {
            mode = Mode.EMERGENCY
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
            _diagnosticOperationId.value = null
            _terminal.value = Terminal.NORMAL
        }
    }
    fun requireNormal() { check(currentMode() == Mode.NORMAL) { "Restore requires synthX to be closed and reopened" } }

    internal fun activeOperationCountForTest(): Int = synchronized(lock) { active }

    /** JVM/instrumentation isolation only; production must never reverse a terminal state. */
    internal fun resetForTest() {
        synchronized(lock) {
            check(active == 0) { "Cannot reset restore coordinator with active operations" }
            mode = Mode.NORMAL
            drained = CompletableDeferred<Unit>().also { it.complete(Unit) }
            _diagnosticOperationId.value = null
            _terminal.value = Terminal.NORMAL
        }
    }
}
