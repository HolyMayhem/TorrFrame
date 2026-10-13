package tv.torrserve.silicon.features.updates

import android.app.Application
import androidx.lifecycle.ViewModel
import tv.torrserve.silicon.AppGraph

class UpdatesViewModel(app: Application, private val graph: AppGraph) : ViewModel() {
    val state = graph.updates.state
    fun server() = graph.updates.server()
    fun check() = graph.updates.check()
    fun download() = graph.updates.download()
    fun installer() = graph.updates.installer()
    fun installerFailed() = graph.updates.installerFailed()
}
