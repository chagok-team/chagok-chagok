package com.autotrip.data.navigation

import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.NavigationDestination
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import com.autotrip.data.flow.recoverCompletedSession

interface NavigationSource {
    val events: Flow<NavigationDestination>
    val available: StateFlow<Boolean>
}

internal data class NaviDestination(
    val name: String?, val address: String?, val latitude: Double, val longitude: Double,
)

internal sealed interface NaviSignal {
    data object Changed : NaviSignal
    data object Cancelled : NaviSignal
    data class Info(val destination: NaviDestination) : NaviSignal
    data class Error(val message: String) : NaviSignal
}

internal interface NaviSession {
    suspend fun connect()
    fun addListener(listener: (NaviSignal) -> Unit)
    fun requestDestination()
    fun removeListener()
    fun release()
}

/** One SDK session shared by active subscribers. No connection-time destination replay. */
internal class NaviDestinations(
    scope: CoroutineScope,
    private val clock: Clock,
    private val newSession: () -> NaviSession,
    private val connectionTimeoutMillis: Long = 10_000,
    private val reportError: (Exception) -> Unit = {},
) : NavigationSource {
    private val mutableAvailable = MutableStateFlow(false)
    override val available = mutableAvailable.asStateFlow()

    override val events: Flow<NavigationDestination> = flow {
        val signals = Channel<NaviSignal>(64)
        var session: NaviSession? = null
        var lastKey: Pair<String?, GeoPoint?>? = null
        var expectingInfo = false
        var refreshPending = false
        try {
            val opened = newSession()
            session = opened
            if (withTimeoutOrNull(connectionTimeoutMillis) { opened.connect() } == null) {
                reportError(TimeoutException("NaviHelper connection timed out"))
                return@flow
            }
            opened.addListener {
                if (signals.trySend(it).isFailure) signals.close(IllegalStateException("Navigation callback buffer full/closed"))
            }
            mutableAvailable.value = true
            // Do not query the current destination here: only live changes may trigger voice.
            for (signal in signals) {
                when (signal) {
                    NaviSignal.Changed -> {
                        if (expectingInfo) {
                            refreshPending = true
                            continue
                        }
                        expectingInfo = true
                        opened.requestDestination()
                    }
                    NaviSignal.Cancelled -> {
                        expectingInfo = false
                        refreshPending = false
                        lastKey = null
                    }
                    is NaviSignal.Info -> {
                        if (!expectingInfo) continue
                        if (refreshPending) {
                            refreshPending = false
                            opened.requestDestination()
                            continue
                        }
                        expectingInfo = false
                        refreshPending = false
                        val raw = signal.destination
                        val name = raw.name?.trim()?.takeIf(String::isNotEmpty)
                            ?: raw.address?.trim()?.takeIf(String::isNotEmpty)
                        val point = if (raw.latitude.isFinite() && raw.longitude.isFinite() &&
                            raw.latitude in -90.0..90.0 && raw.longitude in -180.0..180.0
                        ) GeoPoint(raw.latitude, raw.longitude) else null
                        if (name == null && point == null) continue
                        val key = name to point
                        mutableAvailable.value = true
                        if (key != lastKey) {
                            lastKey = key
                            emit(NavigationDestination(name, point, clock.instant()))
                        }
                    }
                    is NaviSignal.Error -> {
                        expectingInfo = false
                        refreshPending = false
                        lastKey = null
                        mutableAvailable.value = false
                        reportError(IllegalStateException(signal.message))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportError(error)
        } finally {
            signals.close()
            mutableAvailable.value = false
            try { session?.removeListener() } catch (error: Exception) { reportError(error) }
            try { session?.release() } catch (error: Exception) { reportError(error) }
        }
    }.recoverCompletedSession().shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 0)
}

internal object NoNavigation : NavigationSource {
    override val events = emptyFlow<NavigationDestination>()
    override val available = MutableStateFlow(false).asStateFlow()
}
