/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.DEVICE_EVENT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

private const val TAG = "DeviceEventTrackingService"

/**
 * 常驻前台服务，用于实时监听亮屏/黑屏系统广播（ACTION_SCREEN_ON / ACTION_SCREEN_OFF）。
 *
 * 关键技术约束：
 * - SCREEN_ON / SCREEN_OFF 从 Android 最早版本起就带 FLAG_RECEIVER_REGISTERED_ONLY，
 *   系统不会把它们发给 AndroidManifest 静态声明的 receiver，必须用
 *   Context.registerReceiver() 动态注册，且仅在进程存活期间生效。
 * - 因此要做到「实时」捕获，必须有一个常驻前台服务（持续存活 + 一条常驻通知 +
 *   持续小幅耗电），这是 Android 强制要求，无法做成无通知的静默后台服务。
 * - 国产手机省电策略可能会强杀本服务导致事件断续，这是系统层限制，无法在代码层
 *   彻底解决，只能在设置页提示用户关闭电池优化。
 */
class DeviceEventTrackingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 20004

        /**
         * 读取设置，仅当 deviceEventTrackingEnabled 为真且 Supabase 配置齐全时才拉起常驻服务。
         * 被 RikkaHubApp.onCreate / SupabaseSyncReceiver(BOOT_COMPLETED) / 设置页开关三处调用，
         * 保持判断逻辑一致，避免重复。
         */
        fun startIfEnabled(context: Context) {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val settingsStore = GlobalContext.get().get<SettingsStore>()
                    val settings = settingsStore.settingsFlowRaw.first()
                    val s = settings.systemToolsSetting
                    if (s.deviceEventTrackingEnabled &&
                        s.supabaseEnabled &&
                        s.supabaseUrl.isNotBlank() &&
                        s.supabaseApiKey.isNotBlank()
                    ) {
                        val intent = Intent(context, DeviceEventTrackingService::class.java)
                        try {
                            context.startForegroundService(intent)
                            Log.d(TAG, "startIfEnabled: started foreground service")
                        } catch (e: Exception) {
                            Log.e(TAG, "startIfEnabled: startForegroundService failed", e)
                        }
                    } else {
                        Log.d(
                            TAG,
                            "startIfEnabled: conditions not met " +
                                "(deviceEventTrackingEnabled=${s.deviceEventTrackingEnabled}, " +
                                "supabaseEnabled=${s.supabaseEnabled}, " +
                                "urlBlank=${s.supabaseUrl.isBlank()}, " +
                                "keyBlank=${s.supabaseApiKey.isBlank()}), skip"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startIfEnabled: failed to read settings or start service", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action
                    val eventType = when (action) {
                        Intent.ACTION_SCREEN_ON -> "screen_on"
                        Intent.ACTION_SCREEN_OFF -> "screen_off"
                        else -> null
                    } ?: return
                    handleScreenEvent(eventType)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Android 13+ (TIRAMISU) 动态注册非系统广播必须显式指定 RECEIVER_NOT_EXPORTED，
            // 用 ContextCompat.registerReceiver 自动处理 API 等级差异。
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiver = receiver
            Log.d(TAG, "Screen on/off receiver registered dynamically")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: failed to register screen receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: startForeground failed", e)
        }
        // START_STICKY：被系统回收后尽量重启，但 SCREEN_ON/OFF 只能在进程存活期间捕获，
        // 重启后不会补发丢失的事件，这是系统限制。
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            screenReceiver?.let {
                runCatching { unregisterReceiver(it) }
                    .onFailure { e ->
                        // receiver 可能因 onCreate 注册失败而为 null，或已异常注销，防 IllegalArgumentException
                        Log.w(TAG, "onDestroy: unregisterReceiver failed (maybe not registered)", e)
                    }
            }
            screenReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: error during unregister", e)
        }
        runCatching { serviceScope.cancel() }
            .onFailure { e -> Log.w(TAG, "onDestroy: cancel serviceScope failed", e) }
    }

    private fun handleScreenEvent(eventType: String) {
        serviceScope.launch {
            try {
                val settingsStore = GlobalContext.get().get<SettingsStore>()
                val settings = settingsStore.settingsFlowRaw.first()
                val s = settings.systemToolsSetting
                if (!s.deviceEventTrackingEnabled) {
                    Log.d(TAG, "handleScreenEvent: deviceEventTrackingEnabled=false, skip ($eventType)")
                    return@launch
                }
                if (!s.supabaseEnabled || s.supabaseUrl.isBlank() || s.supabaseApiKey.isBlank()) {
                    Log.w(
                        TAG,
                        "handleScreenEvent: supabase config incomplete, skip " +
                            "(supabaseEnabled=${s.supabaseEnabled}, urlBlank=${s.supabaseUrl.isBlank()}, " +
                            "keyBlank=${s.supabaseApiKey.isBlank()}), eventType=$eventType"
                    )
                    return@launch
                }
                val service = SupabaseService(
                    supabaseUrl = s.supabaseUrl,
                    supabaseApiKey = s.supabaseApiKey,
                    tableName = s.supabaseTableName
                )
                val result = service.insertDeviceEvent(eventType)
                if (result.isSuccess) {
                    Log.d(TAG, "handleScreenEvent: pushed event=$eventType")
                } else {
                    Log.e(TAG, "handleScreenEvent: push failed, eventType=$eventType", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleScreenEvent: unexpected error, eventType=$eventType", e)
            }
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, DEVICE_EVENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("设备状态同步")
            .setContentText("设备状态同步运行中")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}