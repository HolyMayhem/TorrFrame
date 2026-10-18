package tv.torrserve.silicon.features.startup

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object StartupWindow {
    fun install(activity: Activity) {
        activity.window.decorView.setBackgroundColor(Color.BLACK)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        hideBars(activity.window)
        if (Build.VERSION.SDK_INT >= 31) activity.splashScreen.setOnExitAnimationListener { it.remove() }
    }
    fun hideBars(window: Window) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
