#!/usr/bin/env python3
"""Measures the queue list's offscreen layer in one scenario of one or more benchmark runs.

Usage: ~/.venvs/perf/bin/python scripts/perf/queue_layer.py <run-folder> [<run-folder> ...]
           [--scenario METHOD] [--trace-processor PATH]

Prints a markdown table with one column per run: the layer's sizes and reallocations, the
layer and memory work on the RenderThread, and the list's main-thread work. The scenario is the
test method name (default queueExpandCollapse). Needs the `perfetto` pip package, as
analyze_traces.py does.

Compose names every layer `graphicsLayer`, so the queue list's layer is found by size: it is as
wide as the sheet and more than half as tall as the tallest such layer. The player background
has the full sheet size too; a frame's full-size redraw is counted as the background's when the
frame ran the background's draw section, and the queue list's otherwise.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import slice_classes as sc
import trace_model as tm
from analyze_traces import find_traces, processor_factory
from perf_common import BUDGET_60HZ_MS, fmt, markdown_table, percentile

DEFAULT_SCENARIO = "queueExpandCollapse"
SLICE_COLUMNS = ("id", "parent_id", "ts", "dur", "depth", "name", "thread_role", "self_dur")
TIMELINE_COLUMNS = ("vsync_id", "ts", "dur", "jank_type", "on_time_finish", "present_type", "expected_ts", "overrun_ns")
FLUSH_LAYERS = "flush layers"
ALLOCATE = "vkAllocateMemory("
FREE = "vkFreeMemory("
BACKGROUND_DRAW = "synthX.PlayerBackground.draw"
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


def queue_layer_heights(layers: list[tuple], sheet: tuple, background_drawn: bool) -> list[int]:
    """Heights of the queue list layer's redraws among one frame's layers; [sheet] is the
    full sheet's (width, height)."""
    width, full_height = sheet
    tall = [h for w, h in layers if w == width and h * 2 > full_height]
    if background_drawn and full_height in tall:
        tall.remove(full_height)
    return tall


def has_ancestor(s: dict, by_id: dict, name: str) -> bool:
    parent = by_id.get(s["parent_id"])
    while parent is not None:
        if parent["name"] == name:
            return True
        parent = by_id.get(parent["parent_id"])
    return False


def measure_trace(slices: list[dict], result) -> dict:
    """One trace's numbers; [slices] must already carry 'root' (trace_model.build_result)."""
    by_id = {s["id"]: s for s in slices}
    frame_layers: dict = {}
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
                out["mask_draws"] += 1
                out["mask_ms"] += dur_ms
            continue
        root = sc.RENDER_FRAME_ROOT.match(by_id[s["root"]]["name"] or "")
        vsync = int(root.group(1)) if root else None
        size = layer_size(name)
        if size and vsync is not None:
            frame_layers.setdefault(vsync, []).append(size)
        elif name == FLUSH_LAYERS:
            out["flush_layers_ms"] += dur_ms
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

    every_layer = [size for layers in frame_layers.values() for size in layers]
    sheet = max(every_layer, key=lambda size: size[0] * size[1]) if every_layer else (0, 0)
    heights, changes, change_alloc_ms, previous = [], 0, 0.0, None
    for frame in result.frames:
        drawn = queue_layer_heights(
            frame_layers.get(frame.vsync_id, []), sheet, BACKGROUND_DRAW in frame.sections)
        changed = False
        for height in drawn:
            heights.append(height)
            if surface_step(height) != previous:
                previous, changed = surface_step(height), True
                changes += 1
        if changed:
            change_alloc_ms += frame_allocation_ms.get(frame.vsync_id, 0.0)
    animation = [f for f in result.frames if f.window == "animation"]
    out.update({
        "sheet": sheet, "queue_heights": heights, "surface_changes": changes,
        "change_alloc_ms": change_alloc_ms,
        "cpu_ms": [f.cpu_ms for f in animation],
        "animation_frames": len(animation),
        "sheet_frames": sum(1 for f in result.frames if f.has_sheet),
        "categories": {c: sum(f.categories.get(c, 0.0) for f in animation) for c in PER_FRAME_CATEGORIES},
    })
    return out


def combine(traces: list[dict]) -> dict:
    """Sums the per-trace numbers of one run and derives the per-frame and per-iteration ones."""
    total = lambda key: sum(t[key] for t in traces)  # noqa: E731
    heights = [h for t in traces for h in t["queue_heights"]]
    cpu = [ms for t in traces for ms in t["cpu_ms"]]
    frames = total("animation_frames")
    sheet_frames = total("sheet_frames")
    run = {key: total(key) for key in (
        "flush_layers_ms", "flush_alloc_ms", "flush_alloc_count", "flush_free_ms", "alloc_ms", "alloc_count",
        "large_alloc_ms", "large_alloc_count", "surface_changes", "change_alloc_ms", "mask_draws")}
    run.update({
        "traces": len(traces),
        "sheet": max((t["sheet"] for t in traces), key=lambda size: size[0] * size[1], default=(0, 0)),
        "redraws": len(heights),
        "distinct_sizes": len(set(heights)),
        "height_range": (min(heights), max(heights)) if heights else None,
        "flush_alloc_max_ms": max((t["flush_alloc_max_ms"] for t in traces), default=0.0),
        "animation_frames": frames,
        "sheet_frames": sheet_frames,
        "per_frame": {c: sum(t["categories"][c] for t in traces) / frames if frames else None
                      for c in PER_FRAME_CATEGORIES},
        "queue_rows_per_iteration": total("queue_rows") / len(traces) if traces else None,
        "mask_draws_per_sheet_frame": total("mask_draws") / sheet_frames if sheet_frames else None,
        "mask_ms_per_sheet_frame": total("mask_ms") / sheet_frames if sheet_frames else None,
        "cpu": tuple(percentile(cpu, f) for f in (0.5, 0.9, 0.99)) if cpu else None,
        "over_budget_share": sum(1 for ms in cpu if ms > BUDGET_60HZ_MS) / len(cpu) if cpu else None,
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

    rows = [
        row("Traces", lambda r: r["traces"]),
        row("Queue list layer redraws", lambda r: r["redraws"]),
        row("Queue list layer sizes", size_range),
        row("Queue list layer distinct sizes", lambda r: r["distinct_sizes"]),
        row(f"Queue list layer surface changes ({LAYER_STEP} px steps)", lambda r: r["surface_changes"]),
        row("`flush layers` allocation ms on those frames", lambda r: fmt(r["change_alloc_ms"], 0)),
        row("`flush layers` total ms", lambda r: fmt(r["flush_layers_ms"], 0)),
        row("`vkAllocateMemory` in `flush layers`, ms (count)", with_count("flush_alloc_ms", "flush_alloc_count")),
        row("largest of those, ms", lambda r: fmt(r["flush_alloc_max_ms"], 1)),
        row("`vkFreeMemory` in `flush layers`, ms", lambda r: fmt(r["flush_free_ms"], 0)),
        row("`vkAllocateMemory` on the RenderThread, ms (count)", with_count("alloc_ms", "alloc_count")),
        row("of which 16 MiB and larger, ms (count)", with_count("large_alloc_ms", "large_alloc_count")),
        row("Animation frames", lambda r: r["animation_frames"]),
        row("RT: GPU memory alloc/free, ms per animation frame", per_frame("rt.gpu_memory")),
        row("RT: layers, ms per animation frame", per_frame("rt.layers")),
        row("main: measure/layout, ms per animation frame", per_frame("main.measure_layout")),
        row("main: recomposition, ms per animation frame", per_frame("main.recomposition")),
        row(f"`{QUEUE_ROW}` compositions per iteration", lambda r: fmt(r["queue_rows_per_iteration"], 1)),
        row(f"`{MASK_DRAW}` per sheet frame", lambda r: fmt(r["mask_draws_per_sheet_frame"], 2)),
        row(f"`{MASK_DRAW}` ms per sheet frame", lambda r: fmt(r["mask_ms_per_sheet_frame"], 3)),
        row("cpu p50 / p90 / p99 ms, animation frames",
            lambda r: "n/a" if r["cpu"] is None else " / ".join(fmt(v) for v in r["cpu"])),
        row(f"Animation frames over {BUDGET_60HZ_MS:.2f} ms",
            lambda r: "n/a" if r["over_budget_share"] is None else f"{r['over_budget_share'] * 100:.1f}%"),
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
        measured.append(measure_trace(slices, tm.build_result(path.name, key, iteration, slices, timeline, [])))
    return combine(measured) if measured else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_folders", type=Path, nargs="+")
    parser.add_argument("--scenario", default=DEFAULT_SCENARIO, help="test method name (default %(default)s)")
    parser.add_argument("--trace-processor", type=Path, help="path to a trace_processor_shell binary")
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
