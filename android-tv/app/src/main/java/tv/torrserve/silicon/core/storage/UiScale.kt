package tv.torrserve.silicon.core.storage

object UiScale {
    const val DEFAULT = 85
    const val MIN = 70
    const val MAX = 110
    const val STEP = 5
    fun normalized(percent: Int) = ((percent.coerceIn(MIN, MAX) + STEP / 2) / STEP) * STEP
    fun adjust(percent: Int, direction: Int) = normalized(normalized(percent) + direction.coerceIn(-1, 1) * STEP)
    fun factor(percent: Int) = normalized(percent) / 100f
}
