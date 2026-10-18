package tv.torrserve.silicon.core.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes

/** UI locale follows the app preference, independently of metadata and the device language. */
fun Context.uiLanguage(): String = applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE)
    .getString("uiLanguage","en").let { if(it=="ru")"ru" else "en" }
fun Context.withUiLanguage(language:String=uiLanguage()): Context {
    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(if(language=="ru")"ru" else "en"))
    }
    // Keep the Activity in the wrapper chain: dialogs and activity launches still use its owner.
    return ContextThemeWrapper(this, 0).apply { applyOverrideConfiguration(configuration) }
}
fun Context.uiString(@StringRes id:Int,vararg args:Any):String = withUiLanguage().getString(id,*args)
