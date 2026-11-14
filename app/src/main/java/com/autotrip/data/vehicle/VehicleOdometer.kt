package com.autotrip.data.vehicle

import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import kotlin.math.roundToLong

interface OdometerSource {
    /** Last confirmed read capability, not automatic trip detection support. */
    val supported: StateFlow<Boolean>
    suspend fun capture(minimumMeters: Long? = null): OdometerReading
}

/** A single initialize / capability / read / release session. No subscriptions. */
internal interface VehicleOdometerSession {
    fun initialize()
    suspend fun canRead(): Boolean
    suspend fun distanceKilometers(): Float?
    fun release()
}

/** SDK-independent policy so nulls, errors and timeouts are tested without a vehicle. */
internal class VehicleOdometer(
    private val newSession: () -> VehicleOdometerSession,
    private val clock: Clock,
    private val timeoutMillis: Long = 5_000,
    private val reportError: (Exception) -> Unit = {},
) : OdometerSource {
    private val mutex = Mutex()
    private val mutableSupported = MutableStateFlow(false)
    override val supported = mutableSupported.asStateFlow()

    override suspend fun capture(minimumMeters: Long?): OdometerReading = mutex.withLock {
        try {
            withTimeoutOrNull(timeoutMillis) {
                val session = newSession()
                try {
                    session.initialize()
                    val readable = session.canRead()
                    mutableSupported.value = readable
                    if (!readable) return@withTimeoutOrNull missing(OdometerCaptureStatus.UNSUPPORTED)
                    // Vehicle 2.3.2 getCurrentDistanceDriven forwards PERF_ODOMETER (km).
                    val kilometers = session.distanceKilometers()
                    val meters = kilometers?.toDouble()?.times(1_000)
                    if (meters == null || !meters.isFinite() || meters < 0 ||
                        meters >= Long.MAX_VALUE.toDouble()
                    ) return@withTimeoutOrNull missing(OdometerCaptureStatus.FAILED)
                    val rounded = meters.roundToLong()
                    if (minimumMeters != null && rounded < minimumMeters) {
                        return@withTimeoutOrNull missing(OdometerCaptureStatus.FAILED)
                    }
                    OdometerReading(rounded, OdometerCaptureStatus.CAPTURED, clock.instant())
                } finally {
                    // Also release after partial initialization, timeout or caller cancellation.
                    try { session.release() } catch (error: Exception) { reportError(error) }
                }
            } ?: run {
                reportError(TimeoutException("Odometer capture exceeded ${timeoutMillis}ms"))
                missing(OdometerCaptureStatus.FAILED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportError(error)
            missing(OdometerCaptureStatus.FAILED)
        }
    }
}

internal fun missing(status: OdometerCaptureStatus) = OdometerReading(null, status, null)

/** Compatibility default for callers without a vehicle provider (including Room tests). */
internal object UnsupportedOdometer : OdometerSource {
    override val supported = MutableStateFlow(false).asStateFlow()
    override suspend fun capture(minimumMeters: Long?) = missing(OdometerCaptureStatus.UNSUPPORTED)
}
