# Player performance benchmark

Macrobenchmark for the player sheet, run on one physical phone. The benchmark app is a separate
package, `com.v1per.synthx.benchmark` ("synthX bench"), built from `:app`'s `benchmark` build
type: release code and R8, debug-signed, profileable. It installs next to production
(`com.v1per.synthx`) and the debug app (`com.v1per.synthx.debug`) and never replaces either.
The test APK is `com.v1per.synthx.macrobenchmark` (the `:benchmark` module).

Phones must run Android 12L (API 32) or newer. The script refuses anything older.

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
5. Play any song once, so the mini player is showing, then pause it. Leave it queued.
6. Go back to the Library tab and leave the app.

The benchmark checks this on every run and stops with a message saying which step is missing.

## Before every run

- Phone plugged in (or at least 80% charged), screen unlocked, battery saver off.
- Fixed brightness: turn off adaptive brightness and set a mid level.
- Stay-awake while charging (Developer options) helps keep the screen on.
- Let the phone cool down if it has been in use; the run stops if it reports thermal throttling.
- Only the target phone needs to be attached, but the script only ever talks to the serial you
  pass.

## Run

Set `JAVA_HOME` as in CLAUDE.md, then from the repo root:

```fish
adb devices -l                                    # find the serial
scripts/perf/run-benchmark.fish <serial>          # CompilationMode.DEFAULT, 10 iterations
scripts/perf/run-benchmark.fish <serial> --none   # CompilationMode.None (worst case)
scripts/perf/run-benchmark.fish <serial> --iterations 20
```

The script builds both APKs with assemble tasks, installs them with `adb install -r` (never
uninstalls), runs `am instrument` directly, and pulls the results into
`perf-output/<timestamp>/` (gitignored):

- `instrumentation.txt`: the runner's output, including failures.
- `*-benchmarkData.json`: per-iteration frame timings (`frameDurationCpuMs`,
  `frameOverrunMs`, frame count) and, per trace section, `<name>SumMs`, `<name>Count`, and
  `<name>MaxMs` for the sheet body and background draw.
- `*.perfetto-trace`: one Perfetto trace per iteration; open in https://ui.perfetto.dev.

On API 32-33 the script disables Macrobenchmark's compilation reset, because on those versions
the reset uninstalls and reinstalls the target app, which would erase the setup above. The
compilation mode is then not applied, and the app runs in whatever state ART left it.

## What the smoke scenario does

Launches synthX bench once (it stays alive between iterations), checks the setup, waits for
the launch-time background work to finish, then per iteration: tap the mini player to expand,
wait about 1 s, press system back to collapse, wait about 1 s. It only taps and presses back.
It never touches the music folder, never deletes or moves anything, and never targets the
production or debug packages.
