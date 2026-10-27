# Performance measurement

How to measure the player sheet on a phone and read the result. Running the benchmarks, the
phone setup and the scenario list are in [scripts/perf/README.md](../../scripts/perf/README.md).

## Workflow

```fish
# 1. Run (about 20 minutes for every scenario; needs the phone)
scripts/perf/run-benchmark.fish <serial>

# 2. Summarize: tables on stdout, summary.json in the run folder
python3 scripts/perf/summarize.py perf-output/<run>

# 3. Analyze the traces: trace-analysis.md and trace-frames.csv in the run folder
~/.venvs/perf/bin/python scripts/perf/analyze_traces.py perf-output/<run>
~/.venvs/perf/bin/python scripts/perf/analyze_traces.py perf-output/<run> --scenario slowDrag

# 4. Compare with an earlier run or a committed baseline (A is the baseline)
python3 scripts/perf/compare.py docs/perf/baselines/<device>-<yyyymmdd>.summary.json perf-output/<run>
```

`summarize.py` and `compare.py` use the Python standard library only. `analyze_traces.py` needs
the `perfetto` package, which downloads Google's TraceProcessor binary on first use:

```fish
python3 -m venv ~/.venvs/perf
~/.venvs/perf/bin/pip install perfetto
```

Without network access, pass an existing binary with `--trace-processor <path>`, or open a
trace in https://ui.perfetto.dev and paste a query from `scripts/perf/sql/`. The four files
without a `ui_` prefix are the exact queries the script runs; the `ui_` ones are extra views
for the UI (slowest frames, synthX sections in time order, texture uploads).

## Reading summarize.py

One block per scenario, named `<Class>.<method>`.

- **frameDurationCpuMs**: how long the app took to produce a frame, from the main thread
  starting it to the RenderThread finishing it. Percentiles are over every frame of every
  iteration. It does not include GPU or compositor time.
- **frameOverrunMs**: how far past its deadline the frame finished, as the system measured it.
  Negative is early. This is the number that corresponds to a visible late frame, and it is
  measured against the refresh rate the panel was actually at.
- **Janky frames**: frames whose CPU time exceeds a budget. The 60 Hz budget is 16.67 ms and
  the 120 Hz budget is 8.33 ms; both are always shown. The "device budget" row uses the refresh
  rate in `device.json` and is `n/a` without one. "Deadline missed" counts `frameOverrunMs > 0`
  and needs no assumption about the refresh rate.
- **frameCount**: frames produced per iteration. A count well above what the animation needs
  means something keeps drawing during the pauses.
- **WARNING: only N of M iterations have data**: the benchmark library dropped the other
  iterations because a metric reported nothing in them (most often no frame was drawn). The
  numbers then cover the recorded iterations only; `summary.json` has both counts as
  `iterations` and `iterationsRecorded`. The run script fails such a scenario.
- **Sections**: each `synthX.*` trace section's median total time, run count and longest run
  per iteration. Sections nest, so do not add them up.

These numbers blend animation frames with the frames of the fixed pauses, which makes the
percentiles look better than the animation alone. `analyze_traces.py` separates the two.

### P50 is not stable for four scenarios on the Redmi

For `fling`, `expandCollapse`, `queueExpandCollapse` and `nextQueue` on the Redmi Note 7 Pro (60 Hz),
about half of the frames are over the 16.67 ms deadline in a typical iteration. The
`frameDurationCpuMs` distribution is then bimodal, around 11 ms and around 25 ms, so P50 sits on
the boundary and flips between the two on a single outlier iteration. The same APK has given P50
values of 11.6 and 18.4 ms in different sessions. For these scenarios read the share of frames over
the deadline instead: the "CPU time over 16.67 ms (60 Hz)" row in `summarize.py` (`jank.over_60hz_budget`
in `summary.json`) and the "deadline missed" row (`jank.deadline_missed`), which `compare.py` lists as
"frames over 16.67 ms" and "deadline missed".

## Reading analyze_traces.py

- **Vsync period**: the refresh rate the trace actually ran at, from the frame timeline. If it
  disagrees with `device.json`, the report says MISMATCH; trust the trace. A scenario that
  draws only a frame or two per second (`miniIdlePlaying`) has no back-to-back frames to read
  it from, and the report then says "not measurable" and checks nothing.
- **Animation windows vs idle**: a window is a dense run of `synthX.*` sections, input events or
  layout passes (no gap over 50 ms, at least 50 ms long) plus a 50 ms margin. Layout passes are
  what mark a list fling, which re-lays the queue out on every frame without composing a section.
  Everything else is idle, including frames that only redraw (the expanded player's background,
  Home's animated background). The same statistics are given for both, so the pauses no longer
  dilute the animation.
- **Where the time goes**: mean self time per frame by class, for the main thread
  (recomposition, measure/layout, draw recording, waiting on the RenderThread) and the
  RenderThread (sync, draw, GPU flush, shader/pipeline creation, texture upload, GPU memory,
  buffer queue). "Waiting on RenderThread" is the main thread blocked until the RenderThread
  takes the frame; it is long when the previous frame's RenderThread work is still running.
- **Slowest animation frames**: one row per frame with both threads' breakdown and the synthX
  sections that ran in it. Use the vsync id to find the frame in the Perfetto UI.
- **Top slices**: by total time and by longest single run, per thread, across all iterations.
- **Texture uploads by size**: many sizes a few pixels apart mean a full-size layer or bitmap
  is uploaded again every frame as it resizes.
- **Frames without a PlayerSheet composition**: what produced the frames in which the sheet did
  not recompose.
- **Unclassified slice names**: names the table in `scripts/perf/slice_classes.py` does not
  know. That table was built from the fixture trace and from the first full run of every
  scenario on the Galaxy S25 Ultra; add the listed names to it after looking at them in a trace.
  Their time is reported as "unclassified", never dropped.

`trace-frames.csv` has one row per frame with the same columns, for a spreadsheet.

## Reading compare.py

Every metric is "lower is better". A change is flagged `REGRESSED` or `IMPROVED` when it is at
least `--threshold` percent (default 10) and also at least a small absolute amount (0.5 ms for
frame times, 1 percentage point for janky-frame shares, 1 ms for section totals), so a tiny
value doubling is not reported as a regression. Scenarios present in only one run are listed and
skipped. The script refuses to compare runs from different device models or different refresh
rates unless `--force`; an unknown refresh rate on either side is a warning, not a refusal.
`--fail-on-regression` makes the exit code 1 when anything regressed.

## Baselines

A baseline is a run's `summary.json`, committed as
`docs/perf/baselines/<device>-<yyyymmdd>.summary.json` (for example
`docs/perf/baselines/SM-S938U-20261013.summary.json`). Commit a new one when a change is meant
to move the numbers, and say so in the commit message. Traces, `trace-analysis.md`, CSVs and the
raw `perf-output/` folders are not committed; they are large and only meaningful next to the
phone state they were taken in. `docs/perf/fixtures/` holds one real result file and one real
trace from the first run, which the script checks in `scripts/perf/tests/` read.

## Known limits

- **Injected drags are not a finger**: `slowDrag`, `fling` and `queueScroll` send one touch
  sample every 33 ms, so at 60 Hz the sheet moves on every second frame during a drag. The
  reason, which is in how the sheet reads release velocity, is in
  [scripts/perf/README.md](../../scripts/perf/README.md) under "How the drags are injected".
  Frame times for the frames that do move the sheet are real; the share of late frames across a
  whole drag is diluted by the frames in between.

- **ROM differences**: slice names differ between Android versions, GPU drivers and vendors, so
  a new phone will list unclassified names until the table is extended. The refresh-rate and
  media-session parsing in the run script are also ROM-dependent; `device.json` records what
  could not be read.
- **Adaptive refresh**: a panel that changes rate mid-run changes the frame deadline with it.
  Budgets taken from `device.json` are then wrong for part of the run; `frameOverrunMs` and the
  trace's own vsync period stay correct.
- **Thermal drift**: later scenarios run on a warmer phone. Compare the battery temperatures in
  `device.json` and re-run single scenarios when in doubt.
- **Run-to-run noise**: differences under about 10% between two runs of the same build are
  normal. Re-run before acting on a small change.
- **One phone at a time**: numbers are only comparable on the same model at the same refresh
  rate, which is what `compare.py` enforces.
- **CPU time only**: `frameDurationCpuMs` and the trace breakdown stop when the RenderThread
  hands the frame over. Time spent by the GPU and the compositor shows up in `frameOverrunMs`
  and the frame timeline's jank type, not in the breakdown.
