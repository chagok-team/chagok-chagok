package com.autotrip.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autotrip.domain.model.EditableField
import com.autotrip.domain.model.TripEditHistory
import java.time.Instant

@Entity(
    tableName = "trip_edit_history",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("tripId")],
)
data class TripEditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val editedAtEpochMillis: Long,
)

fun TripEditHistory.toEntity(): TripEditHistoryEntity = TripEditHistoryEntity(
    id = id, tripId = tripId, field = field.name,
    oldValue = oldValue, newValue = newValue,
    editedAtEpochMillis = editedAt.toEpochMilli(),
)

fun TripEditHistoryEntity.toDomain(): TripEditHistory = TripEditHistory(
    id = id, tripId = tripId, field = EditableField.valueOf(field),
    oldValue = oldValue, newValue = newValue,
    editedAt = Instant.ofEpochMilli(editedAtEpochMillis),
)
