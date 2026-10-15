#!/usr/bin/env fish
# Builds and runs the player macrobenchmarks on ONE phone, then pulls the results.
# Usage: scripts/perf/run-benchmark.fish <serial> [--scenario NAME[,NAME...]|all] [--quick] [--none]
#                                        [--iterations N] [--cooldown-temp C] [--cooldown-max S]
#                                        [--cooldown-fixed S] [--slice-frames]
#   --scenario NAME    scenario(s) to run, by test method name (default all; see README.md)
#   --quick            one scenario per mode (quick_names below); --scenario overrides it
#   --none             measure CompilationMode.None (worst case) instead of DEFAULT
#   --iterations N     iterations for every scenario (default: each scenario's own count)
#   --cooldown-temp C  between scenarios, wait until the battery is at or below C (default 33.0)
#   --cooldown-max S   longest temperature wait in seconds, then continue with a warning (default 120)
#   --cooldown-fixed S fixed pause between scenarios in seconds instead (--cooldown S is the same)
#   --slice-frames     frame timing from the app's frame slices, not the frame timeline (README.md)
# A failed temperature read falls back to a fixed 30 s pause.
# Never uses the Gradle connected task and never uninstalls anything.

set -l app_id com.v1per.synthx.benchmark
set -l test_id com.v1per.synthx.macrobenchmark
set -l runner $test_id/androidx.test.runner.AndroidJUnitRunner
set -l min_api 32

# Scenario names are the test method names; each line is "<method> <class>", in run order.
set -l scenario_table \
    "expandCollapse PlayerExpandCollapseBenchmark" \
    "miniIdlePlaying PlayerMiniIdleBenchmark" \
    "slowDrag PlayerDragBenchmark" \
    "fling PlayerDragBenchmark" \
    "queueToggle PlayerQueueBenchmark" \
    "queueExpandCollapse PlayerQueueBenchmark" \
    "queueScroll PlayerQueueScrollBenchmark" \
    "nextExpanded PlayerTrackChangeBenchmark" \
    "nextQueue PlayerTrackChangeBenchmark" \
    "nextMini PlayerTrackChangeBenchmark" \
    "homeExpandCollapse PlayerHomeBenchmark" \
    "idleStates PlayerIdleStatesBenchmark"
set -l all_names
for row in $scenario_table
    set -a all_names (string split ' ' -- $row)[1]
end
# --quick: one scenario per player mode, in table order.
set -l quick_names expandCollapse slowDrag queueExpandCollapse nextExpanded homeExpandCollapse

set -l usage "Usage: scripts/perf/run-benchmark.fish <serial> [--scenario NAME[,NAME...]|all] [--quick] [--none] [--iterations N] [--cooldown-temp C] [--cooldown-max SECONDS] [--cooldown-fixed SECONDS] [--slice-frames]"
argparse --name=run-benchmark n/none 'i/iterations=' 's/scenario=' 'c/cooldown=' 'cooldown-fixed=' \
    'cooldown-temp=' 'cooldown-max=' quick slice-frames h/help -- $argv
or exit 2
if set -q _flag_help; or test (count $argv) -ne 1
    echo $usage >&2
    echo "Scenarios: $all_names" >&2
    echo "Quick set: $quick_names" >&2
    exit 2
end
set -l serial $argv[1]
set -l mode default
set -q _flag_none; and set mode none
set -l frame_source timeline
set -q _flag_slice_frames; and set frame_source slices
set -l iteration_args
set -l iterations_json null
if set -q _flag_iterations
    if not string match -qr '^[1-9][0-9]*$' -- $_flag_iterations
        echo "--iterations must be a positive integer" >&2
        exit 2
    end
    set iteration_args -e synthx.iterations $_flag_iterations
    set iterations_json $_flag_iterations
end
# Cooldown: by battery temperature unless a fixed pause is given. $cooldown is the fixed pause,
# and also the fallback when the temperature cannot be read.
set -l cooldown 30
set -l cooldown_mode temperature
set -l fixed_flag
set -q _flag_cooldown; and set fixed_flag $_flag_cooldown
set -q _flag_cooldown_fixed; and set fixed_flag $_flag_cooldown_fixed
if test -n "$fixed_flag"
    if not string match -qr '^[0-9]+$' -- $fixed_flag
        echo "--cooldown-fixed (or --cooldown) must be a whole number of seconds" >&2
        exit 2
    end
    set cooldown $fixed_flag
    set cooldown_mode fixed
end
set -l cooldown_temp 33.0
if set -q _flag_cooldown_temp
    if not string match -qr '^[0-9]+(\.[0-9]+)?$' -- $_flag_cooldown_temp
        echo "--cooldown-temp must be degrees Celsius, for example 33 or 33.5" >&2
        exit 2
    end
    set cooldown_temp $_flag_cooldown_temp
end
# The battery service reports tenths of a degree, so the limit is compared in tenths.
set -l cooldown_limit (math "round($cooldown_temp * 10)")
set -l cooldown_max 120
if set -q _flag_cooldown_max
    if not string match -qr '^[0-9]+$' -- $_flag_cooldown_max
        echo "--cooldown-max must be a whole number of seconds" >&2
        exit 2
    end
    set cooldown_max $_flag_cooldown_max
end
set -l selected $all_names
set -q _flag_quick; and set selected $quick_names
if set -q _flag_scenario; and test "$_flag_scenario" = all
    set selected $all_names
else if set -q _flag_scenario
    set selected (string split ',' -- $_flag_scenario)
    for name in $selected
        if not contains -- $name $all_names
            echo "Unknown scenario '$name'. Scenarios: $all_names" >&2
            exit 2
        end
    end
end

source (status dirname)/device-info.fish
or exit 1

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

# Prints the battery temperature in tenths of a degree Celsius, or nothing if it cannot be read.
function battery_tenths --argument-names serial
    adb -s $serial shell dumpsys battery 2>/dev/null \
        | string match -rg '^\s*temperature:\s*(-?[0-9]+)\s*$' | head -n 1
end

function tenths_c --argument-names tenths
    printf '%.1f' (math "$tenths / 10")
end

# Waits before a scenario: a fixed pause, or until the battery is at or below the limit (tenths)
# for at most max seconds. A failed temperature read falls back to the fixed pause.
function cool_down --argument-names serial mode fixed limit max
    if test $mode = fixed
        if test $fixed -gt 0
            echo "Cooling down for $fixed s."
            sleep $fixed
        end
        return
    end
    set -l temp (battery_tenths $serial)
    if not string match -qr '^-?[0-9]+$' -- "$temp"
        echo "Could not read the battery temperature; cooling down for $fixed s." >&2
        sleep $fixed
        return
    end
    if test $temp -le $limit
        echo "Battery "(tenths_c $temp)" C, at or below "(tenths_c $limit)" C; no cooldown."
        return
    end
    echo "Battery "(tenths_c $temp)" C, above "(tenths_c $limit)" C; waiting up to $max s."
    set -l waited 0
    while test $temp -gt $limit
        if test $waited -ge $max
            echo "Warning: battery still "(tenths_c $temp)" C after $max s; continuing." >&2
            return
        end
        set -l step (math "min(5, $max - $waited)")
        sleep $step
        set waited (math $waited + $step)
        set temp (battery_tenths $serial)
        if not string match -qr '^-?[0-9]+$' -- "$temp"
            echo "Could not read the battery temperature; cooling down for $fixed s." >&2
            sleep $fixed
            return
        end
    end
end

# 4. One output folder per invocation, with the phone's state recorded before the first scenario.
set -l run_id (date +%Y%m%d-%H%M%S)
set -l device_root /sdcard/Android/media/$test_id/perf/$run_id
set -l local_dir $repo/perf-output/$run_id
mkdir -p $local_dir; or exit 1
set -l device_members (perf_device_members $serial)
set -l commit_members (perf_commit_members $repo)
set -l cooldown_members "  \"cooldown_mode\": \"$cooldown_mode\"," \
    "  \"cooldown_temp_c\": "(tenths_c $cooldown_limit)"," "  \"cooldown_max_s\": $cooldown_max,"
set -l battery_start (perf_battery_now $serial)

function write_device_json --argument-names file run_id mode frames iterations cooldown battery_start battery_end
    # Remaining arguments: member lines, then "--", selected scenarios, "--", failed scenarios.
    set -l rest $argv[9..]
    set -l split_at (contains -i -- -- $rest)
    set -l members $rest[1..(math $split_at - 1)]
    set -l names $rest[(math $split_at + 1)..]
    set -l failed
    set -l second (contains -i -- -- $names)
    if test -n "$second"
        set failed $names[(math $second + 1)..]
        set names $names[1..(math $second - 1)]
    end
    begin
        echo '{'
        echo '  "schema": 1,'
        echo "  \"run_id\": \"$run_id\","
        echo "  \"compilation\": \"$mode\","
        echo "  \"frame_source\": \"$frames\","
        echo "  \"iterations_override\": $iterations,"
        echo "  \"cooldown_s\": $cooldown,"
        echo "  \"scenarios\": "(perf_json_list $names)","
        echo "  \"failed_scenarios\": "(perf_json_list $failed)","
        printf '%s\n' $members
        echo "  \"battery_start\": $battery_start,"
        echo "  \"battery_end\": $battery_end"
        echo '}'
    end >$file
end
write_device_json $local_dir/device.json $run_id $mode $frame_source $iterations_json $cooldown \
    $battery_start null $cooldown_members $commit_members $device_members -- $selected --
echo "Wrote $local_dir/device.json."

# 5. Run each scenario as its own instrumentation, so each gets its own result file.
set -l failed
set -l position 0
for name in $selected
    set position (math $position + 1)
    set -l class
    for row in $scenario_table
        set -l parts (string split ' ' -- $row)
        test $parts[1] = $name; and set class $parts[2]
    end
    if test $position -gt 1
        cool_down $serial $cooldown_mode $cooldown $cooldown_limit $cooldown_max
    end
    echo "Running scenario $position/"(count $selected)": $name ($class, $mode)."
    set -l device_dir $device_root/$name
    set -l log $local_dir/$name-instrumentation.txt
    adb -s $serial shell am instrument -w \
        -e class "$test_id.$class#$name" \
        -e additionalTestOutputDir $device_dir \
        -e synthx.compilation $mode \
        -e synthx.frames $frame_source \
        $iteration_args \
        $compile_args \
        $runner >$log 2>&1
    set -l ok 1
    if not string match -qr '^OK \(' -- (cat $log)
        set ok 0
        set -a failed $name
    end
    # Pull results (JSON + Perfetto traces) even after a failure, for diagnosis.
    set -l staging $local_dir/.pull-$name
    mkdir -p $staging
    adb -s $serial pull $device_dir/. $staging >/dev/null 2>&1
    for file in $staging/*
        if string match -q '*-benchmarkData.json' -- $file
            mv $file $local_dir/$name-benchmarkData.json
        else
            mv -n $file $local_dir/
        end
    end
    rmdir $staging 2>/dev/null
    # A run can report OK while the library dropped iterations that lacked a metric.
    if test $ok -eq 1; and command -q python3
        if not python3 $repo/scripts/perf/check_result.py $local_dir/$name-benchmarkData.json
            set ok 0
            set -a failed $name
        end
    end
    if test $ok -eq 1
        echo "Scenario $name done."
    else
        echo "Scenario $name FAILED; see $log" >&2
    end
end

# 6. Record the end state, so thermal drift across the run is visible.
write_device_json $local_dir/device.json $run_id $mode $frame_source $iterations_json $cooldown \
    $battery_start (perf_battery_now $serial) $cooldown_members $commit_members $device_members -- $selected -- $failed
if test (count $failed) -gt 0
    echo "Failed scenarios: $failed. Results so far: $local_dir" >&2
    exit 1
end
echo "Results: $local_dir"
echo "Next: python3 scripts/perf/summarize.py $local_dir"
