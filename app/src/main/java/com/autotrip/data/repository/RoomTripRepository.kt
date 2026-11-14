package com.autotrip.data.repository

import androidx.room.withTransaction
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.local.entity.TripEditHistoryEntity
import com.autotrip.data.local.entity.toDomain
import com.autotrip.data.local.entity.toEntity
import com.autotrip.domain.model.*
import com.autotrip.domain.repository.TripRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-backed TripRepository. Tracker integration is a separate step. */
class RoomTripRepository(
    private val database: AutoTripDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val defaultUserId: suspend () -> Long? = { null },
) : TripRepository {
    private val dao = database.tripDao()
    private val plannedMutex = Mutex()
    private var planned: PlannedPurpose? = null

    private data class PlannedPurpose(
        val purpose: TripPurpose,
        val method: InputMethod,
        val voiceRawText: String?,
        val enteredAt: Instant,
    )

    override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> {
        val start = period.start.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val end = period.endInclusive.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        return dao.observeTrips(start, end).map { list -> list.map { it.toDomain() } }
    }

    override fun observeTrip(tripId: Long): Flow<Trip?> =
        dao.observeTrip(tripId).map { it?.toDomain() }

    override fun observePendingPurposeTrips(): Flow<List<Trip>> =
        dao.observePendingPurposeTrips().map { list -> list.map { it.toDomain() } }

    override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> =
        dao.observeEditHistory(tripId).map { list -> list.map { it.toDomain() } }

    override suspend fun confirmPurpose(
        tripId: Long,
        purpose: TripPurpose,
        method: InputMethod,
        userId: Long?,
        voiceRawText: String?,
    ): AutoTripResult<Unit> = storageResult {
        database.withTransaction {
            val trip = dao.findTrip(tripId)?.toDomain()
                ?: return@withTransaction AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
            if (trip.purposeStatus == PurposeStatus.CONFIRMED) {
                return@withTransaction AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(tripId))
            }
            if (trip.endedAt == null) {
                return@withTransaction AutoTripResult.Failure(AutoTripError.InvalidState("Trip is still driving"))
            }
            dao.updateTrip(trip.copy(
                purpose = purpose,
                purposeStatus = PurposeStatus.CONFIRMED,
                purposeInputMethod = method,
                purposeInputTime = clock.instant(),
                voiceRawText = voiceRawText.takeIf { method == InputMethod.VOICE },
                userId = userId ?: defaultUserId(),
            ).toEntity())
            AutoTripResult.Success(Unit)
        }
    }

    override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose): AutoTripResult<Unit> =
        edit(tripId, EditableField.PURPOSE, purpose.name, { it.purpose?.name }) { it.copy(purpose = purpose) }

    override suspend fun updateUser(tripId: Long, userId: Long?): AutoTripResult<Unit> =
        edit(tripId, EditableField.USER, userId?.toString(), { it.userId?.toString() }) { it.copy(userId = userId) }

    override suspend fun updateMemo(tripId: Long, memo: String?): AutoTripResult<Unit> =
        edit(tripId, EditableField.MEMO, memo, { it.memo }) { it.copy(memo = memo) }

    private suspend fun edit(
        tripId: Long,
        field: EditableField,
        newValue: String?,
        oldValue: (Trip) -> String?,
        change: (Trip) -> Trip,
    ): AutoTripResult<Unit> = storageResult {
        database.withTransaction {
            val trip = dao.findTrip(tripId)?.toDomain()
                ?: return@withTransaction AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
            if (field == EditableField.PURPOSE && trip.purposeStatus != PurposeStatus.CONFIRMED) {
                return@withTransaction AutoTripResult.Failure(AutoTripError.PurposeNotConfirmed(tripId))
            }
            val previous = oldValue(trip)
            if (previous == newValue) return@withTransaction AutoTripResult.Success(Unit)
            dao.updateTrip(change(trip).toEntity())
            dao.insertEditHistory(TripEditHistoryEntity(
                tripId = tripId,
                field = field.name,
                oldValue = previous,
                newValue = newValue,
                editedAtEpochMillis = clock.instant().toEpochMilli(),
            ))
            AutoTripResult.Success(Unit)
        }
    }

    override suspend fun setPlannedPurpose(
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): AutoTripResult<Unit> = storageResult {
        plannedMutex.withLock {
            database.withTransaction {
                val active = dao.findActiveTrip()?.toDomain()
                if (active != null) {
                    if (active.purposeStatus == PurposeStatus.CONFIRMED) {
                        return@withTransaction AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(active.id))
                    }
                    dao.updateTrip(active.copy(
                        purpose = purpose,
                        purposeStatus = PurposeStatus.CONFIRMED,
                        purposeInputMethod = method,
                        purposeInputTime = clock.instant(),
                        voiceRawText = voiceRawText.takeIf { method == InputMethod.VOICE },
                        userId = active.userId ?: defaultUserId(),
                    ).toEntity())
                } else {
                    planned = PlannedPurpose(purpose, method, voiceRawText, clock.instant())
                }
                AutoTripResult.Success(Unit)
            }
        }
    }

    /** Tracker-only API. Do not add this method to the public TripRepository contract. */
    suspend fun beginTrip(
        vehicleId: Long,
        source: TripSource,
        startedAt: Instant,
        odometerStart: OdometerReading,
        startPlace: TripPlace? = null,
        destinationName: String? = null,
    ): AutoTripResult<Trip> = storageResult {
        plannedMutex.withLock {
            database.withTransaction {
                if (dao.findActiveTrip() != null) {
                    return@withTransaction AutoTripResult.Failure(AutoTripError.InvalidState("A trip is already active"))
                }
                val candidate = planned
                val accepted = candidate?.takeIf {
                    !startedAt.isBefore(it.enteredAt) &&
                        Duration.between(it.enteredAt, startedAt) <= Duration.ofMinutes(30)
                }
                val trip = Trip(
                    id = 0,
                    vehicleId = vehicleId,
                    source = source,
                    startedAt = startedAt,
                    endedAt = null,
                    odometerStart = odometerStart,
                    odometerEnd = OdometerReading(null, OdometerCaptureStatus.NOT_YET_CAPTURED, null),
                    startPlace = startPlace,
                    endPlace = null,
                    destinationName = destinationName,
                    userId = defaultUserId(),
                    purpose = accepted?.purpose,
                    purposeStatus = if (accepted == null) PurposeStatus.PENDING else PurposeStatus.CONFIRMED,
                    purposeInputMethod = accepted?.method,
                    purposeInputTime = accepted?.enteredAt,
                    voiceRawText = accepted?.voiceRawText?.takeIf { accepted.method == InputMethod.VOICE },
                    memo = null,
                )
                val id = dao.insertTrip(trip.toEntity())
                planned = null
                AutoTripResult.Success(trip.copy(id = id))
            }
        }
    }

    /** Tracker-only API. Preserves original start reading. */
    suspend fun finishTrip(
        tripId: Long,
        endedAt: Instant,
        odometerEnd: OdometerReading,
        endPlace: TripPlace? = null,
    ): AutoTripResult<Trip> = storageResult {
        database.withTransaction {
            val trip = dao.findTrip(tripId)?.toDomain()
                ?: return@withTransaction AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
            if (trip.endedAt != null || endedAt.isBefore(trip.startedAt)) {
                return@withTransaction AutoTripResult.Failure(AutoTripError.InvalidState("Invalid trip end"))
            }
            val finished = trip.copy(endedAt = endedAt, odometerEnd = odometerEnd, endPlace = endPlace)
            dao.updateTrip(finished.toEntity())
            AutoTripResult.Success(finished)
        }
    }

    suspend fun activeTrip(): Trip? = dao.findActiveTrip()?.toDomain()

    internal suspend fun latestFinishedTrip(): Trip? = dao.findLatestFinishedTrip()?.toDomain()

    private suspend fun <T> storageResult(block: suspend () -> AutoTripResult<T>): AutoTripResult<T> =
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AutoTripResult.Failure(AutoTripError.Storage(error))
        }
}
