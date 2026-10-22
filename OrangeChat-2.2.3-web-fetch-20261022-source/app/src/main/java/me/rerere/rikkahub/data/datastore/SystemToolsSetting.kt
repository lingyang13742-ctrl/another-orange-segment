/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class SystemToolsSetting(
    val amapApiKey: String = "",
    val notificationAccess: Boolean = false,
    val cameraAccess: Boolean = false,
    val locationAccess: Boolean = false,
    val appUsageAccess: Boolean = false,
    val ocrProvider: String = "local",
    val ocrApiKey: String = "",
    val ocrApiUrl: String = "",
    val ocrModel: String = "",

    // Feature 1: Location exploration
    val locationExploreEnabled: Boolean = false,
    val locationExploreRadius: Int = 1000,

    // Feature 2: Notification query
    val notificationQueryEnabled: Boolean = false,

    // Feature 3: App usage tracking
    val appUsageEnabled: Boolean = false,

    // Feature 6: Camera OCR
    val cameraOcrEnabled: Boolean = false,

    // Feature 12: Proactive messaging
    val proactiveMessagingEnabled: Boolean = false,
    val proactiveMessagingMinInterval: Int = 30,
    val proactiveMessagingMaxInterval: Int = 90,

    // Feature 13: Supabase data sync
    val supabaseEnabled: Boolean = false,
    val supabaseUrl: String = "",
    val supabaseApiKey: String = "",
    val supabaseTableName: String = "device_data",

    // Feature 22: Boot/Screen event tracking (realtime push to Supabase)
    val deviceEventTrackingEnabled: Boolean = false,

    // Feature 14: Gadgetbridge health data
    val gadgetbridgeEnabled: Boolean = false,
    val gadgetbridgeDbPath: String = "",

    // Feature 15: Alarm
    val alarmEnabled: Boolean = false,

    // Feature 18: Timer
    val timerEnabled: Boolean = false,

    // Feature 16: Battery info
    val batteryEnabled: Boolean = false,

    // Feature 17: Music control
    val musicEnabled: Boolean = false,

    // Feature 19: SMS reading
    val smsEnabled: Boolean = false,

    // Feature 21: AI Song Generation (Suno + RVC)

    // New system tools (batch 1)
    val torchEnabled: Boolean = false,
    val toastEnabled: Boolean = false,
    val vibrateEnabled: Boolean = false,
    val brightnessEnabled: Boolean = false,
    val volumeEnabled: Boolean = false,
    val wifiInfoEnabled: Boolean = false,
    val telephonyInfoEnabled: Boolean = false,
    val shareEnabled: Boolean = false,
    val setWallpaperEnabled: Boolean = false,
    val wakeScreenEnabled: Boolean = false,
    val scanMediaEnabled: Boolean = false,
    val postNotificationEnabled: Boolean = false,
    val storageInfoEnabled: Boolean = false,
    val appSwitchEnabled: Boolean = false,

    // App Lock: 锁定指定 App, 检测到其被打开时拦截并要求密码解锁
    val appLockEnabled: Boolean = false,

    // Fingerprint: verify_fingerprint 工具, 弹出系统指纹/人脸验证框验证用户身份
    val fingerprintEnabled: Boolean = false,
) {
    fun getEnabledOptions(): Set<me.rerere.rikkahub.data.ai.tools.SystemToolOption> {
        val options = mutableSetOf<me.rerere.rikkahub.data.ai.tools.SystemToolOption>()
        if (locationAccess || locationExploreEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Location)
        if (notificationAccess || notificationQueryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Notifications)
        if (appUsageAccess || appUsageEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppUsage)
        if (cameraAccess || cameraOcrEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Camera)
        if (locationExploreEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.ExploreNearby)
        if (gadgetbridgeEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Gadgetbridge)
        if (alarmEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Alarm)
        if (timerEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Timer)
        if (batteryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Battery)
        if (musicEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Music)
        if (smsEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Sms)
        // SupabaseQuery 现在由外置记忆库配置驱动
        if (torchEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Torch)
        if (toastEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Toast)
        if (vibrateEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Vibrate)
        if (brightnessEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Brightness)
        if (volumeEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Volume)
        if (wifiInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.WifiInfo)
        if (telephonyInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.TelephonyInfo)
        if (shareEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Share)
        if (setWallpaperEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SetWallpaper)
        if (wakeScreenEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.WakeScreen)
        if (scanMediaEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.ScanMedia)
        if (postNotificationEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.PostNotification)
        if (storageInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.StorageInfo)
        if (appSwitchEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppSwitch)
        if (appLockEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppLock)
        if (fingerprintEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Fingerprint)
        return options
    }
}