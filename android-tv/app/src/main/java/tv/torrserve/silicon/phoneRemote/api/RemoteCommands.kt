package tv.torrserve.silicon.phoneRemote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.models.MediaKind
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.MetadataPriority
import tv.torrserve.silicon.features.metadata.MetadataMatch
import tv.torrserve.silicon.features.search.SearchResult
import tv.torrserve.silicon.phoneRemote.security.RemoteSession
import java.util.UUID

/** Session-scoped handles refer to actual provider responses, not client-supplied download URLs. */
class RemoteCommands(private val graph: AppGraph) {
    private data class Handle<T>(val owner: String, val value: T, val expires: Long)
    private val torrents = LinkedHashMap<String, Handle<SearchResult>>()
    private val matches = LinkedHashMap<String, Handle<MetadataMatch>>()
    @Synchronized private fun <T> register(map: LinkedHashMap<String, Handle<T>>, session: RemoteSession, value: T): String {
        map.entries.removeAll { it.value.expires < System.currentTimeMillis() }
        while (map.size >= 400) map.remove(map.keys.first())
        val id = UUID.randomUUID().toString(); map[id] = Handle(session.id, value, System.currentTimeMillis() + 600_000); return id
    }
    @Synchronized private fun <T> get(map: LinkedHashMap<String, Handle<T>>, session: RemoteSession, id: String): T {
        return map[id]?.takeIf { it.owner == session.id && it.expires > System.currentTimeMillis() }?.value ?: throw ApplicationActionFailure(R.string.phone_result_expired)
    }
    private fun hash(args: JSONObject) = args.getString("hash").also { require(it.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) }
    private fun confirmed(args: JSONObject) { require(args.optBoolean("confirmed")) }
    private fun value(args: JSONObject, key: String, limit: Int = 250) = args.getString(key).also { require(it.length <= limit) }
    suspend fun execute(session: RemoteSession, type: String, args: JSONObject): JSONObject {
        graph.ready.await()
        return when (type) {
            "search" -> {
                val query = value(args, "query"); require(query.isNotBlank())
                val filter = args.optString("filter", "All"); require(filter in listOf("All", "Movies", "TV Shows"))
                val page = graph.jackett.search(graph.settings.state.value, query, filter)
                val existing = graph.store.list().associateBy { it.hash }
                JSONObject().put("partial", page.partial).put("items", JSONArray(page.results.map { result ->
                    JSONObject().put("handle", register(torrents, session, result)).put("title", result.title)
                        .put("size", result.size).put("seeds", result.seeds).put("peers", result.peers).put("tracker", result.tracker)
                        .put("metadata", existing[result.hash]?.metadata?.json() ?: JSONObject.NULL)
                }))
            }
            "library.add" -> JSONObject().put("hash", graph.library.add(get(torrents, session, value(args, "handle"))))
            "library.sync" -> { graph.library.sync(); JSONObject() }
            "library.remove" -> { confirmed(args); graph.library.remove(hash(args)); JSONObject() }
            "library.files" -> {
                val hash = hash(args); val files = graph.library.files(hash)
                JSONObject().put("files", JSONArray(files.map { it.json() })).put("preferredId", tv.torrserve.silicon.features.player.preferredVideo(graph.store.item(hash), files)?.id ?: JSONObject.NULL)
            }
            "library.play" -> {
                if (!graph.server.foreground) throw ApplicationActionFailure(R.string.phone_open_tv)
                val hash = hash(args); val files = graph.library.files(hash)
                val file = files.firstOrNull { it.id == args.getInt("fileId") } ?: error("No such file")
                graph.playback.open(graph.api.stream(hash, file), "$hash:${file.id}", graph.store.item(hash)?.title ?: file.path)
                JSONObject()
            }
            "metadata.refresh" -> JSONObject().put("found", graph.library.refreshMetadata(hash(args), force = true))
            "metadata.reset" -> { confirmed(args); graph.metadataEditor.reset(hash(args)); JSONObject() }
            "metadata.rename" -> { graph.metadataEditor.rename(hash(args), value(args, "title")); JSONObject() }
            "metadata.search" -> {
                val provider = value(args, "provider"); val kind = MediaKind.valueOf(args.optString("kind", "Movie"))
                val options = graph.metadataEditor.search(value(args, "query"), provider, kind)
                JSONObject().put("items", JSONArray(options.map { JSONObject().put("handle", register(matches, session, it)).put("metadata", it.value.json()) }))
            }
            "metadata.select" -> { graph.metadataEditor.select(hash(args), get(matches, session, value(args, "handle"))); JSONObject() }
            "settings.update" -> { updateSettings(args); JSONObject() }
            "settings.testJackett" -> { graph.jackett.test(graph.settings.state.value); JSONObject() }
            "settings.clearMetadataCache" -> { confirmed(args); graph.store.clearCache(); JSONObject() }
            "server.connect" -> {
                val local = args.getBoolean("local"); val port = args.optInt("port", graph.settings.state.value.port)
                val address = if (local) ServerAddress.local(port) else value(args, "address", 4096)
                JSONObject().put("message", graph.app.getString(graph.serverActions.connect(address, local, port, args.getLong("revision"))))
            }
            "server.boot" -> { graph.serverActions.boot(args.getBoolean("enabled"), args.getLong("revision")); JSONObject() }
            "server.start" -> { graph.serverActions.start(); JSONObject() }
            "server.stop" -> { confirmed(args); graph.serverActions.stop(); JSONObject() }
            "server.restart" -> { confirmed(args); graph.serverActions.restart(); JSONObject() }
            "server.check" -> { graph.connection.check(); JSONObject() }
            "server.diagnose" -> {
                val value = graph.connection.diagnose(graph.settings.state.value.torrServerUrl)
                JSONObject().put("latencyMs", value.latencyMs).put("speedMbps", value.megabitsPerSecond ?: JSONObject.NULL)
                    .put("bytes", value.bytes ?: JSONObject.NULL).put("failure", value.bandwidthFailure ?: JSONObject.NULL)
            }
            "engine.get" -> graph.serverActions.loadEngine().let { RemoteJson.engine(graph, it.first, it.second) }
            "engine.save" -> {
                confirmed(args)
                val json = args.getJSONObject("edits"); val edits = json.keys().asSequence().associateWith { json.getString(it) }
                val original = args.getJSONObject("base"); val base = original.keys().asSequence().associateWith { original.getString(it) }
                require(base.keys == edits.keys)
                val result = graph.serverActions.saveEngine(value(args, "address", 4096), edits, base)
                JSONObject().put("message", graph.app.getString(result.message))
            }
            "engine.reset" -> { confirmed(args); graph.serverActions.resetEngine(value(args, "address", 4096)).let { JSONObject().put("message", graph.app.getString(it.message)) } }
            "playback.command" -> {
                val command = value(args, "command")
                if (command == "stop") confirmed(args)
                graph.playback.command(command, if (args.has("position")) args.getLong("position") else null, args.optString("track").takeIf { it.isNotEmpty() }, value(args, "identity", 100)); JSONObject()
            }
            "updates.server" -> update(graph.updates.server())
            "updates.check" -> update(graph.updates.check())
            "updates.download" -> update(graph.updates.download())
            "updates.install" -> withContext(Dispatchers.Main.immediate) {
                confirmed(args)
                if (!graph.server.foreground) throw ApplicationActionFailure(R.string.phone_open_tv)
                val intent = requireNotNull(graph.updates.installer())
                graph.app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); JSONObject().put("requiresTvConfirmation", true)
            }
            else -> error("Unsupported command")
        }
    }
    private suspend fun update(job: kotlinx.coroutines.Job?): JSONObject {
        check(job != null); job.join(); check(!graph.updates.state.value.failed)
        return RemoteJson.updates(graph)
    }
    private suspend fun updateSettings(args: JSONObject) {
        val expected = args.getLong("revision")
        val edits = args.getJSONObject("edits")
        require(edits.keys().asSequence().all { it in setOf("jackettUrl", "jackettKey", "metadataOrder", "aniListEnabled", "language", "resume", "audioLanguage", "subtitleLanguage", "updateRepository", "uiLanguage") })
        graph.settings.update(expected) { current ->
            val order = edits.optJSONArray("metadataOrder")?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: current.metadataOrder
            val language = edits.optString("language", current.language); require(language in listOf("en-US", "ru-RU"))
            val uiLanguage = edits.optString("uiLanguage", current.uiLanguage); require(uiLanguage in listOf("en", "ru"))
            val next = current.copy(jackettUrl = edits.optString("jackettUrl", current.jackettUrl).take(4096),
                jackettKey = edits.optString("jackettKey", current.jackettKey).take(4096), metadataOrder = MetadataPriority.normalized(order),
                aniListEnabled = edits.optBoolean("aniListEnabled", current.aniListEnabled), language = language,
                resume = edits.optBoolean("resume", current.resume), audioLanguage = edits.optString("audioLanguage", current.audioLanguage).take(40),
                subtitleLanguage = edits.optString("subtitleLanguage", current.subtitleLanguage).take(40), updateRepository = edits.optString("updateRepository", current.updateRepository).take(100), uiLanguage = uiLanguage)
            if (next.jackettUrl.isNotBlank() && next.jackettKey.isNotBlank()) graph.jackett.endpoint(next, "caps")
            next
        }
    }
    companion object { val supported = setOf("search", "library.add", "library.sync", "library.remove", "library.files", "library.play",
        "metadata.refresh", "metadata.reset", "metadata.rename", "metadata.search", "metadata.select", "settings.update", "settings.testJackett", "settings.clearMetadataCache",
        "server.connect", "server.boot", "server.start", "server.stop", "server.restart", "server.check", "server.diagnose", "engine.get", "engine.save", "engine.reset",
        "playback.command", "updates.server", "updates.check", "updates.download", "updates.install") }
}
