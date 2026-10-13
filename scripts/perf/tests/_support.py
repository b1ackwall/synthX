"""Shared paths for the perf script checks. Run: python3 -m unittest discover -s scripts/perf/tests"""

import sys
from pathlib import Path

PERF_DIR = Path(__file__).resolve().parents[1]
REPO = PERF_DIR.parents[1]
FIXTURES = REPO / "docs" / "perf" / "fixtures"
FIXTURE_JSON = FIXTURES / "com.v1per.synthx.macrobenchmark-benchmarkData.json"
SCENARIO = "PlayerExpandCollapseBenchmark.expandCollapse"

if str(PERF_DIR) not in sys.path:
    sys.path.insert(0, str(PERF_DIR))
