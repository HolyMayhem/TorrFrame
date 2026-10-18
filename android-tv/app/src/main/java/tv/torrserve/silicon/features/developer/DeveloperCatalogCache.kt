package tv.torrserve.silicon.features.developer

import tv.torrserve.silicon.features.catalog.data.CatalogCache

/** While simulating TMDB failures, leave the real language caches completely untouched. */
class DeveloperCatalogCache(private val cache:CatalogCache,private val controls:DeveloperControls):CatalogCache {
    private fun bypass()=controls.state.value.bypassMetadata("TMDB")
    override suspend fun read(key:String)=if(bypass())null else cache.read(key)
    override suspend fun write(key:String,payload:String,ttl:Long,retention:Long) {
        if(!bypass())cache.write(key,payload,ttl,retention)
    }
    override suspend fun remove(key:String) { if(!bypass())cache.remove(key) }
}
