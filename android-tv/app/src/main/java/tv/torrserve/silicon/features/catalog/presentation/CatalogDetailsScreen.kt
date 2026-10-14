package tv.torrserve.silicon.features.catalog.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable internal fun CatalogDetailsScreen(state: CatalogUi, back: () -> Unit, retry: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val details = state.details.data
    val backdrop = details?.item?.backdrop.orEmpty()
    // An image failure changes only the presentation; metadata and its cache stay intact.
    var backdropFailed by remember(state.selected, backdrop) { mutableStateOf(false) }
    var restoreHeroAfterFailure by remember(state.selected, backdrop) { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    val cinematic = backdrop.isNotBlank() && !backdropFailed
    val scroll = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("catalog:details")) {
        // Observe the current theme density even if physical constraints did not change.
        val density = LocalDensity.current
        val viewportWidth = with(density) { constraints.maxWidth.toDp() }
        val viewportHeight = with(density) { constraints.maxHeight.toDp() }
        val heroHeight = (viewportHeight * .7f).coerceAtLeast(240.dp)
        val titleSize = (viewportWidth.value * .044f).coerceIn(32f, 48f).sp
        val cinematicPosterWidth = viewportWidth * .21f
        val largePosterWidth = (viewportWidth * .21f).coerceIn(144.dp, 208.dp)
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("catalog:detail:scroll"),
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("hero") {
                // The back button keeps the same composition/focus identity while the layout changes.
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    val focusModifier = Modifier.focusRequester(heroFocus).focusProperties { up = backFocus }
                        .onFocusChanged { heroFocused = it.isFocused }.testTag("catalog:detail:hero")
                    if (details != null && cinematic) CinematicDetails(details, heroHeight, cinematicPosterWidth, titleSize,
                        focusModifier, onBackdropError = {
                            restoreHeroAfterFailure = heroFocused
                            backdropFailed = true
                        })
                    else if (details != null) Box(Modifier.padding(top = 56.dp)) {
                        PosterDetails(details, largePosterWidth, titleSize, focusModifier)
                    }
                    else if (state.details.loading) Row(Modifier.padding(top = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        CatalogSkeleton(Modifier.width(160.dp))
                        TvText(catalogText("Loading…", "Загрузка…"), color = Muted)
                    }
                    TvBackAction(modifier = Modifier.align(Alignment.TopStart)
                        .padding(if (cinematic) 16.dp else 0.dp).focusRequester(backFocus)
                        .focusProperties { if (details != null) down = heroFocus }
                        .testTag("catalog:back"), action = back)
                }
            }
            if (state.details.error) item("error") { CatalogError(retry, cached = details != null) }
            if (details != null) {
                if (cinematic) item("overview") { DetailSynopsis(details, Modifier.fillMaxWidth()) }
                item("credits") { DetailCredits(details) }
                item("source") {
                    TvText(catalogText("Movie information and images from TMDB", "Информация и изображения предоставлены TMDB"),
                        color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
    LaunchedEffect(state.selected) { withFrameNanos { }; backFocus.requestFocus() }
    // Keep the same remote focus target if the backdrop fails while reading the hero.
    LaunchedEffect(backdropFailed) {
        if (backdropFailed && restoreHeroAfterFailure) {
            withFrameNanos { }; heroFocus.requestFocus(); restoreHeroAfterFailure = false
        }
    }
}

@Composable private fun CinematicDetails(details: CatalogDetails, height: Dp, posterWidth: Dp,
    titleSize: TextUnit, modifier: Modifier, onBackdropError: () -> Unit) {
    val background = Background
    val posterOverlap = posterWidth * 1.5f * .42f
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            DetailRegion(modifier.fillMaxWidth(), padding = 0.dp) {
                Box(Modifier.fillMaxWidth().testTag("catalog:detail:cinematic")) {
                    AsyncImage(details.item.backdrop.replace("/t/p/w780/", "/t/p/w1280/"), contentDescription = null,
                        contentScale = ContentScale.Crop, onError = { onBackdropError() },
                        modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)))
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                        0f to background.copy(alpha = .45f), .4f to background.copy(alpha = .18f),
                        1f to background.copy(alpha = .04f))))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to background.copy(alpha = .2f), .35f to background.copy(alpha = .08f),
                        .7f to background.copy(alpha = .6f), 1f to background)))
                    Column(Modifier.fillMaxWidth().heightIn(min = height)
                        .padding(start = 16.dp, end = posterWidth + 48.dp, top = 68.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.Bottom) {
                        DetailTitle(details, titleSize, showOriginal = false)
                    }
                }
            }
            // Only the top part covers the frame; the rest hangs into the section below.
            DetailPoster(details, Modifier.align(Alignment.BottomEnd).offset(y = posterOverlap)
                .padding(end = 16.dp).width(posterWidth))
        }
        Column(Modifier.fillMaxWidth().heightIn(min = posterOverlap)
            .padding(start = 16.dp, end = posterWidth + 48.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentMetadata(details)
        }
    }
}

@Composable private fun PosterDetails(details: CatalogDetails, posterWidth: Dp, titleSize: TextUnit,
    modifier: Modifier) {
    Row(Modifier.fillMaxWidth().testTag("catalog:detail:posterLayout").padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(posterWidth), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DetailPoster(details, Modifier.fillMaxWidth())
            if (details.item.date.isNotBlank()) DetailCredit(catalogText("Premiere", "Премьера"), detailDate(details.item.date))
            if (details.directors.isNotEmpty()) DetailCredit(catalogText("Director", "Режиссёр"), details.directors.joinToString(", "))
        }
        Box(Modifier.width(1.dp).height(posterWidth * 1.5f).background(Outline.copy(alpha = .5f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DetailRegion(modifier.fillMaxWidth()) { DetailHeading(details, titleSize) }
            DetailSynopsis(details, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun DetailPoster(details: CatalogDetails, modifier: Modifier) {
    CatalogImage(details.item.poster.replace("/t/p/w342/", "/t/p/w500/"), details.item.title,
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            .border(1.dp, Outline.copy(alpha = .7f), RoundedCornerShape(8.dp)).testTag("catalog:detail:poster"))
}

@Composable private fun DetailHeading(details: CatalogDetails, titleSize: TextUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailTitle(details, titleSize)
        DetailMetadata(details)
    }
}

@Composable private fun DetailTitle(details: CatalogDetails, titleSize: TextUnit, showOriginal: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvText(listOfNotNull(if (details.item.ref.type == CatalogType.Movie) catalogText("FILM", "ФИЛЬМ")
            else catalogText("SERIES", "СЕРИАЛ"), details.item.year?.toString()).joinToString("  ·  "),
            style = MaterialTheme.typography.labelLarge, color = Muted, translate = false)
        TvText(details.item.title, style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize,
            lineHeight = titleSize * 1.12f, fontWeight = FontWeight.SemiBold), translate = false,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("catalog:detail:title"))
        if (showOriginal && details.item.originalTitle.isNotBlank() && details.item.originalTitle != details.item.title)
            TvText(details.item.originalTitle, color = Muted, translate = false)
    }
}

@Composable private fun DetailMetadata(details: CatalogDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (details.item.votes > 0) Box(Modifier.background(Accent.copy(alpha = .18f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                TvText("★ %.1f".format(Locale.US, details.item.rating), color = AccentText,
                    style = MaterialTheme.typography.titleMedium, translate = false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details.runtime?.let { TvText("$it " + catalogText("min", "мин."), translate = false) }
                if (details.item.votes > 0) TvText(formatVotes(details.item.votes) + " " + catalogText("TMDB votes", "оценок TMDB"),
                    color = Muted, translate = false, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (details.genres.isNotEmpty()) TvText(details.genres.joinToString(" · "), translate = false)
    }
}

private fun formatVotes(votes: Int) = java.text.NumberFormat.getIntegerInstance(Locale.US).format(votes).replace(',', ' ')

private data class AccentStat(val key: String, val value: String, val label: String)

@Composable private fun AccentMetadata(details: CatalogDetails) {
    val item = details.item
    val rating = AccentStat("rating", if (item.votes > 0) "%.1f".format(Locale.US, item.rating) else "",
        formatVotes(item.votes) + " " + catalogText("TMDB votes", "оценок TMDB"))
    val director = AccentStat("director", details.directors.joinToString(", "), catalogText("Director", "Режиссёр"))
    val premiere = AccentStat("premiere", item.date.takeIf(String::isNotBlank)?.let { detailDate(it) }.orEmpty(), catalogText("Premiere", "Премьера"))
    val runtime = AccentStat("runtime", details.runtime?.let { "$it " + catalogText("min", "мин.") }.orEmpty(), catalogText("Runtime", "Длительность"))
    val genres = AccentStat("genres", details.genres.joinToString(" · "), catalogText("Genres", "Жанры"))
    val original = AccentStat("original", item.originalTitle, catalogText("Original title", "Оригинальное название"))
    Column(Modifier.fillMaxWidth().testTag("catalog:detail:stats"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (listOf(rating, director, premiere).any { it.value.isNotBlank() }) AccentStatRow(listOf(rating, director, premiere))
        if (listOf(runtime, genres, original).any { it.value.isNotBlank() }) AccentStatRow(listOf(runtime, genres, original))
    }
}

@Composable private fun AccentStatRow(fields: List<AccentStat>) {
    val weights = listOf(.22f, .43f, .35f)
    val captionStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Values share their last text baseline, including wrapped names and genres.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            fields.forEachIndexed { index, field ->
                val modifier = Modifier.weight(weights[index]).alignBy(LastBaseline)
                if (field.value.isBlank()) Spacer(modifier) else AccentStatValue(field, modifier)
            }
        }
        // One caption row gives every field the same baseline and value-to-label interval.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            fields.forEachIndexed { index, field ->
                val modifier = Modifier.weight(weights[index]).alignByBaseline()
                if (field.value.isBlank()) Spacer(modifier) else TvText(field.label, modifier = modifier.testTag("catalog:stat:${field.key}:label"),
                    color = Muted, style = captionStyle, translate = false)
            }
        }
    }
}

@Composable private fun AccentStatValue(field: AccentStat, modifier: Modifier) {
    val style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    if (field.key == "rating") Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText("★", modifier = Modifier.alignByBaseline(), style = style.copy(fontSize = 28.sp, lineHeight = 36.sp), color = AccentText, translate = false)
        TvText(field.value, modifier = Modifier.alignByBaseline().testTag("catalog:stat:rating:value"),
            style = style.copy(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold), translate = false)
    } else TvText(field.value, modifier = modifier.testTag("catalog:stat:${field.key}:value"), style = style, translate = false)
}

@Composable private fun synopsis(details: CatalogDetails) = details.overview.ifBlank {
    catalogText("No synopsis available.", "Описание пока отсутствует.")
}

@Composable private fun DetailSynopsis(details: CatalogDetails, modifier: Modifier) {
    DetailRegion(modifier.testTag("catalog:detail:overview")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvText(catalogText("Synopsis", "Описание"), style = MaterialTheme.typography.headlineSmall)
            TvText(synopsis(details), translate = false, modifier = Modifier.testTag("catalog:detail:fullOverview"))
            if (details.englishOverview) TvText(catalogText("Synopsis is available in English.", "Описание доступно на английском языке."), color = Muted)
        }
    }
}

@Composable private fun DetailCredits(details: CatalogDetails) {
    DetailRegion(Modifier.fillMaxWidth().testTag("catalog:detail:credits")) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Outline.copy(alpha = .5f)))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (details.cast.isNotEmpty()) Column(Modifier.weight(2f)) {
                    DetailCredit(catalogText("Cast", "В ролях"), details.cast.joinToString(" · "))
                }
            }
            if (details.item.ref.type == CatalogType.Tv) {
                TvText(listOfNotNull(details.seasons?.let { catalogText("Seasons: ", "Сезонов: ") + it },
                    details.episodes?.let { catalogText("Episodes: ", "Эпизодов: ") + it }).joinToString("  ·  "), translate = false)
                if (details.status.isNotBlank()) TvText(catalogText("Status: ", "Статус: ") + localizedStatus(details.status), translate = false)
                if (details.lastAirDate.isNotBlank()) TvText(catalogText("Last aired: ", "Последний выпуск: ") + detailDate(details.lastAirDate), color = Muted, translate = false)
            }
        }
    }
}

@Composable private fun DetailCredit(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText(label, color = Muted, style = MaterialTheme.typography.labelLarge)
        TvText(value, translate = false)
    }
}

@Composable private fun detailDate(value: String): String {
    val locale = if (LocalUiLanguage.current == "ru") Locale.forLanguageTag("ru-RU") else Locale.US
    return remember(value, locale) { runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
    }.getOrDefault(value) }
}

@Composable private fun localizedStatus(status: String): String = when (status) {
    "Returning Series" -> catalogText(status, "Продолжается")
    "Ended" -> catalogText(status, "Завершён")
    "Canceled" -> catalogText(status, "Отменён")
    "In Production" -> catalogText(status, "В производстве")
    "Planned" -> catalogText(status, "Запланирован")
    "Pilot" -> catalogText(status, "Пилот")
    else -> status
}

@Composable private fun DetailRegion(modifier: Modifier = Modifier, padding: Dp = 8.dp, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) FocusBorderColor else Color.Transparent,
        tween(if (LocalAnimationsEnabled.current) 160 else 0), label = "detailFocus")
    Box(modifier.onFocusChanged { focused = it.isFocused }.focusable()
        .border(2.dp, border, RoundedCornerShape(12.dp)).padding(padding)) { content() }
}
