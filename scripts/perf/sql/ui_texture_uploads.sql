-- Perfetto UI only (not read by analyze_traces.py): RenderThread texture uploads and bitmap
-- preparation, largest total first. A size that changes every frame means a layer or bitmap is
-- being recreated per frame.
SELECT
  s.name,
  COUNT(*) AS uploads,
  SUM(s.dur) / 1e6 AS total_ms,
  MAX(s.dur) / 1e6 AS max_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND t.name = 'RenderThread'
  AND (s.name GLOB 'Texture upload*' OR s.name GLOB 'Bitmap#prepareToDraw*')
GROUP BY s.name
ORDER BY total_ms DESC
LIMIT 50
