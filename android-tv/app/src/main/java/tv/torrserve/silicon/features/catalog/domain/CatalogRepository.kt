package tv.torrserve.silicon.features.catalog.domain

import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun section(section: CatalogSection, language: String, page: Int = 1, force: Boolean = false): Flow<CatalogLoad<CatalogPage>>
    fun search(query: String, language: String, page: Int = 1, force: Boolean = false): Flow<CatalogLoad<CatalogPage>>
    fun details(ref: CatalogRef, language: String, force: Boolean = false): Flow<CatalogLoad<CatalogDetails>>
    fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int = 1, force: Boolean = false): Flow<CatalogLoad<CatalogPage>>
    fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int = 1, force: Boolean = false): Flow<CatalogLoad<CatalogPage>>
}
