# TorrServe Silicon TV 0.1.4 — local preview

Metadata now uses embedded build-time API keys and an ordered fallback chain:
IMDb via OMDb, then TMDB, then Kinopoisk by default. Users can move providers up
or down with the remote and save their preferred order. Misses, unavailable
services and failed details fall through; the first confident match wins.

- Remove manual metadata-key input and migrate legacy settings without affecting Jackett.
- Preserve optional AniList anime matching before the general provider chain.
- Keep provider/language caches independent of user priority and support force refresh.
- Keep actual keys in ignored local build configuration, outside Git/source archives.
- Fix Android ICU compatibility in release-name parsing.
- Retain the existing model: TorrServer updates are delivered inside APK updates.

Preview APKs use the local debug certificate. Visual acceptance remains with the owner.

Validation: 34 JVM tests and 12 Android instrumentation tests passed, including
real embedded-key metadata lookup. Debug/R8 builds and APK v2 signature verified;
lint has no errors. Updated the manual-testing emulator without clearing data.
