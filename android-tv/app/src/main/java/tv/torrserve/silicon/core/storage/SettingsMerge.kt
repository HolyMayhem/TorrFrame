package tv.torrserve.silicon.core.storage

/** Retain unsaved TV edits while adopting fields changed by another interface. */
object SettingsMerge {
    fun rebase(draft: Settings, base: Settings, current: Settings) = current.copy(
        jackettUrl = if (draft.jackettUrl != base.jackettUrl) draft.jackettUrl else current.jackettUrl,
        jackettKey = if (draft.jackettKey != base.jackettKey) draft.jackettKey else current.jackettKey,
        metadataOrder = if (draft.metadataOrder != base.metadataOrder) draft.metadataOrder else current.metadataOrder,
        aniListEnabled = if (draft.aniListEnabled != base.aniListEnabled) draft.aniListEnabled else current.aniListEnabled,
        language = if (draft.language != base.language) draft.language else current.language,
        resume = if (draft.resume != base.resume) draft.resume else current.resume,
        audioLanguage = if (draft.audioLanguage != base.audioLanguage) draft.audioLanguage else current.audioLanguage,
        subtitleLanguage = if (draft.subtitleLanguage != base.subtitleLanguage) draft.subtitleLanguage else current.subtitleLanguage,
        updateRepository = if (draft.updateRepository != base.updateRepository) draft.updateRepository else current.updateRepository,
        uiLanguage = if (draft.uiLanguage != base.uiLanguage) draft.uiLanguage else current.uiLanguage,
        uiScalePercent = if (draft.uiScalePercent != base.uiScalePercent) draft.uiScalePercent else current.uiScalePercent)
}
