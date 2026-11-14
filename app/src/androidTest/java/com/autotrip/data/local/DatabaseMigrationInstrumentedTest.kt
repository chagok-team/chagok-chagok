
package com.autotrip.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {

    @Test
    fun migration1To2PreservesTripsAndHistory() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val dbName = "migration-1-to-2-test.db"

        context.deleteDatabase(dbName)

        var roomDb: AutoTripDatabase? = null

        try {
            // 실제 Room v1에서 생성한 스키마를 준비한다.
            val sqlite = context.openOrCreateDatabase(
                dbName,
                Context.MODE_PRIVATE,
                null
            )

            try {
                sqlite.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trips` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `vehicleId` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `endedAtEpochMillis` INTEGER,
                        `odometerStartMeters` INTEGER,
                        `odometerStartStatus` TEXT NOT NULL,
                        `odometerStartCapturedAtEpochMillis` INTEGER,
                        `odometerEndMeters` INTEGER,
                        `odometerEndStatus` TEXT NOT NULL,
                        `odometerEndCapturedAtEpochMillis` INTEGER,
                        `startLatitude` REAL,
                        `startLongitude` REAL,
                        `startLabel` TEXT,
                        `endLatitude` REAL,
                        `endLongitude` REAL,
                        `endLabel` TEXT,
                        `destinationName` TEXT,
                        `userId` INTEGER,
                        `purpose` TEXT,
                        `purposeStatus` TEXT NOT NULL,
                        `purposeInputMethod` TEXT,
                        `purposeInputTimeEpochMillis` INTEGER,
                        `voiceRawText` TEXT,
                        `memo` TEXT
                    )
                    """.trimIndent()
                )

                sqlite.execSQL(
                    "CREATE INDEX `index_trips_startedAtEpochMillis` " +
                            "ON `trips` (`startedAtEpochMillis`)"
                )

                sqlite.execSQL(
                    "CREATE INDEX `index_trips_purposeStatus` " +
                            "ON `trips` (`purposeStatus`)"
                )

                sqlite.execSQL(
                    "CREATE INDEX `index_trips_endedAtEpochMillis` " +
                            "ON `trips` (`endedAtEpochMillis`)"
                )

                sqlite.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trip_edit_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tripId` INTEGER NOT NULL,
                        `field` TEXT NOT NULL,
                        `oldValue` TEXT,
                        `newValue` TEXT,
                        `editedAtEpochMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )

                sqlite.execSQL(
                    "CREATE INDEX `index_trip_edit_history_tripId` " +
                            "ON `trip_edit_history` (`tripId`)"
                )

                sqlite.execSQL(
                    """
                    INSERT INTO `trips` (
                        `id`, `vehicleId`, `source`,
                        `startedAtEpochMillis`, `endedAtEpochMillis`,
                        `odometerStartStatus`, `odometerEndStatus`,
                        `purposeStatus`
                    ) VALUES (
                        1, 1, 'MANUAL',
                        1762995600000, 1762999200000,
                        'UNSUPPORTED', 'UNSUPPORTED',
                        'PENDING'
                    )
                    """.trimIndent()
                )

                sqlite.execSQL(
                    """
                    INSERT INTO `trip_edit_history` (
                        `id`, `tripId`, `field`,
                        `oldValue`, `newValue`,
                        `editedAtEpochMillis`
                    ) VALUES (
                        1, 1, 'MEMO',
                        NULL, '마이그레이션 테스트',
                        1762999200000
                    )
                    """.trimIndent()
                )

                sqlite.version = 1
            } finally {
                sqlite.close()
            }

            // Room이 실제로 1 -> 2 마이그레이션을 수행한다.
            roomDb = Room.databaseBuilder(
                context,
                AutoTripDatabase::class.java,
                dbName
            )
                .addMigrations(Migrations.MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()

            val db = roomDb.openHelper.writableDatabase

            assertEquals(2, db.version)

            db.query("SELECT COUNT(*) FROM trips").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }

            db.query(
                "SELECT memo, source FROM trips WHERE id = 1"
            ).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertEquals("MANUAL", it.getString(1))
            }

            db.query(
                "SELECT newValue FROM trip_edit_history WHERE tripId = 1"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("마이그레이션 테스트", it.getString(0))
            }

            val newTables = listOf(
                "business_profiles",
                "vehicles",
                "trip_users",
                "profile_settings",
                "clients"
            )

            newTables.forEach { table ->
                db.query(
                    "SELECT name FROM sqlite_master " +
                            "WHERE type = 'table' AND name = '$table'"
                ).use {
                    assertTrue(
                        "Missing table: $table",
                        it.moveToFirst()
                    )
                }
            }

        } finally {
            roomDb?.close()
            context.deleteDatabase(dbName)
        }
    }
}
