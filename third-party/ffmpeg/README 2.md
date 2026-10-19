# FFmpeg audio extension provenance and rebuilding

This directory distributes the exact, unmodified FFmpeg 6.0.1 release source used by
TorrFrame's Media3 FFmpeg **audio** extension. It does not replace ExoPlayer.

- FFmpeg source: https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz
- Source SHA-256: `9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623`
- License: LGPL 2.1 or later. `COPYING.LGPLv2.1` is in the source archive and the app's license screen.
- No FFmpeg source patches. GPL, nonfree, version3 components and external codec libraries are disabled.
- Only avcodec, avutil and swresample; internal audio decoders:
  vorbis, opus, flac, alac, pcm_mulaw, pcm_alaw, mp1, mp2, mp3, amrnb, amrwb, aac, ac3,
  eac3, dca (DTS), mlp, truehd. This does not add software video codecs or AC-4.
- JNI and four renderer Java files are unmodified AndroidX Media3 tag `1.11.1`,
  commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`, Apache 2.0.
- Just Player reference commit: `c593ac3f5ca0784d06f424984abf8650f5bd1f8b` (Unlicense).
  No Just Player AARs, custom ExoPlayer builds, UI or assets are shipped.

## Build

Install Android NDK `29.0.14206865` using Android SDK tools, then from the project root:

```sh
scripts/build-player-ffmpeg.sh
scripts/build-local.sh :app:assembleDebug
```

`ANDROID_NDK_HOME` and `ANDROID_SDK_ROOT` may override the toolchain locations.
`FFMPEG_BUILD_DIR` must not contain spaces; the default is `~/.cache/torrframe-player-ffmpeg`.
The script verifies the source archive, builds both ARMv7 and ARM64 with API 26,
links the Media3 JNI bridge, and writes replaceable `libffmpegJNI.so` files in
`android-tv/player-ffmpeg/src/main/jniLibs`. Link alignment is 16 KiB. No separate
C++ runtime library or external codec runtime is required.

`config-armeabi-v7a.h` and `config-arm64-v8a.h` record the complete configurations
of the distributed build. Local tool paths in these configuration records are
build provenance, not runtime paths. The script itself is the portable build recipe.

For modifications, rebuild the library and repackage the APK with the replacement
`lib/<ABI>/libffmpegJNI.so`, then sign the APK using your own Android signing key.
Alternatively build the application normally with the replacement module. Update
the module's intentional binary checksums when adopting a changed build. Users
may relink/repackage and reverse engineer for debugging library modifications
as permitted by LGPL 2.1 section 6. Keep the sources, notices and build recipe
with redistributed binaries; a link to an unrelated FFmpeg release is insufficient.
The accompanying player-source archive includes these inputs and the JNI/Java module.
