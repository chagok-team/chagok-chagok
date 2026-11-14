package com.autotrip.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autotrip.domain.model.*
import java.time.Instant

@Entity(tableName = "trips", indices = [Index("startedAtEpochMillis"), Index("purposeStatus"), Index("endedAtEpochMillis")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val source: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val odometerStartMeters: Long?,
    val odometerStartStatus: String,
    val odometerStartCapturedAtEpochMillis: Long?,
    val odometerEndMeters: Long?,
    val odometerEndStatus: String,
    val odometerEndCapturedAtEpochMillis: Long?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val startLabel: String?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val endLabel: String?,
    val destinationName: String?,
    val userId: Long?,
    val purpose: String?,
    val purposeStatus: String,
    val purposeInputMethod: String?,
    val purposeInputTimeEpochMillis: Long?,
    val voiceRawText: String?,
    val memo: String?,
)

fun Trip.toEntity(): TripEntity = TripEntity(
    id = id, vehicleId = vehicleId, source = source.name,
    startedAtEpochMillis = startedAt.toEpochMilli(),
    endedAtEpochMillis = endedAt?.toEpochMilli(),
    odometerStartMeters = odometerStart.meters,
    odometerStartStatus = odometerStart.status.name,
    odometerStartCapturedAtEpochMillis = odometerStart.capturedAt?.toEpochMilli(),
    odometerEndMeters = odometerEnd.meters,
    odometerEndStatus = odometerEnd.status.name,
    odometerEndCapturedAtEpochMillis = odometerEnd.capturedAt?.toEpochMilli(),
    startLatitude = startPlace?.point?.latitude,
    startLongitude = startPlace?.point?.longitude,
    startLabel = startPlace?.label,
    endLatitude = endPlace?.point?.latitude,
    endLongitude = endPlace?.point?.longitude,
    endLabel = endPlace?.label,
    destinationName = destinationName, userId = userId,
    purpose = purpose?.name, purposeStatus = purposeStatus.name,
    purposeInputMethod = purposeInputMethod?.name,
    purposeInputTimeEpochMillis = purposeInputTime?.toEpochMilli(),
    voiceRawText = voiceRawText, memo = memo,
)

fun TripEntity.toDomain(): Trip = Trip(
    id = id, vehicleId = vehicleId, source = TripSource.valueOf(source),
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    endedAt = endedAtEpochMillis?.let(Instant::ofEpochMilli),
    odometerStart = OdometerReading(
        odometerStartMeters, OdometerCaptureStatus.valueOf(odometerStartStatus),
        odometerStartCapturedAtEpochMillis?.let(Instant::ofEpochMilli),
    ),
    odometerEnd = OdometerReading(
        odometerEndMeters, OdometerCaptureStatus.valueOf(odometerEndStatus),
        odometerEndCapturedAtEpochMillis?.let(Instant::ofEpochMilli),
    ),
    startPlace = place(startLatitude, startLongitude, startLabel),
    endPlace = place(endLatitude, endLongitude, endLabel),
    destinationName = destinationName, userId = userId,
    purpose = purpose?.let(TripPurpose::valueOf),
    purposeStatus = PurposeStatus.valueOf(purposeStatus),
    purposeInputMethod = purposeInputMethod?.let(InputMethod::valueOf),
    purposeInputTime = purposeInputTimeEpochMillis?.let(Instant::ofEpochMilli),
    voiceRawText = voiceRawText, memo = memo,
)

private fun place(lat: Double?, lon: Double?, label: String?): TripPlace? {
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    return if (point == null && label == null) null else TripPlace(point, label)
}
