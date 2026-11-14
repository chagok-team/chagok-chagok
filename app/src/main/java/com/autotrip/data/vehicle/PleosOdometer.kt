package com.autotrip.data.vehicle

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred

/** Created only by RoomAppContainer; Fake mode never constructs Vehicle. */
internal fun pleosOdometer(context: Context, clock: Clock): OdometerSource = VehicleOdometer(
    newSession = { PleosOdometerSession(context.applicationContext) },
    clock = clock,
    reportError = { Log.w("AutoTripVehicle", "Odometer capture/release failed", it) },
)

private class PleosOdometerSession(context: Context) : VehicleOdometerSession {
    private val vehicle = Vehicle(context)

    override fun initialize() = vehicle.initialize()

    override suspend fun canRead(): Boolean {
        val result = CompletableDeferred<Boolean>()
        vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(
            onComplete = { config ->
                result.complete(isReadable(config.accessType))
            },
            onFailed = { result.completeExceptionally(it) },
        )
        return result.await()
    }

    override suspend fun distanceKilometers(): Float? {
        val result = CompletableDeferred<Float?>()
        vehicle.getOdometer().getCurrentDistanceDriven(
            onComplete = { result.complete(it) },
            onFailed = { result.completeExceptionally(it) },
        )
        return result.await()
    }

    override fun release() = vehicle.release()
}

internal fun isReadable(access: AccessType): Boolean =
    access == AccessType.READ || access == AccessType.READ_WRITE
