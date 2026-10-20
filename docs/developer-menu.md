# Developer menu

The **Developer menu / Меню разработчика** category is available in all builds.
Debug builds show it by default, unless explicitly disabled. In optimized/release builds, focus the settings
gear and hold OK for five continuous seconds. A brief text-only bottom notification confirms
“Developer mode enabled” / “Режим разработчика активирован”. Releasing a shorter
press still opens ordinary settings; a completed hold does not also open them.
Pointer press-and-hold follows the same five-second rule. Focus loss, cancellation
or backgrounding cancels the pending key hold.

Hold the same gear for five seconds again to disable developer mode. This hides
the category and diagnostic panel, cancels pending startup previews, and resets any
simulated failures. Ordinary app settings and media remain intact. The gear uses
the same pressed-state animation as other buttons throughout the whole hold,
including cancellation and focus loss. Notifications contain no app icon.

Menu access is saved locally in `developer-access` preferences and survives a new
process, including an explicitly disabled state in debug builds. It is not an authentication boundary or exposed through Phone Remote.
The tools use session-only `DeveloperControls`; no credentials, production settings, library records or poster caches are deleted or changed by its switches. Activity recreation keeps the session, process restart resets it. **Reset test switches** disables all overrides and reloads the current catalog view through the ordinary repository, clearing a previous simulated error when the source is reachable. Reload revisions remain monotonic across resets.

Controls:
- Missing-key simulation for TMDB, OMDb and Kinopoisk independently: raises the existing authentication error before sending a request.
- Metadata network failure: raises a timeout for metadata providers, including AniList. Jackett and TorrentSearch remain independent.
- Metadata/catalog cache bypass: skips reads and writes without clearing stored entries. A failing source automatically bypasses its cache so cached success cannot mask a fault.
- TorrServer unavailability: blocks user-facing API requests, while process health checks continue against the real server. The process and an already running video stream are not stopped.
- Reload catalog requests: repeats the currently selected catalog/search/detail requests through the existing repository. Repeated composition does not repeat the same reload request.
- Reset switches: restores ordinary networking and caching immediately.

Existing library metadata and posters remain in place. To test failed metadata enrichment, add a new title or use **Refresh Metadata** in its library detail page. For catalog failure tests, use **Reload catalog requests**, then return to the catalog. Active test switches are indicated below the application header.

The library uses the existing local process and connection states to distinguish startup, a stopped server, unavailability, authentication failures and invalid API responses. Failed library synchronization also prevents an empty-library claim. Previously saved cards remain available with a compact status banner. A successful refresh or connection recovery restores the ordinary view.

Tests cover initially locked release controls, request scoping, zero outbound requests for injected failures, cache preservation and provider fallback, catalog reload deduplication, actual server stop/restart, simulation without a process restart, D-pad focus and both UI languages.

## Verified recovery, 2026-10-16

Reset now triggers a new catalog refresh revision instead of resetting the revision to zero.
An Android regression test reproduces a simulated TMDB failure in the visible catalog,
resets the switches, verifies successful loading and checks that a second reset is also consumed.
All 9 developer-menu Android tests and 156 JVM tests pass.

The emulator also had an independent DNS issue: system resolution returned loopback
addresses for `api.themoviedb.org` and `image.tmdb.org`, even with the host VPN active.
A diagnostic request using the existing key and an independently resolved address
returned 20 popular movies; no address was hardcoded into the application.
Only the emulator was switched to Android Private DNS `dns.google` (previously unset),
following [Google's Android Private DNS documentation](https://developers.google.com/speed/public-dns/docs/using#android).
Both domains then resolved to public addresses, and all 3 existing live TMDB tests
passed: shelves, genres, movie/TV details, search and Russian/English localization.
This emulator setting is separate from the application and is not bundled in the APK.

## Diagnostic panel

After unlocking (or directly in a debug build), open **Settings → Developer menu → Diagnostic panel**.
The panel is off by default, survives Activity recreation, and resets on process
restart. Its switch is independent of fault injection: **Reset test switches**
keeps the panel visible so recovery can be inspected.

The compact, non-focusable panel appears below the content and above navigation
hints, including when hints are disabled. It also appears below the built-in
player without covering its transport controls. It shows:

- Current app-process RAM PSS, Java heap used/limit and average CPU over the last
  sampling interval. CPU is normalized across available cores: 100% means all
  cores, not one core. Child processes, including TorrServer, are not included.
- Graphics memory reported by Android, where available. This is memory, not GPU
  utilization; GPU utilization is explicitly shown as unavailable. Graphics
  accounting may differ between devices and drivers.
- The most recent unresolved network failure: time, host, DNS/TLS/timeout/
  connection category or HTTP status, and a short RU/EN explanation. Simulated
  failures are marked. Catalog and image requests use the same observer.
  Success clears errors for that host, not unrelated services. “No recorded
  errors” does not assert that every service is reachable.

Metrics are sampled every two seconds on a background dispatcher only while the
panel's Activity is STARTED. Hiding it cancels sampling; returning from background
starts a fresh CPU baseline. Network observation is available in every build, even before menu unlock; it is
process-only, bounded to eight
hosts, and contains no full URLs, request queries, API keys, response bodies or
exception messages. DNS, TLS and connection exceptions retain their original
cause in the HTTP layer so the diagnostic category is not lost.

This panel reports failures; it does not change DNS, route traffic through a VPN,
or add automatic retries. Existing catalog retry buttons remain available.

Verified for this change: 159 app JVM tests (including eight diagnostic tests),
all 11 developer-menu Android tests, and two built-in player Android tests covering
the panel bounds/focus and audio/subtitle controls. RU/EN and 80/100/120/140% UI
scales are covered by the panel test. Lint reports zero errors; the 35 existing
warnings remain. This is emulator verification; Mi Box measurements and rendering
still need to be checked on the device.

## Mi Box DNS investigation, 2026-10-18

The physical Mi Box (Android 14, ARMv7) resolved both `api.themoviedb.org` and
`image.tmdb.org` to `127.0.0.1`. An HTTPS Google DNS query made on the box returned
the same address. Cloudflare's HTTPS JSON endpoint initially returned public addresses, but a later
lookup and DNS-over-TLS queries also returned loopback addresses. Google HTTPS DNS
with `edns_client_subnet=0.0.0.0/0` returned public addresses for both TMDB hosts.
Quad9 returned public addresses in a direct TLS probe, but system DNS queries
stalled when it was selected as Android Private DNS, including after a reboot.
The reason for that system-resolver behavior was not determined.

Both original Private DNS keys were absent. They were restored to that original
state; no router, VPN, root, trust-store or experimental network flags were changed.
The local rollback record is `.tools/mi-box-dns-before.json`.

`TmdbDns` now keeps ordinary public system DNS answers, and uses Google's
[HTTPS JSON DNS endpoint](https://developers.google.com/speed/public-dns/docs/doh/json)
with `edns_client_subnet=0.0.0.0/0` (no client-subnet forwarding)
only when the system returns no usable public addresses for the two TMDB hosts.
Other hosts, including local TorrServer, remain on system DNS. The separate client
retains normal HTTPS certificate and hostname validation, disables redirects, has
a four-second deadline and a 16 KiB response limit. It sends domain/type and the zero-scope subnet option only,
never API keys or catalog requests. Literal IPv4 answers are validated; returned
names are not passed back to the faulty system resolver. The cache is bounded to
two hosts and expires at the returned TTL capped at five minutes. Normal system
DNS remains responsible for IPv6; the recovery path currently requests IPv4.
There are no hardcoded TMDB/CDN IPs or changes to the Android-wide resolver.

Eight regression tests cover healthy DNS passthrough, loopback/private poisoning,
system lookup failures, independent expiring cache entries, malformed/private
secure responses, redirects, size/time limits and concurrent poster requests.
All 167 app JVM tests pass; lint reports zero errors.

Physical Mi Box verification: both forced-live catalog tests pass (all shelves,
Russian/English search, movie/TV details, real genre IDs and filtered collections).
The real TV catalog was also inspected: current English titles, ratings and
posters render successfully. The installed ARMv7 build retains the original
library and preferences. Final desktop artifact:
`artifacts/TorrFrame-MiBox-DNS-fix-debug.apk`.
