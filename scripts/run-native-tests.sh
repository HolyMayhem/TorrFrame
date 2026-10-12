#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_GO=${GO:-"$TASK_ROOT/.tools/go/bin/go"}
[ -x "$TASK_GO" ] || { echo 'Install Go 1.27+ or set GO=/absolute/path/to/go. Go is used only by the local test fixture.'; exit 1; }
mkdir -p "$TASK_ROOT/.tools/fixture"
xcrun swift "$TASK_ROOT/scripts/test-fixture/generate-video.swift" "$TASK_ROOT/.tools/fixture/video.mp4"
python3 "$TASK_ROOT/scripts/test-fixture/make-torrent.py" "$TASK_ROOT/.tools/fixture/video.mp4" "$TASK_ROOT/android-tv/app/src/androidTest/assets/fixture.torrent"
GOPATH="$TASK_ROOT/.tools/go-cache" "$TASK_GO" -C "$TASK_ROOT/scripts/test-fixture" build -o "$TASK_ROOT/.tools/seed"
python3 "$TASK_ROOT/scripts/test-fixture/tracker.py" > "$TASK_ROOT/.tools/tracker-test.log" 2>&1 &
TASK_TRACKER=$!
"$TASK_ROOT/.tools/seed" "$TASK_ROOT/.tools/fixture" "$TASK_ROOT/android-tv/app/src/androidTest/assets/fixture.torrent" > "$TASK_ROOT/.tools/seed-test.log" 2>&1 &
TASK_SEED=$!
trap 'kill "$TASK_TRACKER" "$TASK_SEED" 2>/dev/null || true' EXIT INT TERM
TASK_WAIT=0
while ! rg -q 'fixture ready' "$TASK_ROOT/.tools/seed-test.log"; do
    TASK_WAIT=$((TASK_WAIT+1))
    kill -0 "$TASK_TRACKER" "$TASK_SEED" 2>/dev/null || { echo 'Fixture failed to start. Check the ports 18765 and 18766 and .tools/*-test.log'; exit 1; }
    [ "$TASK_WAIT" -lt 30 ] || { echo 'Fixture startup timed out'; exit 1; }
    sleep 1
done
"$TASK_ROOT/scripts/build-local.sh" :app:connectedDebugAndroidTest
