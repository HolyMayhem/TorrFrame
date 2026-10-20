package tv.torrframe.onboarding

import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only wizard progress. User settings remain owned by their existing stores. */
class SetupController(private val preferences: SharedPreferences, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SetupState())
    val state = mutable.asStateFlow()

    suspend fun initialize(existingInstallation: Boolean) = lock.withLock {
        if (mutable.value.step != null) return@withLock
        var step = SetupStep.restored(null, existingInstallation)
        try {
            withContext(Dispatchers.IO) {
                step = SetupStep.restored(preferences.getString("step", null), existingInstallation)
                // Record eligibility before normal settings loading writes its migrations.
                check(preferences.edit().putString("step", step.name).commit())
            }
            mutable.value = SetupState(step)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A progress-file failure must not break the application's normal startup.
            mutable.value = SetupState(step, saveFailed = true)
        }
    }

    fun next() = move { it.next() }
    fun back() = move { it.previous() }
    fun finish() = move { SetupStep.Finished }
    fun restart() = move { SetupStep.Welcome }

    private fun move(change: (SetupStep) -> SetupStep) {
        if (mutable.value.busy || mutable.value.step == null) return
        mutable.update { it.copy(busy = true, saveFailed = false) }
        scope.launch {
            lock.withLock {
                try {
                    val next = change(requireNotNull(mutable.value.step))
                    withContext(Dispatchers.IO) {
                        check(preferences.edit().putString("step", next.name).commit())
                    }
                    mutable.value = SetupState(next)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(busy = false, saveFailed = true) }
                }
            }
        }
    }
}
