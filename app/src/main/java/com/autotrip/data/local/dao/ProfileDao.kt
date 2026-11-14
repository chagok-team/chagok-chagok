
package com.autotrip.data.local.dao

import androidx.room.*
import com.autotrip.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM business_profiles WHERE id = 1 LIMIT 1")
    fun observeBusinessProfile(): Flow<BusinessProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBusinessProfile(profile: BusinessProfileEntity)

    @Query("SELECT * FROM vehicles WHERE id = 1 LIMIT 1")
    fun observeVehicle(): Flow<VehicleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVehicle(vehicle: VehicleEntity)

    @Query("SELECT * FROM trip_users ORDER BY id ASC")
    fun observeUsers(): Flow<List<TripUserEntity>>

    @Insert
    suspend fun insertUser(user: TripUserEntity): Long

    @Query("SELECT COUNT(*) FROM trip_users WHERE id = :userId")
    suspend fun countUser(userId: Long): Int

    @Query("SELECT * FROM profile_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<ProfileSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: ProfileSettingsEntity)

    @Query("SELECT * FROM clients ORDER BY name ASC, id ASC")
    fun observeClients(): Flow<List<ClientEntity>>

    @Insert
    suspend fun insertClient(client: ClientEntity): Long

    @Query("DELETE FROM clients WHERE id = :clientId")
    suspend fun deleteClient(clientId: Long): Int
}
