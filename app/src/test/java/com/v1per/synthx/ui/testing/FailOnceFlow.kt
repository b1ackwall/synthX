package com.v1per.synthx.ui.testing

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue

/**
 * A repository flow that fails once, the way a transient database error would. The first
 * subscription emits [first], waits for [fail], then throws. Every later subscription emits
 * [afterRetry] and stays open, so a re-subscribing caller would see fresh data.
 */
class FailOnceFlow<T>(private val first: T, private val afterRetry: T) {
    private val subscriptionCount = AtomicInteger(0)
    private val failSignal = CompletableDeferred<Unit>()

    val subscriptions: Int get() = subscriptionCount.get()

    val flow: Flow<T> = flow {
        if (subscriptionCount.incrementAndGet() == 1) {
            emit(first)
            failSignal.await()
            throw IOException("transient database error (test)")
        }
        emit(afterRetry)
        CompletableDeferred<Unit>().await()
    }

    fun fail() {
        failSignal.complete(Unit)
    }
}

/**
 * Collects exceptions that escape a coroutine with no handler (they land on the default
 * uncaught-exception handler) while [block] runs, then puts the previous handler back. Any
 * exception that [expected] does not accept fails the test once [block] has finished, so a leak
 * surfaces in the test that caused it.
 *
 * kotlinx-coroutines-test keeps such an exception when no `runTest` is active and replays it into
 * the next `runTest` in the JVM. A throwaway `runTest` takes what was kept: before [block], so
 * that exceptions left by earlier tests are not blamed on this one, and after it, so that an
 * expected exception cannot reach a later test.
 */
fun <R> withUncaughtExceptionsCaptured(expected: (Throwable) -> Boolean = { false }, block: (List<Throwable>) -> R): R {
    takeKeptExceptions()
    val caught = CopyOnWriteArrayList<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
    val result = try {
        block(caught)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
    val unexpected = (caught + takeKeptExceptions()).filterNot(expected)
    assertTrue(unexpected.isEmpty(), "uncaught exceptions escaped a coroutine: $unexpected")
    return result
}

/** The exceptions kotlinx-coroutines-test kept while no `runTest` was active; empty when none. */
private fun takeKeptExceptions(): List<Throwable> = try {
    runTest {}
    emptyList()
} catch (e: IllegalStateException) {
    e.suppressed.toList()
}

/**
 * [withUncaughtExceptionsCaptured] for a ViewModel built with a plain `Application()`: with
 * `isReturnDefaultValues`, `Context.getString` returns null, so resolving an error message throws
 * Kotlin's null-check exception inside the ViewModel's `catch`. That one exception is expected;
 * anything else fails the test.
 */
fun <R> withStubbedStringLookups(block: (List<Throwable>) -> R): R = withUncaughtExceptionsCaptured(
    expected = { e -> e is NullPointerException && e.message?.contains("getString(...) must not be null") == true },
    block = block,
)
