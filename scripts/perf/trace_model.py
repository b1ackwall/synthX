"""Turns one Perfetto trace into per-frame records for analyze_traces.py.

The SQL lives in scripts/perf/sql/ so the same queries run in the Perfetto UI. Everything after
the queries is plain Python over the rows, so it can be tested without a trace (see tests/).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import slice_classes as sc
from perf_common import median

SQL_DIR = Path(__file__).resolve().parent / "sql"
NS_PER_MS = 1e6
# Expected-frame gaps longer than this are pauses between bursts, not the vsync period.
MAX_VSYNC_GAP_MS = 50.0
# Animation-window detection. The gap stays under the 66 ms at which the expanded player's
# background redraws, so that redraw cannot join two gestures into one window.
DEFAULT_GAP_MS = 50.0
DEFAULT_MIN_BURST_MS = 50.0
DEFAULT_MARGIN_MS = 50.0


@dataclass
class Frame:
    vsync_id: int
    ts: int
    main_ms: float
    render_ms: float | None = None
    render_end: int | None = None
    render_after_ms: float = 0.0
    window: str = "idle"
    has_sheet: bool = False
    timeline_ms: float | None = None
    overrun_ms: float | None = None
    jank_type: str | None = None
    on_time_finish: int | None = None
    categories: dict = field(default_factory=dict)
    sections: dict = field(default_factory=dict)
    self_by_name: dict = field(default_factory=dict)

    @property
    def drew(self) -> bool:
        return self.render_ms is not None

    @property
    def cpu_ms(self) -> float:
        """Main-thread start to RenderThread end, as the library's frameDurationCpuMs."""
        if self.render_end is None:
            return self.main_ms
        return (self.render_end - self.ts) / NS_PER_MS


@dataclass
class TraceResult:
    name: str
    scenario: str
    iteration: int
    frames: list = field(default_factory=list)
    windows: list = field(default_factory=list)
    start_ts: int = 0
    vsync_period_ms: float | None = None
    vsync_source: str | None = None
    slice_stats: dict = field(default_factory=dict)
    other_threads: dict = field(default_factory=dict)
    unclassified: dict = field(default_factory=dict)
    textures: dict = field(default_factory=dict)
    notes: list = field(default_factory=list)


def read_sql(name: str) -> str:
    return (SQL_DIR / name).read_text(encoding="utf-8")


def query(processor, sql_name: str, columns: tuple) -> list[dict]:
    return [{column: getattr(row, column) for column in columns} for row in processor.query(read_sql(sql_name))]


def resolve_categories(slices: list[dict]) -> None:
    """Sets 'category' and 'root' on every slice; synthX sections inherit from their parent."""
    by_id = {s["id"]: s for s in slices}

    def category_of(s: dict) -> str:
        if "category" in s:
            return s["category"]
        found = sc.classify(s["name"] or "", s["thread_role"])
        if found == sc.INHERIT:
            parent = by_id.get(s["parent_id"])
            found = category_of(parent) if parent else "other"
        s["category"] = found or sc.UNCLASSIFIED
        return s["category"]

    def root_of(s: dict) -> int:
        if "root" not in s:
            parent = by_id.get(s["parent_id"])
            s["root"] = root_of(parent) if parent else s["id"]
        return s["root"]

    for s in slices:
        category_of(s)
        root_of(s)


def find_windows(markers: list[tuple], gap_ms: float, min_burst_ms: float, margin_ms: float) -> list[tuple]:
    """Animation windows from (start, end) marker times in ns: runs with no gap over [gap_ms],
    at least [min_burst_ms] long, widened by [margin_ms] on both sides."""
    windows = []
    start = end = last_start = None
    for marker_start, marker_end in sorted(markers):
        if start is not None and (marker_start - last_start) / NS_PER_MS > gap_ms:
            windows.append((start, end))
            start = None
        if start is None:
            start, end = marker_start, marker_end
        end = max(end, marker_end)
        last_start = marker_start
    if start is not None:
        windows.append((start, end))
    margin = int(margin_ms * NS_PER_MS)
    return [(a - margin, b + margin) for a, b in windows if (b - a) / NS_PER_MS >= min_burst_ms]


def vsync_period(expected_starts: list[int], frame_starts: list[int]) -> tuple:
    """(period ms, source): the median gap between expected frames, else between doFrames."""
    for values, source in ((expected_starts, "frame timeline"), (frame_starts, "doFrame spacing")):
        ordered = sorted(set(values))
        gaps = [(b - a) / NS_PER_MS for a, b in zip(ordered, ordered[1:])]
        gaps = [gap for gap in gaps if 0 < gap <= MAX_VSYNC_GAP_MS]
        if len(gaps) >= 5:
            return median(gaps), source
    return None, None


def add_stat(table: dict, key, dur_ms: float, self_ms: float, category: str | None = None) -> None:
    stat = table.setdefault(key, {"count": 0, "total_ms": 0.0, "self_ms": 0.0, "max_ms": 0.0, "category": category})
    stat["count"] += 1
    stat["total_ms"] += dur_ms
    stat["self_ms"] += self_ms
    stat["max_ms"] = max(stat["max_ms"], dur_ms)


def build_frames(slices: list[dict], result: TraceResult) -> dict:
    """Creates one Frame per main-thread doFrame and attributes both threads' slices to it."""
    frames: dict[int, Frame] = {}
    main_root_to_vsync: dict[int, int] = {}
    render_root_to_vsync: dict[int, int] = {}
    current_render = None
    for s in slices:
        if s["depth"] != 0:
            continue
        name = s["name"] or ""
        if s["thread_role"] == "main":
            match = sc.MAIN_FRAME_ROOT.match(name)
            if match:
                vsync = int(match.group(1))
                frames[vsync] = Frame(vsync_id=vsync, ts=s["ts"], main_ms=s["dur"] / NS_PER_MS)
                main_root_to_vsync[s["id"]] = vsync
        else:
            match = sc.RENDER_FRAME_ROOT.match(name)
            if match:
                current_render = int(match.group(1))
                frame = frames.get(current_render)
                if frame:
                    frame.render_ms = s["dur"] / NS_PER_MS
                    frame.render_end = s["ts"] + s["dur"]
            elif current_render in frames:
                # RenderThread work between two frames belongs to the frame just drawn.
                frames[current_render].render_after_ms += s["dur"] / NS_PER_MS
            if current_render is not None:
                render_root_to_vsync[s["id"]] = current_render

    for s in slices:
        name = s["name"] or ""
        role = s["thread_role"]
        dur_ms, self_ms = s["dur"] / NS_PER_MS, s["self_dur"] / NS_PER_MS
        normalized = sc.normalize(name)
        add_stat(result.slice_stats, (role, normalized), dur_ms, self_ms, s["category"])
        if s["category"] == sc.UNCLASSIFIED:
            add_stat(result.unclassified, (role, normalized), dur_ms, self_ms)
        size = sc.texture_size(name) if role == "render" else None
        if size:
            add_stat(result.textures, size, dur_ms, self_ms)
        owners = main_root_to_vsync if role == "main" else render_root_to_vsync
        frame = frames.get(owners.get(s["root"]))
        if frame is None:
            continue
        frame.categories[s["category"]] = frame.categories.get(s["category"], 0.0) + self_ms
        key = (role, normalized)
        frame.self_by_name[key] = frame.self_by_name.get(key, 0.0) + self_ms
        if name.startswith(sc.SECTION_PREFIX):
            frame.sections[name] = frame.sections.get(name, 0.0) + dur_ms
            if name == sc.SHEET_SECTION:
                frame.has_sheet = True
    return frames


def apply_timeline(frames: dict, timeline: list[dict]) -> None:
    """Joins the system's frame timeline by vsync id; the longest row wins if a frame repeats."""
    for row in timeline:
        frame = frames.get(row["vsync_id"])
        if frame is None or row["dur"] is None:
            continue
        timeline_ms = row["dur"] / NS_PER_MS
        if frame.timeline_ms is not None and frame.timeline_ms >= timeline_ms:
            continue
        frame.timeline_ms = timeline_ms
        frame.overrun_ms = None if row["overrun_ns"] is None else row["overrun_ns"] / NS_PER_MS
        frame.jank_type = row["jank_type"]
        frame.on_time_finish = row["on_time_finish"]


def build_result(
    name: str,
    scenario: str,
    iteration: int,
    slices: list[dict],
    timeline: list[dict],
    other_threads: list[dict],
    gap_ms: float = DEFAULT_GAP_MS,
    min_burst_ms: float = DEFAULT_MIN_BURST_MS,
    margin_ms: float = DEFAULT_MARGIN_MS,
) -> TraceResult:
    """The whole analysis of one trace from already-fetched rows."""
    result = TraceResult(name=name, scenario=scenario, iteration=iteration)
    if not slices:
        result.notes.append("no main-thread or RenderThread slices for the benchmark app")
        return result
    result.start_ts = min(s["ts"] for s in slices)
    resolve_categories(slices)
    frames = build_frames(slices, result)
    apply_timeline(frames, timeline)
    markers = [
        (s["ts"], s["ts"] + s["dur"])
        for s in slices
        if s["thread_role"] == "main" and sc.is_activity_marker(s["name"] or "")
    ]
    result.windows = find_windows(markers, gap_ms, min_burst_ms, margin_ms)
    for frame in frames.values():
        end = frame.ts + int(frame.main_ms * NS_PER_MS)
        if any(frame.ts <= b and end >= a for a, b in result.windows):
            frame.window = "animation"
    result.frames = sorted(frames.values(), key=lambda f: f.ts)
    result.vsync_period_ms, result.vsync_source = vsync_period(
        [row["expected_ts"] for row in timeline if row["expected_ts"] is not None],
        [frame.ts for frame in result.frames],
    )
    if not timeline:
        result.notes.append("no frame timeline rows; deadline and jank columns are empty")
    for row in other_threads:
        key = (sc.normalize(row["thread_name"] or "?"), sc.normalize(row["name"] or ""))
        stat = result.other_threads.setdefault(key, {"count": 0, "total_ms": 0.0, "max_ms": 0.0})
        stat["count"] += row["slice_count"]
        stat["total_ms"] += row["total_dur"] / NS_PER_MS
        stat["max_ms"] = max(stat["max_ms"], row["max_dur"] / NS_PER_MS)
    return result


def analyze_trace(path: Path, scenario: str, iteration: int, processor_factory, **window_options) -> TraceResult:
    """Opens one trace with the Perfetto TraceProcessor and analyzes it."""
    processor = processor_factory(path)
    try:
        threads = query(processor, "app_threads.sql", ("thread_role", "thread_name"))
        slices = query(
            processor,
            "thread_slices.sql",
            ("id", "parent_id", "ts", "dur", "depth", "name", "thread_role", "self_dur"),
        )
        try:
            timeline = query(
                processor,
                "frame_timeline.sql",
                ("vsync_id", "ts", "dur", "jank_type", "on_time_finish", "present_type", "expected_ts", "overrun_ns"),
            )
        except Exception as error:  # The tables are missing on traces without frame timeline data.
            timeline = []
            timeline_error = str(error).splitlines()[0]
        else:
            timeline_error = None
        other = query(
            processor,
            "other_thread_slices.sql",
            ("thread_name", "name", "slice_count", "total_dur", "max_dur"),
        )
    finally:
        processor.close()
    result = build_result(path.name, scenario, iteration, slices, timeline, other, **window_options)
    roles = {row["thread_role"] for row in threads}
    for role, label in (("main", "main thread"), ("render", "RenderThread")):
        if role not in roles:
            result.notes.append(f"the benchmark app's {label} is not in this trace")
    if timeline_error:
        result.notes.append(f"frame timeline query failed: {timeline_error}")
    return result
