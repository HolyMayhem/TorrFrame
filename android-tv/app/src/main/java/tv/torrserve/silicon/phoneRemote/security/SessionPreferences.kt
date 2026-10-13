package tv.torrserve.silicon.phoneRemote.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Stores SHA-256 fingerprints, never reusable session cookie secrets. */
class SessionPreferences(context: Context) : SessionPersistence {
    private val prefs = context.getSharedPreferences("phone-remote-sessions", Context.MODE_PRIVATE)
    override suspend fun read(): List<RemoteSession> = withContext(Dispatchers.IO) {
        val json = JSONArray(prefs.getString("sessions", "[]"))
        (0 until json.length()).map { json.getJSONObject(it).let { j ->
            RemoteSession(j.getString("id"), j.getString("hash"), j.getString("csrf"), j.getString("label"), j.getLong("created"), j.getLong("expires"))
        } }
    }
    override suspend fun write(sessions: List<RemoteSession>) = withContext(Dispatchers.IO) {
        val json = JSONArray(sessions.map { JSONObject().put("id", it.id).put("hash", it.secretHash).put("csrf", it.csrf)
            .put("label", it.label).put("created", it.createdAt).put("expires", it.expiresAt) })
        check(prefs.edit().putString("sessions", json.toString()).commit())
    }
}
