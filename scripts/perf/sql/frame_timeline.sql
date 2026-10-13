-- The system's frame timeline for the app: one row per frame, named by its vsync id, with the
-- expected window and how the frame actually ended. overrun_ns > 0 means it finished late.
-- Used by analyze_traces.py.
SELECT
  CAST(a.name AS INT) AS vsync_id,
  a.ts,
  a.dur,
  a.jank_type,
  a.on_time_finish,
  a.present_type,
  e.ts AS expected_ts,
  e.dur AS expected_dur,
  (a.ts + a.dur) - (e.ts + e.dur) AS overrun_ns
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
LEFT JOIN expected_frame_timeline_slice e ON e.upid = a.upid AND e.name = a.name
WHERE p.name = 'com.v1per.synthx.benchmark'
ORDER BY a.ts
