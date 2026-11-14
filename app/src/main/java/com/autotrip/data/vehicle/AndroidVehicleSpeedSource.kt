package com.autotrip.data.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/** Does not request permissions, start a service, or create/finish a trip. */
fun vehicleSpeedSource(context: Context, scope: CoroutineScope, maxAgeMillis: Long = 2_000): VehicleSpeedSource {
    val app = context.applicationContext
    return ValidatedVehicleSpeedSource(scope,
        hasPermission = { app.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED },
        newSession = { AndroidVehicleSpeedSession(app) },
        elapsedNanos = SystemClock::elapsedRealtimeNanos,
        maxAgeMillis = maxAgeMillis,
        reportError = { Log.w("AutoTripSpeed", "Vehicle speed unavailable", it) })
}

@Suppress("DEPRECATION")
internal class AndroidVehicleSpeedSession(private val context: Context) : VehicleSpeedSession {
    private val closed = AtomicBoolean(false)
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var listener: CarPropertyManager.CarPropertyEventCallback? = null
    override suspend fun connect(onDisconnected: () -> Unit) {
        check(car == null && !closed.get())
        val ready = CompletableDeferred<Car>()
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, available ->
            if (closed.get()) {
                connected.disconnect()
            } else if (available) {
                ready.complete(connected)
            } else {
                ready.completeExceptionally(IllegalStateException("Car service disconnected"))
                onDisconnected()
            }
        }
        val connected = ready.await()
        manager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ?: error("CarPropertyManager unavailable")
    }
    override fun isSupported(): Boolean {
        val config = manager!!.propertyList.firstOrNull { it.propertyId == ID } ?: return false
        return config.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ ||
            config.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE
    }
    private fun convert(value: CarPropertyValue<*>): RawVehicleSpeed = RawVehicleSpeed(
        value.value as? Float, value.timestamp, value.status == CarPropertyValue.STATUS_AVAILABLE)
    override fun current(): RawVehicleSpeed = convert(manager!!.getProperty<Float>(ID, 0))
    override fun subscribe(onValue: (RawVehicleSpeed) -> Unit, onError: () -> Unit) {
        check(listener == null && !closed.get()) { "Speed listener already registered/closed" }
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (!closed.get() && value.propertyId == ID && value.areaId == 0) onValue(convert(value))
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                if (!closed.get() && propertyId == ID) onError()
            }
        }
        listener = callback
        check(manager!!.registerCallback(callback, ID, CarPropertyManager.SENSOR_RATE_NORMAL)) {
            "Speed callback registration rejected"
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener?.let { manager?.unregisterCallback(it) } }
        finally { listener = null; manager = null; car?.disconnect(); car = null }
    }
    private companion object { const val ID = VehiclePropertyIds.PERF_VEHICLE_SPEED }
}
