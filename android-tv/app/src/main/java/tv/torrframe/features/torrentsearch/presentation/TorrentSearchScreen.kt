package tv.torrframe.features.torrentsearch.presentation

import tv.torrframe.ui.sound.UiBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.sound.UiDialog as Dialog
import androidx.tv.material3.*
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*
import tv.torrserve.torrentsearch.model.*
import java.util.Locale

@Composable internal fun torrentText(en: String, ru: String) = if(LocalUiLanguage.current=="ru") ru else en
@Composable fun TorrentSearchScreen(state: TorrentSearchUi, vm: TorrentSearchViewModel, entryFocus: FocusRequester,
    back: () -> Unit) {
    val list = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var focusedKey by rememberSaveable(state.target) { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<TorrentResult?>(null) }
    var restoreFocus by remember { mutableStateOf(false) }
    var resultsHaveFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    fun dismiss() { selected=null; restoreFocus=true }
    BackHandler(onBack=back)
    Column(Modifier.fillMaxSize().testTag("torrentsearch:screen"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            TvBackAction(Modifier.focusRequester(entryFocus).focusProperties { if(state.snapshot.results.isNotEmpty()) down=listFocus }
                .testTag("torrentsearch:back"),back)
            Column(Modifier.weight(1f)) {
                TvText(if(state.source == TorrentSearchSource.Jackett) "Jackett" else "TorrentSearch",style=MaterialTheme.typography.headlineSmall,translate=false)
                TvText(state.target?.title.orEmpty(),color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,translate=false)
            }
            TvAction(torrentText("Retry","Повторить"),enabled=state.adding==null,
                modifier=Modifier.focusProperties { if(state.snapshot.results.isNotEmpty()) down=listFocus }.testTag("torrentsearch:retry"),action=vm::retry)
        }
        TvText(state.queries.joinToString(" / "),color=Muted,maxLines=2,translate=false,modifier=Modifier.testTag("torrentsearch:query"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TvText(torrentText("Seeds ↓","Сиды ↓"),color=Accent)
            TvText(if(state.searching) torrentText("Searching…","Поиск…") else torrentText("Results: ","Найдено: ")+state.snapshot.results.size,
                color=Muted,modifier=Modifier.testTag("torrentsearch:status"))
        }
        if(state.searching) {
            val finished=state.snapshot.sources.count { it.phase !in setOf(SourcePhase.Waiting,SourcePhase.Searching) }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Outline).testTag("torrentsearch:loading")) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((finished.toFloat()/state.snapshot.sources.size.coerceAtLeast(1)).coerceAtLeast(.03f)).background(Accent))
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            state.snapshot.sources.forEach { source ->
                val suffix=when(source.phase) {
                    SourcePhase.Complete -> "✓"
                    SourcePhase.Failed -> torrentText("unavailable","недоступен")
                    SourcePhase.TimedOut -> torrentText("timeout","тайм-аут")
                    SourcePhase.Waiting -> "·"
                    SourcePhase.Searching -> "…"
                }
                TvText("${source.name}: $suffix",style=MaterialTheme.typography.labelLarge,color=Muted,translate=false)
            }
        }
        val failed=state.snapshot.sources.count { it.phase in setOf(SourcePhase.Failed,SourcePhase.TimedOut) }
        if(failed>0 && state.snapshot.results.isNotEmpty()) TvText(torrentText("Some sources are unavailable; other results are shown.","Часть источников недоступна. Показаны результаты остальных."),color=Muted)
        if(state.adding!=null) TvText(torrentText("Adding / loading video files…","Добавление / загрузка списка видео…"),color=Accent,modifier=Modifier.testTag("torrentsearch:adding"))
        if(state.actionError!=null) TvText(actionError(state.actionError),color=Muted,modifier=Modifier.testTag("torrentsearch:action-error"))
        if(!state.searching && state.snapshot.results.isEmpty()) TvText(
            if(state.snapshot.sources.isEmpty() && !state.searchError) torrentText("No enabled sources support this title. Choose sources in TorrentSearch settings.","Нет включённых источников для этого типа. Выберите их в настройках TorrentSearch.")
            else if(state.searchError || (failed>0 && failed==state.snapshot.sources.size)) torrentText("Sources are unavailable. Check the connection and retry.","Источники недоступны. Проверьте подключение и повторите поиск.")
            else torrentText("No torrents found with these settings. Change filters or retry later.","С этими настройками торренты не найдены. Измените фильтры или повторите позже."),color=Muted,modifier=Modifier.testTag("torrentsearch:empty"))
        LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth().focusRequester(listFocus).onFocusChanged { resultsHaveFocus=it.hasFocus }.focusGroup().testTag("torrentsearch:results"),
            contentPadding=PaddingValues(6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.snapshot.results,key={ _,row -> row.key }) { index,row ->
                TorrentRow(row,Modifier.fillMaxWidth()
                    .then(if(row.key==focusedKey)Modifier.focusRequester(rowFocus) else Modifier)
                    .focusProperties { if(index==0)up=entryFocus }
                    .onFocusChanged { if(it.isFocused)focusedKey=row.key }) { selected=row }
            }
        }
    }
    LaunchedEffect(state.target) { withFrameNanos { }; entryFocus.requestFocus() }
    // Preserve the selected identity when a late source inserts higher-seeded torrents above it.
    LaunchedEffect(state.snapshot.results.map { it.key }) {
        val index=state.snapshot.results.indexOfFirst { it.key==focusedKey }
        if(index>=0 && resultsHaveFocus && windowFocused && selected==null && state.files.isEmpty()) {
            list.scrollToItem(index);withFrameNanos { };runCatching { rowFocus.requestFocus() }
        }
    }
    LaunchedEffect(restoreFocus,windowFocused) {
        if(restoreFocus && windowFocused) {
            val index=state.snapshot.results.indexOfFirst { it.key==focusedKey }
            if(index>=0)list.scrollToItem(index)
            withFrameNanos { };withFrameNanos { }
            runCatching { rowFocus.requestFocus() }.onFailure { entryFocus.requestFocus() }
            restoreFocus=false
        }
    }
    LaunchedEffect(state.adding,state.added,state.files,state.play) {
        if(selected!=null && state.adding==null && state.actionError==null && selected!!.key in state.added) dismiss()
    }
    selected?.let { row -> ActionMenu(row,state,vm,::dismiss) }
    if(state.files.isNotEmpty()) VideoFilesMenu(state,vm) { vm.closeFiles();restoreFocus=true }
}

@Composable private fun actionError(error: TorrentActionError) = when(error) {
    TorrentActionError.Add -> torrentText("Could not add the torrent. Check TorrServer and the source, or select another result.","Не удалось добавить торрент. Проверьте TorrServer и источник или выберите другую раздачу.")
    TorrentActionError.Files -> torrentText("Torrent added, but video files are unavailable. Try later from the library.","Торрент добавлен, но список видео недоступен. Попробуйте позже из медиатеки.")
}
private fun size(row: TorrentResult): String {
    row.sizeBytes?.let { value ->
        var n=value.toDouble();var unit=0;val units=listOf("B","KiB","MiB","GiB","TiB")
        while(n>=1024 && unit<units.lastIndex){n/=1024;unit++}
        return String.format(Locale.ROOT,"%.1f %s",n,units[unit])
    }
    return row.sizeText?.takeIf { it.isNotBlank() } ?: "—"
}
@Composable private fun TorrentRow(row: TorrentResult,modifier: Modifier,select: () -> Unit) {
    val shape=RoundedCornerShape(12.dp);val motion=rememberTvButtonMotion(shape=shape)
    Button(onClick={motion.activate();select()},modifier=modifier.then(motion.modifier).then(motion.pointerClick(action=select))
        .testTag("torrentsearch:row:${row.key}"),shape=ButtonDefaults.shape(shape),contentPadding=PaddingValues(12.dp),
        colors=motion.colors,border=motion.border,interactionSource=motion.interactions,
        scale=ButtonDefaults.scale(focusedScale=1f,pressedScale=1f)) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            TvText(row.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis,translate=false)
            TvText(torrentText("Seeds: ","Сиды: ")+(row.seeds?.toString() ?: "—")+"   "+torrentText("Peers: ","Пиры: ")+(row.peers?.toString() ?: "—")+
                "   ·   "+size(row)+"   ·   "+row.trackers.sorted().joinToString(" / "),style=MaterialTheme.typography.labelLarge,translate=false)
            if(row.quality.isNotBlank())TvText(row.quality,style=MaterialTheme.typography.labelLarge,translate=false)
        }
    }
}
@Composable private fun ActionMenu(row: TorrentResult,state: TorrentSearchUi,vm: TorrentSearchViewModel,dismiss: () -> Unit) {
    val first=remember { FocusRequester() };val back=remember { FocusRequester() }
    Dialog(onDismissRequest=dismiss) {
        Column(Modifier.width(440.dp).background(Surface,RoundedCornerShape(20.dp)).border(1.dp,Outline,RoundedCornerShape(20.dp))
            .padding(20.dp).testTag("torrentsearch:actions"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TvText(row.title,style=MaterialTheme.typography.titleMedium,maxLines=2,translate=false)
            if(state.actionError!=null)TvText(actionError(state.actionError),color=Muted)
            if(state.adding!=null)TvText(torrentText("Adding…","Добавление…"),color=Muted)
            TvAction(if(row.key in state.added)torrentText("Added to TorrServer","Добавлено в TorrServer") else torrentText("Add to TorrServer","Добавить в TorrServer"),
                enabled=state.adding==null && row.key !in state.added,modifier=Modifier.fillMaxWidth().focusRequester(first).testTag("torrentsearch:add")) { vm.act(row,false) }
            TvAction(torrentText("Watch","Смотреть"),enabled=state.adding==null,modifier=Modifier.fillMaxWidth().testTag("torrentsearch:watch")) { vm.act(row,true) }
            TvBackAction(Modifier.focusRequester(back),dismiss)
        }
        LaunchedEffect(state.adding) { withFrameNanos { };if(state.adding!=null || row.key in state.added)back.requestFocus() else first.requestFocus() }
    }
}
@Composable private fun VideoFilesMenu(state: TorrentSearchUi,vm: TorrentSearchViewModel,dismiss: () -> Unit) {
    val first=remember { FocusRequester() }
    Dialog(onDismissRequest=dismiss) {
        Column(Modifier.width(560.dp).heightIn(max=400.dp).background(Surface,RoundedCornerShape(20.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TvText(torrentText("Choose a video","Выберите видео"),style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.files,key={_,file -> file.id}) { index,file ->
                    TvAction(file.path,modifier=Modifier.fillMaxWidth().then(if(index==0)Modifier.focusRequester(first)else Modifier)
                        .testTag("torrentsearch:file:${file.id}")) { vm.chooseFile(file) }
                }
            }
            TvBackAction(action=dismiss)
        }
        LaunchedEffect(Unit) { withFrameNanos { };first.requestFocus() }
    }
}
