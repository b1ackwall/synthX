#!/usr/bin/env python3
"""Measures the queue list's offscreen layer in one scenario of one or more benchmark runs.

Usage: ~/.venvs/perf/bin/python scripts/perf/queue_layer.py <run-folder> [<run-folder> ...]
           [--scenario METHOD] [--trace-processor PATH] [--json PATH]

Prints a markdown table with one column per run: how often the layer is rendered, its sizes and
reallocations, the RenderThread time it costs, and the list's main-thread work. The scenario is
the test method name (default queueExpandCollapse). --json writes every run's per-iteration
values, for comparisons across runs. Needs the `perfetto` pip package, as analyze_traces.py does.

Compose names every layer `graphicsLayer`, so the queue list's layer is found by size: it is as
wide as the widest layer in the trace (the screen) and taller than it is wide. The expanded
player's background has rendered at a quarter of the sheet's size (360 x 766 on the S25) since
commit 8f43e2d, so it never matches; in runs from before that commit its full-size redraws are
counted as the list's.

"Animation frames" are the frames of the animation windows; "drawn" ones reached the
RenderThread, and "sheet frames" are drawn ones that composed the player sheet. The over-budget
threshold is each trace's own vsync period (trace_model.vsync_period), or 16.67 ms when a trace
has too few back-to-back frames to measure it.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import slice_classes as sc
import trace_model as tm
from analyze_traces import find_traces, processor_factory
from perf_common import BUDGET_60HZ_MS, fmt, markdown_table, median, percentile

DEFAULT_SCENARIO = "queueExpandCollapse"
SLICE_COLUMNS = ("id", "parent_id", "ts", "dur", "depth", "name", "thread_role", "self_dur")
TIMELINE_COLUMNS = ("vsync_id", "ts", "dur", "jank_type", "on_time_finish", "present_type", "expected_ts", "overrun_ns")
FLUSH_LAYERS = "flush layers"
RENDER_PASS = "OpsTask::onPrepare"
ALLOCATE = "vkAllocateMemory("
FREE = "vkFreeMemory("
MASK_DRAW = "synthX.QueueMaskLayer.draw"
QUEUE_ROW = "synthX.QueueRow"
# HWUI's layer allocation step in px (inferred, see docs/perf/layer-discovery.md 2.5).
LAYER_STEP = 64
# Allocations at least this large are a layer about as big as the whole sheet.
LARGE_ALLOCATION_BYTES = 16 * 1024 * 1024
PER_FRAME_CATEGORIES = ("rt.gpu_memory", "rt.layers", "main.measure_layout", "main.recomposition")

_LAYER = re.compile(r"^drawLayer \[graphicsLayer\] (\d+)(?:\.\d+)? x (\d+)(?:\.\d+)?$")
# The allocation size is the argument before the memory type index.
_ALLOCATION = re.compile(r"(\d+), \d+\), \(VkAllocationCallbacks")


def layer_size(name: str) -> tuple[int, int] | None:
    """(width, height) in px from a `drawLayer [graphicsLayer] W x H` slice name."""
    match = _LAYER.match(name)
    return (int(match.group(1)), int(match.group(2))) if match else None


def allocation_bytes(name: str) -> int | None:
    """The requested size from a `vkAllocateMemory(...)` slice name."""
    match = _ALLOCATION.search(name) if name.startswith(ALLOCATE) else None
    return int(match.group(1)) if match else None


def surface_step(height: int) -> int:
    return -(-height // LAYER_STEP) * LAYER_STEP


def is_queue_layer(size: tuple, width: int) -> bool:
    """Whether a layer of [size] is the queue list's; [width] is the widest layer's width."""
    return size[0] == width and size[1] > size[0]


def queue_layer_heights(layers: list[tuple], width: int) -> list[int]:
    """Heights of the queue list layer's renders among one frame's layer sizes."""
    return [h for w, h in layers if is_queue_layer((w, h), width)]


def queue_layer_cost(layers: list[tuple], width: int, flush_ms: float) -> tuple[int, float]:
    """(renders, RenderThread ms) of the queue list layer in one frame. [layers] holds
    ((width, height), drawLayer ms) pairs; [flush_ms] is that frame's `flush layers` time, which
    is shared equally among the layers rendered in the frame (exact when the list is the only one)."""
    queue = [ms for size, ms in layers if is_queue_layer(size, width)]
    if not queue:
        return 0, 0.0
    return len(queue), sum(queue) + flush_ms * len(queue) / len(layers)


def has_ancestor(s: dict, by_id: dict, name: str) -> bool:
    parent = by_id.get(s["parent_id"])
    while parent is not None:
        if parent["name"] == name:
            return True
        parent = by_id.get(parent["parent_id"])
    return False


def frame_vsync(s: dict, by_id: dict, pattern) -> int | None:
    """The vsync id of the frame root [s] belongs to, if its root matches [pattern]."""
    root = pattern.match(by_id[s["root"]]["name"] or "")
    return int(root.group(1)) if root else None


def measure_trace(slices: list[dict], result) -> dict:
    """One trace's numbers; [slices] must already carry 'root' (trace_model.build_result)."""
    by_id = {s["id"]: s for s in slices}
    frame_layers: dict = {}
    frame_flush_ms: dict = {}
    frame_passes: dict = {}
    frame_masks: dict = {}
    frame_allocation_ms: dict = {}
    out = {
        "flush_layers_ms": 0.0, "flush_alloc_ms": 0.0, "flush_alloc_count": 0, "flush_alloc_max_ms": 0.0,
        "flush_free_ms": 0.0, "alloc_ms": 0.0, "alloc_count": 0, "large_alloc_ms": 0.0, "large_alloc_count": 0,
        "queue_rows": 0, "mask_draws": 0, "mask_ms": 0.0,
    }
    for s in slices:
        name = s["name"] or ""
        dur_ms = s["dur"] / tm.NS_PER_MS
        if s["thread_role"] == "main":
            if name == QUEUE_ROW:
                out["queue_rows"] += 1
            elif name == MASK_DRAW:
                vsync = frame_vsync(s, by_id, sc.MAIN_FRAME_ROOT)
                frame_masks[vsync] = frame_masks.get(vsync, 0) + 1
                out["mask_draws"] += 1
                out["mask_ms"] += dur_ms
            continue
        vsync = frame_vsync(s, by_id, sc.RENDER_FRAME_ROOT)
        size = layer_size(name)
        if size and vsync is not None:
            frame_layers.setdefault(vsync, []).append((size, dur_ms))
        elif name == FLUSH_LAYERS:
            out["flush_layers_ms"] += dur_ms
            frame_flush_ms[vsync] = frame_flush_ms.get(vsync, 0.0) + dur_ms
        elif RENDER_PASS in name:
            frame_passes[vsync] = frame_passes.get(vsync, 0) + 1
        elif name.startswith(ALLOCATE):
            out["alloc_ms"] += dur_ms
            out["alloc_count"] += 1
            if (allocation_bytes(name) or 0) >= LARGE_ALLOCATION_BYTES:
                out["large_alloc_ms"] += dur_ms
                out["large_alloc_count"] += 1
            if has_ancestor(s, by_id, FLUSH_LAYERS):
                out["flush_alloc_ms"] += dur_ms
                out["flush_alloc_count"] += 1
                out["flush_alloc_max_ms"] = max(out["flush_alloc_max_ms"], dur_ms)
                frame_allocation_ms[vsync] = frame_allocation_ms.get(vsync, 0.0) + dur_ms
        elif name.startswith(FREE) and has_ancestor(s, by_id, FLUSH_LAYERS):
            out["flush_free_ms"] += dur_ms

    every_layer = [size for layers in frame_layers.values() for size, _ in layers]
    width = max((w for w, _ in every_layer), default=0)
    queue_sizes = [size for size in every_layer if is_queue_layer(size, width)]
    sheet = max(queue_sizes, key=lambda size: size[1]) if queue_sizes else (0, 0)
    heights, changes, change_alloc_ms, previous, trace_renders = [], 0, 0.0, None, 0
    for frame in result.frames:
        drawn = queue_layer_heights([size for size, _ in frame_layers.get(frame.vsync_id, [])], width)
        trace_renders += len(drawn)
        heights += drawn
        changed = False
        for height in drawn:
            if surface_step(height) != previous:
                previous, changed = surface_step(height), True
                changes += 1
        if changed:
            change_alloc_ms += frame_allocation_ms.get(frame.vsync_id, 0.0)

    animation = [f for f in result.frames if f.window == "animation"]
    drawn_frames = [f for f in animation if f.drew]
    sheet_frames = [f for f in drawn_frames if f.has_sheet]
    renders, queue_rt_ms = 0, 0.0
    for frame in drawn_frames:
        count, ms = queue_layer_cost(
            frame_layers.get(frame.vsync_id, []), width, frame_flush_ms.get(frame.vsync_id, 0.0))
        renders += count
        queue_rt_ms += ms
    budget = result.vsync_period_ms or BUDGET_60HZ_MS
    out.update({
        "sheet": sheet, "queue_heights": heights, "surface_changes": changes,
        "change_alloc_ms": change_alloc_ms, "trace_renders": trace_renders,
        "renders": renders, "queue_rt_ms": queue_rt_ms,
        "rt_ms": sum(f.render_ms for f in drawn_frames),
        "gpu_flush_ms": sum(f.categories.get("rt.gpu_flush", 0.0) for f in animation),
        "passes": [frame_passes.get(f.vsync_id, 0) for f in sheet_frames],
        "drawn_passes": [frame_passes.get(f.vsync_id, 0) for f in drawn_frames],
        "sheet_mask_draws": sum(frame_masks.get(f.vsync_id, 0) for f in sheet_frames),
        "sheet_mask_ms": sum(f.sections.get(MASK_DRAW, 0.0) for f in sheet_frames),
        "cpu_ms": [f.cpu_ms for f in animation],
        "budget_ms": budget,
        "over_budget": sum(1 for f in animation if f.cpu_ms > budget),
        "vsync_period_ms": result.vsync_period_ms,
        "window_ms": sum((b - a) / tm.NS_PER_MS for a, b in result.windows),
        "animation_frames": len(animation),
        "drawn_frames": len(drawn_frames),
        "sheet_frames": len(sheet_frames),
        "categories": {c: sum(f.categories.get(c, 0.0) for f in animation) for c in PER_FRAME_CATEGORIES},
    })
    return out


def ratio(numerator: float, denominator: float) -> float | None:
    return numerator / denominator if denominator else None


def iteration_values(trace: dict) -> dict:
    """One trace's comparable values, for per-iteration spreads across runs."""
    cpu = trace["cpu_ms"]
    return {
        "iteration": trace.get("iteration"),
        "queue_renders": trace["renders"],
        "drawn_frames": trace["drawn_frames"],
        "renders_per_sheet_frame": ratio(trace["renders"], trace["sheet_frames"]),
        "renders_per_drawn_frame": ratio(trace["renders"], trace["drawn_frames"]),
        "mask_draws_per_sheet_frame": ratio(trace["sheet_mask_draws"], trace["sheet_frames"]),
        "passes_median": median(trace["passes"]) if trace["passes"] else None,
        "passes_mean": ratio(sum(trace["passes"]), len(trace["passes"])),
        "drawn_passes_mean": ratio(sum(trace["drawn_passes"]), len(trace["drawn_passes"])),
        "queue_rt_ms": trace["queue_rt_ms"],
        "rt_ms": trace["rt_ms"],
        "gpu_flush_per_frame": ratio(trace["gpu_flush_ms"], trace["animation_frames"]),
        "cpu_p50": percentile(cpu, 0.5) if cpu else None,
        "cpu_p90": percentile(cpu, 0.9) if cpu else None,
        "cpu_p99": percentile(cpu, 0.99) if cpu else None,
        "over_budget_share": ratio(trace["over_budget"], len(cpu)),
        "drawn_fps": ratio(trace["drawn_frames"] * 1000.0, trace["window_ms"]),
        "vsync_period_ms": trace["vsync_period_ms"],
    }


def combine(traces: list[dict]) -> dict:
    """Sums the per-trace numbers of one run and derives the per-frame and per-iteration ones."""
    total = lambda key: sum(t[key] for t in traces)  # noqa: E731
    heights = [h for t in traces for h in t["queue_heights"]]
    cpu = [ms for t in traces for ms in t["cpu_ms"]]
    passes = [p for t in traces for p in t["passes"]]
    drawn_passes = [p for t in traces for p in t["drawn_passes"]]
    periods = [t["vsync_period_ms"] for t in traces if t["vsync_period_ms"]]
    frames = total("animation_frames")
    sheet_frames = total("sheet_frames")
    count = len(traces)
    run = {key: total(key) for key in (
        "flush_layers_ms", "flush_alloc_ms", "flush_alloc_count", "flush_free_ms", "alloc_ms", "alloc_count",
        "large_alloc_ms", "large_alloc_count", "surface_changes", "change_alloc_ms", "mask_draws", "renders",
        "trace_renders", "drawn_frames", "over_budget")}
    run.update({
        "traces": count,
        "sheet": max((t["sheet"] for t in traces), key=lambda size: size[0] * size[1], default=(0, 0)),
        "distinct_sizes": len(set(heights)),
        "height_range": (min(heights), max(heights)) if heights else None,
        "flush_alloc_max_ms": max((t["flush_alloc_max_ms"] for t in traces), default=0.0),
        "animation_frames": frames,
        "sheet_frames": sheet_frames,
        "vsync_period_ms": sum(periods) / len(periods) if periods else None,
        "per_frame": {c: ratio(sum(t["categories"][c] for t in traces), frames) for c in PER_FRAME_CATEGORIES},
        "renders_per_sheet_frame": ratio(total("renders"), sheet_frames),
        "queue_rt_ms_per_iteration": ratio(total("queue_rt_ms"), count),
        "rt_ms_per_iteration": ratio(total("rt_ms"), count),
        "gpu_flush_per_frame": ratio(total("gpu_flush_ms"), frames),
        "passes": (median(passes), sum(passes) / len(passes)) if passes else None,
        "drawn_passes": (median(drawn_passes), sum(drawn_passes) / len(drawn_passes)) if drawn_passes else None,
        "queue_rows_per_iteration": ratio(total("queue_rows"), count),
        "mask_draws_per_sheet_frame": ratio(total("sheet_mask_draws"), sheet_frames),
        "mask_ms_per_sheet_frame": ratio(total("sheet_mask_ms"), sheet_frames),
        "cpu": tuple(percentile(cpu, f) for f in (0.5, 0.9, 0.99)) if cpu else None,
        "over_budget_share": ratio(total("over_budget"), len(cpu)),
        "drawn_fps": ratio(total("drawn_frames") * 1000.0, total("window_ms")),
        "iterations": [iteration_values(t) for t in traces],
    })
    return run


def render(runs: dict) -> str:
    """The comparison table; [runs] is {run name: combine() result} in column order."""
    def row(label, cell):
        return [label] + [cell(run) for run in runs.values()]

    def with_count(ms_key, count_key):
        return lambda r: f"{fmt(r[ms_key], 0)} ({r[count_key]})"

    def size_range(r):
        return "n/a" if r["height_range"] is None else f"{r['sheet'][0]} x {r['height_range'][0]}-{r['height_range'][1]}"

    def per_frame(category):
        return lambda r: fmt(r["per_frame"][category], 3)

    def percent(value):
        return "n/a" if value is None else f"{value * 100:.1f}%"

    rows = [
        row("Traces", lambda r: r["traces"]),
        row("Vsync period, ms (budget)", lambda r: fmt(r["vsync_period_ms"])),
        row("Animation frames (drawn)", lambda r: f"{r['animation_frames']} ({r['drawn_frames']})"),
        row("Queue list layer renders in drawn animation frames", lambda r: r["renders"]),
        row("Queue list layer renders per sheet frame", lambda r: fmt(r["renders_per_sheet_frame"], 3)),
        row("Queue list layer renders per drawn animation frame", lambda r: fmt(ratio(r["renders"], r["drawn_frames"]), 3)),
        row("Queue list layer renders, whole trace", lambda r: r["trace_renders"]),
        row("Queue list layer sizes", size_range),
        row("Queue list layer distinct sizes", lambda r: r["distinct_sizes"]),
        row(f"Queue list layer surface changes ({LAYER_STEP} px steps)", lambda r: r["surface_changes"]),
        row("`flush layers` allocation ms on those frames", lambda r: fmt(r["change_alloc_ms"], 0)),
        row("Render passes per sheet frame, median (mean)",
            lambda r: "n/a" if r["passes"] is None else f"{fmt(r['passes'][0], 0)} ({fmt(r['passes'][1])})"),
        row("Render passes per drawn animation frame, median (mean)",
            lambda r: "n/a" if r["drawn_passes"] is None else f"{fmt(r['drawn_passes'][0], 0)} ({fmt(r['drawn_passes'][1])})"),
        row("RenderThread ms on the queue list layer, per iteration", lambda r: fmt(r["queue_rt_ms_per_iteration"], 1)),
        row("RenderThread ms in drawn animation frames, per iteration", lambda r: fmt(r["rt_ms_per_iteration"], 1)),
        row("RT: GPU flush, ms per animation frame", lambda r: fmt(r["gpu_flush_per_frame"], 3)),
        row("`flush layers` total ms", lambda r: fmt(r["flush_layers_ms"], 0)),
        row("`vkAllocateMemory` in `flush layers`, ms (count)", with_count("flush_alloc_ms", "flush_alloc_count")),
        row("largest of those, ms", lambda r: fmt(r["flush_alloc_max_ms"], 1)),
        row("`vkFreeMemory` in `flush layers`, ms", lambda r: fmt(r["flush_free_ms"], 0)),
        row("`vkAllocateMemory` on the RenderThread, ms (count)", with_count("alloc_ms", "alloc_count")),
        row("of which 16 MiB and larger, ms (count)", with_count("large_alloc_ms", "large_alloc_count")),
        row("RT: GPU memory alloc/free, ms per animation frame", per_frame("rt.gpu_memory")),
        row("RT: layers, ms per animation frame", per_frame("rt.layers")),
        row("main: measure/layout, ms per animation frame", per_frame("main.measure_layout")),
        row("main: recomposition, ms per animation frame", per_frame("main.recomposition")),
        row(f"`{QUEUE_ROW}` compositions per iteration", lambda r: fmt(r["queue_rows_per_iteration"], 1)),
        row(f"`{MASK_DRAW}` per sheet frame", lambda r: fmt(r["mask_draws_per_sheet_frame"], 3)),
        row(f"`{MASK_DRAW}` ms per sheet frame", lambda r: fmt(r["mask_ms_per_sheet_frame"], 3)),
        row("cpu p50 / p90 / p99 ms, animation frames",
            lambda r: "n/a" if r["cpu"] is None else " / ".join(fmt(v) for v in r["cpu"])),
        row("Animation frames over the vsync period", lambda r: percent(r["over_budget_share"])),
        row("Drawn fps in animation windows", lambda r: fmt(r["drawn_fps"], 1)),
    ]
    return markdown_table(["Metric"] + list(runs), rows)


def measure_run(folder: Path, scenario: str, open_trace) -> dict | None:
    traces = [
        (key, iteration, path)
        for key, items in find_traces(folder, scenario, None).items()
        if key.rsplit(".", 1)[-1] == scenario
        for iteration, path in items
    ]
    measured = []
    for key, iteration, path in traces:
        processor = open_trace(path)
        try:
            slices = tm.query(processor, "thread_slices.sql", SLICE_COLUMNS)
            try:
                timeline = tm.query(processor, "frame_timeline.sql", TIMELINE_COLUMNS)
            except Exception:  # The tables are missing on traces without frame timeline data.
                timeline = []
        finally:
            processor.close()
        if not slices:
            print(f"{path.name}: no slices for the benchmark app, skipped", file=sys.stderr)
            continue
        trace = measure_trace(slices, tm.build_result(path.name, key, iteration, slices, timeline, []))
        trace["iteration"] = iteration
        measured.append(trace)
    return combine(measured) if measured else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_folders", type=Path, nargs="+")
    parser.add_argument("--scenario", default=DEFAULT_SCENARIO, help="test method name (default %(default)s)")
    parser.add_argument("--trace-processor", type=Path, help="path to a trace_processor_shell binary")
    parser.add_argument("--json", type=Path, help="also write each run's per-iteration values here")
    args = parser.parse_args(argv)
    for folder in args.run_folders:
        if not folder.is_dir():
            parser.error(f"{folder} is not a folder")
    open_trace = processor_factory(args.trace_processor)
    runs = {}
    for folder in args.run_folders:
        run = measure_run(folder, args.scenario, open_trace)
        if run is None:
            print(f"No {args.scenario} traces with app slices in {folder}", file=sys.stderr)
            return 1
        runs[folder.name] = run
        print(f"Measured {folder.name}: {run['traces']} trace(s)", file=sys.stderr)
    print(f"## {args.scenario}\n\n{render(runs)}")
    if args.json:
        payload = {name: run["iterations"] for name, run in runs.items()}
        args.json.write_text(json.dumps({"scenario": args.scenario, "runs": payload}, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
