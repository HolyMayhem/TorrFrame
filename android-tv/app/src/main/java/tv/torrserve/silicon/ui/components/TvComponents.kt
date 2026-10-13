package tv.torrserve.silicon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
    primary: Boolean = false, selected: Boolean = false, action: () -> Unit) {
    val shape = RoundedCornerShape(TvDimensions.buttonRadius)
    Button(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = TvDimensions.actionHeight),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if(primary) Accent else if(selected) SurfaceSecondary else Surface,
            contentColor = if(primary) OnAccent else TextPrimary,
            focusedContainerColor = Accent, focusedContentColor = OnAccent,
            disabledContainerColor = Surface, disabledContentColor = Muted.copy(alpha = .55f)),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, if(primary || selected) Accent.copy(alpha = .6f) else Outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorderColor), shape = shape)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f)) {
        TvText(label, style = MaterialTheme.typography.labelLarge)
    }
}
@Composable fun TvNavItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    TvAction(label, modifier = modifier.testTag("nav:$label").semantics { selected = isSelected }, selected = isSelected, action = action)
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
        TvAction(if (value.isBlank()) { if (russian) stringResource(R.string.field_enter, label) else "Enter $label" } else if(secret) "••••••••" else value,
            modifier = Modifier.fillMaxWidth().testTag("field:$label")) { editing = true }
    }
    if (editing) TextEditor(label, value, secret, keyboardType, { editing = false }, russian) { onValue(it); editing = false }
}
@Composable fun TextEditor(title: String, initial: String, secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, dismiss: () -> Unit, russian: Boolean = false, save: (String) -> Unit) {
    val field = rememberTextFieldState(initialText = initial.take(4096))
    val focus = remember { FocusRequester() }; val doneFocus = remember { FocusRequester() }
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
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
                TvAction(if (russian) stringResource(R.string.dialog_done) else "Done", modifier = Modifier.focusRequester(doneFocus).testTag("dialog:done"), primary = true) { keyboard?.hide(); save(field.text.toString()) }
                TvAction(if (russian) stringResource(R.string.dialog_cancel) else "Cancel", modifier = Modifier.testTag("dialog:cancel")) { keyboard?.hide(); dismiss() }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus(); if(!hardwareKeyboard) keyboard?.show() }
    }
}
@Composable fun TvToggle(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Button(onClick = { onChange(!checked) }, modifier = modifier.fillMaxWidth().heightIn(min = TvDimensions.actionHeight)
        .semantics { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
        shape = ButtonDefaults.shape(RoundedCornerShape(TvDimensions.buttonRadius)),
        colors = ButtonDefaults.colors(containerColor = SurfaceSecondary, contentColor = TextPrimary,
            focusedContainerColor = Accent, focusedContentColor = OnAccent),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, FocusBorderColor))),
        scale = ButtonDefaults.scale(focusedScale = 1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TvText(stringResource(if (checked) R.string.toggle_on else R.string.toggle_off))
            Box(Modifier.width(42.dp).height(24.dp).background(if (checked) Accent else Muted, RoundedCornerShape(12.dp)).padding(3.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(18.dp).background(Color.White, RoundedCornerShape(9.dp)))
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
    Card(onClick = open, modifier = modifier.onFocusChanged { if(it.isFocused) focused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Surface, contentColor = TextPrimary,
            focusedContainerColor = SurfaceSecondary, focusedContentColor = TextPrimary),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, FocusBorderColor), inset = (-3).dp, shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.05f)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(SurfaceSecondary), contentAlignment = Alignment.Center) {
                if (item.metadata?.poster?.isNotBlank() == true && !imageFailed) AsyncImage(item.metadata.poster,
                    contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { imageFailed = true })
                else Text(item.title.take(1).uppercase(), fontSize = 56.sp, color = Muted)
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
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
                TvAction(if (russian) stringResource(R.string.dialog_cancel) else "Cancel", modifier = Modifier.focusRequester(focus), action = dismiss)
                TvAction(confirmLabel, action = confirm)
            }
        }; LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
