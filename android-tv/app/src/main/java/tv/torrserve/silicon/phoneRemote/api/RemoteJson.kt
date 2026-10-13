package tv.torrserve.silicon.phoneRemote.api

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.BuildConfig
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.player.PlaybackSnapshot

object RemoteJson {
    fun settings(value: Settings, revision: Long) = JSONObject().put("revision", revision)
        .put("jackettUrl", value.jackettUrl).put("jackettKeyConfigured", value.jackettKey.isNotBlank())
        .put("metadataOrder", JSONArray(value.metadataOrder)).put("aniListEnabled", value.aniListEnabled)
        .put("language", value.language).put("resume", value.resume).put("audioLanguage", value.audioLanguage)
        .put("subtitleLanguage", value.subtitleLanguage).put("uiLanguage", value.uiLanguage)
        .put("updateRepository", value.updateRepository).put("torrServerUrl", value.torrServerUrl)
        .put("useLocalServer", value.useLocalServer).put("port", value.port).put("startOnBoot", value.startOnBoot)
    fun library(items: List<LibraryItem>) = JSONArray(items.map { item ->
        JSONObject().put("hash", item.hash).put("title", item.title).put("sourceTitle", item.sourceTitle)
            .put("addedAt", item.addedAt).put("metadata", item.metadata?.json() ?: JSONObject.NULL)
            .put("position", item.position).put("duration", item.duration).put("lastFileId", item.lastFileId)
            .put("files", JSONArray(item.files.filter { it.isVideo }.map { it.json() }))
    })
    fun playback(value: PlaybackSnapshot) = JSONObject().put("active", value.active).put("identity", value.identity)
        .put("title", value.title).put("playing", value.playing).put("buffering", value.buffering)
        .put("position", value.position).put("duration", value.duration).put("error", value.error)
        .put("tracks", JSONArray(value.tracks.map { JSONObject().put("id", it.id).put("type", it.type).put("label", it.label).put("selected", it.selected) }))
    fun server(graph: AppGraph): JSONObject {
        val local = graph.settings.state.value.useLocalServer
        val process = graph.server.state.value
        return JSONObject().put("local", local).put("connection", graph.connection.state.value.status.name)
            .put("version", graph.connection.state.value.version)
            .put("process", if (!local) "External" else when (process) {
                ServerState.Stopped -> "Stopped"; ServerState.Starting -> "Starting"; is ServerState.Running -> "Running"; is ServerState.Error -> "Error"
            }).put("error", if (local && process is ServerState.Error) process.message else "")
    }
    fun engine(graph: AppGraph, version: String, json: JSONObject) = JSONObject().put("address", graph.settings.state.value.torrServerUrl)
        .put("version", version).put("canReset", version == "MatriX.146.1").put("fields", JSONArray(EngineSettings.fields.filter { it.supported(json) }.map { field ->
            JSONObject().put("key", field.key).put("label", graph.app.getString(field.label)).put("hint", graph.app.getString(field.hint))
                .put("value", field.display(json)).put("boolean", field.boolean).put("min", field.minimum).put("max", field.maximum)
        }))
    fun updates(graph: AppGraph): JSONObject {
        val value = graph.updates.state.value
        return JSONObject().put("busy", value.busy).put("message", value.message).put("serverVersion", value.serverVersion)
            .put("availableVersion", value.asset?.version ?: "").put("downloaded", value.downloaded != null)
    }
    fun device() = JSONObject().put("name", Build.MODEL ?: "Android TV").put("appVersion", BuildConfig.VERSION_NAME)
}
