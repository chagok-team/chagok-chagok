package com.autotrip.data.location

import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.TripPlace
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.math.*
import com.autotrip.data.flow.recoverCompletedSession
import com.autotrip.data.flow.receiveOrTimeout
import com.autotrip.data.flow.SessionRead

interface TripLocationSource {
    val available: StateFlow<Boolean>
    suspend fun capture(): TripPlace?
    fun startJourney(tripId: Long, restored: Boolean = false) {}
    fun endJourney(tripId: Long) {}
}

object NoTripLocation : TripLocationSource {
    override val available = MutableStateFlow(false)
    override suspend fun capture(): TripPlace? = null
}

internal data class GpsFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?, val timestampNanos: Long)
internal data class GpsPolicy(
    val maxAgeMillis: Long = 10_000,
    val maxAccuracyMeters: Float = 50f,
    val maxSegmentGapMillis: Long = 30_000,
    val maxSpeedMetersPerSecond: Double = 70.0,
    val maxPathPoints: Int = 256,
    val captureTimeoutMillis: Long = 2_000,
) {
    init {
        require(maxAgeMillis in 1..Long.MAX_VALUE / 1_000_000)
        require(maxSegmentGapMillis in 1..Long.MAX_VALUE / 1_000_000)
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(maxSpeedMetersPerSecond.isFinite() && maxSpeedMetersPerSecond > 0)
        require(maxPathPoints >= 2 && captureTimeoutMillis > 0)
    }
    fun accepts(fix: GpsFix, now: Long): Boolean = fix.latitude.isFinite() && fix.longitude.isFinite() &&
        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters != null && fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..maxAccuracyMeters &&
        fix.timestampNanos > 0 && fix.timestampNanos <= now && now - fix.timestampNanos < maxAgeMillis * 1_000_000
}

internal data class GpsJourney(val tripId: Long, val estimatedMeters: Double, val incomplete: Boolean, val path: List<GeoPoint>)

/** Ephemeral diagnostic estimate. Never substitutes for Trip.drivenMeters or SDK odometer. */
internal class GpsPathAccumulator(private val tripId: Long, private val policy: GpsPolicy, restored: Boolean = false) {
    private var previous: GpsFix? = null
    private var lastTimestamp = 0L
    private var meters = 0.0
    private var incomplete = restored
    private val points = ArrayDeque<GeoPoint>()
    fun unavailable() { previous = null; incomplete = true }
    fun add(fix: GpsFix, now: Long) {
        if (!policy.accepts(fix, now) || fix.timestampNanos <= lastTimestamp) { unavailable(); return }
        val observationGap = fix.timestampNanos - lastTimestamp
        lastTimestamp = fix.timestampNanos
        val old = previous
        if (old != null) {
            // Stationary/jitter observations also advance time; an old distance anchor must
            // never make a sudden jump appear to have taken the whole stationary interval.
            val seconds = observationGap / 1_000_000_000.0
            val distance = distance(old, fix)
            if (seconds * 1_000 > policy.maxSegmentGapMillis) unavailable()
            else if (distance > policy.maxSpeedMetersPerSecond * seconds) {
                // Keep the last trusted anchor; one bad jump cannot poison the next segment.
                incomplete = true
                return
            } else if (distance <= max(old.accuracyMeters!!, fix.accuracyMeters!!)) {
                // Do not integrate stationary GPS jitter or repeat coordinates.
                return
            } else meters += distance
        }
        previous = fix
        if (points.size == policy.maxPathPoints) { points.removeFirst(); incomplete = true }
        points.addLast(GeoPoint(fix.latitude, fix.longitude))
    }
    fun snapshot() = GpsJourney(tripId, meters, incomplete, points.toList())
    private fun distance(a: GpsFix, b: GpsFix): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

internal interface GpsSession {
    fun start(onFix: (GpsFix) -> Unit, onUnavailable: () -> Unit)
    fun close()
}

internal class GpsLocationSource(
    private val scope: CoroutineScope,
    private val permission: () -> Boolean,
    private val session: () -> GpsSession,
    private val nowNanos: () -> Long,
    private val policy: GpsPolicy = GpsPolicy(),
) : TripLocationSource {
    private val mutableAvailable = MutableStateFlow(false)
    override val available = mutableAvailable.asStateFlow()
    private var journeyJob: Job? = null
    private var activeId: Long? = null
    private var journeyGeneration = 0L
    private val mutableJourney = MutableStateFlow<GpsJourney?>(null)
    val journey = mutableJourney.asStateFlow()
    private val fixes = flow<GpsFix?> {
        val incoming = Channel<GpsFix?>(16)
        var opened: GpsSession? = null
        try {
            if (!permission()) { emit(null); return@flow }
            opened = session()
            fun send(fix: GpsFix?) {
                if (incoming.trySend(fix).isFailure) incoming.close(IllegalStateException("GPS buffer overflow"))
            }
            opened.start({ send(it) }, { send(null) })
            var lastTimestamp = 0L
            while (true) {
                if (!permission()) { mutableAvailable.value = false; emit(null); return@flow }
                val result = incoming.receiveOrTimeout(1_000)
                if (result is SessionRead.Timeout) {
                    if (mutableAvailable.value && nowNanos() - lastTimestamp >= policy.maxAgeMillis * 1_000_000) {
                        mutableAvailable.value = false
                        emit(null)
                    }
                    continue
                }
                val next = (result as SessionRead.Value).value
                val accepted = permission() && next != null && policy.accepts(next, nowNanos()) && next.timestampNanos > lastTimestamp
                mutableAvailable.value = accepted
                if (accepted) { lastTimestamp = next!!.timestampNanos; emit(next) } else emit(null)
                if (!permission()) return@flow
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableAvailable.value = false; emit(null) }
        finally {
            incoming.cancel()
            mutableAvailable.value = false
            try { opened?.close() } catch (_: Exception) { /* Manual recording remains available. */ }
        }
    }.recoverCompletedSession().buffer(0).shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 1)

    override suspend fun capture(): TripPlace? {
        val fix = withTimeoutOrNull(policy.captureTimeoutMillis) { fixes.first() } ?: return null
        if (!permission() || !policy.accepts(fix, nowNanos())) return null
        return TripPlace(GeoPoint(fix.latitude, fix.longitude), null)
    }
    @Synchronized override fun startJourney(tripId: Long, restored: Boolean) {
        if (activeId == tripId && journeyJob?.isActive == true) return
        journeyJob?.cancel()
        activeId = tripId
        val generation = ++journeyGeneration
        val startedAtNanos = nowNanos()
        val accumulator = GpsPathAccumulator(tripId, policy, restored)
        mutableJourney.value = accumulator.snapshot()
        journeyJob = scope.launch {
            fixes.collect { fix ->
                synchronized(this@GpsLocationSource) {
                if (activeId != tripId || journeyGeneration != generation) return@collect
                if (fix != null && fix.timestampNanos < startedAtNanos) return@collect
                if (fix == null) accumulator.unavailable() else accumulator.add(fix, nowNanos())
                mutableJourney.value = accumulator.snapshot()
                }
            }
        }
    }
    @Synchronized override fun endJourney(tripId: Long) {
        if (activeId != tripId) return
        activeId = null
        journeyGeneration++
        journeyJob?.cancel()
        journeyJob = null
        // No raw path retention after a trip. Only existing Room endpoint fields persist.
        mutableJourney.value = null
    }
}
