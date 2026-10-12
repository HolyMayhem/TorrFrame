package tv.torrserve.silicon.features.metadata

import tv.torrserve.silicon.core.models.MediaKind
import java.text.Normalizer
import java.util.Locale

data class ParsedName(val title: String, val year: Int?, val kind: MediaKind, val season: Int? = null, val episode: Int? = null)
data class TitleCandidate(val parsed: ParsedName, val confidence: Int)

object MediaNameParser {
    private val episode = Regex("\\b(?:S(\\d{1,2})[ ._-]*E(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))\\b", RegexOption.IGNORE_CASE)
    private val season = Regex("\\b(?:S|Season[ ._-]*)(\\d{1,2})\\b|(?:сезон)[ ._-]*(\\d{1,2})", RegexOption.IGNORE_CASE)
    private val year = Regex("\\b(?:19|20)\\d{2}\\b")
    private val technical = Regex("\\b(?:2160p|1440p|1080[pi]|720p|576p|480p|4K|UHD|HDR10\\+?|HDR|DV|DoVi|REMUX|BluRay|BDRip|BRRip|WEB[ ._-]?(?:DL|Rip)|HDTV|DVDRip|x26[45]|h26[45]|HEVC|AVC|AV1|AAC|AC3|EAC3|DTS(?:-HD)?|Atmos|TrueHD|FLAC|MULTI|DUAL|PROPER|REPACK|EXTENDED|UNRATED|COMPLETE)\\b", RegexOption.IGNORE_CASE)
    private val extensions = Regex("\\.(?:mkv|mp4|avi|m4v|mov|ts|m2ts|webm|torrent)$", RegexOption.IGNORE_CASE)
    fun parse(source: String): ParsedName {
        val cleaned = source.replace(extensions, "").replace(Regex("[._+]"), " ").replace(Regex("\\s+"), " ").trim()
        val ep = episode.find(cleaned); val s = season.find(cleaned)
        val seasonNumber = ep?.let { (it.groupValues[1].ifBlank { it.groupValues[3] }).toIntOrNull() }
            ?: s?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.toIntOrNull() }
        val episodeNumber = ep?.let { it.groupValues[2].ifBlank { it.groupValues[4] }.toIntOrNull() }
        val ym = year.findAll(cleaned).lastOrNull()?.takeIf { cleaned.take(it.range.first).any { c -> c.isLetterOrDigit() } }
        val cut = listOfNotNull(ym?.range?.first, ep?.range?.first, s?.range?.first, technical.find(cleaned)?.range?.first).minOrNull() ?: cleaned.length
        val title = cleaned.take(cut).replace(Regex("[\\[\\]{}()]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—')
        return ParsedName(title.ifBlank { cleaned }, ym?.value?.toIntOrNull(),
            if (seasonNumber != null || episodeNumber != null) MediaKind.Tv else MediaKind.Movie, seasonNumber, episodeNumber)
    }
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun candidates(sources: List<String>, provider: String, language: String): List<TitleCandidate> {
        val candidates = mutableListOf<TitleCandidate>()
        sources.filter { it.isNotBlank() }.take(4).forEachIndexed { index, source ->
            val original = parse(source)
            val prefix = source.replace(extensions, "").let { raw ->
                val cut = listOfNotNull(year.findAll(raw).lastOrNull()?.range?.first?.takeIf { it > 0 }, technical.find(raw)?.range?.first)
                    .minOrNull() ?: raw.length; raw.take(cut)
            }
            val noParentheses = prefix.replace(Regex("\\([^)]*(?:\\)|$)"), " ")
            val clean = noParentheses.replace(Regex("\\[[^]]*(?:]|$)|\\{[^}]*(?:}|$)"), " ")
                .replace(Regex("\\s+[-–—]\\s*\\d{1,4}\\s*$"), "")
            val variants = mutableListOf(prefix to 0, noParentheses to 24, clean to 36)
            val split = Regex("\\s*(?:/|\\||\\baka\\b)\\s*", RegexOption.IGNORE_CASE)
            if (split.containsMatchIn(clean)) variants += clean.split(split).take(2).map { it to 60 }
            else variants += Regex("\\(([^)]*)\\)").findAll(prefix).map { it.groupValues[1] to 8 }.toList()
            variants.forEach { (value, bonus) ->
                val p = parse(value + (original.year?.let { " $it" } ?: "")).copy(kind = original.kind, season = original.season, episode = original.episode)
                if (p.title.isNotBlank()) {
                    val latin = Regex("[A-Za-z]").containsMatchIn(p.title); val cyrillic = Regex("[А-Яа-яЁё]").containsMatchIn(p.title)
                    val script = when(provider) {
                        "Kinopoisk" -> if (cyrillic && !latin) 32 else 24
                        "OMDb", "AniList" -> if (latin && !cyrillic) 32 else if (cyrillic) -12 else 0
                        else -> if (language.startsWith("ru") && cyrillic && !latin) 24 else if (latin && !cyrillic) 16 else 0
                    }
                    val noise = (p.title.split(' ').size - 6).coerceAtLeast(0) * 4 + if (p.title.contains('/') || p.title.contains('|')) 18 else 0
                    candidates += TitleCandidate(p, (100 - index * 20).coerceAtLeast(35) + bonus + script - noise)
                }
            }
        }
        return candidates.sortedWith(compareByDescending<TitleCandidate> { it.confidence }.thenBy { it.parsed.title.length })
            .distinctBy { "${normalized(it.parsed.title)}|${it.parsed.year}|${it.parsed.kind}" }.take(5)
    }
    fun similarity(left: String, right: String): Double {
        val a = normalized(left); val b = normalized(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 3.0
        if (a.contains(b) || b.contains(a)) return 2.0
        val x = a.split(' ').toSet(); val y = b.split(' ').toSet()
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
