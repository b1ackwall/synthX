#!/usr/bin/env fish
# Builds and runs the player smoke macrobenchmark on ONE phone, then pulls the results.
# Usage: scripts/perf/run-benchmark.fish <serial> [--none] [--iterations N]
#   --none          measure CompilationMode.None (worst case) instead of DEFAULT
#   --iterations N  expand+collapse cycles to measure (default 10)
# Never uses the Gradle connected task and never uninstalls anything.

set -l app_id com.v1per.synthx.benchmark
set -l test_id com.v1per.synthx.macrobenchmark
set -l runner $test_id/androidx.test.runner.AndroidJUnitRunner
set -l test_class $test_id.PlayerExpandCollapseBenchmark
set -l min_api 32

argparse --name=run-benchmark n/none 'i/iterations=' h/help -- $argv
or exit 2
if set -q _flag_help; or test (count $argv) -ne 1
    echo "Usage: scripts/perf/run-benchmark.fish <serial> [--none] [--iterations N]" >&2
    exit 2
end
set -l serial $argv[1]
set -l mode default
set -q _flag_none; and set mode none
set -l iterations 10
if set -q _flag_iterations
    if not string match -qr '^[1-9][0-9]*$' -- $_flag_iterations
        echo "--iterations must be a positive integer" >&2
        exit 2
    end
    set iterations $_flag_iterations
end

# 1. Device: attached, authorized, API 32+.
if not command -q adb
    echo "adb not found on PATH (Android SDK platform-tools)." >&2
    exit 1
end
set -l state (adb -s $serial get-state 2>/dev/null | string trim)
if test "$state" != device
    echo "Device $serial is not attached or not authorized (see 'adb devices -l')." >&2
    exit 1
end
set -l api (adb -s $serial shell getprop ro.build.version.sdk 2>/dev/null | string trim)
if not string match -qr '^[0-9]+$' -- "$api"
    echo "Could not read the API level of $serial (got '$api')." >&2
    exit 1
end
if test $api -lt $min_api
    echo "$serial is on API $api; benchmarks need API $min_api (Android 12L) or newer." >&2
    exit 1
end
# Below API 34 a compilation reset uninstalls the target and erases its setup.
set -l compile_args
if test $api -lt 34
    set compile_args -e androidx.benchmark.compilation.enabled false
    echo "Note: API $api, compilation reset disabled; '$mode' mode is not applied."
end
echo "Device $serial ready (API $api)."

# 2. Build both APKs with assemble tasks.
set -l repo (path resolve (status dirname)/../..)
cd $repo; or exit 1
./gradlew -q --console=plain :app:assembleBenchmark :benchmark:assembleBenchmark
or begin
    echo "Gradle build failed." >&2
    exit 1
end
set -l app_apk $repo/app/build/outputs/apk/benchmark/app-benchmark.apk
set -l test_apk $repo/benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
for apk in $app_apk $test_apk
    if not test -f $apk
        echo "Expected APK not found: $apk" >&2
        exit 1
    end
end
# The app APK must be the benchmark package, never production.
set -l app_metadata (path dirname $app_apk)/output-metadata.json
if not string match -q "*\"applicationId\": \"$app_id\"*" -- (string join ' ' < $app_metadata)
    echo "Refusing to install: $app_apk is not $app_id (see $app_metadata)." >&2
    exit 1
end
echo "Built $app_id and $test_id."

# 3. Install (replace only; never uninstall).
for apk in $app_apk $test_apk
    set -l extra
    test $apk = $test_apk; and set extra -t
    set -l out (adb -s $serial install -r $extra $apk 2>&1)
    if not string match -q '*Success*' -- $out
        printf '%s\n' $out >&2
        echo "Install failed for $apk. If the ROM asks, allow 'Install via USB'." >&2
        exit 1
    end
end
echo "Installed both APKs on $serial."

# 4. Run the instrumentation directly.
set -l run_id (date +%Y%m%d-%H%M%S)
set -l device_dir /sdcard/Android/media/$test_id/perf/$run_id
set -l local_dir $repo/perf-output/$run_id
mkdir -p $local_dir; or exit 1
echo "Running the smoke benchmark ($mode, $iterations iterations); this takes a few minutes."
adb -s $serial shell am instrument -w \
    -e class $test_class \
    -e additionalTestOutputDir $device_dir \
    -e synthx.compilation $mode \
    -e synthx.iterations $iterations \
    $compile_args \
    $runner >$local_dir/instrumentation.txt 2>&1
set -l failed 0
if not string match -qr '^OK \(' -- (cat $local_dir/instrumentation.txt)
    set failed 1
end

# 5. Pull results (JSON + Perfetto traces) even after a failure, for diagnosis.
adb -s $serial pull $device_dir/. $local_dir >/dev/null 2>&1
if test $failed -eq 1
    echo "Benchmark failed; see $local_dir/instrumentation.txt" >&2
    exit 1
end
echo "Results: $local_dir"
