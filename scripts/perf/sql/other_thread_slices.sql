-- Top-level slices on the app's other threads (GPU completion, binder, decoders), grouped by
-- thread and slice name. Used by analyze_traces.py.
SELECT
  t.name AS thread_name,
  s.name,
  COUNT(*) AS slice_count,
  SUM(s.dur) AS total_dur,
  MAX(s.dur) AS max_dur
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND t.is_main_thread = 0
  AND t.name != 'RenderThread'
  AND s.depth = 0
  AND s.dur >= 0
GROUP BY t.name, s.name
ORDER BY total_dur DESC
