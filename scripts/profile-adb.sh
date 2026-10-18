#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_ADB=${ADB:-"$TASK_ROOT/.tools/sdk/platform-tools/adb"}
TASK_OUT="$TASK_ROOT/artifacts/profile"
mkdir -p "$TASK_OUT"
"$TASK_ADB" shell am force-stop tv.torrserve.silicon
"$TASK_ADB" logcat -c
"$TASK_ADB" shell am start -W -n tv.torrserve.silicon/.MainActivity > "$TASK_OUT/cold-start.txt"
sleep 3
"$TASK_ADB" shell dumpsys meminfo tv.torrserve.silicon > "$TASK_OUT/app-memory.txt"
TASK_PID=$("$TASK_ADB" shell run-as tv.torrserve.silicon cat files/torrserver/managed.pid 2>/dev/null | tr -d '\r' || true)
case "$TASK_PID" in ''|*[!0-9]*)
    TASK_APP_PID=$("$TASK_ADB" shell pidof tv.torrserve.silicon | tr -d '\r')
    TASK_PID=$("$TASK_ADB" shell ps -A -o PID,PPID,NAME | awk -v app="$TASK_APP_PID" '$2==app && $3 ~ /^libtorrserver/ {print $1;exit}')
esac
case "$TASK_PID" in ''|*[!0-9]*) echo 'No verified server PID available'; exit 1;; esac
"$TASK_ADB" shell cat "/proc/$TASK_PID/status" > "$TASK_OUT/server-memory.txt" || "$TASK_ADB" shell dumpsys meminfo "$TASK_PID" > "$TASK_OUT/server-memory.txt"
"$TASK_ADB" shell dumpsys gfxinfo tv.torrserve.silicon framestats > "$TASK_OUT/frames.txt"
"$TASK_ADB" shell input keyevent KEYCODE_BACK
"$TASK_ADB" shell am start -W -n tv.torrserve.silicon/.MainActivity > "$TASK_OUT/warm-start.txt"
"$TASK_ADB" logcat -d -s TorrFrameTiming:I > "$TASK_OUT/timings.txt"
echo "Measurements: $TASK_OUT"
