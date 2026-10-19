package tv.torrserve.torrentsearch.model

import java.util.Locale

/** Search policy is portable; storage and the Android controls live in the host application. */
enum class SearchTitleMode { Original, Russian, Both }
data class TorrentSearchOptions(
    val enabledProviders: Set<String>? = null, // null = all, empty = none
    val resolutions: Set<String> = emptySet(), val formats: Set<String> = emptySet(),
    val minBytes: Long = 0, val maxBytes: Long = 0,
    val minSeeds: Long = 0, val hideZeroSeeds: Boolean = false,
    val sourceTimeoutMs: Long = 18_000, val titleMode: SearchTitleMode = SearchTitleMode.Both
) {
    fun normalized(): TorrentSearchOptions {
        val minimum=minBytes.coerceIn(0,MAX_BYTES)
        return copy(enabledProviders=enabledProviders?.filter { it.isNotBlank() }?.toSet(),
            resolutions=resolutions.intersect(RESOLUTIONS.toSet()),formats=formats.intersect(FORMATS.toSet()),
            minBytes=minimum,maxBytes=if(maxBytes<=0)0 else maxBytes.coerceIn(minimum,MAX_BYTES),
            minSeeds=minSeeds.coerceIn(0,10_000),sourceTimeoutMs=sourceTimeoutMs.coerceIn(5_000,60_000))
    }
    fun accepts(row: TorrentResult): Boolean {
        if(minSeeds>0 && (row.seeds==null || row.seeds<minSeeds))return false
        if(hideZeroSeeds && row.seeds==0L)return false
        val qualities=row.quality.split(" · ").toSet()
        if(resolutions.isNotEmpty() && resolutions.none { it in qualities })return false
        if(formats.isNotEmpty() && formats.none { it in qualities })return false
        if(minBytes>0 || maxBytes>0) {
            val bytes=row.sizeBytes ?: TorrentSize.bytes(row.sizeText) ?: return false
            if(bytes<minBytes || (maxBytes>0 && bytes>maxBytes))return false
        }
        return true
    }
    companion object {
        const val GIB=1_073_741_824L
        const val MAX_BYTES=1024*GIB
        val RESOLUTIONS=listOf("480p","720p","1080p","2160p")
        val FORMATS=listOf("WEB-DL","WEBRip","BluRay","BDRip","HDTV","DVD")
        fun rebase(draft: TorrentSearchOptions, base: TorrentSearchOptions, current: TorrentSearchOptions): TorrentSearchOptions {
            fun <T> pick(d:T,b:T,c:T)=if(d!=b)d else c
            return current.copy(enabledProviders=pick(draft.enabledProviders,base.enabledProviders,current.enabledProviders),
                resolutions=pick(draft.resolutions,base.resolutions,current.resolutions),formats=pick(draft.formats,base.formats,current.formats),
                minBytes=pick(draft.minBytes,base.minBytes,current.minBytes),maxBytes=pick(draft.maxBytes,base.maxBytes,current.maxBytes),
                minSeeds=pick(draft.minSeeds,base.minSeeds,current.minSeeds),hideZeroSeeds=pick(draft.hideZeroSeeds,base.hideZeroSeeds,current.hideZeroSeeds),
                sourceTimeoutMs=pick(draft.sourceTimeoutMs,base.sourceTimeoutMs,current.sourceTimeoutMs),titleMode=pick(draft.titleMode,base.titleMode,current.titleMode)).normalized()
        }
    }
}
object TorrentSize {
    fun bytes(value: String?): Long? {
        val text=value?.replace('\u00a0',' ')?.trim() ?: return null
        val match=Regex("^([0-9][0-9 .,]*)\\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|ТБ|ГБ|МБ|КБ|B|bytes|байт)$",RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val number=match.groupValues[1].replace(" ","").replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 } ?: return null
        val unit=match.groupValues[2].uppercase(Locale.ROOT)
        val exponent=when(unit.first()){ 'T','Т'->4; 'G','Г'->3; 'M','М'->2; 'K','К'->1;else->0 }
        val base=if(unit.contains('I'))1024.0 else 1000.0
        val bytes=number*Math.pow(base,exponent.toDouble())
        return bytes.takeIf { it<=Long.MAX_VALUE.toDouble() }?.toLong()
    }
}
