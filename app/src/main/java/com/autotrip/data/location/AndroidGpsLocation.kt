package com.autotrip.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope

/** Explicit Android GPS source, not a claim of PLEOS FusedLocation compatibility. */
fun androidGpsLocation(context: Context, scope: CoroutineScope): TripLocationSource {
    val app = context.applicationContext
    return GpsLocationSource(scope,
        { app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
        { AndroidGpsSession(app) }, SystemClock::elapsedRealtimeNanos)
}

private class AndroidGpsSession(context: Context) : GpsSession {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    override fun start(onFix: (GpsFix) -> Unit, onUnavailable: () -> Unit) {
        check(listener == null)
        // An initially disabled provider must reach the source's release/backoff/retry path.
        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "GPS provider disabled" }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(GpsFix(location.latitude, location.longitude,
                    if (location.hasAccuracy()) location.accuracy else null, location.elapsedRealtimeNanos))
            }
            override fun onProviderDisabled(provider: String) { onUnavailable() }
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = callback
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, callback, Looper.getMainLooper())
        // Await a new fix; do not immediately accept an arbitrarily old last-known position.
    }
    override fun close() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }
}
