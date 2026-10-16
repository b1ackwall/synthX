package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold startup from the launcher to the first frame (timeToInitialDisplay). The app does not
 * report fully drawn, so there is no timeToFullDisplay.
 *
 * After the first frame each iteration waits for the main screen and for launch-time background
 * work to finish, so the next iteration's process kill never interrupts a scan or backup. These
 * waits are after the first frame and do not change the metric.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    private companion object {
        const val MainScreenTimeoutMs = 15_000L
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun coldStartup() {
        rule.measureRepeated(
            packageName = BenchmarkTarget.PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = BenchmarkArgs.compilationMode(),
            iterations = BenchmarkArgs.iterations(),
            startupMode = StartupMode.COLD,
            setupBlock = {
                pressHome()
                // A process left by an earlier scenario fails the cold-start check otherwise.
                killProcess()
            },
        ) {
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.res(Tags.NavLibrary)), MainScreenTimeoutMs)) {
                "synthX bench did not reach its main screen within " +
                    "${MainScreenTimeoutMs / 1000} s after a cold start."
            }
            AppReadiness.awaitBackgroundWorkSettled(device)
        }
    }
}
