package tv.torrframe.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import tv.torrframe.R
import tv.torrframe.features.torrentsearch.data.*
import tv.torrframe.ui.components.EditField
import tv.torrframe.ui.components.TvAction
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.theme.Muted
import tv.torrserve.torrentsearch.model.MediaType

internal fun LazyListScope.adaptiveSearchSettingsItems(state: SettingsUi, vm: SettingsViewModel) {
    item("ts:adaptive") {
        val process by vm.dpiStatus.collectAsState()
        val busy by vm.dpiBusy.collectAsState()
        SettingsGroup(stringResource(R.string.dpi_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdaptiveMode.entries.forEach { mode ->
                    TvAction(stringResource(mode.label), selected = state.draft.adaptiveMode == mode,
                        modifier = Modifier.testTag("dpi:mode:${mode.name}").semantics { selected = state.draft.adaptiveMode == mode }) { vm.adaptiveMode(mode) }
                }
            }
            TvText(stringResource(R.string.dpi_status, stringResource(process.label)), color = Muted)
            EditField(stringResource(R.string.dpi_query), state.dpiQuery, onValue = vm::dpiQuery)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaType.entries.forEach { type ->
                    val label = if (type == MediaType.Movie) R.string.dpi_movie else R.string.dpi_series
                    TvAction(stringResource(label), selected = state.dpiType == type,
                        modifier = Modifier.semantics { selected = state.dpiType == type }) { vm.dpiType(type) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val canRun = !busy && state.dpiQuery.isNotBlank()
                TvAction(stringResource(R.string.dpi_check), enabled = canRun, modifier = Modifier.testTag("dpi:check")) {
                    vm.runAdaptive(AdaptiveDiagnostic.Check)
                }
                TvAction(stringResource(R.string.dpi_retune), enabled = canRun, modifier = Modifier.testTag("dpi:retune")) {
                    vm.runAdaptive(AdaptiveDiagnostic.Retune)
                }
                TvAction(stringResource(R.string.dpi_compare), enabled = canRun, modifier = Modifier.testTag("dpi:compare")) {
                    vm.runAdaptive(AdaptiveDiagnostic.Compare)
                }
                TvAction(stringResource(R.string.dpi_reset), enabled = !busy, modifier = Modifier.testTag("dpi:reset"),
                    action = vm::resetDpiStrategies)
            }
            (if (busy) R.string.dpi_checking else state.dpiMessage)?.let { TvText(stringResource(it), color = Muted) }
        }
    }
    state.dpiReport?.let { report ->
        item("dpi:summary") {
            SettingsGroup(report.query) {
                val proxy = report.proxiedUnique
                TvText(if (proxy == null) stringResource(R.string.dpi_total, report.unique)
                else stringResource(R.string.dpi_comparison, report.unique, proxy, proxy - report.unique))
                if (proxy != null) TvText(stringResource(R.string.dpi_difference, report.directOnly, report.proxyOnly), color = Muted)
            }
        }
        report.sources.forEach { source ->
            item("dpi:source:${source.name}") {
                SettingsGroup(source.name) {
                    ProbeLine(stringResource(report.firstMode.label), source.first)
                    source.second?.let { ProbeLine("ByeDPI", it) }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ProbeLine(mode: String, probe: SearchProbe) {
    val routes = probe.routes.map { route ->
        when (route) {
            SearchRoute.ByeDpi -> "ByeDPI"
            SearchRoute.Direct -> stringResource(R.string.dpi_direct)
            SearchRoute.Local -> stringResource(R.string.dpi_local)
        }
    }.ifEmpty { listOf("—") }.joinToString(" / ")
    val strategies = probe.strategies.joinToString(" / ") { it.name }
    val result = if (probe.ok) stringResource(R.string.dpi_available) else when (probe.error) {
        SearchProbeError.Timeout -> stringResource(R.string.dpi_timeout)
        SearchProbeError.Dns -> stringResource(R.string.dpi_dns)
        SearchProbeError.Tls -> stringResource(R.string.dpi_tls)
        SearchProbeError.Http -> stringResource(R.string.dpi_http, probe.httpStatus ?: 0)
        SearchProbeError.Response -> stringResource(R.string.dpi_response)
        SearchProbeError.Proxy -> stringResource(R.string.dpi_proxy_error)
        SearchProbeError.Local -> stringResource(R.string.dpi_local_error)
        else -> stringResource(R.string.dpi_network_error)
    }
    TvText(stringResource(R.string.dpi_result, mode, routes, result, probe.count, probe.millis), translate = false)
    if (strategies.isNotBlank()) TvText(strategies, color = Muted, translate = false)
}

private val AdaptiveMode.label get() = when (this) {
    AdaptiveMode.Off -> R.string.dpi_off
    AdaptiveMode.Auto -> R.string.dpi_auto
    AdaptiveMode.Forced -> R.string.dpi_forced
}

private val DpiProcessState.label get() = when (this) {
    DpiProcessState.Idle -> R.string.dpi_idle
    DpiProcessState.Starting -> R.string.dpi_starting
    DpiProcessState.Running -> R.string.dpi_running
    DpiProcessState.Failed -> R.string.dpi_proxy_error
}
