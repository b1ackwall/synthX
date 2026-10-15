# Album-art glow blur controls

Measurement-only study of the blur on the album-art glow
(`ui/player/PlayerAlbumArtWithGlow.kt`, `blur(20.dp, BlurredEdgeTreatment.Unbounded)`), which
[redmi-gpu-controls.md](redmi-gpu-controls.md) skipped (control 4). Measured A-B-A-B on the Redmi
Note 7 Pro (serial , Adreno 612, API 33, fixed 60 Hz) on top of commit `00b8878`, which
already has the quarter-resolution player background, Home's solid background and the one-pass
marquee. The only change between arms was the glow's blur radius. Every source edit was
temporary and has been restored (section 10).

Two kinds of statement appear. **Measured** comes from result files and traces. **Inferred** rests
on source reading or on how HWUI and Skia work, and was not tested directly.

## Verdict

**On the Redmi, the glow blur costs 7-8 render passes on every frame the app draws, about 14-18% of
RenderThread GPU flush per animation frame, and about 17-21% of GPU flush per redraw at rest.**

- **Moving (measured):**
  - Passes per moving frame fall from 18 to 10-11 in every scenario.
  - RT GPU flush per animation frame falls 0.18-0.28 ms (14-18%), beyond the A-B-A-B spread in
    every scenario: `expandCollapse`, `queueExpandCollapse`, `nextExpanded`, `idleStates` and
    `slowDrag`.
  - cpu p50 falls by 0.1-0.7 ms (1-7%). `nextExpanded`'s p50 falls from 1.5 to 0.5 ms, but
    most of its frames are near-empty.
  - Drawn fps does not change: about 51.5 in `expandCollapse` and `queueExpandCollapse`, and
    53.4 in `slowDrag`.
  - On this build the phone already keeps up in these scenarios. The glow is GPU headroom, not a
    visible frame-rate loss.
- **At rest (measured):**
  - Expanded, queue and mini-player redraws each lose 7 passes, 0.6-0.8 ms of GPU flush and
    about 0.8-1.0 ms of RenderThread time (about 10%).
  - The expanded player redraws at 15 fps, so RenderThread busy falls from about 13.4-14.6% to
    11.8-12.9%.
  - The mini player playing redraws at 2 fps. The glow is 7 of its 17 passes and 19% of its
    flush there, but at 2 fps that is only 0.1-0.2 points of RenderThread busy (1.7% to 1.6%).
  - The mini player paused draws nothing, so the glow costs nothing there.
- **slowDrag:** the same picture.
  - Passes per moving frame: 18 to 10.
  - RT GPU flush: -18% (1.44 to 1.18 ms per animation frame).
  - RenderThread total: -13%.
  - cpu p50: -4%.
  - All four are beyond the spread, at matched CPU clocks.
  - Drawn fps is 53.4 against 53.5, so on this build and this phone the glow does not drop
    frames during an injected slow drag. Section 4 has what this does and does not rule out.
- **S25 Ultra:** not measured in this study (section 7).
- **By the brief's rule:** the flush share is at or just above the 15% line, and the cost is
  clearly visible at rest. A cheaper glow is worth doing (section 8). The prototype control (step
  3) was not run, at 's request; the options in section 8 are unmeasured.

## 1. Where the glow blur applies

Read from source; sizes measured in the Redmi traces unless marked.

- **One instance:** `PlayerSheetSkeleton.kt:409` composes `PlayerAlbumArtWithGlow` once, inside
  the sheet. Size and offset come from `computeArtPosition` (`QueueArtPositioning.kt`). The same
  composable is the mini player's art, the expanded art and the queue-mode header art, and it
  morphs between them. It is composed whenever a song is current, so it is also under the mini
  player on Home and Library.
- **Modifier chain on the glow box** (`PlayerAlbumArtWithGlow.kt:63-70`):
  1. `requiredSize(glowSize)`, where glowSize is the art plus 2 dp per side, quantized to 4 dp
     steps.
  2. `scale(artScale)`, its own `graphicsLayer`. It is 1.0 while playing and 0.9 while paused,
     with a spring between them.
  3. `blur(20.dp, Unbounded)`. In Compose 1.12 this is a `graphicsLayer` with
     `renderEffect = BlurEffect(55 px, 55 px, Decal)` and `clip = false`.
  4. `background(black at 80%, 2 dp corners)`.
  The art itself is a sibling drawn on top, with its own `scale` and `clip`. The art has no
  blur.
- **Blur radius:** 20 dp is 55 px on the Redmi (density 2.75). HWUI turns that into sigma
  0.57735 x 55 + 0.5 = 32.3 px, so the shadow fades out about 3 sigma (97 px) past the box
  (inferred).

| State | Art size | Glow layer (blur input) | Visible extent (inferred) |
|---|---|---|---|
| Mini player | 44 dp = 121 px | 48 dp = 132 x 132 px | about 326 px square |
| Expanded | 328.7 dp = 904 px | 332 dp = 913 x 913 px | about 1107 px square |
| Queue mode | 44 dp = 121 px | 132 x 132 px | about 326 px square |
| Expand/collapse morph | 44 to 328.7 dp | 132 to 913 px in 11 px (4 dp) steps; about 44 sizes seen | |
| S25 Ultra (not re-measured) | | 180 to 1215 px in 15 px steps (`layer-discovery.md` saw 195-1215 during expand); radius 75 px | |

- **What re-runs and when:**
  - **Measured:** the glow layer's content (the black rounded rect) is re-rasterized only when
    its quantized size changes. In arm A that is about 31 renders per `expandCollapse`
    iteration, at 0.01-0.05 ms of RenderThread CPU each. It never re-rasterizes in
    `queueExpandCollapse`, where the art stays 44 dp, or at rest.
  - **Measured:** the filter itself costs 7 passes in *every* drawn frame:
    - in all moving frames;
    - in the expanded player's 15 fps background redraws while the art is static;
    - in queue mode at rest;
    - in the mini player's 2 fps redraws while playing.
    Passes per drawn frame are 17-18 with the blur and 10-11 without it in every state.
  - **Inferred** (AOSP 13 `RenderNodeDrawable::drawContent`): HWUI applies a node's
    RenderEffect to the layer snapshot each time the layer is drawn. The blurred result is not
    kept between frames, so any redraw of the window re-runs the blur, even when the glow did
    not change. That matches the Home-layer finding in `redmi-gpu-controls.md` (control 3).
  - **Inferred:** the 7 passes are the layer render plus Skia's blur:
    - downsampling, because sigma 32 is reduced to 4 or less by scaling;
    - two separable blur passes;
    - the upscale draw.
  - **Scale:** artScale is a separate outer layer. Play and pause change only the matrix the
    blurred image is drawn with; the blur layer itself is not re-rasterized (inferred from the
    modifier order).

## 2. Method

- **Command, every arm:** `scripts/perf/run-benchmark.fish  --slice-frames --scenario
  expandCollapse,queueExpandCollapse,nextExpanded,miniIdlePlaying,idleStates`.
  - Each arm is one script invocation, which rebuilds and reinstalls the benchmark APK.
  - Order A1, B1, A2, B2.
  - `slowDrag` ran as a separate block, in the order B3, A3, B4, A4 (section 4).
- **Arms:**
  - **A** = the original file. Copied back from a scratch copy, sha256 `54754d49...`, the same
    as in `redmi-gpu-controls.md`.
  - **B** = one line changed: `GlowBlurRadius = 0.dp`. With the Unbounded edge treatment (no
    clip) and radius 0, Compose 1.12's `Modifier.blur` returns the modifier unchanged (checked in
    `Blur.kt` from the ui-android 1.12.0 sources). So B has no blur layer and no RenderEffect.
    The 80% black rounded rect is still drawn, unblurred, as a 2 dp frame around the art. The
    art is unchanged.
  - Trace check: arm A has square `drawLayer` slices from 132 to 913 px; arm B has none of
    them.
- **Phone state:** battery 27.4-28.2 C in every arm (none needed the 33 C wait); Magisk shell su
  denied (checked), so unrooted; plugged in; fixed 60 Hz.
- **Song:**
  - Every arm started on "Footprints (Mokita Remix)" at 0:00, paused. After each arm I checked
    `dumpsys media_session` and sent Previous keys to return there. Each time synthX bench was
    the only session in the user stack and the media-button session; telecom's
    `HeadsetMediaButton` global-priority session was inactive.
  - A1 and B1 ended on "Lay It Down" at 0:49; Previous x2 brought back "Footprints".
  - A2 and B2 ended on "Footprints" at 1:48 and 2:18; Previous x1 restarted it.
  - **Measured:** no `synthX.PlayerArtDecode` occurs inside any `miniIdlePlaying` or
    `idleStates` trace. The track changes happened in the untraced pauses between iterations,
    where playback continues.
  - Those pauses were longer in A1 and B1 (below), so their later resting windows likely
    played "Lay It Down" while A2 and B2 played "Footprints". The glow is plain black and does
    not depend on the art. The background's filter cost does not depend on the art either
    (inferred). A1 and A2 agree within 0.12 ms on the expanded and queue resting flush per
    redraw, and within 0.34 ms on the mini player's.
- **Metrics:** computed exactly as in `redmi-gpu-controls.md` section 3 and
  `redmi-background-quarter-res.md` section 2:
  - Animation-window cpu p50/p90/p99 and the share over 16.67 ms, from `trace-frames.csv`.
  - Passes: median `OpsTask::onPrepare` per `DrawFrame*` in moving frames.
  - RT GPU flush per animation frame.
  - `dequeueBuffer` sleep: states S and D inside the outermost `dequeueBuffer` only, per
    animation frame.
  - Drawn fps: drawn animation frames per second of animation window.
  - Resting states (`idleStates` iterations 1-3, `miniIdlePlaying` iterations 1-5 using its
    `measureBlock` slice): drawn fps, RenderThread ms per drawn frame, RenderThread busy
    (`DrawFrame*` time over the window), main busy (`Choreographer#doFrame` time over the
    window), GPU flush per drawn frame and passes per drawn frame.
  - The scripts are the previous studies' ad hoc TraceProcessor scripts plus one for the
    resting windows. They are not committed.
- **"Beyond spread":** the difference of the arm means is larger than the larger within-arm range
  (|A1-A2| or |B1-B2|).
- **CPU clock (measured):** time-weighted clock of the RenderThread's core inside
  `flush commands`:
  `expandCollapse` A 1629 / 1785 MHz, B 1779 / 1807 MHz; `idleStates` A 1138 / 1103, B 1117 /
  1118. Flush per call in `expandCollapse` fell from 1.81 / 1.70 ms (A) to 1.45 / 1.47 ms (B), 14%
  in the clock-matched pair A2 / B2, so the difference is not a clock effect.
- **Runs** (prefix `20261014-`):

| Arm | Run | Battery start / end C |
|---|---|---|
| A1 | 205512 | 27.4 / 27.7 |
| B1 | 212046 | 27.5 / 27.9 |
| A2 | 213639 | 27.6 / 28.2 |
| B2 | 215120 | 27.8 / 28.0 |
| B3 (slowDrag) | 220652 | 28.0 / 28.2 |
| A3 (slowDrag) | 221032 | 27.8 / 28.2 |
| B4 (slowDrag) | 221422 | 27.7 / 27.9 |
| A4 (slowDrag) | 221843 | 27.6 / 28.0 |

## 3. Redmi results

Measured. Times are ms. "anim frame" = every frame in an animation window; "moving frame" = an
animation frame in which `synthX.PlayerSheet` composed.

### expandCollapse

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 10.3 | 9.7 | 10.1 | 9.9 | 10.2 | 9.8 | -0.4 (-4%) | 0.2 | yes |
| cpu p90 ms | 16.0 | 15.6 | 24.2 | 15.1 | 20.1 | 15.3 | -4.8 (-24%) | 8.2 | no |
| cpu p99 ms | 32.9 | 26.0 | 32.5 | 31.7 | 32.7 | 28.9 | -3.9 (-12%) | 5.6 | no |
| over 16.67 ms, % | 9.8 | 9.7 | 13.6 | 8.8 | 11.7 | 9.3 | -2.4 (-21%) | 3.8 | no |
| passes per moving frame | 18 | 11 | 18 | 11 | 18 | 11 | -7 (-39%) | 0 | yes |
| RT GPU flush, ms/anim frame | 1.34 | 1.12 | 1.29 | 1.14 | 1.32 | 1.13 | -0.18 (-14%) | 0.05 | yes |
| dequeueBuffer sleep, ms/anim frame | 0.85 | 0.83 | 1.31 | 0.83 | 1.08 | 0.83 | -0.25 (-23%) | 0.46 | no |
| drawn fps | 51.5 | 51.7 | 51.4 | 51.6 | 51.4 | 51.7 | +0.2 (+0%) | 0.1 | yes |

- p90, p99 and the share over budget moved down, but A2 had a worse tail than A1 (p90 24.2
  against 16.0), so they are within the spread.
- `dequeueBuffer` sleep: also within the spread, for the same reason.

### queueExpandCollapse

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 12.4 | 12.1 | 12.2 | 12.2 | 12.3 | 12.2 | -0.1 (-1%) | 0.2 | no |
| cpu p90 ms | 14.8 | 16.4 | 14.1 | 14.1 | 14.5 | 15.2 | +0.8 (+5%) | 2.2 | no |
| cpu p99 ms | 30.0 | 30.0 | 18.2 | 18.8 | 24.1 | 24.4 | +0.3 (+1%) | 11.8 | no |
| over 16.67 ms, % | 5.6 | 9.6 | 2.5 | 2.2 | 4.0 | 5.9 | +1.8 (+46%) | 7.4 | no |
| passes per moving frame | 18 | 11 | 18 | 11 | 18 | 11 | -7 (-39%) | 0 | yes |
| RT GPU flush, ms/anim frame | 1.51 | 1.28 | 1.48 | 1.28 | 1.50 | 1.28 | -0.22 (-14%) | 0.02 | yes |
| dequeueBuffer sleep, ms/anim frame | 0.20 | 0.71 | 0.03 | 0.04 | 0.11 | 0.37 | +0.26 (+228%) | 0.67 | no |
| drawn fps | 51.5 | 51.4 | 51.2 | 51.4 | 51.4 | 51.4 | +0.1 (+0%) | 0.4 | no |

- The glow stays 132 px in queue mode, but its filter still costs the same 7 passes and about
  0.22 ms of flush per frame.
- cpu percentiles and `dequeueBuffer` sleep did not move beyond the spread. B1 had a worse tail
  than the other three runs.

### nextExpanded (bimodal before; read with care)

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 1.7 | 0.5 | 1.3 | 0.4 | 1.5 | 0.5 | -1.1 (-70%) | 0.3 | yes |
| cpu p90 ms | 9.9 | 9.2 | 10.5 | 9.3 | 10.2 | 9.2 | -1.0 (-10%) | 0.5 | yes |
| cpu p99 ms | 22.3 | 24.0 | 24.4 | 23.3 | 23.4 | 23.7 | +0.3 (+1%) | 2.1 | no |
| over 16.67 ms, % | 5.9 | 5.4 | 6.0 | 5.0 | 5.9 | 5.2 | -0.7 (-12%) | 0.4 | yes |
| passes per moving frame | 17 | 10 | 17 | 10 | 17 | 10 | -7 (-41%) | 0 | yes |
| RT GPU flush, ms/anim frame | 1.40 | 1.16 | 1.48 | 1.27 | 1.44 | 1.21 | -0.23 (-16%) | 0.11 | yes |
| dequeueBuffer sleep, ms/anim frame | 0.24 | 0.17 | 0.32 | 0.21 | 0.28 | 0.19 | -0.09 (-32%) | 0.08 | yes |
| drawn fps | 26.2 | 23.8 | 24.4 | 22.9 | 25.3 | 23.4 | -1.9 (-8%) | 1.8 | yes |

- **The bimodality in `redmi-gpu-controls.md` 6.2 did not show up here.** Both A runs had about
  2.7-2.9 ms of GPU flush per drawn frame, against 7 or 15-18 ms before the background moved to
  quarter resolution. So this pair is comparable.
- **RT GPU flush per drawn frame** fell 10% (2.82 to 2.54 ms), which is within the spread
  (0.32).
- **Drawn fps** fell 1.9, beyond the spread. This scenario's animation windows are mostly the
  wait for the art change, not continuous motion (`redmi-background-quarter-res.md`), so fps
  there is not smoothness. Not explained.

### idleStates (animation windows: the transitions between states)

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 9.5 | 8.5 | 9.3 | 8.9 | 9.4 | 8.7 | -0.7 (-7%) | 0.5 | yes |
| cpu p90 ms | 25.8 | 14.0 | 16.0 | 20.5 | 20.9 | 17.3 | -3.6 (-17%) | 9.8 | no |
| cpu p99 ms | 29.7 | 26.4 | 29.2 | 27.0 | 29.4 | 26.7 | -2.7 (-9%) | 0.5 | yes |
| over 16.67 ms, % | 14.9 | 7.0 | 9.2 | 11.1 | 12.1 | 9.0 | -3.1 (-25%) | 5.6 | no |
| passes per moving frame | 18 | 11 | 18 | 11 | 18 | 11 | -7 (-39%) | 0 | yes |
| RT GPU flush, ms/anim frame | 1.60 | 1.26 | 1.56 | 1.35 | 1.58 | 1.30 | -0.28 (-18%) | 0.10 | yes |
| dequeueBuffer sleep, ms/anim frame | 1.46 | 0.67 | 0.90 | 1.14 | 1.18 | 0.90 | -0.28 (-23%) | 0.56 | no |
| drawn fps | 40.4 | 39.6 | 40.5 | 40.6 | 40.5 | 40.1 | -0.4 (-1%) | 1.0 | no |

### At rest

`idleStates` windows (iterations 1-3) and `miniIdlePlaying` (iterations 1-5).

**Mini player playing, 10 s windows (`miniIdlePlaying`):**

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| drawn fps | 2.0 | 2.0 | 2.0 | 2.0 | 2.0 | 2.0 | +0.0 (+0%) | 0.0 | no |
| RT ms per drawn frame | 8.32 | 7.76 | 8.93 | 7.78 | 8.62 | 7.77 | -0.85 (-10%) | 0.61 | yes |
| RT busy % | 1.7 | 1.6 | 1.8 | 1.6 | 1.7 | 1.6 | -0.2 (-10%) | 0.1 | yes |
| main busy % | 2.0 | 2.3 | 2.0 | 1.8 | 2.0 | 2.1 | +0.0 (+2%) | 0.4 | no |
| GPU flush ms per drawn frame | 3.78 | 3.07 | 4.12 | 3.32 | 3.95 | 3.20 | -0.76 (-19%) | 0.34 | yes |
| passes per drawn frame | 17.0 | 10.0 | 17.0 | 10.0 | 17.0 | 10.0 | -7.0 (-41%) | 0.0 | yes |

**Expanded, playing:**

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| drawn fps | 16.7 | 16.8 | 16.7 | 16.2 | 16.7 | 16.5 | -0.2 (-1%) | 0.6 | no |
| RT ms per drawn frame | 8.54 | 7.78 | 8.90 | 7.84 | 8.72 | 7.81 | -0.91 (-10%) | 0.36 | yes |
| RT busy % | 14.2 | 13.1 | 14.9 | 12.7 | 14.6 | 12.9 | -1.6 (-11%) | 0.7 | yes |
| main busy % | 7.0 | 7.0 | 5.4 | 5.6 | 6.2 | 6.3 | +0.2 (+3%) | 1.6 | no |
| GPU flush ms per drawn frame | 3.54 | 2.90 | 3.66 | 2.90 | 3.60 | 2.90 | -0.70 (-19%) | 0.12 | yes |
| passes per drawn frame | 17.9 | 10.9 | 17.9 | 10.9 | 17.9 | 10.9 | -7.0 (-39%) | 0.0 | yes |

**Expanded, paused:**

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| drawn fps | 15.0 | 15.0 | 15.0 | 15.0 | 15.0 | 15.0 | -0.0 (-0%) | 0.0 | no |
| RT ms per drawn frame | 8.95 | 7.96 | 8.87 | 7.81 | 8.91 | 7.89 | -1.02 (-11%) | 0.16 | yes |
| RT busy % | 13.4 | 11.9 | 13.3 | 11.7 | 13.4 | 11.8 | -1.5 (-11%) | 0.2 | yes |
| main busy % | 4.1 | 4.3 | 4.1 | 3.8 | 4.1 | 4.1 | -0.0 (-1%) | 0.6 | no |
| GPU flush ms per drawn frame | 3.60 | 2.84 | 3.55 | 2.83 | 3.58 | 2.84 | -0.74 (-21%) | 0.05 | yes |
| passes per drawn frame | 18.0 | 11.0 | 18.0 | 11.0 | 18.0 | 11.0 | -7.0 (-39%) | 0.0 | yes |

**Queue mode, paused:**

| metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| drawn fps | 15.0 | 15.0 | 15.0 | 15.0 | 15.0 | 15.0 | +0.0 (+0%) | 0.0 | no |
| RT ms per drawn frame | 8.95 | 8.05 | 8.85 | 8.15 | 8.90 | 8.10 | -0.80 (-9%) | 0.11 | yes |
| RT busy % | 13.4 | 12.1 | 13.3 | 12.2 | 13.3 | 12.1 | -1.2 (-9%) | 0.2 | yes |
| main busy % | 4.4 | 4.4 | 4.4 | 4.1 | 4.4 | 4.2 | -0.2 (-4%) | 0.3 | no |
| GPU flush ms per drawn frame | 3.66 | 2.98 | 3.60 | 3.05 | 3.63 | 3.02 | -0.62 (-17%) | 0.08 | yes |
| passes per drawn frame | 18.0 | 11.0 | 18.0 | 11.0 | 18.0 | 11.0 | -7.0 (-39%) | 0.0 | yes |

- **Mini player paused:**
  - 0 drawn frames in every arm, so the glow has no cost there.
  - Main busy and main on-CPU are under 0.3% in every arm.
- **Home with the mini player paused:**
  - 0.5 drawn fps, about 2 frames per window.
  - Passes per drawn frame fell from 93 to 52, and flush per drawn frame from 10.6 to 8.3 ms.
  - Those few frames are the end of the switch to Home, so this is not a steady state.
- **Mini player playing in `idleStates`** (4 s windows, about 24 drawn frames per arm): passes
  16.6 to 10.0 (beyond the spread). Flush 3.69 to 3.75 ms and RenderThread 9.34 to 9.71 ms per
  redraw are within the spread (0.77 and 1.87). Use `miniIdlePlaying`, with about 100 frames per
  arm, for the mini-player cost.

## 4. slowDrag (added at 's request)

Same arms and method as section 3, `scripts/perf/run-benchmark.fish  --slice-frames
--scenario slowDrag`, 10 iterations per arm, in the order B3, A3, B4, A4. The last arm was A, so the
phone ends on the original build. `slowDrag` drags up 35% of the display over one second, holds
300 ms and releases, then the same downward. The sheet settles by its 20% positional threshold.

| metric | A3 | B3 | A4 | B4 | A mean | B mean | B - A | max arm range | beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 ms | 9.4 | 9.2 | 9.5 | 9.0 | 9.5 | 9.1 | -0.4 (-4%) | 0.2 | yes |
| cpu p90 ms | 12.2 | 11.8 | 13.1 | 11.7 | 12.6 | 11.7 | -0.9 (-7%) | 0.9 | no |
| cpu p99 ms | 25.4 | 24.6 | 32.4 | 24.4 | 28.9 | 24.5 | -4.4 (-15%) | 7.0 | no |
| over 16.67 ms, % | 2.8 | 2.5 | 5.6 | 2.6 | 4.2 | 2.6 | -1.6 (-39%) | 2.8 | no |
| passes per moving frame | 18 | 10 | 18 | 10 | 18 | 10 | -8 (-44%) | 0 | yes |
| RT GPU flush, ms/anim frame | 1.49 | 1.16 | 1.39 | 1.20 | 1.44 | 1.18 | -0.26 (-18%) | 0.09 | yes |
| RT GPU flush, ms/drawn frame | 1.64 | 1.27 | 1.53 | 1.31 | 1.58 | 1.29 | -0.29 (-18%) | 0.10 | yes |
| dequeueBuffer sleep, ms/anim frame | 0.42 | 0.38 | 0.65 | 0.39 | 0.54 | 0.39 | -0.15 (-28%) | 0.23 | no |
| drawn fps | 53.4 | 53.4 | 53.3 | 53.5 | 53.3 | 53.5 | +0.1 (+0%) | 0.1 | yes |

**Beyond the spread (measured):**
- Passes per moving frame: 18 to 10.
- RT GPU flush: -18%.
- RenderThread total: -13% (4.88 to 4.23 ms per animation frame).
- cpu p50: -4%.
- Drawn fps: +0.1, which is negligible.

**Within the spread:** p90, p99, the share over 16.67 ms and `dequeueBuffer` sleep. A4 had a
worse tail than A3 (p99 32.4 against 25.4).

**CPU clock (measured):**
- RenderThread in `flush commands`: 1614 / 1633 MHz (A) and 1654 / 1599 MHz (B).
- Flush per call fell from 1.90 / 1.81 ms to 1.47 / 1.50 ms.
- So the difference is not a clock effect.

**The size steps during the drag (measured, arm A):**
- About 65 frames per iteration re-rasterize the glow layer, at about 50 distinct sizes, as it grows
  and shrinks in 4 dp steps. That is 650 of about 2160 drawn animation frames per run.
- Those frames spend 0.38-0.39 ms in `flush layers`, against 0.24-0.25 ms for the other drag
  frames. That is +0.14 ms of layer reallocation per step.
- Their cpu p50 is about 1 ms higher (10.3-10.5 against 9.4-9.5 ms), but they are also the
  frames in which the art is mid-morph, so this is not all the glow's.
- Their share over 16.67 ms is lower (1.8-4.0% against 3.6-7.1%).
- The steps do not cause hitches on this phone.

**What this does and does not rule out:**
- **Measured:** on the Redmi, at 60 Hz, on this build, the glow blur adds GPU and RenderThread
  work in every slow-drag frame, but no dropped frames and no hitch at its size steps.
- **Not covered:**
  - **A real finger.** The injected drag sends one touch sample every 33 ms, so the sheet moves
    on every second frame (`scripts/perf/README.md`). A finger moves it every frame. The
    per-frame cost would be the same, but the share of moving frames doubles (inferred).
  - **The S25 at 120 Hz.** The frame budget there is 8.33 ms; not measured (section 7).
  - **The look.** The shadow's size follows the art in 4 dp steps, so it can lag or lead the
    art by up to 2 dp in size (1 dp, about 3 px, per side). Under a 32 px sigma blur that is
    unlikely to be visible (inferred). No screen recording was taken.
- **If the awkwardness you saw is a feel or a look rather than a frame time,** the next check is
  a screen recording of a slow finger drag in arms A and B, side by side. Do it on the phone
  where you saw it.

## 5. What the glow blur costs on the Redmi

Mean of two runs per arm. "Share" = the glow blur's part of the A value.

| Where | Passes per drawn frame | GPU flush | RenderThread | Beyond spread |
|---|---|---|---|---|
| expandCollapse, per anim frame | 7 of 18 | 0.18 of 1.32 ms (14%) | 0.67 of 5.30 ms (13%) | passes, flush, RT total |
| queueExpandCollapse, per anim frame | 7 of 18 | 0.22 of 1.50 ms (14%) | within noise | passes, flush |
| nextExpanded, per anim frame | 7 of 17 | 0.23 of 1.44 ms (16%) | 0.63 of 2.52 ms (25%) | passes, flush, RT total |
| idleStates transitions, per anim frame | 7 of 18 | 0.28 of 1.58 ms (18%) | 0.84 of 5.46 ms (15%) | passes, flush, RT total |
| slowDrag, per anim frame | 8 of 18 | 0.26 of 1.44 ms (18%) | 0.65 of 4.88 ms (13%) | passes, flush, RT total |
| Expanded at rest (15 fps), per redraw | 7 of 18 | 0.70-0.74 of 3.6 ms (19-21%) | 0.9-1.0 of 8.7-8.9 ms (11%) | all of these; busy 13.4-14.6% to 11.8-12.9% |
| Queue at rest (15 fps), per redraw | 7 of 18 | 0.62 of 3.63 ms (17%) | 0.80 of 8.90 ms (9%) | all of these |
| Mini playing (2 fps), per redraw | 7 of 17 | 0.76 of 3.95 ms (19%) | 0.85 of 8.62 ms (10%) | all of these; busy 1.7% to 1.6% |
| Mini paused | none drawn | none | none | n/a |

Not visible in drawn fps: 51.4 against 51.7 in `expandCollapse`, 51.4 against 51.4 in
`queueExpandCollapse`, 53.3 against 53.5 in `slowDrag`, and 15 / 16.5-16.7 fps at rest in both
arms. Today the glow costs GPU and RenderThread headroom, battery and heat on this phone, not
frames, in every scenario measured.

## 6. Why it costs what it costs (inferred)

- **Pass count, not pixels:**
  - At most 913 px square is blurred, against 270 x 569 for the whole quarter-resolution
    background.
  - On a tiled GPU like the Adreno 612, each extra render pass costs a tile load and store and a
    flush boundary. The 7 passes are a fixed cost per frame, which is why 132 px in queue mode
    costs about as much flush as 913 px in `expandCollapse` (0.22 against 0.18 ms).
- **No reuse:** the blurred result is not cached across frames (section 1), so a static glow
  costs the same as a moving one. That is the at-rest cost.

## 7. Not done

- **S25 Ultra at 120 Hz (step 4):**
  - Not run. The phone was not attached, and  ended the session after the `slowDrag` block.
  - The only S25 data is the layer census in `layer-discovery.md`: 29 glow sizes and 56 ms of
    layer allocations per 10 iterations, rated "real but small".
  - Still open: same-session A and B on `expandCollapse`, `nextExpanded` and `idleStates`.
    Report p50/p90/p99, the share over 8.33 ms and both runs' `flush commands` CPU clock
    (`perf-trace-gotchas`: S25 clocks differ 2-3x across sessions).
- **Cheaper-glow prototype (step 3):**
  - Not run, at 's request.
  - A candidate was prepared in the session scratchpad and never copied into the tree or built.
    It draws the same rounded rect with a `BlurMaskFilter` (same radius-to-sigma conversion) and
    no layer.
- **Visual check:** none; B is a cost control (a hard 2 dp black frame instead of the shadow).

## 8. Options (proposed, not implemented, not measured)

Ranked by expected gain against visual risk. All are draw-phase changes inside
`PlayerAlbumArtWithGlow.kt`. None touches `PlayerSheetMotion.kt`, the gesture loop, the
draggable's `enabled`, or a locked motion constant.

1. **Blur the shape, not a layer:**
   - **Change:** draw the rounded rect with a blur `MaskFilter` (`BlurMaskFilter`, radius 55 px
     on this phone) in `drawBehind`, and drop the `blur` layer.
   - **Expected (inferred):** large sizes use Skia's analytic rounded-rect blur, a single draw
     with a small cached profile texture and no extra pass. Small sizes (under about 74 dp on
     the Redmi) may fall back to a GPU mask that Skia caches per size.
   - **Visual:** the same Gaussian of the same shape. Android's mask filter and its RenderEffect
     blur both convert radius to sigma as 0.57735 x r + 0.5.
   - **Risk:** low to medium. Mask-filter support and caching depend on the HWUI/Skia version, so
     measure passes on both phones.
2. **Compose `Modifier.dropShadow`** (in Compose 1.12):
   - **How it works:** it rasterizes an Alpha8 shadow bitmap on the CPU once per size and draws
     it as a texture.
   - **At rest:** nearly free.
   - **While the art morphs:** each new 4 dp size builds a new bitmap on the main thread, about
     31 per expand-collapse. Its shadow cache keeps every size it has seen (not trimmed in
     1.12's `AndroidShadowContext`).
   - **Visual:** it cuts the shadow at one radius (55 px, about 1.7 sigma), so a faint edge is
     possible on bright backgrounds.
3. **Cache the blurred glow yourself:**
   - **Change:** draw the glow once per size into an `ImageBitmap` (or one nine-patch-style
     bitmap with fixed corners for all sizes) and draw it with `drawImage`.
   - **Same benefit as 2,** without the unbounded cache.
   - **More code.**
4. **Quarter-resolution blur layer** (as for the background):
   - **Change:** shrink the layer and the radius by 4 and scale back up.
   - **Expected:** a small gain. The blur still re-runs on every frame, and its passes remain;
     the pass count, not the pixel count, is most of the cost here (section 6).
5. **Platform elevation shadow** (`Modifier.shadow`):
   - Cheapest, but a different look (light from above, ambient and spot). A design decision.

Measure any of them as here: A-B-A-B with `--slice-frames`, judged by passes per drawn frame,
flush, `dequeueBuffer` sleep and the resting RenderThread rows, plus `slowDrag`. Then a
same-session A-B on the S25.

## 9. Deviations and notes

- **Step 3 (cheaper-glow prototype) and step 4 (S25) were not run:**  asked to drop the
  prototype, test `slowDrag` instead and close for the day. The S25 was not attached at any
  point.
- **Added: a `slowDrag` A-B-A-B** (section 4), at 's request. Its order is B3, A3, B4, A4
  so that the last install is the original source.
- **The first `slowDrag` chain failed instantly:** a shell loop relied on word splitting, which
  zsh does not do, so `arm.sh` stopped at its first `cp` before touching anything. Nothing was
  built, installed or run; the source file's sha256 was checked unchanged before the rerun.
- **Long pauses between iterations:** Macrobenchmark's trace save stalled on the phone (about
  37 s per `expandCollapse` iteration, once 2 min 20 s in A1's `queueExpandCollapse`), with about
  104% I/O wait and an Instagram process bound to a foreground service at the time (gone by the
  end of A1; not touched). The pauses fall outside the traces, but playback continued through
  them, which is why A1 and B1 rolled over to the next song (section 2).
- **Media keys:** six Previous keys in all (two each after A1 and B1, one each after A2 and B2).
  Each time I first confirmed in `dumpsys media_session` that synthX bench was the only session
  in the user stack and the media-button session. The `slowDrag` arms play no audio, and the
  phone ended on "Footprints (Mokita Remix)" at 0:00, paused, the track it started on.
- **Host analysis ran while the phone measured:** it uses only the host's CPU. The phone's
  timings do not depend on it.
- **`device.json` `commit_dirty`:** false for every A arm, because the restored file equals
  `00b8878`, and true for every B arm. No arm failed a scenario.
- **Unexplained:** `nextExpanded` drawn fps fell 1.9 with the blur off (beyond the spread). Its
  windows are mostly waiting for the art change.
- **git:** only read-only commands were used (`git status --porcelain`, `git rev-parse HEAD`).

## 10. Restoration

- **Touched under `app/`:** only `ui/player/PlayerAlbumArtWithGlow.kt`, temporarily.
  - It was copied to the session scratchpad before the first edit.
  - Each arm copied either the scratch copy (A) or the one-line variant (B) into place and
    checked it with `cmp`.
  - The variant files and the unused prototype stayed in the scratchpad.
- **Proof, after the last arm:** the scratch copy was copied back.
  - `diff` against it prints nothing.
  - `sha256sum -c` gives `PlayerAlbumArtWithGlow.kt: OK`, with sha256
    `54754d4930ff2bb505a5069161e1e2fe06eabca19d1c9465856489c2ae5bb249`. That is the same file
    as in `redmi-gpu-controls.md` section 9.
  - 81 lines.
  - `git status --porcelain` is empty apart from this doc.
- **Installed build:** the last arm (A4, 20261014-221843) built and installed the benchmark APK
  from the original source. The phone's synthX bench therefore matches HEAD `00b8878`.
- **Not touched:** the production package `com.v1per.synthx`, the debug package, the music
  folder, and any device other than .
