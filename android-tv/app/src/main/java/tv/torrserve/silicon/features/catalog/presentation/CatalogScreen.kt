package tv.torrserve.silicon.features.catalog.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable fun CatalogScreen(state: CatalogUi, vm: CatalogViewModel, entryFocus: FocusRequester,
    torrentSearch: ((CatalogItem) -> Unit)? = null, restoreTorrentFocus: Boolean = false, refreshRevision:Int = 0,
    showFavorites: Boolean = true, showWatchLater: Boolean = true,
    initialUserList: CatalogUserList? = null, closeUserList: () -> Unit = {}) {
    val vertical = rememberLazyListState()
    val detailStates = rememberSaveableStateHolder()
    val savedDetailEntries = remember { mutableSetOf<Long>() }
    LaunchedEffect(state.selected, state.detailEntry, state.detailTrail) {
        val active = if (state.selected != null) state.detailTrail.toSet() + state.detailEntry else emptySet()
        (savedDetailEntries - active).forEach { detailStates.removeState(it) }
        savedDetailEntries.retainAll(active)
        savedDetailEntries.addAll(active)
    }
    val rows = CatalogSection.entries.associateWith { rememberLazyListState() }
    val personalRows = CatalogUserList.entries.associateWith { rememberLazyListState() }
    val homeLists = if (state.filter == CatalogFilter.All) CatalogUserList.entries.filter {
        if (it == CatalogUserList.Favorites) showFavorites else showWatchLater
    } else emptyList()
    val personalFeeds = CatalogUserList.entries.associateWith { list ->
        CatalogFeed(state.userEntries.filter { it.contains(list) }.map { it.item(state.language) },
            page = 1, totalPages = 1, loading = !state.listsReady && !state.listError, error = state.listError)
    }
    val genreRows = CatalogType.entries.associateWith { key(state.filter) { rememberLazyListState() } }
    val genreFocus = remember { FocusRequester() }
    var genrePicker by remember { mutableStateOf(false) }
    var restoreGenreFocus by remember { mutableStateOf(false) }
    val filterFocus = CatalogFilter.entries.associateWith { remember { FocusRequester() } }
    val listFocus = CatalogUserList.entries.associateWith { remember { FocusRequester() } }
    var menuItem by remember { mutableStateOf<CatalogItem?>(null) }
    var menuAnchor by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreMenuFocus by remember { mutableStateOf(false) }
    val held: (CatalogItem, FocusRequester) -> Unit = { item, anchor -> menuAnchor = anchor; menuItem = item }
    fun dismissMenu() { menuItem = null; restoreMenuFocus = true }
    var focusedFilterName by rememberSaveable { mutableStateOf(state.filter.name) }
    val rememberedFilter = CatalogFilter.entries.firstOrNull { it.name == focusedFilterName } ?: CatalogFilter.All
    val activeFilterFocus = if (focusedFilterName == "Genres" || rememberedFilter.isGenre) genreFocus else filterFocus.getValue(rememberedFilter)
    val grid = rememberLazyGridState()
    var expandedGroup by rememberSaveable(state.filter, state.query) { mutableStateOf(initialUserList?.let { "list:${it.name}" }) }
    var homeCollection by rememberSaveable { mutableStateOf(false) }
    val collectionGrid = key(expandedGroup) { rememberLazyGridState() }
    var restoreMore by remember { mutableStateOf(false) }
    val restore = remember { FocusRequester() }
    val firstPoster = remember { FocusRequester() }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var wasDetail by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var previousLanguage by remember { mutableStateOf(state.language) }
    val viewportWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } - LocalTvLayout.current.screenInset * 2
    val width = ((viewportWidth - 82.dp) / 6).coerceIn(112.dp, 198.dp)
    val laterWidth = width * 2.05f
    val firstGroup = homeLists.firstOrNull { personalFeeds.getValue(it).items.isNotEmpty() }?.let { "list:${it.name}" }
        ?: if (state.filter.isGenre) state.filter.genreTypes.firstOrNull {
        state.genreFeeds.getValue(it).items.isNotEmpty()
    }?.let { "genre:${it.path}" } else state.filter.sections.firstOrNull {
        state.sections.getValue(it).items.isNotEmpty()
    }?.name
    val hasPosters = if (state.query.isBlank()) firstGroup != null else state.results.items.isNotEmpty()
    LaunchedEffect(Unit) { vm.onVisible() }
    LaunchedEffect(refreshRevision,state.language) {
        if(refreshRevision>0 && state.language.isNotBlank())vm.refresh(refreshRevision)
    }
    LaunchedEffect(state.filter) { vertical.scrollToItem(0) }
    LaunchedEffect(state.filter, state.query, state.selected) {
        if (state.selected == null) {
            val filter = if (state.query.isBlank()) state.filter else CatalogFilter.All
            focusedFilterName = filter.name
        }
    }
    BackHandler(state.selected != null && !editing) { vm.close() }
    fun collapseCollection() {
        if (initialUserList != null) { closeUserList(); return }
        focusedSection = expandedGroup
        focusedKey = "more:$expandedGroup"
        expandedGroup = null
        restoreMore = true
    }
    BackHandler(state.selected == null && expandedGroup != null && !editing, onBack = ::collapseCollection)
    if (state.selected != null) {
        Box(Modifier.fillMaxSize().headerEdgeFade(enabled = state.details.data != null)) {
            detailStates.SaveableStateProvider(state.detailEntry) {
                CatalogDetailsScreen(state, vm::close, torrentSearch, restoreTorrentFocus,
                    toggleList = { item, list -> vm.toggleList(item, list) {} }, openRelated = vm::openRelated,
                    retryRelated = vm::retryRelated, moreRelated = { vm.loadRelated(more = true) }, relatedHeld = held) { vm.open(state.selected, true) }
            }
        }
    } else if (expandedGroup != null) {
        val group = expandedGroup!!
        val type = CatalogType.entries.firstOrNull { group == "genre:${it.path}" }
        val section = CatalogSection.entries.firstOrNull { it.name == group }
        val list = CatalogUserList.entries.firstOrNull { group == "list:${it.name}" }
        val feed = if (list != null) CatalogFeed(state.userEntries.filter { it.contains(list) }.map { it.item(state.language) },
            page = 1, totalPages = 1, loading = !state.listsReady, error = state.listError)
            else if (type != null) state.genreFeeds.getValue(type) else state.sections.getValue(section!!)
        val title = if (list != null) userListTitle(list) else if (type != null) genreTitle(type, state.filter) else sectionTitle(section!!)
        CatalogCollectionScreen(title, feed, collectionGrid, width, entryFocus,
            focusedKey.takeIf { focusedSection == group }, restore, !wasDetail, ::collapseCollection,
            retry = { if (list != null) vm.retryUserLists() else if (type != null) vm.retryGenre(type) else vm.retrySection(section!!) },
            more = { if (list == null) { if (type != null) vm.loadGenre(type, more = true) else vm.loadSection(section!!, more = true) } },
            open = vm::open, focused = { focusedKey = it.ref.key; focusedSection = group }, held = held,
            visible = { if (list != null) vm.localizeListItem(it.ref) }, locale = state.language)
    } else Column(Modifier.fillMaxSize().testTag("catalog:screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogSearchField(state.query, Modifier.weight(1f).focusRequester(entryFocus)
                .focusProperties { down = activeFilterFocus }.testTag("catalog:search")) { editing = true }
            if (state.query.isNotBlank()) TvQuietAction(catalogText("Clear", "Очистить"), modifier = Modifier.testTag("catalog:clear")) {
                vm.query(""); entryFocus.requestFocus()
            }
        }
        Row(Modifier.fillMaxWidth().testTag("catalog:filters").focusGroup(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(CatalogFilter.All, CatalogFilter.Movies, CatalogFilter.Series).forEachIndexed { index, filter ->
                val isSelected = state.query.isBlank() && state.filter == filter
                TvQuietAction(filterTitle(filter), selected = isSelected,
                    modifier = Modifier.focusRequester(filterFocus.getValue(filter))
                        .onFocusChanged { if (it.isFocused) focusedFilterName = filter.name }
                        .focusProperties { up = entryFocus; if (hasPosters) down = firstPoster; if (index == 0) left = FocusRequester.Cancel }
                        .testTag("catalog:filter:${filter.name}").semantics { selected = isSelected }) {
                    focusedKey = null; focusedSection = null; vm.filter(filter)
                }
            }
            TvQuietAction((if (state.filter.isGenre) filterTitle(state.filter) else catalogText("Genres", "Жанры")) + "  ⌄",
                selected = state.filter.isGenre, centered = true, modifier = Modifier.weight(1f).widthIn(max = 230.dp)
                    .focusRequester(genreFocus).onFocusChanged { if (it.isFocused) focusedFilterName = "Genres" }
                    .focusProperties { up = entryFocus; if (hasPosters) down = firstPoster }
                    .testTag("catalog:genres")) { genrePicker = true }
            CatalogUserList.entries.forEach { list ->
                val count = state.userEntries.count { it.contains(list) }
                TvQuietAction(userListTitle(list) + "  $count", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    modifier = Modifier.focusRequester(listFocus.getValue(list)).testTag("catalog:list:${list.name}")) {
                    homeCollection = false
                    expandedGroup = "list:${list.name}"
                }
            }
        }
        if (state.query.isBlank()) LazyColumn(state = vertical, modifier = Modifier.weight(1f).scrollEdgeFade(vertical, vertical = true).testTag("catalog:sections"),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(homeLists, key = { "list:${it.name}" }) { list ->
                val group = "list:${list.name}"
                CatalogFeedRow(group, userListTitle(list), personalFeeds.getValue(list),
                    if (list == CatalogUserList.WatchLater) laterWidth else width, personalRows.getValue(list),
                    if (group == firstGroup) firstPoster else null, if (group == firstGroup) activeFilterFocus else null,
                    focusedKey.takeIf { focusedSection == group }, restore, retry = vm::retryUserLists,
                    more = { homeCollection = true; expandedGroup = group }, open = vm::open,
                    focused = { focusedKey = it.ref.key; focusedSection = group }, held = held,
                    landscape = list == CatalogUserList.WatchLater)
            }
            if (state.filter.isGenre) items(state.filter.genreTypes, key = { "genre:${it.path}" }) { type ->
                val group = "genre:${type.path}"
                CatalogFeedRow(group, genreTitle(type, state.filter),
                    state.genreFeeds.getValue(type), width, genreRows.getValue(type),
                    if (group == firstGroup) firstPoster else null, if (group == firstGroup) activeFilterFocus else null,
                    focusedKey.takeIf { focusedSection == group }, restore, retry = { vm.retryGenre(type) },
                    more = { expandedGroup = group }, open = vm::open,
                    focused = { focusedKey = it.ref.key; focusedSection = group }, held = held)
            } else items(state.filter.sections, key = { it.name }) { section ->
                CatalogFeedRow(section.name, sectionTitle(section), state.sections.getValue(section), width, rows.getValue(section),
                    if (section.name == firstGroup) firstPoster else null, if (section.name == firstGroup) activeFilterFocus else null,
                    focusedKey.takeIf { focusedSection == section.name }, restore, retry = { vm.retrySection(section) },
                    more = { expandedGroup = section.name }, open = vm::open,
                    focused = { focusedKey = it.ref.key; focusedSection = section.name }, held = held)
            }
        } else {
            TvText(catalogText("Search results", "Результаты поиска"), style = MaterialTheme.typography.headlineSmall)
            val feed = state.results
            if (feed.loading && feed.items.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(5) { CatalogSkeleton(Modifier.width(width)) }
            }
            if (feed.error) CatalogError(vm::retryResults, cached = feed.items.isNotEmpty())
            if (!feed.loading && !feed.error && feed.items.isEmpty()) TvText(catalogText("Nothing found. Try another title.", "Ничего не найдено. Попробуйте другое название."), color = Muted, modifier = Modifier.testTag("catalog:empty"))
            if (!feed.loading && !feed.error && feed.items.isEmpty() && feed.page < feed.totalPages) TvAction(catalogText("More results", "Ещё результаты"), action = vm::moreSearch)
            LazyVerticalGrid(columns = GridCells.Adaptive(width), state = grid, modifier = Modifier.weight(1f).scrollEdgeFade(grid, vertical = true).testTag("catalog:results"),
                contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(feed.items, key = { _, item -> item.ref.key }) { index, item ->
                    val isEntry by remember(grid, index) { derivedStateOf { index == grid.firstVisibleItemIndex } }
                    CatalogPosterCard(item, Modifier
                        .then(if (isEntry) Modifier.focusRequester(firstPoster) else Modifier)
                        .then(if (item.ref.key == focusedKey && focusedSection == "results") Modifier.focusRequester(restore) else Modifier)
                        .onFocusChanged { if (it.isFocused) {
                            focusedKey = item.ref.key; focusedSection = "results"
                            if (index >= feed.items.size - 5 && !feed.error) vm.moreSearch()
                        } }, held) { vm.open(item.ref) }
                }
                if (feed.loadingMore) item("loading", span = { GridItemSpan(maxLineSpan) }) { TvText(catalogText("Loading…", "Загрузка…"), color = Muted) }
            }
            LaunchedEffect(grid, feed.page, feed.items.size, feed.error) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
                    if (last >= feed.items.size - 5 && feed.items.isNotEmpty() && !feed.error) vm.moreSearch()
                }
            }
        }
    }
    LaunchedEffect(restoreMore) {
        if (restoreMore) {
            val group = focusedSection
            val list = CatalogUserList.entries.firstOrNull { group == "list:${it.name}" }
            if (list != null && !homeCollection) {
                withFrameNanos { }; listFocus.getValue(list).requestFocus()
                restoreMore = false
                return@LaunchedEffect
            }
            val groups = homeLists.map { "list:${it.name}" } +
                if (state.filter.isGenre) state.filter.genreTypes.map { "genre:${it.path}" } else state.filter.sections.map { it.name }
            val index = groups.indexOf(group)
            if (index >= 0) vertical.scrollToItem(index)
            val type = CatalogType.entries.firstOrNull { group == "genre:${it.path}" }
            val section = CatalogSection.entries.firstOrNull { it.name == group }
            val feed = if (list != null) personalFeeds.getValue(list) else if (type != null) state.genreFeeds.getValue(type) else section?.let { state.sections.getValue(it) }
            val row = if (list != null) personalRows.getValue(list) else if (type != null) genreRows.getValue(type) else section?.let { rows.getValue(it) }
            if (feed != null && row != null && feed.items.isNotEmpty()) row.scrollToItem(feed.items.take(10).size)
            withFrameNanos { }; withFrameNanos { }
            runCatching { restore.requestFocus() }.onFailure { entryFocus.requestFocus() }
            restoreMore = false
        }
    }
    menuItem?.let { item -> CatalogListMenu(item, state, ::dismissMenu) { list -> vm.toggleList(item, list, ::dismissMenu) } }
    if (genrePicker) CatalogGenreDialog(state.filter, dismiss = { genrePicker = false; restoreGenreFocus = true }) { filter ->
        focusedKey = null; focusedSection = null; vm.filter(filter)
        genrePicker = false; restoreGenreFocus = true
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(restoreGenreFocus, windowFocused) {
        if (restoreGenreFocus && windowFocused) {
            withFrameNanos { }; withFrameNanos { }; genreFocus.requestFocus(); restoreGenreFocus = false
        }
    }
    LaunchedEffect(restoreMenuFocus, windowFocused) {
        if (restoreMenuFocus && windowFocused) {
            withFrameNanos { }; withFrameNanos { }
            runCatching { menuAnchor?.requestFocus() }.onFailure { entryFocus.requestFocus() }
            restoreMenuFocus = false
        }
    }
    if (editing) TextEditor(catalogText("Find a movie or series", "Найти фильм или сериал"), state.query,
        dismiss = { editing = false }, russian = LocalUiLanguage.current == "ru", onEdit = vm::query) { vm.query(it); editing = false }
    val restoreLoading = when {
        state.query.isNotBlank() -> state.results.loading
        state.filter.isGenre -> state.genreFeeds.entries.firstOrNull { "genre:${it.key.path}" == focusedSection }?.value?.loading == true
        else -> state.sections.entries.firstOrNull { it.key.name == focusedSection }?.value?.loading == true
    }
    LaunchedEffect(state.selected, state.language, restoreLoading) {
        if (previousLanguage != state.language) {
            if (state.selected == null && expandedGroup?.startsWith("list:") != true) { focusedKey = null; focusedSection = null }
            previousLanguage = state.language
        }
        if (state.selected != null) wasDetail = true
        else if (wasDetail && restoreLoading) { withFrameNanos { }; entryFocus.requestFocus() }
        else if (wasDetail) {
            wasDetail = false
            withFrameNanos { }; withFrameNanos { }
            runCatching { restore.requestFocus() }.onFailure { entryFocus.requestFocus() }
        }
    }
}

@Composable private fun genreTitle(type: CatalogType, filter: CatalogFilter) =
    (if (type == CatalogType.Movie) catalogText("Movies", "Фильмы") else catalogText("Series", "Сериалы")) + " · " + filterTitle(filter)

@Composable internal fun CatalogFeedRow(group: String, title: String, feed: CatalogFeed, width: Dp,
    row: LazyListState, entryFocus: FocusRequester?, up: FocusRequester?, restoreKey: String?, restore: FocusRequester,
    retry: () -> Unit, more: () -> Unit, open: (CatalogRef) -> Unit, focused: (CatalogItem) -> Unit,
    held: (CatalogItem, FocusRequester) -> Unit, landscape: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvText(title, style = MaterialTheme.typography.headlineSmall)
        if (feed.error) CatalogError(retry, cached = feed.items.isNotEmpty())
        if (feed.loading && feed.items.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(5) { CatalogSkeleton(Modifier.width(width)) }
        } else if (feed.items.isEmpty() && !feed.error) TvText(catalogText("No titles in this collection.", "В подборке пока нет фильмов."), color = Muted)
        LazyRow(state = row, modifier = Modifier.fillMaxWidth().scrollEdgeFade(row).focusGroup().testTag("catalog:row:$group"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
            itemsIndexed(feed.items.take(10), key = { _, item -> item.ref.key }) { index, item ->
                val isEntry by remember(row, index) { derivedStateOf { index == row.firstVisibleItemIndex } }
                CatalogPosterCard(item, Modifier.width(width)
                    .then(if (entryFocus != null && isEntry) Modifier.focusRequester(entryFocus) else Modifier)
                    .then(if (item.ref.key == restoreKey) Modifier.focusRequester(restore) else Modifier)
                    .focusProperties {
                        if (index == 0) left = FocusRequester.Cancel
                        if (up != null) this.up = up
                    }.onFocusChanged { if (it.isFocused) {
                        focused(item)
                    } }, held, landscape = landscape) { open(item.ref) }
            }
            if (feed.items.isNotEmpty()) item("more") {
                CatalogCollectionCard(feed.items.take(6), title, modifier = Modifier.width(width).aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                    .then(if (restoreKey == "more:$group") Modifier.focusRequester(restore) else Modifier)
                    .then(if (entryFocus != null && row.firstVisibleItemIndex == feed.items.take(10).size) Modifier.focusRequester(entryFocus) else Modifier)
                    .focusProperties { if (up != null) this.up = up; right = FocusRequester.Cancel }
                    .testTag("catalog:more:$group"), open = more)
            }
        }
    }
}
