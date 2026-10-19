#!/bin/bash
# One local gate: ktlint, compile (warnings are errors), Lint, unit tests. Stops at the first failure.
# Prints one line per step and the failing tool's output only on failure. No devices, no git writes.
set -u

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
# shellcheck source=scripts/ktlint-setup.sh
source scripts/ktlint-setup.sh

LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/synthx-check.XXXXXX")" || exit 1
MAX_FAIL_LINES=200

run_step() {
    local name="$1"
    shift
    local log="$LOG_DIR/$name.log"
    local start=$SECONDS
    "$@" >"$log" 2>&1
    local code=$?
    local took=$((SECONDS - start))
    if [ "$code" -eq 0 ]; then
        echo "ok $name (${took}s)"
        return 0
    fi
    echo "FAIL $name (exit $code, ${took}s)"
    # Drop Gradle's per-task progress lines; keep the failed task and the tool's own messages.
    local filtered="$log.filtered"
    grep -Ev '^> Task [^ ]+( UP-TO-DATE| NO-SOURCE| FROM-CACHE| SKIPPED)?$' "$log" >"$filtered"
    log="$filtered"
    local lines
    lines=$(wc -l <"$log")
    if [ "$lines" -gt "$MAX_FAIL_LINES" ]; then
        tail -n "$MAX_FAIL_LINES" "$log"
        echo "... showing the last $MAX_FAIL_LINES of $lines lines; log kept at: $log"
    else
        cat "$log"
        rm -rf "$LOG_DIR"
    fi
    exit 1
}

ktlint_check() {
    ktlint_prepare || return 1
    ktlint_run "app/src/**/*.kt" "benchmark/src/**/*.kt" "*.kts" "app/*.kts" "benchmark/*.kts" --relative
}

run_step ktlint ktlint_check
run_step compile ./gradlew --console=plain \
    :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin \
    :benchmark:compileBenchmarkKotlin
run_step lint ./gradlew --console=plain :app:lintDebug
run_step tests ./gradlew --console=plain :app:testDebugUnitTest

rm -rf "$LOG_DIR"
echo "all checks passed"
