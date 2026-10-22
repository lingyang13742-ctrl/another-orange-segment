/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记忆库条目实体
 * 存储每条记忆的元数据和内容
 */
@Entity(tableName = "memory_bank")
data class MemoryBankEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    
    /** 记忆内容 */
    @ColumnInfo("content")
    val content: String = "",
    
    /** 记忆类型: message, phase_summary, daily_summary, manual */
    @ColumnInfo("type")
    val type: String = "message",
    
    /** 关联的对话ID（可选） */
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    
    /** 关联的助手ID */
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    
    /** 消息角色: user, assistant */
    @ColumnInfo("role")
    val role: String? = null,
    
    /** 创建时间戳 */
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 所属日期（用于每日总结分组），格式 yyyy-MM-dd */
    @ColumnInfo("date_group")
    val dateGroup: String? = null,
    
    /** 向量化状态: pending, done, failed */
    @ColumnInfo("vector_status")
    val vectorStatus: String = "pending",
    
    /** 向量化重试次数 */
    @ColumnInfo("vector_retry_count")
    val vectorRetryCount: Int = 0,

    /** Embedding 向量（JSON 格式的浮点数组） */
    @ColumnInfo("embedding")
    val embedding: String? = null,
)



