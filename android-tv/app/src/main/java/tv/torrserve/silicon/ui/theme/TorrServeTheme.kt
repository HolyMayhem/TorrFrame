package tv.torrserve.silicon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object TvDimensions {
    val screenInset = 24.dp
    val sectionGap = 16.dp
    val actionHeight = 36.dp
    val gap = 12.dp
    val cardRadius = 14.dp
    val buttonRadius = 16.dp
    val posterWidth = 128.dp
}
data class TvLayout(val narrow: Boolean, val short: Boolean, val libraryColumns: Int) {
    val screenInset get() = if(narrow) 20.dp else TvDimensions.screenInset
    val sectionGap get() = if(short) 12.dp else TvDimensions.sectionGap
    val panelPadding get() = if(short) 16.dp else 20.dp
}
fun tvLayoutFor(widthDp: Int, heightDp: Int): TvLayout {
    val inset = if(widthDp < 840) 20 else 24
    val columns = ((widthDp - inset * 2 + 12) / 140).coerceIn(3, 8)
    return TvLayout(widthDp < 840, heightDp < 500, columns)
}
val LocalTvLayout = staticCompositionLocalOf { tvLayoutFor(960, 540) }
val Background = Color(0xFF101114)
val Surface = Color(0xFF1B1C20)
val Muted = Color(0xFFA4A5AD)
val TextPrimary = Color(0xFFF5F5F7)
val SurfaceSecondary = Color(0xFF25262B)
val Outline = Color(0xFF3A3B43)
val Accent = Color(0xFFE2E3EB)
val Positive = Color(0xFF90B78E)

@Composable fun TorrServeTheme(content: @Composable () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = if(window.width > 0) with(density) { window.width.toDp().value.toInt() } else 960
    val heightDp = if(window.height > 0) with(density) { window.height.toDp().value.toInt() } else 540
    val layout = tvLayoutFor(widthDp, heightDp)
    val headingScale = if(layout.short) .92f else 1f
    CompositionLocalProvider(LocalTvLayout provides layout) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Background,
        background = Background, surface = Surface,
        onBackground = TextPrimary, onSurface = TextPrimary,
        surfaceVariant = SurfaceSecondary, onSurfaceVariant = Muted
    ), typography = Typography(
        displaySmall = TextStyle(fontSize = (28 * headingScale).sp, lineHeight = (34 * headingScale).sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = (24 * headingScale).sp, lineHeight = (30 * headingScale).sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = (20 * headingScale).sp, lineHeight = (26 * headingScale).sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    )) {
        // MaterialTheme supplies a palette; plain Text also needs an inherited content color.
        CompositionLocalProvider(LocalContentColor provides TextPrimary) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge, content)
        }
    }
    }
}
