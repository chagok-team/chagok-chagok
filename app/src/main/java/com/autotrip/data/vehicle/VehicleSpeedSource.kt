package com.autotrip.data.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.autotrip.data.flow.recoverCompletedSession
import com.autotrip.data.flow.receiveOrTimeout
import com.autotrip.data.flow.SessionRead

interface VehicleSpeedSource {
    /** No replay. Consumers must also check timestamp freshness when using a sample later. */
    val events: Flow<VehicleSpeedEvent>
}

sealed interface VehicleSpeedEvent {
    data class Sample(
        /** Absolute speed, including reverse travel; unit remains m/s. */
        val metersPerSecond: Float,
        /** Vehicle timestamp in the elapsedRealtimeNanos time base. */
        val timestampNanos: Long,
        val receivedAtNanos: Long,
    ) : VehicleSpeedEvent
    data class Unavailable(val reason: SpeedUnavailableReason) : VehicleSpeedEvent
}

enum class SpeedUnavailableReason {
    PERMISSION_DENIED, UNSUPPORTED, CONNECTION_FAILED, CONNECTION_TIMEOUT,
    DISCONNECTED, SDK_ERROR, INVALID_VALUE, INVALID_TIMESTAMP, OUT_OF_ORDER, DUPLICATE, STALE,
}

internal data class RawVehicleSpeed(val value: Float?, val timestampNanos: Long, val available: Boolean = true)
internal interface VehicleSpeedSession {
    suspend fun connect(onDisconnected: () -> Unit)
    fun isSupported(): Boolean
    fun current(): RawVehicleSpeed
    fun subscribe(onValue: (RawVehicleSpeed) -> Unit, onError: () -> Unit)
    fun close()
}

internal class ValidatedVehicleSpeedSource(
    scope: CoroutineScope,
    private val hasPermission: () -> Boolean,
    private val newSession: () -> VehicleSpeedSession,
    private val elapsedNanos: () -> Long,
    private val maxAgeMillis: Long = 2_000,
    private val connectionTimeoutMillis: Long = 10_000,
    private val reportError: (Exception) -> Unit = {},
) : VehicleSpeedSource {
    init {
        require(maxAgeMillis in 1..(Long.MAX_VALUE / 1_000_000))
        require(connectionTimeoutMillis > 0)
    }
    private sealed interface Signal {
        data class Value(val raw: RawVehicleSpeed) : Signal
        data class Failure(val reason: SpeedUnavailableReason) : Signal
    }

    override val events: Flow<VehicleSpeedEvent> = flow {
        var session: VehicleSpeedSession? = null
        var connected = false
        val incoming = Channel<Signal>(64)
        var lastTimestamp = 0L
        var expiresAt: Long? = null
        fun enqueue(signal: Signal) {
            // Stop the stream rather than silently losing a disconnect/error on overflow.
            if (incoming.trySend(signal).isFailure) incoming.close(IllegalStateException("Speed callback buffer full/closed"))
        }
        try {
            if (!hasPermission()) {
                emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.PERMISSION_DENIED))
                return@flow
            }
            val opened = newSession()
            session = opened
            if (withTimeoutOrNull(connectionTimeoutMillis) {
                    opened.connect { enqueue(Signal.Failure(SpeedUnavailableReason.DISCONNECTED)) }
                } == null) {
                emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.CONNECTION_TIMEOUT))
                return@flow
            }
            connected = true
            if (!hasPermission()) throw SecurityException("CAR_SPEED revoked")
            if (!opened.isSupported()) {
                emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.UNSUPPORTED))
                return@flow
            }
            enqueue(Signal.Value(opened.current()))
            opened.subscribe({ enqueue(Signal.Value(it)) }, { enqueue(Signal.Failure(SpeedUnavailableReason.SDK_ERROR)) })
            while (true) {
                val deadline = expiresAt
                val received = if (deadline == null) incoming.receiveOrTimeout(1_000) else {
                    val remaining = deadline - elapsedNanos()
                    if (remaining <= 0) SessionRead.Timeout else incoming.receiveOrTimeout((remaining / 1_000_000).coerceAtLeast(1))
                }
                val signal = (received as? SessionRead.Value)?.value
                if (!hasPermission()) {
                    emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.PERMISSION_DENIED))
                    return@flow
                }
                if (signal == null) {
                    expiresAt = null
                    emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.STALE))
                    continue
                }
                if (signal is Signal.Failure) {
                    emit(VehicleSpeedEvent.Unavailable(signal.reason))
                    return@flow
                }
                val raw = (signal as Signal.Value).raw
                val now = elapsedNanos()
                val ts = raw.timestampNanos
                val ageLimit = maxAgeMillis * 1_000_000
                val rejection = when {
                    !raw.available -> SpeedUnavailableReason.SDK_ERROR
                    raw.value == null || !raw.value.isFinite() -> SpeedUnavailableReason.INVALID_VALUE
                    ts <= 0 || ts > now -> SpeedUnavailableReason.INVALID_TIMESTAMP
                    now - ts >= ageLimit -> SpeedUnavailableReason.STALE
                    ts < lastTimestamp -> SpeedUnavailableReason.OUT_OF_ORDER
                    ts == lastTimestamp -> SpeedUnavailableReason.DUPLICATE
                    else -> null
                }
                if (rejection != null) {
                    expiresAt = null
                    emit(VehicleSpeedEvent.Unavailable(rejection))
                } else {
                    lastTimestamp = ts
                    // Saturating addition only for the deadline, never for a speed measurement.
                    expiresAt = if (ts > Long.MAX_VALUE - ageLimit) Long.MAX_VALUE else ts + ageLimit
                    emit(VehicleSpeedEvent.Sample(abs(raw.value!!), ts, now))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            reportError(error)
            emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.PERMISSION_DENIED))
        } catch (error: Exception) {
            reportError(error)
            emit(VehicleSpeedEvent.Unavailable(if (!connected) SpeedUnavailableReason.CONNECTION_FAILED else SpeedUnavailableReason.SDK_ERROR))
        } finally {
            incoming.close()
            try { session?.close() } catch (error: Exception) { reportError(error) }
        }
    }.recoverCompletedSession().buffer(0).shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 0)
}
