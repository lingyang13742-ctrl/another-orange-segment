/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_24_25 : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 在 memory_bank 表中添加 embedding 字段（如果尚未存在）
        try {
            db.execSQL("ALTER TABLE memory_bank ADD COLUMN embedding TEXT")
        } catch (e: Exception) {
            // 列已存在则忽略
        }
    }
}
