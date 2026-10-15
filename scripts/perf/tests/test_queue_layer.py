import unittest

import _support  # noqa: F401  (puts scripts/perf on the path)
import queue_layer as ql
import trace_model as tm

MS = 1_000_000
# Names as they appear in the S25 traces.
ALLOCATION_18MB = (
    "vkAllocateMemory(2292732880, (VkMemoryAllocateInfo*)(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, "
    "(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO )18186240, 0), (VkAllocationCallbacks*) 0x0, "
    "(VkDeviceMemory*) 0x783f70bd88);"
)
ALLOCATION_SMALL = (
    "vkAllocateMemory(2292732880, (VkMemoryAllocateInfo*)(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, "
    "0x00000000, 524288, 0), (VkAllocationCallbacks*) 0x0, (VkDeviceMemory*) 0x783f70bd28);"
)
FREE = "vkFreeMemory(2292732880, -5476376627582453392, (VkAllocationCallbacks*) 0x0);"


def slice_(id_, parent, ts_ms, dur_ms, depth, name, role="main", self_ms=None):
    return {
        "id": id_, "parent_id": parent, "ts": int(ts_ms * MS), "dur": int(dur_ms * MS), "depth": depth,
        "name": name, "thread_role": role, "self_dur": int((dur_ms if self_ms is None else self_ms) * MS),
    }


def frame(base, vsync, ts_ms, layers, main_sections=(), flush=()):
    """One frame: doFrame with [main_sections], and a DrawFrames holding [layers] (heights of
    1440-wide layers, or (width, height) pairs) and a `flush layers` whose children are [flush]
    (name, ms) pairs."""
    rows = [slice_(base, None, ts_ms, 3.0, 0, f"Choreographer#doFrame {vsync}", self_ms=1.0)]
    rows.append(slice_(base + 1, base, ts_ms + 0.1, 1.0, 1, "synthX.PlayerSheet"))
    for index, name in enumerate(main_sections):
        rows.append(slice_(base + 2 + index, base, ts_ms + 1.2 + index * 0.2, 0.1, 1, name))
    render = base + 20
    rows.append(slice_(render, None, ts_ms + 3.0, 9.0, 0, f"DrawFrames {vsync}", role="render", self_ms=1.0))
    for index, layer in enumerate(layers):
        width, height = layer if isinstance(layer, tuple) else (1440, layer)
        rows.append(slice_(render + 1 + index, render, ts_ms + 3.1 + index * 0.1, 0.05, 1,
                           f"drawLayer [graphicsLayer] {width}.0 x {height}.0", role="render"))
    flush_ms = sum(ms for _, ms in flush)
    rows.append(slice_(render + 10, render, ts_ms + 4.0, flush_ms + 0.5, 1, "flush layers", role="render", self_ms=0.5))
    at = ts_ms + 4.1
    for index, (name, ms) in enumerate(flush):
        rows.append(slice_(render + 11 + index, render + 10, at, ms, 2, name, role="render"))
        at += ms
    return rows


def measured(slices):
    slices = sorted(slices, key=lambda s: s["ts"])
    return ql.measure_trace(slices, tm.build_result("t", "PlayerQueueBenchmark.queueExpandCollapse", 0, slices, [], []))


class NameParsingTest(unittest.TestCase):
    def test_layer_size(self):
        self.assertEqual(ql.layer_size("drawLayer [graphicsLayer] 1440.0 x 2493.0"), (1440, 2493))
        self.assertIsNone(ql.layer_size("alpha caused saveLayer 1440x135"))
        self.assertIsNone(ql.layer_size("flush layers"))

    def test_allocation_bytes(self):
        self.assertEqual(ql.allocation_bytes(ALLOCATION_18MB), 18186240)
        self.assertEqual(ql.allocation_bytes(ALLOCATION_SMALL), 524288)
        self.assertIsNone(ql.allocation_bytes(FREE))

    def test_surface_step_rounds_up(self):
        self.assertEqual(ql.surface_step(3008), 3008)
        self.assertEqual(ql.surface_step(3009), 3072)
        self.assertEqual(ql.surface_step(3064), 3072)


class QueueLayerIdentityTest(unittest.TestCase):
    WIDTH = 1440

    def test_short_wide_and_narrow_layers_are_not_the_list(self):
        layers = [(1440, 135), (1260, 510), (1440, 1125), (1440, 2493), (1215, 1215)]
        self.assertEqual(ql.queue_layer_heights(layers, self.WIDTH), [2493])

    def test_every_full_size_render_is_the_list_and_the_quarter_res_background_is_not(self):
        self.assertEqual(ql.queue_layer_heights([(1440, 3064)], self.WIDTH), [3064])
        self.assertEqual(ql.queue_layer_heights([(1440, 3064), (360, 766)], self.WIDTH), [3064])
        self.assertEqual(ql.queue_layer_heights([(360, 766)], self.WIDTH), [])

    def test_flush_layers_is_shared_among_the_frame_s_layers(self):
        layers = [((1440, 3064), 0.2), ((1185, 1185), 0.1)]
        renders, ms = ql.queue_layer_cost(layers, self.WIDTH, 1.0)
        self.assertEqual(renders, 1)
        self.assertAlmostEqual(ms, 0.2 + 0.5)
        self.assertEqual(ql.queue_layer_cost([((1440, 3064), 0.2)], self.WIDTH, 1.0)[0], 1)
        self.assertAlmostEqual(ql.queue_layer_cost([((1440, 3064), 0.2)], self.WIDTH, 1.0)[1], 1.2)
        self.assertEqual(ql.queue_layer_cost([((1185, 1185), 0.1)], self.WIDTH, 1.0), (0, 0.0))


class MeasureTraceTest(unittest.TestCase):
    def test_a_layer_that_follows_the_sheet(self):
        slices = (
            frame(100, 1, 0, [3064], main_sections=["synthX.PlayerBackground.draw"])
            + frame(200, 2, 17, [2300], main_sections=["synthX.QueueMaskLayer.draw", "synthX.QueueRow"],
                    flush=[(ALLOCATION_18MB, 6.0), (FREE, 1.0)])
            + frame(300, 3, 34, [2301], main_sections=["synthX.QueueMaskLayer.draw"])
            + frame(400, 4, 51, [2400], main_sections=["synthX.QueueMaskLayer.draw"], flush=[(ALLOCATION_SMALL, 2.0)])
        )
        trace = measured(slices)
        self.assertEqual(trace["sheet"], (1440, 3064))
        self.assertEqual(trace["queue_heights"], [3064, 2300, 2301, 2400])
        # 3064 needs 3072; 2300 and 2301 share the 2304 step; 2400 needs 2432.
        self.assertEqual(trace["surface_changes"], 3)
        self.assertAlmostEqual(trace["change_alloc_ms"], 8.0)
        self.assertAlmostEqual(trace["flush_alloc_ms"], 8.0)
        self.assertEqual(trace["flush_alloc_count"], 2)
        self.assertAlmostEqual(trace["flush_alloc_max_ms"], 6.0)
        self.assertAlmostEqual(trace["flush_free_ms"], 1.0)
        self.assertEqual((trace["large_alloc_count"], trace["alloc_count"]), (1, 2))
        self.assertAlmostEqual(trace["flush_layers_ms"], 0.5 * 4 + 9.0)
        self.assertEqual((trace["queue_rows"], trace["mask_draws"], trace["sheet_frames"]), (1, 3, 4))
        # The four frames form one animation window, and each renders the list once.
        self.assertEqual((trace["renders"], trace["drawn_frames"], trace["sheet_mask_draws"]), (4, 4, 3))
        # drawLayer 0.05 ms each, plus each frame's whole `flush layers` (0.5 ms self + children).
        self.assertAlmostEqual(trace["queue_rt_ms"], 0.05 * 4 + 0.5 * 4 + 9.0)

    def test_a_constant_layer_changes_surface_once(self):
        slices = []
        for index in range(4):
            # The quarter-res background redraws in the second frame, next to the list.
            sections = ["synthX.QueueMaskLayer.draw"] + (["synthX.PlayerBackground.draw"] if index == 1 else [])
            layers = [3064, (360, 766)] if index == 1 else [3064]
            slices += frame(100 * (index + 1), index + 1, 17 * index, layers, main_sections=sections)
        trace = measured(slices)
        self.assertEqual(trace["queue_heights"], [3064] * 4)
        self.assertEqual(trace["surface_changes"], 1)

    def test_allocations_outside_flush_layers_are_only_in_the_thread_total(self):
        slices = frame(100, 1, 0, [3064], main_sections=["synthX.QueueMaskLayer.draw"])
        slices.append(slice_(190, 120, 11.0, 0.8, 1, ALLOCATION_SMALL, role="render"))
        trace = measured(slices)
        self.assertEqual((trace["alloc_count"], trace["flush_alloc_count"]), (1, 0))
        self.assertAlmostEqual(trace["alloc_ms"], 0.8)


class BudgetTest(unittest.TestCase):
    def test_the_threshold_is_the_trace_s_own_vsync_period(self):
        # Eight frames 8.33 ms apart; each runs 12 ms from doFrame start to RenderThread end.
        slices = []
        for index in range(8):
            slices += frame(100 * (index + 1), index + 1, 8.33 * index, [3064], main_sections=[ql.MASK_DRAW])
        trace = measured(slices)
        self.assertAlmostEqual(trace["vsync_period_ms"], 8.33, places=2)
        self.assertAlmostEqual(trace["budget_ms"], 8.33, places=2)
        self.assertEqual(trace["over_budget"], 8)

    def test_without_a_measurable_period_the_threshold_is_60_hz(self):
        trace = measured(frame(100, 1, 0, [3064]) + frame(200, 2, 17, [3064]))
        self.assertIsNone(trace["vsync_period_ms"])
        self.assertAlmostEqual(trace["budget_ms"], 1000 / 60)


class CombineAndRenderTest(unittest.TestCase):
    def test_two_iterations(self):
        one = measured(frame(100, 1, 0, [2300], main_sections=["synthX.QueueMaskLayer.draw", "synthX.QueueRow"])
                       + frame(200, 2, 17, [2400], main_sections=["synthX.QueueMaskLayer.draw"]))
        two = measured(frame(100, 1, 0, [2300], main_sections=["synthX.QueueRow"] * 3))
        run = ql.combine([one, two])
        self.assertEqual((run["traces"], run["trace_renders"], run["distinct_sizes"]), (2, 3, 2))
        self.assertEqual(run["height_range"], (2300, 2400))
        # Each trace starts with no surface, so its first redraw counts.
        self.assertEqual(run["surface_changes"], 3)
        self.assertAlmostEqual(run["queue_rows_per_iteration"], 2.0)
        # Neither trace has an animation window: its frames are under 50 ms in all.
        self.assertIsNone(run["mask_draws_per_sheet_frame"])
        self.assertEqual([i["queue_renders"] for i in run["iterations"]], [0, 0])
        table = ql.render({"runA": run, "runB": run})
        self.assertIn("| Metric | runA | runB |", table)
        self.assertIn("| Queue list layer sizes | 1440 x 2300-2400 | 1440 x 2300-2400 |", table)
        self.assertIn("| Queue list layer renders, whole trace | 3 | 3 |", table)

    def test_a_run_without_the_list_renders(self):
        run = ql.combine([measured(
            frame(100, 1, 0, [(360, 766), 1125], main_sections=["synthX.PlayerBackground.draw"])
            + frame(200, 2, 17, [135]))])
        self.assertEqual((run["trace_renders"], run["height_range"]), (0, None))
        self.assertIn("| Queue list layer sizes | n/a |", ql.render({"run": run}))


if __name__ == "__main__":
    unittest.main()
