#!/usr/bin/env python3
"""Checks one benchmark result file for iterations the library silently dropped.

Usage: python3 scripts/perf/check_result.py <file-benchmarkData.json>

Exits 1, with one line per problem on stderr, when a benchmark has data for fewer iterations
than it ran, and 0 otherwise. run-benchmark.fish calls it after each scenario. Standard library
only.
"""

from __future__ import annotations

import sys
from pathlib import Path

from perf_common import load_json, recorded_iterations, scenario_key


def problems(data: dict) -> list[str]:
    """One message per benchmark whose recorded iterations fall short of the ones it ran."""
    found = []
    benchmarks = data.get("benchmarks") or []
    if not benchmarks:
        found.append("the file holds no benchmark")
    for benchmark in benchmarks:
        name = scenario_key(benchmark.get("className", "?"), benchmark.get("name", "?"))
        ran = benchmark.get("repeatIterations")
        recorded = recorded_iterations(benchmark)
        if recorded is None:
            found.append(f"{name}: no metrics were recorded")
        elif ran is not None and recorded < ran:
            found.append(
                f"{name}: only {recorded} of {ran} iterations have data; a metric reported "
                "nothing in the others (no frames drawn, or its trace section did not run)"
            )
    return found


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    path = Path(args[0])
    if not path.is_file():
        print(f"{path}: no result file was produced", file=sys.stderr)
        return 1
    found = problems(load_json(path))
    for message in found:
        print(message, file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
