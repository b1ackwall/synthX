-- The benchmark app's main thread and RenderThread. Used by analyze_traces.py.
SELECT
  p.upid,
  p.pid,
  t.utid,
  t.tid,
  t.name AS thread_name,
  CASE WHEN t.is_main_thread = 1 THEN 'main' ELSE 'render' END AS thread_role
FROM thread t
JOIN process p USING (upid)
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND (t.is_main_thread = 1 OR t.name = 'RenderThread')
ORDER BY thread_role
