package tv.torrframe.core.storage

object MetadataPriority {
    val defaultOrder = listOf("OMDb", "TMDB", "Kinopoisk")
    fun normalized(values: List<String>): List<String> {
        val valid = values.filter { it in defaultOrder }.distinct()
        return valid + defaultOrder.filter { it !in valid }
    }
    fun move(values: List<String>, provider: String, direction: Int): List<String> {
        require(direction == -1 || direction == 1)
        val order = normalized(values).toMutableList()
        val index = order.indexOf(provider)
        val next = index + direction
        if (index >= 0 && next in order.indices) {
            order[index] = order[next]; order[next] = provider
        }
        return order
    }
    fun label(provider: String) = when(provider) {
        "OMDb" -> "OMDB"
        "Kinopoisk" -> "Kinopoisk"
        else -> provider
    }
}
