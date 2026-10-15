# Home on a solid background

Single change: Home stops drawing the ambient blob background (`AmbientBlobBackground`, 150 dp
blur) and fills with the app's base surface token, `Background`. Follows
[redmi-gpu-controls.md](redmi-gpu-controls.md) section 8, option 5 and the "Home 150 dp blur"
row of section 5.

**Verdict: keep.** On the Redmi Note 7 Pro (A-B-A-B), every `homeExpandCollapse` cost fell
beyond the spread: cpu p50 28.8 to 9.4 ms, frames over 16.67 ms 83% to 0.9%, passes 29 to 18,
`dequeueBuffer` sleep 5.8 to 0.06 ms. Home at rest went from 60 fps with the RenderThread
83-91% busy and the main thread 31-55% busy, to 0.5 fps and under 0.5% busy on both. On the
Galaxy S25 Ultra at 120 Hz (same-session A-B, matched clocks), Home at rest went from 120 fps
with the RenderThread 58% busy to 0 fps. RenderThread cost per moving frame fell 29% and GPU
flush 39%. On both phones every moving frame still drew, and nothing regressed.

## 1. Change

- `ui/screens/home/HomeScreen.kt` (70 lines):
  - The Home box's background is `Background` (was `SurfaceVariant`, which the blobs covered
    completely). `Background` is the theme's `colorScheme.background`, the `SynthXNavHost`
    Scaffold's `containerColor`, and what Library, Songs, Search and the top bars fill with.
  - The `AmbientBlobBackground` call, the debug-state read and the base surface color are
    commented out under `DEAD CODE` markers.
  - `ambientPaused` stays as a parameter, unused, with `@Suppress("UNUSED_PARAMETER")`.
- `ui/MainScaffold.kt` (400 lines): the `homeAmbientPaused` derivation and the argument passing
  it to `SynthXNavHost` are commented out under `DEAD CODE` markers. `SynthXNavHost` and
  `tabRoutes` now get their default `false`.
- **Side effect of the second edit (measured):** that line was `NormalShell`'s only
  body-level read of `playerProgress`. The other read is inside the content lambda. In A the
  whole `NormalShell` body (`synthX.MainShell` section) recomposed on every sheet frame. In B
  only the content lambda does (`synthX.MainShell` sum 65.5 ms to 0 per run). The sheet
  receives the same `playerProgress` value, so its behavior is unchanged.
- No files or public APIs were deleted. No tests were added: the change is wiring only.

### Where the blob background was used (complete list)

| Place | Use |
|---|---|
| `ui/screens/home/HomeScreen.kt` | The only production call site of `AmbientBlobBackground`. Now disabled. |
| `ui/screens/developersettings/BlobBackgroundDebugSheet.kt` | Developer Settings blob editor. Uses `BlobBackgroundDebugState`, `ProductionAmbientBackgroundStyle` and `productionAmbientScrimColor`, but does not call `AmbientBlobBackground`. Untouched. |
| `androidTest/.../AmbientBlobBackgroundToggleTest.kt` | Instrumented test of the composable. Untouched. |
| `androidTest/.../BlobBackgroundDebugSheetResetTest.kt`, `test/.../SharedAmbientBackgroundTest.kt` | Tests of the debug state and style constants. Untouched. |

No other screen draws the blobs. The expanded player's background is a separate renderer
(`PlayerAltStage*`) that shares only `shouldPauseAmbientMotion` and
`rememberSystemReducedMotionActive` (`AmbientMotionPolicy.kt`), which are still used there.

### Now unused in production code (kept, listed for a later decision)

- `ui/components/AmbientBlobBackground.kt`: `AmbientBlobBackground`, `BlobParams` and
  `RandomizerConfig` (the last two still back the debug state and the debug sheet).
- `ui/components/ProductionAmbientBackgroundStyle.kt`: `Modifier.productionAmbientScrim`.
  `ProductionAmbientBackgroundStyle` and `productionAmbientScrimColor` are still read by the
  Developer Settings sheet.
- `ui/components/BlobBackgroundDebugState.kt`: `HomeBlobDebugStateHolder` is still edited by
  the Developer Settings sheet, but nothing on Home reads it, so the sheet no longer changes
  what Home shows.
- `ui/MainScaffold.kt`: `isHomeAmbientFullyOccluded` (still covered by `AmbientMotionPolicyTest`).
- The `homeAmbientPaused` parameters of `SynthXNavHost` and `tabRoutes`, and `ambientPaused` of
  `HomeScreen`, which now always receive `false`.

## 2. Galaxy S25 Ultra at 120 Hz

**Runs** (`perf-output/`, default frame timeline, `homeExpandCollapse,idleStates`):

| Run | Source | Commit | Battery start / end C | RT clock in flush, MHz | Main clock in doFrame, MHz |
|---|---|---|---|---|---|
| `20261014-145014` | baseline (briefed) | `7c80d4f` | 24.4 / 29.0 | 1173 | 1242 |
| `20261014-195245` | A, same session | `8f43e2d` clean | 27.0 / 28.4 | 1161 | 1264 |
| `20261014-194126` | B, this change | `8f43e2d` dirty | 27.4 / 28.4 | 1095 | 1154 |

- Clock = time-weighted `cpufreq` of the core running the thread, `homeExpandCollapse`
  iterations 3-6. The RenderThread was measured inside `flush commands`, and the main thread
  inside `Choreographer#doFrame`.
- Every trace has a vsync period of 8.32 ms (120 Hz). Motion smoothness is Adaptive
  (`refresh_rate_mode` 1), so an idle `dumpsys` can read 60 Hz. Trust the trace.
- **The baseline is doubly confounded.** It predates `8f43e2d` (the expanded player background
  at quarter resolution: `1440 x 3064` to `360 x 766` layer), and it has no `idleStates`.
  For this reason the same-session A run was added (approved). Read section 2.1 for this change.
  Section 2.2 is the briefed comparison.

### 2.1 Same-session A vs B (this change only)

Animation windows of `homeExpandCollapse` (`trace-frames.csv`):

| Metric | A | B | B - A |
|---|---|---|---|
| cpu p50 / p90 / p99 ms | 5.43 / 7.82 / 10.53 | 4.38 / 7.08 / 9.45 | -19% / -9% / -10% |
| Over 8.33 ms | 6.1% | 3.6% | -2.6 points |
| GPU flush, ms per anim frame | 2.09 | 1.27 | -39% |
| Drawn fps in animation windows | 111.3 | 104.2 | see below |
| Moving frames drawn | 1860 of 1860 | 1860 of 1860 | none dropped |
| RenderThread ms per moving frame | 3.68 | 2.61 | -29% |
| GPU flush ms per moving frame | 2.30 | 1.40 | -39% |
| Main ms per moving frame | 2.21 | 2.37 | +7% (main clock -9%) |
| Recomposition ms per moving frame | 1.02 | 1.06 | +4% |

- **Drawn fps falls only in the still tail of the windows.** A redrew Home in 171 of 344 still
  frames, and B in 40 of 223. Every moving frame drew in both. It is not a dropped-frame
  regression.
- **Home's blur layer** (`drawLayer [graphicsLayer] 1440.0 x 2430.0`) was drawn 315 times per
  trace in the baseline and 0 times in B.
- **Main thread +7% per moving frame:** the main-thread clock was 9% lower in B, which explains
  it. Recomposition per moving frame is flat. `compare.py` shows `MainShell.content`,
  `PlayerSheet` and `BottomChrome` sums up 20-22% in B. Those sections are now their own restart
  scope rather than nested in a per-frame `MainShell` recomposition (section 1), and the clock
  is lower. This is not attributable to a cost increase (inferred).
- `compare.py A B` (whole-run, idle frames included): `frameDurationCpuMs` p50 5.00 to 4.63, and
  p90/p95/p99 within 3%. Over 8.33 ms 3.81% to 3.86%, and deadline missed 78.8% to 35.6%.

Home at rest (`idleStates`, `bench.idle.homePaused`, iterations 1-3):

| | Drawn fps | RT ms per frame | RT busy | Main busy |
|---|---|---|---|---|
| A | 120.2 | 4.79 | 57.6% | 9.8% |
| B | 0.0 | n/a | 0.0% | 0.0% |

The other idle states changed by no more than run-to-run noise. For example, expanded paused drew
15 fps at 5.4 and 4.9 ms of RenderThread per redraw.

### 2.2 Baseline `20261014-145014` vs B (as briefed)

Run with `compare.py ... --force` (the baseline's `device.json` sampled 30 Hz). Ignore the
"frames over device budget" row, which compares against a 30 Hz budget.

| homeExpandCollapse animation windows | Baseline | B |
|---|---|---|
| cpu p50 / p90 / p99 ms | 6.04 / 9.96 / 16.25 | 4.38 / 7.08 / 9.45 |
| Over 8.33 ms | 18.0% | 3.6% |
| GPU flush, ms per anim frame | 2.24 | 1.27 |
| Drawn fps | 112.0 | 104.2 |

- `compare.py` whole-run: p50/p90/p99 6.13/9.45/14.95 to 4.63/7.19/9.51. Over 8.33 ms 17.5% to
  3.9%, and deadline missed 95% to 36%.
- Composition sections show +17-18%, for the reason given in 2.1.
- The CPU clocks match (RT 1173 vs 1095 MHz, main 1242 vs 1154 MHz). But this comparison also
  contains the quarter-resolution commit, so it overstates this change. Use 2.1.
- Home at rest: the baseline has no `idleStates`. See 2.1.

## 3. Redmi Note 7 Pro (), A-B-A-B

- **Command:** `scripts/perf/run-benchmark.fish  --slice-frames --scenario
  homeExpandCollapse,idleStates`, one invocation per arm, in the order A1, B1, A2, B2. Each arm
  rebuilt and reinstalled the benchmark APK.
- **Source per arm:** A = the original `HomeScreen.kt` and `MainScaffold.kt`, copied in from saved
  copies. B = this change. Each copy was checked with `cmp` before the run.
- **Phone state:** Magisk shell su was denied, so the phone ran unrooted. The battery read
  23.5-28.6 C, under the 33 C limit throughout. The song was "Footprints (Mokita Remix)" at the
  start.
- **Runs:** `20261014-200131` (A1), `-201233` (B1), `-202005` (A2), `-203718` (B2). All are commit
  `8f43e2d` with `commit_dirty` true (this doc is untracked).
- **Trace check:** Home's blur layer (`drawLayer [graphicsLayer] 1080.0 x 1823.0`) was drawn
  175-185 times per trace in A and 0 times in B.
- **Metrics** are computed as in `redmi-gpu-controls.md` section 3 and
  `redmi-background-quarter-res.md` section 2 (outermost `dequeueBuffer` only). "Beyond spread"
  means the difference of the arm means is larger than the larger within-arm range.

### homeExpandCollapse, animation windows (ms unless noted)

| Metric | A1 | B1 | A2 | B2 | A mean | B mean | B - A | Max arm range | Beyond spread |
|---|---|---|---|---|---|---|---|---|---|
| cpu p50 | 29.0 | 9.2 | 28.7 | 9.6 | 28.8 | 9.4 | -19.5 (-68%) | 0.4 | yes |
| cpu p90 | 32.1 | 12.3 | 32.4 | 12.7 | 32.2 | 12.5 | -19.7 (-61%) | 0.4 | yes |
| cpu p99 | 39.1 | 15.7 | 40.4 | 16.5 | 39.7 | 16.1 | -23.6 (-59%) | 1.3 | yes |
| over 16.67 ms, % | 85.6 | 0.9 | 81.2 | 0.9 | 83.4 | 0.9 | -82.5 (-99%) | 4.5 | yes |
| passes per moving frame | 29 | 18 | 29 | 18 | 29 | 18 | -11 (-38%) | 0 | yes |
| RT GPU flush per anim frame | 2.33 | 1.09 | 2.35 | 1.13 | 2.34 | 1.11 | -1.23 (-53%) | 0.04 | yes |
| dequeueBuffer sleep per anim frame | 6.00 | 0.05 | 5.68 | 0.06 | 5.84 | 0.06 | -5.78 (-99%) | 0.32 | yes |
| RT total per anim frame | 14.44 | 3.46 | 13.85 | 3.58 | 14.15 | 3.52 | -10.62 (-75%) | 0.59 | yes |
| main per anim frame | 11.47 | 5.86 | 11.27 | 6.00 | 11.37 | 5.93 | -5.44 (-48%) | 0.20 | yes |
| drawn fps | 55.2 | 51.4 | 55.5 | 51.1 | 55.4 | 51.2 | -4.1 (-7%) | 0.3 | yes, see below |

- **Drawn fps falls only in the still tail of the windows.** Moving frames drew 919 of 919
  (A1), 916 of 916 (B1), 919 of 919 (A2) and 917 of 917 (B2). Of the still frames, A redrew
  112-113 because the blobs kept animating, and B redrew 38-39. With the RenderThread freed, B
  draws every frame of the motion itself.
- **Main thread:** the main thread also halves (-48%), and its wait on the RenderThread falls
  from 6.2 to 0.4 ms. A was backed up behind the GPU-bound RenderThread.

### Home at rest (`idleStates`, `bench.idle.homePaused`, iterations 1-3)

| Arm | Drawn fps | RT ms per frame | RT busy | Main busy |
|---|---|---|---|---|
| A1 | 60.1 | 13.89 | 83.5% | 31.1% |
| B1 | 0.5 | 7.45 | 0.4% | 0.0% |
| A2 | 60.0 | 15.19 | 91.1% | 55.5% |
| B2 | 0.5 | 7.55 | 0.4% | 0.0% |
| A mean / B mean | 60.0 / 0.5 | 14.54 / 7.50 | 87.3% / 0.4% | 43.3% / 0.0% |

- Drawn fps and both busy figures are beyond the spread. The widest within-arm range is main
  busy in A, at 24 points, against a difference of 43 points.
- B's 0.5 fps is the mini player's occasional redraw. RT ms per frame in B averages only those
  few frames.
- **Other idle states:** the whole-scenario numbers do not change beyond the spread, apart from
  p99 (-24%) and the main thread's wait on the RenderThread (-37%). B2 was quieter than B1
  across all states.

**Drift from `redmi-gpu-controls.md`** (measured, different session): that doc's A had Home at
rest at 48 fps with the RenderThread 100% and the main thread 95% busy, and
`homeExpandCollapse` at p50 48 ms with 33 passes. Today's A is at 60 fps, 83-91%, 31-55%, p50
29 ms and 29 passes. Since then HEAD gained `8f43e2d`: the expanded player background now
filters at quarter resolution (layer `1080 x 2274` to `270 x 569`, visible in these traces).
The phone also started cooler (23.5 C against 28.6-31 C). Only the A-B-A-B above compares this
change.

### Playback afterwards

- `idleStates` rolled the song over to "Lay It Down" (queue item 3, paused at 0:40).
- synthX bench was the media-button session. The only other session was the system telecom
  service (`com.android.server.telecom`), which is not a player.
- Two `cmd media_session dispatch previous` presses returned it to "Footprints (Mokita Remix)",
  item 2, paused at 0:00. The first press restarted the current track, the second stepped back.

## 4. Manual check list (by eye)

1. **Home at rest:** the background is the same near-black green as Library and Search, with no
   seam at the status bar or above the mini player. The wordmark stays centered.
2. **Home to the expanded player and back** (tap, drag and fling): no flash of another color
   behind the sheet's rounded top corners, and the bottom nav slides as before.
3. **Hold the expanded player fully open for a few seconds, then collapse:** Home shows the
   same solid fill and does not flicker on return.
4. **Other tabs** (Library, Search) and Settings, then back to Home: no color jump when
   switching tabs.
5. **The mini player over Home**, playing and paused: the bar's background and the glow look as
   before.
6. **Scrolling Home:** Home has no scrolling content. Check that swiping on Home does nothing
   unexpected. In a debug build, the settings icon (top right) still opens the player
   background sheet.
7. **Developer Settings, blob sheet:** it still opens. Its edits no longer show on Home (expected).

## 5. Deviations and notes

- **Same-session S25 A run** (`20261014-195245`): added with approval, because the baseline has
  no `idleStates` and predates `8f43e2d`. The A source was copied from the scratchpad and the B
  files were restored afterwards; `cmp` confirms they are byte-identical to the saved B copies.
- **S25 cooling:** A waited until the battery read 27.5 C, to match B's start of 27.4 C.
- **The Redmi was off USB** at first. It ran only after an approved
  `adb kill-server; adb start-server` and a replug.
- **A2's completion notice was missed**, so B2 started about 11 minutes after A2 ended (battery
  27.9 C). The phone sat idle in that gap.
- **Work files** (A and B source copies, analysis scripts, logs) moved from the session
  scratchpad to `perf-output/home-solid-bg-work/` (gitignored) partway through.
- No S25 media keys were sent.
- **The briefed context** said the blob background was wired from `MainScaffold.kt`. It is drawn
  in `HomeScreen.kt`; `MainScaffold.kt` only fed it the pause flag.
- **`qr-s25.json` in the baseline folder** was regenerated by the same metrics script. It is a
  derived file under the gitignored `perf-output/`.
