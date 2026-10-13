# TorrServe Silicon — Android TV

Native Android TV client with a bundled ARM64/ARMv7 TorrServer, a local media library,
Jackett search, metadata providers and a Media3 player. Kotlin, Compose for TV,
Coroutines and SQLite. The macOS project is maintained separately.

Current local preview: **0.1.9**. Android 8.0+ / API 26+, ARM64 and ARMv7 (32-bit Android).

## Build and run

Open `android-tv/` in Android Studio. Install the SDK versions declared by the
project and choose an **ARM Android TV** device or ARM64 emulator. Run the `app` module.
The bundled server starts automatically; Jackett is an external service.
The interface defaults to 85% scale. Settings → Appearance provides remote-friendly
minus/plus controls for 70–110% in 5% steps and a reset to 85%. Changes apply
immediately and persist automatically; smaller scales reflow the content into
the available space. Header branding and the navigation hint footer are compact.
Appearance is the final Settings group and also controls language, light/dark
theme and the entire navigation footer. These preferences save immediately.
TV navigation contains Search, Library and Settings, opening Library by default.
Focused controls use #008800. The header indicates server state with a colored dot.
The standard TorrServer web interface is available on the TV's LAN IP and port
8090 by default. Settings shows the current URL under TorrServer. Open that URL
from a browser on the same network; no ADB forwarding is required. LAN access
keeps the server running after leaving the app, independently of boot autostart.
Disable LAN access in Settings to listen only on loopback. The app and player
continue using their local address in either mode. Phone Remote is a separate
optional service and does not need to be enabled for the standard web interface.
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
.tools/sdk/platform-tools/adb -s emulator-5554 install -r "$HOME/Library/Caches/TorrServeSiliconAndroidTV/build/app/outputs/apk/debug/app-debug.apk"
.tools/sdk/platform-tools/adb -s emulator-5554 shell am start -n tv.torrserve.silicon/.MainActivity
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

## Wi-Fi deployment to Mi Box

Open this repository root in VS Code. **Cmd+Shift+B** runs the default
`Mi Box: Build & Deploy` task. It connects to the saved TV, detects its ABI,
uses a warm Gradle daemon and separate per-ABI output/task-history caches, installs with
`adb install -r` and launches MainActivity. No app data is cleared.

```sh
./scripts/deploy-tv.sh
./scripts/deploy-tv.sh --no-build
./scripts/deploy-tv.sh --preview
./scripts/deploy-tv.sh --logs
# If the TV address changes:
./scripts/deploy-tv.sh --device IP:PORT
```

The device address is local-only in `.tools/tv-device`; `TV_SERIAL` overrides it.
Without a saved address, exactly one connected physical device is required;
emulators are never selected automatically. ADB comes from the configured local
SDK, so no additional Homebrew installation is needed on the development Mac.
The preview task uses R8 Release; default Debug avoids the R8 pass during editing.

## Checks

```sh
./scripts/build-local.sh :app:testDebugUnitTest :app:lintDebug
./scripts/build-local.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tv.torrserve.silicon.RemoteNavigationTest
```

The navigation regression tests exercise Android key events, tab selection,
content focus transfer, settings dialog cancellation and inherited dark-theme text color.
Run UI tests on a dedicated fresh emulator profile with the Mi Box disconnected
from ADB; connectedDebugAndroidTest targets all connected devices. The tests expect no Jackett
credentials and must not uninstall the app from a manual-testing device.
Visual acceptance is performed manually by the project owner. A physical TV
still needs playback, codec and performance checks.

## Documentation

- [Setup and functional guide](README_ANDROID_TV.md)
- [Architecture](docs/architecture.md)
- [Icon and banner source specifications](docs/icon-assets.md)
- [Verification evidence](docs/verification.md)
- [0.1.9 preview notes](docs/release-notes-0.1.9.md)

Jackett credentials stay in encrypted application storage. Metadata keys are built into the APK. Local SDKs, JDKs, Gradle
outputs, signing keys and APK artifacts are excluded from Git. Preview APKs use a
local debug certificate; production release signing is configured separately.
TorrServer is by YouROK and distributed under GPL-3.0; its license and source link
are included in the application's open-source notices.
