package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * The longest single run of one trace section in the app, reported as `<section>MaxMs`, and as
 * 0 for an iteration in which the section never ran.
 *
 * TraceSectionMetric's Max mode reports nothing for such an iteration, and Macrobenchmark keeps
 * only the iterations that have every metric. A section that ran in one iteration out of eight
 * therefore discarded the other seven from the result file without any error.
 */
@OptIn(ExperimentalMetricApi::class)
internal class SectionMaxMetric(private val section: String) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val app = captureInfo.targetPackageName
        val longestNs = traceSession.query(
            """
            SELECT MAX(slice.dur) AS longest
            FROM slice
            JOIN thread_track ON slice.track_id = thread_track.id
            JOIN thread USING (utid)
            JOIN process USING (upid)
            WHERE slice.name = '$section'
              AND (process.name = '$app' OR process.name LIKE '$app:%')
            """.trimIndent(),
        ).firstOrNull()?.nullableLong("longest") ?: 0L
        return listOf(Measurement("${section}MaxMs", longestNs / NanosPerMilli))
    }

    private companion object {
        const val NanosPerMilli = 1_000_000.0
    }
}
