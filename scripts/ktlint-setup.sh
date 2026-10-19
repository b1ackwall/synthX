# Sourced by check.sh and format.sh (not run on its own). Provides ktlint_run, backed by the pinned
# ktlint 1.8.0 standalone jar kept outside the repo and /tmp; downloaded and sha256-verified when missing or wrong.

KTLINT_VERSION=1.8.0
KTLINT_SHA256=a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75
KTLINT_URL="https://github.com/ktlint/ktlint/releases/download/${KTLINT_VERSION}/ktlint"
KTLINT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/synthx-tools/ktlint-${KTLINT_VERSION}"
KTLINT_JAR="$KTLINT_DIR/ktlint"

_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

_find_java() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_BIN="$(command -v java)"
    else
        echo "ktlint needs Java 11+: set JAVA_HOME or put java on PATH" >&2
        return 1
    fi
}

ktlint_prepare() {
    _find_java || return 1
    if [ -f "$KTLINT_JAR" ] && [ "$(_sha256 "$KTLINT_JAR")" = "$KTLINT_SHA256" ]; then
        return 0
    fi
    echo "ktlint ${KTLINT_VERSION} missing or sha256 mismatch at $KTLINT_JAR; downloading $KTLINT_URL" >&2
    mkdir -p "$KTLINT_DIR" || return 1
    local tmp="$KTLINT_JAR.download.$$"
    if ! curl -fsSL --retry 2 -o "$tmp" "$KTLINT_URL"; then
        rm -f "$tmp"
        echo "ktlint download failed from $KTLINT_URL" >&2
        return 1
    fi
    local got
    got="$(_sha256 "$tmp")"
    if [ "$got" != "$KTLINT_SHA256" ]; then
        rm -f "$tmp"
        echo "ktlint sha256 mismatch: expected $KTLINT_SHA256, downloaded file has $got" >&2
        return 1
    fi
    chmod +x "$tmp" && mv -f "$tmp" "$KTLINT_JAR"
}

ktlint_run() {
    "$JAVA_BIN" -jar "$KTLINT_JAR" "$@"
}
