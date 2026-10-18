package tv.torrserve.silicon.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.Muted
import tv.torrserve.torrentsearch.model.*
import java.util.Locale

@Composable private fun text(en:String,ru:String)=if(LocalUiLanguage.current=="ru")ru else en

internal fun LazyListScope.torrentSearchSettingsItems(s:TorrentSearchOptions, providers:List<Pair<String,String>>,
    change:((TorrentSearchOptions)->TorrentSearchOptions)->Unit) {
    item("ts:hint") { TvText(text("Saved automatically. Applied when starting or repeating a search.",
        "Сохраняется автоматически. Применяется при запуске или повторе поиска."),color=Muted) }
    item("ts:sources") {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TvText(text("Sources","Источники"),style=MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TvAction(text("Enable all","Включить все"),modifier=Modifier.testTag("ts:all")) { change { it.copy(enabledProviders=null) } }
                TvAction(text("Disable all","Отключить все"),modifier=Modifier.testTag("ts:none")) { change { it.copy(enabledProviders=emptySet()) } }
            }
        }
    }
    providers.forEach { (id,name) -> item("ts:source:$id") {
        val label=name+when(id){"ytsmx"->text(" · movies"," · фильмы");"eztvx"->text(" · series"," · сериалы");else->""}
        TvToggle(label,s.enabledProviders?.contains(id)!=false,Modifier.testTag("ts:source:$id")) { enabled ->
            change { current ->
                val sources=current.enabledProviders ?: providers.map { it.first }.toSet()
                current.copy(enabledProviders=if(enabled)sources+id else sources-id)
            }
        }
    } }
    item("ts:resolution") {
        SettingsGroup(text("Video resolution","Разрешение видео")) {
            MultiChoices(TorrentSearchOptions.RESOLUTIONS,s.resolutions,"resolution") { values -> change { it.copy(resolutions=values) } }
        }
    }
    item("ts:format") {
        SettingsGroup(text("Release format","Формат раздачи")) {
            MultiChoices(TorrentSearchOptions.FORMATS,s.formats,"format") { values -> change { it.copy(formats=values) } }
        }
    }
    val step=TorrentSearchOptions.GIB/2
    item("ts:min-size") {
        Counter(text("Minimum size","Минимальный размер"),gib(s.minBytes),"min-size",s.minBytes>0,
            s.minBytes<TorrentSearchOptions.MAX_BYTES && (s.maxBytes==0L || s.minBytes+step<=s.maxBytes),
            minus={change { it.copy(minBytes=(it.minBytes-step).coerceAtLeast(0)) }},
            plus={change { it.copy(minBytes=it.minBytes+step) }})
    }
    item("ts:max-size") {
        Counter(text("Maximum size","Максимальный размер"),if(s.maxBytes==0L)text("Unlimited","Без ограничения")else gib(s.maxBytes),"max-size",
            s.maxBytes>0,s.maxBytes<TorrentSearchOptions.MAX_BYTES,
            minus={change { it.copy(maxBytes=if(it.maxBytes<=it.minBytes+step)0 else it.maxBytes-step) }},
            plus={change { it.copy(maxBytes=if(it.maxBytes==0L)maxOf(step,it.minBytes)else it.maxBytes+step) }})
    }
    item("ts:seeds") {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Counter(text("Minimum seeds","Минимум сидов"),s.minSeeds.toString(),"seeds",s.minSeeds>0,s.minSeeds<10_000,
                minus={change { it.copy(minSeeds=(it.minSeeds-1).coerceAtLeast(0)) }},plus={change { it.copy(minSeeds=it.minSeeds+1) }})
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(0L,5L,10L,20L,50L,100L).forEach { count -> TvAction(count.toString(),selected=s.minSeeds==count,
                    modifier=Modifier.testTag("ts:seeds:$count")) { change { it.copy(minSeeds=count) } } }
            }
        }
    }
    item("ts:zero") {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TvToggle(text("Hide torrents with zero seeds","Скрывать раздачи с 0 сидов"),s.hideZeroSeeds,Modifier.testTag("ts:zero")) { value ->change { it.copy(hideZeroSeeds=value) } }
            TvText(text("Unknown seeds stay last; a positive minimum excludes unknown counts. Active size/quality filters exclude unknown values.",
                "Неизвестные сиды идут в конце; минимум больше 0 скрывает неизвестное число. При фильтре размера или качества раздачи без этих данных исключаются."),color=Muted)
        }
    }
    item("ts:timeout") {
        Counter(text("Source timeout","Ожидание источника"),"${s.sourceTimeoutMs/1000} "+text("s","с"),"timeout",s.sourceTimeoutMs>5_000,s.sourceTimeoutMs<60_000,
            minus={change { it.copy(sourceTimeoutMs=it.sourceTimeoutMs-1_000) }},plus={change { it.copy(sourceTimeoutMs=it.sourceTimeoutMs+1_000) }})
    }
    item("ts:title") {
        SettingsGroup(text("Search title","Название для поиска")) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SearchTitleMode.entries.forEach { mode ->
                    val label=when(mode){SearchTitleMode.Original->text("Original","Оригинальное");SearchTitleMode.Russian->text("Russian","Русское");SearchTitleMode.Both->text("Both","Оба")}
                    TvAction(label,selected=s.titleMode==mode,modifier=Modifier.testTag("ts:title:${mode.name}").semantics { selected=s.titleMode==mode }) { change { it.copy(titleMode=mode) } }
                }
            }
            TvText(text("Russian titles use TMDB. If unavailable, the available title is used. Year is included; series also search without the year.",
                "Русское название берётся из TMDB. Если его нет, используется доступное. Учитывается год; сериалы также ищутся без года."),color=Muted)
        }
    }
    item("ts:reset") { TvAction(text("Restore TorrentSearch defaults","Сбросить настройки TorrentSearch"),modifier=Modifier.testTag("ts:reset")) { change { TorrentSearchOptions() } } }
}

@Composable private fun MultiChoices(values:List<String>,selected:Set<String>,tag:String,change:(Set<String>)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TvAction(text("Any","Любое"),selected=selected.isEmpty(),modifier=Modifier.testTag("ts:$tag:any").semantics { this.selected=selected.isEmpty() }) { change(emptySet()) }
        values.forEach { value -> TvAction(value,selected=value in selected,modifier=Modifier.testTag("ts:$tag:$value").semantics { this.selected=value in selected }) {
            change(if(value in selected)selected-value else selected+value)
        } }
    }
}
@Composable private fun gib(bytes:Long)=String.format(Locale.ROOT,"%.1f",bytes.toDouble()/TorrentSearchOptions.GIB)+text(" GiB"," ГиБ")
@Composable private fun Counter(label:String,value:String,tag:String,less:Boolean,more:Boolean,minus:()->Unit,plus:()->Unit) {
    val minusFocus=remember { FocusRequester() };val plusFocus=remember { FocusRequester() }
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(value,less,more) {
        if(pending==false && !less && more) { withFrameNanos { };plusFocus.requestFocus() }
        else if(pending==true && !more && less) { withFrameNanos { };minusFocus.requestFocus() }
        pending=null
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TvText(label,color=Muted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            TvAction("−",enabled=less,modifier=Modifier.focusRequester(minusFocus).testTag("ts:$tag:minus")) { pending=false;minus() }
            TvText(value,modifier=Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally).testTag("ts:$tag:value"),translate=false)
            TvAction("+",enabled=more,modifier=Modifier.focusRequester(plusFocus).testTag("ts:$tag:plus")) { pending=true;plus() }
        }
    }
}
