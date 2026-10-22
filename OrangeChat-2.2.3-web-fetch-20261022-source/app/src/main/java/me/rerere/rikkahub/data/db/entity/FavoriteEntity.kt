/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["ref_key"], unique = true),
        Index(value = ["type"]),
        Index(value = ["created_at"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("ref_key")
    val refKey: String,
    @ColumnInfo("ref_json")
    val refJson: String,
    @ColumnInfo("snapshot_json")
    val snapshotJson: String,
    @ColumnInfo("meta_json")
    val metaJson: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
