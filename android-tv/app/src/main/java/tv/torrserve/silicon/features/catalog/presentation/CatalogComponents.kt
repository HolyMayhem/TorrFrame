package tv.torrserve.silicon.features.catalog.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImagePainter
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*
import tv.torrserve.silicon.core.network.CatalogNetworkSettings

val LocalCatalogImageSettings = staticCompositionLocalOf { CatalogNetworkSettings() }

@Composable internal fun catalogText(en: String, ru: String) = if (LocalUiLanguage.current == "ru") ru else en
@Composable internal fun relatedTitle(type: CatalogType) = if (type == CatalogType.Movie)
    catalogText("Similar movies", "Похожие фильмы") else catalogText("Similar series", "Похожие сериалы")
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
@Composable internal fun CatalogImage(url: String, title: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val painter = rememberCatalogImagePainter(url)
    val state by painter.state.collectAsStateWithLifecycle()
    Box(modifier.background(SurfaceSecondary), contentAlignment = Alignment.Center) {
        if (url.isBlank() || state is AsyncImagePainter.State.Error) Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvText("◇", color = Muted, style = MaterialTheme.typography.headlineMedium, translate = false)
            TvText(title, maxLines = 3, overflow = TextOverflow.Ellipsis, translate = false, color = Muted)
        } else Image(painter, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = scale)
    }
}
@Composable internal fun CatalogPosterCard(item: CatalogItem, modifier: Modifier = Modifier,
    held: ((CatalogItem, FocusRequester) -> Unit)? = null, landscape: Boolean = false, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val longClick: (() -> Unit)? = held?.let { { it(item, anchor) } }
    val shape = RoundedCornerShape(6.dp)
    val motion = rememberTvButtonMotion(container = Color.Transparent, content = TextPrimary,
        outline = Color.Transparent, shape = shape, focusedContainer = Color.Transparent,
        focusedContent = TextPrimary, focusedOutline = Color.Transparent)
    val zoom by animateFloatAsState(if (focused && LocalAnimationsEnabled.current) 1.018f else 1f,
        tween(if (LocalAnimationsEnabled.current) 150 else 0), label = "catalogCardFocus")
    Button(onClick = { motion.activate(); open() }, onLongClick = longClick, modifier = modifier.focusRequester(anchor)
        .graphicsLayer { scaleX = zoom; scaleY = zoom }
        .then(motion.modifier).then(motion.pointerClick(onLongClick = longClick, action = open))
        .onFocusChanged { focused = it.isFocused }.testTag("catalog:card:${item.ref.key}"),
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(0.dp),
        colors = motion.colors, border = motion.border, interactionSource = motion.interactions,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 3f / 4f)
                .border(2.dp, if (focused) TextPrimary else Color.Transparent, shape).padding(2.dp)) {
                CatalogImage(if (landscape) item.backdrop.ifBlank { item.poster } else item.poster, item.title,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                    scale = if (landscape && item.backdrop.isBlank()) ContentScale.Fit else ContentScale.Crop)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TvText(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 18.dp), translate = false)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.votes > 0) {
                        TvText("★", color = Positive, style = MaterialTheme.typography.labelMedium, translate = false)
                        TvText("%.1f".format(java.util.Locale.US, item.rating), color = Muted,
                            style = MaterialTheme.typography.labelMedium, translate = false)
                    }
                    item.year?.let { year -> TvText((if (item.votes > 0) "·  " else "") + year, color = Muted,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, translate = false) }
                }
            }
        }
    }
}

@Composable internal fun CatalogCollectionCard(items: List<CatalogItem>, title: String, modifier: Modifier = Modifier, open: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val motion = rememberTvButtonMotion(shape = shape, container = Surface,
        outline = Color.Transparent, focusedContainer = SurfaceSecondary,
        focusedContent = TextPrimary, focusedOutline = TextPrimary)
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
    val painter = rememberCatalogImagePainter(url)
    val state by painter.state.collectAsStateWithLifecycle()
    Box(modifier.background(SurfaceSecondary), contentAlignment = Alignment.Center) {
        if (url.isBlank() || state is AsyncImagePainter.State.Error) TvText("◇", color = Muted, translate = false)
        else Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
@Composable internal fun CatalogSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).background(SurfaceSecondary, RoundedCornerShape(10.dp)))
}
@Composable internal fun CatalogError(retry: () -> Unit, modifier: Modifier = Modifier, cached: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvText(if (cached) catalogText("Showing saved data. Could not refresh the catalog.", "Показаны сохранённые данные. Не удалось обновить каталог.")
            else catalogText("Could not load the catalog. Check your internet connection.", "Не удалось загрузить каталог. Проверьте подключение к интернету."), color = Muted)
        TvAction(catalogText("Retry", "Повторить"), action = retry)
    }
}
