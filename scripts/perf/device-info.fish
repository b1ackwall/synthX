# Functions for run-benchmark.fish: reads the phone's state and writes device.json.
# Sourcing this file runs nothing. The parsers read command output on stdin so they can be
# checked without a phone (scripts/perf/tests/test_device_info.fish).

# Prints a JSON string, or null for an empty value or the settings command's "null".
function perf_json_str
    set -l value (string trim -- "$argv[1]")
    if test -z "$value"; or test "$value" = null
        echo -n null
        return
    end
    set value (string replace -a '\\' '\\\\' -- $value | string replace -a '"' '\\"')
    echo -n "\"$value\""
end

# Prints a JSON number, or null when the value is not numeric.
function perf_json_num
    set -l value (string trim -- "$argv[1]")
    if string match -qr '^-?[0-9]+(\.[0-9]+)?$' -- "$value"
        echo -n $value
    else
        echo -n null
    end
end

# Prints a JSON array of strings.
function perf_json_list
    set -l items
    for item in $argv
        set -a items (perf_json_str $item)
    end
    # Joined into a variable first: an empty command substitution would erase the brackets.
    set -l joined (string join ', ' $items)
    echo -n "[$joined]"
end

# stdin: `dumpsys display`. Prints "<hz> <source>" for the default display, or nothing.
# Tries the render frame rate first, then the fps of the active display mode.
function perf_parse_refresh_hz
    set -l info
    while read -l line
        if string match -q '*mBaseDisplayInfo=DisplayInfo{*' -- $line
            set info $line
            break
        end
    end
    test -n "$info"; or return 1
    set -l render (string match -r 'renderFrameRate ([0-9]+(?:\.[0-9]+)?)' -- $info)
    if test (count $render) -eq 2
        echo $render[2] "dumpsys display renderFrameRate"
        return 0
    end
    set -l mode (string match -r ', mode ([0-9]+),' -- $info)
    if test (count $mode) -eq 2
        set -l fps (string match -r "\\{id=$mode[2], [^}]*?fps=([0-9]+(?:\.[0-9]+)?)" -- $info)
        if test (count $fps) -eq 2
            echo $fps[2] "dumpsys display active mode fps"
            return 0
        end
    end
    return 1
end

# stdin: `dumpsys battery`. Prints a JSON object; fields it cannot find are null.
function perf_battery_json
    set -l level
    set -l temp
    set -l status_code
    set -l plugged false
    while read -l line
        set -l pair (string match -r '^\s*([A-Za-z ]+):\s*(\S+)' -- $line)
        test (count $pair) -eq 3; or continue
        switch $pair[2]
            case level
                set level $pair[3]
            case temperature
                set temp $pair[3]
            case status
                set status_code $pair[3]
            case 'AC powered' 'USB powered' 'Wireless powered' 'Dock powered'
                test $pair[3] = true; and set plugged true
        end
    end
    # The battery service reports tenths of a degree Celsius.
    set -l temp_c
    if string match -qr '^-?[0-9]+$' -- "$temp"
        set temp_c (math --scale=1 "$temp / 10")
    end
    set -l state
    switch "$status_code"
        case 2
            set state charging
        case 3
            set state discharging
        case 4
            set state not_charging
        case 5
            set state full
        case 1
            set state unknown
    end
    printf '{"level": %s, "temperature_c": %s, "status": %s, "plugged": %s}' \
        (perf_json_num $level) (perf_json_num $temp_c) (perf_json_str $state) $plugged
end

function perf_adb_value
    adb -s $argv[1] shell $argv[2..] 2>/dev/null | string trim | head -n 1
end

# Prints the "device", "refresh" and "brightness" members for device.json (no outer braces).
function perf_device_members --argument-names serial
    set -l not_parsed

    set -l hz
    set -l hz_source
    set -l parsed (adb -s $serial shell dumpsys display 2>/dev/null | perf_parse_refresh_hz)
    if test -n "$parsed"
        set -l parts (string split -m 1 ' ' -- $parsed)
        set hz $parts[1]
        set hz_source $parts[2]
    else
        set -a not_parsed "dumpsys display refresh rate"
    end
    set -l peak (perf_adb_value $serial settings get system peak_refresh_rate)
    set -l min (perf_adb_value $serial settings get system min_refresh_rate)
    string match -qr '^[0-9.]+$' -- "$peak"; or set -a not_parsed peak_refresh_rate
    string match -qr '^[0-9.]+$' -- "$min"; or set -a not_parsed min_refresh_rate
    # One UI keeps its motion smoothness choice here: 0 standard, 1 adaptive, 2 high.
    set -l samsung_mode (perf_adb_value $serial settings get secure refresh_rate_mode)

    set -l brightness_mode
    switch (perf_adb_value $serial settings get system screen_brightness_mode)
        case 0
            set brightness_mode manual
        case 1
            set brightness_mode automatic
        case '*'
            set -a not_parsed screen_brightness_mode
    end

    printf '  "device": {"manufacturer": %s, "model": %s, "device": %s, "api": %s, "release": %s, "fingerprint": %s},\n' \
        (perf_json_str (perf_adb_value $serial getprop ro.product.manufacturer)) \
        (perf_json_str (perf_adb_value $serial getprop ro.product.model)) \
        (perf_json_str (perf_adb_value $serial getprop ro.product.device)) \
        (perf_json_num (perf_adb_value $serial getprop ro.build.version.sdk)) \
        (perf_json_str (perf_adb_value $serial getprop ro.build.version.release)) \
        (perf_json_str (perf_adb_value $serial getprop ro.build.fingerprint))
    printf '  "refresh": {"hz": %s, "source": %s, "peak_refresh_rate": %s, "min_refresh_rate": %s, "samsung_refresh_rate_mode": %s, "not_parsed": %s},\n' \
        (perf_json_num $hz) (perf_json_str $hz_source) (perf_json_num $peak) (perf_json_num $min) \
        (perf_json_str $samsung_mode) (perf_json_list $not_parsed)
    printf '  "brightness": {"mode": %s, "level": %s},\n' \
        (perf_json_str $brightness_mode) \
        (perf_json_num (perf_adb_value $serial settings get system screen_brightness))
end

# Prints the battery object for one phone.
function perf_battery_now --argument-names serial
    adb -s $serial shell dumpsys battery 2>/dev/null | perf_battery_json
end

# Prints the "commit" and "commit_dirty" members; both null without git or a repository.
function perf_commit_members --argument-names repo
    set -l commit
    set -l dirty null
    if command -q git
        set commit (git -C $repo rev-parse HEAD 2>/dev/null)
        if test -n "$commit"
            set dirty false
            test -n "$(git -C $repo status --porcelain 2>/dev/null)"; and set dirty true
        end
    end
    printf '  "commit": %s,\n  "commit_dirty": %s,\n' (perf_json_str $commit) $dirty
end
