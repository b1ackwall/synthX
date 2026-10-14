import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import _support
import check_result
import perf_common
import summarize


def benchmark(ran: int, frame_runs: list, section_runs: list, sampled_runs: list) -> dict:
    return {
        "name": "nextQueue",
        "className": "com.v1per.synthx.macrobenchmark.PlayerTrackChangeBenchmark",
        "repeatIterations": ran,
        "metrics": {
            "frameCount": {"median": 1.0, "minimum": 1.0, "maximum": 1.0, "runs": frame_runs},
            "synthX.QueueMaskLayer.drawMaxMs": {"median": 0.1, "runs": section_runs},
        },
        "sampledMetrics": {"frameDurationCpuMs": {"runs": sampled_runs}},
    }


class RecordedIterationsTest(unittest.TestCase):
    def test_complete_entry(self):
        entry = benchmark(3, [9.0, 9.0, 9.0], [0.1, 0.0, 0.2], [[5.0], [6.0], [7.0]])
        self.assertEqual(perf_common.recorded_iterations(entry), 3)

    def test_shortest_list_wins(self):
        entry = benchmark(8, [39.0], [0.2], [[5.0, 6.0]])
        self.assertEqual(perf_common.recorded_iterations(entry), 1)
        entry = benchmark(8, [9.0] * 8, [0.2] * 8, [[5.0]] * 7)
        self.assertEqual(perf_common.recorded_iterations(entry), 7)

    def test_no_metrics(self):
        self.assertIsNone(perf_common.recorded_iterations({"repeatIterations": 8}))

    def test_fixture_recorded_every_iteration(self):
        raw = json.loads(_support.FIXTURE_JSON.read_text())["benchmarks"][0]
        self.assertEqual(perf_common.recorded_iterations(raw), raw["repeatIterations"])


class CheckResultTest(unittest.TestCase):
    def run_on(self, data) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "nextQueue-benchmarkData.json"
            path.write_text(json.dumps(data))
            errors = io.StringIO()
            with contextlib.redirect_stderr(errors):
                code = check_result.main([str(path)])
        return code, errors.getvalue()

    def test_complete_file_passes(self):
        data = {"benchmarks": [benchmark(2, [9.0, 9.0], [0.0, 0.1], [[5.0], [6.0]])]}
        self.assertEqual(self.run_on(data), (0, ""))

    def test_dropped_iterations_fail_and_are_named(self):
        code, errors = self.run_on({"benchmarks": [benchmark(8, [39.0], [0.2], [[5.0]])]})
        self.assertEqual(code, 1)
        self.assertIn("PlayerTrackChangeBenchmark.nextQueue: only 1 of 8 iterations", errors)

    def test_empty_file_fails(self):
        self.assertEqual(self.run_on({"benchmarks": []})[0], 1)

    def test_missing_file_fails(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            code = check_result.main(["/nonexistent/x-benchmarkData.json"])
        self.assertEqual(code, 1)
        self.assertIn("no result file", errors.getvalue())

    def test_fixture_passes(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            self.assertEqual(check_result.main([str(_support.FIXTURE_JSON)]), 0)


class SummaryWarningTest(unittest.TestCase):
    def summary_for(self, entry: dict) -> tuple[dict, str]:
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "nextQueue-benchmarkData.json").write_text(json.dumps({"benchmarks": [entry]}))
            built = summarize.build_summary(Path(folder))
        return built, summarize.render(built, top=8)

    def test_dropped_iterations_are_flagged(self):
        built, text = self.summary_for(benchmark(8, [39.0], [0.2], [[5.0, 6.0]]))
        self.assertEqual(built["scenarios"]["PlayerTrackChangeBenchmark.nextQueue"]["iterationsRecorded"], 1)
        self.assertIn("WARNING: only 1 of 8 iterations have data", text)

    def test_complete_run_has_no_warning(self):
        _, text = self.summary_for(benchmark(2, [9.0, 9.0], [0.0, 0.1], [[5.0], [6.0]]))
        self.assertNotIn("WARNING", text)


if __name__ == "__main__":
    unittest.main()
