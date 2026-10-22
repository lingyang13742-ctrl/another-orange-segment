/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

@file:Suppress("unused")

package me.rerere.rikkahub.data.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForeground: Long,
    val lastTimeUsed: Long,
    val launchCount: Int = 0
)

data class AppTrajectoryEvent(
    val packageName: String,
    val appName: String,
    val eventType: String,
    val timestamp: Long
)

class AppUsageService(private val context: Context) {
    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private val packageManager by lazy {
        context.packageManager
    }

    suspend fun getTodayUsageStats(): Result<List<AppUsageInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            usageStats
                .filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }
                .map { stats ->
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(stats.packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        stats.packageName
                    }
                    AppUsageInfo(
                        packageName = stats.packageName,
                        appName = appName,
                        totalTimeInForeground = stats.totalTimeInForeground,
                        lastTimeUsed = stats.lastTimeUsed,
                        launchCount = 0
                    )
                }
        }
    }

    suspend fun getTodayTrajectory(): Result<List<AppTrajectoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val events = mutableListOf<AppTrajectoryEvent>()
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                val eventType = when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> "打开"
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> "关闭"
                    else -> continue
                }

                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(event.packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    event.packageName
                }

                events.add(
                    AppTrajectoryEvent(
                        packageName = event.packageName,
                        appName = appName,
                        eventType = eventType,
                        timestamp = event.timeStamp
                    )
                )
            }

            events.sortedByDescending { it.timestamp }
        }
    }

    fun getForegroundApp(): Result<String> = runCatching {
        val calendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MINUTE, -1)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        usageStats
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName ?: throw IllegalStateException("无法获取前台应用")
    }

    fun formatUsageTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (millis % (1000 * 60)) / 1000
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}