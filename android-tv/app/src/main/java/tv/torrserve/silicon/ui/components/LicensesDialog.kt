package tv.torrserve.silicon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import tv.torrserve.silicon.ui.theme.Surface

@Composable fun LicensesDialog(dismiss: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val scroll = rememberLazyListState()
    var source by remember { mutableStateOf("TorrServer-GPL-3.0.txt") }; var paragraphs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(source) { paragraphs = withContext(Dispatchers.IO) {
        context.assets.open("licenses/$source").bufferedReader().use { it.readText().split("\n\n") }
    } }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(640.dp).height(430.dp).background(Surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoiceRow(listOf("TorrServer-GPL-3.0.txt", "Apache-2.0.txt"),source) { source = it }
            TvText("TorrServer by YouROK. Kotlin, AndroidX, OkHttp and Coil use Apache-2.0.", fontSize = 14.sp, translate = false)
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).focusable().onPreviewKeyEvent { event ->
                if(event.type == KeyEventType.KeyDown && (event.key == Key.DirectionDown || event.key == Key.DirectionUp)) {
                    val down = event.key == Key.DirectionDown
                    if((down && scroll.canScrollForward) || (!down && scroll.canScrollBackward)) {
                        scope.launch { scroll.animateScrollToItem((scroll.firstVisibleItemIndex + if(down) 1 else -1).coerceAtLeast(0)) }; true
                    } else false
                } else false
            }) { items(paragraphs) { TvText(it, fontSize = 14.sp, translate = false, modifier = Modifier.padding(bottom = 12.dp)) } }
            TvAction("Back", action = dismiss)
        }
    }
}
