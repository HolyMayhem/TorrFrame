package tv.torrserve.silicon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import tv.torrserve.silicon.ui.theme.*
import tv.torrserve.silicon.core.models.LibraryItem

@Composable fun TvAction(label: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    primary: Boolean = false, selected: Boolean = false, action: () -> Unit) {
    val shape = RoundedCornerShape(TvDimensions.buttonRadius)
    Button(onClick = action, enabled = enabled, modifier = modifier,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if(primary) Accent else if(selected) SurfaceSecondary else Surface,
            contentColor = if(primary) Background else TextPrimary,
            focusedContainerColor = TextPrimary, focusedContentColor = Background,
            disabledContainerColor = Surface, disabledContentColor = Muted.copy(alpha = .55f)),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, if(primary || selected) Accent.copy(alpha = .6f) else Outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, TextPrimary), inset = (-3).dp, shape = shape)),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.04f)) {
        TvText(label, style = MaterialTheme.typography.labelLarge)
    }
}
@Composable fun TvNavItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    TvAction(label, modifier = modifier.testTag("nav:$label").semantics { selected = isSelected }, selected = isSelected, action = action)
}
@Composable fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val inset = 5.dp.toPx()
        drawRoundRect(Accent, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = stroke)
        val y = size.height * .5f
        drawLine(Accent, androidx.compose.ui.geometry.Offset(size.width * .32f, y),
            androidx.compose.ui.geometry.Offset(size.width * .68f, y), 2.dp.toPx())
        drawCircle(Positive, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * .67f, size.height * .68f))
    }
}
@Composable fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText(title, style = MaterialTheme.typography.headlineMedium)
        if(subtitle != null) TvText(subtitle, color = Muted)
    }
}
@Composable fun InfoPanel(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.background(Surface, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvText(title, style = MaterialTheme.typography.headlineSmall)
        TvText(text, color = Muted)
        action()
    }
}
@Composable fun EditField(label: String, value: String, secret: Boolean = false, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text, onValue: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvText(label, color = Muted, fontSize = 14.sp)
        TvAction(if (value.isBlank()) "Enter $label" else if(secret) "••••••••" else value,
            modifier = Modifier.fillMaxWidth()) { editing = true }
    }
    if (editing) TextEditor(label, value, secret, keyboardType, { editing = false }) { onValue(it); editing = false }
}
@Composable fun TextEditor(title: String, initial: String, secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, dismiss: () -> Unit, save: (String) -> Unit) {
    val field = rememberTextFieldState(initialText = initial.take(4096))
    val focus = remember { FocusRequester() }; val doneFocus = remember { FocusRequester() }
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    Dialog(onDismissRequest = dismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.width(560.dp).background(Surface, RoundedCornerShape(24.dp)).border(1.dp, Outline, RoundedCornerShape(24.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TvText(title, style = MaterialTheme.typography.headlineSmall)
            val fieldModifier = Modifier.fillMaxWidth().border(1.dp, Muted, RoundedCornerShape(12.dp)).padding(16.dp)
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
                textStyle = TextStyle(color = TextPrimary, fontSize = 20.sp), cursorBrush = SolidColor(Accent))
            else BasicTextField(state = field, modifier = fieldModifier,
                inputTransformation = InputTransformation.maxLength(4096), lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = options, onKeyboardAction = finishEditing,
                textStyle = TextStyle(color = TextPrimary, fontSize = 20.sp), cursorBrush = SolidColor(Accent))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction("Done", modifier = Modifier.focusRequester(doneFocus).testTag("dialog:done"), primary = true) { keyboard?.hide(); save(field.text.toString()) }
                TvAction("Cancel", modifier = Modifier.testTag("dialog:cancel")) { keyboard?.hide(); dismiss() }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus(); if(!hardwareKeyboard) keyboard?.show() }
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
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Accent), inset = (-3).dp, shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.05f)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color(0xFF25262B)), contentAlignment = Alignment.Center) {
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
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(480.dp).background(Surface, RoundedCornerShape(24.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TvText(title, style = MaterialTheme.typography.headlineSmall); TvText(text, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction("Cancel", modifier = Modifier.focusRequester(focus), action = dismiss)
                TvAction("Remove", action = confirm)
            }
        }; LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
