#!/bin/bash
# Formats the Kotlin files changed since HEAD (tracked changes plus untracked) with the pinned ktlint, then
# checks them. Exit 0 when they are clean afterwards. Read-only git; never stages or commits.
set -u

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
# shellcheck source=scripts/ktlint-setup.sh
source scripts/ktlint-setup.sh

files=()
while IFS= read -r -d '' f; do
    [ -f "$f" ] && files+=("$f")
done < <(
    git diff -z --name-only --diff-filter=d HEAD -- '*.kt' '*.kts'
    git ls-files -z --others --exclude-standard -- '*.kt' '*.kts'
)

if [ "${#files[@]}" -eq 0 ]; then
    echo "ok format (no changed Kotlin files)"
    exit 0
fi

ktlint_prepare || exit 1
# -F exits 1 even when it fixed everything; only the check below decides the result.
ktlint_run -F --relative "${files[@]}" >/dev/null 2>&1
out="$(ktlint_run --relative "${files[@]}" 2>&1)"
code=$?
if [ "$code" -eq 0 ]; then
    echo "ok format (${#files[@]} files)"
else
    echo "FAIL format: violations remain that ktlint cannot fix (exit $code)"
    printf '%s\n' "$out"
fi
exit "$code"
