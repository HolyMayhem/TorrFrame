package tv.torrserve.silicon.core.storage

object UiAccent {
    const val DEFAULT = "green"
    val colors = linkedMapOf("green" to 0xFF008800L, "white" to 0xFFFFFFFFL,
        "orange" to 0xFFFF8A3DL, "pink" to 0xFFF472B6L, "blue" to 0xFF3B82F6L)
    fun normalized(value: String?) = value?.takeIf { it in colors } ?: DEFAULT
}
