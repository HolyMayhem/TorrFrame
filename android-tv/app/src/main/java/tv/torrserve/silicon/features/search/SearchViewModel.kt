package tv.torrserve.silicon.features.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.features.library.friendly

data class SearchUi(val query: String = "", val filter: String = "All", val sort: String = "Seeds",
    val results: List<SearchResult> = emptyList(), val searching: Boolean = false, val adding: String? = null,
    val added: Set<String> = emptySet(), val message: String = "", val searched: Boolean = false)
class SearchViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SearchUi())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun filter(value: String) { mutable.update { it.copy(filter = value) } }
    fun sort(value: String) { mutable.update { it.copy(sort = value) } }
    fun search(value: String = state.value.query) {
        job?.cancel()
        mutable.update { it.copy(query = value, searching = true, message = "", searched = true) }
        val filter = state.value.filter
        job = viewModelScope.launch {
            try {
                graph.ready.await()
                if (value.isBlank()) { mutable.update { it.copy(results = emptyList()) }; return@launch }
                val page = tv.torrserve.silicon.core.performance.Diagnostics.timed("search_results") {
                    graph.jackett.search(graph.settings.state.value, value.trim(), filter)
                }
                mutable.update { it.copy(results = page.results, message = if(page.partial) "Some indexers returned incomplete results." else "") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = friendly(e), results = emptyList()) }
            } finally { if (currentCoroutineContext().isActive) mutable.update { it.copy(searching = false) } }
        }
    }
    fun cancel() { job?.cancel(); mutable.update { it.copy(searching = false) } }
    fun add(result: SearchResult, watch: Boolean, done: (String) -> Unit) {
        if (state.value.adding != null) return
        mutable.update { it.copy(adding = result.id, message = "") }
        viewModelScope.launch {
            try {
                graph.ready.await(); val hash = graph.library.add(result)
                mutable.update { it.copy(added = it.added + result.id, message = "Added to your library.") }
                if (watch) done(hash)
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(message = friendly(e)) } }
            finally { mutable.update { it.copy(adding = null) } }
        }
    }
}
