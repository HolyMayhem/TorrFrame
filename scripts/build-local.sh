#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
supports_gradle() {
    [ -x "$1" ] || return 1
    version=$("$1" -version 2>&1) || return 1
    major=$(printf '%s\n' "$version" | awk -F '"' '/version "/ { split($2, parts, "."); print parts[1]; exit }')
    [ "${major:-0}" -ge 17 ] 2>/dev/null
}

if [ -n "${JAVA_HOME:-}" ]; then
    supports_gradle "$JAVA_HOME/bin/java" || { echo 'JAVA_HOME must point to a working JDK 17 or newer.' >&2; exit 1; }
else
    gradle_jdks="${GRADLE_USER_HOME:-$HOME/.gradle}/jdks"
    for task_jdk in "$TASK_ROOT"/.tools/jdk/*/Contents/Home "$gradle_jdks"/*/*/Contents/Home "$gradle_jdks"/*; do
        if supports_gradle "$task_jdk/bin/java"; then
            export JAVA_HOME="$task_jdk"
            break
        fi
    done
    if [ -z "${JAVA_HOME:-}" ] && ! supports_gradle "$(command -v java || true)"; then
        echo 'Set JAVA_HOME to a working JDK 17 or newer.' >&2
        exit 1
    fi
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$TASK_ROOT/.tools/sdk" ]; then export ANDROID_HOME="$TASK_ROOT/.tools/sdk"; fi
cd "$TASK_ROOT/android-tv"
exec ./gradlew "$@"
