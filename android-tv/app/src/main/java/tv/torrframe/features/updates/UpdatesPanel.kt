package tv.torrframe.features.updates

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.torrframe.R
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.components.*

@Composable fun UpdatesPanel(state: UpdatesUi,
    vm: UpdatesViewModel, install: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvText(AppReleases.URL, translate = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvAction(stringResource(R.string.settings_check_server_update), !state.busy, action = vm::server)
            TvAction(stringResource(R.string.settings_check_app_update), !state.busy, action = vm::check)
        }
        if(state.asset != null) TvAction(stringResource(R.string.settings_download_update, state.asset.version), !state.busy, action = vm::download)
        if(state.downloaded != null) TvAction(stringResource(R.string.settings_install_update), !state.busy, action = install)
        Message(if(state.busy) stringResource(R.string.settings_checking_update) else state.message)
    }
}
