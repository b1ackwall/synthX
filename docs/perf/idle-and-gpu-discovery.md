# Idle drawing and background-effect discovery

Discovery on the Galaxy S25 Ultra (SM-S938U, serial withheld, Android 17), 60 Hz
(`refresh_rate_mode` 0, `device.json` refresh.hz 60.000004, vsync 16.64-16.65 ms in every trace),
benchmark build of commit `c7537c9` plus the new idle scenario below. Every run started with
the battery at 32.1-33.1 C and ended at 32.4-33.7 C. Two kinds of statement appear:
**Measured** comes from traces, result files or the GPU sampler; **Inferred** rests on source
reading or on how Compose/HWUI work and was not tested directly.

## 1. Findings in short

1. **The always-drawing is `basicMarquee` on the song title.** Measured: draw-only frames at
   60 fps for the first 45-65 s after the app starts, in every state including mini player
   paused, then 0 fps. Inferred, strongly supported: the cause is the four `basicMarquee`
   texts, which are composed at all times and run Compose's default three passes.
2. **It happens in normal use**, not only in the benchmark: after every app start and (inferred)
   every track change whose title or artist overflows its row, for about 50-65 s with this
   title, whether the player is collapsed, expanded, playing or paused.
3. Once the marquee ends, the steady idle rates are: mini paused 0 fps, mini playing 2 fps,
   expanded 15-17 fps (the 15 fps background, as designed), queue mode 15 fps, Home 60 fps (the
   animated background, as designed). Expanded and queue modes still wake the main thread
   60 times a second to draw 15 frames.
4. **The background RenderEffect is about half the render passes but a small part of the
   time.** Effect off: render passes per moving frame 19-20 to 9 (the blur alone is about 8 of
   them), RenderThread GPU flush per animation frame down 19-24%, RenderThread time down
   14-16%, cpu p50/p90/p99 down 6-12% (below the 20% this phone needs before trusting a
   difference). Measured GPU busy time per iteration did not change beyond the ±5% noise. The
   GPU sits at its lowest clocks (160-222 MHz) and 17-21% busy throughout.
5. The worst frames are not the effect: in both arms of `expandCollapse` they are the first
   iteration's GPU memory allocations (4-13 ms of `vkAllocateMemory` on the first expands).

## 2. Runs

All in `perf-output/` (gitignored). "A" is the unmodified build, "B" is the effect turned off,
"C" is the blur alone turned off. `gpu` means the kgsl GPU sampler (section 4.1) ran alongside.

| Run | Arm | Scenarios | Battery start / end C |
|---|---|---|---|
| 20261013-213307 | reference, `fb2b546` | expandCollapse | 32.0 / 32.3 |
| 20261013-215333 | reference, `b77bfcc` | expandCollapse | 31.1 / 31.7 |
| 20261013-215444 | reference, `b77bfcc` | queue scenarios, nextQueue | 31.8 / 33.0 |
| 20261013-205918 | reference, `f005dd4` (newest run with slowDrag before today) | slowDrag and others | 30.4 / 32.4 |
| 20261013-222150 | A | idleStates (new) | 31.6 / 32.4 |
| 20261013-222811 | A | expandCollapse, slowDrag, queueExpandCollapse | 32.2 / 32.9 |
| 20261013-223734 | B | expandCollapse | 32.4 / 32.8 |
| 20261013-224218 | B, gpu | slowDrag | 32.1 / 33.0 |
| 20261013-224420 | B, gpu | queueExpandCollapse | 33.1 / 33.7 |
| 20261013-224912 | B, gpu | expandCollapse | 32.6 / 33.2 |
| 20261013-225329 | C, gpu | expandCollapse | 32.7 / 33.3 |
| 20261013-225736 | A (restored source), gpu | expandCollapse | 32.8 / 33.3 |
| 20261013-230421 | A (restored source), gpu | slowDrag | 32.5 / 33.4 |
| 20261013-230956 | A (restored source), gpu | queueExpandCollapse | 32.9 / 33.6 |

`224420` started at 33.1 C, just over the 33 C limit set for control runs.

## 3. Part A: what keeps the app drawing at idle

### 3.1 The old runs

Measured, idle frames (outside animation windows) that reached the RenderThread:

| Run | Scenario | Idle frames | Drew |
|---|---|---|---|
| 213307 | expandCollapse | 64 | 20 |
| 212349 | expandCollapse / queueExpandCollapse / queueToggle | 70 / 63 / 491 | 21 / 20 / 134 |
| 215333 | expandCollapse | 824 | 824 |
| 215444 | queueExpandCollapse / queueToggle / queueScroll / nextQueue | 758 / 484 / 708 / 659 | 753 / 482 / 549 / 312 |

In 215333 the idle frames have, per frame, 0.021 ms of recomposition, no measure/layout, no
`synthX.*` section (except `PlayerBackground.draw` in 20 of 824), and every one runs
`Recomposer:animation` and `AndroidOwner:draw`: a frame-clock animation invalidating draw only.
Cost per idle frame: cpu p50 4.99 ms (main 1.46, RenderThread 4.01); means of 0.32 ms draw
recording, 0.25 ms animation callbacks and 0.42 ms waiting on the RenderThread on the main
thread, and 1.66 ms GPU flush, 0.52 ms draw and 0.64 ms buffer queue on the RenderThread.

When it stops (measured, per iteration of 215444): `queueExpandCollapse` draws every idle frame
through iteration 8 and stops 1.1 s into iteration 9; `queueToggle` (a new process) draws in
all ten; `queueScroll` stops during iteration 5 (iterations 6 and 7 draw 24 and 22 of about 87);
`nextQueue` never shows it in its measured windows. Every scenario starts a new process, so the
pattern is "about a minute after the process starts", not a state. Inferred for `nextQueue`:
its measured windows show the next song, whose title presumably fits.

Not explained: 213307 and 212349 (build `fb2b546`) show none of it. The only app change between
`fb2b546` and `b77bfcc` is the queue list height (`9c3ab99`), which is not composed in
`expandCollapse`, so the likely difference is which song was current. That was not checked; the
traces do not name the song, and no git command was run to inspect the commits.

### 3.2 Frame-driven work in the source

Paths under `app/src/main/java/com/v1per/synthx/`.

| Where | What drives it | Active when | Reaches idle shell / mini player |
|---|---|---|---|
| `ui/player/PlayerSkeletonMiniContent.kt:83` | `basicMarquee()` on the mini title: one pass (`Motion.MarqueeIterations`, since 038d383; 3 passes when measured here), 1.2 s delay, 30 dp/s, restarts on attach and on any content or container width change | Always composed with the sheet, alpha 0 while expanded | Yes |
| `ui/player/PlayerSkeletonExpandedContent.kt:66, 74` | `basicMarquee()` on the expanded title and artist, one pass | Always composed; alpha 0 and outside the sheet's clip while collapsed | Yes, invisible |
| `ui/player/PlayerSkeletonQueueContent.kt:199` | `basicMarquee()` on the queue header title, one pass | Always composed; alpha 0 outside queue mode | Yes, invisible |
| `ui/player/PlayerAltStageMotion.kt:105-119` (`runAltStageMotion`) | Updates and redraws at 10 fps (`PlayerAltStageStyle.FrameCapFps`; 15 when measured here). Since #188 it sleeps between updates and asks only for the update's frame (one or two per update, depending on the refresh rate); it used to ask for every frame | Sheet progress at or above 0.001, a song, music playing (since #188), no reduced motion | No (paused while collapsed or not playing) |
| `ui/components/AmbientBlobBackground.kt:179, 337, 396` | `withFrameNanos` loops | Never since 00b8878: Home draws a solid background and the call is commented out (`HomeScreen.kt:42`) | No (Home was 60 fps when measured here) |
| `ui/components/AmbientMotionPolicy.kt:48` | `delay(1 000)` poll of power saver and animation scale | Whenever the player background is composed (Home no longer composes it) | One main-thread wakeup per second, no draw |
| `playback/ExoPlayerEngine.kt:158-162` | 500 ms position and duration ticker | While collected | Yes: the hidden controls overlay recomposes twice a second while playing |
| `playback/ExoPlayerEngineListener.kt:269` | Periodic checkpoint `delay` loop | While playing | No UI |
| `ui/screens/library/RecentlyAddedSection.kt:193` | `rememberInfiniteTransition` skeleton pulse, read in composition | Only while Recently Added is loading | Not seen in any trace |
| `ui/components/PlayerAlbumArt.kt:54`, `ui/player/PlayerSheetSkeleton.kt:173` | `animateFloatAsState` on play state and controls visibility | Only after a target change | Finite |

The basicMarquee behaviour is from the Compose Foundation 1.12.0 source (`BasicMarquee.kt`):
`Iterations = 3`, `RepeatDelayMillis = 1_200`, `Velocity = 30.dp`, spacing a third of the
container. The animation restarts in `onAttach`, in `update` when its parameters change, and
whenever `contentWidth + spacing` changes (the `snapshotFlow`), which a new title causes. The
offset is read in the draw phase, so each step invalidates draw only, which matches the trace.

### 3.3 The new scenario

`PlayerIdleStatesBenchmark.idleStates` (`benchmark/.../PlayerIdleStatesBenchmark.kt`, metric in
`IdleStateFrameMetric.kt`). Each iteration: mini paused, mini playing, expanded playing,
expanded paused, queue mode paused, Home; 0.5 s to settle after each transition, then four
seconds inside a `bench.idle.<state>` trace section written by the test process. The metric
counts the app's RenderThread `DrawFrames` and main-thread `Choreographer#doFrame` slices inside
each section and reports `<state>DrawnFps`, `<state>CallbackFps` and `<state>WindowMs`.
`runScenario` gained an optional `extraMetrics` parameter (default empty, so the other
scenarios are unchanged), and `scripts/perf/run-benchmark.fish` and `scripts/perf/README.md`
list the scenario. `summarize.py` does not print the new metrics; read them from
`idleStates-benchmarkData.json`.

Measured, run 222150 (frames per second, iterations 0 / 1 / 2 / 3):

| State | Drawn | Main-thread frames |
|---|---|---|
| miniPaused | 60.0 / 0 / 0 / 0 | 60.0 / 1.0 / 1.0 / 1.0 |
| miniPlaying | 60.2 / 2.0 / 2.0 / 2.0 | 60.2 / 6.2 / 6.2 / 6.0 |
| expandedPlaying | 60.0 / 17.0 / 16.0 / 16.0 | 60 in all |
| expandedPaused | 60.2 / 15.0 / 15.0 / 15.0 | 60 in all |
| queuePaused | 60.0 / 15.0 / 15.0 / 15.0 | 60 in all |
| homePaused | 60 in all | 60 in all |

The current song was "You Were Loved (Burial) (from "Project Hail Mary")" (from
`dumpsys media_session`). The app started at 1791948113.07 (logcat, epoch s). With the traces'
clock snapshot, iteration 0's windows ran 19.7-53.1 s after launch and drew continuously, and
iteration 1's ran 65.4-101.1 s and did not (apart from Home). The drawing therefore stopped
between 45 s (end of `queuePaused`; Home's own animation hides 45-53 s) and 65 s after launch.
Inferred estimate for three passes of this title (Outfit at font scale 0.9, rows measured from
the layout code, glyph widths guessed at 0.5 em): mini title about 51 s, queue header about
51 s, expanded title about 65 s. The measured window agrees.

Iteration 0's `miniPaused` window has 240 frames, each with `Recomposer:animation` and
`AndroidOwner:draw`, and 0 recompositions, 0 layouts and 0 `synthX.*` sections. That rules out
the skeleton pulse (read in composition), the position ticker (recomposes), and the background
loop (paused while collapsed, and would emit `PlayerBackground.draw`).

Cost per state, measured (iteration 0 = marquee running, iteration 2 = after it):

| State | Marquee running: RT ms per frame / RT busy / main busy | After: drawn fps / RT busy / main busy |
|---|---|---|
| miniPaused | 1.02 / 6.1% / 2.6% | 0 / 0% / 0.0% |
| miniPlaying | 1.90 / 11.4% / 6.3% | 2 / 0.5% / 1.2% |
| expandedPlaying | 2.29 / 13.7% / 5.3% | 16 / 5.3% / 2.4% |
| expandedPaused | 2.92 / 17.6% / 7.2% | 15 / 9.6% / 5.3% |
| queuePaused | 4.82 / 28.9% / 10.3% | 15 / 9.9% / 4.3% |
| homePaused | 6.51 / 39.1% / 8.1% | 60 / 38.6% / 7.1% |

"Busy" is the summed slice time over the four-second window. GPU time is not in these numbers.

### 3.4 What is continuous, what is expected, what is waste

- **Waste: the marquees.** 60 fps for about a minute after every start and (inferred) every
  track change with an overflowing title, at 1-5 ms of RenderThread time per frame depending on
  state. Three of the four marquees are invisible at any moment and still drive frames. With
  songs of 3-4 minutes and long titles, that is roughly a quarter to a third of playback time
  at 60 fps (inferred).
- **Expected: the 15 fps background** while expanded or in queue mode (`PlayerBackground.draw`
  at 15 fps, RenderThread 3.3-6.6 ms per redraw), and **Home's 60 fps background**
  (RenderThread 6.4 ms per frame, 39% busy).
- **Small waste: 45 empty frame callbacks per second** in expanded and queue modes, because the
  background loop asks for every frame and draws one in four (main thread 0.4-0.9 ms per
  callback, 2.4-5.3% busy).
- **Small waste: the hidden controls overlay follows the position ticker** while collapsed and
  playing: 2 drawn frames and about 6 main-thread frames per second, 1.9 ms main and 2.6 ms
  RenderThread per drawn frame.
- **Negligible: the motion-policy poll**, one 0.03 ms main-thread frame per second.
- Not measured: whether any of this continues with the activity stopped. Inferred no: Compose
  pauses its frame clock when the window is not visible.

## 4. Part B: price of the background effect

### 4.1 Method

The control replaced `renderEffect = effect` with `renderEffect = null` in
`PlayerAltStageBackground.kt:88` (effect off; the layer, clip, drawing and motion unchanged).
The split control instead passed `0f` as the blur radius at line 73 (blur off, twist and
saturation kept). Both were built and run, then the file was copied back (section 6).

Frame times on this phone move with CPU and GPU clocks from run to run by more than the effect
does. Two runs of the same build differ by up to 30% (222811 against 225736, `expandCollapse`
cpu p50 5.48 and 4.77 ms), and main-thread time, which the effect cannot change, varied from
2.3 to 3.6 ms per frame between runs. The tables therefore pair runs whose main-thread time
matches (2.31 / 2.34 / 2.28 ms, 2.09 / 2.09 ms and 2.54 / 2.52 ms) and add three measures that
do not depend on CPU clocks:

- render passes per moving frame: `OpsTask::onPrepare` slices per `DrawFrames`, in frames where
  `synthX.PlayerSheet` composed;
- GPU busy time per iteration: `/sys/class/kgsl/kgsl-3d0/gpubusy` (busy and total µs over
  windows of about one second) and `clock_mhz`, read about every 50-70 ms by an adb shell loop
  during the run and summed over each trace's time span (traces and `/proc/uptime` share the
  boot clock);
- "waiting for GPU completion" slice length per moving frame.

### 4.2 Results, animation windows

Measured. RT columns are mean ms per animation frame. "Passes" is the median per moving frame.

| Scenario, run | Arm | cpu p50 / p90 / p99 | Over 16.67 ms | RT GPU flush | RT draw | RT layers | RT total | Main | Passes | GPU busy per iteration |
|---|---|---|---|---|---|---|---|---|---|---|
| expandCollapse 213307 | reference | 7.43 / 11.82 / 14.52 | 0.6% | 2.47 | 0.71 | 0.16 | 4.40 | 3.26 | 19 | n/a |
| expandCollapse 215333 | reference | 7.82 / 13.32 / 16.31 | 0.6% | 2.69 | 0.73 | 0.18 | 4.86 | 3.43 | 20 | n/a |
| expandCollapse 222811 | A | 5.48 / 8.06 / 15.38 | 0.6% | 1.77 | 0.51 | 0.15 | 3.22 | 2.55 | 20 | n/a |
| expandCollapse 223734 | B | 7.42 / 12.19 / 14.34 | 0.4% | 2.14 | 0.56 | 0.19 | 4.05 | 3.64 | 9 | n/a |
| **expandCollapse 225736** | **A** | 4.77 / 8.18 / 11.21 | 0.2% | 1.58 | 0.46 | 0.13 | 2.98 | 2.31 | 20 | 877 ms, 18.4% |
| **expandCollapse 224912** | **B** | 4.33 / 7.22 / 10.49 | 0.3% | 1.26 | 0.36 | 0.13 | 2.49 | 2.34 | 9 | 926 ms, 17.2% |
| **expandCollapse 225329** | **C (blur off)** | 4.02 / 7.68 / 10.89 | 0.3% | 1.29 | 0.36 | 0.14 | 2.53 | 2.28 | 12 | 842 ms, 17.1% |
| slowDrag 205918 | reference | 7.77 / 12.30 / 14.61 | 0.0% | 2.73 | 0.77 | 0.21 | 4.81 | 3.32 | 19 | n/a |
| slowDrag 222811 | A | 8.05 / 12.80 / 15.14 | 0.3% | 2.86 | 0.79 | 0.23 | 5.13 | 3.49 | 19 | n/a |
| **slowDrag 230421** | **A** | 4.42 / 7.37 / 10.63 | 0.0% | 1.53 | 0.45 | 0.16 | 2.80 | 2.09 | 19 | 1327 ms, 21.6% |
| **slowDrag 224218** | **B** | 11.26 / 13.82 / 17.11 | 1.7% | 1.16 | 0.35 | 0.13 | 7.28 | 2.09 | 9 | 1186 ms, 19.3% |
| queueExpandCollapse 215444 | reference | 8.50 / 14.27 / 17.36 | 1.9% | 3.20 | 0.75 | 0.39 | 5.57 | 3.83 | 19 | n/a |
| queueExpandCollapse 222811 | A | 9.61 / 14.68 / 17.68 | 3.6% | 3.38 | 0.80 | 0.39 | 5.75 | 4.25 | 19 | n/a |
| **queueExpandCollapse 230956** | **A** | 5.12 / 8.85 / 12.73 | 0.1% | 1.79 | 0.45 | 0.26 | 3.21 | 2.54 | 19 | 891 ms, 20.9% |
| **queueExpandCollapse 224420** | **B** | 4.64 / 8.16 / 11.70 | 0.0% | 1.45 | 0.35 | 0.23 | 2.77 | 2.52 | 9 | 849 ms, 19.6% |

The bold rows are the matched pairs. The slowDrag reference is 205918, the newest run with
slowDrag before today (commit `f005dd4`, so its build differs from today's); 230421 is the
like-for-like baseline. The slowDrag control (224218) spent 5.15 ms per animation frame in the
RenderThread's buffer queue (`dequeueBuffer` waiting for a free buffer) against 0.64 ms in every
other run. Its cpu times are inflated by that wait. The effect removal cannot plausibly cause
such a wait, and its GPU busy time was lower, so I read it as a pipeline or compositor state of
that run (inferred). Its RT GPU flush, draw and pass counts are still comparable.

Matched-pair differences, effect off against on:

| | expandCollapse | slowDrag | queueExpandCollapse |
|---|---|---|---|
| RT GPU flush per animation frame | -0.32 ms (-20%) | -0.37 ms (-24%) | -0.34 ms (-19%) |
| RT total per animation frame | -16% | not comparable (buffer wait) | -14% |
| cpu p50 / p90 / p99 | -9% / -12% / -6% | not comparable | -9% / -8% / -8% |
| Render passes per moving frame | 20 to 9 | 19 to 9 | 19 to 9 |
| GPU busy per iteration | +6% | -11% | -5% |
| Slowest frame, RT GPU flush | 5.3 to 8.6 ms (iteration-0 frames) | 7.6 to 4.0 ms | 7.7 to 5.9 ms |

The blur-off arm sits with the effect-off arm: RT GPU flush 1.29 against 1.26 ms, 12 against 9
passes. The blur is therefore about 8 of the effect's 11 passes and nearly all of its RT time;
twist and saturation together are about 3 passes and about 0.03 ms (measured, one run each).

### 4.3 The 10 slowest animation frames

Measured, from each run's `trace-analysis.md`.

- `expandCollapse` A (225736): six of ten are in iteration 0, led by GPU memory allocation
  (3.9-11.5 ms) on the first expand and collapse, then GPU flush 1.5-3.6 ms. The other four are
  in later iterations: GPU flush 3.6-5.3 ms, with 1.8-2.2 ms of recomposition on the main
  thread. Slowest cpu 17.25 ms.
- `expandCollapse` B (224912): the same make-up. Five iteration-0 frames with allocation 2.0-8.5
  ms and GPU flush 2.5-8.6 ms; the rest GPU flush 2.3-3.6 ms. Slowest 21.89 ms. Blur-off C
  (225329): the same pattern, allocation up to 13.5 ms.
- `slowDrag` A (230421): GPU flush 3.9-7.6 ms in all ten, recomposition 0.6-2.5 ms, one with a
  3.1 ms allocation. Slowest 14.17 ms. B (224218): buffer queue 6.7-14.1 ms in all ten, GPU
  flush 2.0-4.0 ms.
- `queueExpandCollapse` A (230956): GPU flush 4.0-7.7 ms, layers 0.5-1.4 ms, recomposition
  1.4-2.8 ms. Slowest 16.97 ms. B (224420): GPU flush 2.4-5.9 ms, the rest alike. Slowest
  13.84 ms.

### 4.4 The effect's share

- **Measured, RenderThread side:** about 0.32-0.37 ms of the 1.5-1.8 ms "GPU flush" per
  animation frame (19-24%), and 14-16% of RenderThread time per animation frame. About 11 of
  the 19-20 render passes per moving frame.
- **Measured, GPU side:** no difference in GPU busy time beyond the ±5-11% spread between runs;
  the GPU stayed at 160-222 MHz and 17-22% busy in both arms. The effect's GPU time is therefore
  small. Inferred bound: under about 1 ms of GPU time per re-filter at 160 MHz, from that spread
  over roughly 116 re-filters per iteration (94 moving frames plus about 22 background redraws).
- **Not reliable here:** "waiting for GPU completion" per moving frame was 5.53 ms on against
  3.40 ms off in the matched pair, but 2.02 on against 3.12 off in the earlier unmatched pair,
  and the busy time does not support a 2 ms difference. The wait includes queueing behind
  earlier frames and depends on the GPU clock, so it was not used.
- Off, the background has no offscreen layer at all (22 redraws of the 1440x3064 layer per
  iteration to 0), while texture draws per frame stay the same (18.5 moving, 14 idle). The art
  is drawn either way; what goes away is the filter chain over 4.4 Mpx.

## 5. Part C: options (not implemented)

Ranked by expected saving against visual and risk impact. "Measure with" names existing tools.

### 5.1 Continuous drawing

1. **Play each title marquee once per track** (`iterations = 1` on the four `basicMarquee`
   calls). Saves about two thirds of the 60 fps time per track change: about 50-65 s to about
   17-22 s for this title. In the mini paused state that is 6.1% RenderThread and 2.6% main
   thread for 35-45 s fewer per track. Visual: the title scrolls once, then rests ellipsized.
   Files: `PlayerSkeletonMiniContent.kt`, `PlayerSkeletonExpandedContent.kt`,
   `PlayerSkeletonQueueContent.kt`. No gesture code touched. Measure with `idleStates`: the
   iteration in which `miniPausedDrawnFps` reaches 0, and the idle row's "drew" count of
   `expandCollapse` in `trace-analysis.md` (824 of 824 today).
2. **Run only the visible marquee.** Pass `iterations = 0` to a copy while its alpha is 0 (mini
   while expanded, expanded title and artist while collapsed or in queue mode, queue header
   outside queue mode), from a `derivedStateOf { alpha > 0f }` so composition sees only the
   threshold. Visual: none, except a marquee starts from the beginning, after its 1.2 s delay,
   when its row fades in. Saving: the hidden copies' frames, which matter when the visible row
   fits but a hidden one overflows (the expanded title is 22 sp against 16 sp), and the tail
   after the visible one ends (about 14 s per track here, inferred). Combine with option 1 for
   the most. Restarting on a threshold crossing is a recomposition of three Text nodes, twice
   per expand. Measure with `idleStates` and `expandCollapse` idle frames.
3. **Background loop wakes 15 times a second, not 60.** In `PlayerAltStageMotion.kt:97-104`,
   wait about one frame-cap interval with `delay` before each `withFrameNanos`, so the
   accumulator still sees real frame times. `FrameCapFps` and the motion stay as they are; this
   is not `PlayerSheetMotion.kt`. Saves about 45 callbacks a second (2.4-5.3% of the main
   thread) in expanded and queue modes. Visual: none expected; check the redraw stays at 15 fps.
   Measure with `idleStates`: `expandedPausedCallbackFps` 60 to about 15.
   Implemented for #188, together with a 10 fps cap and a pause while the music is not playing
   (not yet measured).
4. **Hidden controls overlay ignores the position ticker while collapsed.** Read the position
   only where the overlay is visible (for example a `() -> Long` read in the slider's draw or
   layout, or stop collecting while the sheet is collapsed). Saves 2 drawn frames and about 4
   main-thread frames per second while playing collapsed, about 1% of a core. Measure with
   `idleStates`: `miniPlayingDrawnFps` 2 to 0.
5. Not proposed: Home's background (60 fps, 39% RenderThread) is time-driven by design, and the
   one-second motion-policy poll costs 0.03 ms.

### 5.2 Background effect

The effect is about 0.3 ms of RenderThread time per animation frame and no visible GPU time,
with sheet jank already at 0.0-0.2% in the matched baselines. None of these is urgent.

1. **Keep the clip change from re-running the filter.** Inferred mechanism (from
   layer-discovery 2.5, unverified): HWUI re-filters the layer whenever the clip around it
   changes. Wrap the filtered background in an outer `graphicsLayer` with
   `CompositingStrategy.Offscreen` and the same constant bounds, so the sheet's moving clip
   applies to an already-filtered texture. Expected, if the mechanism holds: passes per moving
   frame 20 to about 9-10 and RT GPU flush about -0.3 ms per animation frame (the B numbers),
   with the filter running only on the 15 fps redraws. Cost: one more 1440x3064 layer (about 17.6
   MB) and one texture draw per frame. Visual: none. One modifier in
   `PlayerAltStageBackground.kt`. Measure with the pass count (`OpsTask::onPrepare` per frame)
   and `trace-analysis.md`'s RT GPU flush, in a same-session A/B with matched main-thread time.
2. **Run the filter at lower resolution.** Draw the art into a quarter-size layer (360x766) with
   the blur radius scaled by the same factor, then scale the filtered result up. A 120 dp blur
   hides the upscale; the twist runs on 1/16 of the pixels. Expected: most of the effect's GPU
   fill gone and some of its passes; RT saving up to the B numbers. Visual: slight softening
   at the twist's edge; compare screenshots. Files: `PlayerAltStageBackground.kt` and
   `PlayerAltStageEffects.kt` (the twist's uniforms scale with the layer size). Measure as above.
3. **Pre-blur the art once per art change.** Blur is linear and isotropic, so each copy could
   be drawn from a pre-blurred bitmap (sigma divided by the copy's scale) and only twist and
   saturation stay per-frame. Expected: like C, 20 to about 12 passes and RT GPU flush about
   -0.3 ms. Visual risk: real. The copies overlap and are drawn opaque over each other, so
   blur-then-composite is not the same as composite-then-blur where they overlap. Larger change.
4. **Drop or move the twist.** Measured at about 3 passes and 0.03 ms of RenderThread time
   together with saturation: not worth a visual change.

None of these touches `anchoredDraggable`, gesture code or `PlayerSheetMotion.kt`; all are
draw-phase changes in the background or text rows.

## 6. Restoration, limits and how to re-check

- Touched under `app/` (temporary, restored): `ui/player/PlayerAltStageBackground.kt`. Copied to
  the session scratchpad with `PlayerAltStageEffects.kt` before the first edit, copied back
  after the last control. `diff` against the copies prints nothing for either file, SHA-256 is
  `442a8776...` and `01ee3e58...` before and after, `wc -l` gives 178 and 109. The benchmark
  build was rebuilt and reinstalled from the restored source, and the last three runs (225736,
  230421, 230956) were measured on it.
- Added (not under `app/`): `benchmark/.../PlayerIdleStatesBenchmark.kt` (97 lines),
  `benchmark/.../IdleStateFrameMetric.kt` (67 lines), the `extraMetrics` parameter in
  `ScenarioSupport.kt`, a row in `scripts/perf/run-benchmark.fish`, a row and the full-run
  duration in `scripts/perf/README.md`, and the full-run duration in `docs/perf/README.md`.
- `analyze_traces.py` lists three unclassified slices from `idleStates`
  (`TextAnnotatedStringNode:measure`, `binder reply`, an AIDL server slice); not added to
  `slice_classes.py`.
- One run per arm and scenario, except `expandCollapse` (two A, two B, one C). The GPU sampler's
  one-second windows cannot separate animation from the pauses inside an iteration.
- The trace queries were ad hoc TraceProcessor scripts and are not committed. To re-count
  passes for one trace in the Perfetto UI:

```sql
SELECT df.name, COUNT(op.id) AS passes
FROM slice df
JOIN thread_track tt ON df.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
LEFT JOIN slice op ON op.track_id = df.track_id AND op.ts >= df.ts
  AND op.ts < df.ts + df.dur AND op.name GLOB '*OpsTask::onPrepare*'
WHERE p.name = 'com.v1per.synthx.benchmark' AND t.name = 'RenderThread'
  AND df.name GLOB 'DrawFrames *'
GROUP BY df.id
ORDER BY df.ts
```
