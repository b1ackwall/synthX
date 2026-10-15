# Expanded player background at quarter resolution

Single-variable experiment: the expanded player's background (art layers, then blur, twist and
saturation) is drawn and filtered on a layer a quarter of the width and height, then scaled back
up. Measured A-B-A-B on the Redmi Note 7 Pro (serial , Adreno 612, API 33, 60 Hz), then
once on the Galaxy S25 Ultra at 120 Hz against baseline run `20261014-145014`. Follows
[redmi-gpu-controls.md](redmi-gpu-controls.md) section 8, option 1.

**Verdict: keep.** On the Redmi every measured cost of `expandCollapse` and
`queueExpandCollapse` fell beyond the A-B-A-B spread: cpu p50 halved, RenderThread GPU flush fell
77-81%, sleeping in `dequeueBuffer` fell 77-84%, drawn fps rose from 41-43 to 51. That is close to
removing the effect entirely. Screenshots and screen recordings on the Redmi show no visible
difference. On the S25 at 120 Hz nothing regressed. The single-run comparison with the baseline is
confounded by a 2.3x CPU clock difference in `expandCollapse`; in the clock-matched queue
scenario the gain is small (flush -9%). The eye check on the S25 (section 6) is still open.

## 1. Change

- `ui/player/PlayerAltStageStyle.kt`: new `FilterResolutionDivisor = 4`. Set it to 2 for the
  quality fallback, or to 1 for full resolution. 1 renders as before, plus one transform-only
  layer.
- `ui/player/PlayerAltStageBackground.kt`:
  - A new layout step, `ReducedResolution`, sits between the outer node and the effect's
    `graphicsLayer`. It measures the rest of the chain at the full size divided by 4 per side,
    rounded up (`altStageReducedSize`; 1080 x 2274 becomes 270 x 569). It keeps the full size
    for itself and places the small node in a layer scaled by full / reduced per axis from the
    top-left corner, so the result covers the original bounds exactly.
  - The draw code is unchanged. It already works in fractions of the layer size, so the art
    layers come out the same at the smaller size.
  - The effect is built for the reduced layer size, which scales the twist center, radius and
    clamp size by the same factor. The blur gets `density / FilterResolutionDivisor`, so 120 dp
    stays 120 dp on screen.
- Unchanged: `PlayerAltStageEffects.kt`, the scrim (still on the full-size outer node), the
  15 fps motion cap, which composables read progress, the bounds and bottom anchoring, the
  effect node's `clip = true`, and the sheet's rounded-corner clip.
- **Why it really filters at the reduced size** (AOSP 13 `RenderNodeDrawable::drawContent`):
  - A RenderEffect runs on the node's own layer. That layer is sized from the node's bounds,
    not its transform.
  - The filter uses the canvas's local clip bounds.
  - The filtered image is then drawn through the node's matrix with linear sampling, so a
    scale on the node or a parent upscales the filtered image.
- **Trace check:** the background's layer slice is `drawLayer [graphicsLayer] 1080.0 x 2274.0`
  in every A trace and `270.0 x 569.0` in every B trace, in all three scenarios. The other
  1080 x 2274 layer that remains in queue mode is the queue content's own offscreen alpha
  layer.
- **Tests:** new `PlayerAltStageReducedSizeTest` (6 tests). Mutation check: with floor division
  in place of round-up, 2 tests fail. All `ui.player` unit tests pass.

## 2. Method (Redmi)

- **Command:** `scripts/perf/run-benchmark.fish  --slice-frames --scenario
  expandCollapse,queueExpandCollapse,nextExpanded`.
- **Arms:** one script invocation each, in the order A1, B1, A2, B2. Each invocation rebuilds
  and reinstalls the benchmark APK. A = the two files copied back from a scratchpad copy of
  the original source (sha256 `442a8776...` and `56bede54...`, the same originals as in
  `redmi-gpu-controls.md`). B = this change.
- **Phone state:**
  - The battery read 27.4-28.7 C throughout. No arm needed to wait for the 33 C limit.
  - Magisk shell su was denied, so the phone ran unrooted.
  - Same song in every arm ("Footprints (Mokita Remix)").
- **Metrics:** computed exactly as in `redmi-gpu-controls.md` section 3. That covers
  animation-window cpu percentiles and the share over 16.67 ms (`trace-frames.csv`), passes
  (median `OpsTask::onPrepare` per moving frame), drawn fps and "beyond spread" (the
  difference of arm means is larger than the larger within-arm range).
- **New metric, `dequeueBuffer` sleep:** RenderThread time in states S or D inside the
  outermost `dequeueBuffer` slice, summed over animation frames and divided by the number of
  animation frames. libgui nests a second `dequeueBuffer` inside the first; counting both
  would double the time.
- **Runs:** `20261014-183141` (A1), `-184040` (B1), `-185155` (A2), `-190203` (B2).
  `device.json` records `commit_dirty` true for all four, because the new test file and this
  doc are uncommitted.

## 3. Redmi results

Measured. Times are ms; "anim frame" = every frame in an animation window, "drawn frame" = those
that reached the RenderThread.

### expandCollapse

| Metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | Max arm range | Beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 25.5 | 10.6 | 25.9 | 10.4 | 25.7 | 10.5 | -15.2 (-59%) | 0.4 | yes |
| cpu p90 ms | 55.4 | 25.7 | 55.2 | 23.9 | 55.3 | 24.8 | -30.5 (-55%) | 1.8 | yes |
| cpu p99 ms | 71.2 | 32.8 | 69.4 | 33.0 | 70.3 | 32.9 | -37.4 (-53%) | 1.7 | yes |
| over 16.67 ms, % | 81.3 | 17.9 | 84.6 | 13.7 | 82.9 | 15.8 | -67.1 (-81%) | 4.2 | yes |
| passes per moving frame | 21 | 18 | 21 | 18 | 21 | 18 | -3 (-14%) | 0 | yes |
| RT GPU flush, ms/anim frame | 5.79 | 1.30 | 5.53 | 1.30 | 5.66 | 1.30 | -4.36 (-77%) | 0.26 | yes |
| dequeueBuffer sleep, ms/anim frame | 6.26 | 1.72 | 6.87 | 1.33 | 6.57 | 1.52 | -5.04 (-77%) | 0.61 | yes |
| drawn fps | 43.3 | 51.6 | 43.5 | 51.4 | 43.4 | 51.5 | +8.1 (+19%) | 0.2 | yes |

### queueExpandCollapse

| Metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | Max arm range | Beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 25.9 | 12.6 | 25.8 | 12.4 | 25.9 | 12.5 | -13.3 (-52%) | 0.2 | yes |
| cpu p90 ms | 65.8 | 24.3 | 66.1 | 24.3 | 66.0 | 24.3 | -41.6 (-63%) | 0.3 | yes |
| cpu p99 ms | 74.5 | 30.2 | 76.6 | 30.0 | 75.6 | 30.1 | -45.5 (-60%) | 2.1 | yes |
| over 16.67 ms, % | 87.6 | 13.8 | 86.5 | 13.6 | 87.0 | 13.7 | -73.3 (-84%) | 1.1 | yes |
| passes per moving frame | 21 | 18 | 21 | 18 | 21 | 18 | -3 (-14%) | 0 | yes |
| RT GPU flush, ms/anim frame | 7.93 | 1.52 | 7.97 | 1.52 | 7.95 | 1.52 | -6.43 (-81%) | 0.03 | yes |
| dequeueBuffer sleep, ms/anim frame | 6.81 | 1.01 | 6.65 | 1.10 | 6.73 | 1.06 | -5.68 (-84%) | 0.16 | yes |
| drawn fps | 40.9 | 51.2 | 40.3 | 51.2 | 40.6 | 51.2 | +10.6 (+26%) | 0.6 | yes |

### nextExpanded (bimodal, read with care)

| Metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | Max arm range | Beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 4.2 | 1.8 | 21.1 | 1.3 | 12.6 | 1.5 | -11.1 (-88%) | 16.9 | no |
| cpu p90 ms | 34.0 | 9.9 | 48.2 | 13.2 | 41.1 | 11.6 | -29.6 (-72%) | 14.2 | yes |
| cpu p99 ms | 47.7 | 25.4 | 55.8 | 31.4 | 51.8 | 28.4 | -23.4 (-45%) | 8.1 | yes |
| over 16.67 ms, % | 32.1 | 5.7 | 52.8 | 8.5 | 42.4 | 7.1 | -35.4 (-83%) | 20.7 | yes |
| passes per moving frame | 21 | 17 | 21 | 17 | 21 | 17 | -4 (-19%) | 0 | yes |
| RT GPU flush, ms/anim frame | 3.45 | 1.50 | 10.55 | 1.76 | 7.00 | 1.63 | -5.37 (-77%) | 7.10 | no |
| RT GPU flush, ms/drawn frame | 6.51 | 2.94 | 18.31 | 3.52 | 12.41 | 3.23 | -9.18 (-74%) | 11.80 | no |
| dequeueBuffer sleep, ms/anim frame | 1.95 | 0.32 | 2.17 | 0.56 | 2.06 | 0.44 | -1.62 (-79%) | 0.24 | yes |
| drawn fps | 25.5 | 24.9 | 24.7 | 24.3 | 25.1 | 24.6 | -0.5 (-2%) | 0.8 | no |

- **The two A runs fell into different modes.** A1 is the fast mode (6.5 ms flush per drawn
  frame) and A2 the slow one (18.3 ms), as described in `redmi-gpu-controls.md` 6.2. So p50
  and flush are not beyond the spread.
- **Still, every B run beat even the fast A run** on p50 (1.3-1.8 against 4.2), flush per
  drawn frame (2.9-3.5 against 6.5), p90, p99, over-budget share, `dequeueBuffer` sleep and
  passes.
- **Drawn fps is unchanged**, at about 25. This scenario's animation windows are mostly the
  wait for the art change, not continuous motion.

**Expanded at rest** (measured: the 15 fps background redraws outside the animation windows in
`nextExpanded`; mean RenderThread ms and GPU flush ms per drawn idle frame):

| | A1 | B1 | A2 | B2 |
|---|---|---|---|---|
| RenderThread ms per redraw | 11.1 | 8.2 | 17.4 | 7.6 |
| GPU flush ms per redraw | 7.5 | 3.1 | 15.7 | 3.3 |

**Anomaly, does not affect the tables:** in B1 `nextExpanded`, iteration 7's trace started
67 s before its measure block (the harness's Perfetto start script returned late), and
iteration 6's trace ran about 5 s longer than usual. The measure block itself took 1.6 s with a normal 15 animation
frames. The extra frames are idle expanded-at-rest redraws, so `summarize.py` for that run
shows frameCount up to 556 for that iteration. The animation-window numbers above are not
affected.

## 4. Against the earlier effect-off control (inferred: different session and song)

| expandCollapse | p50 | over 16.67 ms | flush | passes | drawn fps |
|---|---|---|---|---|---|
| Effect on (A, this study) | 25.7 | 82.9% | 5.66 | 21 | 43.4 |
| Quarter resolution (B, this study) | 10.5 | 15.8% | 1.30 | 18 | 51.5 |
| Effect off (`redmi-gpu-controls.md` 4.1, B mean) | 10.8 | 16.0% | 1.00 | 8-9 | 52.3 |

- **Close to effect off:** quarter resolution recovers about 93% of effect-off's flush saving
  (4.36 of 4.66 ms) and all of its p50 and drawn-fps gains.
- **Passes barely move (21 to 18):** the filter still runs every stage (layer, blur steps,
  twist, saturation, composite), and only the blur needs fewer downsample steps. What shrinks
  is the size of each pass (1/16 of the pixels), and that is where the time was.

## 5. Galaxy S25 Ultra at 120 Hz

**Setup:**
- Serial , Android 17, 1440 x 3120. Motion smoothness Adaptive
  (`refresh_rate_mode` 1).
- The vsync period was 8.32 ms in every scenario of both runs, so both ran at 120 Hz.
  `device.json` recorded 30 Hz for the baseline (an idle sample) and 120 Hz for B. That is
  why `compare.py` needed `--force`, and why its "frames over device budget" rows show
  REGRESSED (the baseline's budget was computed at 30 Hz). Ignore those rows.
- **Runs:** baseline `20261014-145014` (commit `7c80d4f`, clean, battery 24.4 to 29.0 C) and B
  `20261014-191835` (this change, battery 23.1 to 26.1 C), one run each, default frame
  timeline.
- **Trace check:** the background layer is `1440.0 x 3064.0` in the baseline and
  `360.0 x 766.0` in B, in all three scenarios.

Measured, animation windows (`trace-frames.csv`). "Clock" = time-weighted CPU clock of the core
running the RenderThread inside `flush commands`, iterations 3-6.

| Scenario | Run | cpu p50 / p90 / p99 ms | Over 8.33 ms | GPU flush ms/anim frame | Passes/moving frame | Clock MHz |
|---|---|---|---|---|---|---|
| expandCollapse | baseline | 4.79 / 7.75 / 10.69 | 6.2% | 1.59 | 18 | 1224 |
| | B | 3.10 / 6.21 / 8.89 | 1.8% | 1.03 | 16 | 2854 |
| queueExpandCollapse | baseline | 5.89 / 8.41 / 11.90 | 11.0% | 1.93 | 19 | 1143 |
| | B | 5.74 / 8.23 / 10.95 | 9.2% | 1.85 | 16 | 1182 |
| nextExpanded | baseline | 1.46 / 4.65 / 7.29 | 0.8% | 1.05 | 1 | 966 |
| | B | 1.31 / 3.45 / 7.36 | 0.0% | 0.80 | 1 | 1089 |

`compare.py` (whole-run `frameDurationCpuMs`, idle frames included):
- **expandCollapse:** p50/p90/p99 4.78/7.74/10.62 to 3.02/6.14/8.85; over 8.33 ms 6.1% to 1.7%;
  deadline missed 51% to 18%.
- **queueExpandCollapse:** 6.13/8.50/12.05 to 5.94/8.32/10.98; over 8.33 ms 12.0% to 9.9%;
  frameOverrunMs p90 5.60 to 3.96 ms; deadline missed 81% to 80%.
- **nextExpanded:** 3.70/6.48/8.46 to 3.37/6.14/7.80; over 8.33 ms 1.5% to 0.2%.
- Nothing regressed apart from the 30 Hz budget artifact above.

**How to read it:**
- **expandCollapse is confounded.** The CPU ran 2.3 times faster in the B run, both on the
  RenderThread (1224 to 2854 MHz) and on the main thread during composition (1261 to
  2999 MHz). Main-thread composition sections, which this change does not touch, fell 37%
  with it. Most of this scenario's gain is therefore the clock, not the change.
- **queueExpandCollapse ran at the same clock** (1143 and 1182 MHz). Here B cut flush per call
  by 9% (1.60 to 1.46 ms), the share over 8.33 ms by about 2 points, and p99 by 8%; p50 and p90
  moved only 2-3%.
  - Queue mode keeps a second full-size layer: the queue content's offscreen alpha layer,
    1440 x 3064, drawn 170 times per two traces in both runs.
  - That fits the earlier S25 finding that the background effect is only about 20% of flush
    on this phone.
- **nextExpanded:** the clock was 13% higher in B, and the 10% lower flush per call is about
  what the clock explains. Inconclusive.
- **No 120 Hz regression is visible.** A same-session A-B on the S25 is needed for a real
  number.

## 6. Visual check, risks and manual check list

**Measured on the Redmi:**
- **Screenshots, expanded at rest:** A2 and B2 on the same song look the same at phone scale.
  Crops at full resolution show smooth gradients in both, with no blocks or seams at the left
  edge or the bottom band.
- **Screen recordings (720 x 1560) of the expand and collapse:** match frame for frame at
  thumbnail scale. Mid-morph frames show the rounded top corners clipped cleanly in both.
- **Collapsed mini bar:** looks the same in A1 and B1.
- **Not checked:** bright or dark art, queue mode, a track change by eye, and the S25 panel.

**Visual risks (inferred):**
- **Banding:** the filter output is 8-bit at 270 x 569 and upscaled 4x with bilinear
  sampling. Where the full-resolution version had 1 px contour steps, band edges now step on
  a 4 px grid. Dark, smooth art is the most likely place to see it. Saturation 1.75 makes the
  steps larger in both versions.
- **Edge shimmer:** the 120 dp blur (sigma about 48 reduced px) removes detail long before
  the 4x upscale. As the copies rotate at 15 fps, contour steps may crawl slightly; this is
  most likely on near-flat dark art.
- **Edge shift at the sheet edges:**
  - The layer is scaled to cover the full bounds exactly, and the sheet's clip and corners
    are unchanged.
  - The outer 2 px clamp to the edge texel, which in practice is the same as the blur's CLAMP
    edge.
  - A side that is not a multiple of 4 is rounded up (the Redmi's 2274 px height becomes
    569), so that axis scales by a little under 4 (3.9965). The content stretches by less than
    0.1%.
- **Twist:**
  - Center and radius scale exactly with the layer, and the angle is unitless.
  - The twisted image is sampled at quarter resolution, then upscaled. The swirl's tightest
    curvature, near the center, could look a little softer.
  - Android's blur adds 0.5 to sigma after its radius conversion, so the effective blur is
    about 1-2 px (under 1%) wider. Not visible.
- **Art copies:** integer rounding at the reduced size shifts a copy's rotation center by up
  to 2 full-size px. This is under the blur.

**Manual check list (S25, by eye, A-B if possible; set `FilterResolutionDivisor` to 1 for A):**
1. Expanded player with a **bright** album art: at rest for 10 s. Look for blockiness, color
   change or a duller or brighter background than before.
2. The same with a **dark**, low-detail album art, which is the banding case: look at large
   smooth areas for contour lines or steps that crawl as the background turns.
3. **During a slow drag** from mini to expanded and back: the top edge and rounded corners,
   the left and right edges, and whether the background shifts or jumps when the drag starts
   or ends.
4. **At rest after a fling** to expanded: the swirl (upper left) should keep the same shape
   and position. Watch it for a few seconds of motion.
5. **Queue mode:** enter and leave the queue, and expand and collapse with queue mode on. The
   background behind the list should look the same.
6. **Track change** while expanded (next and previous): the background should switch art with
   no flash or one-frame mismatch.
7. **Collapsed mini player:** the strip behind the mini bar should look the same as before.
8. If anything is visible, try `FilterResolutionDivisor = 2` before dropping the change.

## 7. Deviations and notes

- **A 2-iteration B smoke run** (`20261014-182742`, `expandCollapse` only) was made before A1, to
  check that B rendered and that the layer was reduced before spending an A arm. It is not part
  of the A-B-A-B and is not in the tables.
- **Expanded-player screenshots and recordings:** after A2 and B2 finished measuring, and
  while their traces were analyzed on the host, I tapped the mini player in synthX bench,
  recorded 6 s to `/data/local/tmp`, took a screenshot and pressed Back. The recording was
  pulled and deleted from the phone. Each next scenario re-establishes its baseline anyway.
- **Playback:** the song stayed "Footprints (Mokita Remix)" throughout (queue item 2, paused).
  `nextExpanded` undoes its skips with Previous, which also moved the position from 0:17 to
  0:00. No media keys were sent. `idleStates` was not run, so the song did not roll over.
- **S25:**
  - One B run only, as briefed; no same-session A run.
  - The bench app ended on "Suvvi Suvvi (From "They Call Him OG")". `nextExpanded` fails
    unless it steps back to its starting title, so this is the song it found.
  - The paused KDE Connect media session was left alone, and no media keys were sent.
- **compare.py** needed `--force` because of the baseline's 30 Hz `device.json` sample (section 5).
