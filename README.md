<p align="center">
  <img src="icons/TorrFrame%20square%20icon%20ver2.png" width="128" height="128" alt="TorrFrame app icon">
</p>

<h1 align="center">TorrFrame</h1>

<p align="center">
  <strong>Movies, series, and torrents on Android TV</strong><br>
  Browse the catalog, find a torrent, and watch — with your TV remote or phone.
</p>

<p align="center">
  <a href="https://github.com/HolyMayhem/TorrFrame/releases">Download APK</a>
  &nbsp;·&nbsp;
  <a href="#installation">Installation</a>
  &nbsp;·&nbsp;
  <a href="#phone-remote">Phone Remote</a>
  &nbsp;·&nbsp;
  <strong>English</strong> · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android_TV-8.0%2B-3DDC84?style=flat-square" alt="Android TV 8.0 or later">
  <img src="https://img.shields.io/badge/Architecture-ARM64_%7C_ARMv7-77B900?style=flat-square" alt="ARM64 and ARMv7">
  <img src="https://img.shields.io/badge/TorrServer-included-77B900?style=flat-square" alt="TorrServer included">
  <img src="https://img.shields.io/badge/Languages-EN_%7C_RU-4388CC?style=flat-square" alt="English and Russian">
</p>

---

Choose a movie or series, find a torrent, and start watching in the built-in player. TorrFrame brings the catalog, torrent search, saved titles, and your media library together on the big screen.

**TorrServer is already built in.** It starts automatically; no computer or separate server installation is needed. You can also connect your own TorrServer.

## Features

| | What you can do |
| --- | --- |
| **Movie and TV catalog** | Explore titles with posters, descriptions, ratings, genres, and similar films. |
| **Torrent search** | Search with TorrentSearch, or connect your own Jackett for additional sources. |
| **Favorites and Watch later** | Save titles and optionally display these collections on the catalog home page. |
| **Your media library** | Browse torrents added to TorrServer and choose the movie or episode to play. |
| **Built-in player** | Seek, switch audio tracks and subtitles, adjust playback speed, and resume where you left off. An installed external player can also be used. |
| **Phone Remote** | Browse, search, add magnet links, and control playback from your phone’s browser. |
| **First-time setup** | Choose your language, appearance, phone remote, and server in four short steps. |
| **Personal appearance** | English and Russian, light and dark themes, accent colors, interface size, animations, and optional sounds. |

## Installation

**Requires Android TV 8.0 or later, an ARM64 or ARMv7 device, and an internet connection.** Use a remote with directional buttons, OK, and Back.

1. Download the **TorrFrame APK** from [Releases](https://github.com/HolyMayhem/TorrFrame/releases).
2. Transfer it to your TV over your local network or on a USB drive.
3. Open the APK with a file manager on the TV and install it.
4. Launch **TorrFrame** from your apps list.

> [!NOTE]
> If Android asks, allow the file manager to install apps from this source.

To update TorrFrame, install the new APK over the existing app without uninstalling it first. A regular update keeps your settings and library. The built-in TorrServer is updated with the app.

## First launch

The setup wizard helps you choose a language and appearance, connect your phone, and select a TorrServer. You can skip it and start using the app immediately.

The defaults are **Russian**, **Phone Remote on**, and **navigation hints off**. You can change them later. To repeat setup, open **Settings → About → First-time setup**.

## Start watching

**Catalog → choose a title → TorrentSearch → choose a torrent → watch.**

If a torrent contains several video files, select the movie or episode you want. Added torrents appear in **Library** — this is the content added to your TorrServer, not a list of everything in the catalog. A torrent does not need to be fully downloaded before playback can begin.

Use the heart on a title’s page for **Favorites**, or the clock for **Watch later**. Both lists are available from Catalog and Library. Their catalog home page rows can be hidden under **Settings → Catalog**.

| Remote button | Action |
| --- | --- |
| **Directional buttons** | Move between items. |
| **OK** | Select or open an item. |
| **Back** | Return to the previous screen. |
| **Hold OK on a catalog poster** | Open the title’s action menu. |
| **Hold OK on the Catalog tab** | Return to the catalog home page. |

## Phone Remote

Type on your phone instead of entering each letter with the TV remote. Find films, paste magnet links, manage your library, and control the built-in player. **No phone app is required.**

1. Connect your phone and TV to the **same home network**.
2. Open the phone icon in the TV’s top bar, or go to **Settings → Phone Remote**.
3. Scan the QR code. You can also open the displayed address in your browser and enter the on-screen pairing code.

The phone interface has four tabs: **Search**, **Library**, **Remote**, and **Settings**. Your library, Favorites, and Watch later are shared with the TV. The phone interface language can be changed independently.

Playback controls work with TorrFrame’s built-in player. External player features depend on the app you choose.

## Catalog and search

**TMDB** is selected by default. Under **Settings → Catalog**, you can choose **CUB** or **automatic mode**. Automatic mode tries TMDB first and uses CUB if loading collections or search fails. A CUB account is not required.

Full title details and similar films use TMDB in every mode. Images have separate fallback addresses. Catalog and image availability depend on access to these services from your network.

To use your own [Jackett](https://github.com/Jackett/Jackett), enable it under **Settings → Jackett** and enter its address and API key. Its search button then appears on title pages. Jackett is optional; TorrentSearch works independently.

Torrent availability and playback speed depend on the search sources, your connection, and the peers sharing the file.

## Appearance and sounds

Open **Settings → Appearance** to change the language, theme, accent color, interface size, and animations.

Quiet interface sounds and the startup sound can be turned off with **Interface sounds**. Movie and TV audio is controlled separately.

## Acknowledgements

TorrFrame is an independent app using [TorrServer](https://github.com/YouROK/TorrServer), data from [TMDB](https://www.themoviedb.org/), the public [CUB](https://tmdb.cub.best/) catalog, and work from [TorrentSearch](https://github.com/prajwalch/TorrentSearch).

Thank you to their authors and contributors. These projects and services are distributed under their own terms and licenses. Third-party licenses are available in **Settings → About**.
