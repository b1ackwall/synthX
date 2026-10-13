"""Markdown report and per-frame CSV for analyze_traces.py, from trace_model results."""

from __future__ import annotations

import csv
from pathlib import Path

import slice_classes as sc
from perf_common import fmt, markdown_table, percentile

WINDOWS = ("animation", "idle")
CSV_FIXED = [
    "scenario", "trace", "iteration", "vsync_id", "window", "ts_ms", "cpu_ms", "main_ms", "render_ms",
    "render_after_ms", "timeline_ms", "overrun_ms", "jank_type", "on_time_finish", "sheet_composed", "drew",
]


def pct(values: list[float], fraction: float) -> str:
    return fmt(percentile(values, fraction)) if values else "n/a"


def merge_stats(results: list, attribute: str) -> dict:
    merged: dict = {}
    for result in results:
        for key, stat in getattr(result, attribute).items():
            target = merged.setdefault(key, {"count": 0, "total_ms": 0.0, "self_ms": 0.0, "max_ms": 0.0, "category": stat.get("category")})
            target["count"] += stat["count"]
            target["total_ms"] += stat["total_ms"]
            target["self_ms"] += stat.get("self_ms", 0.0)
            target["max_ms"] = max(target["max_ms"], stat["max_ms"])
    return merged


def frame_time_table(frames_by_window: dict) -> str:
    rows = []
    for window in WINDOWS:
        frames = frames_by_window[window]
        cpu = [f.cpu_ms for f in frames]
        main = [f.main_ms for f in frames]
        render = [f.render_ms for f in frames if f.render_ms is not None]
        timed = [f for f in frames if f.overrun_ms is not None]
        missed = sum(1 for f in timed if f.overrun_ms > 0)
        rows.append([
            window, len(frames), len(render),
            pct(cpu, 0.5), pct(cpu, 0.9), pct(cpu, 0.95), pct(cpu, 0.99), fmt(max(cpu)) if cpu else "n/a",
            pct(main, 0.5), pct(main, 0.9), pct(render, 0.5), pct(render, 0.9),
            f"{missed}/{len(timed)}" if timed else "n/a",
        ])
    return markdown_table(
        ["window", "frames", "drew", "cpu p50", "cpu p90", "cpu p95", "cpu p99", "cpu max",
         "main p50", "main p90", "RT p50", "RT p90", "deadline missed"],
        rows,
    )


def category_table(frames_by_window: dict) -> str:
    rows = []
    for category, label in sc.CATEGORIES.items():
        cells = []
        for window in WINDOWS:
            frames = frames_by_window[window]
            total = sum(f.categories.get(category, 0.0) for f in frames)
            cells.append(fmt(total / len(frames), 3) if frames else "n/a")
        if any(cell not in ("n/a", "0.000") for cell in cells):
            rows.append([label] + cells)
    return markdown_table(["self time, mean ms per frame", "animation", "idle"], rows)


def section_table(frames_by_window: dict) -> str | None:
    names = sorted({name for frames in frames_by_window.values() for f in frames for name in f.sections})
    if not names:
        return None
    rows = []
    for name in names:
        cells = []
        for window in WINDOWS:
            frames = frames_by_window[window]
            hit = [f.sections[name] for f in frames if name in f.sections]
            cells += [f"{len(hit)}/{len(frames)}", fmt(sum(hit), 1), fmt(max(hit), 3) if hit else "n/a"]
        rows.append([name] + cells)
    rows.sort(key=lambda row: -float(row[2]))
    return markdown_table(
        ["section", "anim frames", "anim total ms", "anim max ms", "idle frames", "idle total ms", "idle max ms"], rows)


def breakdown(frame, prefix: str, limit: int = 4) -> str:
    parts = sorted(((ms, cat) for cat, ms in frame.categories.items() if cat.startswith(prefix)), reverse=True)
    return ", ".join(f"{sc.CATEGORIES.get(cat, cat).split(': ', 1)[-1]} {ms:.1f}" for ms, cat in parts[:limit] if ms >= 0.05)


def slowest_table(tagged_frames: list, count: int) -> str:
    """[(result, frame)] for animation frames, slowest CPU time first."""
    ranked = sorted(tagged_frames, key=lambda pair: -pair[1].cpu_ms)[:count]
    rows = []
    for result, frame in ranked:
        sections = sorted(frame.sections.items(), key=lambda item: -item[1])[:3]
        rows.append([
            f"iter{result.iteration:03d}", frame.vsync_id, fmt((frame.ts - result.start_ts) / 1e6, 0),
            fmt(frame.cpu_ms), fmt(frame.main_ms), fmt(frame.render_ms), fmt(frame.overrun_ms),
            frame.jank_type or "n/a", breakdown(frame, "main."), breakdown(frame, "rt."),
            ", ".join(f"{name.removeprefix(sc.SECTION_PREFIX)} {ms:.2f}" for name, ms in sections) or "none",
        ])
    return markdown_table(
        ["trace", "vsync", "t ms", "cpu ms", "main ms", "RT ms", "overrun ms", "jank type",
         "main thread (self ms)", "RenderThread (self ms)", "synthX sections (ms)"],
        rows,
    )


def top_slices_table(stats: dict, role: str, sort_key: str, count: int) -> str:
    ranked = sorted(((key[1], stat) for key, stat in stats.items() if key[0] == role), key=lambda item: -item[1][sort_key])
    rows = [[name, sc.CATEGORIES.get(stat["category"], stat["category"]), stat["count"], fmt(stat["total_ms"], 1),
             fmt(stat["self_ms"], 1), fmt(stat["max_ms"])] for name, stat in ranked[:count]]
    return markdown_table(["slice", "class", "count", "total ms", "self ms", "max ms"], rows)


def no_sheet_section(frames: list, top: int) -> list[str]:
    """What the frames without a PlayerSheet composition were doing."""
    without = [f for f in frames if not f.has_sheet]
    lines = [
        f"{len(frames)} frames, {len(frames) - len(without)} with a `{sc.SHEET_SECTION}` composition, "
        f"{len(without)} without. Of those without, {sum(1 for f in without if f.drew)} still drew "
        f"(reached the RenderThread) and {sum(1 for f in without if not f.drew)} ran callbacks only.",
    ]
    if not without:
        return lines
    drew = [f for f in without if f.drew]
    if drew:
        lines.append(f"The ones that drew cost a mean {sum(f.cpu_ms for f in drew) / len(drew):.2f} ms of CPU time "
                     f"each ({sum(f.render_ms for f in drew) / len(drew):.2f} ms of it on the RenderThread).")
    usage: dict = {}
    for frame in without:
        for (role, name), ms in frame.self_by_name.items():
            if role != "main":
                continue
            entry = usage.setdefault(name, [0, 0.0])
            entry[0] += 1
            entry[1] += ms
    ranked = sorted(usage.items(), key=lambda item: -item[1][1])[:top]
    lines += ["", markdown_table(
        ["main-thread slice in frames without the sheet", "frames", "self ms total", "self ms per frame"],
        [[name, frames_with, fmt(ms, 1), fmt(ms / frames_with, 3)] for name, (frames_with, ms) in ranked],
    ), "", "A frame that only runs `Recomposer:animation` is a Compose frame-clock client (an animation or a "
           "`withFrameNanos` loop) asking for the next frame without changing anything on screen."]
    return lines


def scenario_report(scenario: str, results: list, device_hz: float | None, top: int, slowest: int) -> list[str]:
    lines = [f"## {scenario}", ""]
    tagged = [(result, frame) for result in results for frame in result.frames]
    frames_by_window = {window: [f for _, f in tagged if f.window == window] for window in WINDOWS}
    periods = [r.vsync_period_ms for r in results if r.vsync_period_ms]
    window_ms = sum((b - a) / 1e6 for r in results for a, b in r.windows)
    lines.append(f"- Traces: {len(results)} (iterations {', '.join(str(r.iteration) for r in results)})")
    if periods:
        period = sum(periods) / len(periods)
        lines.append(f"- Vsync period in the traces: {period:.2f} ms ({1000 / period:.0f} Hz, from {results[0].vsync_source})")
        if device_hz and abs(1000 / period - device_hz) > 5:
            lines.append(f"- MISMATCH: device.json recorded {device_hz:g} Hz, the traces ran at {1000 / period:.0f} Hz. "
                         "The panel changed rate during the run; budgets from device.json are wrong for these frames.")
    lines.append(f"- Animation windows: {sum(len(r.windows) for r in results)} in total, {window_ms:.0f} ms; "
                 f"{len(frames_by_window['animation'])} animation frames, {len(frames_by_window['idle'])} idle frames")
    for result in results:
        lines += [f"- Note ({result.name}): {note}" for note in result.notes]
    if not tagged:
        return lines + ["", "No frames found for this scenario.", ""]

    lines += ["", "### Frame times: animation windows vs idle", "", frame_time_table(frames_by_window)]
    jank = {}
    for window in WINDOWS:
        for frame in frames_by_window[window]:
            if frame.jank_type:
                jank[(window, frame.jank_type)] = jank.get((window, frame.jank_type), 0) + 1
    if jank:
        lines += ["", markdown_table(["window", "frame timeline jank type", "frames"],
                                     [[w, t, n] for (w, t), n in sorted(jank.items(), key=lambda item: (item[0][0], -item[1]))])]
    lines += ["", "### Where the time goes", "", category_table(frames_by_window)]
    sections = section_table(frames_by_window)
    if sections:
        lines += ["", "### synthX sections", "", sections]
    animation = [(r, f) for r, f in tagged if f.window == "animation"]
    if animation:
        lines += ["", f"### Slowest {min(slowest, len(animation))} animation frames", "", slowest_table(animation, slowest)]

    stats = merge_stats(results, "slice_stats")
    for role, label in (("main", "Main thread"), ("render", "RenderThread")):
        lines += ["", f"### {label}: top slices by total time", "", top_slices_table(stats, role, "total_ms", top)]
        lines += ["", f"### {label}: top slices by longest single run", "", top_slices_table(stats, role, "max_ms", top)]
    others = merge_stats(results, "other_threads")
    if others:
        ranked = sorted(others.items(), key=lambda item: -item[1]["total_ms"])[:top]
        lines += ["", "### Other app threads (top-level slices)", "", markdown_table(
            ["thread", "slice", "count", "total ms", "max ms"],
            [[key[0], key[1], stat["count"], fmt(stat["total_ms"], 1), fmt(stat["max_ms"])] for key, stat in ranked])]
    textures = merge_stats(results, "textures")
    if textures:
        ranked = sorted(textures.items(), key=lambda item: -item[1]["total_ms"])[:top]
        lines += ["", "### RenderThread texture uploads by size", "",
                  f"{len(textures)} distinct sizes, {sum(s['count'] for s in textures.values())} uploads, "
                  f"{sum(s['total_ms'] for s in textures.values()):.0f} ms in total. Many sizes that differ by a few "
                  "pixels mean a full-size layer or bitmap is re-uploaded as it resizes.", "",
                  markdown_table(["size", "uploads", "total ms", "max ms"],
                                 [[size, stat["count"], fmt(stat["total_ms"], 1), fmt(stat["max_ms"])] for size, stat in ranked])]
    lines += ["", "### Frames without a PlayerSheet composition", ""] + no_sheet_section([f for _, f in tagged], top)
    unclassified = merge_stats(results, "unclassified")
    lines += ["", "### Unclassified slice names", ""]
    if unclassified:
        ranked = sorted(unclassified.items(), key=lambda item: -item[1]["self_ms"])
        lines += ["Add these to scripts/perf/slice_classes.py:", "", markdown_table(
            ["slice", "thread", "count", "self ms", "max ms"],
            [[key[1], key[0], stat["count"], fmt(stat["self_ms"], 1), fmt(stat["max_ms"])] for key, stat in ranked])]
    else:
        lines.append("None: every slice on the main thread and RenderThread matched a rule.")
    return lines + [""]


def render_report(run_name: str, by_scenario: dict, device_hz: float | None, top: int, slowest: int) -> str:
    lines = [
        f"# Trace analysis: {run_name}",
        "",
        "Frames are split into animation windows (dense runs of synthX.* sections or input events, plus a "
        "margin) and idle frames (everything else, mostly the fixed pauses). cpu ms is main-thread start to "
        "RenderThread end, the same span as frameDurationCpuMs. Breakdowns use self time.",
        "",
    ]
    for scenario in sorted(by_scenario):
        lines += scenario_report(scenario, by_scenario[scenario], device_hz, top, slowest)
    return "\n".join(lines)


def write_csv(path: Path, by_scenario: dict) -> int:
    categories = list(sc.CATEGORIES)
    rows = 0
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(CSV_FIXED + [f"{c}_ms" for c in categories] + ["synthx_sections_ms"])
        for scenario in sorted(by_scenario):
            for result in by_scenario[scenario]:
                for f in result.frames:
                    writer.writerow([
                        scenario, result.name, result.iteration, f.vsync_id, f.window,
                        round((f.ts - result.start_ts) / 1e6, 3), round(f.cpu_ms, 3), round(f.main_ms, 3),
                        "" if f.render_ms is None else round(f.render_ms, 3), round(f.render_after_ms, 3),
                        "" if f.timeline_ms is None else round(f.timeline_ms, 3),
                        "" if f.overrun_ms is None else round(f.overrun_ms, 3),
                        f.jank_type or "", "" if f.on_time_finish is None else f.on_time_finish,
                        int(f.has_sheet), int(f.drew),
                    ] + [round(f.categories.get(c, 0.0), 3) for c in categories]
                      + [round(sum(f.sections.values()), 3)])
                    rows += 1
    return rows
