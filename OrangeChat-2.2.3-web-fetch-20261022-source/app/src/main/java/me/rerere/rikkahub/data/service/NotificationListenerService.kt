/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * 通知监听服务
 * 用于捕获和存储设备上的通知
 */
class RikkaNotificationListenerService : NotificationListenerService() {
    
    companion object {
        private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
        val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()
        
        private val _recentNotifications = MutableStateFlow<List<NotificationData>>(emptyList())
        val recentNotifications: StateFlow<List<NotificationData>> = _recentNotifications.asStateFlow()
        
        private const val MAX_STORED_NOTIFICATIONS = 500
        private const val RECENT_THRESHOLD_MS = 86_400_000L // 24小时内的通知 (24 * 60 * 60 * 1000)
        
        /**
         * 获取今日通知
         */
        fun getTodayNotifications(): List<NotificationData> {
            val now = System.currentTimeMillis()
            val startOfDay = now - (now % TimeUnit.DAYS.toMillis(1))
            return _notifications.value.filter { it.timestamp >= startOfDay }
        }
        
        /**
         * 获取指定时间段内的通知
         */
        fun getNotificationsInRange(startTime: Long, endTime: Long): List<NotificationData> {
            return _notifications.value.filter { 
                it.timestamp in startTime..endTime 
            }
        }
        
        /**
         * 获取指定应用的通知
         */
        fun getNotificationsForPackage(packageName: String): List<NotificationData> {
            return _notifications.value.filter { it.packageName == packageName }
        }
        
        /**
         * 清除所有缓存的通知
         */
        fun clearNotifications() {
            _notifications.value = emptyList()
            _recentNotifications.value = emptyList()
        }
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle.connected = true
        // 服务连接时，获取所有活动通知
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle.connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        addNotification(sbn)
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // 通知被移除时，我们保留记录但标记为已移除
        removeNotification(sbn)
    }
    
    private fun refreshNotifications() {
        try {
            val activeNotifications = activeNotifications ?: return
            val now = System.currentTimeMillis()
            
            val notificationList = activeNotifications
                .mapNotNull { parseNotification(it) }
                .sortedByDescending { it.timestamp }
            
            _notifications.value = notificationList.take(MAX_STORED_NOTIFICATIONS)
            updateRecentNotifications(now)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun addNotification(sbn: StatusBarNotification) {
        val notification = parseNotification(sbn) ?: return
        val now = System.currentTimeMillis()

        // Dispatch to workflow notification triggers (no-op when no workflow uses them).
        runCatching {
            me.rerere.rikkahub.workflow.trigger.NotificationTriggerDispatcher
                .onPosted(notification.packageName, notification.title, notification.content)
        }

        val currentList = _notifications.value.toMutableList()
        
        // 避免重复
        val existingIndex = currentList.indexOfFirst { it.id == notification.id && it.packageName == notification.packageName }
        if (existingIndex >= 0) {
            currentList[existingIndex] = notification
        } else {
            currentList.add(0, notification)
        }
        
        // 限制存储数量
        _notifications.value = currentList
            .sortedByDescending { it.timestamp }
            .take(MAX_STORED_NOTIFICATIONS)
        
        updateRecentNotifications(now)
    }
    
    private fun removeNotification(sbn: StatusBarNotification) {
        val key = sbn.key
        val currentList = _notifications.value.toMutableList()
        val index = currentList.indexOfFirst { it.key == key }
        
        if (index >= 0) {
            currentList.removeAt(index)
            _notifications.value = currentList
            updateRecentNotifications(System.currentTimeMillis())
        }
    }
    
    private fun updateRecentNotifications(now: Long) {
        val threshold = now - RECENT_THRESHOLD_MS
        _recentNotifications.value = _notifications.value
            .filter { it.timestamp >= threshold }
    }
    
    private fun parseNotification(sbn: StatusBarNotification): NotificationData? {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            val packageName = sbn.packageName
            val appName = getAppName(packageName)
            
            // 获取通知标题和内容
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: notification.tickerText?.toString()
                ?: ""
            
            val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""
            
            // 跳过空通知
            if (title.isBlank() && content.isBlank()) {
                return null
            }
            
            // 跳过某些系统通知
            if (shouldSkipNotification(packageName, notification)) {
                return null
            }
            
            return NotificationData(
                key = sbn.key,
                id = sbn.id,
                packageName = packageName,
                appName = appName,
                title = title,
                content = content.take(1000), // 限制内容长度
                timestamp = sbn.postTime,
                category = notification.category,
                priority = notification.priority,
                isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isClearable = sbn.isClearable
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
    
    private fun shouldSkipNotification(packageName: String, notification: Notification): Boolean {
        // 跳过某些系统通知
        val skipPackages = setOf(
            "android",
            "com.android.systemui"
        )
        
        if (packageName in skipPackages) {
            return true
        }
        
        // 跳过持续进行的通知（如音乐播放器）
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            return true
        }
        
        return false
    }
}

/**
 * 通知数据模型
 */
data class NotificationData(
    val key: String,              // 唯一标识
    val id: Int,                  // 通知ID
    val packageName: String,      // 包名
    val appName: String,          // 应用名称
    val title: String,            // 标题
    val content: String,          // 内容
    val timestamp: Long,          // 时间戳
    val category: String? = null, // 分类
    val priority: Int = 0,        // 优先级
    val isOngoing: Boolean = false, // 是否持续进行
    val isClearable: Boolean = true // 是否可清除
)