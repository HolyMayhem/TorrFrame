package tv.torrframe.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONObject
import tv.torrframe.BuildConfig
import java.time.LocalDate
import java.util.Locale

enum class TelemetryFeature(val field: String) {
    BuiltinTorrServer("builtinTorrServer"), ExternalTorrServer("externalTorrServer"),
    BuiltinPlayer("builtinPlayer"), ExternalPlayer("externalPlayer"), PhoneRemote("phoneRemote"),
    TorrentSearch("torrentSearch"), Jackett("jackett"), Tmdb("tmdb"), Cub("cub"), Afr("afr"),
    Favorites("favorites"), WatchLater("watchLater");
    val bit get() = 1 shl ordinal
}

internal data class DeviceSnapshot(
    val api: Int, val manufacturer: String, val model: String, val architecture: String,
    val ramBucket: String, val deviceType: String
) {
    companion object {
        fun read(context: Context): DeviceSnapshot {
            val ram = runCatching {
                ActivityManager.MemoryInfo().apply { context.getSystemService(ActivityManager::class.java).getMemoryInfo(this) }.totalMem
            }.getOrDefault(0)
            val type = runCatching {
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) "Android TV" else "Other"
            }.getOrDefault("Unknown")
            val is64 = Process.is64Bit()
            val supported = if (is64) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            val abi = (if (is64) "arm64-v8a" else "armeabi-v7a").takeIf { it in supported } ?: "Unknown"
            return DeviceSnapshot(Build.VERSION.SDK_INT, manufacturer(Build.MANUFACTURER), model(Build.MODEL), abi, memory(ram), type)
        }

        fun manufacturer(raw: String?): String = tag(raw, mapOf(
            "xiaomi" to "Xiaomi", "google" to "Google", "nvidia" to "NVIDIA", "sony" to "Sony", "tcl" to "TCL",
            "hisense" to "Hisense", "philips" to "Philips", "amazon" to "Amazon", "onn" to "onn.",
            "onn." to "onn.", "mecool" to "Mecool", "homatics" to "Homatics"))
        fun model(raw: String?): String = tag(raw, mapOf(
            "mibox4" to "Mi Box S", "mi box s" to "Mi Box S", "mibox4s" to "Mi Box S", "mi tv stick" to "Mi TV Stick",
            "xiaomi tv box s" to "Xiaomi TV Box S", "chromecast" to "Chromecast with Google TV",
            "google tv streamer" to "Google TV Streamer", "shield android tv" to "SHIELD TV", "shield tv" to "SHIELD TV",
            "shield tv pro" to "SHIELD TV Pro", "sdk_google_atv64_arm64" to "Android TV Emulator"))
        private fun tag(value: String?, allowlist: Map<String, String>): String =
            when (val key = value?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "unknown" -> "Unknown"
                else -> allowlist[key] ?: "Other"
            }
        fun memory(bytes: Long): String {
            val gib = 1L shl 30
            return when {
                bytes <= 0 -> "Unknown"
                bytes < gib -> "lt1"
                bytes < 2 * gib -> "1to2"
                bytes < 3 * gib -> "2to3"
                bytes < 4 * gib -> "3to4"
                bytes < 6 * gib -> "4to6"
                bytes < 8 * gib -> "6to8"
                else -> "gte8"
            }
        }
    }
}

internal data class UsageReport(val date: LocalDate, val features: Int) {
    fun json(): JSONObject = JSONObject().put("date", date.toString()).put("features", JSONObject().apply {
        TelemetryFeature.entries.forEach { put(it.field, features and it.bit != 0) }
    })
}

internal data class TelemetryPayload(
    val installationID: String?, val device: DeviceSnapshot, val language: String, val theme: String,
    val usage: UsageReport?
) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("installationID", installationID ?: JSONObject.NULL)
        .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
        .put("androidApiLevel", device.api).put("manufacturer", device.manufacturer).put("deviceModel", device.model)
        .put("cpuArchitecture", device.architecture).put("ramBucket", device.ramBucket).put("deviceType", device.deviceType)
        .put("interfaceLanguage", if (language == "ru") "ru" else "en")
        .put("theme", if (theme == "light") "light" else "dark")
        .apply { usage?.let { put("usage", it.json()) } }
}
