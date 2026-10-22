/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.entity.MemoryBankEntity
import me.rerere.rikkahub.data.model.ExternalMemory
import org.koin.core.context.GlobalContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日记总结服务
 * 每天指定小时自动生成日记总结
 * 从 Supabase 外置记忆库拉取聊天记录进行总结
 */
class DiarySummaryService {

    companion object {
        const val TAG = "DiarySummaryService"
        const val ACTION_DIARY_SUMMARY = "me.rerere.rikkahub.DIARY_SUMMARY"
        private const val REQUEST_CODE = 10004

        private const val PREFS_NAME = "diary_summary_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"

        /** 默认触发时间：每天凌晨 3:00 */
        private const val DEFAULT_HOUR = 3

        /**
         * 计算距离下次目标时间的毫秒数
         */
        private fun calculateDelayToNextTarget(hour: Int = DEFAULT_HOUR): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // 如果今天的目标时间已过，设置为明天
            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            return target.timeInMillis - now.timeInMillis
        }

        /**
         * 调度下一次日记总结闹钟
         */
        fun scheduleNext(context: Context, hour: Int = DEFAULT_HOUR) {
            val delayMs = calculateDelayToNextTarget(hour)
            val triggerTime = System.currentTimeMillis() + delayMs

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_HOUR, hour)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DiarySummaryReceiver::class.java).apply {
                action = ACTION_DIARY_SUMMARY
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            Log.d(TAG, "Scheduled diary summary at ${sdf.format(Date(triggerTime))} (delay: ${delayMs / 60000} min)")
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .putBoolean(KEY_ENABLED, false)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DiarySummaryReceiver::class.java).apply {
                action = ACTION_DIARY_SUMMARY
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled diary summary alarm")
            }
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        /**
         * 重新调度日记总结（App 启动时调用）
         * 已废弃：日记总结完全由 Supabase Edge Function 负责，App 不再本地调度。
         */
        @Deprecated("Diary summary is now handled by Supabase Edge Function")
        fun rescheduleIfEnabled(context: Context) {
            cancel(context)
            Log.i(TAG, "Local diary summary disabled; handled by Supabase Edge Function")
        }

        /**
         * 检查最近几天是否有未写的日记，如果有则补写
         * 已废弃：日记总结完全由 Supabase Edge Function 负责，App 不再本地生成。
         */
        @Deprecated("Diary summary is now handled by Supabase Edge Function")
        fun checkAndGenerateMissingDiaries(context: Context) {
            Log.d(TAG, "Local diary summary generation disabled; handled by Supabase Edge Function")
        }

        /**
         * 从 Supabase 外置记忆库拉取指定日期的消息
         */
        private suspend fun getDayMessagesFromSupabase(
            dateStr: String,
            configs: List<ExternalMemory>
        ): List<MemoryBankEntity> {
            val allMessages = mutableListOf<ExternalMemoryMessage>()

            configs.forEach { config ->
                runCatching {
                    val service = ExternalMemoryService(config)
                    val messages = service.queryMessagesByDate(dateStr).getOrDefault(emptyList())
                    allMessages.addAll(messages)
                    Log.d(TAG, "getDayMessagesFromSupabase: ${config.name} returned ${messages.size} messages for $dateStr")
                }.onFailure {
                    Log.w(TAG, "Failed to fetch messages from ${config.name} for $dateStr", it)
                }
            }

            // 去重（按 content + role + assistantId）
            val uniqueMessages = allMessages.distinctBy { "${it.assistantId}:${it.role}:${it.content.take(50)}" }

            // 转换为 MemoryBankEntity 格式
            return uniqueMessages.map { msg ->
                MemoryBankEntity(
                    content = msg.content,
                    type = "message",
                    role = msg.role,
                    assistantId = msg.assistantId.ifBlank { null },
                    conversationId = msg.conversationId.ifBlank { null },
                    dateGroup = dateStr
                )
            }.also {
                Log.d(TAG, "getDayMessagesFromSupabase: total ${it.size} unique messages for $dateStr")
            }
        }

        /**
         * 为指定日期生成日记总结（按助手隔离）
         */
        private suspend fun generateDiaryForDate(
            dateStr: String,
            messages: List<MemoryBankEntity>,
            settingsStore: SettingsStore,
            providerManager: ProviderManager,
            memoryBankService: MemoryBankService,
            diaryConfigs: List<ExternalMemory> = emptyList()
        ) {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "[STEP 1] generateDiaryForDate START for $dateStr")
                    
                    val settings = settingsStore.settingsFlowRaw.first()
                    Log.i(TAG, "[STEP 2] settings loaded")
                    
                    val model = settings.getCurrentChatModel()
                        ?: settings.findModelById(settings.chatModelId)
                        ?: run {
                            Log.e(TAG, "[STEP FAIL] No chat model available for diary generation, chatModelId=${settings.chatModelId}")
                            return@withContext
                        }
                    Log.i(TAG, "[STEP 3] model found: ${model.id}")

                    val provider = model.findProvider(settings.providers)
                        ?: run {
                            Log.e(TAG, "[STEP FAIL] No provider found for model ${model.id}")
                            return@withContext
                        }
                    Log.i(TAG, "[STEP 4] provider found: ${provider.javaClass.simpleName}")

                    val providerImpl = providerManager.getProviderByType(provider)
                    Log.i(TAG, "[STEP 5] providerImpl obtained")

                    // 按助手 ID 分组消息
                    val groupedMessages = messages.groupBy { it.assistantId ?: "" }
                        .filter { it.key.isNotBlank() }
                    
                    if (groupedMessages.isEmpty()) {
                        Log.w(TAG, "No messages with valid assistantId for $dateStr")
                        return@withContext
                    }

                    groupedMessages.forEach { (assistantId, assistantMessages) ->
                        // 获取当前助手及其关联的日记配置
                        val assistant = settings.assistants.find { it.id.toString() == assistantId }
                        val assistantDiaryConfigs = diaryConfigs.filter {
                            it.id in (assistant?.externalMemoryIds ?: emptySet())
                        }

                        // 检查该日期是否已有日记（去重）
                        val hasExistingDiary = assistantDiaryConfigs.any { config ->
                            runCatching {
                                val service = ExternalMemoryService(config)
                                service.querySummariesByDate(assistantId, dateStr).getOrDefault(emptyList()).isNotEmpty()
                            }.getOrDefault(false)
                        }
                        if (hasExistingDiary) {
                            Log.d(TAG, "Diary already exists for $dateStr assistant=$assistantId, skip generation")
                            return@forEach
                        }

                        Log.i(TAG, "[STEP 5a] Processing diary for assistant=$assistantId, messages=${assistantMessages.size}")

                        // 构建对话内容
                        val conversationText = assistantMessages.joinToString("\n") { msg ->
                            val roleLabel = when (msg.role) {
                                "user" -> "用户"
                                "assistant" -> "AI"
                                else -> msg.role ?: "未知"
                            }
                            "[$roleLabel] ${msg.content}"
                        }

                        val systemPrompt = assistant?.systemPrompt?.takeIf { it.isNotBlank() }

                        val summaryPrompt = buildString {
                            appendLine("请根据以下 $dateStr 的聊天记录，生成一篇日记总结。")
                            appendLine()
                            appendLine("要求：")
                            appendLine("1. 你是 AI 助手，用第一人称\"我\"的视角来写这篇日记")
                            appendLine("2. 记录今天你与用户的互动：用户的提问、情绪、需求，以及你的回应和思考")
                            appendLine("3. 关注用户的习惯、喜好和变化，像真正的 AI 伴侣在记录与主人的日常")
                            appendLine("4. 融入你的人设和语气，让日记读起来像你亲笔写的")
                            appendLine("5. 篇幅在200-500字之间")
                            appendLine()
                            if (!systemPrompt.isNullOrBlank()) {
                                appendLine("你的人设/系统提示词（供参考）：")
                                appendLine(systemPrompt)
                                appendLine()
                            }
                            appendLine("聊天记录：")
                            appendLine(conversationText)
                        }

                        val result = providerImpl.generateText(
                            providerSetting = provider,
                            messages = listOf(
                                UIMessage(
                                    role = MessageRole.USER,
                                    parts = listOf(UIMessagePart.Text(summaryPrompt))
                                )
                            ),
                            params = TextGenerationParams(
                                model = model,
                                reasoningLevel = ReasoningLevel.OFF,
                                maxTokens = 1024,
                            ),
                        )

                        val summaryText = result.choices.firstOrNull()?.message?.toText()?.trim() ?: ""

                        if (summaryText.isBlank()) {
                            Log.e(TAG, "Generated diary is empty for $dateStr assistant=$assistantId")
                            return@forEach
                        }

                        // 保存到本地 memory_bank（类型为 daily_summary）
                        val savedMemory = memoryBankService.saveAutoSummary(
                            content = summaryText,
                            assistantId = assistantId,
                            conversationId = assistantMessages.firstOrNull()?.conversationId
                        )

                        if (savedMemory != null) {
                            Log.i(TAG, "Diary saved to local memory bank for $dateStr assistant=$assistantId")
                        } else {
                            Log.e(TAG, "saveAutoSummary returned null for $dateStr assistant=$assistantId")
                        }

                        // 保存到外置记忆库（日记摘要）
                        try {
                            assistantDiaryConfigs.forEach { config ->
                                runCatching {
                                    val service = ExternalMemoryService(config)
                                    var embedding: List<Float>? = null

                                    // 如果配置了向量模型，生成 embedding
                                    if (config.embeddingModelId != null) {
                                        val embeddingModel = settings.findModelById(config.embeddingModelId)
                                        if (embeddingModel != null) {
                                            val embeddingProvider = embeddingModel.findProvider(settings.providers)
                                            if (embeddingProvider != null) {
                                                val providerImpl = providerManager.getProviderByType(embeddingProvider)
                                                val embedResult = providerImpl.generateEmbedding(
                                                    providerSetting = embeddingProvider,
                                                    params = me.rerere.ai.provider.EmbeddingGenerationParams(
                                                        model = embeddingModel,
                                                        input = listOf(summaryText),
                                                    )
                                                )
                                                embedding = embedResult.embeddings.firstOrNull()
                                                Log.d(TAG, "Generated embedding for diary summary: ${embedding?.size} dims")
                                            }
                                        }
                                    }

                                    val saveResult = service.saveDiarySummary(
                                        assistantId = assistantId,
                                        content = summaryText,
                                        embedding = embedding,
                                        targetDate = dateStr,
                                    )
                                    if (saveResult.isSuccess) {
                                        Log.i(TAG, "Diary saved to external memory ${config.name} for $dateStr assistant=$assistantId")
                                    } else {
                                        Log.w(TAG, "Failed to save diary to external memory ${config.name} for $dateStr assistant=$assistantId", saveResult.exceptionOrNull())
                                    }
                                }.onFailure {
                                    Log.w(TAG, "Failed to save diary to external memory ${config.name} for $dateStr assistant=$assistantId", it)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to save diary to external memory for $dateStr assistant=$assistantId", e)
                        }
                    }

                    Log.i(TAG, "[STEP DONE] Diary generation completed for $dateStr, assistants=${groupedMessages.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "[STEP EXCEPTION] Failed to generate diary for $dateStr", e)
                }
            }
        }

        /**
         * 立即触发一次日记总结（调试/手动触发用）
         */
        fun triggerNow(context: Context) {
            val serviceIntent = Intent(context, DiarySummaryTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }

    }
}

/**
 * 日记总结闹钟接收器
 */
class DiarySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(DiarySummaryService.TAG, "DiarySummaryReceiver triggered, action=${intent.action}")
        when (intent.action) {
            DiarySummaryService.ACTION_DIARY_SUMMARY -> {
                val serviceIntent = Intent(context, DiarySummaryTriggerService::class.java)
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(DiarySummaryService.TAG, "Boot completed, rescheduling diary summary")
                DiarySummaryService.rescheduleIfEnabled(context)
            }
        }
    }
}

/**
 * 日记总结执行 Foreground Service
 */
class DiarySummaryTriggerService : Service() {

    companion object {
        private const val TAG = "DiarySummaryTrigger"
        private const val NOTIFICATION_ID = 20004
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DiarySummaryTriggerService started")

        val notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在生成日记总结...")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiarySummaryService.checkAndGenerateMissingDiaries(this@DiarySummaryTriggerService)
            } catch (e: Exception) {
                Log.e(TAG, "Diary summary generation failed", e)
            } finally {
                // 调度下一次
                DiarySummaryService.rescheduleIfEnabled(this@DiarySummaryTriggerService)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
