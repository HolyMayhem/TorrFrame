#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-"$TASK_ROOT/.tools/sdk"}
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
EMULATOR_GPU=swiftshader
# Qt plugin discovery fails when this macOS emulator lives in a path with spaces.
if [ "$(uname -s)" = Darwin ] && [ -x "$HOME/Library/Android/torrserve-silicon/emulator/emulator" ]; then
    EMULATOR_BIN="$HOME/Library/Android/torrserve-silicon/emulator/emulator"
    EMULATOR_GPU=host
fi
exec "$EMULATOR_BIN" -avd TorrServe_TV_API36 -gpu "$EMULATOR_GPU" -memory 2048 -cores 4 -use-keycode-forwarding -crash-report-mode disabled "$@"
