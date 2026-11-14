package com.autotrip

import android.app.Application
import com.autotrip.di.AppContainer
import com.autotrip.di.FakeAppContainer
import com.autotrip.di.RoomAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.util.Log

class AutoTripApplication : Application() {

    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val container: AppContainer by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        if (BuildConfig.USE_FAKE_DATA) {
            FakeAppContainer()
        } else {
            RoomAppContainer(applicationContext).also { room ->
                applicationScope.launch {
                    try {
                        room.restoreActiveTrip()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("AutoTrip", "Room restoration failed; automatic start disabled", error)
                    }
                }
            }
        }
    }
}
