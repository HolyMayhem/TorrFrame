package tv.torrserve.silicon

import org.json.JSONObject
import tv.torrserve.silicon.features.catalog.data.CatalogRepositoryImpl

/** These UI/configuration tests verify cached navigation, not TMDB network availability. */
internal suspend fun <T> withCatalogDetailsFixture(graph:AppGraph,block:suspend ()->T):T {
    graph.ready.await()
    val keys=listOf("en-US","ru-RU").associateWith { "details:v2:movie:157336:$it" }
    val previous=keys.values.associateWith { graph.catalogCache.read(it) }
    try {
        for((language,key)in keys) {
            val russian=language=="ru-RU"
            val data=JSONObject().put("id",157336).put("title",if(russian)"Интерстеллар"else"Interstellar")
                .put("original_title","Interstellar").put("release_date","2014-11-05").put("runtime",169)
                .put("overview",if(russian)"Тестовое описание фильма."else"Film description fixture.")
                .put("vote_average",8.5).put("vote_count",100)
            graph.catalogCache.write(key,data.toString(),CatalogRepositoryImpl.DAY,CatalogRepositoryImpl.DAY)
        }
        return block()
    } finally {
        for((key,entry)in previous) {
            if(entry==null)graph.catalogCache.remove(key)
            else graph.catalogCache.write(key,entry.payload,(entry.expiresAt-System.currentTimeMillis()).coerceAtLeast(0),CatalogRepositoryImpl.DAY)
        }
    }
}
