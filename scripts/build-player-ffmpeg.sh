#!/bin/bash
# Rebuild the LGPL audio library from the exact source distributed with this project.
set -euo pipefail
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_SDK=${ANDROID_SDK_ROOT:-"$TASK_ROOT/.tools/sdk"}
TASK_NDK=${ANDROID_NDK_HOME:-"$TASK_SDK/ndk/29.0.14206865"}
TASK_HOST=darwin-x86_64
[ "$(uname -s)" != Linux ] || TASK_HOST=linux-x86_64

TASK_SOURCE="$TASK_ROOT/third-party/ffmpeg/ffmpeg-6.0.1.tar.xz"
TASK_WORK=${FFMPEG_BUILD_DIR:-"$HOME/.cache/torrframe-player-ffmpeg"}
mkdir -p "$TASK_WORK"
# FFmpeg configure cannot tokenize toolchain paths containing spaces.
ln -sfn "$TASK_NDK" "$TASK_WORK/ndk"
TASK_BIN="$TASK_WORK/ndk/toolchains/llvm/prebuilt/$TASK_HOST/bin"
python3 - "$TASK_SOURCE" <<'PY'
import hashlib,sys
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()=='9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623', 'FFmpeg source hash mismatch'
PY
[ -x "$TASK_BIN/clang" ] || { echo "Install NDK 29.0.14206865, or set ANDROID_NDK_HOME"; exit 1; }
mkdir -p "$TASK_WORK"
tar -xf "$TASK_SOURCE" -C "$TASK_WORK"
TASK_FFMPEG="$TASK_WORK/ffmpeg-6.0.1"
TASK_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp1 mp2 mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
TASK_COMMON=(--target-os=android --enable-cross-compile --enable-static --disable-shared --enable-pic
 --disable-gpl --disable-nonfree --disable-version3 --disable-doc --disable-programs --disable-everything
 --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
 --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan
 --nm="$TASK_BIN/llvm-nm" --ar="$TASK_BIN/llvm-ar" --ranlib="$TASK_BIN/llvm-ranlib" --strip="$TASK_BIN/llvm-strip")
for TASK_DECODER in "${TASK_DECODERS[@]}"; do TASK_COMMON+=(--enable-decoder="$TASK_DECODER"); done
for TASK_ABI in armeabi-v7a arm64-v8a; do
 TASK_OUT="$TASK_WORK/$TASK_ABI"
 mkdir -p "$TASK_OUT"
 if [ "$TASK_ABI" = arm64-v8a ]; then
  TASK_TRIPLE=aarch64-linux-android; TASK_ARCH=aarch64; TASK_CPU=armv8-a
 else
  TASK_TRIPLE=armv7a-linux-androideabi; TASK_ARCH=arm; TASK_CPU=armv7-a
 fi
 (
 cd "$TASK_OUT"
 "$TASK_FFMPEG/configure" "${TASK_COMMON[@]}" --arch="$TASK_ARCH" --cpu="$TASK_CPU" \
  --cc="$TASK_BIN/${TASK_TRIPLE}26-clang" --cxx="$TASK_BIN/${TASK_TRIPLE}26-clang++" \
  --extra-cflags="-O2 -fPIC" --extra-ldflags="-Wl,-z,max-page-size=16384"
 make -j"${FFMPEG_JOBS:-4}"
 )
 TASK_DEST="$TASK_ROOT/android-tv/player-ffmpeg/src/main/jniLibs/$TASK_ABI"
 mkdir -p "$TASK_DEST"
 "$TASK_BIN/${TASK_TRIPLE}26-clang++" -std=c++11 -O2 -fPIC -shared -fvisibility=hidden \
  -I"$TASK_OUT" -idirafter "$TASK_FFMPEG" \
  "$TASK_ROOT/android-tv/player-ffmpeg/src/main/jni/ffmpeg_jni.cc" \
  -Wl,--start-group "$TASK_OUT/libswresample/libswresample.a" "$TASK_OUT/libavcodec/libavcodec.a" "$TASK_OUT/libavutil/libavutil.a" -Wl,--end-group \
  -Wl,-z,max-page-size=16384 -Wl,-Bsymbolic -static-libstdc++ -landroid -llog -lm -lz -o "$TASK_DEST/libffmpegJNI.so"
 "$TASK_BIN/llvm-strip" --strip-unneeded "$TASK_DEST/libffmpegJNI.so"
 cp "$TASK_OUT/config.h" "$TASK_ROOT/third-party/ffmpeg/config-$TASK_ABI.h"
done
