
package com.autotrip.data.repository

import androidx.room.withTransaction
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.local.entity.*
import com.autotrip.domain.model.*
import com.autotrip.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProfileRepository(
    private val database: AutoTripDatabase,
    private val sdkModelProvider: suspend () -> String? = { null },
) : ProfileRepository {

    private val dao = database.profileDao()

    override fun observeBusinessProfile(): Flow<BusinessProfile?> =
        dao.observeBusinessProfile().map { entity ->
            entity?.let {
                BusinessProfile(
                    type = BusinessType.valueOf(it.type),
                    name = it.name,
                    registrationNumber = it.registrationNumber,
                )
            }
        }

    override fun observeVehicle(): Flow<VehicleInfo?> =
        dao.observeVehicle().map { entity ->
            entity?.let {
                VehicleInfo(
                    id = it.id,
                    model = it.model,
                    modelSource = ValueSource.valueOf(it.modelSource),
                    plateNumber = it.plateNumber,
                )
            }
        }

    override fun observeUsers(): Flow<List<TripUser>> =
        dao.observeUsers().map { entities ->
            entities.map {
                TripUser(
                    id = it.id,
                    department = it.department,
                    name = it.name,
                )
            }
        }

    override fun observeDefaultUserId(): Flow<Long?> =
        dao.observeSettings().map { it?.defaultUserId }

    override fun observeClients(): Flow<List<Client>> =
        dao.observeClients().map { entities ->
            entities.map {
                Client(
                    id = it.id,
                    name = it.name,
                    point = if (it.latitude != null && it.longitude != null) {
                        GeoPoint(it.latitude, it.longitude)
                    } else {
                        null
                    },
                )
            }
        }

    override suspend fun saveBusinessProfile(
        profile: BusinessProfile,
    ): AutoTripResult<Unit> {
        if (profile.name.isBlank() || profile.registrationNumber.isBlank()) {
            return invalid("Business name and registration number are required")
        }

        dao.saveBusinessProfile(
            BusinessProfileEntity(
                id = 1L,
                type = profile.type.name,
                name = profile.name,
                registrationNumber = profile.registrationNumber,
            )
        )

        return AutoTripResult.Success(Unit)
    }

    override suspend fun saveVehicle(
        model: String?,
        plateNumber: String,
    ): AutoTripResult<VehicleInfo> {
        if (plateNumber.isBlank()) {
            return invalid("Plate number is required")
        }

        val typed = model?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = typed
            ?: sdkModelProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AutoTripResult.Failure(
                AutoTripError.Sdk("CarInfo", "Vehicle model unavailable")
            )

        val saved = VehicleInfo(
            id = 1L,
            model = resolved,
            modelSource = if (typed != null) ValueSource.USER else ValueSource.SDK,
            plateNumber = plateNumber.trim(),
        )

        dao.saveVehicle(
            VehicleEntity(
                id = saved.id,
                model = saved.model,
                modelSource = saved.modelSource.name,
                plateNumber = saved.plateNumber,
            )
        )

        return AutoTripResult.Success(saved)
    }

    override suspend fun addUser(
        department: String,
        name: String,
    ): AutoTripResult<TripUser> {
        if (name.isBlank()) {
            return invalid("User name is required")
        }

        val normalizedDepartment = department.trim()
        val normalizedName = name.trim()

        val id = dao.insertUser(
            TripUserEntity(
                department = normalizedDepartment,
                name = normalizedName,
            )
        )

        return AutoTripResult.Success(
            TripUser(id, normalizedDepartment, normalizedName)
        )
    }

    override suspend fun setDefaultUserId(
        userId: Long?,
    ): AutoTripResult<Unit> = database.withTransaction {
        if (userId != null && dao.countUser(userId) == 0) {
            return@withTransaction invalid("Unknown user ID: $userId")
        }

        dao.saveSettings(
            ProfileSettingsEntity(
                id = 1L,
                defaultUserId = userId,
            )
        )

        AutoTripResult.Success(Unit)
    }

    override suspend fun addClient(
        name: String,
        point: GeoPoint?,
    ): AutoTripResult<Client> {
        if (name.isBlank()) {
            return invalid("Client name is required")
        }

        if (point != null && !validPoint(point)) {
            return invalid("Invalid client coordinates")
        }

        val normalizedName = name.trim()

        val id = dao.insertClient(
            ClientEntity(
                name = normalizedName,
                latitude = point?.latitude,
                longitude = point?.longitude,
            )
        )

        return AutoTripResult.Success(
            Client(id, normalizedName, point)
        )
    }

    override suspend fun removeClient(
        clientId: Long,
    ): AutoTripResult<Unit> {
        val deleted = dao.deleteClient(clientId)

        if (deleted == 0) {
            return invalid("Unknown client ID: $clientId")
        }

        return AutoTripResult.Success(Unit)
    }

    private fun validPoint(point: GeoPoint): Boolean =
        point.latitude.isFinite() &&
                point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 &&
                point.longitude in -180.0..180.0

    private fun invalid(message: String): AutoTripResult.Failure =
        AutoTripResult.Failure(AutoTripError.InvalidState(message))
}
