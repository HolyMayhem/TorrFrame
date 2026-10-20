# Built-in player

The existing Media3 1.11.1 ExoPlayer remains the only engine, owned by
`PlaybackService : MediaSessionService`. There is no embedded Just Player app,
second ExoPlayer, WebView or copied Just Player UI.

## Architecture

- `PlaybackService`: renderer configuration, audio focus/noisy-output handling,
  MediaSession, foreground ownership, live speed/skip-silence/frame-rate options,
  the existing library checkpoint mechanism and TorrServer ownership. Late
  checkpoints skip removed titles atomically instead of failing a foreign-key constraint.
- `PlayerController`: MediaController presentation adapter, timeline, track selection,
  retry, seeking and external-subtitle media-item replacement with position and pause preservation.
- `PlayerActivity`: connects to the session, hosts the native video/subtitle surface,
  invokes the document picker, applies subtitle styling and restores the display mode.
- `PlayerScreen` / `PlayerMenus`: Compose TV controls, seek bar, D-pad focus, menus,
  five-second control auto-hide, optional fades and focus restoration after dialogs.
- `PlayerPreferences`: persisted playback-only values, validation and per-field merge.
  Uses the existing SettingsStore, including concurrent Settings/Phone Remote edits.
- `ExternalSubtitles`: SRT, ASS/SSA, VTT and TTML MIME mapping; bounded local file
  reads and UTF-8/UTF-16 handling, with Windows-1251 fallback for Russian UI.
- `FrameRateMatcher`: exact-cadence display modes at the current resolution,
  adapted from Just Player. Uses the already selected video format, without an
  extra network probe. Modern seamless surface hints are supplied by Media3.
- `:player-ffmpeg`: official Media3 FFmpeg audio renderer/JNI and our source-built
  replaceable FFmpeg library. No video engine or duplicate Media3 core in this module.

Appearance is provided by `TorrFrameTheme` and `AppLanguage` using **the same**
`Settings.uiAccent`, `uiTheme`, `uiScalePercent`, `animationsEnabled`, `uiLanguage`
as the rest of the app. Subtitle styling is separate from UI appearance. Native
video remains full-screen at all UI scales. Subtitles move above visible controls.

## Controls and features

- OK on Play/Pause toggles playback. Left/right on the progress bar seek 10 seconds;
  while controls are hidden, left/right seek and OK/up/down reveal the controls.
- Audio and subtitle menus show supported tracks and selected state; subtitles
  can be disabled. Return closes a menu and restores focus to its invoking button.
  Return from the player saves progress, stops playback and returns to the caller.
- External subtitles: system document picker, or explicit HTTP/HTTPS URL for TVs
  without a picker. Local files are copied into private cache (8 MB bound); stale
  copies older than seven days are removed when another file is attached.
- Subtitle text size, white/yellow text, background, bold, embedded styling.
  Media3's ASS styling support is limited; this does not implement libass.
- Speed 0.5–2×; fit/crop/stretch; silence skipping.
- Audio decoder priority: device first with FFmpeg fallback (default), or FFmpeg
  first. Changing priority applies to the **next playback session** so an active
  stream is not torn down to recreate its renderers.
- Display frame-rate matching is opt-in. It may briefly blank the TV and depends
  on hardware, supported modes and system settings. Unknown FPS or no matching
  cadence leaves the display mode unchanged. Mode preference is restored on exit
  and backgrounding; Android may still override the requested mode.
- Video uses device MediaCodec decoders with decoder fallback. We do not expose
  a fictitious “software video” mode: FFmpeg here decodes audio only. Experimental
  FFmpeg video, Dolby Vision rewrites, proprietary codecs and tunneling are not
  silently added. Supporting those would need separate hardware/format validation.
- Existing resume/audio/subtitle language preferences and external-player modes
  continue to work. Background playback remains owned by MediaSessionService.

## Licenses and sources

Just Player reference commit `c593ac3f5ca0784d06f424984abf8650f5bd1f8b` is Unlicense.
Media3 renderer/JNI source is Apache 2.0, FFmpeg is LGPL 2.1 or later with GPL,
nonfree and version3 disabled. License texts and attribution are available in
Settings → About → Licenses. Exact source, build config and relinking instructions:
[`third-party/ffmpeg/README.md`](../third-party/ffmpeg/README.md).

The release's source archive must accompany binary redistribution. It includes
the exact FFmpeg source archive, Java/JNI bridge, configurations and portable
rebuild recipe. No unverified prebuilt binaries from Just Player are distributed.

## Validation

`PlayerPreferencesTests` covers corrupt settings, concurrent edits, cadence,
subtitle formats/encoding and timeline formatting. `BuiltInPlayerIntegrationTest`
uses an original generated 30-second H.264 MKV with two AC-3 tracks and embedded
SRT; it checks actual FFmpeg playback, D-pad menus, external subtitles, resume,
speed/style persistence and global appearance. Fixture regeneration:

```sh
PLAYER_FIXTURE_FFMPEG=/path/to/host/ffmpeg scripts/test-fixture/generate-player-fixture.sh
```

`NativeIntegrationTest` checks the packaged TorrServer, real BitTorrent streaming,
checkpoints and playback in the background beyond the idle grace period.

Instrumentation should be installed manually with `adb install -r` and run with
`am instrument` to preserve owner data. AGP's connected task uninstalls the app.
Real display switching, HDMI passthrough/HDR, ARMv7 runtime and older Android
hardware require device verification; an ARM64 Android TV emulator cannot prove them.

Release playback is checked with a platform-only instrumentation runner, because
AndroidX Test's debug APK expects Kotlin classes before R8 renaming. It uses no
app internals and asserts a genuinely playing platform MediaSession:

```sh
scripts/build-local.sh :app:assembleDebugAndroidTest \
  -PplayerSmokeRunner=tv.torrframe.PlatformPlayerSmokeInstrumentation
adb install -r <preview-signed-release.apk>
adb install -r <app-debug-androidTest.apk>
adb shell am instrument -w -r \
  tv.torrframe.test/tv.torrframe.PlatformPlayerSmokeInstrumentation
```

Normal tests keep the default AndroidJUnitRunner; omit `playerSmokeRunner`.

Validation on 2026-10-16: Debug and minified Release builds succeeded; 135 app JVM
and 26 torrent-search JVM tests passed. Emulator: 7 player, 1 library-order,
22 existing remote-navigation, 8 external-player, 6 localization, 4 native/TorrServer
tests passed, plus the platform Release smoke check (49 checks total).
Lint: 0 errors, 37 warnings. Release log confirms `ffmpegLavc60.3.100-ac3` decoding.
Both native ABIs are packaged and the ARM64 ELF LOAD segments use 16 KiB alignment.
Debug was restored on the emulator and the test APK/fake external player removed.
