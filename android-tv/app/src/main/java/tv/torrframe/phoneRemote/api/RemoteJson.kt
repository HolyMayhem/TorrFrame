package tv.torrframe.phoneRemote.api

import tv.torrframe.core.localization.uiString
import tv.torrframe.core.localization.withUiLanguage
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tv.torrframe.AppGraph
import tv.torrframe.BuildConfig
import tv.torrframe.core.models.*
import tv.torrframe.core.server.*
import tv.torrframe.core.storage.Settings
import tv.torrframe.features.player.PlaybackSnapshot

object RemoteJson {
    fun settings(value: Settings, revision: Long) = JSONObject().put("revision", revision)
        .put("jackettEnabled", value.jackettEnabled).put("jackettUrl", value.jackettUrl).put("jackettKeyConfigured", value.jackettKey.isNotBlank())
        .put("metadataOrder", JSONArray(value.metadataOrder)).put("aniListEnabled", value.aniListEnabled)
        .put("language", value.language).put("resume", value.resume).put("audioLanguage", value.audioLanguage)
        .put("subtitleLanguage", value.subtitleLanguage).put("uiLanguage", value.uiLanguage)
        .put("updateRepository", value.updateRepository).put("torrServerUrl", value.torrServerUrl)
        .put("uiTheme", value.uiTheme).put("uiAccent", value.uiAccent).put("uiScalePercent", value.uiScalePercent)
        .put("uiSoundsEnabled", value.uiSoundsEnabled)
        .put("animationsEnabled", value.animationsEnabled).put("showNavigationHints", value.showNavigationHints)
        .put("playerMode", value.playerMode.name).put("externalPlayer", value.externalPlayer)
        .put("builtInPlayer", JSONObject(value.builtInPlayer.encode()))
        .put("torrentSearch", JSONObject(tv.torrframe.core.storage.TorrentSearchSettingsCodec.encode(value.torrentSearch)))
        .put("catalogNetwork", JSONObject(value.catalogNetwork.encode()).put("cubTokenConfigured", value.catalogNetwork.cubToken.isNotBlank()))
        .put("lanAccess", value.lanAccess).put("useLocalServer", value.useLocalServer).put("port", value.port).put("startOnBoot", value.startOnBoot)
    fun library(items: List<LibraryItem>) = JSONArray(items.groupedLibrary().map { item ->
        JSONObject().put("hash", item.hash).put("title", item.title).put("sourceTitle", item.sourceTitle)
            .put("torrentHashes", JSONArray(item.torrentHashes)).put("addedAt", item.addedAt).put("metadata", item.metadata?.json() ?: JSONObject.NULL)
            .put("position", item.position).put("duration", item.duration).put("lastFileId", item.lastFileId)
            .put("files", JSONArray(item.files.filter { it.isVideo }.map { it.json() }))
    })
    fun playback(value: PlaybackSnapshot) = JSONObject().put("active", value.active).put("identity", value.identity)
        .put("title", value.title).put("playing", value.playing).put("playWhenReady", value.playWhenReady).put("ended", value.ended).put("seekable", value.seekable).put("buffering", value.buffering)
        .put("position", value.position).put("duration", value.duration).put("error", value.error)
        .put("tracks", JSONArray(value.tracks.map { JSONObject().put("id", it.id).put("type", it.type).put("label", it.label).put("selected", it.selected) }))
    fun server(graph: AppGraph): JSONObject {
        val local = graph.settings.state.value.useLocalServer
        val process = graph.server.state.value
        return JSONObject().put("local", local).put("connection", graph.connection.state.value.status.name)
            .put("version", graph.connection.state.value.version)
            .put("process", if (!local) "External" else when (process) {
                ServerState.Stopping -> "Stopping"; ServerState.Stopped -> "Stopped"; ServerState.Starting -> "Starting"; is ServerState.Running -> "Running"; is ServerState.Error -> "Error"
            }).put("error", if (local && process is ServerState.Error) process.message else "")
    }
    fun engine(graph: AppGraph, version: String, json: JSONObject) = JSONObject().put("address", graph.settings.state.value.torrServerUrl)
        .put("version", version).put("canReset", version == "MatriX.146.1").put("fields", JSONArray(EngineSettings.fields.filter { it.supported(json) }.map { field ->
            JSONObject().put("key", field.key).put("label", graph.app.uiString(field.label)).put("hint", graph.app.uiString(field.hint))
                .put("labelRu", graph.app.withUiLanguage("ru").getString(field.label)).put("labelEn", graph.app.withUiLanguage("en").getString(field.label))
                .put("hintRu", graph.app.withUiLanguage("ru").getString(field.hint)).put("hintEn", graph.app.withUiLanguage("en").getString(field.hint))
                .put("value", field.display(json)).put("boolean", field.boolean).put("min", field.minimum).put("max", field.maximum)
        }))
    fun updates(graph: AppGraph): JSONObject {
        val value = graph.updates.state.value
        return JSONObject().put("busy", value.busy).put("message", value.message).put("serverVersion", value.serverVersion)
            .put("availableVersion", value.asset?.version ?: "").put("downloaded", value.downloaded != null)
    }
    fun device() = JSONObject().put("name", Build.MODEL ?: "Android TV").put("appVersion", BuildConfig.VERSION_NAME)
}
