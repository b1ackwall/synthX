package com.v1per.synthx.playback

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

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
     * Whether the system has stopped the service but its `onDestroy` has not returned yet. The
     * system drops a stopping service from [isRunning] before `onDestroy` runs on the main thread,
     * and lists it as "Destroy" in `dumpsys activity services` until `onDestroy` returns.
     */
    fun isDestroying(context: Context): Boolean {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys activity services ${context.packageName}")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        return dump.lineSequence().any { "* Destroy " in it && SynthXPlaybackService::class.java.name in it }
    }

    /**
     * Clears any start request and waits until the system has destroyed the service. Every
     * controller this process holds must already be released, or the binding keeps it alive.
     */
    fun stopAndAwaitDestroyed(context: Context, timeoutMs: Long = 15_000L) {
        context.stopService(Intent(context, SynthXPlaybackService::class.java))
        awaitDestroyed(context, timeoutMs)
    }

    /**
     * Waits until the service is neither running nor still inside `onDestroy`, so its exit
     * checkpoint has already read [SynthXPlaybackService.exitPersistenceOverride] and cannot write
     * into a recorder installed after this returns.
     */
    fun awaitDestroyed(context: Context, timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isRunning(context) && !isDestroying(context)) return
            Thread.sleep(50)
        }
        val state = if (isRunning(context)) "still running" else "stopped, but its onDestroy has not returned"
        throw AssertionError(
            "SynthXPlaybackService $state after $timeoutMs ms: a controller from another test " +
                "is probably still bound to it, or the main thread is blocked",
        )
    }
}
