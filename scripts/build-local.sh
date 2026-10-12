#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$TASK_ROOT/.tools/jdk/jdk-17.0.20.1+1/Contents/Home" ]; then
    export JAVA_HOME="$TASK_ROOT/.tools/jdk/jdk-17.0.20.1+1/Contents/Home"
fi
if [ -d "$TASK_ROOT/.tools/sdk" ]; then export ANDROID_HOME="$TASK_ROOT/.tools/sdk"; fi
cd "$TASK_ROOT/android-tv"
exec ./gradlew "$@"
