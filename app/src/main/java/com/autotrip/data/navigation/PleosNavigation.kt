package com.autotrip.data.navigation

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

internal fun pleosNavigation(context: Context, scope: CoroutineScope, clock: Clock): NavigationSource =
    NaviDestinations(scope, clock, { PleosNaviSession(context.applicationContext) },
        reportError = { Log.w("AutoTripNavi", "Navigation unavailable", it) })

private class PleosNaviSession(context: Context) : NaviSession {
    private val helper = NaviHelper(context)
    private var listener: NaviHelperEventListener? = null

    override suspend fun connect() {
        val connected = CompletableDeferred<Unit>()
        helper.initialize(onConnected = { connected.complete(Unit) },
            onFailed = { connected.completeExceptionally(it) })
        connected.await()
    }

    override fun addListener(listener: (NaviSignal) -> Unit) {
        check(this.listener == null) { "Navigation listener already registered" }
        val sdkListener = object : NaviHelperEventListener {
            override fun onDestinationChanged() { listener(NaviSignal.Changed) }
            override fun onRoutingCompleted() { listener(NaviSignal.Changed) }
            // Navi Service 3.0.4 on the API 34 emulator still emits this retained callback.
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onRouteEnded() { listener(NaviSignal.Changed) }
            override fun onRouteCancelled() { listener(NaviSignal.Cancelled) }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                listener(NaviSignal.Info(NaviDestination(destinationInfo.name, destinationInfo.address,
                    destinationInfo.latitude, destinationInfo.longitude)))
            }
            override fun onError(errorCode: NaviErrorCode) { listener(NaviSignal.Error(errorCode.name)) }
        }
        this.listener = sdkListener
        helper.addListener(sdkListener)
    }

    override fun requestDestination() = helper.getDestinationInfo()
    override fun removeListener() {
        listener?.let { helper.removeListener(it) }
        listener = null
    }
    override fun release() = helper.release()
}
