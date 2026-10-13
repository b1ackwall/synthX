#!/usr/bin/env fish
# Checks device-info.fish's parsers without a phone: fish scripts/perf/tests/test_device_info.fish
# The dumpsys samples are hand-written in the two layouts the parser handles, not captures.

source (status dirname)/../device-info.fish

set -g failures 0
function expect --argument-names what actual expected
    if test "$actual" != "$expected"
        echo "FAIL $what: got '$actual', expected '$expected'"
        set -g failures (math $failures + 1)
    end
end

expect "string" (perf_json_str 'SM-S938U') '"SM-S938U"'
expect "string escapes" (perf_json_str 'a"b\\c') '"a\\"b\\\\c"'
expect "empty string" (perf_json_str '') null
expect "settings null" (perf_json_str null) null
expect "number" (perf_json_num 120.0) 120.0
expect "not a number" (perf_json_num abc) null
expect "list" (perf_json_list a b) '["a", "b"]'
expect "empty list" (perf_json_list) '[]'

set -l modern '  mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1440 x 3120, mode 1, renderFrameRate 60.000004, defaultModeId 1, supportedModes [{id=1, width=1440, height=3120, fps=120.0}]}'
expect "render frame rate" (echo $modern | perf_parse_refresh_hz) "60.000004 dumpsys display renderFrameRate"

set -l older '  mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1080 x 2340, mode 2, defaultMode 1, modes [{id=1, width=1080, height=2340, fps=60.0}, {id=2, width=1080, height=2340, fps=90.0}]}'
expect "active mode fps" (echo $older | perf_parse_refresh_hz) "90.0 dumpsys display active mode fps"
expect "no display info" (echo 'nothing useful' | perf_parse_refresh_hz) ""

set -l battery (printf '%s\n' 'Current Battery Service state:' '  AC powered: false' '  USB powered: true' '  status: 2' '  level: 87' '  temperature: 312')
expect "battery" (printf '%s\n' $battery | perf_battery_json) '{"level": 87, "temperature_c": 31.2, "status": "charging", "plugged": true}'
expect "battery unreadable" (echo '' | perf_battery_json) '{"level": null, "temperature_c": null, "status": null, "plugged": false}'

if test $failures -gt 0
    echo "$failures check(s) failed"
    exit 1
end
echo "device-info.fish: all checks passed"
