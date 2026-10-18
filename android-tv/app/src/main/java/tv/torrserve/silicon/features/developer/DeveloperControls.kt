package tv.torrserve.silicon.features.developer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.torrserve.silicon.core.network.ServiceFailure

/** Session-only fault injection. Production wiring passes BuildConfig.DEBUG. */
data class DeveloperState(val missingKeys:Set<String> = emptySet(), val metadataOffline:Boolean=false,
    val bypassCache:Boolean=false, val serverUnavailable:Boolean=false) {
    val active get()=missingKeys.isNotEmpty() || metadataOffline || bypassCache || serverUnavailable
    fun bypassMetadata(provider:String)=bypassCache || metadataOffline || provider in missingKeys
}
class DeveloperControls(val enabled:Boolean) {
    private val overlay = MutableStateFlow(false)
    val diagnosticsVisible = overlay.asStateFlow()
    fun showDiagnostics(value: Boolean) { if (enabled) overlay.value = value }
    private val mutable=MutableStateFlow(DeveloperState())
    val state=mutable.asStateFlow()
    private val reloads=MutableStateFlow(0)
    val catalogReloads=reloads.asStateFlow()
    private val reloadSequence=java.util.concurrent.atomic.AtomicInteger()
    fun reloadCatalog(){if(enabled)reloads.value=reloadSequence.incrementAndGet()}
    fun update(change:(DeveloperState)->DeveloperState) { if(enabled)mutable.update(change) }
    fun reset(){
        mutable.value=DeveloperState()
        // Clear a simulated catalog error as well as the switches. Keep revisions
        // increasing so an already visible CatalogViewModel accepts this refresh.
        reloadCatalog()
    }
    /** Throw before a request is enqueued; actual credentials never change or reach a test endpoint. */
    fun beforeRequest(service:String) {
        if(!enabled)return
        val s=state.value
        when {
            service=="TorrServer" && s.serverUnavailable -> throw ServiceFailure(service,0)
            service in metadataServices && s.metadataOffline -> throw ServiceFailure(service,408)
            service in s.missingKeys && service in keyedServices -> throw ServiceFailure(service,401)
        }
    }
    companion object {
        val keyedServices=setOf("TMDB","OMDb","Kinopoisk")
        private val metadataServices=keyedServices+"AniList"
    }
}
