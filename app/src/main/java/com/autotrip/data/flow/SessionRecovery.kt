package com.autotrip.data.flow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout

internal sealed interface SessionRead<out T> {
    data class Value<T>(val value: T) : SessionRead<T>
    object Timeout : SessionRead<Nothing>
}

/** Atomic choice: a timeout must not cancel a receive that has already consumed a sample. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> ReceiveChannel<T>.receiveOrTimeout(millis: Long): SessionRead<T> = select {
    onReceive { SessionRead.Value(it) }
    onTimeout(millis) { SessionRead.Timeout }
}

/** Retry a completed session only after its finally/cleanup has run. Subscriber cancellation stops it. */
internal fun <T> Flow<T>.recoverCompletedSession(delayMillis: Long = 3_000): Flow<T> {
    require(delayMillis > 0)
    return flow {
        while (currentCoroutineContext().isActive) {
            emitAll(this@recoverCompletedSession)
            delay(delayMillis)
        }
    }
}
