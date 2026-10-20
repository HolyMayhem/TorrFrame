#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-"$TASK_ROOT/.tools/sdk"}
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
EMULATOR_GPU=swiftshader
# Qt plugin discovery fails when this macOS emulator lives in a path with spaces.
if [ "$(uname -s)" = Darwin ]; then
    for TASK_EMULATOR_DIR in "$HOME/Library/Android/torrframe"; do
        if [ -x "$TASK_EMULATOR_DIR/emulator/emulator" ]; then
            EMULATOR_BIN="$TASK_EMULATOR_DIR/emulator/emulator"
            EMULATOR_GPU=host
            break
        fi
    done
fi
TASK_AVD=${TORRFRAME_AVD:-TorrFrame_TV_API36}
# Reuse the existing development device and its data after the app rename.
if [ -z "${TORRFRAME_AVD:-}" ]; then
    TASK_AVAILABLE_AVDS=$("$EMULATOR_BIN" -list-avds)
    if ! printf '%s\n' "$TASK_AVAILABLE_AVDS" | grep -Fxq "$TASK_AVD" &&
        printf '%s\n' "$TASK_AVAILABLE_AVDS" | grep -Fxq TorrServe_TV_API36; then
        TASK_AVD=TorrServe_TV_API36
    fi
fi
exec "$EMULATOR_BIN" -avd "$TASK_AVD" -gpu "$EMULATOR_GPU" -memory 2048 -cores 4 -use-keycode-forwarding -crash-report-mode disabled "$@"
