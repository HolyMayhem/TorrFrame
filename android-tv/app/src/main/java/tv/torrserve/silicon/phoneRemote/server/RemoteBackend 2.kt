package tv.torrserve.silicon.phoneRemote.server

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import tv.torrserve.silicon.phoneRemote.security.RemoteSession

interface RemoteBackend {
    val events: Flow<String>
    val epoch: String
    val sequence: Long
    suspend fun snapshot(session: RemoteSession): JSONObject
    suspend fun command(session: RemoteSession, request: JSONObject): JSONObject
    suspend fun operation(session: RemoteSession, id: String): JSONObject?
    suspend fun close()
}
interface WebAssets { suspend fun read(path: String): ByteArray? }
