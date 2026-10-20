package tv.torrframe.features.developer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.torrframe.core.network.ServiceFailure

/** Fault injection stays session-only, even when menu access has been unlocked persistently. */
data class DeveloperState(val missingKeys:Set<String> = emptySet(), val metadataOffline:Boolean=false,
    val bypassCache:Boolean=false, val serverUnavailable:Boolean=false) {
    val active get()=missingKeys.isNotEmpty() || metadataOffline || bypassCache || serverUnavailable
    fun bypassMetadata(provider:String)=bypassCache || metadataOffline || provider in missingKeys
}
data class StartupPreviewRequest(val sequence: Int, val readyDelayMs: Long)

class DeveloperControls(enabled:Boolean) {
    private val access = MutableStateFlow(enabled)
    val accessible = access.asStateFlow()
    val enabled get() = access.value
    fun unlock(): Boolean {
        if (enabled) return false
        setEnabled(true)
        return true
    }
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (!value) {
            // Leaving developer mode must not leave simulated outages or overlays behind.
            reset()
            overlay.value = false
            startup.value = null
        }
        access.value = value
    }
    private val startupSequence = java.util.concurrent.atomic.AtomicInteger()
    private val startup = MutableStateFlow<StartupPreviewRequest?>(null)
    val startupPreviews = startup.asStateFlow()
    fun previewStartup(slow: Boolean) {
        if (enabled) startup.value = StartupPreviewRequest(startupSequence.incrementAndGet(), if (slow) 7_000L else 0L)
    }
    fun takeStartupPreview(): StartupPreviewRequest? = startup.value.also { startup.value = null }
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
