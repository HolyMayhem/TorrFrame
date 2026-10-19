package tv.torrserve.silicon.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import tv.torrserve.silicon.R
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import tv.torrserve.silicon.ui.theme.*
import tv.torrserve.silicon.core.models.LibraryItem

@Composable fun TvAction(label: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    primary: Boolean = false, selected: Boolean = false, translate: Boolean = true, action: () -> Unit) {
    val shape = RoundedCornerShape(TvDimensions.buttonRadius)
    val motion = rememberTvButtonMotion(enabled,
        container = if(primary) Accent else if(selected) SurfaceSecondary else Surface,
        content = if(primary) OnAccent else TextPrimary,
        outline = if(primary || selected) Accent.copy(alpha = .6f) else Outline, shape = shape)
    Button(onClick = { motion.activate(); action() }, enabled = enabled, modifier = modifier.heightIn(min = TvDimensions.actionHeight).then(motion.modifier).then(motion.pointerClick(enabled, action = action)),
        shape = ButtonDefaults.shape(shape),
        interactionSource = motion.interactions, colors = motion.colors, border = motion.border,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        TvText(label, modifier = motion.contentModifier, style = MaterialTheme.typography.labelLarge, translate = translate)
    }
}
@Composable fun TvIconAction(@androidx.annotation.DrawableRes icon: Int, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) {
    val shape = CircleShape
    val motion = rememberTvButtonMotion(enabled,
        container = if (primary) Accent else Surface, content = if (primary) OnAccent else TextPrimary,
        outline = Color.Transparent, shape = shape, focusedContainer = SurfaceSecondary,
        focusedContent = TextPrimary, focusedOutline = TextPrimary.copy(alpha = .8f))
    Button(onClick = { motion.activate(); action() }, enabled = enabled,
        modifier = modifier.size(TvDimensions.actionHeight).then(motion.modifier).then(motion.pointerClick(enabled, action = action)),
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(0.dp),
        interactionSource = motion.interactions, colors = motion.colors, border = motion.border,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), contentDescription = localize(label),
                colorFilter = ColorFilter.tint(LocalContentColor.current), modifier = Modifier.size(20.dp).then(motion.contentModifier))
        }
    }
}
@Composable fun TvNavItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier, displayLabel: String = label, action: () -> Unit) {
    TvQuietAction(displayLabel, modifier = modifier.testTag("nav:$label").semantics { selected = isSelected },
        selected = isSelected, underline = true, style = MaterialTheme.typography.titleMedium, action = action)
}
@Composable fun TvBackAction(modifier: Modifier = Modifier, action: () -> Unit) {
    val shape = CircleShape
    val motion = rememberTvButtonMotion(shape = shape)
    Button(onClick = { motion.activate(); action() }, modifier = modifier.size(TvDimensions.actionHeight)
        .then(motion.modifier).then(motion.pointerClick(action = action)),
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(0.dp),
        interactionSource = motion.interactions, colors = motion.colors, border = motion.border,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_arrow_back), contentDescription = if (LocalUiLanguage.current == "ru") "Назад" else "Back",
                colorFilter = ColorFilter.tint(LocalContentColor.current),
                modifier = Modifier.size(20.dp).then(motion.contentModifier).testTag("back:icon"))
        }
    }
}
@Composable fun SettingsShortcut(modifier: Modifier = Modifier, isOpen: Boolean = false, open: () -> Unit) {
    TvIconAction(R.drawable.ic_settings, stringResource(R.string.settings_title),
        modifier = modifier.testTag("nav:Settings").semantics { selected = isOpen }, action = open)
}
@Composable fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = modifier.size(24.dp))
}
@Composable fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText(title, style = MaterialTheme.typography.headlineMedium)
        if(subtitle != null) TvText(subtitle, color = Muted)
    }
}
@Composable fun InfoPanel(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.background(Surface, RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(LocalTvLayout.current.panelPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvText(title, style = MaterialTheme.typography.headlineSmall)
        TvText(text, color = Muted)
        action()
    }
}
@Composable fun EditField(label: String, value: String, secret: Boolean = false, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text, russian: Boolean = false, onValue: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier.testTag("field-group:$label"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText(label, color = Muted, fontSize = 14.sp)
        TvAction(if (value.isBlank()) { stringResource(R.string.field_enter, localize(label)) } else if(secret) "••••••••" else value,
            modifier = Modifier.fillMaxWidth().testTag("field:$label"), translate = value.isBlank()) { editing = true }
    }
    if (editing) TextEditor(label, value, secret, keyboardType, { editing = false }, russian) { onValue(it); editing = false }
}
@Composable fun TextEditor(title: String, initial: String, secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, dismiss: () -> Unit, russian: Boolean = false,
    onEdit: ((String) -> Unit)? = null, save: (String) -> Unit) {
    val field = rememberTextFieldState(initialText = initial.take(4096))
    val focus = remember { FocusRequester() }; val doneFocus = remember { FocusRequester() }
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    val currentOnEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { currentOnEdit?.invoke(it) } }
    Dialog(onDismissRequest = dismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(.8f).background(Surface, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(title, style = MaterialTheme.typography.headlineSmall)
            val fieldModifier = Modifier.fillMaxWidth().border(1.dp, Muted, RoundedCornerShape(12.dp)).padding(12.dp)
                .focusRequester(focus).testTag("dialog:editor").onPreviewKeyEvent { event ->
                    if(event.key == Key.DirectionDown) {
                        if(event.type == KeyEventType.KeyDown) { keyboard?.hide(); doneFocus.requestFocus() }
                        true
                    } else false
                }
            val options = KeyboardOptions(keyboardType = if(secret) KeyboardType.Password else keyboardType,
                autoCorrectEnabled = false, imeAction = ImeAction.Done, showKeyboardOnFocus = !hardwareKeyboard)
            val finishEditing = KeyboardActionHandler { keyboard?.hide(); doneFocus.requestFocus() }
            if(secret) BasicSecureTextField(state = field, modifier = fieldModifier,
                inputTransformation = InputTransformation.maxLength(4096),
                keyboardOptions = options, onKeyboardAction = finishEditing,
                textObfuscationMode = TextObfuscationMode.Hidden,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp), cursorBrush = SolidColor(FocusBorderColor))
            else BasicTextField(state = field, modifier = fieldModifier,
                inputTransformation = InputTransformation.maxLength(4096), lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = options, onKeyboardAction = finishEditing,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp), cursorBrush = SolidColor(FocusBorderColor))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.dialog_done), modifier = Modifier.focusRequester(doneFocus).testTag("dialog:done"), primary = true) { keyboard?.hide(); save(field.text.toString()) }
                TvAction(stringResource(R.string.dialog_cancel), modifier = Modifier.testTag("dialog:cancel")) { keyboard?.hide(); dismiss() }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus(); if(!hardwareKeyboard) keyboard?.show() }
    }
}
@Composable fun TvToggle(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val motion = rememberTvButtonMotion(container = SurfaceSecondary, outline = Color.Transparent)
    var focused by remember { mutableStateOf(false) }
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 0.dp, tween(if (LocalAnimationsEnabled.current) 180 else 0), label = "switchThumb")
    val trackColor by animateColorAsState(if (checked) Accent else Muted, tween(if (LocalAnimationsEnabled.current) 180 else 0), label = "switchTrack")
    // White is also the focused row background; separate both the track and thumb from it.
    val whiteChecked = Accent == Color.White && checked
    val thumbColor by animateColorAsState(if (whiteChecked) OnAccent else Color.White,
        tween(if (LocalAnimationsEnabled.current) 180 else 0), label = "switchThumbColor")
    val trackOutline by animateColorAsState(when {
        whiteChecked -> OnAccent.copy(alpha = .55f)
        checked && focused -> OnAccent.copy(alpha = .85f)
        else -> Color.Transparent
    },
        tween(if (LocalAnimationsEnabled.current) 180 else 0), label = "switchOutline")
    Button(onClick = { motion.activate(); onChange(!checked) }, modifier = modifier.onFocusChanged { focused = it.isFocused }
        .then(motion.modifier).then(motion.pointerClick { onChange(!checked) }).fillMaxWidth().heightIn(min = TvDimensions.actionHeight)
        .semantics { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
        shape = ButtonDefaults.shape(RoundedCornerShape(TvDimensions.buttonRadius)),
        interactionSource = motion.interactions, colors = motion.colors, border = motion.border,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(label, Modifier.weight(1f).wrapContentWidth(Alignment.Start).then(motion.contentModifier), style = MaterialTheme.typography.titleMedium)
            TvText(stringResource(if (checked) R.string.toggle_on else R.string.toggle_off))
            Box(Modifier.width(42.dp).height(24.dp).background(trackColor, RoundedCornerShape(12.dp))
                .border(1.dp, trackOutline, RoundedCornerShape(12.dp)).testTag("toggle:track").padding(3.dp),
                contentAlignment = Alignment.CenterStart) {
                Box(Modifier.offset(x = thumbOffset).size(18.dp).background(thumbColor, RoundedCornerShape(9.dp)))
            }
        }
    }
}
@Composable fun ChoiceRow(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.forEach { value -> TvAction(value, selected = value == selected) { onSelect(value) } }
    }
}
@Composable fun Poster(item: LibraryItem, modifier: Modifier = Modifier, focused: () -> Unit = {}, open: () -> Unit) {
    var imageFailed by remember(item.metadata?.poster) { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDimensions.cardRadius)
    var hasFocus by remember { mutableStateOf(false) }
    Card(onClick = open, modifier = modifier.onFocusChanged { hasFocus = it.isFocused; if(it.isFocused) focused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, contentColor = TextPrimary,
            focusedContainerColor = Color.Transparent, focusedContentColor = TextPrimary),
        border = CardDefaults.border(),
        scale = CardDefaults.scale(focusedScale = if (LocalAnimationsEnabled.current) 1.03f else 1f, pressedScale = 1f)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(SurfaceSecondary, shape).clip(shape)
                .border(2.dp, if (hasFocus) TextPrimary else Color.Transparent, shape), contentAlignment = Alignment.Center) {
                if (item.metadata?.poster?.isNotBlank() == true && !imageFailed) AsyncImage(item.metadata.poster,
                    contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { imageFailed = true })
                else Text(item.title.take(1).uppercase(), fontSize = 56.sp, color = Muted)
            }
            Column(Modifier.padding(horizontal = 2.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(listOfNotNull(item.metadata?.year?.toString(), item.metadata?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "), color = Muted, fontSize = 13.sp)
                if (item.inProgress && item.duration > 0) Box(Modifier.fillMaxWidth().height(3.dp).background(Muted.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxWidth((item.position.toFloat() / item.duration).coerceIn(0f,1f)).height(3.dp).background(Color.White))
                }
            }
        }
    }
}
@Composable fun Message(value: String) { if(value.isNotBlank()) TvText(value, color = Muted, modifier = Modifier.padding(vertical = 8.dp)) }
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, confirmLabel: String = "Remove", russian: Boolean = false, confirm: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(480.dp).background(Surface, RoundedCornerShape(24.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TvText(title, style = MaterialTheme.typography.headlineSmall); TvText(text, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.dialog_cancel), modifier = Modifier.focusRequester(focus), action = dismiss)
                TvAction(confirmLabel, action = confirm)
            }
        }; LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
