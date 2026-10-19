# Refreshed TV interface

Branch: `codex/refreshed-interface`, based on `torrentsearch` at `dea92b8`.
That baseline preserves the existing startup video hold, five-second loading hint,
launcher icon and all previous catalog/network work before the visual changes.

The implementation follows the third, minimal concept using native Compose TV:

- centered Catalog/Library tabs with an understated selection underline;
- a quiet search field, three type filters and a separate scrollable genre picker;
- persistent favorites/watch-later shortcuts with real counts;
- larger, frameless 3:4 catalog artwork with a high-contrast focus ring;
- a horizontal watch-later shelf, with complete poster fallback when no backdrop exists;
- contextual Details and Favorites actions using the existing user-list storage;
- a neutral graphite palette, restrained surfaces and consistent circular header controls;
- matching library cards, while preserving saved theme/accent/scale/language settings.

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

- 206 JVM tests passed; lint: zero errors, 35 existing warnings.
- 33 catalog/detail navigation tests passed, including contextual favorites actions,
  genre selection/cancellation, list removal, collection paging and image recovery.
- Two settings/localization tests passed; the new header test covers Russian/English
  at the supported 70%, 85% and 110% UI scales, light/dark, and D-pad settings return.
- Debug and optimized Release previews build and have matching signing certificates.
- Both packaged variants retain the original MP4 and ARMv7/ARM64 TorrServer libraries.
- The final Debug APK is installed on the emulator. Live catalog/genre screenshots are
  saved alongside the concept; physical Mi Box visuals have not been checked here.
