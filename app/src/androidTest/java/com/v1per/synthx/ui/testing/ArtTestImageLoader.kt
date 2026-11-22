package com.v1per.synthx.ui.testing

import android.content.Context
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The singleton image loader an art test installs: memory and disk caches off, an event listener
 * that records every finished request, and an interceptor that turns the crossfade off so a
 * finished request is drawn at once. Call [install] before setting content and [reset] in `@After`.
 *
 * This is the only test file that names the image library's loader, listener and interceptor API.
 */
internal class ArtTestImageLoader(private val context: Context) {
    private val failures = AtomicInteger(0)
    private val successes = AtomicInteger(0)
    private val lastThrowable = AtomicReference<Throwable?>(null)
    private val lastSize = AtomicReference<Pair<Int, Int>?>(null)

    val failureCount: Int get() = failures.get()
    val successCount: Int get() = successes.get()

    /** Why the most recent failed request failed. */
    val lastFailure: Throwable? get() = lastThrowable.get()

    /** Width and height in pixels of the most recently delivered image. */
    val lastDecodedSize: Pair<Int, Int>? get() = lastSize.get()

    // setUnsafe and reset are DelicateCoilApi: a test replaces the process-wide loader on purpose.
    @OptIn(DelicateCoilApi::class)
    fun install() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .components { add(NoCrossfadeInterceptor) }
                .eventListener(
                    object : EventListener() {
                        override fun onError(request: ImageRequest, result: ErrorResult) {
                            lastThrowable.set(result.throwable)
                            failures.incrementAndGet()
                        }

                        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                            lastSize.set(result.image.width to result.image.height)
                            successes.incrementAndGet()
                        }
                    },
                )
                .build(),
        )
    }

    @OptIn(DelicateCoilApi::class)
    fun reset() {
        SingletonImageLoader.reset()
    }

    private object NoCrossfadeInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            chain.withRequest(chain.request.newBuilder().crossfade(false).build()).proceed()
    }
}
