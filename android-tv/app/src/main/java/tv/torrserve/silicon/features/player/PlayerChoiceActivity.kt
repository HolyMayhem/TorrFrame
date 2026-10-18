package tv.torrserve.silicon.features.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.torrserve.silicon.R
import tv.torrserve.silicon.TorrFrameApplication
import tv.torrserve.silicon.ui.components.AppLanguage
import tv.torrserve.silicon.core.localization.withUiLanguage
import tv.torrserve.silicon.core.server.LocalServerService
import tv.torrserve.silicon.ui.components.TvText
import tv.torrserve.silicon.ui.theme.*

/** Stays behind the external player so the local server lease ends when playback returns. */
class PlayerChoiceActivity : ComponentActivity() {
    private val graph get() = (application as TorrFrameApplication).graph
    private var players by mutableStateOf<List<ExternalPlayer>?>(null)
    private var message by mutableIntStateOf(0)
    private var waiting by mutableStateOf(false)
    private var ownsLease = false
    private var externalBackgrounded = false
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.withUiLanguage()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waiting = savedInstanceState?.getBoolean("waiting") == true
        ownsLease = savedInstanceState?.getBoolean("lease") == true
        externalBackgrounded = savedInstanceState?.getBoolean("externalBackgrounded") == true
        if (intent.getStringExtra("stream").isNullOrBlank() || intent.getStringExtra("identity").isNullOrBlank()) { finish(); return }
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            TorrFrameTheme(settings.uiScalePercent, settings.uiTheme == "light", settings.uiAccent, settings.animationsEnabled) {
                AppLanguage(settings.uiLanguage) {
                    Box(Modifier.fillMaxSize().background(Background).padding(28.dp), contentAlignment = Alignment.Center) {
                        val found = players
                        if (found == null || waiting) TvText(androidx.compose.ui.res.stringResource(R.string.player_loading))
                        else PlayerChoices(found, includeInternal = true, selected = settings.externalPlayer,
                            message = if (message == 0) "" else androidx.compose.ui.res.stringResource(message),
                            choose = ::choose, dismiss = ::finish)
                    }
                }
            }
        }
        lifecycleScope.launch {
            try {
                graph.ready.await()
                refresh()
                if (!waiting) {
                    val settings = graph.settings.state.value
                    when (val target = playerDestination(settings.playerMode, settings.externalPlayer, players.orEmpty())) {
                        PlayerDestination.Internal -> choose(null)
                        is PlayerDestination.External -> choose(target.player)
                        is PlayerDestination.Choose -> if (target.missingPreferred) message = R.string.player_missing
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = R.string.player_start_failed; players = emptyList()
            }
        }
    }

    private suspend fun refresh() { players = graph.externalPlayers.available(intent.getStringExtra("stream")) }

    private fun choose(player: ExternalPlayer?) {
        if (waiting) return
        if (player == null) {
            startActivity(Intent(this, PlayerActivity::class.java).putExtras(requireNotNull(intent.extras)))
            finish()
            return
        }
        waiting = true
        lifecycleScope.launch {
            try {
                graph.playback.stopInternal()
                if (graph.settings.state.value.useLocalServer) {
                    graph.server.requireRunning()
                    graph.server.externalPlayback(true); ownsLease = true
                    LocalServerService.start(this@PlayerChoiceActivity)
                }
                startActivity(graph.externalPlayers.intent(player, requireNotNull(intent.getStringExtra("stream")), intent.getStringExtra("title").orEmpty()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                releaseLease(); waiting = false; externalBackgrounded = false
                message = R.string.player_external_failed
                players = try { graph.externalPlayers.available(intent.getStringExtra("stream")) }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; emptyList() }
            }
        }
    }
    private fun releaseLease() {
        if (ownsLease) { graph.server.externalPlayback(false); ownsLease = false }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waiting", waiting); outState.putBoolean("lease", ownsLease)
        outState.putBoolean("externalBackgrounded", externalBackgrounded)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        if (waiting && !isChangingConfigurations) externalBackgrounded = true
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        // Some players forward to another Activity or use singleTask, returning an
        // activity result immediately. Release only after our screen actually returns.
        if (waiting && externalBackgrounded) finish()
    }
    override fun onDestroy() {
        if (!isChangingConfigurations) releaseLease()
        super.onDestroy()
    }
}
