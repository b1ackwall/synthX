#!/usr/bin/env python3
"""Summarizes one benchmark run folder: a markdown table per scenario, plus summary.json.

Usage: python3 scripts/perf/summarize.py <run-folder> [--out DIR] [--top N]

Reads every *benchmarkData.json in the folder (and device.json when present), prints markdown
to stdout and writes summary.json, the format compare.py reads and docs/perf/baselines keeps.
Standard library only.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from perf_common import (
    BUDGET_60HZ_MS,
    BUDGET_120HZ_MS,
    SUMMARY_FILE,
    device_identity,
    fmt,
    load_benchmarks,
    load_device,
    markdown_table,
    median,
    percentile,
    recorded_iterations,
)

PERCENTILES = (("p50", 0.50), ("p90", 0.90), ("p95", 0.95), ("p99", 0.99))
SECTION_PREFIX = "synthX."
SECTION_SUFFIXES = (("SumMs", "sumMs"), ("Count", "count"), ("MaxMs", "maxMs"))


def distribution(sampled: dict) -> dict | None:
    """Percentiles of one sampled metric, pooled over iterations from the raw samples."""
    runs = sampled.get("runs")
    samples = [value for run in runs for value in run] if runs else []
    if samples:
        stats = {name: percentile(samples, fraction) for name, fraction in PERCENTILES}
        stats["max"] = max(samples)
        stats["samples"] = len(samples)
        return stats
    if "P50" in sampled:
        # No raw samples in this file: keep the library's own percentiles.
        stats = {name: sampled.get(name.upper()) for name, _ in PERCENTILES}
        stats["max"] = None
        stats["samples"] = 0
        return stats
    return None


def pooled(sampled: dict | None) -> list[float]:
    return [value for run in (sampled or {}).get("runs", []) for value in run]


def share(count: int, total: int) -> float | None:
    return None if total == 0 else 100.0 * count / total


def jank(cpu_samples: list[float], overrun_samples: list[float], device_budget_ms: float | None) -> dict:
    """Frames whose CPU time exceeds each budget, and frames that missed their deadline."""
    total = len(cpu_samples)

    def over(budget_ms: float | None) -> dict | None:
        if budget_ms is None:
            return None
        count = sum(1 for value in cpu_samples if value > budget_ms)
        return {"budget_ms": round(budget_ms, 3), "frames": count, "percent": share(count, total)}

    missed = sum(1 for value in overrun_samples if value > 0)
    return {
        "frames": total,
        "over_60hz_budget": over(BUDGET_60HZ_MS),
        "over_120hz_budget": over(BUDGET_120HZ_MS),
        "over_device_budget": over(device_budget_ms),
        "deadline_missed": {"frames": missed, "percent": share(missed, len(overrun_samples))},
    }


def sections(metrics: dict) -> dict:
    """synthX.* trace sections: median per-iteration sum, count and longest run."""
    found: dict[str, dict] = {}
    for metric_name, metric in metrics.items():
        if not metric_name.startswith(SECTION_PREFIX):
            continue
        for suffix, field in SECTION_SUFFIXES:
            if metric_name.endswith(suffix):
                section = metric_name[: -len(suffix)]
                found.setdefault(section, {})[field] = metric.get("median")
                break
    return found


def summarize_benchmark(entry: dict, device_budget_ms: float | None) -> dict:
    metrics = entry.get("metrics", {})
    sampled = entry.get("sampledMetrics", {})
    frame_count = metrics.get("frameCount")
    return {
        "className": entry.get("className"),
        "method": entry.get("name"),
        "sourceFile": entry.get("sourceFile"),
        "iterations": entry.get("repeatIterations"),
        "iterationsRecorded": recorded_iterations(entry),
        "compilationMode": entry.get("context", {}).get("compilationMode"),
        "totalRunTimeS": round(entry.get("totalRunTimeNs", 0) / 1e9, 1),
        "frameCount": None if frame_count is None else {
            "median": frame_count.get("median"),
            "minimum": frame_count.get("minimum"),
            "maximum": frame_count.get("maximum"),
        },
        "frameDurationCpuMs": distribution(sampled.get("frameDurationCpuMs", {})),
        "frameOverrunMs": distribution(sampled.get("frameOverrunMs", {})),
        "jank": jank(pooled(sampled.get("frameDurationCpuMs")), pooled(sampled.get("frameOverrunMs")), device_budget_ms),
        "sections": sections(metrics),
    }


def build_summary(folder: Path) -> dict:
    entries = load_benchmarks(folder)
    if not entries:
        raise SystemExit(f"No *benchmarkData.json with benchmarks found in {folder}")
    device_json = load_device(folder)
    identity = device_identity(device_json, entries[0].get("context"))
    budget = 1000.0 / identity["refresh_hz"] if identity["refresh_hz"] else None
    scenarios = {}
    for entry in entries:
        if entry["scenario"] in scenarios:
            print(f"warning: {entry['scenario']} appears twice; keeping {entry['sourceFile']}", file=sys.stderr)
        scenarios[entry["scenario"]] = summarize_benchmark(entry, budget)
    return {
        "schema": 1,
        "run": folder.name,
        "commit": (device_json or {}).get("commit"),
        "commit_dirty": (device_json or {}).get("commit_dirty"),
        "compilation": (device_json or {}).get("compilation"),
        "device": identity,
        "battery_start": (device_json or {}).get("battery_start"),
        "battery_end": (device_json or {}).get("battery_end"),
        "has_device_json": device_json is not None,
        "scenarios": scenarios,
    }


def jank_rows(jank_stats: dict, refresh_hz: float | None) -> list[list[str]]:
    def row(label: str, stats: dict | None) -> list[str]:
        if stats is None:
            return [label, "n/a", "n/a"]
        return [label, str(stats["frames"]), fmt(stats["percent"], 1) + "%"]

    device_label = f"device budget ({refresh_hz:g} Hz)" if refresh_hz else "device budget (refresh rate unknown)"
    return [
        row("CPU time over 16.67 ms (60 Hz)", jank_stats["over_60hz_budget"]),
        row("CPU time over 8.33 ms (120 Hz)", jank_stats["over_120hz_budget"]),
        row("CPU time over " + device_label, jank_stats["over_device_budget"]),
        row("deadline missed (frameOverrunMs > 0)", jank_stats["deadline_missed"]),
    ]


def render_scenario(name: str, scenario: dict, refresh_hz: float | None, top: int) -> str:
    lines = [f"## {name}", ""]
    frame_count = scenario["frameCount"]
    frames = scenario["jank"]["frames"]
    count_text = "" if frame_count is None else (
        f", frameCount median {fmt(frame_count['median'], 1)} per iteration "
        f"({fmt(frame_count['minimum'], 0)} to {fmt(frame_count['maximum'], 0)})"
    )
    lines.append(f"{scenario['iterations']} iterations, {frames} frames sampled{count_text}.")
    lines.append("")
    recorded = scenario.get("iterationsRecorded")
    if recorded is not None and scenario["iterations"] is not None and recorded < scenario["iterations"]:
        lines.append(
            f"**WARNING: only {recorded} of {scenario['iterations']} iterations have data.** The "
            "library dropped the others because a metric reported nothing in them (no frames, or "
            "a metric that is absent when its section does not run). Every number below covers "
            "the recorded iterations only."
        )
        lines.append("")
    rows = []
    for metric in ("frameDurationCpuMs", "frameOverrunMs"):
        stats = scenario[metric]
        if stats is None:
            rows.append([metric] + ["n/a"] * 5)
        else:
            rows.append([metric] + [fmt(stats[name]) for name, _ in PERCENTILES] + [fmt(stats["max"])])
    lines.append(markdown_table(["metric", "p50", "p90", "p95", "p99", "max"], rows))
    lines.append("")
    lines.append(markdown_table(["janky frames", "frames", "share"], jank_rows(scenario["jank"], refresh_hz)))
    ranked = sorted(scenario["sections"].items(), key=lambda item: -(item[1].get("sumMs") or 0.0))[:top]
    if ranked:
        lines.append("")
        lines.append(markdown_table(
            ["section (median per iteration)", "sum ms", "count", "max ms"],
            [[name, fmt(s.get("sumMs")), fmt(s.get("count"), 0), fmt(s.get("maxMs"))] for name, s in ranked],
        ))
    return "\n".join(lines)


def render(summary: dict, top: int) -> str:
    device = summary["device"]
    refresh = f"{device['refresh_hz']:g} Hz ({device['refresh_source']})" if device["refresh_hz"] else "unknown"
    lines = [
        f"# Benchmark summary: {summary['run']}",
        "",
        f"- Device: {device['model']} (API {device['api']}), refresh rate {refresh}",
        f"- Commit: {summary['commit'] or 'unknown'}" + (" (uncommitted changes)" if summary["commit_dirty"] else ""),
        f"- Compilation: {summary['compilation'] or 'see scenarios'}",
    ]
    if not summary["has_device_json"]:
        lines.append("- No device.json in this folder: device budget and refresh rate are unknown.")
    for name, scenario in summary["scenarios"].items():
        lines += ["", render_scenario(name, scenario, device["refresh_hz"], top)]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_folder", type=Path)
    parser.add_argument("--out", type=Path, help="where to write summary.json (default: the run folder)")
    parser.add_argument("--top", type=int, default=8, help="trace sections to list per scenario (default 8)")
    args = parser.parse_args(argv)
    if not args.run_folder.is_dir():
        parser.error(f"{args.run_folder} is not a folder")
    summary = build_summary(args.run_folder)
    print(render(summary, args.top), end="")
    out_dir = args.out or args.run_folder
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / SUMMARY_FILE, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(f"\nWrote {out_dir / SUMMARY_FILE}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
