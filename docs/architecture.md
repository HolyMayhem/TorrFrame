# Architecture — Android TV

Analysis recorded 2026-10-12. Android is a separate project. The macOS checkout
was inspected read-only at `ac00385fa9aa5cb7c6f63cfbc287911a8add98b3`.

## Runtime

One `app` module, Kotlin, Compose Foundation lazy layouts and `androidx.tv.material3`.
ViewModels expose immutable StateFlow. Repositories own network/database operations.
Manual dependency composition avoids a DI framework and excess modules.
SQLiteOpenHelper provides transactions and versioned migrations; disk work runs on IO.
Images live in Coil's bounded file/memory caches, never in SQLite.

## TorrServer

Official `MatriX.146.1` Android ARM64 ELF is packaged as
`jniLibs/arm64-v8a/libtorrserver.so`. This is an executable, not a JNI library:
do not call System.loadLibrary. Legacy JNI packaging makes Package Manager extract
it into the read-only nativeLibraryDir. ProcessBuilder runs that installed copy;
only data/config live in filesDir. Bind HTTP explicitly to 127.0.0.1.
The official release SHA-256 is pinned and checked during packaging.
No root, writable-directory exec, runtime binary replacement, or signal immunity.

Foreground app owns the idle server. A Media3 MediaSessionService owns playback
and keeps server lifetime across Activity backgrounding. Stop idle server after
backgrounding; do not misuse dataSync FGS for indefinite playback. A single
serialized controller prevents duplicate child processes. Process death recovery
must verify recorded PID identity before terminating any orphan owned by this app.

Executable packaging is the primary candidate. JNI/Go-library embedding would need
an upstream API bridge and a larger maintenance surface. Physical-device testing
remains necessary; successful compilation alone does not verify execution policy.

## HTTP and data

TorrServer: POST /torrents (list/get/add/rem), multipart /torrent/upload,
GET /echo, /stream?link=hash&index=one-based-index&play. Preserve unknown settings.
Library identity is normalized info hash, never title. Local records survive server
outages. Torrent removal requires confirmation.

Jackett stays external. Preserve reverse-proxy base paths. Validate with t=caps,
use t=search, category filters 2000/5000; use movie/tvsearch only if caps permit.
Namespaced streaming XML parser; bounded responses and results; cancellable OkHttp
calls; explicit search submission cancels prior work. Handle HTTP and Torznab errors
without showing server text or credential-bearing URLs. Prefer magnets, otherwise
download torrent on the client and upload bytes to TorrServer.

Android Keystore AES-GCM protects the user-specific Jackett key in private preferences, excluded from
backup. TLS verification stays enabled. Cleartext is needed for loopback and
user-configured LAN Jackett; HTTPS remains supported.

## Metadata

Keep the macOS behavioral pipeline, independently implemented in Kotlin:
release-name parsing → at most five aliases → language/provider preferences →
bounded search → localized/original title, year and kind ranking → details.
Support bilingual slash aliases and parenthetical aliases without mistaking
director/release annotations for titles. Cache by parser version, provider,
language and candidates: 30 days found, 24 hours not found. Network failures are
not cached as misses. Metadata absence cannot prevent adding or playing torrents.
The default fallback order is OMDb (IMDb) → TMDB → Kinopoisk; the user can reorder
all three with the remote. Stop at the first confident result; misses, service
errors and failed details continue to the next provider. Cache records remain
provider-specific, so changing priority cannot reuse a lower-priority result
before checking higher-priority providers. Force refresh removes cached misses.
AniList remains opt-in and is checked first for strict anime matching, like macOS.

Three metadata keys are generated into BuildConfig from an ignored local
properties file or CI environment variables. No manual metadata key UI/state or
persisted key overrides remain. Legacy key preferences are removed without
attempting to decrypt them; Jackett's encrypted credential is retained. Keys can
be extracted from a distributed APK and are not protected by the private source
repository. Actual values are not committed to Git or included in source archives.

## UI and player

Dark palette #101114/#1B1C20/#25262B; shared dp tokens. Top-level Home/Search/
Library/Settings. Posters 2:3, continue-watching row only when history exists.
Stable lazy keys, saved scroll state and focused item; BACK returns from player
to details, from details to original section. System IME for D-pad text input.
Media3 uses device decoders and explicit loading/buffering/error states. Save
per-file progress at checkpoints and pause/stop; no per-second database writes.

## Updates and measurements

Server updates arrive with a signed APK. The ELF runs from nativeLibraryDir,
which the Android package installer manages; downloading a file into filesDir
and executing it is blocked for target API 29+ on Android 10+.
UpdatesViewModel checks the latest TorrServer tag, but does not replace the ELF.
The app updater downloads an APK, validates SHA-256, package ID, signing
certificate and increasing versionCode, then opens Android's installer.
The current private GitHub repository has no anonymously accessible release
channel; distribution and a persistent production certificate still need setup.
For independent engine updates, a separate server APK/service could be designed
as a future architecture change, with IPC and separate lifecycle/version handling.

Platform reference: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
GitHub release check + integrity and certificate checks + system installer for
app updates, after user consent. Release repository is configurable until created.
No publication or production signing is implicit in this local build.
Measure startup, app/server RSS, library/search/player latency and frame timing;
do not promise 60 FPS, <2s startup or codec support without target-device evidence.

## Sources

- https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/build/releases/agp-9-4-0-release-notes
- https://developer.android.com/jetpack/androidx/releases/tv
- https://github.com/YouROK/TorrServer
- https://github.com/YouROK/TorrServe
- https://github.com/Jackett/Jackett/wiki/Jackett-API
- macOS: TorrServerNativeAPI, MediaNameParser, MediaTitleCandidateGenerator,
  MetadataResolver, MetadataCache, JackettClient, JackettTorznabParser,
  JackettModels, SearchViewModel. Graph generation was stale; exact current source
  was read as fallback. The graph is discovery evidence, not proof of completeness.
