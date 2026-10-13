-- Perfetto UI only (not read by analyze_traces.py): every synthX.* section in time order.
-- Gaps between rows are the idle pauses; dense runs are the animation windows.
SELECT
  s.ts,
  s.dur / 1e6 AS ms,
  s.name,
  t.name AS thread_name
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND s.name GLOB 'synthX.*'
ORDER BY s.ts
