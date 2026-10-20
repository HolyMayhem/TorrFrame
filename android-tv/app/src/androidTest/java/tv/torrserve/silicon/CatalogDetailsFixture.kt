package tv.torrserve.silicon

import org.json.JSONObject
import tv.torrserve.silicon.features.catalog.data.CatalogRepositoryImpl

/** These UI/configuration tests verify cached navigation, not TMDB network availability. */
internal suspend fun <T> withCatalogDetailsFixture(graph:AppGraph,block:suspend ()->T):T {
    graph.ready.await()
    val originalNetwork=graph.settings.state.value.catalogNetwork
    graph.settings.update { it.copy(catalogNetwork=it.catalogNetwork.copy(source=tv.torrserve.silicon.core.network.CatalogSource.Tmdb)) }
    val locales=listOf("en-US","ru-RU")
    val queryHash=java.security.MessageDigest.getInstance("SHA-256").digest("Interstellar".toByteArray()).joinToString("") { "%02x".format(it) }
    val keys=locales.flatMap { listOf("tmdb:details:v2:movie:157336:$it","tmdb:search:$queryHash:$it:1") }
    val previous=keys.associateWith { graph.catalogCache.read(it) }
    try {
        for(language in locales) {
            val russian=language=="ru-RU"
            val data=JSONObject().put("id",157336).put("media_type","movie").put("title",if(russian)"Интерстеллар"else"Interstellar")
                .put("original_title","Interstellar").put("release_date","2014-11-05").put("runtime",169)
                .put("overview",if(russian)"Тестовое описание фильма."else"Film description fixture.")
                .put("vote_average",8.5).put("vote_count",100)
            graph.catalogCache.write("tmdb:details:v2:movie:157336:$language",data.toString(),CatalogRepositoryImpl.DAY,CatalogRepositoryImpl.DAY)
            val page=JSONObject().put("page",1).put("total_pages",1).put("results",org.json.JSONArray().put(data))
            graph.catalogCache.write("tmdb:search:$queryHash:$language:1",page.toString(),CatalogRepositoryImpl.DAY,CatalogRepositoryImpl.DAY)
        }
        return block()
    } finally {
        for((key,entry)in previous) {
            if(entry==null)graph.catalogCache.remove(key)
            else graph.catalogCache.write(key,entry.payload,(entry.expiresAt-System.currentTimeMillis()).coerceAtLeast(0),CatalogRepositoryImpl.DAY)
        }
        graph.settings.update { it.copy(catalogNetwork=originalNetwork) }
    }
}
