package tv.torrframe.onboarding

/** Stable names are persisted; presentation order can change without losing progress. */
enum class SetupStep {
    Welcome, Language, Appearance, PhoneRemote, Server, Finished;

    val number: Int get() = when (this) {
        Language -> 1; Appearance -> 2; PhoneRemote -> 3; Server -> 4; else -> 0
    }
    fun next(): SetupStep = when (this) {
        Welcome -> Language; Language -> Appearance; Appearance -> PhoneRemote
        PhoneRemote -> Server; Server -> Finished; Finished -> Finished
    }
    fun previous(): SetupStep = when (this) {
        Language -> Welcome; Appearance -> Language; PhoneRemote -> Appearance
        Server -> PhoneRemote; else -> this
    }
    companion object {
        const val COUNT = 4
        fun restored(value: String?, existingInstallation: Boolean): SetupStep =
            entries.firstOrNull { it.name == value }
                ?: if (existingInstallation) Finished else Welcome
    }
}

data class SetupState(val step: SetupStep? = null, val busy: Boolean = false, val saveFailed: Boolean = false)
