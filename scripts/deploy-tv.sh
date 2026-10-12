#!/usr/bin/env bash
# Build for the selected TV's ABI, install without clearing data, and launch.
set -euo pipefail
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET_FILE="$TASK_ROOT/.tools/tv-device"
TARGET=${TV_SERIAL:-}
BUILD=1
VARIANT=debug
MODE=deploy
fail() { printf '%s\n' "$*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
    case "$1" in
        --device) [ "$#" -ge 2 ] || fail 'Missing serial after --device'; TARGET=$2; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --preview) VARIANT=release; shift ;;
        --logs) MODE=logs; shift ;;
        --help|-h)
            printf '%s\n' 'Usage: scripts/deploy-tv.sh [--device SERIAL] [--no-build] [--preview] [--logs]' \
                'Default: incremental Debug build, install -r, then launch on the saved TV.' \
                '--no-build installs the previous APK for this ABI; --preview uses R8 Release.' \
                '--logs follows only the running app process; Ctrl+C stops logging.'
            exit 0 ;;
        *) fail "Unknown option: $1" ;;
    esac
done

TASK_ADB=${ADB:-"$TASK_ROOT/.tools/sdk/platform-tools/adb"}
if [ ! -x "$TASK_ADB" ]; then
    TASK_ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
fi
if [ ! -x "$TASK_ADB" ]; then TASK_ADB=$(command -v adb || true); fi
[ -n "$TASK_ADB" ] && [ -x "$TASK_ADB" ] || fail 'ADB not found. Set ADB or install Android SDK Platform Tools.'
command -v python3 >/dev/null || fail 'Python 3 is required to read Gradle APK metadata.'

if [ -z "$TARGET" ] && [ -f "$TARGET_FILE" ]; then TARGET=$(cat "$TARGET_FILE"); fi
if [ -z "$TARGET" ]; then
    DEVICES=$("$TASK_ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ { print $1 }')
    [ "$(printf '%s\n' "$DEVICES" | awk 'NF {n++} END {print n+0}')" -eq 1 ] \
        || fail 'Choose one physical TV with --device SERIAL; emulators are never selected automatically.'
    TARGET=$DEVICES
fi
printf 'Connecting to %s…\n' "$TARGET"
if [ "$("$TASK_ADB" -s "$TARGET" get-state 2>/dev/null || true)" != device ]; then
    case "$TARGET" in *:*) "$TASK_ADB" connect "$TARGET" ;; esac
fi
[ "$("$TASK_ADB" -s "$TARGET" get-state 2>/dev/null || true)" = device ] \
    || fail 'TV is offline or unauthorized. Check its address and accept the ADB prompt on the TV.'
mkdir -p "$(dirname -- "$TARGET_FILE")"
printf '%s\n' "$TARGET" > "$TARGET_FILE"
APP_ID=tv.torrserve.silicon

if [ "$MODE" = logs ]; then
    PID=$("$TASK_ADB" -s "$TARGET" shell pidof "$APP_ID" | tr -d '\r' | awk '{print $1}' || true)
    [ -n "$PID" ] || fail 'The app is not running. Deploy it first.'
    printf 'Following app PID %s on %s. Stop with Ctrl+C; restart logs after redeploy.\n' "$PID" "$TARGET"
    exec "$TASK_ADB" -s "$TARGET" logcat --pid="$PID" -v threadtime
fi

ABILIST=$("$TASK_ADB" -s "$TARGET" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$ABILIST," in
    *,arm64-v8a,*) ABI=arm64-v8a ;;
    *,armeabi-v7a,*) ABI=armeabi-v7a ;;
    *) fail "Unsupported TV ABI: $ABILIST. This app supports ARM64 and ARMv7." ;;
esac
SDK=$("$TASK_ADB" -s "$TARGET" shell getprop ro.build.version.sdk | tr -d '\r')
case "$SDK" in ''|*[!0-9]*) fail 'Could not read TV Android API level.' ;; esac
[ "$SDK" -ge 26 ] || fail 'Android 8.0 / API 26 or newer is required.'
if [ "$(uname -s)" = Darwin ]; then
    DEFAULT_BUILD_ROOT="$HOME/Library/Caches/TorrServeSiliconAndroidTV/deploy/$ABI"
else
    DEFAULT_BUILD_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/TorrServeSiliconAndroidTV/deploy/$ABI"
fi
BUILD_ROOT=${TORRSERVE_DEPLOY_BUILD_ROOT:-$DEFAULT_BUILD_ROOT}
case "$BUILD_ROOT" in /*) ;; *) fail 'TORRSERVE_DEPLOY_BUILD_ROOT must be an absolute path.' ;; esac
STARTED=$SECONDS
if [ "$BUILD" -eq 1 ]; then
    printf 'Building %s for %s (Gradle daemon and incremental compilation)…\n' "$VARIANT" "$ABI"
    if [ "$VARIANT" = release ]; then TASK=:app:assembleRelease; else TASK=:app:assembleDebug; fi
    "$TASK_ROOT/scripts/build-local.sh" --daemon --max-workers=4 --project-cache-dir "$BUILD_ROOT/.gradle" "$TASK" \
        "-PtorrserveAbi=$ABI" "-PtorrserveBuildRoot=$BUILD_ROOT" -PpreviewSigning=true
fi
METADATA="$BUILD_ROOT/app/outputs/apk/$VARIANT/output-metadata.json"
[ -f "$METADATA" ] || fail 'No APK for this ABI/variant. Run without --no-build first.'
APK=$(python3 - "$METADATA" "$APP_ID" "$ABI" "$VARIANT" <<'PY'
import json, sys
from pathlib import Path
from zipfile import ZipFile
metadata = Path(sys.argv[1])
data = json.loads(metadata.read_text())
assert data['applicationId'] == sys.argv[2], 'Unexpected application ID'
assert data['variantName'] == sys.argv[4], 'Unexpected APK variant'
assert len(data['elements']) == 1, 'Expected one APK'
apk = (metadata.parent / data['elements'][0]['outputFile']).resolve()
assert apk.parent == metadata.parent.resolve() and apk.is_file(), 'Invalid APK output path'
with ZipFile(apk) as archive:
    assert f'lib/{sys.argv[3]}/libtorrserver.so' in archive.namelist(), 'TorrServer ABI mismatch'
print(apk)
PY
)
printf 'Installing %s on %s; application data is retained…\n' "$ABI" "$TARGET"
"$TASK_ADB" -s "$TARGET" install -r "$APK" \
    || fail 'Installation failed. Check storage, version and signing certificate; no data was cleared.'
"$TASK_ADB" -s "$TARGET" shell input keyevent KEYCODE_WAKEUP
"$TASK_ADB" -s "$TARGET" shell am force-stop "$APP_ID"
LAUNCH=$("$TASK_ADB" -s "$TARGET" shell am start -W -n "$APP_ID/.MainActivity")
printf '%s\n' "$LAUNCH"
printf '%s\n' "$LAUNCH" | tr -d '\r' | grep -q '^Status: ok$' || fail 'Android could not launch the application.'
printf 'Ready on %s · %s · %s · total %ss\n' "$TARGET" "$ABI" "$VARIANT" "$((SECONDS - STARTED))"
