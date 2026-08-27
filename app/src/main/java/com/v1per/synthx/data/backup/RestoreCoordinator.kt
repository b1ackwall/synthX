package com.v1per.synthx.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val lock = Mutex()
    private var mode = Mode.NORMAL
    private var active = 0
    private var drained = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private val _terminal = MutableStateFlow(Terminal.NORMAL)
    val terminal: StateFlow<Terminal> = _terminal.asStateFlow()

    suspend fun <T> withDbOperation(block: suspend () -> T): T {
        lock.withLock {
            check(mode == Mode.NORMAL) { "Database operation is unavailable during restore" }
            active += 1
            if (active == 1) drained = CompletableDeferred()
        }
        return try {
            block()
        } finally {
            lock.withLock {
                active -= 1
                check(active >= 0) { "Restore operation counter underflow" }
                if (active == 0) drained.complete(Unit)
            }
        }
    }

    /** Runs a restore-owned critical section after admission is closed and normal work drains. */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        val waitFor = lock.withLock {
            check(mode == Mode.NORMAL) { "Another restore or terminal state is active" }
            mode = Mode.RESTORE_PREPARING
            drained
        }
        waitFor.await()
        return try { block() } catch (t: Throwable) { throw t }
    }

    fun markClosed() { mode = Mode.RESTORE_CLOSED }
    fun current(): Terminal = _terminal.value
    fun currentMode(): Mode = mode
    fun markRestartRequired(rolledBack: Boolean) {
        mode = Mode.RESTART_REQUIRED
        _terminal.value = if (rolledBack) Terminal.ROLLED_BACK_RESTART_REQUIRED else Terminal.RESTART_REQUIRED
    }
    fun markEmergency() { mode = Mode.EMERGENCY; _terminal.value = Terminal.EMERGENCY }
    fun markNormal() { mode = Mode.NORMAL; _terminal.value = Terminal.NORMAL }
    fun requireNormal() { check(mode == Mode.NORMAL) { "Restore requires synthX to be closed and reopened" } }
}
