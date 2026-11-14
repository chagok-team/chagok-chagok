package com.autotrip.data.tracker

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reports availability; a completed diagnostic is not proof of a non-null GPS fix. */
@RunWith(AndroidJUnit4::class)
class FusedLocationProbeTest {
    @Test fun recordActualSupportAndPermissions() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun record(text: String) {
            context.openFileOutput("stage7-location-probe.txt", Context.MODE_APPEND).bufferedWriter().use { it.appendLine(text) }
        }
        context.openFileOutput("stage7-location-probe.txt", Context.MODE_PRIVATE).close()
        val fused = FusedLocation(context)
        var listener: FusedLocationListener? = null
        try {
            val granted = context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED
            val fine = context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
            val supported = fused.isSupported()
            record("uid=${android.os.Process.myUid()} fusedSupported=$supported fusedPermission=$granted finePermission=$fine providers=${context.getSystemService(LocationManager::class.java).allProviders}")
            if (!supported || !granted) { record("NO_LOCATION_READ: unsupported or awaiting normal user approval"); return@runBlocking }
            val result = CompletableDeferred<LocationData?>()
            listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) { result.complete(location) }
                override fun onError(exception: Exception) { result.completeExceptionally(exception) }
            }
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            fused.getLastKnownLocation(listener)
            val value = withTimeout(10_000) { result.await() }
            record("nonNull=${value != null} confidenceRaw=${value?.confidence} timestampRaw=${value?.timestampMillis}; coordinates omitted")
            // Raw confidence/timestamp semantics remain unverified; not converted to accuracy/age.
        } finally {
            try { listener?.let { fused.unregisterFusedLocationCallback(it) } } finally { fused.release() }
        }
    }
}
