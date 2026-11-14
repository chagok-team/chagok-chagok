package com.autotrip.fake

import com.autotrip.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeProfileRepositoryTest {
    @Test fun userAndDefaultUser() = runBlocking {
        val repo = FakeProfileRepository()
        val user = (repo.addUser("영업", "김테스트") as AutoTripResult.Success).value
        assertEquals(1L, user.id)
        assertTrue(repo.setDefaultUserId(user.id) is AutoTripResult.Success)
        assertEquals(user.id, repo.observeDefaultUserId().first())
        assertTrue(repo.setDefaultUserId(999L) is AutoTripResult.Failure)
    }

    @Test fun clientListIsSortedAndRemovalWorks() = runBlocking {
        val repo = FakeProfileRepository()
        repo.addClient("제타", null)
        val alpha = (repo.addClient("알파", GeoPoint(37.5, 127.0)) as AutoTripResult.Success).value
        assertEquals(listOf("알파", "제타"), repo.observeClients().first().map { it.name })
        assertTrue(repo.removeClient(alpha.id) is AutoTripResult.Success)
        assertEquals(listOf("제타"), repo.observeClients().first().map { it.name })
    }

    @Test fun vehicleUsesUserModelOrFakeSdkModel() = runBlocking {
        val repo = FakeProfileRepository(fakeSdkModel = "테스트 차량")
        val sdk = (repo.saveVehicle(null, "12가3456") as AutoTripResult.Success).value
        assertEquals(ValueSource.SDK, sdk.modelSource)
        val user = (repo.saveVehicle("직접 입력", "12가3456") as AutoTripResult.Success).value
        assertEquals(ValueSource.USER, user.modelSource)
        assertEquals(sdk.id, user.id)
    }

    @Test fun missingSdkModelReturnsFailure() = runBlocking {
        val repo = FakeProfileRepository()
        assertTrue(repo.saveVehicle(null, "12가3456") is AutoTripResult.Failure)
    }
}
