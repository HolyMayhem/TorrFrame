# Refreshed TV interface

Branch: `codex/refreshed-interface`, based on `torrentsearch` at `dea92b8`.
That baseline preserves the existing startup video hold, five-second loading hint,
launcher icon and all previous catalog/network work before the visual changes.

The implementation follows the third, minimal concept using native Compose TV:

- centered oval Catalog/Library buttons, matching the server-status pill;
- a quiet search field, three type filters and a separate scrollable genre picker;
- persistent favorites/watch-later shortcuts with real counts;
- larger, frameless 2:3 catalog artwork with a high-contrast focus ring;
- a horizontal watch-later shelf, with complete poster fallback when no backdrop exists;
- collection mosaics matching the artwork dimensions, without extra shelf action buttons;
- small, muted brand marks for unavailable catalog artwork;
- a compact header with a larger brand mark, a bordered server-status pill and circular icon controls;
- a neutral graphite palette and restrained surfaces;
- matching library cards, while preserving saved theme/accent/scale/language settings;
- a soft gradient beneath the header only after scrolling details;
- Favorites/Watch later shortcuts in Library, returning focus to their launching buttons;
- a small localized Library hint clarifying that its media was added to TorrServe;
- wide torrent-search actions and compact favorite/watch-later icon buttons in details;
- a persistent Jackett enable switch, hiding its TV actions while disabled.

The selected poster has its own TV focus indication. Selection underlines do not
replace focus feedback. The genre dialog restores focus to its launcher on selection
or cancellation, and opening a currently selected offscreen genre scrolls it into view.
Detail/collection return, pagination, search, long-press list menus and recovery of
images remain on the existing state and repository paths. Movie images and data are
real catalog results; generated concept artwork is not bundled in the application.

The original settings drawer, phone remote, playback and startup sequence are retained.
No API keys, catalog source defaults, application identity or signing aliases change.
There is no full-screen blur, animated wallpaper, additional catalog API or polling.

Validation artifacts and the approved concept are in `artifacts/refreshed-interface/`.

## Validation

The previously postponed follow-up checks are now covered: 216 app JVM, 26 search
module JVM, 15 Web and 72 selected Android cases passed across targeted runs.
This includes icon actions, shared Jackett enablement, real TV search entry,
header scale/language bounds and settings navigation. See [verification](verification.md)
for fixture limits and the timing-sensitive genre focus rerun.

The remaining figures below describe the earlier visual revision.

- 206 JVM tests passed; lint: zero errors, 35 existing warnings.
- 40 device tests passed for this revision: catalog/detail navigation, artwork/mosaic
  geometry, direct shelf navigation, genre selection/cancellation, list removal,
  collection paging, image recovery, Library shortcuts and outage states, and the header
  in Russian/English at the supported 70%, 85% and 110% UI scales, light/dark, with D-pad settings return.
- Debug and optimized Release previews build and have matching signing certificates.
- Both packaged variants retain the original MP4 and ARMv7/ARM64 TorrServer libraries.
- The final Debug APK is installed on the emulator. Live catalog, Library and detail screenshots are
  saved alongside the concept; physical Mi Box visuals have not been checked here.
