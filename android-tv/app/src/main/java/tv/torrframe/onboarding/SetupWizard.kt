package tv.torrframe.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.MaterialTheme
import tv.torrframe.R
import tv.torrframe.core.storage.UiAccent
import tv.torrframe.ui.components.*
import tv.torrframe.ui.sound.UiBackHandler as BackHandler
import tv.torrframe.ui.sound.UiDialog as Dialog
import tv.torrframe.ui.theme.*

/** Presentation has no AppGraph, network calls, preferences or application startup logic. */
data class SetupChoices(val language: String, val theme: String, val accent: String,
    val phoneEnabled: Boolean, val useLocal: Boolean, val serverAddress: String)
data class SetupCallbacks(val next: () -> Unit, val back: () -> Unit, val skip: () -> Unit,
    val language: (String) -> Unit, val theme: (String) -> Unit, val accent: (String) -> Unit,
    val phone: (Boolean) -> Unit, val local: (Boolean) -> Unit, val address: (String) -> Unit)

@Composable fun SetupWizard(step: SetupStep?, enabled: Boolean, busy: Boolean, error: Int,
    choices: SetupChoices, actions: SetupCallbacks, serverStatus: Int, remote: @Composable () -> Unit) {
    val motion = LocalAnimationsEnabled.current
    val duration = if (motion) 260 else 0
    val visible = enabled && step != null && step != SetupStep.Finished
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    // Retain the last page during the closing animation instead of flashing empty content.
    var lastStep by remember { mutableStateOf(SetupStep.Welcome) }
    if (step != null && step != SetupStep.Finished) lastStep = step
    if (!visible && !visibility.currentState) return
    val primary = remember { FocusRequester() }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(.65f)
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        BackHandler(visible && !busy) {
            if (lastStep == SetupStep.Welcome) actions.skip() else actions.back()
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            val welcome = lastStep == SetupStep.Welcome
            val width by animateDpAsState(minOf(maxWidth, if (welcome) 560.dp else 860.dp),
                tween(duration, easing = FastOutSlowInEasing), label = "setupWidth")
            val height by animateDpAsState(minOf(maxHeight, if (welcome) 290.dp else 460.dp),
                tween(duration, easing = FastOutSlowInEasing), label = "setupHeight")
            val surface by animateColorAsState(Surface, tween(duration), label = "setupSurface")
            AnimatedVisibility(visibleState = visibility,
                enter = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .98f),
                exit = fadeOut(tween(duration))) {
                Column(Modifier.width(width).height(height).clip(RoundedCornerShape(28.dp))
                    .background(surface).border(1.dp, Outline, RoundedCornerShape(28.dp))
                    .padding(28.dp).testTag("setup:panel"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.brand_mark), contentDescription = null,
                            colorFilter = ColorFilter.tint(TextPrimary), modifier = Modifier.size(28.dp))
                        TvText("TorrFrame", style = MaterialTheme.typography.titleMedium, translate = false)
                        Spacer(Modifier.weight(1f))
                        if (!welcome) {
                            TvText(stringResource(R.string.setup_progress, lastStep.number, SetupStep.COUNT),
                                style = MaterialTheme.typography.labelLarge, color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(SetupStep.COUNT) { index ->
                                    val indicatorWidth by animateDpAsState(if (index + 1 == lastStep.number) 22.dp else 6.dp,
                                        tween(duration), label = "setupStep$index")
                                    Box(Modifier.width(indicatorWidth).height(6.dp).background(
                                        if (index + 1 <= lastStep.number) AccentText else Outline, CircleShape))
                                }
                            }
                        }
                    }
                    AnimatedContent(lastStep, Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            (fadeIn(tween(duration, delayMillis = if (motion) 60 else 0)) +
                                slideInVertically(tween(duration)) { if (motion) it / 24 else 0 }) togetherWith
                                fadeOut(tween(if (motion) 100 else 0)) using SizeTransform(clip = false)
                        }, label = "setupPage") { page ->
                        Column(Modifier.fillMaxSize().testTag("setup:page:${page.name}"),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            TvText(stringResource(when (page) {
                                SetupStep.Language -> R.string.setup_language_title
                                SetupStep.Appearance -> R.string.setup_appearance_title
                                SetupStep.PhoneRemote -> R.string.setup_phone_title
                                SetupStep.Server -> R.string.setup_server_title
                                else -> R.string.setup_welcome_title
                            }), style = MaterialTheme.typography.headlineMedium)
                            when (page) {
                                SetupStep.Welcome -> {
                                    TvText(stringResource(R.string.setup_welcome_body), color = Muted)
                                    TvText(stringResource(R.string.setup_duration, SetupStep.COUNT), color = AccentText,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                                SetupStep.Language -> {
                                    TvText(stringResource(R.string.setup_language_body), color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TvAction("Русский", !busy, selected = choices.language == "ru", translate = false,
                                            modifier = Modifier.testTag("setup:language:ru")) { actions.language("ru") }
                                        TvAction("English", !busy, selected = choices.language == "en", translate = false,
                                            modifier = Modifier.testTag("setup:language:en")) { actions.language("en") }
                                    }
                                }
                                SetupStep.Appearance -> AppearanceCard(choices, actions, busy)
                                SetupStep.PhoneRemote -> {
                                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                        Box(Modifier.width(182.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { remote() }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            TvText(stringResource(R.string.setup_phone_body), color = Muted)
                                            TvText(stringResource(R.string.setup_phone_instruction), color = Muted,
                                                style = MaterialTheme.typography.labelLarge)
                                            // The existing toggle owns its sounds, focus and accessibility semantics.
                                            TvToggle(stringResource(R.string.phone_enable), choices.phoneEnabled,
                                                Modifier.testTag("setup:phone:enabled")) { if (!busy) actions.phone(it) }
                                            TvText(stringResource(R.string.setup_phone_optional), color = Muted,
                                                style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                SetupStep.Server -> {
                                    TvText(stringResource(R.string.setup_server_body), color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TvAction(stringResource(R.string.setup_server_builtin), !busy, selected = choices.useLocal,
                                            modifier = Modifier.testTag("setup:server:local")) { actions.local(true) }
                                        TvAction(stringResource(R.string.setup_server_external), !busy, selected = !choices.useLocal,
                                            modifier = Modifier.testTag("setup:server:external")) { actions.local(false) }
                                    }
                                    if (!choices.useLocal) EditField(stringResource(R.string.server_url), choices.serverAddress,
                                        keyboardType = KeyboardType.Uri, modifier = Modifier.testTag("setup:server:address"),
                                        russian = choices.language == "ru", onValue = actions.address)
                                    else TvText(stringResource(serverStatus), color = Muted,
                                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("setup:server:status"))
                                }
                                SetupStep.Finished -> Unit
                            }
                        }
                    }
                    if (error != 0) TvText(stringResource(error), color = Muted,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("setup:error"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (welcome) TvAction(stringResource(R.string.setup_skip), !busy,
                            modifier = Modifier.testTag("setup:skip"), action = actions.skip)
                        else TvAction(stringResource(R.string.setup_back), !busy,
                            modifier = Modifier.testTag("setup:back"), action = actions.back)
                        Spacer(Modifier.weight(1f))
                        TvAction(stringResource(when {
                            welcome -> R.string.setup_start
                            lastStep == SetupStep.Server -> R.string.setup_finish
                            else -> R.string.setup_next
                        }), !busy, primary = true, modifier = Modifier.focusRequester(primary).testTag("setup:next"), action = actions.next)
                    }
                }
            }
        }
        LaunchedEffect(visible, lastStep) {
            if (visible) { withFrameNanos { }; primary.requestFocus() }
        }
    }
}

@Composable private fun AppearanceCard(choices: SetupChoices, actions: SetupCallbacks, busy: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(182.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Background)
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceSecondary),
                contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.brand_mark), contentDescription = null,
                    colorFilter = ColorFilter.tint(Muted.copy(alpha = .5f)), modifier = Modifier.size(52.dp))
            }
            TvText(stringResource(R.string.setup_preview), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().background(Accent, CircleShape).padding(8.dp), contentAlignment = Alignment.Center) {
                TvText(stringResource(R.string.setup_preview_action), color = OnAccent, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TvText(stringResource(R.string.setup_appearance_body), color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvAction(stringResource(R.string.ui_theme_dark), !busy, selected = choices.theme == "dark",
                    modifier = Modifier.testTag("setup:theme:dark")) { actions.theme("dark") }
                TvAction(stringResource(R.string.ui_theme_light), !busy, selected = choices.theme == "light",
                    modifier = Modifier.testTag("setup:theme:light")) { actions.theme("light") }
            }
            TvText(stringResource(R.string.ui_accent_title), color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiAccent.colors.keys.forEach { key ->
                    val label = stringResource(when (key) {
                        "white" -> R.string.ui_accent_white; "orange" -> R.string.ui_accent_orange
                        "pink" -> R.string.ui_accent_pink; "blue" -> R.string.ui_accent_blue
                        else -> R.string.ui_accent_green
                    })
                    TvAction(label, !busy, selected = choices.accent == key,
                        modifier = Modifier.testTag("setup:accent:$key")) { actions.accent(key) }
                }
            }
        }
    }
}
