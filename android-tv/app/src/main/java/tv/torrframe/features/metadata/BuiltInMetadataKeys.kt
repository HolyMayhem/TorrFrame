package tv.torrframe.features.metadata

import tv.torrframe.BuildConfig

object BuiltInMetadataKeys {
    fun forProvider(provider: String): String = when(provider) {
        "TMDB" -> BuildConfig.TMDB_API_KEY
        "OMDb" -> BuildConfig.OMDB_API_KEY
        "Kinopoisk" -> BuildConfig.KINOPOISK_API_KEY
        else -> ""
    }
}
