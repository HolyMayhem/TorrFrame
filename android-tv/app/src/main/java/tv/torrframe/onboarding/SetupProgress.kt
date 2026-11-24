package tv.torrframe.onboarding

/** Stable names are persisted; presentation order can change without losing progress. */
enum class SetupStep {
    Welcome, Language, Appearance, PhoneRemote, Privacy, Server, Finished;

    val number: Int get() = when (this) {
        Language -> 1; Appearance -> 2; PhoneRemote -> 3; Privacy -> 4; Server -> 5; else -> 0
    }
    fun next(): SetupStep = when (this) {
        Welcome -> Language; Language -> Appearance; Appearance -> PhoneRemote
        PhoneRemote -> Privacy; Privacy -> Server; Server -> Finished; Finished -> Finished
    }
    fun previous(): SetupStep = when (this) {
        Language -> Welcome; Appearance -> Language; PhoneRemote -> Appearance
        Privacy -> PhoneRemote; Server -> Privacy; else -> this
    }
    companion object {
        const val COUNT = 5
        fun restored(value: String?, existingInstallation: Boolean): SetupStep =
            entries.firstOrNull { it.name == value }
                ?: if (existingInstallation) Finished else Welcome
    }
}

data class SetupState(val step: SetupStep? = null, val busy: Boolean = false, val saveFailed: Boolean = false)
