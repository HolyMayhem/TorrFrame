# Android TV interface language

The existing `Settings.uiLanguage` (`en` / `ru`) controls application text independently of the device language. The metadata language, preferred audio and subtitles remain separate settings. Catalog TMDB localization continues to use its existing interface-language flow.

- `res/values/strings.xml`: Russian UI resources (existing fallback).
- `res/values-en/strings.xml`: complete English equivalents, with matching keys and format arguments.
- `AppLanguage`: supplies localized Compose resources and context without recreating the Activity or changing density, UI scale or navigation state.
- `Context.uiString`: reads the same saved preference for ViewModels, services and Phone Remote responses. Do not use the application context's unqualified `getString` for UI messages.
- Native player Activity and service attach a localized context for Media3 controls. Audio/subtitle selection remains unchanged.
- Ongoing TorrServer and Phone Remote notifications refresh their text after an interface-language change without restarting either server.

Existing catalog and TorrentSearch bilingual labels and the legacy `TvText` translation dictionary remain in use. User-entered values, film names, torrent filenames, licenses and server identifiers must not be passed through automatic UI translation. Add new shared UI text to both resource files.

Validation: `LocalizationTests` checks resource parity, format arguments and network error translation. `LocalizationNavigationTest` checks all settings categories, dialogs, repeated EN/RU switching, native-context ownership, untouched user input and separation from metadata language. Remote navigation and native playback integration tests cover focus and playback regressions. No Computer Use is required for these automated checks.
