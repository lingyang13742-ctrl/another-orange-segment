/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SecurityAuditRepository(
    private val dao: SecurityAuditDao,
) {
    companion object {
        private const val MAX_LOGS = 500
        private const val RETAIN_DAYS = 30L
    }

    val recentLogs: Flow<List<SecurityAuditEntity>> = dao.getRecent(MAX_LOGS)

    suspend fun log(
        category: String,
        action: String,
        target: String = "",
        detail: String = "",
        status: String = "",
    ) = withContext(Dispatchers.IO) {
        dao.insert(
            SecurityAuditEntity(
                category = category,
                action = action,
                target = target,
                detail = detail,
                status = status,
            )
        )
        // 自动清理：保留最近 30 天或最多 500 条
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24 * 60 * 60 * 1000L
        dao.deleteOlderThan(cutoff)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
