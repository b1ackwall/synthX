package com.v1per.synthx.playback

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Process-level control of the real [SynthXPlaybackService] for tests that need it gone or freshly
 * created (exit paths, channel set-up). The service is shared by every playback test in the
 * process, so these tests start from a destroyed service instead of whatever an earlier class left.
 */
internal object PlaybackServiceLifecycle {

    /** Whether the system currently has the service running (for this app's own services). */
    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION") // still returns the caller's own services
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SynthXPlaybackService::class.java.name }
    }

    /**
     * Clears any start request and waits until the system has destroyed the service. Every
     * controller this process holds must already be released, or the binding keeps it alive.
     */
    fun stopAndAwaitDestroyed(context: Context, timeoutMs: Long = 15_000L) {
        context.stopService(Intent(context, SynthXPlaybackService::class.java))
        awaitDestroyed(context, timeoutMs)
    }

    fun awaitDestroyed(context: Context, timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isRunning(context)) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "SynthXPlaybackService still running after $timeoutMs ms: a controller from another test " +
                "is probably still bound to it",
        )
    }
}
