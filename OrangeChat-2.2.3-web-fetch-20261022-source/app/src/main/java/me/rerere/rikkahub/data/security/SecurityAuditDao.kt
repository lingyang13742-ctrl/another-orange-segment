/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityAuditDao {
    @Insert
    suspend fun insert(entity: SecurityAuditEntity)

    @Query("SELECT * FROM security_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<SecurityAuditEntity>>

    @Query("SELECT * FROM security_audit_logs ORDER BY timestamp DESC")
    suspend fun getAll(): List<SecurityAuditEntity>

    @Query("DELETE FROM security_audit_logs WHERE timestamp < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)

    @Query("DELETE FROM security_audit_logs")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM security_audit_logs")
    suspend fun count(): Int
}
