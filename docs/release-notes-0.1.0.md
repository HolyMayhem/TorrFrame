# TorrFrame TV 0.1.0 — local preview

A native Android TV application with a bundled ARM64 TorrServer, remote-friendly
Compose for TV screens, Jackett search, a persistent library and Media3 playback.

- Add magnets without Jackett, or search an external Jackett instance and add/watch releases.
- Resolve metadata through TMDB, OMDb, Kinopoisk Unofficial or AniList with bounded caching.
- Continue playback in the background and retain per-file resume positions.
- Configure encrypted API keys, cache limits, languages and an update repository.
- Verify APK integrity and signing certificates before opening Android's installer.

This preview uses a local development signing certificate. No public release has
been published. Visual acceptance and physical TV/codec/performance testing remain
pending. Live user Jackett and metadata-provider credentials were not available;
provider contracts were tested with isolated fixtures. Some diagnostic messages
remain in English.
