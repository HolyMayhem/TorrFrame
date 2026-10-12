# TorrServe Silicon — Android TV

Native Android TV client with a bundled ARM64 TorrServer, a local media library,
Jackett search, metadata providers and a Media3 player. Kotlin, Compose for TV,
Coroutines and SQLite. The macOS project is maintained separately.

Current local preview: **0.1.5**. Android 8.0+ / API 26+, ARM64 only.

## Build and run

Open `android-tv/` in Android Studio. Install the SDK versions declared by the
project and choose an **ARM64 Android TV** device or emulator. Run the `app` module.
The bundled server starts automatically; Jackett is an external service.
Metadata keys are embedded at build time. Copy
`android-tv/metadata-keys.properties.example` to the ignored
`android-tv/metadata-keys.properties`, or supply the three `TORRSERVE_*_API_KEY`
environment variables described in the setup guide. The development Mac already
has its local key file; key values are excluded from Git and source archives.

On the development Mac, the local SDK and JDK are already configured:

```sh
./scripts/build-local.sh :app:assembleDebug
./scripts/emulator.sh -no-snapshot
# In a second terminal, after Android TV has booted:
.tools/sdk/platform-tools/adb install -r "$HOME/Library/Caches/TorrServeSiliconAndroidTV/build/app/outputs/apk/debug/app-debug.apk"
.tools/sdk/platform-tools/adb shell am start -n tv.torrserve.silicon/.MainActivity
```

Enable the hardware keyboard in the AVD. With the emulator window active, use
arrow keys and Enter, or the Directional Pad under Extended Controls.
Escape is handled as Back by the app. On the development Mac, the launcher uses
an emulator outside the workspace path to avoid Qt plugin discovery failures.

If Jackett runs on the same Mac, use `http://10.0.2.2:9117` in the emulator
(adjust the port to match your Jackett installation). A physical TV device needs
the host's reachable LAN address instead.

On macOS, Gradle outputs are stored in `~/Library/Caches/TorrServeSiliconAndroidTV/build/`
to avoid duplicate generated files in synchronized Documents folders. Override
the location with `-PtorrserveBuildRoot=/absolute/path` if needed.

## Checks

```sh
./scripts/build-local.sh :app:testDebugUnitTest :app:lintDebug
./scripts/build-local.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tv.torrserve.silicon.RemoteNavigationTest
```

The navigation regression tests exercise Android key events, tab selection,
content focus transfer, settings dialog cancellation and inherited dark-theme text color.
Run UI tests on a dedicated fresh emulator profile; the tests expect no Jackett
credentials and must not uninstall the app from a manual-testing device.
Visual acceptance is performed manually by the project owner. A physical TV
still needs playback, codec and performance checks.

## Documentation

- [Setup and functional guide](README_ANDROID_TV.md)
- [Architecture](docs/architecture.md)
- [Icon and banner source specifications](docs/icon-assets.md)
- [Verification evidence](docs/verification.md)
- [0.1.5 preview notes](docs/release-notes-0.1.5.md)

Jackett credentials stay in encrypted application storage. Metadata keys are built into the APK. Local SDKs, JDKs, Gradle
outputs, signing keys and APK artifacts are excluded from Git. Preview APKs use a
local debug certificate; production release signing is configured separately.
TorrServer is by YouROK and distributed under GPL-3.0; its license and source link
are included in the application's open-source notices.
