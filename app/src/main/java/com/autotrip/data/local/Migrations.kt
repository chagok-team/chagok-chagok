
package com.autotrip.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `business_profiles` (
                    `id` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `registrationNumber` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vehicles` (
                    `id` INTEGER NOT NULL,
                    `model` TEXT NOT NULL,
                    `modelSource` TEXT NOT NULL,
                    `plateNumber` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trip_users` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `department` TEXT NOT NULL,
                    `name` TEXT NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profile_settings` (
                    `id` INTEGER NOT NULL,
                    `defaultUserId` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `clients` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `latitude` REAL,
                    `longitude` REAL
                )
                """.trimIndent()
            )
        }
    }
}
