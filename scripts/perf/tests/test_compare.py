import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

import _support
import compare
import summarize


def make_summary(**device):
    summary = summarize.build_summary(_support.FIXTURES)
    summary["device"].update({"refresh_hz": 120.0, **device})
    return summary


def run_main(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = compare.main([str(a) for a in args])
    return code, out.getvalue(), err.getvalue()


class JudgeTest(unittest.TestCase):
    def test_small_percent_is_same(self):
        self.assertEqual(compare.judge(10.0, 10.9, 10.0, 0.5)["verdict"], "same")

    def test_large_change_is_flagged_in_both_directions(self):
        self.assertEqual(compare.judge(10.0, 12.0, 10.0, 0.5)["verdict"], "regressed")
        self.assertEqual(compare.judge(10.0, 8.0, 10.0, 0.5)["verdict"], "improved")

    def test_tiny_absolute_change_is_not_flagged(self):
        result = compare.judge(0.10, 0.30, 10.0, 0.5)
        self.assertEqual(result["verdict"], "same")
        self.assertAlmostEqual(result["percent"], 200.0)

    def test_negative_baseline_uses_its_magnitude(self):
        result = compare.judge(-4.0, -1.0, 10.0, 0.5)
        self.assertEqual(result["verdict"], "regressed")
        self.assertAlmostEqual(result["percent"], 75.0)

    def test_zero_baseline_has_no_percent(self):
        result = compare.judge(0.0, 2.0, 10.0, 0.5)
        self.assertIsNone(result["percent"])
        self.assertEqual(result["verdict"], "regressed")

    def test_missing_value(self):
        self.assertEqual(compare.judge(None, 2.0, 10.0, 0.5)["verdict"], "n/a")


class CompareRunsTest(unittest.TestCase):
    def setUp(self):
        self.base = make_summary()
        self.candidate = copy.deepcopy(self.base)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, summary):
        path = Path(self.tmp.name) / name
        path.write_text(json.dumps(summary))
        return path

    def test_identical_runs_have_no_flags(self):
        result = compare.compare(self.base, self.candidate, 10.0)
        self.assertTrue(all(row["verdict"] in ("same", "n/a") for row in result["scenarios"][_support.SCENARIO]))

    def test_regression_and_improvement_are_found(self):
        scenario = self.candidate["scenarios"][_support.SCENARIO]
        scenario["frameDurationCpuMs"]["p90"] *= 1.5
        scenario["sections"]["synthX.PlayerSheet"]["sumMs"] *= 0.5
        rows = {row["metric"]: row for row in compare.compare(self.base, self.candidate, 10.0)["scenarios"][_support.SCENARIO]}
        self.assertEqual(rows["frameDurationCpuMs p90"]["verdict"], "regressed")
        self.assertEqual(rows["synthX.PlayerSheet sum"]["verdict"], "improved")
        self.assertEqual(rows["frameDurationCpuMs p50"]["verdict"], "same")

    def test_scenarios_missing_on_one_side_are_listed(self):
        self.candidate["scenarios"]["PlayerDragBenchmark.fling"] = self.candidate["scenarios"].pop(_support.SCENARIO)
        result = compare.compare(self.base, self.candidate, 10.0)
        self.assertEqual(result["only_in_base"], [_support.SCENARIO])
        self.assertEqual(result["only_in_candidate"], ["PlayerDragBenchmark.fling"])
        self.assertEqual(result["scenarios"], {})

    def test_different_refresh_rate_is_refused_unless_forced(self):
        self.candidate["device"]["refresh_hz"] = 60.0
        a, b = self.write("a.json", self.base), self.write("b.json", self.candidate)
        code, out, err = run_main(a, b)
        self.assertEqual(code, compare.EXIT_REFUSED)
        self.assertIn("different refresh rates", err)
        self.assertEqual(out, "")
        code, out, _ = run_main(a, b, "--force")
        self.assertEqual(code, 0)
        self.assertIn("forced past", out)

    def test_different_device_is_refused(self):
        self.candidate["device"]["model"] = "Redmi Note 7 Pro"
        code, _, err = run_main(self.write("a.json", self.base), self.write("b.json", self.candidate))
        self.assertEqual(code, compare.EXIT_REFUSED)
        self.assertIn("different devices", err)

    def test_unknown_refresh_rate_only_warns(self):
        self.candidate["device"]["refresh_hz"] = None
        code, out, _ = run_main(self.write("a.json", self.base), self.write("b.json", self.candidate))
        self.assertEqual(code, 0)
        self.assertIn("refresh rate unknown", out)

    def test_fail_on_regression_exit_code(self):
        self.candidate["scenarios"][_support.SCENARIO]["frameDurationCpuMs"]["p99"] *= 2
        a, b = self.write("a.json", self.base), self.write("b.json", self.candidate)
        self.assertEqual(run_main(a, b)[0], 0)
        self.assertEqual(run_main(a, b, "--fail-on-regression")[0], compare.EXIT_REGRESSED)

    def test_run_folder_with_summary_json_is_accepted(self):
        folder = Path(self.tmp.name) / "run"
        folder.mkdir()
        (folder / "summary.json").write_text(json.dumps(self.base))
        self.assertEqual(run_main(folder, folder)[0], 0)


if __name__ == "__main__":
    unittest.main()
