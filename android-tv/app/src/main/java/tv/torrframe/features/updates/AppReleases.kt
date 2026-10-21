package tv.torrframe.features.updates

/** The official update channel is fixed; user settings cannot redirect APK downloads. */
object AppReleases {
    const val REPOSITORY = "HolyMayhem/TorrFrame"
    const val URL = "https://github.com/$REPOSITORY/releases"

    fun isNewer(tag: String, installed: String): Boolean {
        fun parts(value: String): List<Long>? {
            val version = value.removePrefix("v")
            if (!version.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) return null
            return version.split('.').map { it.toLongOrNull() ?: return null }
        }
        val candidate = parts(tag) ?: return false
        val current = parts(installed) ?: return false
        for (index in 0 until maxOf(candidate.size, current.size)) {
            val comparison = (candidate.getOrElse(index) { 0L }).compareTo(current.getOrElse(index) { 0L })
            if (comparison != 0) return comparison > 0
        }
        return false
    }
}
