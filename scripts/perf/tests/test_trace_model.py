import unittest

import _support  # noqa: F401  (puts scripts/perf on the path)
import slice_classes as sc
import trace_model as tm

MS = 1_000_000


def slice_(id_, parent, ts_ms, dur_ms, depth, name, role="main", self_ms=None):
    return {
        "id": id_, "parent_id": parent, "ts": int(ts_ms * MS), "dur": int(dur_ms * MS), "depth": depth,
        "name": name, "thread_role": role, "self_dur": int((dur_ms if self_ms is None else self_ms) * MS),
    }


def frame_slices(base_id, vsync, ts_ms, sheet=True, draw=True):
    """One synthetic frame: doFrame > Compose:recompose > synthX.PlayerSheet, plus a RenderThread draw."""
    rows = [slice_(base_id, None, ts_ms, 3.0, 0, f"Choreographer#doFrame {vsync}", self_ms=1.0)]
    rows.append(slice_(base_id + 1, base_id, ts_ms + 0.1, 2.0, 1, "Compose:recompose", self_ms=1.5 if sheet else 2.0))
    if sheet:
        rows.append(slice_(base_id + 2, base_id + 1, ts_ms + 0.2, 0.5, 2, "synthX.PlayerSheet"))
    if draw:
        rows.append(slice_(base_id + 3, None, ts_ms + 3.0, 4.0, 0, f"DrawFrames {vsync}", role="render", self_ms=1.0))
        rows.append(slice_(base_id + 4, base_id + 3, ts_ms + 3.5, 3.0, 1, "Texture upload(7) 1440x3064", role="render"))
        rows.append(slice_(base_id + 5, None, ts_ms + 7.2, 0.5, 0, "Bitmap#prepareToDraw 105x105", role="render"))
    return rows


class ClassificationTest(unittest.TestCase):
    def test_fixture_names(self):
        self.assertEqual(sc.classify("postAndWait", "main"), "main.wait_render")
        self.assertEqual(sc.classify("Choreographer#doFrame 26006982", "main"), "main.choreographer")
        self.assertEqual(sc.classify("Texture upload(2389) 1440x3064", "render"), "rt.texture_upload")
        self.assertEqual(sc.classify("DrawFrames 26007598", "render"), "rt.frame")
        self.assertEqual(sc.classify("synthX.MainShell", "main"), sc.INHERIT)

    def test_names_from_the_scenario_traces(self):
        # A queue fling: rows composed ahead of the scroll, and their album art painters.
        self.assertEqual(sc.classify("compose:lazy:prefetch:compose", "main"), "main.recomposition")
        self.assertEqual(sc.classify("compose:lazy:prefetch:measure", "main"), "main.measure_layout")
        self.assertEqual(sc.classify("AsyncImagePainter.onRemembered", "main"), "main.recomposition")
        # A touch batch inside a frame, and the same VRI[ prefix on the RenderThread.
        self.assertEqual(sc.classify("input", "main"), "main.input")
        self.assertEqual(sc.classify("VRI[MainActivity]@bd4d1d", "main"), "main.input")
        self.assertEqual(sc.classify("VRI[MainActivity]@bd4d1d#3(f:0,a:1,s:0)", "render"), "rt.buffer_queue")
        # The Home background's paths and first-use pipeline creation on the RenderThread.
        self.assertEqual(sc.classify("TriangulatingPathOp", "render"), "rt.gpu_flush")
        self.assertEqual(sc.classify("CreateGraphicsPipeline-CacheLookup", "render"), "rt.pipeline")
        self.assertEqual(sc.classify("prepareAndDraw 28467137", "render"), "rt.frame")
        self.assertEqual(sc.classify("binder transaction", "main"), "other")

    def test_class_descriptors_are_runtime_work(self):
        self.assertEqual(sc.classify("Lu12;", "main"), "other")
        self.assertEqual(sc.classify("Landroid/media/metrics/PlaybackMetrics$1;", "main"), "other")
        self.assertEqual(sc.classify("Lock contention on a monitor lock (owner tid: 7)", "main"), "other")
        # The regex must match the whole name, not any name that starts with L.
        self.assertIsNone(sc.classify("LayoutSomethingNew", "main"))
        self.assertIsNone(sc.classify("Lu12; and more", "main"))

    def test_unknown_name_is_not_guessed(self):
        self.assertIsNone(sc.classify("SomeFutureSlice", "main"))
        self.assertIsNone(sc.classify("postAndWait", "render"))

    def test_every_rule_points_at_a_known_category(self):
        for rules in sc.RULES_BY_ROLE.values():
            for kind, _, category in rules:
                self.assertIn(kind, (sc.EXACT, sc.PREFIX, sc.REGEX))
                self.assertTrue(category == sc.INHERIT or category in sc.CATEGORIES, category)

    def test_normalize(self):
        self.assertEqual(sc.normalize("Choreographer#doFrame 26006982"), "Choreographer#doFrame <n>")
        self.assertEqual(sc.normalize("Texture upload(2389) 1440x3064"), "Texture upload(<n>) <n>x<n>")
        self.assertEqual(sc.normalize("deliverInputEvent src=0x101 id=0xdd3ef37f"), "deliverInputEvent src=<hex> id=<hex>")
        self.assertEqual(sc.normalize("synthX.PlayerSheet.onProgressChanged"), "synthX.PlayerSheet.onProgressChanged")

    def test_texture_size(self):
        self.assertEqual(sc.texture_size("Texture upload(2389) 1440x3064"), "1440x3064")
        self.assertEqual(sc.texture_size("Bitmap#prepareToDraw 105x105"), "105x105")
        self.assertIsNone(sc.texture_size("flush commands"))


class WindowTest(unittest.TestCase):
    def markers(self, *starts_ms):
        return [(int(s * MS), int((s + 1) * MS)) for s in starts_ms]

    def test_two_bursts_stay_separate(self):
        markers = self.markers(*range(100, 400, 10), *range(700, 1000, 10))
        windows = tm.find_windows(markers, gap_ms=50, min_burst_ms=50, margin_ms=20)
        self.assertEqual(len(windows), 2)
        self.assertEqual(windows[0], (80 * MS, 411 * MS))

    def test_sparse_markers_are_not_a_window(self):
        # One marker every 66 ms, as the expanded player's background redraw produces.
        self.assertEqual(tm.find_windows(self.markers(*range(0, 660, 66)), 50, 50, 20), [])

    def test_single_frame_of_markers_is_too_short(self):
        self.assertEqual(tm.find_windows(self.markers(100, 100.2, 100.4), 50, 50, 20), [])

    def test_no_markers(self):
        self.assertEqual(tm.find_windows([], 50, 50, 20), [])

    def test_activity_markers(self):
        self.assertTrue(sc.is_activity_marker("synthX.QueueRow"))
        self.assertTrue(sc.is_activity_marker("deliverInputEvent src=0x1002 eventTimeNano=1 id=0x2"))
        self.assertTrue(sc.is_activity_marker("AndroidOwner:measureAndLayout"))
        # Drawing alone is what a pause does (background redraws), so it is not activity.
        self.assertFalse(sc.is_activity_marker("AndroidOwner:draw"))
        self.assertFalse(sc.is_activity_marker("Record View#draw()"))


class VsyncPeriodTest(unittest.TestCase):
    def test_median_gap_ignores_pauses(self):
        starts = [int(i * 8.33 * MS) for i in range(20)] + [int((1000 + i * 8.33) * MS) for i in range(20)]
        period, source = tm.vsync_period(starts, [])
        self.assertAlmostEqual(period, 8.33, places=2)
        self.assertEqual(source, "frame timeline")

    def test_falls_back_to_frame_spacing(self):
        period, source = tm.vsync_period([], [int(i * 16.67 * MS) for i in range(10)])
        self.assertAlmostEqual(period, 16.67, places=2)
        self.assertEqual(source, "doFrame spacing")

    def test_unknown_when_too_few_frames(self):
        self.assertEqual(tm.vsync_period([], [0, MS]), (None, None))

    def test_skipped_vsyncs_do_not_stretch_the_period(self):
        # Half the frames are two vsyncs apart, as when every other frame has nothing to draw.
        starts, now = [], 0.0
        for i in range(40):
            starts.append(int(now * MS))
            now += 16.64 if i % 2 else 33.28
        period, source = tm.vsync_period(starts, [])
        self.assertAlmostEqual(period, 16.64, places=2)
        self.assertEqual(source, "frame timeline")

    def test_sparse_frames_are_not_measurable(self):
        # The mini player idling: one drawn frame per 499.4 ms, and three doFrames around it
        # whose start times include wake-up latency (gaps of 41.2, 58.2 and 400 ms).
        expected = [int(i * 499.4 * MS) for i in range(20)]
        frames = []
        for i in range(20):
            frames += [int((i * 499.4 + offset) * MS) for offset in (0.0, 41.2, 99.4)]
        self.assertEqual(tm.vsync_period(expected, frames), (None, None))

    def test_doframes_do_not_stand_in_for_a_sparse_timeline(self):
        # Animation callbacks every second vsync next to a timeline with one frame per 500 ms.
        expected = [int(i * 499.4 * MS) for i in range(20)]
        frames = [int((i * 499.4 + step * 33.29) * MS) for i in range(20) for step in range(3)]
        self.assertEqual(tm.vsync_period(expected, frames), (None, None))

    def test_duplicate_timeline_rows_are_not_a_period(self):
        # One frame can have two timeline rows a few microseconds apart.
        starts = []
        for i in range(30):
            starts += [int(i * 16.64 * MS), int(i * 16.64 * MS) + 40]
        period, _ = tm.vsync_period(starts, [])
        self.assertAlmostEqual(period, 16.64, places=2)

    def test_dense_frame_spacing_still_works_without_a_timeline(self):
        # Mostly back-to-back frames with one long pause in the middle.
        frames = [int(i * 8.33 * MS) for i in range(30)] + [int((2000 + i * 8.33) * MS) for i in range(30)]
        period, source = tm.vsync_period([], frames)
        self.assertAlmostEqual(period, 8.33, places=2)
        self.assertEqual(source, "doFrame spacing")


class BuildResultTest(unittest.TestCase):
    def build(self):
        slices = []
        # Ten animated frames 10 ms apart, then two idle frames without the sheet, one undrawn.
        for i in range(10):
            slices += frame_slices(100 * (i + 1), 500 + i, 1000 + i * 10)
        slices += frame_slices(5000, 600, 2000, sheet=False)
        slices += frame_slices(6000, 601, 2010, sheet=False, draw=False)
        slices.append(slice_(9000, 100, 1001, 0.2, 1, "BrandNewSlice"))
        timeline = [
            {"vsync_id": 500, "ts": 1000 * MS, "dur": 12 * MS, "jank_type": "Buffer Stuffing", "on_time_finish": 0,
             "present_type": "Late Present", "expected_ts": 1000 * MS, "overrun_ns": 4 * MS},
            {"vsync_id": 600, "ts": 2000 * MS, "dur": 6 * MS, "jank_type": "None", "on_time_finish": 1,
             "present_type": "On-time Present", "expected_ts": 2000 * MS, "overrun_ns": -2 * MS},
        ]
        other = [{"thread_name": "binder:18940_2", "name": "releaseBuffer 7", "slice_count": 3,
                  "total_dur": 6 * MS, "max_dur": 3 * MS}]
        return tm.build_result("t.perfetto-trace", "S.m", 0, slices, timeline, other)

    def test_frames_and_windows(self):
        result = self.build()
        self.assertEqual(len(result.frames), 12)
        self.assertEqual(len(result.windows), 1)
        self.assertEqual([f.window for f in result.frames], ["animation"] * 10 + ["idle"] * 2)
        self.assertEqual([f.has_sheet for f in result.frames], [True] * 10 + [False] * 2)

    def test_cpu_time_spans_both_threads(self):
        first, undrawn = self.build().frames[0], self.build().frames[-1]
        self.assertAlmostEqual(first.cpu_ms, 7.0)
        self.assertAlmostEqual(first.render_after_ms, 0.5)
        self.assertFalse(undrawn.drew)
        self.assertAlmostEqual(undrawn.cpu_ms, 3.0)

    def test_sections_inherit_their_parents_category(self):
        frame = self.build().frames[1]
        # Compose:recompose 1.5 self plus the sheet section's 0.5, both recomposition.
        self.assertAlmostEqual(frame.categories["main.recomposition"], 2.0)
        self.assertAlmostEqual(frame.categories["rt.texture_upload"], 3.5)
        self.assertAlmostEqual(frame.sections["synthX.PlayerSheet"], 0.5)

    def test_timeline_join(self):
        frames = {f.vsync_id: f for f in self.build().frames}
        self.assertAlmostEqual(frames[500].overrun_ms, 4.0)
        self.assertEqual(frames[500].jank_type, "Buffer Stuffing")
        self.assertIsNone(frames[501].overrun_ms)

    def test_unknown_names_are_listed_not_dropped(self):
        result = self.build()
        self.assertEqual(list(result.unclassified), [("main", "BrandNewSlice")])
        self.assertAlmostEqual(result.frames[0].categories[sc.UNCLASSIFIED], 0.2)

    def test_aggregates(self):
        result = self.build()
        self.assertEqual(result.textures["1440x3064"]["count"], 11)
        self.assertEqual(result.other_threads[("binder:<n>_<n>", "releaseBuffer <n>")]["count"], 3)
        self.assertEqual(result.slice_stats[("main", "Choreographer#doFrame <n>")]["count"], 12)

    def test_empty_trace(self):
        result = tm.build_result("t", "S.m", 0, [], [], [])
        self.assertEqual(result.frames, [])
        self.assertTrue(result.notes)


def plain_frame(base_id, vsync, ts_ms, child):
    """One frame with no synthX section and no input: doFrame > [child], plus a RenderThread draw."""
    return [
        slice_(base_id, None, ts_ms, 3.0, 0, f"Choreographer#doFrame {vsync}", self_ms=1.0),
        slice_(base_id + 1, base_id, ts_ms + 0.1, 2.0, 1, child),
        slice_(base_id + 2, None, ts_ms + 3.0, 4.0, 0, f"DrawFrames {vsync}", role="render"),
    ]


class FlingWindowTest(unittest.TestCase):
    """A list fling as the queue scroll traces show it: layout on every frame, nothing else."""

    def build(self):
        slices = []
        # Twelve fling frames 16 ms apart that only re-lay out the list.
        for i in range(12):
            slices += plain_frame(100 * (i + 1), 700 + i, 1000 + i * 16, "AndroidOwner:measureAndLayout")
        # Then a pause: the background redraws every 66 ms and nothing is laid out.
        for i in range(6):
            slices += plain_frame(5000 + 100 * i, 800 + i, 1500 + i * 66, "AndroidOwner:draw")
        return tm.build_result("t.perfetto-trace", "S.m", 0, slices, [], [])

    def test_fling_frames_are_animation_and_redraws_are_idle(self):
        result = self.build()
        self.assertEqual(len(result.windows), 1)
        self.assertEqual([f.window for f in result.frames], ["animation"] * 12 + ["idle"] * 6)
        self.assertFalse(any(f.has_sheet for f in result.frames))

    def test_sparse_layout_passes_are_not_a_window(self):
        # One layout pass every 500 ms, as the mini player's progress updates produce.
        slices = []
        for i in range(8):
            slices += plain_frame(100 * (i + 1), 900 + i, 1000 + i * 500, "AndroidOwner:measureAndLayout")
        result = tm.build_result("t.perfetto-trace", "S.m", 0, slices, [], [])
        self.assertEqual(result.windows, [])
        self.assertEqual({f.window for f in result.frames}, {"idle"})


if __name__ == "__main__":
    unittest.main()
