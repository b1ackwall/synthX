# Player performance benchmark

Macrobenchmarks for the player sheet, run on one physical phone. The benchmark app is a separate
package, `com.v1per.synthx.benchmark` ("synthX bench"), built from `:app`'s `benchmark` build
type: release code and R8, debug-signed, profileable. It installs next to production
(`com.v1per.synthx`) and the debug app (`com.v1per.synthx.debug`) and never replaces either.
The test APK is `com.v1per.synthx.macrobenchmark` (the `:benchmark` module).

Phones must run Android 12L (API 32) or newer. The script refuses anything older.

This file covers running the benchmarks. Reading the results (summarize, analyze, compare) is
in [docs/perf/README.md](../../docs/perf/README.md).

## One-time setup per phone

1. Settings > Developer options: turn on **USB debugging**, and **Install via USB** if the ROM
   has it (MIUI/HyperOS do). Connect the phone and accept the RSA prompt.
2. Install the benchmark app. Either run the script once (step "Run" below; it stops at the
   "not onboarded" check, which is expected the first time), or build and install it yourself:
   `./gradlew :app:assembleBenchmark` then
   `adb -s <serial> install -r app/build/outputs/apk/benchmark/app-benchmark.apk`.
3. Open **synthX bench** on the phone and onboard it:
   - Music folder: the **same folder the debug app uses** as its music folder. The benchmark
     only reads it.
   - New Arrivals folder: a **new, empty** folder used by nothing else (for example
     `synthX-bench/new-arrivals`). Keep it empty: anything placed there is moved into the
     music folder by the app's graduation step.
   - Backup folder: a **new, empty** folder used by nothing else (for example
     `synthX-bench/backups`).
4. Let the first library scan finish (the scan notification disappears).
5. Queue a long list: open Library > Songs and play the **first** song, so the rest of the list
   follows it. Answer the notification permission prompt if it appears. Then pause. The queue
   scroll needs a queue several screens long (it checks this itself by scrolling once before it
   measures), and the track-change scenarios need a next song.
6. Go back to the Library tab and leave the app.

The benchmarks check this on every run and stop with a message saying which step is missing.

## Before every run

- **Fix the refresh rate at 60 Hz** (Samsung: Settings > Display > Motion smoothness > Standard;
  a phone with a 60 Hz-only panel needs nothing). An adaptive panel changes rate between the
  idle pauses and the animations, which changes the frame deadline mid-run. Then check it: the
  run writes the rate it read into `device.json`, and `analyze_traces.py` prints the rate the
  traces actually ran at and flags a mismatch. The first recorded run was meant
  to be at 60 Hz and its trace shows 120 Hz, so do not trust the setting alone.
- Phone plugged in (or at least 80% charged), screen unlocked, battery saver off.
- Fixed brightness: turn off adaptive brightness and set a mid level.
- Stay-awake while charging (Developer options) helps keep the screen on.
- Media volume low: two scenarios (`miniIdlePlaying`, `nextMini`) play audio.
- Close other media apps. `nextMini` sends a media key and refuses to if another app would
  receive it.
- Let the phone cool down if it has been in use; a scenario stops if the phone reports thermal
  throttling.
- Only the target phone needs to be attached, but the script only ever talks to the serial you
  pass.

## Run

Set `JAVA_HOME` as in CLAUDE.md, then from the repo root:

```fish
adb devices -l                                                # find the serial
scripts/perf/run-benchmark.fish <serial>                      # every scenario, about 20 minutes
scripts/perf/run-benchmark.fish <serial> --scenario slowDrag  # one scenario
scripts/perf/run-benchmark.fish <serial> --scenario slowDrag,fling
scripts/perf/run-benchmark.fish <serial> --none               # CompilationMode.None (worst case)
scripts/perf/run-benchmark.fish <serial> --iterations 20      # same count for every scenario
scripts/perf/run-benchmark.fish <serial> --quick             # one scenario per mode, see below
scripts/perf/run-benchmark.fish <serial> --cooldown-temp 32    # wait until the battery is at or below 32 C
scripts/perf/run-benchmark.fish <serial> --cooldown-max 300    # longest temperature wait, seconds
scripts/perf/run-benchmark.fish <serial> --cooldown-fixed 30   # old fixed pause (--cooldown 30 is the same)
scripts/perf/run-benchmark.fish <serial> --slice-frames        # frame timing without the frame timeline
```

`--quick` runs `expandCollapse`, `slowDrag`, `queueExpandCollapse`, `nextExpanded` and
`homeExpandCollapse`, one per player mode, with their usual iteration counts. An explicit
`--scenario` (including `all`) overrides it.

The script builds both APKs with assemble tasks, installs them with `adb install -r` (never
uninstalls), and runs each scenario as its own `am instrument` call. It prints which scenario is
running, cools down between scenarios (see "Cooldown and heat"), and keeps going after a
failed scenario so one flaky scenario does not waste the run; it exits non-zero at the end and
lists the failures. A scenario also counts as failed when its result file has data for fewer
iterations than it ran (`check_result.py`, see "Dropped iterations" below). Everything lands in
one folder, `perf-output/<timestamp>/` (gitignored):

- `device.json`: model, API level, build fingerprint, refresh rate (and which values could not
  be read, under `refresh.not_parsed`), brightness mode, battery level, temperature and charging
  state at the start and at the end, the repo commit, and which scenarios ran and failed.
- `<scenario>-instrumentation.txt`: the runner's output, including failures.
- `<scenario>-benchmarkData.json`: per-iteration frame timings (`frameDurationCpuMs`,
  `frameOverrunMs`, frame count) and, per trace section, `<name>SumMs`, `<name>Count` and, for a
  few sections, `<name>MaxMs`.
- `<Class>_<method>_iterNNN_<time>.perfetto-trace`: one Perfetto trace per iteration; open in
  https://ui.perfetto.dev.

On API 32-33 the script disables Macrobenchmark's compilation reset, because on those versions
the reset uninstalls and reinstalls the target app, which would erase the setup above. The
compilation mode is then not applied, and the app runs in whatever state ART left it.

## Scenarios

`--scenario` takes the test method name. Each scenario launches synthX bench once, checks the
setup, waits for launch-time background work, confirms the baseline state (player collapsed,
queue mode off, playback paused, Library tab), and returns to that state when it ends.
Macrobenchmark force-stops the app before the first iteration and again after the last, so
every scenario starts from a cold process, and the end-of-scenario cleanup opens the app once
more and leaves it in the foreground at the baseline. Durations assume the default iteration
counts and about four seconds of tracing overhead per iteration.

| Scenario | Class | Iterations | About | What one iteration measures |
|---|---|---|---|---|
| `expandCollapse` | `PlayerExpandCollapseBenchmark` | 10 | 1 min | Tap the mini player to expand, system back to collapse, on Library. |
| `miniIdlePlaying` | `PlayerMiniIdleBenchmark` | 6 | 1.5 min | Ten seconds with the mini player showing and a song playing; no interaction. |
| `slowDrag` | `PlayerDragBenchmark` | 10 | 1.5 min | Drag up 35% of the display over one second, hold 300 ms, release; then the same downward. The sheet settles by its 20% positional threshold. |
| `fling` | `PlayerDragBenchmark` | 10 | 1 min | Swipe up half the display in 200 ms and release while moving, then the same downward; the sheet settles by fling velocity. |
| `queueToggle` | `PlayerQueueBenchmark` | 10 | 1 min | Expanded: tap the queue button to enter queue mode, tap again to leave. |
| `queueExpandCollapse` | `PlayerQueueBenchmark` | 10 | 1 min | Tap to expand and back to collapse while queue mode stays on. |
| `queueScroll` | `PlayerQueueScrollBenchmark` | 8 | 1.5 min | Expanded queue mode: two swipes toward the end of the list, two back, each a third of a second and ending in a fling. Includes the controls fading out and in. |
| `nextExpanded` | `PlayerTrackChangeBenchmark` | 8 | 1 min | Expanded, paused: tap next, wait for the art change. |
| `nextQueue` | `PlayerTrackChangeBenchmark` | 8 | 1 min | The same in queue mode, where the list also loses its first row. |
| `nextMini` | `PlayerTrackChangeBenchmark` | 8 | 1 min | Collapsed, playing: a MEDIA_NEXT key, because the mini player has no next button. |
| `homeExpandCollapse` | `PlayerHomeBenchmark` | 10 | 1 min | `expandCollapse` on the Home tab, whose animated background keeps running under the mini player. |
| `idleStates` | `PlayerIdleStatesBenchmark` | 4 | 3.5 min | No gesture inside the windows: four seconds each with the mini player paused and playing, expanded playing and paused, queue mode paused, and Home. Reports `<state>DrawnFps`, `<state>CallbackFps` and `<state>WindowMs` per iteration in the result file. Plays audio. |

Every scenario records `FrameTimingMetric` and one `TraceSectionMetric` per `synthX.*` section.

### When the frame timeline cannot be trusted (`--slice-frames`)

From API 31 `FrameTimingMetric` keeps only frames that have an actual frame timeline slice from
SurfaceFlinger. On the Redmi Note 7 Pro (Android 13 custom ROM) those slices report presents
that drift from 0.1 s to several seconds late over a run, while `dumpsys SurfaceFlinger
--latency` shows about 40 ms, so each iteration loses more frames past the end of its trace
(slowDrag: 281 frames in the first iteration, 7 in the tenth, with about 200 drawn in each).
`--slice-frames` replaces it with `SliceFrameTimingMetric`, which pairs the app's
`Choreographer#doFrame <id>` with its RenderThread `DrawFrame* <id>` and reports
`frameDurationCpuMs` (the same span) and `frameCount`, without `frameOverrunMs`. `device.json` and
`summary.json` record it as `frame_source`, and `compare.py` warns when two runs differ in it. Use
it for every run on such a phone; the Galaxy S25 Ultra keeps the default.

### How the drags are injected

Drags and swipes are sent by `TouchInjector` as equal steps exactly 33 ms apart, not with
UiAutomator's `swipe`. The reason is in the app: the player sheet measures the release velocity
from touch positions relative to itself while it moves under the finger. Its reading is about a
tenth of the real finger speed, and one uneven step (a shorter last step, a finger that slows
down, or Android's own smoothing of touch samples that are less than 20 ms apart) reads as a
reversal, which sends the sheet back where it came from. Equal steps more than 20 ms apart are
the input that settles the same way every time.

What this costs: at 60 Hz the sheet gets a new finger position on every second frame, so during
a drag about half the frames have no sheet movement. A real finger moves it on every frame.
`analyze_traces.py` lists those frames separately ("Frames without a PlayerSheet composition").

### Dropped iterations

Macrobenchmark keeps only the iterations in which every metric reported a value, and says
nothing about the rest. An iteration with no frames at all is dropped that way. The run script
checks each result file with `check_result.py` and fails the scenario when iterations are
missing, and `summarize.py` prints a warning for such a scenario. The traces of dropped
iterations are still in the run folder.

### What the scenarios touch

They tap, press back, drag vertically, and skip tracks. They never reorder or remove a queue
row, delete or replace a song, change a folder, or write to the music folder, and they never
target the production or debug packages. Track skips are undone with Previous, so a scenario
ends on the song it started on (at position zero).

### Flaky and less safe scenarios

- `queueScroll` is the **least safe**: the list it swipes on can remove a row (horizontal swipe)
  and reorder (the handle at the right edge). The swipe is one straight vertical line on the
  left third of the list, and the scenario compares the first three rows before the scrolling
  with the saved queue after a relaunch, and fails loudly if they differ. It cannot undo a
  change. A change would affect synthX bench's queue only, never a file. Before measuring it
  scrolls once unmeasured, and stops with a message if the queue is too short to scroll twice.
- `nextMini` is **ROM-dependent**: a media key goes to whichever app owns the media button
  session. The scenario reads `dumpsys media_session` and sends the key only if synthX bench is
  named as the owner; on a ROM that formats that dump differently it fails without sending
  anything. Run it last or skip it if another media app must stay untouched.
- `slowDrag` and `fling` depend on how the sheet reads release velocity (see "How the drags are
  injected"). If the sheet's gesture code changes, a drag that ends in the wrong state fails
  with the sheet's bounds in the message; adjust the constants in `PlayerGestures.kt`.
- `nextQueue`: the first skip after queue mode is turned on redraws the queue mask for about 25
  frames and the later ones do not, so iteration 0 has more frames than the rest.
- `miniIdlePlaying`: a song ending inside a ten-second window adds a track-change burst.
- `homeExpandCollapse`: with the system animation scale at 0, or battery saver on, the Home
  background stands still and the result matches `expandCollapse`.
- The class comment at the top of each benchmark file has the full notes.

## Cooldown and heat

Frame times drift as the phone warms up, and a full run is long enough to see it.

Between scenarios the script reads the battery temperature (`dumpsys battery`) and prints one
line:

- At or below `--cooldown-temp` (default 33.0 C, the limit used for the Redmi A-B runs): no wait.
- Above it: it re-reads every 5 s until the battery is at or below the limit, for at most
  `--cooldown-max` seconds (default 120). After that it prints a warning and continues.
- If the temperature cannot be read: a fixed 30 s pause, the old behavior.
- `--cooldown-fixed S` (or `--cooldown S`) skips the temperature check and always pauses S
  seconds.

`device.json` records the settings as `cooldown_mode`, `cooldown_temp_c`, `cooldown_max_s` and
`cooldown_s` (the fixed pause, also the fallback).

Keep the phone off wireless chargers and out of the sun, and
compare `battery_start` with `battery_end` in `device.json`: a rise of more than a few degrees
means the later scenarios ran hotter than the first. When two runs disagree, re-run the single
scenario with `--scenario` on a cool phone before trusting either number.

## Checks without a phone

```fish
fish scripts/perf/tests/test_device_info.fish          # device.json parsing
python3 -m unittest discover -s scripts/perf/tests     # summarize, compare, trace model
```
