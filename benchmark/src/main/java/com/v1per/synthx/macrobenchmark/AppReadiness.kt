package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Fail-fast checks for the one-time manual setup in scripts/perf/README.md. */
internal object AppReadiness {
    private const val SetupHint = "See the one-time setup in scripts/perf/README.md."
    private const val MainScreenTimeoutMs = 15_000L
    private const val ElementTimeoutMs = 5_000L
    private const val SettleTimeoutMs = 180_000L
    private const val SettlePollMs = 1_000L
    private const val SettleInitialPauseMs = 3_000L
    private const val SettleFinalPauseMs = 2_000L

    /** WorkManager services that are bound while startup scans or backups run. */
    private val BackgroundWorkServices = listOf("SystemJobService", "SystemForegroundService")

    fun requireInstalled(device: UiDevice) {
        val path = device.executeShellCommand("pm path ${BenchmarkTarget.PACKAGE}").trim()
        check(path.startsWith("package:")) {
            "${BenchmarkTarget.PACKAGE} is not installed. scripts/perf/run-benchmark.fish " +
                "installs it; then do the one-time setup. $SetupHint"
        }
    }

    /** Call right after launch: main screen, Library tab, and a loaded song. */
    fun requireUiReady(device: UiDevice) {
        val navLibrary = device.wait(Until.findObject(By.res(Tags.NavLibrary)), MainScreenTimeoutMs)
        checkNotNull(navLibrary) {
            "synthX bench did not reach its main screen within ${MainScreenTimeoutMs / 1000} s " +
                "(no Library tab). It is most likely not onboarded yet, or it is showing a " +
                "restore or migration screen. Open 'synthX bench' on the phone and finish " +
                "onboarding. $SetupHint"
        }
        showLibrary(device)
        checkNotNull(device.wait(Until.findObject(By.res(Tags.MiniPlayer)), ElementTimeoutMs)) {
            "No mini player in synthX bench, so no song is loaded. Play any song once in the " +
                "benchmark app and leave it in the queue. $SetupHint"
        }
    }

    /** Selects the Library tab if the Library screen is not already showing. */
    fun showLibrary(device: UiDevice) {
        if (device.hasObject(By.res(Tags.LibraryScreen))) return
        findOrFail(device, Tags.NavLibrary, "Library tab").click()
        findOrFail(device, Tags.LibraryScreen, "Library screen")
    }

    /** Selects the Home tab if the Home screen is not already showing. */
    fun showHome(device: UiDevice) {
        if (device.hasObject(By.res(Tags.HomeScreen))) return
        findOrFail(device, Tags.NavHome, "Home tab").click()
        findOrFail(device, Tags.HomeScreen, "Home screen")
    }

    /**
     * Waits for the launch-time scans, backup and maintenance work to finish, so they do not
     * compete with the measured frames.
     */
    fun awaitBackgroundWorkSettled(device: UiDevice) {
        SystemClock.sleep(SettleInitialPauseMs)
        val deadline = SystemClock.uptimeMillis() + SettleTimeoutMs
        var quietPolls = 0
        while (quietPolls < 2) {
            check(SystemClock.uptimeMillis() < deadline) {
                "synthX bench still had background work running after " +
                    "${SettleTimeoutMs / 1000} s (library scan, verify or backup). Let it " +
                    "finish with the app open, then re-run."
            }
            val services = device.executeShellCommand(
                "dumpsys activity services ${BenchmarkTarget.PACKAGE}",
            )
            quietPolls = if (BackgroundWorkServices.none { it in services }) quietPolls + 1 else 0
            SystemClock.sleep(SettlePollMs)
        }
        SystemClock.sleep(SettleFinalPauseMs)
    }

    fun findOrFail(device: UiDevice, tag: String, what: String): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.res(tag)), ElementTimeoutMs)) {
            "$what (tag '$tag') did not appear within ${ElementTimeoutMs / 1000} s."
        }
}
