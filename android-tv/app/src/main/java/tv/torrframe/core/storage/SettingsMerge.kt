package tv.torrframe.core.storage

/** Retain unsaved TV edits while adopting fields changed by another interface. */
object SettingsMerge {
    fun rebase(draft: Settings, base: Settings, current: Settings) = current.copy(
        catalogNetwork = tv.torrframe.core.network.CatalogNetworkSettings.rebase(draft.catalogNetwork, base.catalogNetwork, current.catalogNetwork),
        torrentSearch = tv.torrserve.torrentsearch.model.TorrentSearchOptions.rebase(draft.torrentSearch, base.torrentSearch, current.torrentSearch),
        jackettEnabled = if (draft.jackettEnabled != base.jackettEnabled) draft.jackettEnabled else current.jackettEnabled,
        jackettUrl = if (draft.jackettUrl != base.jackettUrl) draft.jackettUrl else current.jackettUrl,
        jackettKey = if (draft.jackettKey != base.jackettKey) draft.jackettKey else current.jackettKey,
        metadataOrder = if (draft.metadataOrder != base.metadataOrder) draft.metadataOrder else current.metadataOrder,
        aniListEnabled = if (draft.aniListEnabled != base.aniListEnabled) draft.aniListEnabled else current.aniListEnabled,
        language = if (draft.language != base.language) draft.language else current.language,
        resume = if (draft.resume != base.resume) draft.resume else current.resume,
        builtInPlayer = tv.torrframe.features.player.PlayerPreferences.rebase(draft.builtInPlayer, base.builtInPlayer, current.builtInPlayer),
        playerMode = if (draft.playerMode != base.playerMode) draft.playerMode else current.playerMode,
        externalPlayer = if (draft.externalPlayer != base.externalPlayer) draft.externalPlayer else current.externalPlayer,
        audioLanguage = if (draft.audioLanguage != base.audioLanguage) draft.audioLanguage else current.audioLanguage,
        subtitleLanguage = if (draft.subtitleLanguage != base.subtitleLanguage) draft.subtitleLanguage else current.subtitleLanguage,
        updateRepository = if (draft.updateRepository != base.updateRepository) draft.updateRepository else current.updateRepository,
        uiLanguage = if (draft.uiLanguage != base.uiLanguage) draft.uiLanguage else current.uiLanguage,
        uiScalePercent = if (draft.uiScalePercent != base.uiScalePercent) draft.uiScalePercent else current.uiScalePercent,
        uiTheme = if (draft.uiTheme != base.uiTheme) draft.uiTheme else current.uiTheme,
        showNavigationHints = if (draft.showNavigationHints != base.showNavigationHints) draft.showNavigationHints else current.showNavigationHints,
        uiSoundsEnabled = if (draft.uiSoundsEnabled != base.uiSoundsEnabled) draft.uiSoundsEnabled else current.uiSoundsEnabled,
        animationsEnabled = if (draft.animationsEnabled != base.animationsEnabled) draft.animationsEnabled else current.animationsEnabled,
        uiAccent = if (draft.uiAccent != base.uiAccent) draft.uiAccent else current.uiAccent)
}
