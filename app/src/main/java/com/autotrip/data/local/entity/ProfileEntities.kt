
package com.autotrip.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "business_profiles")
data class BusinessProfileEntity(
    @PrimaryKey
    val id: Long = 1L,
    val type: String,
    val name: String,
    val registrationNumber: String,
)

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey
    val id: Long,
    val model: String,
    val modelSource: String,
    val plateNumber: String,
)

@Entity(tableName = "trip_users")
data class TripUserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val department: String,
    val name: String,
)

@Entity(tableName = "profile_settings")
data class ProfileSettingsEntity(
    @PrimaryKey
    val id: Long = 1L,
    val defaultUserId: Long?,
)

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
)
