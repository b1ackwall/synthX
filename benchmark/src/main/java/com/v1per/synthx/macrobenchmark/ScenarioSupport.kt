package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** What every scenario shares: metrics, fail-fast checks, the launch, and the baseline state. */
internal object Scenario {
    fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The device and install checks each benchmark class runs in its @Before. */
    fun checkPreconditions() {
        DeviceConditions.requireReady()
        AppReadiness.requireInstalled(device())
    }

    /**
     * Frame timing (from the frame timeline, or from the app's frame slices when
     * [BenchmarkArgs.sliceFrames]) plus every synthX.* section. Each metric here must report a
     * value in every iteration (see [SectionMaxMetric]), or Macrobenchmark drops the iterations
     * that lack it.
     */
    @OptIn(ExperimentalMetricApi::class)
    fun metrics(): List<Metric> = buildList {
        add(if (BenchmarkArgs.sliceFrames()) SliceFrameTimingMetric() else FrameTimingMetric())
        Sections.All.forEach { add(TraceSectionMetric(it)) }
        Sections.WithMax.forEach { add(SectionMaxMetric(it)) }
    }
}

/**
 * The state every scenario starts from and leaves behind: player collapsed, queue mode off,
 * playback paused, Library tab. Queue mode can only be read while expanded, so this expands once.
 */
internal object Baseline {
    fun require(device: UiDevice) {
        val player = PlayerDriver(device)
        player.ensureExpanded()
        QueueDriver(device).ensureQueueMode(on = false)
        player.ensureCollapsed()
        PlaybackDriver(device).ensurePaused()
        AppReadiness.showLibrary(device)
    }
}

/**
 * Runs one scenario. The app is launched once and stays alive across iterations.
 *
 * [prepare] runs once, unmeasured, after the baseline is confirmed; [beforeEach] runs unmeasured
 * before every iteration; [measure] is the traced block. [extraMetrics] are added to the shared
 * ones.
 *
 * Macrobenchmark force-stops the app when the measurement ends or fails, so [cleanup] runs on a
 * fresh launch (player collapsed, playback stopped) and must set up whatever screen it needs.
 * The baseline is then confirmed again. After a failure the same cleanup is still attempted, but
 * its own errors are dropped so the original failure is the one reported.
 */
internal fun MacrobenchmarkRule.runScenario(
    defaultIterations: Int,
    extraMetrics: List<Metric> = emptyList(),
    prepare: (UiDevice) -> Unit = {},
    beforeEach: (UiDevice) -> Unit = {},
    cleanup: (UiDevice) -> Unit = {},
    measure: (UiDevice) -> Unit,
) {
    val device = Scenario.device()
    var prepared = false
    val measured = runCatching {
        measureRepeated(
            packageName = BenchmarkTarget.PACKAGE,
            metrics = Scenario.metrics() + extraMetrics,
            compilationMode = BenchmarkArgs.compilationMode(),
            iterations = BenchmarkArgs.iterations(defaultIterations),
            // No startup mode: the process is not killed between iterations.
            startupMode = null,
            setupBlock = {
                if (device.currentPackageName != packageName) {
                    startActivityAndWait()
                    AppReadiness.requireUiReady(device)
                    AppReadiness.awaitBackgroundWorkSettled(device)
                    prepared = false
                }
                if (!prepared) {
                    Baseline.require(device)
                    prepare(device)
                    prepared = true
                }
                beforeEach(device)
            },
        ) {
            measure(device)
        }
    }
    val cleaned = runCatching {
        AppReadiness.relaunch(device)
        cleanup(device)
    }
    // Attempted even when the cleanup failed, so the next scenario starts from the baseline.
    val restored = runCatching { Baseline.require(device) }
    measured.getOrThrow()
    cleaned.getOrThrow()
    restored.getOrThrow()
}
