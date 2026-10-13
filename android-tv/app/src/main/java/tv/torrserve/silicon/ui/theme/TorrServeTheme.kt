package tv.torrserve.silicon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import tv.torrserve.silicon.core.storage.UiScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

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
data class TvPalette(val background: Color, val surface: Color, val muted: Color,
    val text: Color, val secondary: Color, val outline: Color)
val DarkPalette = TvPalette(Color(0xFF101114), Color(0xFF1B1C20), Color(0xFFA4A5AD),
    Color(0xFFF5F5F7), Color(0xFF25262B), Color(0xFF3A3B43))
val LightPalette = TvPalette(Color(0xFFF5F7F5), Color.White, Color(0xFF586358),
    Color(0xFF172117), Color(0xFFEAF0EA), Color(0xFFCCD5CC))
val LocalTvPalette = staticCompositionLocalOf { DarkPalette }
val Background: Color @Composable get() = LocalTvPalette.current.background
val Surface: Color @Composable get() = LocalTvPalette.current.surface
val Muted: Color @Composable get() = LocalTvPalette.current.muted
val TextPrimary: Color @Composable get() = LocalTvPalette.current.text
val SurfaceSecondary: Color @Composable get() = LocalTvPalette.current.secondary
val Outline: Color @Composable get() = LocalTvPalette.current.outline
val Accent = Color(0xFF008800)
val OnAccent = Color.White
val Positive = Color(0xFF90B78E)

@Composable fun TorrServeTheme(uiScalePercent: Int = UiScale.DEFAULT, light: Boolean = false, content: @Composable () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val platformDensity = LocalDensity.current
    val density = Density(platformDensity.density * UiScale.factor(uiScalePercent), platformDensity.fontScale)
    val widthDp = if(window.width > 0) with(density) { window.width.toDp().value.toInt() } else 960
    val heightDp = if(window.height > 0) with(density) { window.height.toDp().value.toInt() } else 540
    val layout = tvLayoutFor(widthDp, heightDp)
    val headingScale = if(layout.short) .92f else 1f
    // Changing density causes an actual reflow: smaller controls expose more content.
    CompositionLocalProvider(LocalDensity provides density, LocalTvLayout provides layout,
        LocalTvPalette provides if (light) LightPalette else DarkPalette) {
    val colors = if (light) lightColorScheme() else darkColorScheme()
    MaterialTheme(colorScheme = colors.copy(
        primary = Accent, onPrimary = OnAccent,
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
