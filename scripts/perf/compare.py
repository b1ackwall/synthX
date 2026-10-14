#!/usr/bin/env python3
"""Compares two benchmark runs' summary.json files, scenario by scenario.

Usage: python3 scripts/perf/compare.py <runA> <runB> [--threshold PCT] [--force]
                                       [--fail-on-regression]

runA is the baseline, runB the candidate. Each is a run folder holding summary.json (written by
summarize.py) or a summary file itself, such as docs/perf/baselines/<device>-<date>.summary.json.
Every compared metric is "lower is better". A change is flagged only when it is at least the
threshold in percent AND at least a small absolute amount, so noise on tiny values is not flagged.
Runs from different devices or refresh rates are refused unless --force. Standard library only.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from perf_common import SUMMARY_FILE, fmt, load_json, markdown_table

EXIT_REFUSED = 2
EXIT_REGRESSED = 1

# (label, path into a scenario summary, unit, smallest absolute change worth flagging)
KEY_METRICS = [
    ("frameDurationCpuMs p50", ("frameDurationCpuMs", "p50"), "ms", 0.5),
    ("frameDurationCpuMs p90", ("frameDurationCpuMs", "p90"), "ms", 0.5),
    ("frameDurationCpuMs p95", ("frameDurationCpuMs", "p95"), "ms", 0.5),
    ("frameDurationCpuMs p99", ("frameDurationCpuMs", "p99"), "ms", 0.5),
    ("frameOverrunMs p50", ("frameOverrunMs", "p50"), "ms", 0.5),
    ("frameOverrunMs p90", ("frameOverrunMs", "p90"), "ms", 0.5),
    ("frameOverrunMs p95", ("frameOverrunMs", "p95"), "ms", 0.5),
    ("frameOverrunMs p99", ("frameOverrunMs", "p99"), "ms", 0.5),
    ("frames over 16.67 ms", ("jank", "over_60hz_budget", "percent"), "%", 1.0),
    ("frames over 8.33 ms", ("jank", "over_120hz_budget", "percent"), "%", 1.0),
    ("frames over device budget", ("jank", "over_device_budget", "percent"), "%", 1.0),
    ("deadline missed", ("jank", "deadline_missed", "percent"), "%", 1.0),
]
SECTION_MIN_ABS_MS = 1.0


def load_summary(path: Path) -> dict:
    file = path / SUMMARY_FILE if path.is_dir() else path
    if not file.is_file():
        raise SystemExit(f"{file} not found. Run summarize.py on the run folder first.")
    summary = load_json(file)
    if "scenarios" not in summary:
        raise SystemExit(f"{file} is not a summary.json written by summarize.py.")
    return summary


def dig(node: dict | None, path: tuple[str, ...]) -> float | None:
    for key in path:
        if not isinstance(node, dict):
            return None
        node = node.get(key)
    return node if isinstance(node, (int, float)) else None


def judge(base: float | None, candidate: float | None, threshold_pct: float, min_abs: float) -> dict:
    """Absolute and percent change, and whether it counts as improved, regressed or same."""
    if base is None or candidate is None:
        return {"base": base, "candidate": candidate, "delta": None, "percent": None, "verdict": "n/a"}
    delta = candidate - base
    percent = None if abs(base) < 1e-9 else 100.0 * delta / abs(base)
    big_enough = abs(delta) >= min_abs and (percent is None or abs(percent) >= threshold_pct)
    verdict = "same" if not big_enough else ("regressed" if delta > 0 else "improved")
    return {"base": base, "candidate": candidate, "delta": delta, "percent": percent, "verdict": verdict}


def compare_scenario(base: dict, candidate: dict, threshold_pct: float) -> list[dict]:
    rows = []
    for label, path, unit, min_abs in KEY_METRICS:
        result = judge(dig(base, path), dig(candidate, path), threshold_pct, min_abs)
        rows.append({"metric": label, "unit": unit, **result})
    shared = sorted(set(base.get("sections", {})) & set(candidate.get("sections", {})))
    for section in shared:
        result = judge(
            dig(base, ("sections", section, "sumMs")),
            dig(candidate, ("sections", section, "sumMs")),
            threshold_pct,
            SECTION_MIN_ABS_MS,
        )
        rows.append({"metric": f"{section} sum", "unit": "ms", **result})
    return rows


def device_problems(base: dict, candidate: dict) -> tuple[list[str], list[str]]:
    """(reasons to refuse, things to warn about) when the two runs' devices differ."""
    a, b = base.get("device", {}), candidate.get("device", {})
    refuse, warn = [], []
    if a.get("model") != b.get("model"):
        refuse.append(f"different devices: {a.get('model')} vs {b.get('model')}")
    hz_a, hz_b = a.get("refresh_hz"), b.get("refresh_hz")
    if hz_a is None or hz_b is None:
        warn.append(f"refresh rate unknown for at least one run ({hz_a} vs {hz_b}); cannot check it")
    elif abs(hz_a - hz_b) > 1.0:
        refuse.append(f"different refresh rates: {hz_a:g} Hz vs {hz_b:g} Hz")
    if a.get("fingerprint") != b.get("fingerprint"):
        warn.append("different build fingerprints (system update between the runs)")
    if base.get("compilation") != candidate.get("compilation"):
        warn.append(f"different compilation modes: {base.get('compilation')} vs {candidate.get('compilation')}")
    frames_a, frames_b = base.get("frame_source", "timeline"), candidate.get("frame_source", "timeline")
    if frames_a != frames_b:
        warn.append(f"different frame sources: {frames_a} vs {frames_b} (frameCount and frameOverrunMs differ)")
    return refuse, warn


def compare(base: dict, candidate: dict, threshold_pct: float) -> dict:
    base_names, candidate_names = set(base["scenarios"]), set(candidate["scenarios"])
    return {
        "scenarios": {
            name: compare_scenario(base["scenarios"][name], candidate["scenarios"][name], threshold_pct)
            for name in sorted(base_names & candidate_names)
        },
        "only_in_base": sorted(base_names - candidate_names),
        "only_in_candidate": sorted(candidate_names - base_names),
    }


def render(result: dict, base: dict, candidate: dict, threshold_pct: float, warnings: list[str]) -> str:
    lines = [
        f"# Benchmark comparison: {base.get('run')} (A) vs {candidate.get('run')} (B)",
        "",
        f"- A: {base.get('device', {}).get('model')}, commit {base.get('commit') or 'unknown'}",
        f"- B: {candidate.get('device', {}).get('model')}, commit {candidate.get('commit') or 'unknown'}",
        f"- Flagged at {threshold_pct:g}% or more; lower is better for every metric.",
    ]
    lines += [f"- Warning: {warning}" for warning in warnings]
    for name, rows in result["scenarios"].items():
        flagged = [row for row in rows if row["verdict"] in ("improved", "regressed")]
        lines += ["", f"## {name}", ""]
        lines.append(f"{sum(r['verdict'] == 'regressed' for r in rows)} regressed, "
                     f"{sum(r['verdict'] == 'improved' for r in rows)} improved.")
        lines.append("")
        table = []
        for row in rows:
            if row["delta"] is None and row["base"] is None and row["candidate"] is None:
                continue
            percent = "n/a" if row["percent"] is None else f"{row['percent']:+.1f}%"
            delta = "n/a" if row["delta"] is None else f"{row['delta']:+.2f}"
            mark = row["verdict"].upper() if row in flagged else row["verdict"]
            table.append([row["metric"], fmt(row["base"]), fmt(row["candidate"]), f"{delta} {row['unit']}", percent, mark])
        lines.append(markdown_table(["metric", "A", "B", "change", "percent", "verdict"], table))
    for label, names in (("Only in A", result["only_in_base"]), ("Only in B", result["only_in_candidate"])):
        if names:
            lines += ["", f"{label} (not compared): " + ", ".join(names)]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_a", type=Path, help="baseline: run folder or summary.json")
    parser.add_argument("run_b", type=Path, help="candidate: run folder or summary.json")
    parser.add_argument("--threshold", type=float, default=10.0, help="percent change to flag (default 10)")
    parser.add_argument("--force", action="store_true", help="compare even across devices or refresh rates")
    parser.add_argument("--fail-on-regression", action="store_true", help="exit 1 if anything regressed")
    args = parser.parse_args(argv)
    base, candidate = load_summary(args.run_a), load_summary(args.run_b)
    refuse, warnings = device_problems(base, candidate)
    if refuse and not args.force:
        print("Refusing to compare: " + "; ".join(refuse) + ". Use --force to compare anyway.", file=sys.stderr)
        return EXIT_REFUSED
    warnings = [f"forced past: {reason}" for reason in refuse] + warnings
    result = compare(base, candidate, args.threshold)
    print(render(result, base, candidate, args.threshold, warnings), end="")
    regressed = any(row["verdict"] == "regressed" for rows in result["scenarios"].values() for row in rows)
    return EXIT_REGRESSED if args.fail_on_regression and regressed else 0


if __name__ == "__main__":
    sys.exit(main())
