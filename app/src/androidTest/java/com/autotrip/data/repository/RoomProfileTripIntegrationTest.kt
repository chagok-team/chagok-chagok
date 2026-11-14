
package com.autotrip.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RoomProfileTripIntegrationTest {

    @Test
    fun defaultUserIsAppliedToTripAndPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "profile-trip-integration-test.db"
        val clock = Clock.system(ZoneId.of("Asia/Seoul"))

        context.deleteDatabase(dbName)

        fun openDatabase(): AutoTripDatabase =
            Room.databaseBuilder(
                context,
                AutoTripDatabase::class.java,
                dbName
            ).build()

        var database = openDatabase()

        try {
            var profiles = RoomProfileRepository(database)
            var trips = RoomTripRepository(
                database = database,
                clock = clock,
                defaultUserId = {
                    profiles.observeDefaultUserId().first()
                }
            )

            val userAResult = profiles.addUser("개발팀", "운전자 A")
            val userBResult = profiles.addUser("영업팀", "운전자 B")

            assertTrue(userAResult is AutoTripResult.Success)
            assertTrue(userBResult is AutoTripResult.Success)

            val userB = (userBResult as AutoTripResult.Success).value

            assertTrue(
                profiles.setDefaultUserId(userB.id) is AutoTripResult.Success
            )

            val start = Instant.parse("2026-11-13T01:00:00Z")

            val startResult = trips.beginTrip(
                vehicleId = 1L,
                source = TripSource.MANUAL,
                startedAt = start,
                odometerStart = OdometerReading(
                    meters = null,
                    status = OdometerCaptureStatus.UNSUPPORTED,
                    capturedAt = null
                )
            )

            assertTrue(startResult is AutoTripResult.Success)

            val trip = (startResult as AutoTripResult.Success).value

            assertEquals(userB.id, trip.userId)

            database.close()

            database = openDatabase()
            profiles = RoomProfileRepository(database)
            trips = RoomTripRepository(
                database = database,
                clock = clock,
                defaultUserId = {
                    profiles.observeDefaultUserId().first()
                }
            )

            assertEquals(
                userB.id,
                profiles.observeDefaultUserId().first()
            )

            assertEquals(
                userB.id,
                trips.observeTrip(trip.id).first()?.userId
            )

        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }
}
