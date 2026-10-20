package tv.torrframe.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import tv.torrframe.core.localization.withUiLanguage

/** Updating only locale locals preserves dialogs, D-pad focus, density, and ongoing operations. */
@Composable fun AppLanguage(language:String,content:@Composable ()->Unit) {
    val base=LocalContext.current
    val configuration=LocalConfiguration.current
    val locale=if(language=="ru")"ru" else "en"
    val context=remember(base,configuration,locale) { base.withUiLanguage(locale) }
    CompositionLocalProvider(LocalUiLanguage provides locale,LocalContext provides context,
        LocalConfiguration provides context.resources.configuration,LocalResources provides context.resources,content=content)
}
