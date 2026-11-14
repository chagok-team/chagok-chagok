
package com.autotrip.fake

import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Demo-only sample data.
 *
 * Produces 10 completed trips without accessing Room or the vehicle SDK.
 */
internal object FakeTripSeeder {

    fun createTrips(
        clock: Clock = Clock.systemDefaultZone(),
        vehicleId: Long = 1L,
        userId: Long? = null,
    ): List<Trip> {
        val today = LocalDate.now(clock)
        val zone = clock.zone

        var odometer = 100_000L

        data class Sample(
            val daysAgo: Long,
            val hour: Int,
            val durationMinutes: Long,
            val distanceMeters: Long,
            val purpose: TripPurpose?,
            val source: TripSource = TripSource.AUTO,
            val startFailed: Boolean = false,
            val endFailed: Boolean = false,
            val destination: String? = null,
            val memo: String? = null,
        )

        val samples = listOf(
            Sample(9, 9, 45, 18_000, TripPurpose.CLIENT_VISIT,
                destination = "거래처"),
            Sample(8, 8, 35, 12_000, TripPurpose.COMMUTE,
                destination = "회사"),
            Sample(7, 15, 30, 8_000, TripPurpose.PERSONAL,
                destination = "개인 일정"),
            Sample(6, 11, 40, 15_000, null,
                destination = "방문지"),
            Sample(5, 10, 50, 21_000, TripPurpose.MEETING,
                destination = "회의 장소"),
            Sample(4, 8, 30, 11_000, TripPurpose.COMMUTE,
                destination = "회사"),
            Sample(3, 13, 60, 27_000, TripPurpose.SITE_VISIT,
                startFailed = true, destination = "현장"),
            Sample(2, 14, 45, 19_000, TripPurpose.CLIENT_VISIT,
                endFailed = true, destination = "거래처"),
            Sample(1, 16, 25, 7_000, TripPurpose.PERSONAL,
                source = TripSource.MANUAL, destination = "개인 일정"),
            Sample(0, 9, 40, 14_000, TripPurpose.TRAINING,
                destination = "교육장", memo = "업무 교육 참석"),
        )

        return samples.mapIndexed { index, sample ->
            val startedAt = today.minusDays(sample.daysAgo)
                .atTime(LocalTime.of(sample.hour, 0))
                .atZone(zone)
                .toInstant()

            val endedAt = startedAt.plus(
                Duration.ofMinutes(sample.durationMinutes)
            )

            val startMeters = odometer
            odometer += sample.distanceMeters
            val endMeters = odometer

            val startReading = if (sample.startFailed) {
                OdometerReading(
                    null, OdometerCaptureStatus.FAILED, null
                )
            } else {
                OdometerReading(
                    startMeters, OdometerCaptureStatus.CAPTURED, startedAt
                )
            }

            val endReading = if (sample.endFailed) {
                OdometerReading(
                    null, OdometerCaptureStatus.FAILED, null
                )
            } else {
                OdometerReading(
                    endMeters, OdometerCaptureStatus.CAPTURED, endedAt
                )
            }

            Trip(
                id = index + 1L,
                vehicleId = vehicleId,
                source = sample.source,
                startedAt = startedAt,
                endedAt = endedAt,
                odometerStart = startReading,
                odometerEnd = endReading,
                startPlace = null,
                endPlace = null,
                destinationName = sample.destination,
                userId = userId,
                purpose = sample.purpose,
                purposeStatus = if (sample.purpose == null) {
                    PurposeStatus.PENDING
                } else {
                    PurposeStatus.CONFIRMED
                },
                purposeInputMethod = if (sample.purpose == null) {
                    null
                } else {
                    InputMethod.BUTTON
                },
                purposeInputTime = if (sample.purpose == null) {
                    null
                } else {
                    endedAt
                },
                voiceRawText = null,
                memo = sample.memo,
            )
        }
    }
}
