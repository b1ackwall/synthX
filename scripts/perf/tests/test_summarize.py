import json
import shutil
import tempfile
import unittest
from pathlib import Path

import _support
import perf_common
import summarize


class PercentileTest(unittest.TestCase):
    def test_interpolates_between_samples(self):
        self.assertEqual(perf_common.percentile([1.0, 2.0, 3.0, 4.0], 0.5), 2.5)
        self.assertEqual(perf_common.percentile([5.0], 0.99), 5.0)
        self.assertEqual(perf_common.percentile([4.0, 1.0, 3.0, 2.0], 1.0), 4.0)

    def test_empty_list_is_an_error(self):
        with self.assertRaises(ValueError):
            perf_common.percentile([], 0.5)


class NamesTest(unittest.TestCase):
    def test_scenario_key_drops_the_package(self):
        self.assertEqual(perf_common.scenario_key("com.v1per.synthx.macrobenchmark.PlayerDragBenchmark", "fling"),
                         "PlayerDragBenchmark.fling")

    def test_trace_name(self):
        name = "PlayerExpandCollapseBenchmark_expandCollapse_iter004_2026-10-13-21-22-46.perfetto-trace"
        self.assertEqual(perf_common.parse_trace_name(name), (_support.SCENARIO, 4))
        self.assertIsNone(perf_common.parse_trace_name("something-else.perfetto-trace"))


class FixtureSummaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = json.loads(_support.FIXTURE_JSON.read_text())["benchmarks"][0]
        cls.summary = summarize.build_summary(_support.FIXTURES)
        cls.scenario = cls.summary["scenarios"][_support.SCENARIO]

    def test_percentiles_match_the_library(self):
        for metric in ("frameDurationCpuMs", "frameOverrunMs"):
            for ours, theirs in (("p50", "P50"), ("p90", "P90"), ("p95", "P95"), ("p99", "P99")):
                self.assertAlmostEqual(self.scenario[metric][ours], self.raw["sampledMetrics"][metric][theirs], places=9)

    def test_frame_count_and_iterations(self):
        self.assertEqual(self.scenario["iterations"], 10)
        self.assertEqual(self.scenario["frameCount"]["median"], self.raw["metrics"]["frameCount"]["median"])

    def test_jank_counts_come_from_raw_samples(self):
        samples = [v for run in self.raw["sampledMetrics"]["frameDurationCpuMs"]["runs"] for v in run]
        jank = self.scenario["jank"]
        self.assertEqual(jank["frames"], len(samples))
        self.assertEqual(jank["over_60hz_budget"]["frames"], sum(v > 1000 / 60 for v in samples))
        self.assertEqual(jank["over_120hz_budget"]["frames"], sum(v > 1000 / 120 for v in samples))
        self.assertGreaterEqual(jank["over_120hz_budget"]["frames"], jank["over_60hz_budget"]["frames"])

    def test_no_device_json_means_no_device_budget(self):
        self.assertFalse(self.summary["has_device_json"])
        self.assertIsNone(self.summary["device"]["refresh_hz"])
        self.assertIsNone(self.scenario["jank"]["over_device_budget"])
        self.assertEqual(self.summary["device"]["model"], "SM-S938U")

    def test_sections_keep_dotted_names(self):
        sections = self.scenario["sections"]
        self.assertEqual(sections["synthX.PlayerSheet"]["count"], 183.0)
        self.assertIn("maxMs", sections["synthX.PlayerSheet"])
        self.assertIn("synthX.PlayerSheet.onProgressChanged", sections)
        self.assertIn("synthX.PlayerBackground.draw", sections)

    def test_markdown_renders(self):
        text = summarize.render(self.summary, top=3)
        self.assertIn("## " + _support.SCENARIO, text)
        self.assertIn("refresh rate unknown", text)


class DeviceBudgetTest(unittest.TestCase):
    def test_device_json_sets_the_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            shutil.copy(_support.FIXTURE_JSON, folder / "expandCollapse-benchmarkData.json")
            (folder / "device.json").write_text(json.dumps({
                "commit": "abc123", "commit_dirty": False, "compilation": "default",
                "device": {"model": "SM-S938U", "api": 37, "fingerprint": "fp"},
                "refresh": {"hz": 120.00001, "source": "dumpsys display renderFrameRate"},
            }))
            summary = summarize.build_summary(folder)
            jank = summary["scenarios"][_support.SCENARIO]["jank"]
            self.assertEqual(summary["device"]["refresh_hz"], 120.0)
            self.assertEqual(jank["over_device_budget"]["frames"], jank["over_120hz_budget"]["frames"])
            self.assertEqual(summary["commit"], "abc123")

    def test_library_percentiles_are_kept_without_raw_samples(self):
        stats = summarize.distribution({"P50": 1.0, "P90": 2.0, "P95": 3.0, "P99": 4.0})
        self.assertEqual((stats["p50"], stats["p99"], stats["samples"]), (1.0, 4.0, 0))
        self.assertIsNone(summarize.distribution({}))

    def test_empty_folder_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(SystemExit):
                summarize.build_summary(Path(tmp))


if __name__ == "__main__":
    unittest.main()
