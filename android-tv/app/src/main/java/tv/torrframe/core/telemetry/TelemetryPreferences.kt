package tv.torrframe.core.telemetry

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate

enum class TelemetryConsent { Loading, Unasked, Enabled, Disabled }

internal data class TelemetryState(
    val consent: TelemetryConsent = TelemetryConsent.Unasked,
    val offered: Boolean = false,
    val id: String? = null,
    val days: Map<LocalDate, Int> = emptyMap(),
    val attemptDay: LocalDate? = null,
    val attemptWall: Long = 0,
    val attemptElapsed: Long = 0,
    val attemptCount: Int = 0,
    val successDay: LocalDate? = null,
    val successDeviceDay: LocalDate? = null,
    val retryAfter: Long = 0,
    val successWall: Long = 0
) {
    fun pending(today: LocalDate): UsageReport? =
        days.entries.filter { it.key < today && it.key >= today.minusDays(7) }.minByOrNull { it.key }
            ?.let { UsageReport(it.key, it.value) }
}

internal interface TelemetryPersistence {
    fun read(): TelemetryState
    fun write(state: TelemetryState)
}

internal class TelemetryPreferences(context: Context, debug: Boolean) : TelemetryPersistence {
    // noBackupFilesDir excludes both cloud backups and device-to-device transfer.
    private val file = AtomicFile(File(context.noBackupFilesDir, if (debug) "telemetry-debug.json" else "telemetry-production.json"))

    override fun read(): TelemetryState {
        if (!file.baseFile.exists()) return TelemetryState()
        return try {
            val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val choice = TelemetryConsent.entries.firstOrNull { it.name == root.optString("choice") }
                ?.takeIf { it != TelemetryConsent.Loading } ?: TelemetryConsent.Unasked
            val id = root.optString("id").takeIf {
                Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(it)
            }
            if (choice != TelemetryConsent.Enabled || id == null) return TelemetryState(
                if (choice == TelemetryConsent.Enabled) TelemetryConsent.Disabled else choice, root.optBoolean("offered"))
            val history = root.optJSONObject("days") ?: JSONObject()
            fun date(key: String) = runCatching { LocalDate.parse(root.getString(key)) }.getOrNull()
            val days = history.keys().asSequence().mapNotNull { key ->
                runCatching { LocalDate.parse(key) to history.getInt(key).coerceIn(0, (1 shl TelemetryFeature.entries.size) - 1) }.getOrNull()
            }.sortedByDescending { it.first }.take(8).toMap()
            TelemetryState(choice, root.optBoolean("offered"), id, days, date("attemptDay"),
                root.optLong("attemptWall"), root.optLong("attemptElapsed"), root.optInt("attemptCount").coerceIn(0,3),
                date("successDay"), date("successDeviceDay"), root.optLong("retryAfter").coerceIn(0,86_400_000), root.optLong("successWall"))
        } catch (_: Exception) {
            TelemetryState(consent = TelemetryConsent.Disabled, offered = true)
        }
    }

    override fun write(state: TelemetryState) {
        val data = JSONObject().put("choice", state.consent.name).put("offered", state.offered).apply {
            if (state.consent == TelemetryConsent.Enabled) {
                put("id", state.id)
                put("days", JSONObject().apply { state.days.forEach { (day, mask) -> put(day.toString(), mask) } })
                put("attemptDay", state.attemptDay?.toString()).put("attemptWall", state.attemptWall)
                put("attemptElapsed", state.attemptElapsed).put("attemptCount", state.attemptCount)
                put("successDay", state.successDay?.toString()).put("successDeviceDay", state.successDeviceDay?.toString())
                put("retryAfter", state.retryAfter).put("successWall", state.successWall)
            }
        }.toString().toByteArray()
        try {
            val stream = file.startWrite()
            try {
                stream.write(data)
                file.finishWrite(stream)
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: IOException) {
            if (state.consent == TelemetryConsent.Disabled) file.delete()
            throw e
        }
    }
}
