package com.v1per.synthx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * `collect` with a per-event exception boundary (P4-T2 / R3-F07): a plain `flow.collect { ... }`
 * loop has its whole body inside the collector coroutine, so an uncaught exception from handling
 * ONE event kills that coroutine permanently — every event of that type after it is silently
 * never processed again for the rest of the process, with `SupervisorJob` doing nothing to help
 * (it only isolates sibling coroutines from each other, not sequential emissions within one).
 *
 * [onError] is called instead, and collection continues with the next emission. Cancellation is
 * always rethrown rather than swallowed — a boundary that ate `CancellationException` would break
 * structured concurrency (the coroutine would never actually stop when its scope is cancelled).
 */
internal suspend fun <T> Flow<T>.collectCatching(
    onError: (value: T, error: Throwable) -> Unit,
    action: suspend (T) -> Unit,
) {
    collect { value ->
        try {
            action(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(value, e)
        }
    }
}
