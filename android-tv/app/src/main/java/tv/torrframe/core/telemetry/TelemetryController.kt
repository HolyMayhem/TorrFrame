package tv.torrframe.core.telemetry

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.torrframe.BuildConfig
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal data class TelemetryTime(val wall: Long, val elapsed: Long) {
    val day: LocalDate get() = Instant.ofEpochMilli(wall).atOffset(ZoneOffset.UTC).toLocalDate()
}

internal fun TelemetryState.mayAttempt(time: TelemetryTime): Boolean {
    if (successDay == time.day || successDeviceDay == time.day || attemptDay == time.day && attemptCount >= 3) return false
    if (attemptWall == 0L) return true
    val interval = if (time.elapsed >= attemptElapsed) time.elapsed - attemptElapsed
        else if (time.wall >= attemptWall) time.wall - attemptWall else time.elapsed
    val delay = when {
        successDeviceDay == attemptDay -> 60 * 60_000L
        attemptCount >= 3 -> 4 * 60 * 60_000L
        attemptCount == 2 -> 60 * 60_000L
        else -> 15 * 60_000L
    }
    return interval >= maxOf(delay, retryAfter)
}

/** Uses the host's main scope for lifecycle, and one serialized private store for disk state. */
class TelemetryController internal constructor(
    private val scope: CoroutineScope, private val preferences: TelemetryPersistence,
    private val transport: TelemetryTransport?, private val snapshot: () -> DeviceSnapshot,
    private val appearance: () -> Pair<String, String>,
    private val clock: () -> TelemetryTime = { TelemetryTime(System.currentTimeMillis(), SystemClock.elapsedRealtime()) },
    private val delayMillis: Long = 12_000,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private var state = TelemetryState(consent = TelemetryConsent.Loading)
    private var visible = false
    private var schedule: Job? = null
    @Volatile private var generation = 0L
    private val mutable = MutableStateFlow(TelemetryConsent.Loading)
    val consent = mutable.asStateFlow()

    constructor(context: Context, scope: CoroutineScope, appearance: () -> Pair<String, String>) : this(
        scope, TelemetryPreferences(context, BuildConfig.DEBUG),
        telemetryEndpoint(BuildConfig.TELEMETRY_ENDPOINT, BuildConfig.DEBUG)?.let(::HttpTelemetryTransport),
        { DeviceSnapshot.read(context) }, appearance)

    suspend fun initialize() {
        mutex.withLock {
            if (state.consent != TelemetryConsent.Loading) return
            state = withContext(io) { preferences.read() }
            mutable.value = state.consent
        }
        scheduleForeground()
    }

    fun foreground(value: Boolean) {
        visible = value
        if (!value) { schedule?.cancel(); schedule = null }
        else scheduleForeground()
    }

    suspend fun choose(enabled: Boolean): Boolean {
        if (mutable.value == TelemetryConsent.Loading) return false
        if (!enabled) mutable.value = TelemetryConsent.Disabled
        val revision = ++generation
        schedule?.cancel(); schedule = null
        val saved = mutex.withLock {
            if (revision != generation) return@withLock false
            val next = if (enabled) {
                if (state.consent == TelemetryConsent.Enabled) state
                else TelemetryState(TelemetryConsent.Enabled, true, UUID.randomUUID().toString())
            } else TelemetryState(TelemetryConsent.Disabled, true)
            val stored = save(next)
            if (!enabled || stored) {
                state = next
                if (revision == generation) mutable.value = next.consent
            }
            stored
        }
        if (revision == generation) scheduleForeground()
        return saved
    }

    internal fun changeInAppScope(operation: suspend () -> Boolean, result: (Boolean) -> Unit) {
        scope.launch { result(operation()) }
    }

    suspend fun reset(): Boolean {
        if (mutable.value != TelemetryConsent.Enabled) return false
        val revision = ++generation
        schedule?.cancel(); schedule = null
        val saved = mutex.withLock {
            if (revision != generation || mutable.value != TelemetryConsent.Enabled) return@withLock false
            val next = TelemetryState(TelemetryConsent.Enabled, true, UUID.randomUUID().toString(),
                attemptWall = state.attemptWall, attemptElapsed = state.attemptElapsed,
                attemptCount = state.attemptCount, attemptDay = state.attemptDay,
                retryAfter = state.retryAfter)
            save(next).also { if (it) state = next }
        }
        if (revision == generation) scheduleForeground()
        return saved
    }

    /** The upgrade invitation is recorded before display so it appears only once. */
    suspend fun claimOffer(): Boolean = mutex.withLock {
        if (state.consent != TelemetryConsent.Unasked || state.offered) return@withLock false
        val next = state.copy(offered = true)
        save(next).also { if (it) state = next }
    }

    fun record(feature: TelemetryFeature) {
        if (mutable.value != TelemetryConsent.Enabled) return
        val revision = generation
        scope.launch {
            mutex.withLock {
                if (revision != generation || mutable.value != TelemetryConsent.Enabled) return@withLock
                noteDay(clock().day, feature.bit)
            }
        }
    }

    suspend fun preview(): String {
        val current = mutex.withLock {
            if (mutable.value == TelemetryConsent.Enabled) state else TelemetryState()
        }
        val device = withContext(io) { snapshot() }
        val (language, theme) = appearance()
        return TelemetryPayload(current.id, device, language, theme, current.pending(clock().day)).json().toString(2)
    }

    private fun scheduleForeground() {
        if (!visible || mutable.value != TelemetryConsent.Enabled || schedule?.isActive == true) return
        schedule = scope.launch {
            mutex.withLock { if (mutable.value == TelemetryConsent.Enabled) noteDay(clock().day, 0) }
            delay(delayMillis)
            while (isActive && visible && mutable.value == TelemetryConsent.Enabled) {
                heartbeat()
                delay(5 * 60_000L)
            }
        }
    }

    private suspend fun heartbeat() {
        val activeTransport = transport
        val revision = generation
        val time = clock()
        val ticket = mutex.withLock {
            if (!visible || mutable.value != TelemetryConsent.Enabled || state.id == null) return
            noteDay(time.day, 0)
            if (activeTransport == null || !state.mayAttempt(time)) return
            val next = state.copy(attemptDay = time.day, attemptElapsed = time.elapsed, attemptWall = time.wall,
                attemptCount = if (state.attemptDay == time.day) state.attemptCount + 1 else 1, retryAfter = 0)
            if (!save(next)) return
            state = next
            requireNotNull(next.id) to next.pending(time.day)
        }
        val device = withContext(io) { snapshot() }
        val (language, theme) = appearance()
        val result = try {
            checkNotNull(activeTransport).send(TelemetryPayload(ticket.first, device, language, theme, ticket.second))
        } catch (_: IOException) { TelemetryReceipt(null) }
        mutex.withLock {
            if (revision != generation || mutable.value != TelemetryConsent.Enabled || !visible) return
            val next = if (result.day != null) state.copy(successDay = result.day, successDeviceDay = time.day, successWall = clock().wall,
                days = if (result.usage != null && result.usage == ticket.second?.date) state.days - result.usage else state.days)
            else state.copy(retryAfter = result.retryMillis)
            if (save(next)) state = next
        }
    }

    private suspend fun noteDay(today: LocalDate, mask: Int) {
        val days = state.days.filterKeys { it >= today.minusDays(7) && it <= today }.toMutableMap()
        days[today] = (days[today] ?: 0) or mask
        val next = state.copy(days = days)
        if (next != state && save(next)) state = next
    }

    private suspend fun save(next: TelemetryState) = withContext(io) {
        try { preferences.write(next); true } catch (_: IOException) { false }
    }
}
