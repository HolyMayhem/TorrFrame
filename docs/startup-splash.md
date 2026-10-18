# Animated startup

The launcher uses the supplied `icons/Welcome Clip.mp4`, packaged byte-for-byte as
`app/src/main/res/raw/welcome_clip.mp4`. It is H.264/AVC, 1920×1080, approximately
60 fps, 2.667 seconds, with no audio track. No re-encoding or additional cropping
is applied. The original animation, including its final zoom, is preserved.

The sequence is video → immediate black alpha 1 to 0 over 1,200 ms using
`FastOutSlowInEasing`. There is no mandatory pause after the video. If mandatory
local initialization is still pending, the black phase waits only until it is ready.
Fading changes the cover's opacity; the app stays
mounted beneath it throughout.

## Integration

- `features/startup/StartupSplashState.kt` belongs to `AppGraph`, once per process.
  It holds only phase/timing/decoder diagnostics, never an Activity, view or player.
- `StartupSplash.kt` composes the existing content immediately, intercepts input
  while covered, owns the black/fade presentation and lifecycle observers.
- `StartupVideoPlayer.kt` owns at most one local ExoPlayer for that startup. It uses
  the existing Media3 dependencies, filters out software codecs, disables extension
  renderers, audio/text tracks, audio focus and looping, and releases on end, error,
  disposal or backgrounding. No playback service/media session is created.
- The video surface is a TextureView inside a centered FIT AspectRatioFrameLayout.
  There are no player controls. Letterboxing uses the black cover underneath it.
- `StartupWindow.kt`, the launcher theme and API 31 theme variant provide black
  system/decor backgrounds, a transparent system splash icon and hidden system bars.
  No separate splash Activity or new dependency is required.

`MainActivity` supplies readiness after the existing settings initialization and
the first local library read settle and the UI gets two composition frames. The
library completion signal does not wait for TorrServer sync, TMDB, poster downloads
or other optional network work. Local load failures still complete this signal and
surface through the existing UI message path, so an error cannot strand the cover.
`reportFullyDrawn()` now runs after the cover is removed and local UI is ready.

The main screen's state/ViewModels/normal initialization continue under the cover.
Saved settings and QR dialogs are mounted after reveal to prevent another Android
window from appearing above the startup video.

## Lifecycle and failures

The animation can be claimed once per process. Home/backgrounding ends the startup
and releases its decoder; returning to the live process does not replay it. Back
closes the launcher and releases resources. Configuration recreation skips any
unfinished video and preserves the current black/fade timestamps, without creating
  another startup player. A genuinely new process receives a fresh sequence.

Player errors, unsupported hardware decoders and missing/corrupt media advance to
the black/readiness/fade path. A six-second monotonic watchdog handles stalled
video. Monotonic deadlines are rechecked after wake-up to tolerate timer rounding.
Fade progress also uses the process timestamp so recreation cannot reset it to black.

## Verification

- All 189 app JVM tests pass, including six new timing/readiness/process/lifecycle cases;
  Android lint reports zero errors and the same 35 pre-existing warnings.
- Android instrumentation covers real hardware-codec selection, delayed readiness,
  missing media/surface detachment, recreation, background/resume and Back.
  Run each lifecycle class in a separate instrumentation invocation to exercise a
  fresh process; general navigation tests can run after the cold launch test.
  Seven startup cases and three existing catalog/settings/D-pad navigation cases passed.
- Emulator video/log capture confirmed initialization during the video, one hardware
  decoder (`c2.goldfish.h264.decoder`), player release before reveal, and a gradual fade.
- The supplied MP4's packaged hash is verified during artifact preparation.

Physical Mi Box playback has not been checked for this change. Devices without a
compatible hardware decoder intentionally skip the video and keep normal startup.
