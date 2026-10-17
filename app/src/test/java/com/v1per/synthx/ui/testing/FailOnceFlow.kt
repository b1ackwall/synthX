package com.v1per.synthx.ui.testing

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
 * uncaught-exception handler) while [block] runs, then puts the previous handler back.
 */
fun <R> withUncaughtExceptionsCaptured(block: (List<Throwable>) -> R): R {
    val caught = CopyOnWriteArrayList<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
    try {
        return block(caught)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
