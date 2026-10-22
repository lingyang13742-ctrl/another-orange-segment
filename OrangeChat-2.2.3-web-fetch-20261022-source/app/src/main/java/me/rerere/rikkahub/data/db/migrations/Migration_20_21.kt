/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 20 to 21: Fix memory_bank table schema
 * - Remove updated_at column
 * - Make assistant_id nullable
 */
val Migration_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite doesn't support DROP COLUMN before 3.35.0, so we need to recreate the table

        // Step 1: Create new table with correct schema (no DEFAULT, no indexes - matching Entity)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `memory_bank_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `content` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `role` TEXT,
                `assistant_id` TEXT,
                `conversation_id` TEXT,
                `date_group` TEXT,
                `vector_status` TEXT NOT NULL,
                `vector_retry_count` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL
            )
        """.trimIndent())

        // Step 2: Copy data from old table to new table (excluding updated_at)
        db.execSQL("""
            INSERT INTO `memory_bank_new` (`id`, `content`, `type`, `role`, `assistant_id`, `conversation_id`, `date_group`, `vector_status`, `vector_retry_count`, `created_at`)
            SELECT `id`, `content`, `type`, `role`, `assistant_id`, `conversation_id`, `date_group`, `vector_status`, `vector_retry_count`, `created_at`
            FROM `memory_bank`
        """.trimIndent())

        // Step 3: Drop old table
        db.execSQL("DROP TABLE IF EXISTS `memory_bank`")

        // Step 4: Rename new table to original name
        db.execSQL("ALTER TABLE `memory_bank_new` RENAME TO `memory_bank`")

        // Also recreate memory_vector table to remove foreign key (Entity doesn't declare one)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `memory_vector_new` (
                `memory_id` INTEGER NOT NULL,
                `vector` TEXT NOT NULL,
                `dimensions` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`memory_id`)
            )
        """.trimIndent())

        // Copy data
        db.execSQL("""
            INSERT INTO `memory_vector_new` (`memory_id`, `vector`, `dimensions`, `model`, `created_at`)
            SELECT `memory_id`, `vector`, `dimensions`, `model`, `created_at`
            FROM `memory_vector`
        """.trimIndent())

        // Drop old table and rename
        db.execSQL("DROP TABLE IF EXISTS `memory_vector`")
        db.execSQL("ALTER TABLE `memory_vector_new` RENAME TO `memory_vector`")
    }
}