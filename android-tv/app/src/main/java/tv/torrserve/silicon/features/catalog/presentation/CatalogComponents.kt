package tv.torrserve.silicon.features.catalog.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable internal fun catalogText(en: String, ru: String) = if (LocalUiLanguage.current == "ru") ru else en
@Composable internal fun filterTitle(filter: CatalogFilter): String = when (filter) {
    CatalogFilter.All -> catalogText("All", "Все")
    CatalogFilter.Movies -> catalogText("Movies", "Фильмы")
    CatalogFilter.Series -> catalogText("Series", "Сериалы")
    CatalogFilter.ActionAdventure -> catalogText("Action & adventure", "Боевики и приключения")
    CatalogFilter.Comedy -> catalogText("Comedy", "Комедия")
    CatalogFilter.Drama -> catalogText("Drama", "Драма")
    CatalogFilter.ScienceFictionFantasy -> catalogText("Sci-fi & fantasy", "Фантастика и фэнтези")
    CatalogFilter.Thriller -> catalogText("Thriller", "Триллер")
    CatalogFilter.Horror -> catalogText("Horror", "Ужасы")
    CatalogFilter.Animation -> catalogText("Animation", "Мультфильмы")
    CatalogFilter.Crime -> catalogText("Crime", "Криминал")
    CatalogFilter.Mystery -> catalogText("Mystery", "Детектив")
    CatalogFilter.Family -> catalogText("Family", "Семейные")
    CatalogFilter.Documentary -> catalogText("Documentary", "Документальные")
    CatalogFilter.Romance -> catalogText("Romance", "Мелодрама")
}
@Composable internal fun sectionTitle(section: CatalogSection): String = when (section) {
    CatalogSection.TrendingMovies -> catalogText("Trending this week", "В тренде")
    CatalogSection.NewMovies -> catalogText("New releases", "Новинки кино")
    CatalogSection.PopularMovies -> catalogText("Popular movies", "Популярные фильмы")
    CatalogSection.TopRated -> catalogText("Top rated", "Высокий рейтинг")
    CatalogSection.PopularTv -> catalogText("Popular series", "Популярные сериалы")
    CatalogSection.TrendingTv -> catalogText("Trending series", "Сериалы в тренде")
}
@Composable internal fun CatalogImage(url: String, title: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(SurfaceSecondary), contentAlignment = Alignment.Center) {
        if (url.isBlank() || failed) Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvText("◇", color = Muted, style = MaterialTheme.typography.headlineMedium, translate = false)
            TvText(title, maxLines = 3, overflow = TextOverflow.Ellipsis, translate = false, color = Muted)
        } else AsyncImage(url, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onError = { failed = true })
    }
}
@Composable internal fun CatalogPosterCard(item: CatalogItem, modifier: Modifier = Modifier,
    held: ((CatalogItem, FocusRequester) -> Unit)? = null, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val longClick: (() -> Unit)? = held?.let { { it(item, anchor) } }
    val shape = RoundedCornerShape(10.dp)
    val motion = rememberTvButtonMotion(shape = shape)
    val zoom by animateFloatAsState(if (focused && LocalAnimationsEnabled.current) 1.035f else 1f,
        tween(if (LocalAnimationsEnabled.current) 180 else 0), label = "catalogCardFocus")
    Button(onClick = { motion.activate(); open() }, onLongClick = longClick, modifier = modifier.focusRequester(anchor)
        .then(Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom })
        .then(motion.modifier).then(motion.pointerClick(onLongClick = longClick, action = open))
        .onFocusChanged { focused = it.isFocused }.testTag("catalog:card:${item.ref.key}"),
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(0.dp),
        colors = motion.colors, border = motion.border, interactionSource = motion.interactions,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Column {
            CatalogImage(item.poster, item.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TvText(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 40.dp), translate = false)
                TvText(listOfNotNull(if (item.votes > 0) "★ %.1f".format(java.util.Locale.US, item.rating) else null, item.year?.toString()).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, translate = false)
            }
        }
    }
}

@Composable internal fun CatalogCollectionCard(items: List<CatalogItem>, title: String, modifier: Modifier = Modifier, open: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val motion = rememberTvButtonMotion(shape = shape)
    val label = catalogText("Open collection: ", "Открыть подборку: ") + title
    Button(onClick = { motion.activate(); open() }, modifier = modifier
        .then(motion.modifier).then(motion.pointerClick(action = open)).semantics { contentDescription = label },
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(4.dp),
        colors = motion.colors, border = motion.border, interactionSource = motion.interactions,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Column(Modifier.fillMaxSize().then(motion.contentModifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { column ->
                        val index = row * 2 + column
                        val item = items.getOrNull(index)
                        CollectionMiniPoster(item?.poster.orEmpty(), Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).testTag("collection:mini:$index"))
                    }
                }
            }
        }
    }
}

@Composable private fun CollectionMiniPoster(url: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(SurfaceSecondary), contentAlignment = Alignment.Center) {
        if (url.isBlank() || failed) TvText("◇", color = Muted, translate = false)
        else AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onError = { failed = true })
    }
}
@Composable internal fun CatalogSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).background(SurfaceSecondary, RoundedCornerShape(10.dp)))
}
@Composable internal fun CatalogError(retry: () -> Unit, modifier: Modifier = Modifier, cached: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvText(if (cached) catalogText("Showing saved data. Could not refresh TMDB.", "Показаны сохранённые данные. Не удалось обновить TMDB.")
            else catalogText("Could not load the catalog. Check your internet connection.", "Не удалось загрузить каталог. Проверьте подключение к интернету."), color = Muted)
        TvAction(catalogText("Retry", "Повторить"), action = retry)
    }
}
