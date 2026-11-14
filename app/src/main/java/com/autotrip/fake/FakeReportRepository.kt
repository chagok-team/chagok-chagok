package com.autotrip.fake

import com.autotrip.domain.model.*
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.ProfileRepository
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Produces report data for B's PDF renderer; does not generate PDF bytes. */
class FakeReportRepository(
    private val trips: TripRepository,
    private val profiles: ProfileRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ReportRepository {
    override fun observeSummary(period: TaxPeriod): Flow<MileageSummary> =
        trips.observeTrips(period).combine(profiles.observeUsers()) { items, _ ->
            summary(period, items)
        }

    override suspend fun buildLogbookExport(period: TaxPeriod): AutoTripResult<LogbookExport> {
        val business = profiles.observeBusinessProfile().first()
            ?: return AutoTripResult.Failure(AutoTripError.ProfileIncomplete)
        val vehicle = profiles.observeVehicle().first()
            ?: return AutoTripResult.Failure(AutoTripError.ProfileIncomplete)
        val users = profiles.observeUsers().first().associateBy { it.id }
        val records = trips.observeTrips(period).first().filter { it.vehicleId == vehicle.id }
        val editIds = mutableSetOf<Long>()
        for (trip in records) {
            if (trips.observeEditHistory(trip.id).first().isNotEmpty()) editIds.add(trip.id)
        }
        val rows = records.sortedWith(compareBy<Trip> { it.startedAt }.thenBy { it.id }).map { trip ->
            val distance = trip.drivenMeters
            val category = trip.purpose?.category.takeIf { trip.purposeStatus == PurposeStatus.CONFIRMED }
            val user = trip.userId?.let(users::get)
            val flags = buildSet {
                if (distance == null) add(RowFlag.ODOMETER_MISSING)
                if (trip.purposeStatus == PurposeStatus.PENDING) add(RowFlag.PURPOSE_PENDING)
                if (trip.source == TripSource.MANUAL) add(RowFlag.MANUAL_TRIP)
                if (trip.id in editIds) add(RowFlag.EDITED)
            }
            val date = trip.startedAt.atZone(clock.zone).toLocalDate()
            LogbookRow(
                tripId = trip.id,
                date = date,
                dayOfWeek = date.dayOfWeek,
                userDepartment = user?.department,
                userName = user?.name,
                odometerBeforeMeters = trip.odometerStart.meters,
                odometerAfterMeters = trip.odometerEnd.meters,
                drivenMeters = distance,
                commuteMeters = distance.takeIf { category == PurposeCategory.COMMUTE },
                generalBusinessMeters = distance.takeIf { category == PurposeCategory.GENERAL_BUSINESS },
                remark = remark(trip, flags),
                purpose = trip.purpose,
                source = trip.source,
                flags = flags,
            )
        }
        return AutoTripResult.Success(LogbookExport(
            business = business,
            vehicle = vehicle,
            period = period,
            rows = rows,
            summary = summary(period, records),
            generatedAt = clock.instant(),
        ))
    }

    private fun summary(period: TaxPeriod, items: List<Trip>): MileageSummary {
        // In-progress trips cannot contribute to a completed-trip tax logbook.
        val completed = items.filter { it.endedAt != null }
        val valid = completed.filter { it.drivenMeters != null }
        fun metersWhere(predicate: (Trip) -> Boolean): Long =
            valid.filter(predicate).sumOf { it.drivenMeters ?: 0L }
        val total = metersWhere { true }
        val commute = metersWhere { it.purposeStatus == PurposeStatus.CONFIRMED && it.purpose?.category == PurposeCategory.COMMUTE }
        val general = metersWhere { it.purposeStatus == PurposeStatus.CONFIRMED && it.purpose?.category == PurposeCategory.GENERAL_BUSINESS }
        val personal = metersWhere { it.purposeStatus == PurposeStatus.CONFIRMED && it.purpose?.category == PurposeCategory.NON_BUSINESS }
        val unclassified = metersWhere { it.purposeStatus == PurposeStatus.PENDING }
        val business = commute + general
        return MileageSummary(
            period = period,
            totalMeters = total,
            commuteMeters = commute,
            generalBusinessMeters = general,
            businessMeters = business,
            nonBusinessMeters = personal,
            unclassifiedMeters = unclassified,
            businessUseRatio = if (total == 0L) null else business.toDouble() / total.toDouble(),
            incompleteTripCount = completed.count { it.drivenMeters == null },
            pendingPurposeTripCount = completed.count { it.purposeStatus == PurposeStatus.PENDING },
        )
    }

    private fun remark(trip: Trip, flags: Set<RowFlag>): String {
        val purposeName = when (trip.purpose) {
            TripPurpose.CLIENT_VISIT -> "거래처 방문"
            TripPurpose.SITE_VISIT -> "사업장 방문"
            TripPurpose.MEETING -> "회의"
            TripPurpose.PROMOTION -> "판촉"
            TripPurpose.TRAINING -> "교육"
            TripPurpose.COMMUTE -> "출퇴근"
            TripPurpose.PERSONAL -> "사적 사용"
            null -> null
        }.takeIf { trip.purposeStatus == PurposeStatus.CONFIRMED }

        val details = buildList {
            purposeName?.let { add(it) }
            trip.destinationName?.takeIf { it.isNotBlank() }?.let { add(it) }
            trip.memo?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

        val markers = buildList {
            if (RowFlag.MANUAL_TRIP in flags) add("[수동기록]")
            if (RowFlag.ODOMETER_MISSING in flags) add("[계기판 수집 실패]")
            if (RowFlag.PURPOSE_PENDING in flags) add("[목적 미입력]")
            if (RowFlag.EDITED in flags) add("[수정됨]")
        }

        return (listOf(details).filter { it.isNotBlank() } + markers)
            .joinToString(" ")
    }
}
