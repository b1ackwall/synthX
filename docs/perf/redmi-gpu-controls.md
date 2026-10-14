# GPU cost controls on the Redmi Note 7 Pro

Measurement-only study on the Redmi Note 7 Pro (serial , `violet`, Android 13 custom ROM,
API 33, Adreno 612 with the GL backend, fixed 60 Hz). Benchmark build `com.v1per.synthx.benchmark`
of commit `7c80d4f`, plus one temporary source change per control arm. Every run used
`scripts/perf/run-benchmark.fish  --slice-frames`, so frame timing comes from the app's
own slices (`frameOverrunMs` and deadline-missed are not available). The battery read 27.6-31.4 C
in every run, and each arm started at or below 31.0 C. Magisk shell su stayed denied, so the phone
ran unrooted.

Two kinds of statement appear. **Measured** comes from result files and traces. **Inferred** rests
on source reading or on how HWUI and Skia work, and was not tested directly.

## 1. Findings in short

1. **The expanded player's background effect is most of the per-frame GPU-side cost on this
   phone.** Measured: with the effect off, RenderThread "GPU flush" per animation frame fell by
   84-85% (6.1 to 1.0 ms in `expandCollapse`, 8.0 to 1.2 ms in `queueExpandCollapse`). Render
   passes per moving frame fell from 21 to 8-9, and cpu p50 fell by 56-58%. Frames over 16.67 ms
   fell from 78-79% to 16%. The S25 found about 20% of GPU flush for the same effect.
2. **The blur is most of that, but not all.** Measured: with only the blur off, GPU flush fell by
   76-82% and the drawn frame rate recovered to the effect-off level (about 53 fps against 46).
   But the RenderThread then waited longer for a free buffer, so `expandCollapse` cpu p50 moved
   only 5%. The full-size offscreen layer with twist and saturation still keeps the GPU behind
   (inferred from buffer back-pressure).
3. **Home's cost while the sheet moves is its blur filter, not its animation.** Measured: a
   static Home background changed `homeExpandCollapse` p50 by only 4% and GPU flush by 6%. Home
   with the blur off, animation on, cut p50 by 40% (48.2 to 28.7 ms), GPU flush by 54%, and
   passes from 33 to 21, the same as on Library. Home's 150 dp blur re-runs on every frame the
   sheet moves, even when the blobs are static.
4. **Home at rest saturates the phone.** Measured: on Home with the mini player paused, the app
   draws only 48 fps, with the RenderThread 100% busy and the main thread 95% busy. Static: 0.5 fps
   and 1%. Blur off, animation on: 60 fps, RenderThread 79%, main 30%.
5. **The RenderThread's "GPU flush" is on-CPU time.** Measured: 99% of `flush commands` is the
   Running state on big cores 6 and 7. The baseline's "buffer queue" is 99% sleeping in
   `dequeueBuffer`, waiting for a free buffer. Inferred: the GL driver's flush time also absorbs
   GPU waits. Same op counts at the same CPU clock cost about 3x more in some runs, which points
   at the GPU clock. The GPU clock cannot be read without root.
6. **`nextExpanded` is bimodal between runs of the same build.** Measured: GPU flush per drawn
   frame was either about 7 or 15-18 ms in the five baseline runs (CPU clock and op counts
   equal). Its A-B-A-B spread is therefore too wide for p50, but every effect-off or blur-off
   run beat every baseline on p90 and buffer queue.

## 2. Runs

All under `perf-output/` (gitignored). The arm letters are per control: A = unmodified source,
B = effect off, C = blur off, H = Home static, X = Home blur off.

| Control | Arm | Run | Batt start/end C | Song |
|---|---|---|---|---|
| 1 effect off | A1 / B1 / A2 / B2 | 150459 / 151349 / 152250 / 153329 | 27.6-29.3 / 29.3-29.9 | All The Stars |
| 2 blur off (first try, cut short) | A / C | 154113 / 154953 | 28.9-29.0 / 29.4 | All The Stars |
| 2 blur off | A1 / C1 / A2 / C2 | 155849 / 160738 / 161600 / 162511 | 28.5-29.3 / 29.6-30.0 | All The Stars |
| 3 Home static | A1 / H1 / A2 / H2 | 163508 / 164554 / 165823 / 170715 | 29.0-29.9 / 29.6-31.0 | All The Stars |
| 3b Home blur off (added) | A1 / X1 / A2 / X2 | 171619 / 172912 / 173932 / 174821 | 29.5-30.0 / 30.8-31.4 | see section 7 |
| restored source, check | - | 175718 | 29.8 / 30.5 | Love Is A Drug |

Prefix every run with `20261014-`. `device.json` records `commit_dirty` false for every A arm
and true for every variant arm.

## 3. Method

- **Changes (all temporary, all restored):**
  - Control 1: `renderEffect = effect` became `renderEffect = null` in
    `ui/player/PlayerAltStageBackground.kt:88`.
  - Control 2: the blur radius passed to `altStageEffect` at line 73 became `0f`, so
    `altStageChain` drops the blur and keeps twist and saturation.
  - Control 3: `|| true` was added to `motionPaused` in
    `ui/components/AmbientBlobBackground.kt:76`. The motion-policy poll still runs and the layout
    is unchanged. The blobs stay at their spawn positions under the blur.
  - Control 3b: `.blur(radius = blurRadiusDp * 0)` at line 102. With Compose 1.12's default
    rectangle edge treatment this keeps the graphics layer and clip and sets `renderEffect` to
    null (checked in `Blur.kt` from the ui-android 1.12.0 sources).
- **Control 4 (album-art glow) was skipped:** control 1 changed p50 by far more than 20%.
- **Arm order:** A-B-A-B per control. Each arm is one script invocation, which rebuilds and
  reinstalls the benchmark APK from the current source. Arms waited for the battery to read
  33 C or less; no arm needed to wait (28.6-31.0 C at start).
- **Animation-window numbers** come from `trace-frames.csv` (written by `analyze_traces.py`):
  - cpu p50/p90/p99, with the benchmark library's interpolation.
  - The share of animation frames whose cpu time is over 16.67 ms. This is how the figures in the
    brief were computed, and it reproduces them exactly from run 135701.
  - Mean self ms per animation frame of the classes RT GPU flush, buffer queue, RT draw, RT layers
    and "main: waiting on RenderThread", plus RT total and main total.
- **Ad hoc TraceProcessor scripts** (session scratchpad, not committed) added the rest:
  - **Passes:** median `OpsTask::onPrepare` count per `DrawFrame*` slice, in animation frames
    where `synthX.PlayerSheet` composed ("moving frames").
  - **Drawn fps:** animation frames that reached the RenderThread, per second of animation window.
    This is a dropped-frame proxy, because the frame timeline is unusable here.
  - **Idle states:** drawn fps, RenderThread ms per frame and busy %, and main-thread busy %
    inside each `bench.idle.<state>` window of `idleStates`, iterations 1-3.
  - **Thread state:** RenderThread scheduler state and CPU inside `flush commands` and
    `dequeueBuffer`, with time-weighted `cpufreq` of the core.
- "Beyond spread" means the difference of the arm means is larger than the larger within-arm
  range (|A1-A2| or |B1-B2|).

## 4. Results

Measured. cpu = p50 / p90 / p99 ms over animation-window frames. ">16.67" = share of those
frames. Flush, BQ (buffer queue), draw, layers and wait (main waiting on RenderThread) are mean
ms per animation frame. "fps" = drawn frames per second in animation windows.

### 4.1 Control 1: background effect off

| Scenario | Arm | cpu | >16.67 | Flush | BQ | Draw | Layers | Wait | Passes | fps |
|---|---|---|---|---|---|---|---|---|---|---|
| expandCollapse | A1 | 25.7 / 55.9 / 67.4 | 72.9% | 6.51 | 5.47 | 0.90 | 0.15 | 8.24 | 21 | 44.2 |
| | B1 | 10.9 / 27.5 / 32.7 | 22.6% | 1.03 | 2.55 | 0.79 | 0.10 | 1.34 | 9 | 53.3 |
| | A2 | 25.8 / 54.8 / 67.8 | 84.8% | 5.71 | 7.13 | 0.82 | 0.14 | 7.52 | 21 | 43.9 |
| | B2 | 10.7 / 15.6 / 33.3 | 9.3% | 0.97 | 1.12 | 0.76 | 0.09 | 0.93 | 8 | 51.2 |
| queueExpandCollapse | A1 | 25.8 / 66.4 / 75.9 | 85.1% | 8.13 | 6.71 | 0.90 | 0.31 | 9.36 | 21 | 40.9 |
| | B1 | 11.5 / 27.8 / 31.8 | 22.2% | 1.30 | 2.29 | 0.79 | 0.34 | 1.42 | 9 | 52.6 |
| | A2 | 25.8 / 65.7 / 75.6 | 71.6% | 7.97 | 5.21 | 0.84 | 0.30 | 8.88 | 21 | 40.9 |
| | B2 | 11.3 / 17.5 / 31.2 | 10.6% | 1.15 | 1.05 | 0.72 | 0.31 | 0.95 | 9 | 51.3 |
| nextExpanded | A1 | 43.7 / 52.4 / 66.1 | 82.3% | 13.35 | 5.25 | 1.11 | 0.06 | 13.45 | 21 | 32.8 |
| | B1 | 10.3 / 19.1 / 29.2 | 11.3% | 1.48 | 2.18 | 1.20 | 0.00 | 1.02 | 8 | 37.8 |
| | A2 | 9.6 / 36.9 / 47.0 | 38.6% | 3.65 | 4.80 | 0.94 | 0.04 | 3.75 | 21 | 24.6 |
| | B2 | 2.6 / 11.0 / 24.7 | 4.5% | 1.21 | 0.85 | 1.05 | 0.00 | 0.82 | 8 | 25.2 |

**Beyond the A-B-A-B spread:**
- `expandCollapse`: every metric above, including RT draw (-10%) and RT layers (-34%).
- `queueExpandCollapse`: every metric except RT layers (+5%, within spread).
- `nextExpanded`: only p90, p99, share over budget, buffer queue, RT layers and passes. p50, GPU
  flush, wait, RT total and main are within the spread, because the two baselines disagree
  (section 6.2).

### 4.2 Control 2: blur off (twist and saturation kept)

| Scenario | Arm | cpu | >16.67 | Flush | BQ | Draw | Layers | Wait | Passes | fps |
|---|---|---|---|---|---|---|---|---|---|---|
| expandCollapse | A1 | 25.7 / 51.3 / 66.0 | 86.8% | 5.62 | 7.22 | 0.84 | 0.14 | 7.62 | 21 | 46.3 |
| | C1 | 24.5 / 29.4 / 37.1 | 81.0% | 1.39 | 8.21 | 0.85 | 0.15 | 3.62 | 11 | 53.0 |
| | A2 | 25.7 / 53.9 / 67.0 | 83.9% | 5.65 | 7.07 | 0.85 | 0.15 | 7.89 | 21 | 45.5 |
| | C2 | 24.5 / 29.5 / 38.0 | 81.9% | 1.36 | 8.30 | 0.84 | 0.14 | 3.56 | 11 | 52.9 |
| queueExpandCollapse | A1 | 25.7 / 66.0 / 71.2 | 84.1% | 7.92 | 6.82 | 0.85 | 0.30 | 8.93 | 21 | 41.3 |
| | C1 | 15.0 / 31.4 / 45.4 | 46.1% | 1.41 | 3.76 | 0.76 | 0.32 | 2.95 | 11 | 52.1 |
| | A2 | 25.7 / 66.0 / 73.3 | 84.1% | 7.80 | 7.02 | 0.85 | 0.30 | 9.22 | 21 | 41.1 |
| | C2 | 23.7 / 31.6 / 45.5 | 67.2% | 1.49 | 6.29 | 0.82 | 0.35 | 3.81 | 11 | 51.9 |
| nextExpanded | A1 | 13.5 / 31.8 / 36.6 | 33.6% | 5.42 | 3.94 | 1.39 | 0.04 | 3.23 | 21 | 31.0 |
| | C1 | 9.7 / 27.0 / 33.6 | 20.4% | 1.53 | 3.48 | 1.25 | 0.03 | 1.91 | 10 | 36.4 |
| | A2 | 39.6 / 50.9 / 58.4 | 77.0% | 13.34 | 5.16 | 1.13 | 0.05 | 11.44 | 22 | 29.6 |
| | C2 | 8.4 / 14.0 / 29.1 | 9.8% | 1.43 | 1.51 | 1.15 | 0.04 | 0.86 | 10 | 31.7 |

**Beyond the spread:**
- `expandCollapse`:
  - p50 -5%, p90 -44%, p99 -43% and share -4 points.
  - Flush -76%, wait -54%, main -32%, passes 21 to 11.
  - Buffer queue *rose* 16%.
  - Not beyond the spread: RT draw and RT layers.
- `queueExpandCollapse`:
  - Beyond the spread: p90 -52%, p99 -37%, share -27 points, flush -82%, wait -63%,
    passes 21 to 11.
  - Not beyond the spread: p50 (-25%) and buffer queue, because C1 and C2 differ (15.0 vs
    23.7 ms).
- `nextExpanded`: only p90, buffer queue, RT layers and passes (bimodal baselines again).

The first, cut-short attempt (154113 / 154953) agrees with C1 and C2 for the two scenarios it
finished: `expandCollapse` 24.2 / 33.8 / 39.4, flush 1.34, 11 passes. Its data is not used in
the means.

### 4.3 Control 3: Home background static

| Scenario | Arm | cpu | >16.67 | Flush | BQ | Draw | Layers | Wait | Passes | fps |
|---|---|---|---|---|---|---|---|---|---|---|
| homeExpandCollapse | A1 | 48.7 / 68.9 / 79.7 | 89.7% | 14.17 | 4.46 | 0.64 | 0.26 | 17.73 | 33 | 36.6 |
| | H1 | 46.9 / 68.0 / 77.7 | 86.9% | 13.34 | 4.92 | 0.60 | 0.15 | 16.22 | 32 | 34.8 |
| | A2 | 47.9 / 68.2 / 77.6 | 88.8% | 13.96 | 4.40 | 0.63 | 0.25 | 17.62 | 33 | 36.7 |
| | H2 | 45.8 / 68.5 / 76.2 | 85.8% | 13.12 | 4.57 | 0.61 | 0.15 | 15.81 | 32 | 35.3 |

**Beyond the spread:**
- p50 -4%, share -3 points, flush -6% (-0.83 ms), wait -9%, RT layers -42%, RT draw -4%.
- Drawn fps fell 1.6.
- Not beyond the spread: p90, p99, buffer queue.

The Home layer's re-render (`drawLayer [graphicsLayer] 1080 x 1823`) went from 149 to 2 per
trace, but passes per moving frame stayed at 32-33.

`idleStates`, iterations 1-3, the Home state (mini player paused on Home), both runs per arm:

| Arm | Drawn fps | RT ms per frame | RT busy | Main busy |
|---|---|---|---|---|
| A (163508, 165823) | 48.2 / 48.4 | 20.8 / 20.6 | 100% / 100% | 95% / 95% |
| H (164554, 170715) | 0.5 / 0.5 | 21.1 / 22.1 | 1% / 1% | 1% / 1% |

The other idle states did not change beyond run-to-run noise. For example, expanded paused drew
15 fps at 13.0-15.5 ms of RenderThread per redraw and 19-23% busy in every arm.

### 4.4 Control 3b: Home blur off, animation kept (added, see section 7)

| Scenario | Arm | cpu | >16.67 | Flush | BQ | Draw | Layers | Wait | Passes | fps |
|---|---|---|---|---|---|---|---|---|---|---|
| homeExpandCollapse | A1 | 48.0 / 68.5 / 78.5 | 90.0% | 13.98 | 4.33 | 0.63 | 0.25 | 17.40 | 33 | 37.4 |
| | X1 | 28.7 / 61.8 / 77.6 | 75.1% | 6.45 | 5.60 | 0.69 | 0.13 | 9.69 | 21 | 45.6 |
| | A2 | 48.4 / 68.6 / 76.9 | 86.7% | 13.71 | 4.08 | 0.62 | 0.25 | 16.97 | 33 | 36.4 |
| | X2 | 28.8 / 58.7 / 77.9 | 71.2% | 6.36 | 5.35 | 0.69 | 0.13 | 9.43 | 21 | 46.1 |

**Beyond the spread:**
- p50 -40%, p90 -12%, share -15 points.
- Flush -54% (-7.4 ms), wait -44%, main -34%, passes 33 to 21.
- RT layers -48%, buffer queue +30%.
- Not beyond the spread: p99.

Idle Home:

| Arm | Drawn fps | RT ms per frame | RT busy | Main busy |
|---|---|---|---|---|
| A | 48.5 / 48.2 | 20.6 / 20.6 | 100% / 100% | 95% / 95% |
| X | 60.1 / 59.9 | 13.4 / 13.0 | 80% / 78% | 31% / 30% |

### 4.5 Restored-source check

Run 175718, after every file was restored:
- `expandCollapse`: 25.8 / 55.2 / 66.7, 80.8%, flush 5.80, 44.1 fps.
- `homeExpandCollapse`: 47.6 / 68.6 / 78.5, 88.4%, flush 13.63, 36.8 fps.

Both are inside the baseline ranges above.

## 5. Measured share of each effect on this phone

| Effect | Where | GPU flush per animation frame | Passes per moving frame | cpu p50 | Over 16.67 ms | Drawn fps |
|---|---|---|---|---|---|---|
| Background effect (blur + twist + saturation + its offscreen layer) | expandCollapse | -5.1 ms of 6.1 (84%) | 12.5 of 21 | -58% | 79% to 16% | 44 to 52 |
| | queueExpandCollapse | -6.8 ms of 8.1 (85%) | 12 of 21 | -56% | 78% to 16% | 41 to 52 |
| Blur alone | expandCollapse | -4.3 ms of 5.6 (76%) | 10 of 21 | -5% | 85% to 81% | 46 to 53 |
| | queueExpandCollapse | -6.4 ms of 7.9 (82%) | 10 of 21 | -25% (within spread) | 84% to 57% | 41 to 52 |
| Home blob animation | homeExpandCollapse | -0.8 ms of 14.1 (6%) | 1 of 33 | -4% | 89% to 86% | 37 to 35 |
| | Home at rest | n/a | n/a | n/a | n/a | RT busy 100% to 1% |
| Home 150 dp blur | homeExpandCollapse | -7.4 ms of 13.8 (54%) | 12 of 33 | -40% | 88% to 73% | 37 to 46 |
| | Home at rest | n/a | n/a | n/a | n/a | 48 to 60 fps, RT busy 100% to 79% |
| Album-art glow blur | not measured (control 4 skipped by rule) | | | | | |

Twist, saturation and the offscreen layer together, as the difference between controls 1 and 2
(inferred from two different controls, not one): about 0.3 ms of flush and 2-3 passes. The cost
that matters is GPU back-pressure. With the blur off, sleeping in `dequeueBuffer` went *up*
(below), and only removing the whole effect brought it down.

## 6. Why: thread state and noise

### 6.1 Where the time actually goes (measured, `expandCollapse` iterations 3-6)

| Run | Arm | `flush commands`, Running | `dequeueBuffer`, Sleeping |
|---|---|---|---|
| 152250 | A | 2235 ms | 5143 ms |
| 153329 | B (effect off) | 501 ms | 16 ms |
| 155849 / 161600 | A | 2302 / 2249 ms | 5068 / 5190 ms |
| 160738 / 162511 | C (blur off) | 749 / 747 ms | 6568 / 6585 ms |

- **Flush is on-CPU:** about 99% Running, always on big cores 6 and 7. The time-weighted clock
  was 1.6-1.9 GHz in every arm, so the arms are comparable on CPU clock.
- **The baseline's buffer queue is a wait:** the RenderThread sleeps until the compositor
  returns a buffer, which happens when the GPU and compositor are behind.
- **Where the blur's cost lands:** removing only the blur removes on-CPU flush work, but the
  sleep grows, so the GPU stays the limit. Removing the whole effect removes both.
- **The 15 fps idle redraw:** 13-17 ms of RenderThread per redraw, 19-28% busy, in every
  baseline `idleStates` run.
- **GPU-completion wait:** "waiting for GPU completion" averaged 16.3-17.5 ms per frame with the
  effect and 13.1-14.5 ms without, in `expandCollapse` and `queueExpandCollapse`. It includes queueing behind earlier frames, so it was not used
  as a GPU time.
- **Unmeasurable:** GPU busy and clock (`/sys/class/kgsl/kgsl-3d0/gpubusy`, `clock_mhz` and so on
  return Permission denied unrooted), and there is no GPU frequency counter in the traces.

### 6.2 The `nextExpanded` spread (measured; cause inferred)

**What was measured:**
- Across the five baselines, GPU flush per *drawn* frame was 15.8 (150459), 7.0 (152250),
  18.2 (154113), 7.5 (155849) and 17.1 ms (161600). Idle frames show the same split.
- Effect-off and blur-off runs: 2.0-2.4 ms.
- A slow and a fast baseline trace have the same op counts per frame (about 64 `FillRectOp`
  and 21 passes) and the same time-weighted CPU clock. But each `FillRectOp` takes 0.17 against
  0.05 ms, and each flush call 12.4 against 4.5-4.9 ms.

**Inferred:** the GL driver blocks inside those ops when the GPU is behind, and the GPU's clock
state differs between runs. That could not be checked unrooted. `expandCollapse` and the queue
scenarios did not show this split.

### 6.3 Other things the traces show (measured)

- **Track-change art decode** runs off the main thread:
  - `decodeBitmap` on pool threads, 7-13 ms each, plus one or two 35-63 ms decodes on
    `DefaultDispatch` per run.
  - `synthX.PlayerArtDecode` takes 6-13 ms per change.
  - Texture uploads are small: 41-59 per run, at most 176 x 176, 5-6 ms per run.
  - The per-frame cost of `nextExpanded` is the background effect: flush per drawn frame drops
    to about 2 ms without it. `nextQueue` was not run here. Its earlier baseline (135701) shows
    the same flush-dominated breakdown (11.9 ms per animation frame), so it very likely has the
    same cause (inferred).
- **Large alpha layers** are small here:
  - RT layers is 0.13-0.15 ms per animation frame on Library and 0.30-0.35 ms in queue mode.
    The queue content uses an Offscreen layer while its alpha is above 0
    (`PlayerSkeletonQueueContent.kt:270-308`).
  - The Home layer adds 0.10-0.12 ms (removed in both Home controls).

## 7. Deviations, surprises and failures

- **Control 3b was added.** It was not in the brief. Control 3 showed Home's sheet-motion cost
  does not come from the animation, so I priced the Home blur directly with a fourth A-B-A-B. It
  is the same kind of temporary draw-phase change, restored the same way.
- **The phone dropped off USB** during the first blur-off arm (154953, `nextExpanded` failed).
  Work stopped. After reconnection, the host adb server had to be restarted
  (`adb kill-server; adb start-server`) before  was listed. Control 2 was then rerun as a
  full A-B-A-B, and the cut-short pair is kept only as a cross-check.
- **The song changed twice:**
  - The interrupted `nextExpanded` left the player two tracks ahead. I sent two Previous media
    keys (`cmd media_session dispatch previous`), after confirming that synthX bench was the
    only media session and the media-button owner. That restored "All The Stars" before control 2.
  - `idleStates` plays about 10 s per iteration and never resets the position. After eight
    `idleStates` runs, "All The Stars" ended during iteration 3 of run 171619 (the only
    `synthX.PlayerArtDecode` in any `idleStates` trace). Runs 172912, 173932, 174821 and 175718
    therefore played "Love Is A Drug". Control 3b's A1 (All The Stars) and A2 (Love Is A Drug)
    agree within 0.4 ms p50 and 0.3 ms flush, and the restored check matches earlier baselines,
    so the art did not measurably change these numbers. The X arms are both on the second song.
  - At the end the phone was put back on "All The Stars" with two Previous keys.
- **Static Home lowered drawn fps slightly** (36.7 to 35.0, beyond the spread) while lowering
  every cost. Not explained.
- **cpu over 16.67 ms is a poor jank measure here when the time is buffer waiting.** The blur-off
  arm keeps 81% of `expandCollapse` frames over budget but draws as many frames as effect off.
- **Not measured:**
  - GPU busy time and clocks (unrooted).
  - `frameOverrunMs` (frame timeline unusable on this phone).
  - The album-art glow (control 4 skipped by rule).
  - Effect-off idle states (control 1 did not include `idleStates`).
  - The expanded player over Home at rest.

## 8. Options (proposed, not implemented)

Ranked by measured share times how often it runs, against visual change and risk. All are
draw-phase changes. None touches `anchoredDraggable`, gesture code, `PlayerSheetMotion.kt` or a
locked motion constant.

1. **Expanded background: run the filter at reduced resolution.**
   - **Change:** draw the base layer and copies into a quarter-size (or eighth-size) layer, scale
     the blur radius and the twist uniforms by the same factor, then scale the filtered result up.
   - **Why first:** control 2 shows that removing the blur alone leaves the full-size layer with
     twist and saturation saturating the GPU, so the pixel count must fall too. At quarter size
     the twist and saturation touch 1/16 of the pixels.
   - **Expected (inferred):** close to control 1's numbers, flush about 1-1.5 ms and drawn fps
     about 52.
   - **Visual:** a 120 dp blur hides the upscale. The twist's edge may soften slightly; compare
     screenshots.
   - **Files:** `PlayerAltStageBackground.kt`, `PlayerAltStageEffects.kt`.
   - **Risk:** low.
   - **Measure:** `expandCollapse`, `queueExpandCollapse`, `nextExpanded` A-B-A-B. Use the pass
     count, flush, `dequeueBuffer` sleep and drawn fps, plus `idleStates` expanded RT ms per
     redraw (13-17 ms today).
2. **Expanded background: filter once per motion step, not once per frame.**
   - **Today:** the filter runs in every moving frame (21 passes in every one), although the
     background only changes 15 times a second.
   - **Option a, cached bitmap:** draw the filtered result into a cached bitmap per motion step
     and per art change, and draw that texture while the sheet moves.
   - **Option b, fixed-bounds outer layer:** put an outer, fixed-bounds
     `CompositingStrategy.Offscreen` layer around the filtered node. Whether HWUI then reuses the
     filtered texture is inferred, not verified. Control 3 suggests this HWUI re-applies a
     RenderEffect whenever its parent redraws, even with unchanged content: the static Home layer
     stopped re-rendering, but its passes stayed. So the outer layer may need to be the cache
     itself; test it.
   - **Expected:** passes in moving frames between motion steps fall to about 9, and the filter
     runs about 15 times a second instead of 45-55.
   - **Cost:** one more 1080 x 2274 layer (about 10 MB).
   - **Files:** `PlayerAltStageBackground.kt`.
   - **Risk:** low to medium. Read the motion frame only in the draw phase, as today.
   - **Measure:** passes per moving frame, flush, drawn fps.
3. **Expanded background: static while the sheet moves.**
   - **Change:** pause the 15 fps motion while progress is strictly between the anchors.
   - **Combined with 2:** the filter would not run during a drag or settle at all.
   - **Gesture-rule risk:** derive the pause from progress with `derivedStateOf`, so composition
     only sees the threshold crossing, or read it in the draw phase. Never touch the draggable's
     `enabled`. Keep the loop keyed reactively (`LaunchedEffect(paused)`, as
     `rememberAltStageMotion` already is), not timer-based.
   - **Visual:** the slow spin stops during motion, which is hard to see at that speed.
   - **Measure:** as 2, plus `slowDrag` and `fling` (not run here).
4. **Home: stop re-running the 150 dp blur on frames where the blobs did not move.**
   - **Measured size:** 54% of flush, 12 passes and 9 drawn fps in `homeExpandCollapse`. Pausing
     the blobs alone buys only 6%.
   - **Change:** cache the blurred result (as in 2) and pause the blobs while the sheet is above
     progress 0. Today they pause only at 0.999 or more (`MainScaffold.kt:380`).
   - **Gesture-rule risk:** the same `derivedStateOf` threshold rule as 3.
   - **Files:** `AmbientBlobBackground.kt`, `MainScaffold.kt`.
   - **Measure:** `homeExpandCollapse` passes (33 today, 21 is the floor) and flush.
5. **Home at rest: cheaper drawing or a lower rate.**
   - **Today:** Home holds 48 fps with the RenderThread 100% busy and the main thread 95% busy.
   - **a. Frame cap:** cap the blob motion at 20-30 fps, as the player's 15 fps cap does
     (`AltStageFrameAccumulator`). Inferred: RenderThread busy roughly halves at 30 fps. The
     blobs move at 0.08 rad/s, so 30 fps is likely invisible.
   - **b. Blur at low resolution:** draw the blobs into a small layer and blur at low resolution.
     The upper bound measured with the blur off is 60 fps at 79% RenderThread.
   - **c. No RenderEffect:** widen the radial-gradient falloff and drop the RenderEffect. Visual
     risk: the wobbling path edges show; needs a design check.
   - **Files:** `AmbientBlobBackground.kt`, `ProductionAmbientBackgroundStyle.kt`.
   - **Measure:** the `idleStates` Home row.
6. **Lower priority:**
   - **Queue Offscreen alpha layer:** about 0.15 ms per frame.
   - **Album-art glow:** not measured here; small on the S25 (`layer-discovery.md`).
   - **Track-change decode:** off the main thread; frame cost is the background effect, which 1-3
     address.

Measure every option with A-B-A-B and `--slice-frames`. Judge it by passes, flush, `dequeueBuffer`
sleep and drawn fps, not only by the share of cpu time over 16.67 ms (section 7).

## 9. Restoration

- **Touched under `app/`:** `ui/player/PlayerAltStageBackground.kt` and
  `ui/components/AmbientBlobBackground.kt`, temporarily.
  `ui/player/PlayerAltStageEffects.kt` and `ui/player/PlayerAlbumArtWithGlow.kt` were copied but
  never edited.
- **Proof:** all four were copied to the session scratchpad before the first edit and copied back
  after each control. `diff` against the copies prints nothing for any of them, and
  `sha256sum -c` gives OK for all four:
  - `442a8776...` for `PlayerAltStageBackground.kt`
  - `01ee3e58...` for `PlayerAltStageEffects.kt`
  - `3c23c3d0...` for `AmbientBlobBackground.kt`
  - `54754d49...` for `PlayerAlbumArtWithGlow.kt`
- **Line counts:** 178, 109, 419 and 81.
- **Rebuilt from restored source:** run 175718 rebuilt and reinstalled the benchmark APK, so the
  installed build matches the source.
- **Pass count query** (Perfetto UI): `idle-and-gpu-discovery.md` section 6, with the process
  name unchanged and `DrawFrame*` in place of `DrawFrames *`.
