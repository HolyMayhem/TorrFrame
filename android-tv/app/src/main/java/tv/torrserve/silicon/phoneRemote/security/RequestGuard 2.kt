package tv.torrserve.silicon.phoneRemote.security

class RequestGuard(val address: String, val port: Int) {
    val authority = "$address:$port"
    val origin = "http://$authority"
    fun peer(value: String): Boolean {
        val bytes = value.split('.').map { it.toIntOrNull() ?: return false }
        if (bytes.size != 4 || bytes.any { it !in 0..255 }) return false
        return bytes[0] == 10 || bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 172 && bytes[1] in 16..31)
    }
    fun host(host: String?) = host == authority
    fun origin(value: String?, required: Boolean) = if (value == null) !required else value == origin
}
