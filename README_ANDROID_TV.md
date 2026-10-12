# TorrServe Silicon — Android TV

Самостоятельное нативное приложение для Android TV, Kotlin и Compose for TV.
Исходники Android находятся в `android-tv/`; macOS-приложение не изменялось.

## Быстрый запуск

Откройте каталог **android-tv** в Android Studio. APK для проверки лежит в
`artifacts/TorrServe-Silicon-TV-0.1.1-preview-arm64.apk`.
Нужны Android 8.0+ (API 26+) и ARM64. ARM32 и x86 не включены.

```sh
.tools/sdk/platform-tools/adb install -r artifacts/TorrServe-Silicon-TV-0.1.1-preview-arm64.apk
.tools/sdk/platform-tools/adb shell am start -n tv.torrserve.silicon/.MainActivity
```

Preview APK собран с R8 и сокращением ресурсов, но подписан **локальным debug-ключом**.
Он подходит для установки и проверки, не является подписанным публичным релизом.
Debug и preview на этом компьютере используют один сертификат и заменяют друг друга.

TorrServer MatriX.146.1 уже включён в APK. Отдельно устанавливать сервер не нужно.
Он автоматически запускается при открытии приложения. Библиотека и настройки
сохраняются на устройстве. После сворачивания приложение держит сервер во время
воспроизведения; без плеера останавливает его после короткой задержки.

Если Jackett не настроен, можно добавлять magnet через Home или Library.

## Android Studio и SDK на Mac

1. Установите стабильную [Android Studio для Apple Silicon](https://developer.android.com/studio).
2. Откройте `android-tv/`, выберите JDK 17+ для Gradle и дождитесь синхронизации.
3. В SDK Manager установите Android SDK Platform **37.0**, Build Tools **36.0.0**,
   Platform Tools и Android Emulator.
4. Для TV-эмулятора: Device Manager → Create Virtual Device → TV / 1080p →
   Android TV API 36, **arm64-v8a**. x86-образ не подходит ARM64 APK.
5. Run configuration: модуль `app`, Activity `MainActivity`.

В текущем workspace инструменты установлены локально в `.tools/`; Android Studio
пока не устанавливалась. `scripts/build-local.sh` выбирает этот JDK и SDK, если
они существуют. Этот каталог исключён из Git и из архива исходников.
После удаления `.tools/` используйте Android Studio либо переменные JAVA_HOME и
ANDROID_HOME со своим SDK. `android-tv/local.properties` — локальный, не публикуемый файл.

Уже создан AVD `TorrServe_TV_API36`. Ручной запуск с видимым окном:

```sh
scripts/emulator.sh
# Запуск без окна для функциональных тестов:
scripts/emulator.sh -no-window -no-audio -no-snapshot
```

Если эмулятор уже работает, второй экземпляр этого же AVD не запускайте.
В профиле AVD включите аппаратную клавиатуру (`hw.keyboard=yes`). В окне эмулятора
стрелки перемещают фокус, Enter выбирает, Escape возвращает назад. Альтернатива:
Extended Controls → Directional Pad.
На этом Mac графический эмулятор установлен в `~/Library/Android/torrserve-silicon/`:
Qt не находил плагины при запуске из пути workspace с пробелами.
Для Jackett на этом же Mac используйте `http://10.0.2.2:9117` (или свой порт).
При разработке все визуальные проверки выполняет пользователь. Computer Use в
первой реализации не использовался; тесты и измерения выполнялись через CLI/ADB.

На macOS результаты сборки находятся в
`~/Library/Caches/TorrServeSiliconAndroidTV/build/app/`, а не в `android-tv/app/build/`.
Это предотвращает дублирование изменяемых файлов сборки в синхронизируемом Documents.
Путь можно переопределить параметром `-PtorrserveBuildRoot=/absolute/path`.

## Сборка

Зафиксированы AGP 9.4.1, Gradle 9.6.0, Kotlin/Compose compiler 2.4.21,
Compose BOM 2026.09.00, TV Material 1.1.0, Media3 1.11.1, OkHttp 5.5.0 и Coil 3.6.3.
Совместимость подтверждена локальными сборками и тестами.
Wrapper проверяет SHA-256 Gradle, preBuild — SHA-256 встроенного TorrServer.

```sh
scripts/build-local.sh :app:assembleDebug
scripts/build-local.sh :app:testDebugUnitTest :app:lintDebug
# Подписанный локальным debug-ключом оптимизированный preview:
scripts/build-local.sh -PpreviewSigning=true :app:assembleRelease
# Без previewSigning: unsigned release, для отдельной production-подписи:
scripts/build-local.sh :app:assembleRelease
```

Результаты: `android-tv/app/build/outputs/apk/debug/` и `.../release/`.
Production-ключ следует создать и хранить вне репозитория, а готовый unsigned APK
подписать через Android SDK apksigner. Сохраните один production-сертификат для
всех будущих обновлений. Пароли и keystore не следует добавлять в Git.
Неподписанный release APK установить нельзя.

## Настройка Jackett

Settings → Search · Jackett:

- URL, например `http://192.168.1.100:9117` или HTTPS-адрес вашего reverse proxy.
- API Key из Jackett.
- Test Connection проверяет настоящий Torznab `t=caps`.
- Save сохраняет параметры. API-ключ зашифрован AES-GCM с Android Keystore.

`127.0.0.1` на телевизоре означает сам телевизор. Автоматического адреса Jackett
нет. Поддерживаются базовые пути reverse proxy, стандартная проверка TLS и LAN HTTP.
API-ключи и URL с токенами не выводятся в пользовательские ошибки или Logcat.

Поиск выполняется по подтверждению пользователя, отменяет предыдущий запрос и
показывает не больше 200 результатов. Фильтры All / Movies / TV Shows, сортировка
Seeds / Size / Date. Используется `t=search`; фильтры используют категории
Torznab, без предположения о поддержке t=movie/t=tvsearch всеми индексаторами.
Поддерживаются magnet, перенаправление HTTP на magnet и скачивание torrent-файлов
на клиенте с последующей передачей байтов TorrServer.

Add to Library сохраняет раздачу. Watch добавляет её, получает видеофайлы и
запускает единственный файл либо предлагает выбор. Запись определяется hash,
разные релизы с одинаковым названием не объединяются.

## Метаданные

Settings → Metadata: выберите источник и укажите **свой** ключ, затем Save.

- TMDB: API Key v3, основной источник по умолчанию.
- OMDb: ключ OMDb API.
- Kinopoisk: ключ `kinopoiskapiunofficial.tech` (не `kinopoisk.dev`).
- AniList: публичный GraphQL API, ключ для поиска не требуется.

Ключи разных источников сохраняются отдельно. Язык метаданных en-US / ru-RU
независим от языка основных надписей интерфейса en / ru.
Часть технических сообщений первой версии остаётся на английском.
Тема первой версии — тёмная.

Алгоритм очищает названия релизов, определяет год/сезон/эпизод, создаёт ограниченное
число русских и английских вариантов, ранжирует совпадения и отбрасывает слабые.
Кеш: 30 дней найденные данные, 24 часа отсутствие результата; сетевые ошибки
не сохраняются как промахи. Постеры TMDB w342, backdrop w780. Файловый кеш изображений
ограничен 128 MiB, память Coil — 24 MiB.

При отсутствии ключей или сети торренты всё равно добавляются и воспроизводятся.
Сохранённые метаданные остаются доступными без интернета. Refresh Metadata на
странице фильма принудительно повторяет разрешение названия.

## Плеер и пульт

UP / DOWN / LEFT / RIGHT перемещают фокус, OK выбирает действие, BACK возвращается
назад. Ввод — через системную клавиатуру, открывающуюся при выборе поля.
Скролл сохраняется в разделах; возврат из карточки восстанавливает выбранный постер.

В плеере OK показывает управление. При скрытых элементах LEFT / RIGHT перематывают;
при открытых управляют панелью. Настройки плеера позволяют выбирать доступные
аудиодорожки и субтитры. BACK останавливает текущий просмотр и возвращает на карточку.
При сворачивании приложения воспроизведение продолжается через MediaSessionService.
Позиция сохраняется при паузе/остановке и раз в 30 секунд.

Поддержка конкретных кодеков, HDR и многоканального звука зависит от устройства.
Аппаратное декодирование на физической приставке не проверено. Эмуляторный тест
использует собственный короткий H.264 ролик, не доказывает поддержку всех форматов.

## Обновления

Check TorrServer проверяет официальные GitHub Releases. Встроенный ELF меняется
только с новой версией APK; скачивание и запуск ELF из записываемого каталога
приложения не используются.

Для Check App задайте в Settings репозиторий Android TV в формате owner/repository
и сохраните его. Публичный репозиторий и релиз этим запуском не создавались.
Нужен GitHub Release с одним ARM64/universal APK и GitHub SHA-256 digest для asset.
Проверяются checksum, applicationId, увеличение versionCode и сертификат подписи.
Install Update открывает стандартный Android Package Installer; при необходимости
сначала настройки разрешения установки из этого источника. Тихой установки нет.
Смена сертификата и rollback к меньшему versionCode отклоняются.

## Проверки

```sh
scripts/build-local.sh :app:testDebugUnitTest :app:lintDebug
# При работающем ARM64 TV AVD, Xcode command line tools, Python 3 и Go 1.27+:
scripts/run-native-tests.sh
```

Go нужен **только для тестового BitTorrent-сида на Mac**, в приложение он не добавлен.
Скрипт создаёт собственное видео, torrent, локальный tracker/seed на 127.0.0.1:
18765/18766, выполняет instrumentation и останавливает оба тестовых процесса.
В APK production нет тестового видео, fake API, MockWebServer или тестовых ключей.

Подтверждены: 25 JVM-тестов, 4 instrumentation-теста Android TV API 36, lint без
ошибок, Debug и оптимизированная Release-сборка. Полный результат и ограничения:
[docs/verification.md](docs/verification.md).

## Измерения

```sh
scripts/profile-adb.sh
.tools/sdk/platform-tools/adb logcat -s TorrServeTiming:I AndroidRuntime:E
.tools/sdk/platform-tools/adb shell dumpsys gfxinfo tv.torrserve.silicon framestats
```

Профилировщик сохраняет отдельно startup, память приложения и TorrServer, кадры.
Debug Logcat содержит только фиксированные названия метрик и длительность:
activity_ui, server_start, library_load, search_results, playback_first_frame.
FrameMonitor считает кадры выше бюджета текущей частоты экрана. Для плотных
библиотек и реальных результатов Jackett нужно отдельно измерить прокрутку.
Цели <2 s / <500 ms / 60 FPS не гарантируются измерением одного эмулятора.

## Документы

- [Архитектура и источники](docs/architecture.md)
- [Проверки и ручная приёмка](docs/verification.md)
- [Release notes](docs/release-notes-0.1.1.md)
- `android-tv/TORRSERVER_LICENSE` и встроенный экран Open Source Licenses.
