package tv.torrserve.silicon.features.updates

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.torrserve.silicon.R
import androidx.compose.ui.unit.dp
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.ui.components.*

@Composable fun UpdatesPanel(state: UpdatesUi, settings: Settings, edit: (String) -> Unit,
    vm: UpdatesViewModel, install: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditField(stringResource(R.string.settings_update_repo), settings.updateRepository, russian = true, onValue = edit)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction(stringResource(R.string.settings_check_server_update), !state.busy, action = vm::server)
            TvAction(stringResource(R.string.settings_check_app_update), !state.busy, action = vm::check)
        }
        if(state.asset != null) TvAction(stringResource(R.string.settings_download_update, state.asset.version), !state.busy, action = vm::download)
        if(state.downloaded != null) TvAction(stringResource(R.string.settings_install_update), !state.busy, action = install)
        Message(if(state.busy) stringResource(R.string.settings_checking_update) else state.message)
    }
}
