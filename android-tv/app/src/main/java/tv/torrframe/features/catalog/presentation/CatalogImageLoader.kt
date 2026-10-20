package tv.torrframe.features.catalog.presentation

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalImageRecovery = staticCompositionLocalOf { 0 }

/** One connectivity observer per activity, not one callback/timer for every loaded poster. */
@Composable fun CatalogImageRecovery(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        var validated: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (validated != network) { validated = network; scope.launch { revision++ } }
                } else if (validated == network) validated = null
            }
            override fun onLost(network: Network) { if (validated == network) validated = null }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    CompositionLocalProvider(LocalImageRecovery provides revision, content = content)
}

/** Failed images retry twice while visible, then wait for connectivity recovery or activity resume. */
@Composable internal fun rememberCatalogImagePainter(url: String): AsyncImagePainter {
    val context = LocalContext.current
    val settings = LocalCatalogImageSettings.current
    val recovery = LocalImageRecovery.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).size(1280, 1280).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var retries by remember(url, recovery, settings.route, settings.imageMirrors) { mutableIntStateOf(0) }
    LaunchedEffect(recovery, settings.route, settings.imageMirrors) {
        if (painter.state.value is AsyncImagePainter.State.Error) painter.restart()
    }
    LaunchedEffect(state, recovery, lifecycle) {
        if (lifecycle.isAtLeast(Lifecycle.State.STARTED) && state is AsyncImagePainter.State.Error && url.isNotBlank() && retries < 2) {
            delay(1_500L * (retries + 1))
            retries++
            painter.restart()
        }
    }
    return painter
}
