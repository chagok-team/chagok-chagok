package com.autotrip.di

import com.autotrip.domain.model.*
import com.autotrip.domain.repository.*
import com.autotrip.fake.*
import java.time.Clock
import com.autotrip.fake.FakeTripSeeder

/** Demo-only dependency graph. No vehicle SDK or persistent storage is used. */
class FakeAppContainer(
    clock: Clock = Clock.systemDefaultZone(),
) : AppContainer {
    private val profiles = FakeProfileRepository(
        initialBusiness = BusinessProfile(
            type = BusinessType.CORPORATION,
            name = "AutoTrip 데모 사업자",
            registrationNumber = "000-00-00000",
        ),
        initialVehicle = VehicleInfo(
            id = 1L,
            model = "데모 차량",
            modelSource = ValueSource.USER,
            plateNumber = "00가0000",
        ),
        initialUsers = listOf(
            TripUser(id = 1L, department = "영업팀", name = "데모 사용자 1"),
            TripUser(id = 2L, department = "운영팀", name = "데모 사용자 2"),
        ),
        initialDefaultUserId = 1L,
        initialClients = listOf(
            Client(id = 1L, name = "판교 데모 거래처", point = null),
            Client(id = 2L, name = "서울 데모 거래처", point = null),
        ),
    )



    private val trips = FakeTripRepository(
        clock = clock,
        defaultUserId = { 1L },
        initialTrips = FakeTripSeeder.createTrips(
            clock = clock,
            vehicleId = 1L,
            userId = 1L,
        ),
    )

    init {
        kotlinx.coroutines.runBlocking {
            trips.updatePurpose(
                tripId = 5L,
                purpose = TripPurpose.CLIENT_VISIT,
            )

            trips.updateUser(
                tripId = 6L,
                userId = 2L,
            )

            trips.updateMemo(
                tripId = 10L,
                memo = "정기 업무 교육 참석",
            )
        }
    }



    override val tripTracker: TripTracker = FakeTripTracker(
        repository = trips,
        clock = clock,
        initialOdometerMeters = 252_000L,
    )
    override val tripRepository: TripRepository = trips
    override val profileRepository: ProfileRepository = profiles
    override val reportRepository: ReportRepository = FakeReportRepository(trips, profiles, clock)

    /** Demo controls are intentionally not part of the shared AppContainer contract. */
    val demoTracker: FakeTripTracker get() = tripTracker as FakeTripTracker
}
