package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke scenario: from the Library tab, tap the mini player to expand, then system back to
 * collapse, once per iteration. The app is launched once and stays alive across iterations.
 */
@RunWith(AndroidJUnit4::class)
class PlayerExpandCollapseBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() {
        DeviceConditions.requireReady()
        AppReadiness.requireInstalled(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }

    @Test
    fun expandCollapse() {
        rule.measureRepeated(
            packageName = BenchmarkTarget.PACKAGE,
            metrics = metrics(),
            compilationMode = BenchmarkArgs.compilationMode(),
            iterations = BenchmarkArgs.iterations(),
            // No startup mode: the process is not killed between iterations.
            startupMode = null,
            setupBlock = {
                if (device.currentPackageName != packageName) {
                    startActivityAndWait()
                    AppReadiness.requireUiReady(device)
                    AppReadiness.awaitBackgroundWorkSettled(device)
                }
                PlayerDriver(device).ensureCollapsed()
                AppReadiness.showLibrary(device)
            },
        ) {
            val player = PlayerDriver(device)
            player.expand()
            player.collapse()
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun metrics(): List<Metric> = listOf(
        FrameTimingMetric(),
        TraceSectionMetric(Sections.PlayerSheet),
        TraceSectionMetric(Sections.PlayerSheet, TraceSectionMetric.Mode.Max),
        TraceSectionMetric(Sections.OnProgressChanged),
        TraceSectionMetric(Sections.Background),
        TraceSectionMetric(Sections.BackgroundDraw),
        TraceSectionMetric(Sections.BackgroundDraw, TraceSectionMetric.Mode.Max),
        TraceSectionMetric(Sections.AlbumArtWithGlow),
        TraceSectionMetric(Sections.MiniContent),
        TraceSectionMetric(Sections.TitleRow),
        TraceSectionMetric(Sections.ControlsOverlay),
        TraceSectionMetric(Sections.QueueContent),
    )
}
