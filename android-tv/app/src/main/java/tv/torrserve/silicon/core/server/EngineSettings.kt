package tv.torrserve.silicon.core.server

import org.json.JSONObject
import tv.torrserve.silicon.R

/** Fields and units verified against YouROK/TorrServer tag MatriX.146.1. */
data class EngineField(val key: String, val label: Int, val hint: Int, val minimum: Long = 0,
    val maximum: Long = 0, val scale: Long = 1, val boolean: Boolean = false) {
    fun supported(json: JSONObject): Boolean = if (boolean) json.opt(key) is Boolean else json.opt(key) is Number
    fun display(json: JSONObject): String = if (boolean) json.getBoolean(key).toString()
        else java.math.BigDecimal(json.getLong(key)).divide(java.math.BigDecimal(scale)).stripTrailingZeros().toPlainString()
    fun parse(text: String): Any {
        if (boolean) { require(text == "true" || text == "false"); return text.toBoolean() }
        val raw = text.trim().replace(',', '.').toBigDecimalOrNull() ?: error("Некорректное число.")
        require(raw >= minimum.toBigDecimal() && raw <= maximum.toBigDecimal()) { "Допустимый диапазон: $minimum–$maximum." }
        return raw.multiply(scale.toBigDecimal()).longValueExact()
    }
}
object EngineSettings {
    val fields = listOf(
        EngineField("CacheSize", R.string.engine_cache, R.string.engine_cache_hint, 16, 4096, 1024L * 1024),
        EngineField("ReaderReadAHead", R.string.engine_read_ahead, R.string.engine_read_ahead_hint, 5, 100),
        EngineField("PreloadCache", R.string.engine_preload, R.string.engine_preload_hint, 0, 100),
        EngineField("DownloadRateLimit", R.string.engine_download, R.string.engine_rate_hint, 0, 1_000_000),
        EngineField("UploadRateLimit", R.string.engine_upload, R.string.engine_rate_hint, 0, 1_000_000),
        EngineField("ConnectionsLimit", R.string.engine_connections, R.string.engine_connections_hint, 1, 1000),
        EngineField("TorrentDisconnectTimeout", R.string.engine_disconnect, R.string.engine_disconnect_hint, 1, 86400),
        EngineField("PeersListenPort", R.string.engine_port, R.string.engine_port_hint, 0, 65535),
        EngineField("RetrackersMode", R.string.engine_retrackers, R.string.engine_retrackers_hint, 0, 3),
        EngineField("ResponsiveMode", R.string.engine_responsive, R.string.engine_boolean_hint, boolean = true),
        EngineField("ForceEncrypt", R.string.engine_encrypt, R.string.engine_boolean_hint, boolean = true),
        EngineField("EnableIPv6", R.string.engine_ipv6, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisableTCP", R.string.engine_tcp, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisableUTP", R.string.engine_utp, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisableUPNP", R.string.engine_upnp, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisableDHT", R.string.engine_dht, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisablePEX", R.string.engine_pex, R.string.engine_boolean_hint, boolean = true),
        EngineField("DisableUpload", R.string.engine_no_upload, R.string.engine_boolean_hint, boolean = true),
        EngineField("EnableLPD", R.string.engine_lpd, R.string.engine_boolean_hint, boolean = true)
    )
    fun merge(current: JSONObject, edits: Map<String, String>): JSONObject {
        val next = JSONObject(current.toString())
        edits.forEach { (key, value) ->
            val field = fields.firstOrNull { it.key == key } ?: error("Неподдерживаемый параметр.")
            check(field.supported(current)) { "Сервер больше не поддерживает этот параметр." }
            next.put(key, field.parse(value))
        }
        require(!(next.optBoolean("DisableTCP") && next.optBoolean("DisableUTP"))) { "Нельзя одновременно отключить TCP и uTP." }
        return next
    }
    fun verify(expected: JSONObject, actual: JSONObject, keys: Set<String>) {
        check(keys.all { key ->
            val field = fields.first { it.key == key }
            field.supported(actual) && field.display(actual) == field.display(expected)
        }) { "Сервер не подтвердил сохранение. Загрузите актуальные значения повторно." }
    }
    val defaults = mapOf("CacheSize" to "64", "ReaderReadAHead" to "95", "PreloadCache" to "50",
        "ConnectionsLimit" to "25", "RetrackersMode" to "1", "TorrentDisconnectTimeout" to "30",
        "DownloadRateLimit" to "0", "UploadRateLimit" to "0", "PeersListenPort" to "0",
        "ResponsiveMode" to "true", "ForceEncrypt" to "false", "EnableIPv6" to "false",
        "DisableTCP" to "false", "DisableUTP" to "false", "DisableUPNP" to "false",
        "DisableDHT" to "false", "DisablePEX" to "false", "DisableUpload" to "false", "EnableLPD" to "true")
}
