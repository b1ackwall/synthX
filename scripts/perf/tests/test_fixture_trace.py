"""Runs the trace analysis on the committed fixture trace. Skipped without the perfetto package."""

import csv
import tempfile
import unittest
from pathlib import Path

import _support
import analyze_traces
import trace_model as tm

try:
    from perfetto.trace_processor import TraceProcessor
except ImportError:
    TraceProcessor = None

TRACES = sorted(_support.FIXTURES.glob("*.perfetto-trace"))


@unittest.skipIf(TraceProcessor is None, "the perfetto pip package is not installed")
@unittest.skipIf(not TRACES, "no fixture trace in docs/perf/fixtures")
class FixtureTraceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.out.cleanup)
        cls.code = analyze_traces.main([str(_support.FIXTURES), "--out", cls.out.name])
        cls.report = (Path(cls.out.name) / "trace-analysis.md").read_text()
        with open(Path(cls.out.name) / "trace-frames.csv", newline="") as handle:
            cls.rows = list(csv.DictReader(handle))

    def test_exit_code(self):
        self.assertEqual(self.code, 0)

    def test_frames_match_the_trace(self):
        self.assertEqual(len(self.rows), 382)
        self.assertEqual(sum(row["sheet_composed"] == "1" for row in self.rows), 184)
        self.assertEqual({row["scenario"] for row in self.rows}, {_support.SCENARIO})

    def test_expand_and_collapse_are_two_windows(self):
        self.assertIn("Animation windows: 2 in total", self.report)
        animation = [row for row in self.rows if row["window"] == "animation"]
        idle = [row for row in self.rows if row["window"] == "idle"]
        # Every sheet composition is inside a window; the pauses still produce frames.
        self.assertEqual(sum(row["sheet_composed"] == "1" for row in animation), 184)
        self.assertGreater(len(idle), 100)

    def test_vsync_period_is_read_from_the_trace(self):
        self.assertIn("(120 Hz, from frame timeline)", self.report)

    def test_every_fixture_slice_is_classified(self):
        self.assertIn("None: every slice on the main thread and RenderThread matched a rule.", self.report)

    def test_slow_frames_show_both_threads(self):
        self.assertIn("waiting on RenderThread", self.report)
        self.assertIn("texture upload", self.report)

    def test_all_sql_files_run(self):
        processor = TraceProcessor(trace=str(TRACES[0]))
        try:
            for path in sorted(tm.SQL_DIR.glob("*.sql")):
                with self.subTest(sql=path.name):
                    self.assertGreater(len(list(processor.query(path.read_text()))), 0)
        finally:
            processor.close()


if __name__ == "__main__":
    unittest.main()
