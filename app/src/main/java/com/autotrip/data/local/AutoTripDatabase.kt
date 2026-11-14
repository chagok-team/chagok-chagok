
package com.autotrip.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.autotrip.data.local.dao.ProfileDao
import com.autotrip.data.local.dao.TripDao
import com.autotrip.data.local.entity.*

@Database(
    entities = [
        TripEntity::class,
        TripEditHistoryEntity::class,
        BusinessProfileEntity::class,
        VehicleEntity::class,
        TripUserEntity::class,
        ProfileSettingsEntity::class,
        ClientEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AutoTripDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    abstract fun profileDao(): ProfileDao
}
