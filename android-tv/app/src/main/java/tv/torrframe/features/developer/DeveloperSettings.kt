package tv.torrframe.features.developer

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.torrframe.R
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.Muted

@Composable fun DeveloperSettings(controls:DeveloperControls, restartSetup: () -> Unit = {}) {
    val state by controls.state.collectAsState()
    val diagnostics by controls.diagnosticsVisible.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TvToggle(stringResource(R.string.developer_diagnostics), diagnostics, Modifier.testTag("developer:diagnostics-toggle"), controls::showDiagnostics)
        TvText(stringResource(R.string.developer_diagnostics_hint), color=Muted)
        TvAction(stringResource(R.string.developer_startup_preview), modifier=Modifier.testTag("developer:startup-preview")) { controls.previewStartup(slow=false) }
        TvAction(stringResource(R.string.developer_startup_slow), modifier=Modifier.testTag("developer:startup-slow")) { controls.previewStartup(slow=true) }
        TvText(stringResource(R.string.developer_startup_hint), color=Muted)
        TvAction(stringResource(R.string.setup_developer_restart), modifier = Modifier.testTag("developer:setup"), action = restartSetup)
        TvText(stringResource(R.string.developer_hint),color=Muted)
        for(provider in DeveloperControls.keyedServices) {
            TvToggle(stringResource(R.string.developer_missing_key,provider),provider in state.missingKeys,
                Modifier.testTag("developer:key:$provider")) { value ->
                controls.update { it.copy(missingKeys=if(value)it.missingKeys+provider else it.missingKeys-provider) }
            }
        }
        TvToggle(stringResource(R.string.developer_metadata_offline),state.metadataOffline,Modifier.testTag("developer:metadata-offline")) { value -> controls.update { it.copy(metadataOffline=value) } }
        TvToggle(stringResource(R.string.developer_bypass_cache),state.bypassCache,Modifier.testTag("developer:bypass-cache")) { value -> controls.update { it.copy(bypassCache=value) } }
        TvToggle(stringResource(R.string.developer_server_offline),state.serverUnavailable,Modifier.testTag("developer:server-offline")) { value -> controls.update { it.copy(serverUnavailable=value) } }
        TvText(stringResource(R.string.developer_cache_hint),color=Muted)
        TvAction(stringResource(R.string.developer_reload_catalog),modifier=Modifier.testTag("developer:reload-catalog"),action=controls::reloadCatalog)
        TvAction(stringResource(R.string.developer_reset),modifier=Modifier.testTag("developer:reset"),action=controls::reset)
    }
}
