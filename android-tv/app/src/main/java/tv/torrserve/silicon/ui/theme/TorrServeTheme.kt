package tv.torrserve.silicon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    val screenInset = 32.dp
    val sectionGap = 24.dp
    val actionHeight = 44.dp
    val gap = 16.dp
    val cardRadius = 14.dp
    val buttonRadius = 20.dp
    val posterWidth = 144.dp
}
val Background = Color(0xFF101114)
val Surface = Color(0xFF1B1C20)
val Muted = Color(0xFFA4A5AD)
val TextPrimary = Color(0xFFF5F5F7)
val SurfaceSecondary = Color(0xFF25262B)
val Outline = Color(0xFF3A3B43)
val Accent = Color(0xFFE2E3EB)
val Positive = Color(0xFF90B78E)

@Composable fun TorrServeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Background,
        background = Background, surface = Surface,
        onBackground = TextPrimary, onSurface = TextPrimary,
        surfaceVariant = SurfaceSecondary, onSurfaceVariant = Muted
    ), typography = Typography(
        displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    )) {
        // MaterialTheme supplies a palette; plain Text also needs an inherited content color.
        CompositionLocalProvider(LocalContentColor provides TextPrimary) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge, content)
        }
    }
}
