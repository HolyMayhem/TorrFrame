package tv.torrframe.features.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import tv.torrframe.R
import tv.torrframe.core.models.LibraryItem
import tv.torrframe.core.models.TorrentFile
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*
import java.util.Locale

/** Presentation only: file loading, playback and dialog state belong to MovieDetailScreen. */
@Composable internal fun MovieDetailHeader(item: LibraryItem, minHeight: Dp,
    backFocus: FocusRequester, watchFocus: FocusRequester, back: () -> Unit,
    actions: @Composable () -> Unit) {
    val backdrop = item.metadata?.backdrop.orEmpty()
    var failed by remember(item.hash, backdrop) { mutableStateOf(false) }
    var loaded by remember(item.hash, backdrop) { mutableStateOf(false) }
    val cinematic = backdrop.isNotBlank() && !failed
    val background = Background
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background)
        .testTag("movie:hero")) {
        if (cinematic) {
            AsyncImage(backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().testTag(if (loaded) "movie:backdrop:loaded" else "movie:backdrop:loading"),
                onSuccess = { loaded = true }, onError = { failed = true })
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to background, .28f to background.copy(alpha = .97f),
                .62f to background.copy(alpha = .65f), 1f to background.copy(alpha = .08f))))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to background.copy(alpha = .1f), .5f to background.copy(alpha = .2f), 1f to background)))
        }
        // Keep Back, the information column and its actions in the same composition
        // when an image fails, so a remote focus target is never replaced.
        val titleSize = (maxWidth.value * .042f).coerceIn(30f, 44f).sp
        Row(Modifier.fillMaxWidth().heightIn(min = if (cinematic) minHeight else 0.dp)
            .padding(start = 20.dp, end = 20.dp, top = 76.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            if (!cinematic) MovieCover(item, Modifier.width(if (LocalTvLayout.current.short) 140.dp else 166.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(if (cinematic) .72f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvText(stringResource(R.string.library_in_library), color = Muted,
                        style = MaterialTheme.typography.labelLarge, translate = false)
                    TvText(item.title, style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize,
                        lineHeight = titleSize * 1.12f), modifier = Modifier.testTag("movie:title"), translate = false)
                    item.metadata?.originalTitle?.takeIf { it.isNotBlank() && it != item.title }?.let {
                        TvText(it, color = Muted, translate = false)
                    }
                    val facts = listOfNotNull(item.metadata?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(Locale.US, it) },
                        item.metadata?.year?.toString(), item.metadata?.runtime?.takeIf { it > 0 }?.let { "$it ${localize("min")}" })
                    if (facts.isNotEmpty()) TvText(facts.joinToString("  ·  "), color = Muted,
                        modifier = Modifier.testTag("movie:facts"), translate = false)
                    item.metadata?.genres?.takeIf { it.isNotBlank() }?.let {
                        TvText(it, color = Muted, modifier = Modifier.testTag("movie:genres"), translate = false)
                    }
                    TvText(item.metadata?.overview.orEmpty().ifBlank { item.sourceTitle },
                        modifier = Modifier.testTag("movie:overview"), translate = false)
                }
                actions()
            }
        }
        TvBackAction(Modifier.align(Alignment.TopStart).padding(16.dp).focusRequester(backFocus)
            .focusProperties { down = watchFocus }.testTag("movie:back"), action = back)
    }
}

@Composable internal fun MovieCover(item: LibraryItem, modifier: Modifier = Modifier) {
    var failed by remember(item.metadata?.poster) { mutableStateOf(false) }
    var loaded by remember(item.metadata?.poster) { mutableStateOf(false) }
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(SurfaceSecondary)
        .testTag("movie:poster"), contentAlignment = Alignment.Center) {
        if (!failed && item.metadata?.poster?.isNotBlank() == true) AsyncImage(item.metadata.poster,
            contentDescription = item.title, modifier = Modifier.fillMaxSize().testTag(if (loaded) "movie:poster:loaded" else "movie:poster:loading"),
            contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { failed = true })
        else TvText(item.title.take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = Muted, translate = false)
    }
}

@Composable internal fun MovieFileAction(file: TorrentFile, number: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, action: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val motion = rememberTvButtonMotion(enabled, shape = shape)
    Button(onClick = { motion.activate(); action() }, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).then(motion.modifier).then(motion.pointerClick(enabled, action = action)),
        interactionSource = motion.interactions, colors = motion.colors, border = motion.border,
        shape = ButtonDefaults.shape(shape), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TvText(number.toString().padStart(2, '0'), Modifier.width(24.dp),
                style = MaterialTheme.typography.labelLarge, translate = false)
            TvText(file.path.substringAfterLast('/'), Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, translate = false)
            TvText(file.path.substringAfterLast('.', "").uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelLarge, translate = false)
            Image(painterResource(R.drawable.ic_play), contentDescription = null,
                colorFilter = ColorFilter.tint(LocalContentColor.current), modifier = Modifier.size(18.dp).then(motion.contentModifier))
        }
    }
}
