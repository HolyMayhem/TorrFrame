package tv.torrserve.silicon.features.player

/** Stable preference values; unknown values from older/newer builds use the built-in player. */
enum class PlayerMode { Internal, External, Ask;
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: Internal }
}

data class ExternalPlayer(val component: String, val label: String)

sealed interface PlayerDestination {
    data object Internal : PlayerDestination
    data class External(val player: ExternalPlayer) : PlayerDestination
    data class Choose(val missingPreferred: Boolean = false) : PlayerDestination
}

fun playerDestination(mode: PlayerMode, preferred: String, available: List<ExternalPlayer>): PlayerDestination = when (mode) {
    PlayerMode.Internal -> PlayerDestination.Internal
    PlayerMode.Ask -> PlayerDestination.Choose()
    PlayerMode.External -> available.firstOrNull { it.component == preferred }?.let(PlayerDestination::External)
        ?: PlayerDestination.Choose(missingPreferred = preferred.isNotEmpty())
}
