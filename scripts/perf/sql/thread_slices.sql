-- Every slice on the app's main thread and RenderThread, with its self time (its own duration
-- minus its direct children). Used by analyze_traces.py; in the Perfetto UI, sort by self_dur.
SELECT
  s.id,
  s.parent_id,
  s.ts,
  s.dur,
  s.depth,
  s.name,
  CASE WHEN t.is_main_thread = 1 THEN 'main' ELSE 'render' END AS thread_role,
  s.dur - COALESCE(k.child_dur, 0) AS self_dur
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
LEFT JOIN (
  SELECT parent_id, SUM(dur) AS child_dur
  FROM slice
  WHERE parent_id IS NOT NULL AND dur >= 0
  GROUP BY parent_id
) k ON k.parent_id = s.id
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND (t.is_main_thread = 1 OR t.name = 'RenderThread')
  AND s.dur >= 0
ORDER BY s.ts
