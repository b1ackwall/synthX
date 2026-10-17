package com.v1per.synthx.ui.testing

import com.v1per.synthx.data.repository.SongRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Device copy of the JVM helper: a repository flow that fails once, the way a transient database
 * error would. The first subscription emits [first], waits for [fail], then throws. Every later
 * subscription emits [afterRetry] and stays open, so a re-subscribing caller would see fresh data.
 */
internal class FailOnceFlow<T>(private val first: T, private val afterRetry: T) {
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

/** A [SongRepository] answering only the named Flow-returning reads; any other call fails loudly. */
internal fun flowOnlyRepository(flows: Map<String, Flow<*>>): SongRepository =
    Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "flowOnlyRepository"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> flows[method.name] ?: error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository
