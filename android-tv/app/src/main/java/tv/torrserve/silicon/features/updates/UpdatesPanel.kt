package tv.torrserve.silicon.features.updates

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.ui.components.*

@Composable fun UpdatesPanel(state: UpdatesUi, settings: Settings, edit: (String) -> Unit,
    vm: UpdatesViewModel, install: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditField("Android TV GitHub repository", settings.updateRepository, onValue = edit)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction("Check TorrServer", !state.busy, action = vm::server)
            TvAction("Check App", !state.busy, action = vm::check)
        }
        if(state.asset != null) TvAction("Download ${state.asset.version}", !state.busy, action = vm::download)
        if(state.downloaded != null) TvAction("Install Update", !state.busy, action = install)
        Message(if(state.busy) "Checking / downloading…" else state.message)
    }
}
