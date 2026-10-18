# TorrentSearch provider adaptation

Source: https://github.com/prajwalch/TorrentSearch
Pinned commit: 661c5adf3493add41ed2a6bda422b22d8aa73533
License: MIT; Copyright (c) 2026 Prajwal Chapagain. Full notice: LICENSE-TorrentSearch.txt.

Adapted search endpoints, form/category mappings, result fields and selectors from providers/Rutor.kt, NoNameClub.kt, TorrentsCSV.kt, Knaben.kt, ThePirateBay.kt, Yts.kt, Nyaa.kt, ThirteenThirtySevenX.kt and Eztv.kt.

Changes: use the host bounded/cancellable HTTP transport, org.json instead of Kotlin serialization, portable result models, nullable counts, canonical info-hash deduplication, seed sorting and lazy magnet resolution. Upstream Android UI, accounts, storage, browser/challenge solver, Koin and Ktor client were not imported. Dedicated adult, book, game and audio providers are outside this film/series feature. Website availability and access restrictions remain independent of the code license.

HTML parsing uses jsoup 1.23.2 (MIT, Jonathan Hedley); its license is bundled in the application. Existing coroutines and host HTTP client are reused.
