package tv.torrserve.silicon.features.torrentsearch.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.features.torrentsearch.data.TorrentSearchActions
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*

data class TorrentPlayback(val hash: String, val file: TorrentFile)
enum class TorrentSearchSource { Builtin, Jackett }
enum class TorrentActionError { Add, Files }
data class TorrentSearchUi(val target: SearchTarget? = null, val snapshot: SearchSnapshot = SearchSnapshot(),
    val queries: List<String> = emptyList(), val options: TorrentSearchOptions = TorrentSearchOptions(),
    val starting: Boolean = false, val adding: String? = null, val added: Set<String> = emptySet(),
    val actionError: TorrentActionError? = null, val searchError: Boolean = false,
    val files: List<TorrentFile> = emptyList(), val fileHash: String? = null, val play: TorrentPlayback? = null, val source: TorrentSearchSource = TorrentSearchSource.Builtin) {
    val searching get() = !searchError && (starting || snapshot.searching)
}
class TorrentSearchViewModel(private val engine: TorrentSearchEngine, private val actions: TorrentSearchActions,
    private val saved: SavedStateHandle,
    private val options: suspend () -> TorrentSearchOptions = { TorrentSearchOptions() },
    private val prepare: suspend (SearchTarget,TorrentSearchOptions) -> SearchTarget = { target, _ -> target },
    private val jackettEngine: TorrentSearchEngine? = null) : ViewModel() {
    private val mutable = MutableStateFlow(TorrentSearchUi())
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var actionJob: Job? = null
    private var generation = 0
    init {
        saved.get<String>("torrentTitle")?.let { title -> open(SearchTarget(title,saved["torrentOriginal"] ?: "",saved["torrentYear"],
            if (saved.get<String>("torrentType") == "Series") MediaType.Series else MediaType.Movie,saved["torrentTmdbId"]),
            TorrentSearchSource.entries.firstOrNull { it.name == saved.get<String>("torrentSource") } ?: TorrentSearchSource.Builtin) }
    }
    fun open(target: SearchTarget, source: TorrentSearchSource = TorrentSearchSource.Builtin) {
        searchJob?.cancel(); actionJob?.cancel(); val version = ++generation
        saved["torrentSource"] = source.name; saved["torrentTitle"] = target.title; saved["torrentOriginal"] = target.originalTitle; saved["torrentYear"] = target.year; saved["torrentType"] = target.type.name; saved["torrentTmdbId"] = target.tmdbId
        mutable.value = TorrentSearchUi(target=target,queries=target.queries(),starting=true,source=source)
        searchJob = viewModelScope.launch {
            try {
                val searchEngine = if (source == TorrentSearchSource.Jackett) requireNotNull(jackettEngine) else engine
                val config=options().let { if (source == TorrentSearchSource.Jackett) it.copy(enabledProviders=setOf("jackett")) else it }.normalized()
                val queryTarget=if(searchEngine.providers.any { target.type in it.types && config.enabledProviders?.contains(it.id)!=false })prepare(target,config)else target
                if(version!=generation)return@launch
                mutable.update { it.copy(options=config,queries=queryTarget.queries(config.titleMode)) }
                searchEngine.search(queryTarget,config).collect { snapshot -> if (version == generation) mutable.update { it.copy(snapshot=snapshot,starting=false) } }
            }
            catch (e: Exception) { if (e is CancellationException) throw e; if(version==generation) mutable.update { it.copy(starting=false,searchError=true) } }
        }
    }
    fun retry() { if(state.value.adding==null) state.value.target?.let { open(it, state.value.source) } }
    fun close() {
        generation++; searchJob?.cancel(); actionJob?.cancel(); saved["torrentTitle"] = null
        mutable.value = TorrentSearchUi()
    }
    fun act(row: TorrentResult, watch: Boolean) {
        if (state.value.adding != null) return
        val version = generation
        mutable.update { it.copy(adding=row.key,actionError=null) }
        actionJob = viewModelScope.launch {
            var added = false
            try {
                val hash = actions.add(row); added = true
                if (version != generation) return@launch
                mutable.update { it.copy(added=it.added+row.key) }
                if (watch) {
                    val files = actions.files(hash)
                    require(files.isNotEmpty())
                    val preferred = actions.preferred(hash,files)
                    if (version != generation) return@launch
                    mutable.update { if(preferred!=null) it.copy(play=TorrentPlayback(hash,preferred)) else it.copy(files=files,fileHash=hash) }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if(version==generation) mutable.update { it.copy(actionError=if(added)TorrentActionError.Files else TorrentActionError.Add) }
            } finally { if(version==generation) mutable.update { it.copy(adding=null) } }
        }
    }
    fun chooseFile(file: TorrentFile) { val hash=state.value.fileHash ?: return; mutable.update { it.copy(play=TorrentPlayback(hash,file),files=emptyList(),fileHash=null) } }
    fun closeFiles() { mutable.update { it.copy(files=emptyList(),fileHash=null) } }
    fun consumePlay() { mutable.update { it.copy(play=null) } }
}
