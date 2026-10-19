package tv.torrserve.silicon.features.catalog.presentation

import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import tv.torrserve.silicon.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil3.compose.AsyncImagePainter
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable internal fun CatalogDetailsScreen(state: CatalogUi, back: () -> Unit,
    torrentSearch: ((CatalogItem) -> Unit)? = null, restoreTorrentFocus: Boolean = false,
    toggleList: ((CatalogItem, CatalogUserList) -> Unit)? = null,
    openRelated: ((CatalogRef) -> Unit)? = null, retryRelated: () -> Unit = {}, moreRelated: () -> Unit = {},
    relatedHeld: ((CatalogItem, FocusRequester) -> Unit)? = null, jackettEnabled: Boolean = false, retry: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    val actionFocus = remember { listOf("torrent", "jackett", "favorite", "later").associateWith { FocusRequester() } }
    val torrentFocus = actionFocus.getValue(if(torrentSearch!=null) "torrent" else if(jackettEnabled) "jackett" else "favorite")
    var focusedAction by remember(state.selected) { mutableStateOf<String?>(null) }
    var actionsFocused by remember { mutableStateOf(false) }
    var restoreActionAfterFailure by remember { mutableStateOf<String?>(null) }
    val heroFocus = remember { FocusRequester() }
    val details = state.details.data
    val hasActions = torrentSearch != null || toggleList != null || jackettEnabled
    val actions: (@Composable () -> Unit)? = if (details != null && hasActions) {{
        DetailActions(details.item, state, actionFocus, backFocus, torrentSearch, toggleList, jackettEnabled,
            focused = { focusedAction=it }, groupFocused = { actionsFocused=it })
    }} else null
    val backdrop = details?.item?.backdrop.orEmpty()
    // An image failure changes only the presentation; metadata and its cache stay intact.
    var backdropFailed by remember(state.selected, backdrop) { mutableStateOf(false) }
    var restoreHeroAfterFailure by remember(state.selected, backdrop) { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    val backdropPainter = rememberCatalogImagePainter(backdrop.replace("/t/p/w780/", "/t/p/w1280/"))
    val backdropState by backdropPainter.state.collectAsStateWithLifecycle()
    LaunchedEffect(backdropState) {
        when (backdropState) {
            is AsyncImagePainter.State.Error -> if (!backdropFailed) {
                restoreHeroAfterFailure = heroFocused
                restoreActionAfterFailure = focusedAction.takeIf { actionsFocused }
                backdropFailed = true
            }
            is AsyncImagePainter.State.Success -> if (backdropFailed) {
                restoreHeroAfterFailure = heroFocused
                restoreActionAfterFailure = focusedAction.takeIf { actionsFocused }
                backdropFailed = false
            }
            else -> Unit
        }
    }
    val cinematic = backdrop.isNotBlank() && !backdropFailed
    val scroll = rememberLazyListState()
    val relatedRow = rememberLazyListState()
    val relatedGrid = rememberLazyGridState()
    val relatedRestore = remember { FocusRequester() }
    val collectionBack = remember { FocusRequester() }
    var expandedRelated by rememberSaveable { mutableStateOf(false) }
    var relatedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var relatedGridKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreRelatedRow by rememberSaveable { mutableStateOf(false) }
    var restoreRelatedGrid by rememberSaveable { mutableStateOf(false) }
    val relatedGroup = "related:${state.selected?.key}"
    val relatedTitle = relatedTitle(state.selected?.type ?: CatalogType.Movie)
    val posterWidth = if (LocalTvLayout.current.short) 104.dp else 116.dp
    fun relatedSelected(ref: CatalogRef) {
        if (expandedRelated) { relatedGridKey = ref.key; restoreRelatedGrid = true }
        else { relatedRowKey = ref.key; restoreRelatedRow = true }
        openRelated?.invoke(ref)
    }
    fun collapseRelated() {
        expandedRelated = false; relatedRowKey = "more:$relatedGroup"; restoreRelatedRow = true
    }
    fun retryRelatedList() {
        // Retry removes its button after success; hand focus to a poster instead
        // of leaving the remote on a control that no longer exists.
        if (expandedRelated) restoreRelatedGrid = true else restoreRelatedRow = true
        retryRelated()
    }
    if (expandedRelated && openRelated != null) {
        BackHandler(onBack = ::collapseRelated)
        CatalogCollectionScreen(relatedTitle, state.related, relatedGrid, posterWidth, collectionBack,
            relatedGridKey, relatedRestore, !restoreRelatedGrid, ::collapseRelated, ::retryRelatedList, moreRelated,
            open = ::relatedSelected, focused = { relatedGridKey = it.ref.key },
            held = relatedHeld ?: { _, _ -> }, locale = state.language)
        LaunchedEffect(restoreRelatedGrid, state.related.loading, state.related.items) {
            if (restoreRelatedGrid && !state.related.loading) {
                val index = if (relatedGridKey == null && state.related.items.isNotEmpty()) 0
                    else state.related.items.indexOfFirst { it.ref.key == relatedGridKey }
                if (index >= 0) relatedGridKey = state.related.items[index].ref.key
                withFrameNanos { }; withFrameNanos { }
                if (index >= 0 && relatedGrid.layoutInfo.visibleItemsInfo.none { it.key == relatedGridKey }) relatedGrid.scrollToItem(index)
                withFrameNanos { }; withFrameNanos { }
                if (index >= 0) relatedRestore.requestFocus() else collectionBack.requestFocus()
                restoreRelatedGrid = false
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().headerEdgeFade(enabled = scroll.canScrollBackward).testTag("catalog:details")) {
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
                    val focusModifier = Modifier.focusRequester(heroFocus).focusProperties { up = backFocus; if (hasActions) down = torrentFocus }
                        .onFocusChanged { heroFocused = it.isFocused }.testTag("catalog:detail:hero")
                    if (details != null && cinematic) CinematicDetails(details, heroHeight, cinematicPosterWidth, titleSize,
                        focusModifier, actions, backdropPainter)
                    else if (details != null) Box(Modifier.padding(top = 56.dp)) {
                        PosterDetails(details, largePosterWidth, titleSize, focusModifier, actions)
                    }
                    else if (state.details.loading) Row(Modifier.padding(top = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        CatalogSkeleton(Modifier.width(160.dp))
                        TvText(catalogText("Loading…", "Загрузка…"), color = Muted)
                    }
                    TvBackAction(modifier = Modifier.align(Alignment.TopStart)
                        .padding(if (cinematic) 16.dp else 0.dp).focusRequester(backFocus)
                        .focusProperties { if (details != null) { down = heroFocus; if(hasActions)right=torrentFocus } }
                        .testTag("catalog:back"), action = back)
                }
            }
            if (state.details.error) item("error") { CatalogError(retry, cached = details != null) }
            if (details != null) {
                item("overview") { DetailSynopsis(details, Modifier.fillMaxWidth()) }
                item("credits") { DetailCredits(details) }
                if (openRelated != null && (state.related.items.isNotEmpty() || state.related.loading || state.related.error)) item("related") {
                    CatalogFeedRow(relatedGroup, relatedTitle, state.related, posterWidth, relatedRow, null, null,
                        relatedRowKey, relatedRestore, retry = ::retryRelatedList,
                        more = { relatedRowKey = "more:$relatedGroup"; expandedRelated = true },
                        open = ::relatedSelected, focused = { relatedRowKey = it.ref.key }, held = relatedHeld ?: { _, _ -> })
                }
                item("source") {
                    TvText(catalogText("Movie information and images from TMDB", "Информация и изображения предоставлены TMDB"),
                        color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
    LaunchedEffect(state.selected) {
        withFrameNanos { }
        if (restoreTorrentFocus && torrentSearch!=null && details!=null) { restoreRelatedRow = false; torrentFocus.requestFocus() }
        else if (!restoreRelatedRow) backFocus.requestFocus()
    }
    LaunchedEffect(restoreRelatedRow, state.related.loading, state.related.items, details != null) {
        if (restoreRelatedRow && details != null && !state.related.loading) {
            val index = if (relatedRowKey == null && state.related.items.isNotEmpty()) 0
                else if (relatedRowKey == "more:$relatedGroup") state.related.items.take(10).size
                else state.related.items.take(10).indexOfFirst { it.ref.key == relatedRowKey }
            if (index >= 0 && state.related.items.isNotEmpty()) {
                if (relatedRowKey == null) relatedRowKey = state.related.items[index].ref.key
                withFrameNanos { }; withFrameNanos { }
                if (scroll.layoutInfo.visibleItemsInfo.none { it.key == "related" }) scroll.scrollToItem(if (state.details.error) 4 else 3)
                val key = if (relatedRowKey == "more:$relatedGroup") "more" else relatedRowKey
                if (relatedRow.layoutInfo.visibleItemsInfo.none { it.key == key }) relatedRow.scrollToItem(index)
                withFrameNanos { }; withFrameNanos { }
                relatedRestore.requestFocus()
            } else backFocus.requestFocus()
            restoreRelatedRow = false
        }
    }
    // Keep the same remote target when the backdrop fails or recovers while reading the hero.
    LaunchedEffect(backdropFailed) {
        if (restoreActionAfterFailure!=null) {
            withFrameNanos { };actionFocus.getValue(if(restoreActionAfterFailure == "jackett" && !jackettEnabled) { if(torrentSearch != null) "torrent" else "favorite" } else restoreActionAfterFailure!!).requestFocus();restoreActionAfterFailure=null
        } else if (restoreHeroAfterFailure) {
            withFrameNanos { }; heroFocus.requestFocus(); restoreHeroAfterFailure = false
        }
    }
}

@Composable private fun CinematicDetails(details: CatalogDetails, height: Dp, posterWidth: Dp,
    titleSize: TextUnit, modifier: Modifier, actions: (@Composable () -> Unit)?, backdropPainter: AsyncImagePainter) {
    val background = Background
    val posterOverlap = posterWidth * 1.5f * .42f
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            DetailRegion(modifier.fillMaxWidth(), padding = 0.dp) {
                Box(Modifier.fillMaxWidth().testTag("catalog:detail:cinematic")) {
                    Image(backdropPainter, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)))
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                        0f to background.copy(alpha = .04f), .6f to background.copy(alpha = .18f),
                        1f to background.copy(alpha = .45f))))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to background.copy(alpha = .2f), .35f to background.copy(alpha = .08f),
                        .7f to background.copy(alpha = .6f), 1f to background)))
                    Column(Modifier.fillMaxWidth().heightIn(min = height)
                        .padding(start = posterWidth + 48.dp, end = 16.dp, top = 68.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.Bottom) {
                        DetailTitle(details, titleSize, showOriginal = false)
                    }
                }
            }
            // Only the top part covers the frame; the rest hangs into the section below.
            DetailPoster(details, Modifier.align(Alignment.BottomStart).offset(y = posterOverlap)
                .padding(start = 16.dp).width(posterWidth))
        }
        Column(Modifier.fillMaxWidth().heightIn(min = posterOverlap)
            .padding(start = posterWidth + 48.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            actions?.invoke()
            AccentMetadata(details)
        }
    }
}

@Composable private fun PosterDetails(details: CatalogDetails, posterWidth: Dp, titleSize: TextUnit,
    modifier: Modifier, actions: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().testTag("catalog:detail:posterLayout").padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        DetailPoster(details, Modifier.width(posterWidth))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DetailRegion(modifier.fillMaxWidth()) { DetailTitle(details, titleSize, showOriginal = false) }
            actions?.invoke()
            AccentMetadata(details)
        }
    }
}

@Composable private fun DetailActions(item: CatalogItem, state: CatalogUi, focus: Map<String,FocusRequester>,
    back: FocusRequester, torrentSearch: ((CatalogItem) -> Unit)?, toggleList: ((CatalogItem, CatalogUserList) -> Unit)?,
    jackettEnabled: Boolean, focused: (String) -> Unit, groupFocused: (Boolean) -> Unit) {
    val entry = state.userEntries.firstOrNull { it.ref == item.ref }
    val favorite = entry?.favorite == true
    val later = entry?.watchLater == true
    val favoriteFocus = focus.getValue("favorite")
    val laterFocus = focus.getValue("later")
    var restoreList by remember { mutableStateOf<CatalogUserList?>(null) }
    var jackett by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { groupFocused(it.hasFocus) }.focusGroup().testTag("catalog:detail:actions"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        DetailAction("TorrentSearch", Modifier.weight(1f).focusRequester(focus.getValue("torrent")).onFocusChanged { if(it.isFocused)focused("torrent") }.focusProperties { up=back }
            .testTag("catalog:torrentsearch"), enabled=torrentSearch!=null, icon=R.drawable.ic_search) { torrentSearch?.invoke(item) }
        if (jackettEnabled) DetailAction("Jackett", Modifier.weight(1f).focusRequester(focus.getValue("jackett")).onFocusChanged { if(it.isFocused)focused("jackett") }.focusProperties { up=back }.testTag("catalog:jackett"), icon=R.drawable.ic_search) { jackett=true }
        Spacer(Modifier.width(8.dp))
        DetailAction(if(favorite)catalogText("Remove favorite", "Удалить из избранного") else catalogText("Add to favorites", "Добавить в избранное"),
            Modifier.size(40.dp).focusRequester(favoriteFocus).onFocusChanged { if(it.isFocused)focused("favorite") }.focusProperties { up=back }.testTag("catalog:favorite"),
            selected=favorite, icon=if(favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline, iconOnly=true, enabled=state.listsReady && !state.listBusy && toggleList!=null) {
            restoreList=CatalogUserList.Favorites;toggleList?.invoke(item,CatalogUserList.Favorites)
        }
        DetailAction(if(later)catalogText("Remove from watch later", "Удалить из «Смотреть позже»") else catalogText("Watch later", "Смотреть позже"),
            Modifier.size(40.dp).focusRequester(laterFocus).onFocusChanged { if(it.isFocused)focused("later") }.focusProperties { up=back }.testTag("catalog:watch-later"),
            selected=later, icon=R.drawable.ic_watch_later, iconOnly=true, enabled=state.listsReady && !state.listBusy && toggleList!=null) {
            restoreList=CatalogUserList.WatchLater;toggleList?.invoke(item,CatalogUserList.WatchLater)
        }
    }
    if(state.listError)TvText(catalogText("Could not save the list. Try again.", "Не удалось сохранить список. Попробуйте ещё раз."),color=Muted)
    LaunchedEffect(state.listBusy,restoreList) {
        if(!state.listBusy && restoreList!=null) {
            withFrameNanos { }
            if(restoreList==CatalogUserList.Favorites)favoriteFocus.requestFocus()else laterFocus.requestFocus()
            restoreList=null
        }
    }
    if(jackett && jackettEnabled) {
        val close=remember { FocusRequester() }
        Dialog(onDismissRequest={jackett=false}) {
            Column(Modifier.width(360.dp).background(Surface,RoundedCornerShape(20.dp)).padding(20.dp)
                .testTag("catalog:jackett:placeholder"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TvText("Jackett",style=MaterialTheme.typography.titleLarge,translate=false)
                TvText(catalogText("Jackett search will be available here later.", "Поиск через Jackett появится здесь позже."))
                TvBackAction(Modifier.focusRequester(close)) { jackett=false }
            }
            LaunchedEffect(Unit) { withFrameNanos { };close.requestFocus() }
        }
    }
}

@Composable private fun DetailAction(label: String, modifier: Modifier, enabled: Boolean = true,
    selected: Boolean = false, icon: Int? = null, iconOnly: Boolean = false, action: () -> Unit) {
    val shape=CircleShape
    val motion=rememberTvButtonMotion(enabled,container=if(selected)SurfaceSecondary else Surface,
        content=if(selected)AccentText else TextPrimary, outline=if(selected)Accent else Outline,shape=shape)
    Button(onClick={motion.activate();action()},enabled=enabled,
        modifier=modifier.height(40.dp).then(motion.modifier).then(motion.pointerClick(enabled,action=action))
            .semantics { this.selected=selected },
        interactionSource=motion.interactions,colors=motion.colors,border=motion.border,
        shape=ButtonDefaults.shape(shape),contentPadding=if(iconOnly) PaddingValues(0.dp) else PaddingValues(horizontal=16.dp,vertical=4.dp),
        scale=ButtonDefaults.scale(focusedScale=1f,pressedScale=1f)) {
        Row(Modifier.then(motion.contentModifier), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
            icon?.let { Image(painterResource(it), contentDescription=if(iconOnly) label else null,
                colorFilter=ColorFilter.tint(androidx.tv.material3.LocalContentColor.current), modifier=Modifier.size(20.dp)) }
            if(!iconOnly) TvText(label,style=MaterialTheme.typography.labelLarge.copy(textAlign=TextAlign.Center),
                maxLines=1,overflow=TextOverflow.Ellipsis,translate=false)
        }
    }
}

@Composable private fun DetailPoster(details: CatalogDetails, modifier: Modifier) {
    CatalogImage(details.item.poster.replace("/t/p/w342/", "/t/p/w500/"), details.item.title,
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            .border(1.dp, Outline.copy(alpha = .7f), RoundedCornerShape(8.dp)).testTag("catalog:detail:poster"))
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
