package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Frames per second inside each idle window that [IdleStateWindows.dwell] marks in this test
 * process's trace: `<state>DrawnFps` counts the app's RenderThread frames, `<state>CallbackFps`
 * its main-thread Choreographer frames (a frame-clock client asking for frames counts there
 * even when nothing is drawn). `<state>WindowMs` is the window's length, 0 if it is missing.
 */
@OptIn(ExperimentalMetricApi::class)
internal class IdleStateFrameMetric(private val states: List<String>) : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val app = captureInfo.targetPackageName
        val bench = InstrumentationRegistry.getInstrumentation().context.packageName
        return states.flatMap { state ->
            val row = traceSession.query(
                """
                WITH w AS (
                  SELECT slice.ts AS start, slice.ts + slice.dur AS finish
                  FROM slice
                  JOIN thread_track ON slice.track_id = thread_track.id
                  JOIN thread USING (utid)
                  JOIN process USING (upid)
                  WHERE slice.name = '${IdleStateWindows.section(state)}'
                    AND process.name = '$bench'
                  LIMIT 1
                ),
                f AS (
                  SELECT slice.ts AS ts, slice.name AS name, thread.name AS thread_name,
                         thread.tid = process.pid AS is_main
                  FROM slice
                  JOIN thread_track ON slice.track_id = thread_track.id
                  JOIN thread USING (utid)
                  JOIN process USING (upid)
                  WHERE process.name = '$app' AND slice.depth = 0
                )
                SELECT
                  (SELECT finish - start FROM w) AS window_ns,
                  (SELECT COUNT(*) FROM f, w WHERE f.ts >= w.start AND f.ts < w.finish
                     AND f.thread_name = 'RenderThread' AND f.name GLOB 'DrawFrame*') AS drawn,
                  (SELECT COUNT(*) FROM f, w WHERE f.ts >= w.start AND f.ts < w.finish
                     AND f.is_main AND f.name GLOB 'Choreographer#doFrame*') AS callbacks
                """.trimIndent(),
            ).firstOrNull()
            val windowNs = row?.nullableLong("window_ns") ?: 0L
            val seconds = windowNs / NanosPerSecond
            fun perSecond(count: Long?) = if (seconds > 0) (count ?: 0L) / seconds else 0.0
            listOf(
                Measurement("${state}DrawnFps", perSecond(row?.nullableLong("drawn"))),
                Measurement("${state}CallbackFps", perSecond(row?.nullableLong("callbacks"))),
                Measurement("${state}WindowMs", windowNs / NanosPerMilli),
            )
        }
    }

    private companion object {
        const val NanosPerSecond = 1_000_000_000.0
        const val NanosPerMilli = 1_000_000.0
    }
}
