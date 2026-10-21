package tv.torrframe.features.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import tv.torrframe.R
import tv.torrframe.TorrFrameApplication
import tv.torrframe.core.localization.uiString
import tv.torrframe.core.localization.withUiLanguage
import tv.torrframe.core.performance.Diagnostics
import tv.torrframe.ui.components.AppLanguage
import tv.torrframe.ui.components.TextEditor
import tv.torrframe.ui.theme.TorrFrameTheme

/** Hosts video and the themed TV controls. Playback lifetime belongs to PlaybackService. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val graph get() = (application as TorrFrameApplication).graph
    private val presentation = PlayerController()
    private val playbackHost = Any()
    private var mediaJob: Job? = null
    private var choosingSubtitle = false
    private var resumeAfterRecreation = false
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var video: PlayerView? = null
    private lateinit var frameRate: FrameRateMatcher
    private var subtitleStyle: Pair<PlayerPreferences, Boolean>? = null
    private var subtitleUrl by mutableStateOf(false)
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingSubtitle = false
        uri?.let(::attachSubtitle)
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.withUiLanguage()) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        choosingSubtitle = savedInstanceState?.getBoolean("choosingSubtitle") == true
        resumeAfterRecreation = savedInstanceState?.getBoolean("playerResume") == true
        graph.playback.claimHost(playbackHost)
        frameRate = FrameRateMatcher(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            val state by presentation.state.collectAsStateWithLifecycle()
            val diagnosticsVisible by graph.developer.diagnosticsVisible.collectAsStateWithLifecycle()
            TorrFrameTheme(settings.uiScalePercent, settings.uiTheme == "light", settings.uiAccent, settings.animationsEnabled) {
                AppLanguage(settings.uiLanguage) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            PlayerScreen(state, settings.builtInPlayer, presentation, ::change,
                                close = { graph.playback.stopHost(playbackHost); finish() }, file = ::openSubtitleFile, url = { subtitleUrl = true }) { controlsVisible ->
                                AndroidView(factory = { context -> PlayerView(context).apply {
                                    useController = false
                                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                    isFocusable = false
                                    player = controller
                                    video = this
                                    subtitleStyle = null
                                } }, modifier = Modifier.fillMaxSize(), update = {
                                    it.player = controller
                                    it.resizeMode = when (settings.builtInPlayer.aspect) {
                                        VideoAspect.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        VideoAspect.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        VideoAspect.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    }
                                    applySubtitles(it, settings.builtInPlayer, controlsVisible)
                                })
                            }
                        }
                        if (diagnosticsVisible)
                            tv.torrframe.features.developer.DiagnosticPanel(graph.networkDiagnostics,
                                Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    if (subtitleUrl) TextEditor(androidx.compose.ui.res.stringResource(R.string.player_subtitle_url), "", dismiss = { subtitleUrl = false }) {
                        subtitleUrl = false
                        val uri = it.trim().toUri()
                        if (uri.scheme in setOf("http", "https")) attachSubtitle(uri) else showMessage(R.string.player_subtitle_failed)
                    }
                    LaunchedEffect(settings.builtInPlayer.matchFrameRate, state.frameRate) {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) frameRate.update(settings.builtInPlayer.matchFrameRate, state.frameRate)
                    }
                }
            }
        }
        lifecycleScope.launch { graph.playback.activeHost.collect { if (it !== playbackHost) finish() } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.ready.await()
            if (canStartMedia()) connect()
            while (isActive) { presentation.refresh(); delay(500) }
        } }
    }
    private fun canStartMedia() = !isFinishing && !isDestroyed && graph.playback.ownsHost(playbackHost)
        && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun connect() {
        if (future != null || !canStartMedia()) return
        val began = Diagnostics.mark()
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync().also { result ->
            result.addListener({
                if (!canStartMedia() || result.isCancelled) return@addListener
                runCatching { result.get() }.onSuccess { player ->
                    controller = player; video?.player = player; presentation.attach(player)
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { Diagnostics.elapsed("playback_first_frame", began) }
                    })
                    prepareMedia(player)
                }.onFailure { presentation.failed() }
            }, ContextCompat.getMainExecutor(this))
        }
    }
    private fun prepareMedia(player: MediaController) {
        mediaJob?.cancel()
        val requested = intent
        val stream = requested.getStringExtra("stream")
        val identity = requested.getStringExtra("identity")
        if (stream.isNullOrBlank() || identity.isNullOrBlank()) { presentation.failed(); return }
        if (player.currentMediaItem?.mediaId == identity) {
            if (resumeAfterRecreation && canStartMedia()) player.play()
            resumeAfterRecreation = false
            return
        }
        graph.playback.pauseInternal()
        mediaJob = lifecycleScope.launch {
            val parts = identity.split(':')
            val position = if (graph.settings.state.value.resume && parts.size == 2 && parts[1].toIntOrNull() != null)
                graph.store.progress(parts[0], parts[1].toInt()) else 0
            ensureActive()
            if (!canStartMedia() || controller !== player) return@launch
            player.setMediaItem(MediaItem.Builder().setUri(stream).setMediaId(identity)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(requested.getStringExtra("title")).build()).build(), position)
            player.prepare(); player.play()
            resumeAfterRecreation = false
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resumeAfterRecreation = false
        controller?.let(::prepareMedia)
    }
    private fun disconnect() {
        video?.player = null
        presentation.detach()
        future?.let(MediaController::releaseFuture); future = null; controller = null
    }
    private fun change(edit: (PlayerPreferences) -> PlayerPreferences) {
        graph.scope.launch {
            try { graph.settings.update { it.copy(builtInPlayer = edit(it.builtInPlayer).normalized()) } }
            catch (e: Exception) { if (e is CancellationException) throw e; withContext(Dispatchers.Main) { showMessage(R.string.player_start_failed) } }
        }
    }
    private fun openSubtitleFile() {
        graph.playback.pauseInternal()
        choosingSubtitle = true
        try { pickSubtitle.launch(arrayOf("*/*")) }
        catch (_: android.content.ActivityNotFoundException) { choosingSubtitle = false; showMessage(R.string.player_no_file_picker) }
    }
    private fun attachSubtitle(uri: Uri) {
        lifecycleScope.launch {
            try { presentation.subtitle(ExternalSubtitles.load(this@PlayerActivity, uri, graph.settings.state.value.uiLanguage == "ru")) }
            catch (e: Exception) { if (e is CancellationException) throw e; showMessage(R.string.player_subtitle_failed) }
        }
    }
    private fun showMessage(resource: Int) { Toast.makeText(this, uiString(resource), Toast.LENGTH_LONG).show() }
    private fun applySubtitles(view: PlayerView, preferences: PlayerPreferences, controlsVisible: Boolean) {
        val key = preferences to controlsVisible
        if (subtitleStyle == key) return
        subtitleStyle = key
        view.subtitleView?.apply {
            setStyle(CaptionStyleCompat(if (preferences.subtitleColor == SubtitleColor.Yellow) android.graphics.Color.YELLOW else android.graphics.Color.WHITE,
                if (preferences.subtitleBackground) 0xCC000000.toInt() else android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK,
                Typeface.create("sans-serif", if (preferences.subtitleBold) Typeface.BOLD else Typeface.NORMAL)))
            setApplyEmbeddedStyles(preferences.embeddedSubtitleStyle)
            setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * preferences.subtitleSize / 100f)
            setBottomPaddingFraction(if (controlsVisible) .32f else .08f)
        }
    }
    override fun onStart() {
        super.onStart()
        graph.playback.hostVisibility(playbackHost, true)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("choosingSubtitle", choosingSubtitle)
        outState.putBoolean("playerResume", controller?.playWhenReady ?: resumeAfterRecreation)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (::frameRate.isInitialized) frameRate.update(graph.settings.state.value.builtInPlayer.matchFrameRate, presentation.state.value.frameRate)
    }
    override fun onStop() {
        mediaJob?.cancel()
        if (isChangingConfigurations) resumeAfterRecreation = controller?.playWhenReady == true
        graph.playback.hostVisibility(playbackHost, false)
        if (!isChangingConfigurations && graph.playback.ownsHost(playbackHost)) {
            if (choosingSubtitle) graph.playback.pauseInternal()
            else { graph.playback.stopHost(playbackHost); finish() }
        }
        if (!choosingSubtitle || controller == null) disconnect()
        frameRate.restore(); super.onStop()
    }
    override fun onDestroy() {
        if (!isChangingConfigurations) graph.playback.stopHost(playbackHost)
        disconnect(); video = null
        frameRate.restore(); super.onDestroy()
    }
}
