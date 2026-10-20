package tv.torrframe.onboarding

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import tv.torrframe.AppGraph
import tv.torrframe.R
import tv.torrframe.core.localization.uiString
import tv.torrframe.core.server.ApplicationActionFailure
import tv.torrframe.core.server.ServerAddress
import tv.torrframe.core.server.ServerState

/** The single adapter between the independent wizard and existing application services. */
@Composable fun SetupHost(graph: AppGraph, enabled: Boolean) {
    val progress by graph.setup.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val preference by graph.phonePreferences.state.collectAsStateWithLifecycle()
    val connection by graph.connection.state.collectAsStateWithLifecycle()
    val server by graph.server.state.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableIntStateOf(0) }
    var useLocal by rememberSaveable { mutableStateOf(settings.useLocalServer) }
    var address by rememberSaveable { mutableStateOf(if (settings.useLocalServer) "" else settings.torrServerUrl) }

    LaunchedEffect(progress.step) {
        error = 0
        if (progress.step == SetupStep.Welcome) {
            useLocal = settings.useLocalServer
            address = if (settings.useLocalServer) "" else settings.torrServerUrl
        }
    }
    fun change(block: suspend () -> Unit) {
        if (saving || progress.busy) return
        saving = true; error = 0
        graph.scope.launch {
            try { graph.ready.await(); block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                error = (e as? ApplicationActionFailure)?.resource ?: R.string.setup_operation_failed
            } finally { saving = false }
        }
    }
    fun complete() {
        if (progress.step != SetupStep.Server) { graph.setup.next(); return }
        val target = if (useLocal) ServerAddress.local(settings.port) else
            runCatching { ServerAddress.normalize(address) }.getOrElse { error = R.string.server_invalid_url; return }
        val changed = useLocal != settings.useLocalServer || target != settings.torrServerUrl
        if (changed && graph.server.hasPlayback) { error = R.string.server_stop_playback; return }
        change {
            if (changed) {
                // Commit the selected connection before closing. Network readiness is not
                // a condition for leaving setup: the existing action owns its check/start.
                val operation = graph.scope.async { graph.serverActions.connect(target, useLocal, settings.port) }
                try {
                    kotlinx.coroutines.coroutineScope {
                        val persisted = async {
                            graph.settings.state.first { it.useLocalServer == useLocal && it.torrServerUrl == target }
                        }
                        kotlinx.coroutines.selects.select<Unit> {
                            persisted.onAwait { }
                            operation.onAwait { }
                        }
                        persisted.cancel()
                    }
                } catch (e: Exception) { operation.cancel(); throw e }
                graph.scope.launch {
                    try { operation.await() }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Toast.makeText(graph.app, graph.app.uiString(R.string.setup_connection_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            graph.setup.finish()
        }
    }
    val status = when {
        settings.useLocalServer && server is ServerState.Starting -> R.string.server_process_starting
        connection.status == tv.torrframe.core.server.ConnectionStatus.Connected -> R.string.setup_server_ready
        else -> R.string.setup_server_later
    }
    SetupWizard(progress.step, enabled, progress.busy || saving,
        if (progress.saveFailed) R.string.setup_save_failed else error,
        SetupChoices(settings.uiLanguage, settings.uiTheme, settings.uiAccent, preference.enabled, useLocal, address),
        SetupCallbacks(
            next = ::complete, back = graph.setup::back, skip = graph.setup::finish,
            language = { language -> change { graph.settings.update { it.copy(uiLanguage = language) } } },
            theme = { theme -> change { graph.settings.update { it.copy(uiTheme = theme) } } },
            accent = { accent -> change { graph.settings.update { it.copy(uiAccent = accent) } } },
            phone = { enabled -> change { graph.phoneRemote.initialize(); graph.phoneRemote.enabled(enabled) } },
            local = { useLocal = it; error = 0 }, address = { address = it; error = 0 }),
        serverStatus = status,
        remote = { SetupPhoneCard(graph.phoneRemote, preference.enabled) })
}
