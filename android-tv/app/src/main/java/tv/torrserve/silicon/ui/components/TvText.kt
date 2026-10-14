package tv.torrserve.silicon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.LocalTextStyle

val LocalUiLanguage = compositionLocalOf { "en" }
private val russian = mapOf(
    "Catalog" to "Каталог",
    "Explore catalog" to "Открыть каталог",
    "Your added torrents and viewing progress appear here. Explore movies and series in Catalog." to "Здесь появятся добавленные вами торренты и история просмотра. Фильмы и сериалы можно посмотреть в каталоге.",
    "Find films with Jackett. Your library and viewing progress will appear here." to "Находите фильмы через Jackett. Здесь появятся ваша медиатека и история просмотра.",
    "01  Connect Jackett" to "01  Подключите Jackett",
    "Search your trackers in one place." to "Поиск по вашим трекерам в одном месте.",
    "Find a film in Search and add it to your library. Posters and film details are loaded automatically." to "Найдите фильм в поиске и добавьте его в медиатеку. Постеры и информация загрузятся автоматически.",
    "YOUR PERSONAL CINEMA" to "ВАШ ДОМАШНИЙ КИНОЗАЛ",
    "Your next movie starts here." to "Ваш следующий фильм — здесь.",
    "Add a magnet link or search with Jackett. Your films and viewing progress will appear here." to "Добавьте magnet-ссылку или найдите фильм через Jackett. Здесь появятся ваши фильмы и история просмотра.",
    "Make it yours" to "Начните свою коллекцию",
    "Magnet links or Jackett search." to "Magnet-ссылки или поиск Jackett.",
    "A built-in player, ready to go." to "Встроенный плеер уже готов.",
    "01  Add a film" to "01  Добавьте фильм",
    "Use a magnet link, or connect Jackett in Settings." to "Используйте magnet-ссылку или подключите Jackett в настройках.",
    "02  Choose and watch" to "02  Выберите и смотрите",
    "Open a film to play it with the built-in player." to "Откройте фильм и запустите встроенный плеер.",
    "03  Continue anytime" to "03  Продолжайте в любое время",
    "Your place is saved automatically." to "Позиция просмотра сохраняется автоматически.",
    "Server Online" to "Сервер работает", "Server Offline" to "Сервер выключен", "Starting…" to "Запуск…",
    "↑ ↓ ← →  Navigate     OK  Select     BACK  Return" to "↑ ↓ ← →  Навигация     OK  Выбор     BACK  Назад",
    "Find your next film or series." to "Найдите фильм или сериал для просмотра.",
    "Open Settings" to "Открыть настройки",
    "Add a magnet link to start your collection. Posters and film details are loaded automatically." to "Добавьте magnet-ссылку. Постеры и информация о фильмах загрузятся автоматически.",
    "No titles match this filter." to "По этому фильтру фильмы не найдены.",
    "Set up search, playback and your library." to "Настройте поиск, воспроизведение и медиатеку.",
    "Home" to "Главная", "Search" to "Поиск", "Library" to "Медиатека", "Settings" to "Настройки",
    "Your library is empty." to "Медиатека пока пуста.", "Find something to watch." to "Найдите, что посмотреть.",
    "Search Torrents" to "Найти торренты", "Add Magnet Link" to "Добавить magnet-ссылку", "Add Magnet" to "Добавить magnet",
    "Continue Watching" to "Продолжить просмотр", "Recently Added" to "Недавно добавленные", "My Library" to "Моя медиатека",
    "Refresh" to "Обновить", "Title" to "Название", "Year" to "Год", "All" to "Все", "Movies" to "Фильмы", "TV Shows" to "Сериалы",
    "Loading…" to "Загрузка…", "No titles here yet." to "Здесь пока нет фильмов.", "Cancel" to "Отмена", "Done" to "Готово",
    "Connect Jackett to search movies, series and torrents." to "Подключите Jackett для поиска фильмов, сериалов и торрентов.",
    "Connect Jackett" to "Подключить Jackett", "Search movies, series or torrents" to "Название фильма, сериала или торрента",
    "Seeds" to "Сиды", "Size" to "Размер", "Date" to "Дата", "Searching…" to "Поиск…",
    "No torrents found. Try another title." to "Раздачи не найдены. Попробуйте другое название.",
    "Add to Library" to "В медиатеку", "Added" to "Добавлено", "Watch" to "Смотреть", "Adding…" to "Добавление…",
    "Back" to "Назад", "Find Torrents" to "Найти раздачи", "Files" to "Файлы", "Refresh Metadata" to "Обновить метаданные", "Remove" to "Удалить",
    "Remove from library?" to "Удалить из медиатеки?", "The torrent and its saved playback history will be removed." to "Торрент и история его просмотра будут удалены.",
    "Choose a video" to "Выберите видео", "Save" to "Сохранить", "Saving…" to "Сохранение…", "Server" to "Сервер", "Stopped" to "Остановлен",
    "Starting…" to "Запуск…", "Start" to "Запустить", "Stop" to "Остановить", "Restart" to "Перезапустить", "Port" to "Порт", "Cache (MB)" to "Кеш (МБ)",
    "Search · Jackett" to "Поиск · Jackett", "Test Connection" to "Проверить подключение", "Metadata" to "Метаданные", "Metadata language" to "Язык метаданных",
    "Clear Metadata Cache" to "Очистить кеш метаданных", "Playback" to "Воспроизведение", "Audio language (en / ru)" to "Язык аудио (en / ru)",
    "Subtitle language (en / ru)" to "Язык субтитров (en / ru)", "Updates" to "Обновления", "Check TorrServer" to "Проверить TorrServer", "Check App" to "Проверить приложение",
    "Install Update" to "Установить обновление", "About" to "О приложении", "Appearance" to "Оформление", "Language" to "Язык", "Theme: Dark" to "Тема: тёмная",
    "Settings saved." to "Настройки сохранены.", "Connected to Jackett." to "Подключение к Jackett установлено.", "TorrServer is running." to "TorrServer запущен.",
    "TorrServer stopped." to "TorrServer остановлен.", "TorrServer restarted." to "TorrServer перезапущен.", "Added to your library." to "Добавлено в медиатеку.",
    "Metadata updated." to "Метаданные обновлены.", "Set the metadata provider API key in Settings first." to "Сначала укажите API-ключ источника метаданных в настройках.",
    "Resume Playback: On" to "Продолжение просмотра: включено", "Resume Playback: Off" to "Продолжение просмотра: выключено",
    "Checking / downloading…" to "Проверка / загрузка…", "Open Source Licenses" to "Лицензии открытого ПО",
    "Connected" to "Подключено", "Disconnected" to "Не подключено", "Invalid API Key" to "Неверный API-ключ",
    "Connection Error" to "Ошибка подключения", "Timeout" to "Превышено время ожидания"
)
@Composable fun localize(value: String): String {
    if(LocalUiLanguage.current != "ru") return value
    val prefix = if(value.startsWith("✓ ") || value.startsWith("● ")) value.take(2) else ""
    val plain = value.removePrefix(prefix)
    return prefix + (russian[plain] ?: if(plain.startsWith("Enter ")) "Введите ${russian[plain.removePrefix("Enter ")] ?: plain.removePrefix("Enter ")}" else plain)
}
@Composable fun TvText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, style: TextStyle = LocalTextStyle.current, translate: Boolean = true) {
    androidx.tv.material3.Text(if(translate) localize(text) else text, modifier = modifier, color = color,
        fontSize = fontSize, maxLines = maxLines, overflow = overflow, style = style)
}
