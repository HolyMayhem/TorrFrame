![TorrFrame](icons/TorrFrame%20icon%2016x9.png)

# TorrFrame

### Movies, torrents, and your library on Android TV

Choose a title, find a torrent, and start watching — with your regular TV remote.

[Installation](#installation) · [Phone Remote](#phone-remote) · [English](README.md) · [Русский](README.ru.md)

---

Browse the catalog, save something for later, or go straight to your library. TorrFrame brings title information, torrent search, and playback together in one app.

TorrServe is built in and starts automatically. You do not need a computer or a separate server to start watching.

## Features

| | |
| --- | --- |
| **Movie and TV catalog** | Posters, descriptions, ratings, genres, and similar titles. TMDB, CUB, and automatic source selection. |
| **Torrent search** | Find torrents with TorrentSearch. Connect your own Jackett for additional search. |
| **Your saved titles** | Favorites and Watch later, with optional rows on the catalog home page. |
| **TorrServe library** | Access the torrents you have added, or connect to your own external TorrServe. |
| **Built-in player** | Seeking, audio tracks, subtitles, playback speed, and resume. You can also use an installed external player. |
| **Phone Remote** | Browse, search, add magnet links, and control playback from your phone's browser. |
| **Make it yours** | English and Russian, light and dark themes, accent colors, interface size, animations, and optional sounds. |

## Installation

You will need a TV or streaming box running **Android TV 8.0 or later**, an internet connection, and a remote with directional buttons and OK. ARM64 and ARMv7 devices are supported, including boxes running 32-bit Android.

1. Download the **TorrFrame APK** and transfer it to your TV over your local network or on a USB drive.
2. Open the APK using a file manager on the TV. Allow installation from this source if Android asks.
3. Launch TorrFrame from your apps list.

On first launch, the app uses Russian, Phone Remote is enabled, and navigation hints are hidden. You can change these in settings.

To update, install the new APK over the existing app without uninstalling it first. This keeps your settings and library.

## Start watching

**Catalog → choose a title → TorrentSearch → choose a torrent → watch.**

If a torrent contains several video files, select the movie or episode you want. Added torrents appear in **Library**; they are not necessarily fully downloaded to your device.

Use the heart on a title's page for **Favorites**, or the clock for **Watch later**. These lists save titles without starting a download. Both are accessible from Catalog and Library; their home page rows can be hidden under **Settings → Catalog**.

Navigate with the directional buttons, select with **OK**, and return with **Back**. Hold **OK** on a catalog poster for its actions, or on the **Catalog** tab to return to the catalog home page.

## Phone Remote

Use TorrFrame from your phone without installing another app.

1. Connect your phone and TV to the **same home network**.
2. Open **Settings → Phone Remote** on the TV. It is enabled by default.
3. Scan the QR code, or open the displayed address in your browser and enter the on-screen code.

The phone interface has four tabs: **Search**, **Library**, **Remote**, and **Settings**. Browse the catalog, find torrents, paste a magnet link, or manage your saved titles. Favorites, Watch later, and Library are shared with the TV. The phone's language can be changed independently.

Playback controls work with TorrFrame's built-in player. External player features depend on the app you choose.

## Catalog sources

**TMDB** is selected by default. Choose **CUB** or **automatic mode** under **Settings → Catalog**. Automatic mode tries TMDB first and uses CUB if loading collections or search fails. A CUB account is not required.

Full title details and similar titles still use TMDB in every mode. Posters have separate fallback addresses. Availability of these services depends on your network and internet provider; switching sources may not resolve every loading problem.

The catalog helps you choose what to watch. Torrent availability and playback speed depend on the search sources, your connection, and the peers sharing the file.

## Optional search with Jackett

If you use Jackett, enable it under **Settings → Jackett** and enter your server address and API key. A Jackett search button will appear on title pages.

Jackett is optional. Leave it disabled to use TorrentSearch on its own.

## Appearance and sounds

Open the round gear button, then **Appearance**, to change the language, theme, accent color, interface size, and animations.

Quiet interface sounds and the startup sound are enabled by default. Turn them off with **Interface sounds** in the same section. Movie and TV audio is controlled separately.

## Acknowledgements

TorrFrame is an independent project using [TorrServer](https://github.com/YouROK/TorrServer), data from [TMDB](https://www.themoviedb.org/), the public [CUB](https://tmdb.cub.best/) catalog, and work from [TorrentSearch](https://github.com/prajwalch/TorrentSearch).

Thank you to their authors and contributors. Third-party licenses are available in **About**.
