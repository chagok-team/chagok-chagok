
package com.autotrip.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomProfileRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: AutoTripDatabase
    private lateinit var repository: RoomProfileRepository

    private val dbName = "profile-test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(
            context,
            AutoTripDatabase::class.java,
            dbName
        ).build()

        repository = RoomProfileRepository(database)
    }

    @Test
    fun businessProfilePersistsAfterReopen() = runBlocking {
        val profile = BusinessProfile(
            type = BusinessType.CORPORATION,
            name = "AutoTrip 테스트 법인",
            registrationNumber = "123-45-67890"
        )

        assertTrue(repository.saveBusinessProfile(profile) is AutoTripResult.Success)

        database.close()
        openDatabase()

        assertEquals(profile, repository.observeBusinessProfile().first())
    }

    @Test
    fun vehiclePersistsAfterReopen() = runBlocking {
        assertTrue(
            repository.saveVehicle("현대 아이오닉 5", "12가3456")
                    is AutoTripResult.Success
        )

        database.close()
        openDatabase()

        val vehicle = repository.observeVehicle().first()

        assertNotNull(vehicle)
        assertEquals("현대 아이오닉 5", vehicle?.model)
        assertEquals("12가3456", vehicle?.plateNumber)
        assertEquals(ValueSource.USER, vehicle?.modelSource)
    }

    @Test
    fun userAndDefaultUserPersistAfterReopen() = runBlocking {
        val result = repository.addUser("개발팀", "홍길동")
        assertTrue(result is AutoTripResult.Success)

        val user = (result as AutoTripResult.Success).value

        assertTrue(
            repository.setDefaultUserId(user.id) is AutoTripResult.Success
        )

        database.close()
        openDatabase()

        val users = repository.observeUsers().first()
        val defaultUserId = repository.observeDefaultUserId().first()

        assertEquals(1, users.size)
        assertEquals(user.id, users.first().id)
        assertEquals(user.id, defaultUserId)
    }

    @Test
    fun clientPersistsAfterReopen() = runBlocking {
        val point = GeoPoint(37.3947, 127.1112)

        val result = repository.addClient(
            "현대오토에버 판교",
            point
        )

        assertTrue(result is AutoTripResult.Success)

        database.close()
        openDatabase()

        val clients = repository.observeClients().first()

        assertEquals(1, clients.size)
        assertEquals("현대오토에버 판교", clients.first().name)
        assertEquals(point, clients.first().point)
    }

    @Test
    fun invalidDataIsRejected() = runBlocking {
        assertTrue(
            repository.addUser("", "") is AutoTripResult.Failure
        )

        assertTrue(
            repository.setDefaultUserId(9999L) is AutoTripResult.Failure
        )

        assertTrue(
            repository.addClient(
                "잘못된 좌표",
                GeoPoint(200.0, 300.0)
            ) is AutoTripResult.Failure
        )

        assertTrue(
            repository.removeClient(9999L) is AutoTripResult.Failure
        )
    }

    @Test
    fun clientDeletionPersistsAfterReopen() = runBlocking {
        val result = repository.addClient("삭제할 거래처", null)
        assertTrue(result is AutoTripResult.Success)

        val client = (result as AutoTripResult.Success).value

        assertTrue(
            repository.removeClient(client.id) is AutoTripResult.Success
        )

        database.close()
        openDatabase()

        assertTrue(repository.observeClients().first().isEmpty())
    }
}
