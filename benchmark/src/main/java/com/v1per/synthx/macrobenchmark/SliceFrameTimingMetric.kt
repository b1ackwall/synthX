package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * `frameDurationCpuMs` and `frameCount` from the app's own frame slices only, for phones whose
 * SurfaceFlinger frame timeline cannot be trusted (`-e synthx.frames slices`).
 *
 * From API 31 FrameTimingMetric keeps only frames that also have an actual frame timeline slice.
 * On the Redmi Note 7 Pro (Android 13 custom ROM) those slices report presents up to seconds
 * late, while SurfaceFlinger's own latency dump shows about 40 ms, so more frames fall past the
 * end of each trace every iteration and the metric silently loses them. This pairs each
 * main-thread `Choreographer#doFrame <id>` with the first RenderThread `DrawFrame* <id>` after
 * it, and measures from the doFrame start to the later of the two ends: the same span as
 * FrameTimingMetric's frameDurationCpuMs. There is no frameOverrunMs in this mode.
 */
@OptIn(ExperimentalMetricApi::class)
internal class SliceFrameTimingMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val app = captureInfo.targetPackageName
        val cpuMs = traceSession.query(
            """
            WITH app_slice AS (
              SELECT slice.ts AS ts, slice.dur AS dur, slice.name AS name,
                     thread.name AS thread_name, thread.tid = process.pid AS is_main
              FROM slice
              JOIN thread_track ON slice.track_id = thread_track.id
              JOIN thread USING (utid)
              JOIN process USING (upid)
              WHERE process.name = '$app' OR process.name LIKE '$app:%'
            ),
            ui AS (
              SELECT ts, dur, CAST(substr(name, instr(name, ' ') + 1) AS INT) AS id
              FROM app_slice
              WHERE is_main AND name GLOB 'Choreographer#doFrame [0-9]*'
            ),
            rt AS (
              SELECT ts, dur, CAST(substr(name, instr(name, ' ') + 1) AS INT) AS id
              FROM app_slice
              WHERE thread_name = 'RenderThread' AND name GLOB 'DrawFrame* [0-9]*'
            ),
            frame AS (
              SELECT ui.ts AS ui_start, ui.ts + ui.dur AS ui_end,
                     (SELECT rt.ts + rt.dur FROM rt WHERE rt.id = ui.id AND rt.ts >= ui.ts
                      ORDER BY rt.ts LIMIT 1) AS rt_end
              FROM ui
            )
            SELECT MAX(ui_end, rt_end) - ui_start AS cpu_ns
            FROM frame
            WHERE rt_end IS NOT NULL
            ORDER BY ui_start
            """.trimIndent(),
        ).map { it.long("cpu_ns") / NanosPerMilli }.toList()
        // No frames reports nothing, like FrameTimingMetric, so the iteration is flagged as dropped.
        if (cpuMs.isEmpty()) return emptyList()
        return listOf(
            Measurement("frameDurationCpuMs", cpuMs),
            Measurement("frameCount", cpuMs.size.toDouble()),
        )
    }

    private companion object {
        const val NanosPerMilli = 1_000_000.0
    }
}
