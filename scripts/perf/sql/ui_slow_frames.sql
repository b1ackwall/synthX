-- Perfetto UI only (not read by analyze_traces.py): the app's slowest frames. For each vsync
-- id, the main thread's doFrame, the RenderThread's DrawFrames and the frame timeline verdict.
SELECT
  CAST(a.name AS INT) AS vsync_id,
  m.ts AS main_ts,
  m.dur / 1e6 AS main_ms,
  r.dur / 1e6 AS render_ms,
  ((r.ts + r.dur) - m.ts) / 1e6 AS cpu_ms,
  a.dur / 1e6 AS timeline_ms,
  ((a.ts + a.dur) - (e.ts + e.dur)) / 1e6 AS overrun_ms,
  a.jank_type
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
LEFT JOIN expected_frame_timeline_slice e ON e.upid = a.upid AND e.name = a.name
LEFT JOIN slice m ON m.name = 'Choreographer#doFrame ' || a.name
LEFT JOIN slice r ON r.name = 'DrawFrames ' || a.name
WHERE p.name = 'com.v1per.synthx.benchmark'
ORDER BY cpu_ms DESC
LIMIT 50
