#!/usr/bin/env python3
"""Analyzes the Perfetto traces of one benchmark run folder, frame by frame.

Usage: python3 scripts/perf/analyze_traces.py <run-folder> [--scenario X] [--out DIR]
           [--top N] [--slowest N] [--limit N] [--margin-ms MS] [--trace-processor PATH]

Writes trace-analysis.md and trace-frames.csv next to the traces. Needs the `perfetto` pip
package, which downloads the TraceProcessor binary on first use:

    python3 -m venv ~/.venvs/perf && ~/.venvs/perf/bin/pip install perfetto
    ~/.venvs/perf/bin/python scripts/perf/analyze_traces.py perf-output/<run>

Without network access, pass an already-downloaded binary with --trace-processor, or run the
queries in scripts/perf/sql/ by hand in https://ui.perfetto.dev.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from perf_common import device_identity, load_device, parse_trace_name
from trace_model import DEFAULT_GAP_MS, DEFAULT_MARGIN_MS, DEFAULT_MIN_BURST_MS, analyze_trace
from trace_report import render_report, write_csv

EXIT_NO_PERFETTO = 2


def find_traces(folder: Path, scenario_filter: str | None, limit: int | None) -> dict:
    """{scenario: [(iteration, path)]} for the folder's traces, in iteration order."""
    by_scenario: dict = {}
    for path in sorted(folder.glob("*.perfetto-trace")):
        parsed = parse_trace_name(path.name)
        scenario, iteration = parsed if parsed else (path.stem, 0)
        if scenario_filter and scenario_filter.lower() not in scenario.lower():
            continue
        by_scenario.setdefault(scenario, []).append((iteration, path))
    for scenario, traces in by_scenario.items():
        traces.sort()
        if limit:
            by_scenario[scenario] = traces[:limit]
    return by_scenario


def processor_factory(binary: Path | None):
    """Returns a function opening one trace, or exits with install advice."""
    try:
        from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    except ImportError:
        print(
            "The `perfetto` Python package is not installed.\n"
            "  python3 -m venv ~/.venvs/perf && ~/.venvs/perf/bin/pip install perfetto\n"
            "then run this script with ~/.venvs/perf/bin/python. Or open the traces in\n"
            "https://ui.perfetto.dev and run the queries in scripts/perf/sql/.",
            file=sys.stderr,
        )
        raise SystemExit(EXIT_NO_PERFETTO)

    def open_trace(path: Path):
        config = TraceProcessorConfig(bin_path=str(binary)) if binary else TraceProcessorConfig()
        return TraceProcessor(trace=str(path), config=config)

    return open_trace


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_folder", type=Path)
    parser.add_argument("--scenario", help="only scenarios whose Class.method contains this text")
    parser.add_argument("--out", type=Path, help="where to write the report and CSV (default: the run folder)")
    parser.add_argument("--top", type=int, default=15, help="rows in each top-slices table (default 15)")
    parser.add_argument("--slowest", type=int, default=10, help="slowest animation frames to list (default 10)")
    parser.add_argument("--limit", type=int, help="analyze at most this many traces per scenario")
    parser.add_argument("--margin-ms", type=float, default=DEFAULT_MARGIN_MS,
                        help="margin around an animation window (default %(default)g)")
    parser.add_argument("--gap-ms", type=float, default=DEFAULT_GAP_MS,
                        help="quiet time that ends a window (default %(default)g)")
    parser.add_argument("--min-burst-ms", type=float, default=DEFAULT_MIN_BURST_MS,
                        help="shortest activity that counts as a window (default %(default)g)")
    parser.add_argument("--trace-processor", type=Path, help="path to a trace_processor_shell binary")
    args = parser.parse_args(argv)
    if not args.run_folder.is_dir():
        parser.error(f"{args.run_folder} is not a folder")
    traces = find_traces(args.run_folder, args.scenario, args.limit)
    if not traces:
        print(f"No *.perfetto-trace files match in {args.run_folder}", file=sys.stderr)
        return 1
    open_trace = processor_factory(args.trace_processor)
    results: dict = {}
    for scenario, items in traces.items():
        for iteration, path in items:
            results.setdefault(scenario, []).append(analyze_trace(
                path, scenario, iteration, open_trace,
                gap_ms=args.gap_ms, min_burst_ms=args.min_burst_ms, margin_ms=args.margin_ms,
            ))
        print(f"Analyzed {scenario}: {len(items)} trace(s)", file=sys.stderr)
    device_hz = device_identity(load_device(args.run_folder), None)["refresh_hz"]
    out_dir = args.out or args.run_folder
    out_dir.mkdir(parents=True, exist_ok=True)
    suffix = f"-{args.scenario}" if args.scenario else ""
    report_path = out_dir / f"trace-analysis{suffix}.md"
    csv_path = out_dir / f"trace-frames{suffix}.csv"
    report_path.write_text(render_report(args.run_folder.name, results, device_hz, args.top, args.slowest), encoding="utf-8")
    rows = write_csv(csv_path, results)
    print(f"Wrote {report_path} and {csv_path} ({rows} frames)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
