package tv.torrserve.silicon.features.catalog.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.torrserve.silicon.features.catalog.domain.*

data class CatalogFeed(val items: List<CatalogItem> = emptyList(), val page: Int = 0, val totalPages: Int = 1,
    val loading: Boolean = true, val loadingMore: Boolean = false, val stale: Boolean = false, val error: Boolean = false, val failedPage: Int? = null)
data class CatalogUi(val query: String = "", val language: String = "", val sections: Map<CatalogSection, CatalogFeed> = CatalogSection.entries.associateWith { CatalogFeed() },
    val results: CatalogFeed = CatalogFeed(loading = false), val selected: CatalogRef? = null,
    val details: CatalogLoad<CatalogDetails> = CatalogLoad(), val filter: CatalogFilter = CatalogFilter.All,
    val genreFeeds: Map<CatalogType, CatalogFeed> = CatalogType.entries.associateWith { CatalogFeed() },
    val userEntries: List<CatalogListEntry> = emptyList(), val listsReady: Boolean = false,
    val listBusy: Boolean = false, val listError: Boolean = false,
    val related: CatalogFeed = CatalogFeed(loading = false), val relatedSource: CatalogRelatedSource = CatalogRelatedSource.Similar,
    val detailEntry: Long = 0, val detailTrail: List<Long> = emptyList())

private data class CatalogDetailVisit(val ref: CatalogRef, val entry: Long, val language: String = "",
    val details: CatalogLoad<CatalogDetails> = CatalogLoad(), val related: CatalogFeed = CatalogFeed(loading = false),
    val source: CatalogRelatedSource = CatalogRelatedSource.Similar)

private fun catalogRef(key: String): CatalogRef? {
    val parts = key.split(':'); val id = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val type = CatalogType.entries.firstOrNull { it.path == parts.first() } ?: return null
    return CatalogRef(id, type)
}

/** Catalog-only state. No Jackett, library, TorrServer or playback dependency. */
class CatalogViewModel(private val repository: CatalogRepository, languages: Flow<String>, private val saved: SavedStateHandle,
    private val now: () -> Long = System::currentTimeMillis,
    private val userLists: CatalogUserLists = MemoryCatalogUserLists(),
    configurationChanges: Flow<*> = emptyFlow<Unit>()) : ViewModel() {
    private val visits = saved.get<ArrayList<String>>("catalogDetailTrail").orEmpty().mapNotNull { value ->
        val entry = value.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        catalogRef(value.substringAfter('|'))?.let { CatalogDetailVisit(it, entry) }
    }.takeLast(24).toMutableList()
    private var detailSequence = maxOf(saved.get<Long>("catalogDetailSequence") ?: 0, visits.maxOfOrNull { it.entry } ?: 0)
    private val mutable = MutableStateFlow(CatalogUi(query = saved["catalogQuery"] ?: "",
        selected = saved.get<String>("catalogSelected")?.let(::catalogRef),
        detailEntry = saved["catalogDetailEntry"] ?: 0, detailTrail = visits.map { it.entry },
        filter = CatalogFilter.entries.firstOrNull { it.name == saved.get<String>("catalogFilter") } ?: CatalogFilter.All))
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var relatedJob: Job? = null
    private var detailRevision = 0
    private val sectionJobs = mutableMapOf<CatalogSection, Job>()
    private val loadedAt = mutableMapOf<CatalogSection, Long>()
    private val genreJobs = mutableMapOf<CatalogType, Job>()
    private val genreLoadedAt = mutableMapOf<CatalogType, Long>()
    private var filterRevision = 0
    private var searchLoadedAt: Long? = null
    private var revision = 0
    private var localeRevision = 0
    private var developerRefreshRevision = 0
    private val listRequests = mutableSetOf<String>()
    private val listSlots = Semaphore(3)
    init { viewModelScope.launch {
        try {
            userLists.load()
            userLists.entries.collect { entries -> mutable.update { it.copy(userEntries = entries, listsReady = true) } }
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(listError = true) } }
    } }
    fun toggleList(item: CatalogItem, list: CatalogUserList, done: () -> Unit) {
        if (!state.value.listsReady || state.value.listBusy) return
        val language = state.value.language
        mutable.update { it.copy(listBusy = true, listError = false) }
        // Complete the disk write even if the activity recreates or this model is cleared.
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { userLists.toggle(item, language, list) }
                mutable.update { it.copy(userEntries = userLists.entries.value) }; done()
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(listError = true) } }
            finally { mutable.update { it.copy(listBusy = false) } }
        }
    }
    fun localizeListItem(ref: CatalogRef) {
        val language = state.value.language
        if (state.value.userEntries.firstOrNull { it.ref == ref }?.localized?.containsKey(language) != false) return
        if (!listRequests.add("${ref.key}:$language")) return
        viewModelScope.launch {
            var resolved = false
            try {
                listSlots.withPermit {
                    repository.details(ref, language).collect { load ->
                        load.data?.let {
                            userLists.localize(it.item, language); resolved = true
                            mutable.update { current -> current.copy(userEntries = userLists.entries.value) }
                        }
                    }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
            finally { if (!resolved) listRequests.remove("${ref.key}:$language") }
        }
    }
    fun retryUserLists() { viewModelScope.launch {
        try {
            userLists.load(); mutable.update { it.copy(userEntries = userLists.entries.value, listsReady = true, listError = false) }
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(listError = true) } }
    } }
    init { viewModelScope.launch {
        languages.distinctUntilChanged().collect { language ->
            revision++; localeRevision++; searchJob?.cancel(); detailsJob?.cancel(); relatedJob?.cancel(); sectionJobs.values.forEach(Job::cancel); sectionJobs.clear(); loadedAt.clear()
            genreJobs.values.forEach(Job::cancel); genreJobs.clear(); genreLoadedAt.clear()
            mutable.update { it.copy(language = language, sections = CatalogSection.entries.associateWith { CatalogFeed() },
                genreFeeds = CatalogType.entries.associateWith { CatalogFeed() }, results = CatalogFeed(loading = it.query.isNotBlank()),
                details = CatalogLoad(loading = it.selected != null), related = CatalogFeed(loading = false), relatedSource = CatalogRelatedSource.Similar) }
            loadBrowse()
            if (state.value.query.isNotBlank()) scheduleSearch(false)
            state.value.selected?.let { open(it) }
        }
    } }
    fun filter(value: CatalogFilter) {
        if (state.value.filter == value && state.value.query.isBlank()) return
        query("")
        if (state.value.filter != value) {
            filterRevision++; genreJobs.values.forEach(Job::cancel); genreJobs.clear(); genreLoadedAt.clear()
            saved["catalogFilter"] = value.name
            mutable.update { it.copy(filter = value, genreFeeds = CatalogType.entries.associateWith { CatalogFeed() }) }
        }
        loadBrowse()
    }
    private fun loadBrowse() {
        val filter = state.value.filter
        if (filter.isGenre) filter.genreTypes.filter { it !in genreLoadedAt }.forEach { loadGenre(it) }
        else filter.sections.filter { it !in loadedAt }.forEach { loadSection(it) }
    }
    fun query(value: String) {
        val text = value.take(200)
        if (text == state.value.query) return
        revision++; searchJob?.cancel(); saved["catalogQuery"] = text
        mutable.update { it.copy(query = text, results = CatalogFeed(loading = text.isNotBlank())) }
        if (text.isNotBlank() && state.value.language.isNotBlank()) scheduleSearch(false)
    }
    private fun scheduleSearch(force: Boolean) {
        searchLoadedAt = now()
        searchJob?.cancel(); val version = revision; val query = state.value.query.trim(); val language = state.value.language
        searchJob = viewModelScope.launch {
            if (!force) delay(450)
            repository.search(query, language, force = force).collect { load ->
                if (version == revision) mutable.update { it.copy(results = merge(it.results, load, false)) }
            }
        }
    }
    /** Explicit refresh for developer scenarios; existing repository caches and navigation stay intact. */
    fun refresh(requestRevision:Int? = null) {
        if(state.value.language.isBlank() || (requestRevision != null && requestRevision <= developerRefreshRevision))return
        if(requestRevision != null)developerRefreshRevision=requestRevision
        revision++;localeRevision++;filterRevision++
        searchJob?.cancel();detailsJob?.cancel();relatedJob?.cancel()
        sectionJobs.values.forEach(Job::cancel);sectionJobs.clear()
        genreJobs.values.forEach(Job::cancel);genreJobs.clear()
        val filter=state.value.filter
        if(state.value.query.isNotBlank())scheduleSearch(true)
        else if(filter.isGenre)filter.genreTypes.forEach{loadGenre(it,force=true)}
        else filter.sections.forEach{loadSection(it,force=true)}
        state.value.selected?.let{open(it,true)}
    }
    init { viewModelScope.launch { configurationChanges.distinctUntilChanged().drop(1).collect { refresh() } } }
    fun retrySearch() { if (state.value.query.isNotBlank()) scheduleSearch(true) }
    fun retryResults() { if ((state.value.results.failedPage ?: 1) > 1) moreSearch() else retrySearch() }
    fun retrySection(section: CatalogSection) { loadSection(section, more = (state.value.sections.getValue(section).failedPage ?: 1) > 1, force = true) }
    fun onVisible() {
        val time = now()
        val filter = state.value.filter
        if (filter.isGenre) filter.genreTypes.filter { type -> genreLoadedAt[type]?.let { time - it >= 12 * 3_600_000L } == true }.forEach { loadGenre(it) }
        else filter.sections.filter { section -> loadedAt[section]?.let { time - it >= section.ttlHours * 3_600_000L } == true }.forEach { loadSection(it) }
        if (state.value.query.isNotBlank() && searchLoadedAt?.let { time - it >= 3_600_000L } == true) scheduleSearch(false)
    }
    fun moreSearch() {
        val current = state.value.results
        if (searchJob?.isActive == true || current.loading || current.loadingMore || current.page >= current.totalPages) return
        val version = revision; val query = state.value.query.trim(); val language = state.value.language
        mutable.update { it.copy(results = it.results.copy(loadingMore = true, error = false)) }
        searchJob = viewModelScope.launch {
            repository.search(query, language, current.page + 1).collect { load ->
                if (version == revision) mutable.update { it.copy(results = merge(current, load, true).copy(failedPage = if (load.error) current.page + 1 else null)) }
            }
        }
    }
    fun loadSection(section: CatalogSection, more: Boolean = false, force: Boolean = false) {
        if (sectionJobs[section]?.isActive == true || state.value.language.isBlank()) return
        val current = state.value.sections.getValue(section)
        if (more && (current.loading || current.loadingMore || current.page >= current.totalPages)) return
        val page = if (more) current.page + 1 else 1
        if (!more) loadedAt[section] = now()
        val language = state.value.language
        val locale = localeRevision
        mutable.update { it.copy(sections = it.sections + (section to current.copy(loading = !more, loadingMore = more, error = false))) }
        sectionJobs[section] = viewModelScope.launch {
            repository.section(section, language, page, force).collect { load ->
                if (localeRevision == locale && state.value.language == language) mutable.update { it.copy(sections = it.sections + (section to merge(current, load, more).copy(failedPage = if (load.error) page else null))) }
            }
        }
    }
    fun retryGenre(type: CatalogType) { loadGenre(type, more = (state.value.genreFeeds.getValue(type).failedPage ?: 1) > 1, force = true) }
    fun loadGenre(type: CatalogType, more: Boolean = false, force: Boolean = false) {
        val filter = state.value.filter
        if (!filter.isGenre || filter.genres(type) == null || state.value.language.isBlank() || genreJobs[type]?.isActive == true) return
        val current = state.value.genreFeeds.getValue(type)
        if (more && (current.loading || current.loadingMore || current.page >= current.totalPages)) return
        val page = if (more) current.page + 1 else 1
        if (!more) genreLoadedAt[type] = now()
        val language = state.value.language; val locale = localeRevision; val version = filterRevision
        mutable.update { it.copy(genreFeeds = it.genreFeeds + (type to current.copy(loading = !more, loadingMore = more, error = false))) }
        genreJobs[type] = viewModelScope.launch {
            repository.discover(filter, type, language, page, force).collect { load ->
                if (version == filterRevision && locale == localeRevision && state.value.filter == filter && state.value.language == language)
                    mutable.update { it.copy(genreFeeds = it.genreFeeds + (type to merge(current, load, more).copy(failedPage = if (load.error) page else null))) }
            }
        }
    }
    private fun merge(before: CatalogFeed, load: CatalogLoad<CatalogPage>, more: Boolean): CatalogFeed {
        val page = load.data
        return CatalogFeed(
            items = if (page == null) before.items else if (more) (before.items + page.items).distinctBy { it.ref.key } else page.items,
            page = page?.page ?: before.page, totalPages = page?.totalPages ?: before.totalPages,
            loading = load.loading && !more, loadingMore = load.loading && more, stale = load.stale, error = load.error)
    }
    fun open(ref: CatalogRef, force: Boolean = false) {
        if (state.value.selected != ref) {
            visits.clear()
            select(ref)
        }
        detailRevision++; detailsJob?.cancel(); relatedJob?.cancel()
        saved["catalogSelected"] = ref.key
        loadDetails(ref, force)
        loadRelated(force = force)
    }
    private fun select(ref: CatalogRef) {
        val entry = ++detailSequence
        saved["catalogDetailSequence"] = detailSequence
        saved["catalogDetailEntry"] = entry
        saved["catalogSelected"] = ref.key
        saveTrail()
        mutable.update { it.copy(selected = ref, detailEntry = entry, detailTrail = visits.map { visit -> visit.entry },
            details = CatalogLoad(loading = true), related = CatalogFeed(loading = false), relatedSource = CatalogRelatedSource.Similar) }
    }
    /** A detail visit keeps its own scroll/focus identity, including A -> B -> A chains. */
    fun openRelated(ref: CatalogRef) {
        val current = state.value
        val parent = current.selected ?: return
        if (ref == parent) return
        visits += CatalogDetailVisit(parent, current.detailEntry, current.language, current.details.copy(loading = false),
            current.related.copy(loading = false, loadingMore = false), current.relatedSource)
        if (visits.size > 24) visits.removeAt(0)
        detailRevision++; detailsJob?.cancel(); relatedJob?.cancel()
        select(ref)
        loadDetails(ref, false)
        loadRelated()
    }
    private fun loadDetails(ref: CatalogRef, force: Boolean) {
        mutable.update { it.copy(details = it.details.copy(loading = true)) }
        val language = state.value.language
        val locale = localeRevision; val version = detailRevision
        detailsJob = viewModelScope.launch {
            repository.details(ref, language, force).collect { load ->
                if (version == detailRevision && localeRevision == locale && state.value.selected == ref && state.value.language == language) mutable.update { it.copy(details = load) }
            }
        }
    }
    fun retryRelated() = loadRelated(more = (state.value.related.failedPage ?: 1) > 1, force = true)
    fun loadRelated(more: Boolean = false, force: Boolean = false) {
        val ref = state.value.selected ?: return
        if (state.value.language.isBlank() || relatedJob?.isActive == true) return
        val before = state.value.related
        if (more && (before.loading || before.loadingMore || before.page >= before.totalPages)) return
        val page = if (more) before.page + 1 else 1
        val source = if (more) state.value.relatedSource else CatalogRelatedSource.Similar
        val language = state.value.language; val locale = localeRevision; val version = detailRevision
        fun current() = version == detailRevision && locale == localeRevision && state.value.selected == ref && state.value.language == language
        mutable.update { it.copy(related = before.copy(loading = !more, loadingMore = more, error = false)) }
        relatedJob = viewModelScope.launch {
            try {
                suspend fun collectSource(value: CatalogRelatedSource): CatalogLoad<CatalogPage>? {
                    var last: CatalogLoad<CatalogPage>? = null
                    repository.related(ref, value, language, page, force).collect { load ->
                        last = load
                        if (current()) mutable.update { it.copy(relatedSource = value,
                            related = merge(if (more) before else CatalogFeed(loading = false), load, more)
                                .copy(failedPage = if (load.error) page else null)) }
                    }
                    return last
                }
                val last = collectSource(source)
                // Only an actual successful empty first page triggers TMDB's other list.
                // Never mix, rescore or reorder the two sources, or switch sources mid-pagination.
                if (current() && !more && source == CatalogRelatedSource.Similar && last != null &&
                    !last.loading && !last.error && last.data?.items?.isEmpty() == true) {
                    mutable.update { it.copy(related = CatalogFeed(loading = true), relatedSource = CatalogRelatedSource.Recommendations) }
                    collectSource(CatalogRelatedSource.Recommendations)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current()) mutable.update { it.copy(related = it.related.copy(loading = false, loadingMore = false, error = true, failedPage = page)) }
            }
        }
    }
    private fun saveTrail() { saved["catalogDetailTrail"] = ArrayList(visits.map { "${it.entry}|${it.ref.key}" }) }
    fun close() {
        detailRevision++; detailsJob?.cancel(); relatedJob?.cancel()
        val parent = visits.removeLastOrNull()
        saveTrail()
        if (parent == null) {
            saved["catalogSelected"] = null
            mutable.update { it.copy(selected = null, details = CatalogLoad(), related = CatalogFeed(loading = false), detailTrail = emptyList()) }
            return
        }
        saved["catalogSelected"] = parent.ref.key; saved["catalogDetailEntry"] = parent.entry
        val sameLanguage = parent.language == state.value.language
        mutable.update { it.copy(selected = parent.ref, detailEntry = parent.entry, detailTrail = visits.map { visit -> visit.entry },
            details = if (sameLanguage) parent.details else CatalogLoad(),
            related = if (sameLanguage) parent.related else CatalogFeed(loading = false), relatedSource = parent.source) }
        if (state.value.details.data == null) loadDetails(parent.ref, false)
        if (state.value.related.page == 0) loadRelated()
    }
}
