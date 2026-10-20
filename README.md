![TorrFrame](icons/TorrFrame%20icon%2016x9.png)

[English](README.md) · [Русский](README.ru.md)

# TorrFrame

TorrFrame is an app for watching movies and TV shows on Android TV. Pick a title from the catalog, find a torrent, and start watching with your regular TV remote. TorrServe is included, so there is no need to install it separately.

## What you can do

- Browse movies and TV shows with posters, descriptions, ratings, and similar titles.
- Find torrents with TorrentSearch and, optionally, Jackett.
- Save titles to Favorites and Watch later.
- Keep track of torrents added to TorrServe in your Library.
- Use the built-in player with seeking, audio tracks, subtitles, and playback resume.
- Watch with an installed external player.
- Control the app from your phone's browser, including search and adding magnet links.
- Choose Russian or English, a light or dark theme, an accent color, and the interface size.

## What you need

A TV or streaming box running **Android TV 8.0 or later**, an internet connection, and a remote with directional buttons and OK. TorrFrame supports ARM64 and ARMv7 devices, including boxes running 32-bit Android.

## Installation

1. Download the TorrFrame APK file.
2. Transfer it to your TV or streaming box over your local network or on a USB drive.
3. Open the APK using a file manager on the TV. If Android asks you to allow installation from this source, enable the permission and try again.
4. Open TorrFrame from your apps list.

The built-in TorrServe starts automatically. Everyday use does not require a computer, a separate server, or a CUB account.

To update, install the new APK over the existing app. You do not need to uninstall TorrFrame first: keeping the app installed preserves your settings and library.

## Start watching

1. Open **Catalog** and choose a movie or TV show. You can search by title or browse genres.
2. Select **TorrentSearch** on the title's details page to search for matching torrents.
3. Choose a torrent and start playback. If it contains several video files, select the movie or episode you want.

Added torrents appear in **Library**. The next time you watch, the built-in player can offer to resume from your saved position if resume is enabled in settings.

The catalog provides information about titles. A title appearing there does not guarantee an available torrent. How quickly playback starts depends on your connection and the number of peers sharing the file.

### Catalog and Library: what's the difference?

**Catalog** helps you choose what to watch. It contains collections, genres, search, and title details.

**Library** shows torrents added to TorrServe. These files are not necessarily fully downloaded to your device.

**Favorites** and **Watch later** are separate lists of saved titles. Adding a title to either list does not start a download. You can access both lists from Catalog and Library.

### Favorites and Watch later

On a title's details page, select the heart to add it to Favorites or the clock to save it for later. Select the icon again to remove the title from that list.

You can also hold **OK** on a catalog poster and choose an action from the menu.

In **Settings → Catalog**, you can show or hide the Favorites and Watch later rows at the top of the catalog home page. Hiding a row does not remove your saved titles.

## Using your TV remote

| Button | Action |
| --- | --- |
| Directional buttons | Move between items |
| OK | Open or select |
| Back | Close a menu or return to the previous screen |
| Hold OK on a catalog poster | Open the Favorites and Watch later menu |
| Hold OK on the Catalog tab | Return to the catalog home page |

In the built-in player, **OK** reveals the controls. Use the playback button to pause or resume, and left/right on the progress bar to seek. The player menus let you choose audio tracks, subtitles, and playback speed.

## Control from your phone

Phone Remote lets you use TorrFrame from your phone's browser. You do not need to install a separate phone app.

1. Connect your phone and TV to the same home network.
2. On the TV, open **Settings → Phone Remote** and enable it.
3. Display the QR code and scan it with your phone. Alternatively, open the address shown on the TV in your browser and enter the on-screen code.

The phone interface has four tabs:

- **Search** — the movie and TV catalog, plus torrent search.
- **Library** — added torrents and magnet link input.
- **Remote** — playback controls for your TV.
- **Settings** — app and server preferences.

Favorites, Watch later, and Library are shared between the TV and phone. You can switch the phone interface between English and Russian independently of the TV language.

Phone Remote's playback controls work with the built-in player. External player features depend on the app you choose.

## Useful settings

Open settings using the round gear button in the upper-right corner.

- **Catalog** — choose TMDB, CUB, or automatic mode, and show or hide your personal rows.
- **TorrentSearch** — search sources, quality, file size, and other torrent filters.
- **Jackett** — enable optional search and enter your Jackett address and API key. Leave it disabled if you do not use Jackett.
- **Playback** — choose the built-in or an external player, playback resume, and preferred track languages.
- **Appearance** — language, theme, accent color, interface size, animations, and navigation hints.
- **TorrServer** — configure the built-in server or connect to your own external server.

### Choosing a catalog source

**TMDB** is selected by default. In **automatic mode**, TorrFrame tries TMDB first and falls back to CUB if loading collections or search fails. You can also select **CUB** manually.

Full descriptions and similar titles still come from TMDB in every mode. Switching to CUB may therefore not resolve a problem with loading a full details page. Posters use separate fallback addresses regardless of the catalog source.

## If something isn't working

**The catalog or posters won't load.** Check the TV's connection, retry, and try automatic catalog mode. Access to TMDB, CUB, and image servers depends on your network and internet provider. One of these services can fail even when the rest of the internet works.

**Torrent search finds nothing.** Check the enabled sources and filters in TorrentSearch settings. Try the movie's original title. A third-party source may be temporarily unavailable on your network.

**Playback takes a long time to start or keeps stopping.** Try a torrent with more seeders or a smaller file. Playback depends on both your network speed and the availability of the torrent itself.

**Phone Remote won't open.** Make sure the phone and TV really are on the same network and Phone Remote is enabled. Display the QR code again: the TV's address may have changed after switching networks. Guest Wi-Fi may prevent devices from communicating with each other.

**The interface is too large or too small.** Adjust the interface size under Appearance. You can also disable animations there if they do not run smoothly on your device.

## Acknowledgements

TorrFrame uses [TorrServer](https://github.com/YouROK/TorrServer), data from [TMDB](https://www.themoviedb.org/), the public [CUB](https://tmdb.cub.best/) catalog, and work from [TorrentSearch](https://github.com/prajwalch/TorrentSearch). Third-party licenses are available under About.
