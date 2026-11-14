package com.autotrip.data.tracker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.location.LocationManager
import androidx.room.Room
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.vehicle.pleosOdometer
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.OdometerCaptureStatus
import java.time.Clock
import com.autotrip.data.location.GpsLocationSource
import com.autotrip.data.location.androidGpsLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires normal FINE approval and emulator GPS injection. No Fake LocationSource. */
@RunWith(AndroidJUnit4::class)
class AndroidGpsProbeTest {
    @Test fun receivesActualAndroidGpsCallbacks() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Normal user FINE approval required", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION"))
        fun record(message: String) {
            context.openFileOutput("stage7-android-gps.txt", Context.MODE_APPEND).bufferedWriter().use { it.appendLine(message) }
        }
        context.openFileOutput("stage7-android-gps.txt", Context.MODE_PRIVATE).close()
        // While-in-use location must be tested with a visible Activity, without AppOps overrides.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val foreground = instrumentation.startActivitySync(Intent().setClassName(context,
            "com.autotrip.diagnostics.SpeedPermissionActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = androidGpsLocation(context, scope) as GpsLocationSource
        try {
            source.startJourney(1)
            record("READY: visible diagnostic Activity, gpsEnabled=${context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)}, inject first fix")
            withTimeout(20_000) { source.available.first { it } }
            val place = source.capture()
            assertNotNull(place?.point)
            record("FIRST_FIX: finite fresh coordinates with accuracy <=50m received; inject second fix")
            val journey = withTimeout(20_000) { source.journey.first { it != null && it.path.size >= 2 && it.estimatedMeters > 0 } }!!
            assertTrue(journey.estimatedMeters in 20.0..50.0)
            source.endJourney(1)
            assertNull(source.journey.value)
            val database = Room.inMemoryDatabaseBuilder(context, AutoTripDatabase::class.java).build()
            try {
                val repository = RoomTripRepository(database)
                val tracker = RoomTripTracker(repository, scope,
                    odometer = pleosOdometer(context, Clock.systemUTC()), location = source)
                val id = (tracker.startManualTrip() as AutoTripResult.Success).value
                val started = repository.observeTrip(id).first()!!
                assertNotNull("Actual GPS start endpoint", started.startPlace?.point)
                assertEquals(OdometerCaptureStatus.CAPTURED, started.odometerStart.status)
                assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
                val saved = repository.observeTrip(id).first()!!
                assertNotNull("Actual GPS end endpoint", saved.endPlace?.point)
                assertEquals(OdometerCaptureStatus.CAPTURED, saved.odometerEnd.status)
                assertEquals("Vehicle is stationary; GPS estimate must not replace odometer", 0L, saved.drivenMeters)
                assertNull(source.journey.value)
                record("ACTUAL_ROOM: GPS start/end saved; Vehicle odometer drivenMeters=${saved.drivenMeters}; estimated GPS movement not added")
            } finally { database.close() }
            record("COMPLETE: measured GPS estimateMeters=${journey.estimatedMeters}; path cleared; not FusedLocation proof")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            instrumentation.runOnMainSync { foreground.finish() }
        }
    }
}
