"""Shared helpers for the perf scripts: run folders, scenario names, device info, percentiles."""

from __future__ import annotations

import json
import re
from pathlib import Path

APP_PACKAGE = "com.v1per.synthx.benchmark"
BUDGET_60HZ_MS = 1000.0 / 60.0
BUDGET_120HZ_MS = 1000.0 / 120.0
SUMMARY_FILE = "summary.json"
DEVICE_FILE = "device.json"

# <Class>_<method>_iterNNN_<timestamp>.perfetto-trace, as Macrobenchmark names its traces.
_TRACE_NAME = re.compile(r"^(?P<cls>[A-Za-z0-9]+)_(?P<method>[A-Za-z0-9]+)_iter(?P<iter>\d+)_.*\.perfetto-trace$")


def percentile(values: list[float], fraction: float) -> float:
    """Linear-interpolated percentile, the method the benchmark library uses for P50 to P99."""
    if not values:
        raise ValueError("percentile of an empty list")
    ordered = sorted(values)
    position = fraction * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def median(values: list[float]) -> float:
    return percentile(values, 0.5)


def load_json(path: Path) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def scenario_key(class_name: str, method: str) -> str:
    """'PlayerDragBenchmark.fling' from a class name (qualified or not) and a test method."""
    return f"{class_name.rsplit('.', 1)[-1]}.{method}"


def load_benchmarks(folder: Path) -> list[dict]:
    """Every benchmark entry of every *benchmarkData.json in a run folder.

    Each entry is the library's own dict plus 'scenario', 'sourceFile' and 'context'.
    """
    entries = []
    for path in sorted(folder.glob("*benchmarkData.json")):
        data = load_json(path)
        for benchmark in data.get("benchmarks", []):
            entry = dict(benchmark)
            entry["scenario"] = scenario_key(benchmark.get("className", "?"), benchmark.get("name", "?"))
            entry["sourceFile"] = path.name
            entry["context"] = data.get("context", {})
            entries.append(entry)
    return entries


def load_device(folder: Path) -> dict | None:
    """device.json as run-benchmark.fish wrote it, or None when the folder has none."""
    path = folder / DEVICE_FILE
    return load_json(path) if path.is_file() else None


def device_identity(device_json: dict | None, context: dict | None) -> dict:
    """Model, API, fingerprint and refresh rate; device.json first, benchmark context second."""
    build = (context or {}).get("build", {})
    identity = {
        "model": build.get("model"),
        "api": (build.get("version") or {}).get("sdk"),
        "fingerprint": build.get("fingerprint"),
        "refresh_hz": None,
        "refresh_source": None,
    }
    if device_json:
        device = device_json.get("device") or {}
        for key in ("model", "api", "fingerprint"):
            if device.get(key) is not None:
                identity[key] = device[key]
        refresh = device_json.get("refresh") or {}
        if refresh.get("hz"):
            identity["refresh_hz"] = round(float(refresh["hz"]), 1)
            identity["refresh_source"] = refresh.get("source")
    return identity


def parse_trace_name(file_name: str) -> tuple[str, int] | None:
    """(scenario key, iteration) from a Macrobenchmark trace file name, or None if it differs."""
    match = _TRACE_NAME.match(file_name)
    if not match:
        return None
    return scenario_key(match["cls"], match["method"]), int(match["iter"])


def fmt(value: float | None, digits: int = 2) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def markdown_table(header: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(str(cell) for cell in row) + " |" for row in rows]
    return "\n".join(lines)
