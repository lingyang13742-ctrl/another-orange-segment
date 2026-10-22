/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SshHostEntity

@Dao
interface SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    suspend fun getAll(): List<SshHostEntity>

    @Query("SELECT * FROM ssh_hosts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: SshHostEntity)

    @Delete
    suspend fun delete(host: SshHostEntity)

    @Query("DELETE FROM ssh_hosts WHERE name = :name")
    suspend fun deleteByName(name: String)
}
