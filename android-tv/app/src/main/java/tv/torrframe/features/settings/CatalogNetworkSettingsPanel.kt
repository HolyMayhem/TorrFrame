package tv.torrframe.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tv.torrframe.R
import tv.torrframe.core.network.*
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.Muted

@Composable internal fun CatalogNetworkSettingsPanel(value: CatalogNetworkSettings,
    edit: ((CatalogNetworkSettings) -> CatalogNetworkSettings) -> Unit) {
    var invalid by remember { mutableStateOf(false) }
    fun change(block: (CatalogNetworkSettings) -> CatalogNetworkSettings) {
        try { edit(block); invalid = false } catch (_: IllegalArgumentException) { invalid = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (invalid) TvText(stringResource(R.string.catalog_network_invalid), color = Muted)
        TvText(stringResource(R.string.catalog_network_source), color = Muted)
        CatalogSource.entries.forEach { source ->
            TvAction(if (source == CatalogSource.Auto) stringResource(R.string.catalog_source_auto) else if (source == CatalogSource.Cub) "CUB" else "TMDB",
                selected = value.source == source, modifier = Modifier.fillMaxWidth().testTag("catalog:source:${source.name}")) {
                change { it.copy(source = source) }
            }
        }
        TvText(stringResource(R.string.catalog_source_hint), color = Muted)
        TvToggle(stringResource(R.string.catalog_show_favorites), value.showFavorites, Modifier.testTag("catalog:home:Favorites")) { enabled ->
            change { it.copy(showFavorites = enabled) }
        }
        TvToggle(stringResource(R.string.catalog_show_watch_later), value.showWatchLater, Modifier.testTag("catalog:home:WatchLater")) { enabled ->
            change { it.copy(showWatchLater = enabled) }
        }
        var advanced by remember { mutableStateOf(false) }
        TvAction(stringResource(if (advanced) R.string.catalog_advanced_hide else R.string.catalog_advanced_show),
            modifier = Modifier.fillMaxWidth().testTag("catalog:advanced")) { advanced = !advanced }
        if (advanced) {
            TvText(stringResource(R.string.catalog_network_route), color = Muted)
            CatalogRoute.entries.forEach { route ->
                TvAction(stringResource(when (route) {
                    CatalogRoute.Direct -> R.string.catalog_network_direct
                    CatalogRoute.Auto -> R.string.catalog_network_auto
                    CatalogRoute.Proxy -> R.string.catalog_network_proxy
                }), selected = value.route == route, modifier = Modifier.fillMaxWidth().testTag("catalog:route:${route.name}")) {
                    change { it.copy(route = route) }
                }
            }
            TvText(stringResource(R.string.catalog_network_proxy_hint), color = Muted)
            EditField(stringResource(R.string.catalog_network_api), value.apiProxy, keyboardType = KeyboardType.Uri) { text ->
                change { it.copy(apiProxy = text.trim()) }
            }
            repeat(3) { index ->
                EditField(stringResource(R.string.catalog_network_image, index + 1), value.imageMirrors.getOrNull(index).orEmpty(), keyboardType = KeyboardType.Uri) { text ->
                    change { current ->
                        val mirrors = current.imageMirrors.toMutableList()
                        while (mirrors.size <= index) mirrors += ""
                        mirrors[index] = text.trim()
                        current.copy(imageMirrors = mirrors.filter(String::isNotBlank).distinct())
                    }
                }
            }
            TvText(stringResource(R.string.catalog_network_cub_hint), color = Muted)
            EditField(stringResource(R.string.catalog_network_cub_url), value.cubUrl, keyboardType = KeyboardType.Uri) { text ->
                change { it.copy(cubUrl = text.trim()) }
            }
            EditField(stringResource(R.string.catalog_network_cub_token), value.cubToken, secret = true) { text ->
                change { it.copy(cubToken = text.trim()) }
            }
            if (value.cubToken.isNotBlank()) TvAction(stringResource(R.string.catalog_network_clear_token)) {
                change { it.copy(source = CatalogSource.Tmdb, cubToken = "") }
            }
        }
    }
}
