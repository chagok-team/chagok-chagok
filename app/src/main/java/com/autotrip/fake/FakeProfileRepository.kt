package com.autotrip.fake

import com.autotrip.domain.model.*
import com.autotrip.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** In-memory demo profile store; values disappear when the process is killed. */
class FakeProfileRepository(
    initialBusiness: BusinessProfile? = null,
    initialVehicle: VehicleInfo? = null,
    initialUsers: List<TripUser> = emptyList(),
    initialDefaultUserId: Long? = null,
    initialClients: List<Client> = emptyList(),
    /** Fake CarInfo result. Null means SDK model lookup is unavailable. */
    private val fakeSdkModel: String? = null,
) : ProfileRepository {
    private val business = MutableStateFlow(initialBusiness)
    private val vehicle = MutableStateFlow(initialVehicle)
    private val users = MutableStateFlow(initialUsers.toList())
    private val defaultUserId = MutableStateFlow(initialDefaultUserId?.takeIf { id -> initialUsers.any { it.id == id } })
    private val clients = MutableStateFlow(initialClients.sortedWith(compareBy<Client> { it.name }.thenBy { it.id }))
    private val userIds = AtomicLong((initialUsers.maxOfOrNull { it.id } ?: 0L) + 1L)
    private val clientIds = AtomicLong((initialClients.maxOfOrNull { it.id } ?: 0L) + 1L)

    override fun observeBusinessProfile(): Flow<BusinessProfile?> = business.asStateFlow()
    override fun observeVehicle(): Flow<VehicleInfo?> = vehicle.asStateFlow()
    override fun observeUsers(): Flow<List<TripUser>> = users.asStateFlow()
    override fun observeDefaultUserId(): Flow<Long?> = defaultUserId.asStateFlow()
    override fun observeClients(): Flow<List<Client>> = clients.asStateFlow()

    override suspend fun saveBusinessProfile(profile: BusinessProfile): AutoTripResult<Unit> = synchronized(this) {
        if (profile.name.isBlank() || profile.registrationNumber.isBlank()) {
            return@synchronized invalid("Business name and registration number are required")
        }
        business.value = profile
        AutoTripResult.Success(Unit)
    }

    override suspend fun saveVehicle(model: String?, plateNumber: String): AutoTripResult<VehicleInfo> = synchronized(this) {
        if (plateNumber.isBlank()) return@synchronized invalid("Plate number is required")
        val typed = model?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = typed ?: fakeSdkModel?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@synchronized AutoTripResult.Failure(AutoTripError.Sdk("CarInfo", "Vehicle model unavailable"))
        val saved = VehicleInfo(
            id = vehicle.value?.id ?: 1L,
            model = resolved,
            modelSource = if (typed != null) ValueSource.USER else ValueSource.SDK,
            plateNumber = plateNumber.trim(),
        )
        vehicle.value = saved
        AutoTripResult.Success(saved)
    }

    override suspend fun addUser(department: String, name: String): AutoTripResult<TripUser> = synchronized(this) {
        if (name.isBlank()) return@synchronized invalid("User name is required")
        val added = TripUser(userIds.getAndIncrement(), department.trim(), name.trim())
        users.value = users.value + added
        AutoTripResult.Success(added)
    }

    override suspend fun setDefaultUserId(userId: Long?): AutoTripResult<Unit> = synchronized(this) {
        if (userId != null && users.value.none { it.id == userId }) {
            return@synchronized invalid("Unknown user ID: $userId")
        }
        defaultUserId.value = userId
        AutoTripResult.Success(Unit)
    }

    override suspend fun addClient(name: String, point: GeoPoint?): AutoTripResult<Client> = synchronized(this) {
        if (name.isBlank()) return@synchronized invalid("Client name is required")
        if (point != null && !validPoint(point)) return@synchronized invalid("Invalid client coordinates")
        val added = Client(clientIds.getAndIncrement(), name.trim(), point)
        clients.value = (clients.value + added).sortedWith(compareBy<Client> { it.name }.thenBy { it.id })
        AutoTripResult.Success(added)
    }

    override suspend fun removeClient(clientId: Long): AutoTripResult<Unit> = synchronized(this) {
        if (clients.value.none { it.id == clientId }) return@synchronized invalid("Unknown client ID: $clientId")
        clients.value = clients.value.filterNot { it.id == clientId }
        AutoTripResult.Success(Unit)
    }

    private fun validPoint(point: GeoPoint): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    private fun invalid(message: String): AutoTripResult.Failure =
        AutoTripResult.Failure(AutoTripError.InvalidState(message))
}
