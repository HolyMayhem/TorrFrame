package tv.torrserve.silicon

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** A separately installed handler exercises package visibility and return from another task. */
class FakeExternalPlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "External player integration fixture" })
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 22_000)
    }
}
