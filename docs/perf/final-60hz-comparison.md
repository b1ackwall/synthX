# Final 60 Hz comparison: S25 Ultra, before and after

Full-suite run `20261015-000626` against the 60 Hz baseline `20261013-191357`, both on the
Galaxy S25 Ultra. Raw folders are in `perf-output/` (not committed).

## Verdict

**The player sheet's jank at 60 Hz is fixed.** In the sheet-motion scenarios the share of
animation frames over 16.67 ms fell from 16-28% to 0-3%, and cpu p99 from 31-37 ms to 14-18 ms.
Drawn fps shows no change beyond spread. Queue mode is still the roughest. 120 Hz was not
re-verified.

**Decision:** stop frame-time optimization here. The remaining items cost GPU headroom, battery
and heat, not visible frames. They are tracked as GitHub issues:

- "Perf: album-art glow blur costs 7 render passes per drawn frame"
- "Perf: queue mode keeps a second full-size offscreen alpha layer"
- "Perf: expanded/queue idle states run 60 main-thread callbacks/s while drawing ~15 fps"

## 1. Setup

| | Baseline `20261013-191357` | New `20261015-000626` |
|---|---|---|
| Phone | S25 Ultra ``, API 37 | same |
| Refresh | Standard mode (Samsung mode 0), 60 Hz | same |
| Trace vsync | 16.64 ms | 16.64 ms (16.65 in `queueToggle`) |
| Commit | `f8b5121` (dirty) | `5c92c5c` (clean) |
| Battery | 34.3 to 37.3 °C | 30.6 to 33.9 °C |
| CPU clocks | not recorded | policy0 1.997 to 1.555 GHz, policy6 1.018 GHz |
| Scenarios | 10, no `nextMini`, no `idleStates` | all 12 |
| Iterations | each scenario's default | same |

- **The cpu-ms rows are weak evidence.** The baseline's clocks were not recorded, and on this
  phone frame times move by up to 30% with CPU clocks
  ([idle-and-gpu-discovery.md](idle-and-gpu-discovery.md)).
- **GPU flush and render passes do not depend on CPU clocks.** They are the stronger evidence.
- The new run took 21 min 38 s.

## 2. Method

- **Values:** pooled animation-window numbers, baseline to new.
  - cpu p50/p90/p99 and GPU flush (RenderThread, mean ms per animation frame) are from each
    run's `trace-analysis.md`. Every number below matches those files.
  - Over 16.67 ms (share of animation frames), render passes and drawn fps were computed from
    the same traces with `trace_model.py`. They are not printed in `trace-analysis.md`.
- **Render passes:** the median count of `OpsTask::onPrepare` slices per frame, over frames that
  composed `synthX.PlayerSheet` and drew.
- **Drawn fps:** drawn animation frames divided by total animation-window time.
- **Beyond spread (✔):** the difference of the per-iteration means is larger than the larger
  within-arm range. A change without ✔ is not counted as real.

## 3. Results, animation windows

| Scenario | cpu p50 | cpu p90 | cpu p99 | Over 16.67 ms | Passes | GPU flush ms | Drawn fps |
|---|---|---|---|---|---|---|---|
| expandCollapse | 10.57→6.87 | 21.33→12.79 | 34.37→14.83 ✔ | 21.2→0.1% | 19→17 ✔ | 3.90→2.32 | 51.9→54.0 |
| slowDrag | 10.50→7.56 ✔ | 19.55→11.79 ✔ | 33.07→14.03 ✔ | 16.3→0.1% ✔ | 19→17 ✔ | 4.43→2.58 ✔ | 52.8→53.2 |
| fling | 10.96→7.64 | 21.20→12.76 ✔ | 31.59→14.50 ✔ | 19.8→0.3% ✔ | 19→17 ✔ | 4.19→2.69 ✔ | 53.5→54.2 |
| queueToggle | 5.30→5.06 | 10.29→10.16 | 37.54→31.74 | 2.0→1.9% | 11→11 | 2.65→2.65 | 45.9→47.5 |
| queueExpandCollapse | 11.92→9.90 | 23.66→14.40 ✔ | 35.34→17.71 ✔ | 28.5→2.7% | 19→17 ✔ | 4.29→3.31 ✔ | 52.1→52.2 |
| queueScroll | 7.81→7.78 | 11.48→11.17 | 14.67→14.17 | 0.5→0.3% | 2→2 | 2.91→2.93 | 51.5→51.8 |
| nextExpanded | 0.31→1.58 | 3.99→4.51 | 8.72→7.96 | 0→0% | 1→1 | 0.75→0.92 | 26.9→37.5 |
| nextQueue | 1.33→1.86 | 5.70→5.61 | 9.04→9.91 | 0→0% | 2→10 | 1.18→1.27 | 33.3→42.5 |
| homeExpandCollapse | 11.45→6.77 | 19.44→12.03 | 32.97→13.86 ✔ | 19.4→0.0% ✔ | 30→17 ✔ | 4.40→2.17 ✔ | 56.4→53.3 |

- **Nothing beyond spread:** `queueToggle`, `queueScroll`, `nextExpanded` and `nextQueue`.
- **`nextQueue` passes 2→10** is not beyond spread (per-iteration medians 1-12 in the baseline,
  2-11 in the new run). Unconfirmed; worth a look.
- **Drawn fps:** no scenario changes beyond spread. The new run's within-arm ranges are wide.
- **`miniIdlePlaying`** has no animation windows. Its cpu p50/p90 over all frames
  (`summary.json`) went 4.48→2.53 and 7.21→4.78 ms; no spread test.
- **`nextMini`** is in the new run only: cpu p50/p90/p99 2.51/4.04/5.33 ms, 0% over 16.67 ms,
  8 passes, GPU flush 0.95 ms.

## 4. Idle states (new run only)

From `idleStates-benchmarkData.json`, 4 iterations. Drawn fps / main-thread callback fps:

| State | Drawn fps | Callback fps |
|---|---|---|
| miniPaused | 0 | 1 |
| miniPlaying | 2 | 5.5-6.25 |
| expandedPlaying | 16-17 | 60 |
| expandedPaused | 15 | 60 |
| queuePaused | 15 | 60 |
| homePaused | 0 | 0.75-1 |

- Nothing draws continuously.
- The expanded and queue states still run a main-thread callback on every vsync while drawing
  about 15 fps.
- Iteration 0 matches the others. In the earlier idleStates-only run `20261013-222150`,
  iteration 0 drew at 60 fps in every state.

## 5. Conclusions

- **Sheet jank fixed at 60 Hz:** over-budget share 16-28% to 0-3%, cpu p99 31-37 ms to 14-18 ms
  in the sheet-motion scenarios. Render passes per moving frame fell 19→17 in the sheet
  scenarios and 30→17 in `homeExpandCollapse`.
- **Drawn fps:** no beyond-spread change.
- **Queue is still the roughest mode:** `queueExpandCollapse` 2.7% over 16.67 ms,
  `queueToggle` cpu p99 about 32 ms.
- **120 Hz not re-verified.** The 120 Hz baseline `20261014-145014` is not on this machine; only
  partial raw data from the phone is kept in `perf-output/_phone-media-backup-20261015/`.
