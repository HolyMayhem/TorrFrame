# Catalog

TV Settings → **Catalog** and Phone Remote → Settings → **Catalog** share the same
preferences. A fresh installation defaults to **TMDB**, with direct routing and
its secure DNS recovery. Existing explicitly configured endpoints remain saved.

The built-in TMDB, public CUB and poster mirror hosts fall back to Google DoH when
system DNS fails or returns non-public addresses. The DoH connection bootstraps via
Google's published IPv4 resolver addresses, retaining `dns.google` as the HTTPS host,
SNI and certificate name; it therefore works even when router DNS cannot resolve
`dns.google`. Other domains, custom proxies and LAN services keep system DNS.
See [Google resolver addresses](https://developers.google.com/speed/public-dns/docs/using#google_public_dns_ip_addresses)
and [secure transport hostnames](https://developers.google.com/speed/public-dns/docs/secure-transports#endpoints).

## Sources

- **TMDB:** lists, search, details and related titles use TMDB as before.
- **CUB:** lists, genres and search use the public `https://tmdb.cub.best/` service,
  without a CUB account, token or separate server.
- **Automatic:** start each first page with TMDB. On a failed load, try CUB, retaining
  usable cached TMDB data if CUB also fails. A successful empty result does not
  trigger fallback. Subsequent pages stay on the provider selected for that feed;
  refreshing page one checks TMDB again. Provider assignments are bounded to 64 feeds.

CUB is a different API, not a transparent TMDB gateway. Lists use its root with
`cat`, `sort` and optional `genre`; search combines `search/movie` and `search/tv`
while preserving `(media type, TMDB ID)` identities. CUB's `language` parameter is
not a translation locale: sending `ru-RU` caused otherwise valid searches to become
empty. Browse/search omit it, so CUB can return Russian titles even in the English UI.

**Full details, translations, credits and related titles continue using TMDB in all
three modes.** Tested CUB `/3/...` URLs redirect to TMDB and do not provide an
independent solution to blocked details. Normal TMDB authentication, localization,
rate limiting, error handling and its cache remain in use for those operations.

If full details fail, a bounded memory map (256 items, keyed by title identity and
locale) preserves the basic title/poster from an already loaded browse/search page.
The card remains usable for torrent search and personal lists; its load is explicitly
marked partial/stale on TV and Phone Remote. Missing credits or synopsis are not
invented. A subsequent successful retry replaces the partial card with full details.
Previously cached full details take precedence over this basic fallback.

CUB and TMDB have separate cache namespaces. CUB URL/token changes also change its
namespace. The CUB client limits concurrent requests to two, uses an 18-second call
deadline, a 1 MiB response limit and no redirects. Denied/malformed responses are errors.

The built-in public CUB host uses a separate HTTP/1.1 pool and `Connection: close`
per request. On the physical Mi Box, a reused connection repeatedly stalled after
successful responses, while isolated connections completed the same requests.
This bounds the failure to one request and avoids sharing it between HTTP/2 streams.
Custom CUB hosts and the TMDB/detail/image clients keep their existing protocols
and pools. TLS verification and the two-request CUB concurrency limit remain enabled.

## Personal home rows

Two independent switches show **Favorites**, then **Watch later**, before the remote
collections on the unfiltered catalog home page. Both default to on. Empty lists
show an empty-state message, not a request to a remote catalog. Search and genre
pages retain their normal contents. Header shortcuts to the full personal lists
remain available when their home rows are hidden.

These are the existing persistent catalog lists, shared with Phone Remote. Hiding
a row does not delete its contents. Layout-only changes do not refresh the network
catalog or invalidate caches. D-pad focus returns to the originating card or full
collection tile after navigating back.

## Poster fallback and advanced connections

Poster recovery is independent of catalog selection: TMDB remains the default, but
all catalog modes can recover images through `imagetmdb.com`, `nl.imagetmdb.com` and
`de.imagetmdb.com`, plus configured mirrors. Proxy-only mode omits the original host.
Three public mirrors returned valid JPEGs during verification.

Coil uses `CatalogImageNetwork` with three concurrent image transfers, a four-second
whole-call deadline per attempt and a sixteen-second routing deadline. The complete
image body is received before handing it to Coil, so a timeout during body transfer
also advances the next route. Each body is capped at 4 MiB; responses are closed and
cancellation cancels the current socket without starting another route. Coil retains
its existing disk/memory cache and canonical image keys. Other image providers and
API clients retain their original networking.

Host health is bounded to sixteen entries with a sixty-second failure cooldown.
The last successful host is persisted without image URLs or secrets. A successful
fallback clears the origin's error; diagnostics report a failure only when recovery
is exhausted, rather than leaving an error for a successfully replaced request.

Thumbnails, full posters, collection mosaics and backdrops share the recovery code.
A failed visible image retries twice with backoff, then waits for network recovery
or activity resume. One connectivity callback is registered per activity and is
removed on disposal. Retrying a backdrop keeps its loader alive even while showing
the poster layout; returning to the cinematic layout preserves D-pad focus.

Phone Remote follows the same mirror list/health order, remembers the working host,
limits each visible image attempt to four seconds, retries failed images twice, and
recovers on online/visibility events. Lazy images start their timer only near the
viewport, and effects/listeners/timers are removed on unmount.

Advanced controls preserve custom HTTPS API proxies, up to three poster mirrors,
and optional custom CUB URL/token. An empty CUB URL uses the built-in public service.
No VPS is deployed. CUB account/bookmark API access is unrelated to public catalogs.

TMDB request routing remains separate from source selection:

- Direct uses the original API.
- Automatic routing tries the origin and then configured proxies/mirrors on
  connection/TLS/DNS failure, HTTP 403/408 or 5xx, including a stalled API response
  body or a successful HTTP response containing HTML instead of JSON. API HTTP
  401/404/429 retain their normal handling.
- Proxy-only routing requires both an API proxy and at least one configured mirror.

API bases include `/3/`; image bases precede `/t/p/`. Endpoints must be HTTPS without
credentials, query or fragment. Cross-host routing drops authorization/cookie/token
headers. API query keys are forwarded only to explicitly configured API proxies.
TorrServer, trackers and unrelated metadata providers retain their routes. Failed
routes have a bounded 30-second cooldown. Each API attempt limits connection time to
five seconds and body inactivity to eight seconds, within the existing total call
deadline. Response bodies are checked inside routing so a body timeout can reach
the configured fallback rather than failing after route selection. At most two API and seven image destinations
are eligible; Coil image attempts use their own bounded deadlines; cancellation closes failed responses.

Optional CUB tokens remain encrypted using Android Keystore. Phone snapshots expose
only whether a token exists. Partial TV/phone updates merge individual fields and
never return the token. No credentials are required for the built-in catalog.

References: [Lampa CUB source](https://github.com/yumata/lampa-source/blob/main/src/core/api/sources/cub.js),
[Lampa image routing](https://github.com/yumata/lampa-source/blob/main/src/core/tmdb/proxy.js).

Physical Mi Box availability must be checked separately from emulator/Mac results.

### Failure reporting and attribution

Catalog loads retain a safe structured failure: provider, DNS, timeout, TLS,
HTTP access denial/rate limit/status, malformed/oversized response or connection
failure. The TV displays that reason rather than assuming all failures mean no
internet. Raw exception messages, request URLs and credentials are not displayed.
Cached content and Retry focus handover keep their existing behavior.

CUB makes at most one additional attempt after a transient connection failure,
timeout or HTTP 502/503/504, with 200 ms between attempts. Each attempt retains
an 18-second total deadline and allows 14 seconds of body inactivity. Access
denial, rate limiting, certificate errors and malformed JSON are not retried.
This does not guarantee availability of the public service. TorrentsCSV also
uses the existing secure-DNS recovery when its system DNS lookup fails; other
tracker/custom hosts retain system DNS.

Browse results remember their provider in the bounded session cache, including
automatic fallback. CUB detail footers distinguish `Catalog: CUB` from
`Details and images: TMDB`; selecting CUB does not replace the TMDB detail API.
Long-pressing the top Catalog tab clears detail history, query, genre and expanded
collection and returns to the beginning of the home catalog. A short press keeps
the existing route.

## Verification, 2026-10-19

- 200 JVM tests passed, including provider fallback, provider-stable pagination,
  recovery, cached-data preservation, keyless CUB requests, image fallback and
  preference merging/serialization.
- 15 Phone Remote tests passed; production TypeScript/Vite build passed.
- Android lint: zero errors, 35 existing warnings.
- Emulator: all 23 catalog navigation cases and two general D-pad/settings cases
  passed. Source/home-row controls and encrypted settings reopening passed.
- The explicit Android live check returned 20 movies, 20 series, six Matrix search
  results and a 44,828-byte JPEG from the public poster mirror without a CUB token.
- APK contents verified: current Phone Remote assets, original startup MP4,
  installed application identity and ARM64/ARMv7 libraries.

Build artifact: `artifacts/TorrFrame-Catalog-debug.apk`.

### Image recovery verification

- Header-success/body-timeout fallback, cancellation without a second route,
  rejection of HTML-as-image, host preference/cooldown and cross-host header
  stripping are covered with controlled network fixtures.
- 32 catalog/detail navigation scenarios passed on the emulator, including the
  same movie's failed backdrop recovering without losing D-pad focus.
- On the physical Mi Box, all three images for the reported Avatar Aang movie
  loaded through the real Coil pipeline: thumbnail, full poster and backdrop.
  Repeated requests used the memory cache. No catalog source/settings changes
  were required. The image host preference is stored separately.
- Debug and R8 release preview builds completed. The preview is signed with the
  existing debug key for in-place comparison, and excludes Developer Console.
