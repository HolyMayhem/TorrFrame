# TMDB catalog

Implemented in `codex/tmdb-catalog` inside the existing `app` module.

## Existing architecture and integration

`AppGraph` owns the existing OkHttp client and Coil loader. Metadata was previously
resolved by `MetadataProviders` with TMDB authentication inside its private HTTP
method. The old Search UI called `SearchViewModel -> JackettClient -> LibraryRepository`.
The app uses activity-scoped ViewModels, Compose saveable screen state, a SQLite
library store, and encrypted settings. Theme density implements the existing
70–110% scale. Catalog API locale now follows `Settings.uiLanguage` (ru → ru-RU,
en → en-US), the same source as `LocalUiLanguage`. The separate metadata
`Settings.language` continues to control the existing user library only. Accent
and animation preferences remain the sources of truth.

The main tab is labeled Catalog / Каталог and displays `CatalogScreen`. Its internal
Search route and saved-state key remain compatible. Existing Jackett classes, settings,
Phone Remote commands, library models and playback remain available. The library's
catalog shortcut copy was updated because catalog titles cannot yet be added as torrents.

## Boundaries and files

- `core/network/TmdbClient.kt`: shared TMDB authentication, two concurrent requests,
  cancellation and shared cooldown. Both metadata and catalog use this same client
  from `AppGraph`. The existing built-in key is reused.
- `features/catalog/domain/CatalogModels.kt`: typed `(movie|tv, TMDB ID)` identities,
  six sections, pages, rich details and defensive JSON decoding.
- `features/catalog/domain/CatalogRepository.kt`: catalog data contract.
- `features/catalog/data/TmdbCatalogApi.kt`: endpoints, recent releases, lazy synopsis
  fallback and appended credits only for opened titles. No trailers or other eager extras.
- `features/catalog/data/CatalogCache.kt`: separate disposable `catalog.sqlite` cache;
  no library schema or settings migration. Up to 256 entries and 16 MiB JSON.
- `features/catalog/data/CatalogRepositoryImpl.kt`: cache-first streams, stale data
  during refresh/offline, request coalescing, cancellation when the last subscriber
  leaves, and isolation from disk failures. Cache keys include language and page;
  search queries are hashed in cache keys.
- `features/catalog/presentation/CatalogViewModel.kt`: independent UI state, per-section
  failures, 450 ms search debounce, cancellation/version checks, incremental pagination,
  typed details and SavedStateHandle query/selection restoration.
- `CatalogScreen.kt`, `CatalogComponents.kt`, `CatalogDetailsScreen.kt`: lazy rows/grid,
  poster cards, errors/placeholders, metadata pages, theme-aware surfaces and visible
  remote focus. Vertical and individual row positions stay in saveable list states;
  the previously focused card is restored after details. No network calls in UI.
- `MainActivity.kt`, `TorrServeApplication.kt`: ViewModel factory/navigation/DI.
- `MetadataProviders.kt`, `Http.kt`: reuse transport and preserve Retry-After metadata.
- `TvComponents.kt`: optional live input callback on the existing text editor.
- `SettingsScreen.kt`, `res/drawable/tmdb_logo.xml`: official attribution.
- `LibraryScreen.kt`, `TvText.kt`: catalog shortcut labels in both languages.
- `CatalogDataTests.kt`, `CatalogViewModelTests.kt`, `CatalogNavigationTest.kt`,
  `CatalogLiveTest.kt`: unit, device and explicit real-service verification.

## Catalog and API behavior

The landing screen has weekly trending movies, new releases, popular movies,
top-rated movies, popular TV and weekly trending TV. Recent releases use
`discover/movie`, `primary_release_date` from today minus six months through today,
`vote_count.gte=30`, `popularity.desc`, and exclude adult/video content. These are
primary release dates rather than regional theatrical release dates.

Multi-search filters out people, adult titles and incomplete records. Empty/whitespace
queries make no request. Both Cyrillic and Latin titles are passed unchanged to TMDB.
Pagination never fetches every page in advance. A failed next page preserves the
existing cards; retry targets the failed page. A person-only page can advance via
"More results". Returning from details does not trigger fresh shelf/search requests.
When re-entering the Search tab after a long session, expired section/search TTLs
are rechecked; fresh ViewModel feeds are kept without repeated requests.
Rapid locale changes use a separate locale generation guard, so stale section/detail
responses cannot win after a RU → EN → RU round trip. Detail-back restores the same
TMDB card even if localized search results complete slightly later.

Details contain original/localized titles, bounded-size poster/backdrop images,
rating/votes, genres, dates, synopsis, runtime and credits. TV adds seasons, episodes,
status and last air date. Missing optional fields are omitted. A missing localized
synopsis/title or an ambiguous title matching a foreign original triggers a lazy
Translations request only on detail opening. Matching ISO language metadata is used
with the selected country preferred, then other countries of that same language.
Existing localized fields are preserved; no Cyrillic/Latin heuristics are used.
If the requested synopsis is absent, an available English/original-language synopsis
is retained. If Translations is unavailable, the prior lazy English detail request
is retained. Fallback failure preserves otherwise valid details. English synopsis
fallback is labeled. No extra translation requests are made for shelf/search cards.

Detail cache keys now use `details:v2:<type>:<id>:<locale>` to avoid reusing old
fallback payloads after this correction. Shelf/search caches and other application
stores are retained. Returning to a previously fetched locale reuses its cache.

## Browse shortcuts below Search

The horizontal button row switches between All, Movies, Series and twelve genre
collections. All retains the original six shelves; Movies and Series show their
existing shelves. Genre collections use `discover/movie` and `discover/tv` with
TMDB `with_genres` IDs and the UI locale, sorted by popularity. Combined action /
adventure and science fiction / fantasy labels match TMDB's TV genre taxonomy.
Movie-only genres do not manufacture equivalent TV genres.

These buttons switch catalog collections; text search keeps its existing multi-search
behavior. Selecting a shortcut leaves text-search mode. The collection is saved in
SavedStateHandle and retained while opening/closing details. D-pad Down moves from
Search to the active shortcut, then into the visible posters; Up returns through
the same controls. Selected shortcuts remain visible in the horizontally scrolling row.

Genre pages reuse repository request coalescing, offline fallback and the existing
bounded SQLite cache. Keys include genre shortcut, media type, locale and page;
freshness is 12 hours with seven-day retention. Requests occur when selecting a genre
and as the user reaches the end of the expanded vertical grid. Switching collections cancels obsolete
genre requests and rejects late responses. No per-title genre/detail fanout is added.

Each horizontal shelf is a preview of at most ten titles, followed by a poster-sized
collection card containing six poster thumbnails (two columns by three rows) from
that feed. Its accessible label names the collection; no More text is drawn.
Preview scrolling does not request further pages. The collection card opens that
same movie or series collection in an adaptive vertical grid, using the already
loaded feed and existing pagination. Search results keep their existing grid.
BACK or the centered arrow button returns to the preview with the collection card focused and its
row scrolled into view. Opening and closing a title retains the collection grid's
position and focused card. Missing/error pages keep available titles and expose
the existing retry control.

## Personal lists

Favorites and Watch later are independent, local lists for movie/TV identities.
Long OK/Enter on any catalog poster opens the contextual add/remove menu; a short
press still opens details. Pointer long press has the same behavior. Closing the
menu restores its card focus; removing the last card from an open list focuses its
Back arrow. Both list shortcuts use the existing collection grid.

`SqliteCatalogUserLists` stores membership and locale-specific card snapshots in
`catalog-user-lists.sqlite`. These records have no expiration and are independent
of the disposable catalog cache and torrent library. Writes are transactional and
finish across model cancellation. Failed writes keep existing membership and show
an error without reporting success. No TMDB account, new key or backend is used.
Saved titles reuse their locale snapshot; missing locales resolve through the
existing details repository only as list cards are composed, with bounded jobs and
request coalescing. Offline failures retain the available card/original title.

## Detail presentation

Titles with a backdrop use the cinematic layout: a large background frame with
theme-aware fades, Back directly on the frame, and a prominent title anchored at
the bottom left. The larger poster on the right hangs 42% below the frame's edge,
leaving the center of the backdrop open. Below the frame, six existing information
fields use the selected typography-led layout: a large rating with vote count,
director, premiere, runtime, genres and original title. Values share text baselines
within each row; captions share a common baseline, 14sp type and an 8dp gap from
the value row. Longer values wrap without truncation; absent optional fields have
no empty captions. There is no enclosing surface or table divider. Director and
premiere move into this area, and original title moves out of the banner, so these
fields are not repeated below. The poster-led fallback retains its prior layout.
The synopsis appears once, in its complete block below the hero;
there is no truncated duplicate or extra synopsis-navigation hint. D-pad Down reaches the complete synopsis
and credits below. Titles without a backdrop use the poster-led
layout: a larger poster on the left and the title/full synopsis on the right.
An image load error selects the same poster-led layout without reloading metadata.
Changing the selected title or backdrop resets that image-failure state.

Back remains the initial focus target. Reading regions display the existing accent
as a focus border, honor the animation setting, and retain heading focus during an
image-error layout change. Existing movie/TV fields, both interface languages,
theme and 70–110% UI scale remain supported. Images use the existing Coil loader;
only opened detail images use the bounded w500 poster and w1280 backdrop sizes.
`CatalogDetailsPresentationTest` covers missing/failed/available backdrops, delayed
image failure during remote navigation, full synopsis, TV metadata and theme/scale
extremes. Layout bounds also verify Back is inside the banner, the title is at its
bottom left, and the enlarged poster crosses the banner edge without covering its
center. Existing catalog navigation tests cover returning to shelves/search.

## Cache and network policy

| Payload | Fresh for | Delete after |
| --- | --- | --- |
| Trending/popular/new-release pages | 12 hours | 7 days |
| Top-rated pages | 24 hours | 7 days |
| Detail page with credits | 3 days | 7 days |
| Search result page | 1 hour | 24 hours |

The final column is a bounded technical retention policy, not a substitute for the
terms applicable to the project's TMDB account. Expired-but-retained payloads can be
shown offline; hard-expired entries are deleted on cache access/write. Image caching
remains with the existing Coil configuration (24 MiB memory, 128 MiB disk).

429 respects both seconds and HTTP-date Retry-After. Up to three attempts use
exponential backoff with jitter. Cooldown is shared between operations; delays over
30 seconds produce an error state with cached data retained instead of leaving a
screen waiting indefinitely. A later manual retry must still respect the cooldown.
No keys or upstream response bodies are exposed in errors.

## Attribution and licensing

About includes the official TMDB logo, sourced from the header of TMDB's developer
site (`https://files.readme.io/29c6fee-blue_short.svg`) and converted to an Android
vector without changing its geometry or gradient. It also includes:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

References: [TMDB FAQ](https://developer.themoviedb.org/docs/faq),
[rate limiting](https://developer.themoviedb.org/docs/rate-limiting),
[discover movie](https://developer.themoviedb.org/reference/discover-movie),
[multi-search](https://developer.themoviedb.org/reference/search-multi),
[movie details](https://developer.themoviedb.org/reference/movie-details),
[TV details](https://developer.themoviedb.org/reference/tv-series-details).
Commercial rights are not inferred from possession of a key; this change does not
publish a release or change the account's licensing agreement.

## Future torrent integration

A future action can accept `CatalogRef`, localized/original title and year, construct
Jackett queries and explicitly add the chosen torrent through the existing
`LibraryRepository`. Catalog identity alone never creates a library/server record.
There are currently no watch/torrent-search buttons on catalog details.

## Verification environment

Builds use `.tools/settings-build` as an isolated copy, excluding pre-existing sync
files named `* 2.*`; originals are retained. The visible API 36 emulator uses ARM64.
Universal APKs include both pinned ARM64 and ARMv7 server binaries. ARMv7 has package
verification, not a physical-device performance certification.

The host DNS initially resolved TMDB API/images to `127.0.0.1`. For real-service testing,
the same emulator was restarted with `-dns-server 8.8.8.8,1.1.1.1`. No DNS override or
fixed IP was added to production code. A local network blocking TMDB still needs an
accessible DNS/network route. Actual test results and APK integrity are recorded in
`artifacts/tmdb-catalog/verification.txt` after final verification.

## Catalog localization verification

The correction changes only the catalog language binding, lazy detail translation
resolution, the detail cache namespace, and guards for rapid locale changes. The
library metadata preference, UI design, images and server/remote integrations are
preserved. Recommendations are not a current catalog section; this correction does
not introduce new sections or data providers.

The official language and translation contracts are documented in
[TMDB languages](https://developer.themoviedb.org/docs/languages),
[movie translations](https://developer.themoviedb.org/reference/movie-translations),
[TV translations](https://developer.themoviedb.org/reference/tv-series-translations).

Live checks compare movie 157336 (`Интерстеллар` / `Interstellar`) and TV 1396
(`Во все тяжкие` / `Breaking Bad`), localized synopses and search results. A device
test operates the real Appearance language controls while deliberately keeping the
library metadata language different, verifies RU → EN → RU details/search, and
restores the original settings. Unit tests cover absent translations, explicit
language/country selection, Latin localized titles, fallback errors and cache reuse.
Results are recorded in `artifacts/catalog-localization/verification.txt`.
