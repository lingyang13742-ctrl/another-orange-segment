/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.DEVICE_EVENT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceEventAiTrigger"

/**
 * 激进模式常驻前台服务：
 * - 监听亮屏/锁屏（ACTION_SCREEN_ON / ACTION_SCREEN_OFF 动态广播）
 * - 每 3 秒轮询 UsageStatsManager.queryEvents 检测应用切换/回桌面
 * - 收到事件后按用户设置的秒数防抖（批量收集），默认 30 秒，限流检查后触发 AI 思考
 * - AI 思考复用 ProactiveMessageTriggerService 的核心逻辑
 */
class DeviceEventAiTriggerService : Service() {

    companion object {
        const val NOTIFICATION_ID = 20005
        private const val POLL_INTERVAL_MS = 3000L
        // 默认防抖延迟（毫秒），仅在读取设置失败/值非法时兜底使用
        private const val DEFAULT_DEBOUNCE_DELAY_MS = 30_000L

        fun startIfEnabled(context: Context) {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val settingsStore = GlobalContext.get().get<SettingsStore>()
                    val settings = settingsStore.settingsFlowRaw.first()
                    val proactiveSetting = settings.proactiveMessageSetting
                    // 激进模式可以独立工作，不需要主动消息开关
                    if (proactiveSetting.aggressiveModeEnabled) {
                        val intent = Intent(context, DeviceEventAiTriggerService::class.java)
                        try {
                            context.startForegroundService(intent)
                            Log.d(TAG, "startIfEnabled: started foreground service")
                        } catch (e: Exception) {
                            Log.e(TAG, "startIfEnabled: startForegroundService failed", e)
                        }
                    } else {
                        Log.d(TAG, "startIfEnabled: conditions not met, skip")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startIfEnabled: failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DeviceEventAiTriggerService::class.java))
                Log.d(TAG, "stop: service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "stop: failed", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenReceiver: BroadcastReceiver? = null
    private var appPollJob: Job? = null
    private var debounceJob: Job? = null

    // 事件收集缓冲区（防抖期间累积）
    private val eventBuffer = mutableListOf<DeviceEvent>()
    private val eventBufferMutex = Mutex()

    // 上次轮询时记录的前台包名，用于检测切换
    private var lastForegroundPackage: String? = null
    private var lastPollTimeMs: Long = 0L

    // 上次 AI 思考时间，用于限流
    private var lastAiTriggerTimeMs: Long = 0L

    private data class DeviceEvent(
        val type: String,        // screen_on, screen_off, app_switch, home
        val packageName: String, // 相关包名
        val appName: String,     // 应用名
        val timestamp: Long
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // 注册亮屏/锁屏广播
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val eventType = when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> "screen_on"
                        Intent.ACTION_SCREEN_OFF -> "screen_off"
                        else -> null
                    } ?: return
                    val now = System.currentTimeMillis()
                    serviceScope.launch {
                        addEvent(DeviceEvent(eventType, "", "", now))
                        scheduleDebounce(getDebounceDelayMs())
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiver = receiver
            Log.d(TAG, "Screen on/off receiver registered")

            // 启动应用切换轮询
            startAppPolling()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: startForeground failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            screenReceiver?.let {
                runCatching { unregisterReceiver(it) }
            }
            screenReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: error during unregister", e)
        }
        appPollJob?.cancel()
        appPollJob = null
        debounceJob?.cancel()
        debounceJob = null
        runCatching { serviceScope.cancel() }
    }

    /**
     * 每 3 秒轮询 UsageStatsManager，检测应用切换和回桌面
     */
    private fun startAppPolling() {
        appPollJob = serviceScope.launch {
            lastPollTimeMs = System.currentTimeMillis()
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    if (usm != null) {
                        val events = usm.queryEvents(lastPollTimeMs - 5000, now)
                        val event = UsageEvents.Event()
                        var detectedForeground: String? = null
                        var detectedAppName: String = ""

                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                detectedForeground = event.packageName
                                detectedAppName = getAppName(event.packageName)
                            }
                        }

                        // 检测到前台应用变化
                        if (detectedForeground != null && detectedForeground != lastForegroundPackage) {
                            val isHome = isLauncherPackage(detectedForeground)
                            val eventType = if (isHome) "home" else "app_switch"

                            // 跳过本应用自身的切换（避免AI思考时切屏导致循环）
                            if (detectedForeground != packageName) {
                                addEvent(DeviceEvent(
                                    type = eventType,
                                    packageName = detectedForeground,
                                    appName = detectedAppName,
                                    timestamp = now
                                ))
                                scheduleDebounce(getDebounceDelayMs())
                            }
                            lastForegroundPackage = detectedForeground
                        }
                    }
                    lastPollTimeMs = now
                } catch (e: Exception) {
                    Log.w(TAG, "App poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun getAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == pkg
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun addEvent(event: DeviceEvent) {
        eventBufferMutex.withLock {
            eventBuffer.add(event)
            // 限制缓冲区大小
            if (eventBuffer.size > 50) {
                eventBuffer.removeAt(0)
            }
        }
        Log.d(TAG, "Event added: ${event.type} ${event.appName}")
    }

    /**
     * 防抖：收到事件后等待指定时长，期间新事件会重置计时器
     * 防抖结束后检查限流，满足条件则触发 AI 思考
     */
    private fun scheduleDebounce(delayMs: Long) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(delayMs)
            triggerAiThinking()
        }
    }

    /**
     * 从设置读取用户配置的防抖秒数，转换成毫秒。
     * 读取失败或值非法（<=0）时兜底用 DEFAULT_DEBOUNCE_DELAY_MS，避免因为一次设置读取异常
     * 导致激进模式完全不触发。
     */
    private suspend fun getDebounceDelayMs(): Long {
        return try {
            val settingsStore = GlobalContext.get().get<SettingsStore>()
            val settings = settingsStore.settingsFlowRaw.first()
            val seconds = settings.proactiveMessageSetting.aggressiveDebounceSeconds
            if (seconds > 0) seconds * 1000L else DEFAULT_DEBOUNCE_DELAY_MS
        } catch (e: Exception) {
            Log.e(TAG, "getDebounceDelayMs failed, fallback to default ${DEFAULT_DEBOUNCE_DELAY_MS}ms", e)
            DEFAULT_DEBOUNCE_DELAY_MS
        }
    }

    private suspend fun triggerAiThinking() {
        try {
            val settingsStore = GlobalContext.get().get<SettingsStore>()
            val settings = settingsStore.settingsFlowRaw.first()
            val proactiveSetting = settings.proactiveMessageSetting

            // 检查是否仍启用（激进模式可独立工作，不依赖主动消息开关）
            if (!proactiveSetting.aggressiveModeEnabled) {
                Log.d(TAG, "Aggressive mode disabled, skip")
                return
            }

            // 限流检查
            val minIntervalMs = proactiveSetting.aggressiveMinIntervalSeconds * 1000L
            val now = System.currentTimeMillis()
            if (now - lastAiTriggerTimeMs < minIntervalMs) {
                Log.d(TAG, "Rate limited, skip (${(now - lastAiTriggerTimeMs) / 1000}s < ${proactiveSetting.aggressiveMinIntervalSeconds}s)")
                return
            }

            // 取出缓冲区事件
            val events = eventBufferMutex.withLock {
                val copy = eventBuffer.toList()
                eventBuffer.clear()
                copy
            }

            if (events.isEmpty()) {
                Log.d(TAG, "No events to process")
                return
            }

            lastAiTriggerTimeMs = now
            Log.d(TAG, "Triggering AI thinking with ${events.size} events")

            // 构建事件上下文
            val eventContext = buildEventContext(events)

            // 复用 ProactiveMessageTriggerService 的 AI 思考逻辑
            val triggerIntent = Intent(this@DeviceEventAiTriggerService, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, eventContext)
            }
            startForegroundService(triggerIntent)

        } catch (e: Exception) {
            Log.e(TAG, "triggerAiThinking failed", e)
        }
    }

    private fun buildEventContext(events: List<DeviceEvent>): String {
        val sb = StringBuilder()
        sb.appendLine("[设备事件触发]")
        sb.appendLine("以下是过去30秒内用户的手机操作动向：")
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        events.forEach { event ->
            val time = dateFormat.format(Date(event.timestamp))
            val desc = when (event.type) {
                "screen_on" -> "亮屏"
                "screen_off" -> "锁屏"
                "app_switch" -> "切换到应用 ${event.appName}"
                "home" -> "回到桌面"
                else -> event.type
            }
            sb.appendLine("  - [$time] $desc")
        }
        sb.appendLine()
        sb.appendLine("请根据以上用户动向，以自然、关心、有趣的方式决定是否主动给用户发一条消息。")
        sb.appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
        sb.appendLine("重要规则：")
        sb.appendLine("- 不要提及你是在因为设备事件发消息，要像自然想起对方一样")
        sb.appendLine("- 绝对不要提及任何数据来源、工具使用、传感器数据、应用使用统计等技术细节")
        sb.appendLine("- 不要说\"根据xxx\"、\"我注意到xxx数据\"之类暴露信息来源的话")
        sb.appendLine("- 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说")
        sb.appendLine("- 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容")
        sb.appendLine("- 不要调用任何工具或函数，只输出纯文本回复")
        sb.appendLine("- 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话")
        return sb.toString()
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
            .setContentTitle("激进模式运行中")
            .setContentText("AI 正在感知你的手机动向")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}