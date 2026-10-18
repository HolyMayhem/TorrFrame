package tv.torrserve.silicon.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Routing is restricted to public TMDB GETs. TorrServer, trackers and CUB are never rewritten. */
class CatalogRouteInterceptor(private val settings: () -> CatalogNetworkSettings,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) : Interceptor {
    private val unavailableUntil = linkedMapOf<String, Long>()
    private fun key(url: HttpUrl) = "${url.host}:${url.port}"
    @Synchronized private fun available(routes: List<HttpUrl>): List<HttpUrl> {
        unavailableUntil.entries.removeAll { it.value <= now() }
        return routes.filter { key(it) !in unavailableUntil }.ifEmpty { routes }
    }
    @Synchronized private fun failed(url: HttpUrl) {
        unavailableUntil.remove(key(url)); unavailableUntil[key(url)] = now() + 30_000
        while (unavailableUntil.size > 8) unavailableUntil.remove(unavailableUntil.keys.first())
    }
    @Synchronized private fun recovered(url: HttpUrl) { unavailableUntil.remove(key(url)) }
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method != "GET" || !original.url.isHttps || original.url.port != 443 ||
            original.url.host !in setOf("api.themoviedb.org", "image.tmdb.org")) return chain.proceed(original)
        val config = settings()
        val destinations = available(destinations(original.url, config))
        var failure: IOException? = null
        for ((index, url) in destinations.withIndex()) {
            if (chain.call().isCanceled()) throw IOException("Catalog request cancelled.")
            val request = if (url == original.url) original else original.newBuilder().url(url)
                .removeHeader("Authorization").removeHeader("Proxy-Authorization").removeHeader("Cookie").removeHeader("token").build()
            try {
                val response = chain.proceed(request)
                val blockedImage = original.url.host == "image.tmdb.org" && response.code == 403
                if (!retryable(response.code) && !blockedImage) { if (response.isSuccessful) recovered(url); return response }
                failed(url)
                if (index == destinations.lastIndex) return response
                // Release each failed response before advancing to another route.
                response.close()
            } catch (error: IOException) {
                if (chain.call().isCanceled()) throw error
                failed(url)
                if (index == destinations.lastIndex) throw error
                failure = error
            }
        }
        throw failure ?: IOException("No catalog route is configured.")
    }

    companion object {
        internal fun retryable(status: Int) = status == 408 || status in 500..599
        fun destinations(original: HttpUrl, config: CatalogNetworkSettings): List<HttpUrl> {
            val bases = when (original.host) {
                "api.themoviedb.org" -> listOf(config.apiProxy).filter(String::isNotBlank)
                "image.tmdb.org" -> (config.imageMirrors + CatalogNetworkSettings.PUBLIC_IMAGE_MIRRORS).distinct()
                else -> return listOf(original)
            }
            val publicImages = original.host == "image.tmdb.org"
            if (config.route == CatalogRoute.Direct && !publicImages) return listOf(original)
            val alternatives = bases.map { base ->
                val endpoint = CatalogNetworkSettings.endpoint(base)
                // API bases include /3/. Image bases precede /t/p/<size>/....
                val suffix = if (original.host == "api.themoviedb.org") original.encodedPath.removePrefix("/3/")
                    else original.encodedPath.trimStart('/')
                endpoint.newBuilder().encodedPath(endpoint.encodedPath.trimEnd('/') + "/" + suffix)
                    .encodedQuery(original.encodedQuery).build()
            }
            return (if (config.route == CatalogRoute.Auto || publicImages && config.route == CatalogRoute.Direct)
                listOf(original) + alternatives else alternatives).distinct()
        }
    }
}
