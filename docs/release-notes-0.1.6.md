# TorrFrame TV 0.1.6 — local preview

Support Mi Box devices running 32-bit Android with the official Android ARMv7
TorrServer, alongside existing ARM64 support. Native binaries remain pinned to
MatriX.146.1 and are verified by SHA-256 before packaging.

Add VS Code tasks for incremental Wi-Fi deployment, installation of the previous
build, optimized preview deployment and process-filtered logs. The default task
builds only the connected TV's ABI and launches the app after an in-place update.
Device selection is explicit/saved locally and never automatically targets an emulator.

Validated on a physical MiTV-AFKR0 / Android 14 / armeabi-v7a: install, launch and
native TorrServer version response. First Debug deployment took 41 seconds;
a no-change incremental deployment took 18 seconds. Visual/playback acceptance
remains with the owner. Preview builds use the local debug certificate.
