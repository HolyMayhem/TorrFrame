# Animated startup

The launcher uses the supplied `icons/Welcome Clip.mp4`, packaged byte-for-byte as
`app/src/main/res/raw/welcome_clip.mp4`. It is H.264/AVC, 1920×1080, approximately
60 fps, 2.667 seconds, with no audio track. No re-encoding or additional cropping
is applied. The original animation, including its final zoom, is preserved.

The video and normal local initialization run concurrently. At media position 1,900 ms,
readiness is checked. If still unready at 2,000 ms, the same player pauses and seeks to
that position. The same TextureView displays the decoded video frame throughout the
hold; no PNG overlay is used. This preserves the video rendering/color pipeline and
avoids brightness changes caused by mixing bitmap ICC conversion with video output.
When the UI is ready, the same player continues its tail on the same surface.
If readiness arrives between the check and hold, no pause is inserted.

After five seconds from the start of the sequence, an ongoing frozen wait displays
“Preparing the app…” at the bottom (Russian: “Подготавливаем приложение…”), following
the saved app language. A single cancellable timer uses the process-owned monotonic
startup timestamp. The hint is removed immediately when the frozen phase ends,
without an exit animation; it never overlays the continuing clip or final fade.

After the video ends, the black cover immediately fades from alpha 1 to 0 over
500 ms using `FastOutSlowInEasing`. There is no additional black hold. The existing
black/readiness fallback remains for decoder errors or interrupted video. The app
stays mounted beneath the cover throughout; optional network work never gates it.

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
video playback, excluding a legitimate frozen/readiness wait. Resuming the tail starts
a fresh watchdog budget. Monotonic deadlines are rechecked after wake-up to tolerate timer rounding.
Fade progress also uses the process timestamp so recreation cannot reset it to black.

## Developer previews

Debug Settings → Developer offers a normal replay and a slow-load replay. Each is
consumed once and recreates the launcher Activity with fresh screen state. The slow
variant withholds UI readiness until at least seven seconds after the sequence starts,
using a cancellable coroutine delay, without blocking or stressing the UI thread.
The AppGraph/process, settings, files and caches remain intact: these are startup
presentation simulations rather than a full cold-process benchmark. Normal Activity
recreation and foreground return do not replay the intro. Release controls are inert.

## Verification

- 206 app JVM tests pass, including readiness races, long frozen waits, tail resumption,
  500 ms fading, one-shot previews and disabled Release controls.
- Android instrumentation exercises hardware decoding, a video frame held longer than the
  six-second playback watchdog, missing media, recreation, background/resume and Back.
  The hold test checks unchanged TextureView identity and decoded pixels across the wait.
  Developer-menu D-pad tests exercise both preview buttons and preserve user settings.
- The supplied MP4 and regenerated launcher icons are checked in packaged
  outputs. Physical Mi Box playback must be distinguished from emulator validation.
