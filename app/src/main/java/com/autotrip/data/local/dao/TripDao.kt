package com.autotrip.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.autotrip.data.local.entity.TripEditHistoryEntity
import com.autotrip.data.local.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE startedAtEpochMillis >= :startInclusive AND startedAtEpochMillis < :endExclusive ORDER BY startedAtEpochMillis DESC, id DESC")
    fun observeTrips(startInclusive: Long, endExclusive: Long): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    fun observeTrip(tripId: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE endedAtEpochMillis IS NOT NULL AND purposeStatus = 'PENDING' ORDER BY startedAtEpochMillis DESC")
    fun observePendingPurposeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip_edit_history WHERE tripId = :tripId ORDER BY editedAtEpochMillis ASC, id ASC")
    fun observeEditHistory(tripId: Long): Flow<List<TripEditHistoryEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun findTrip(tripId: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE endedAtEpochMillis IS NULL ORDER BY startedAtEpochMillis DESC, id DESC LIMIT 1")
    suspend fun findActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE endedAtEpochMillis IS NOT NULL ORDER BY endedAtEpochMillis DESC, id DESC LIMIT 1")
    suspend fun findLatestFinishedTrip(): TripEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEditHistory(history: TripEditHistoryEntity): Long
}
