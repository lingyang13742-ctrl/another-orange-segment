/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 安全审计日志实体
 * 记录涉及隐私、权限、高危操作的关键事件
 */
@Entity(
    tableName = "security_audit_logs",
    indices = [Index(value = ["timestamp"])],
)
data class SecurityAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 事件类别：plugin / workflow / tool / system
     */
    val category: String,
    /**
     * 动作：installed / integrity_failed / blocked / approved / denied / triggered 等
     */
    val action: String,
    /**
     * 目标标识：如插件ID、工作流ID、工具名
     */
    val target: String = "",
    /**
     * 详情描述
     */
    val detail: String = "",
    /**
     * 结果状态：success / failure / blocked
     */
    val status: String = "",
)
