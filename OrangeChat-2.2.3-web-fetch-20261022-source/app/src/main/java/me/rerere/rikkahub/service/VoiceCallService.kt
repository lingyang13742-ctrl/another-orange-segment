/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

/**
 * 语音通话后台服务
 *
 * 把原来 VoiceCallVM 里的业务逻辑迁移成"独立运行、跟随 Service 生命周期"的形式.
 * 用户在 VoiceCallPage 手动开始通话后, 切到后台/退出页面, 通话依然继续跑,
 * 有持续通知栏, 点通知能回到通话页面. 只有用户主动点"挂断"才真正结束.
 *
 * 同一时刻只允许存在一路通话 (由 _activeConversationId 这个 companion object 级别的
 * StateFlow 做单例保护).
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val httpClient: OkHttpClient by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "VoiceCallService coroutine exception", e)
        }
    )

    private lateinit var conversationId: Uuid
    private lateinit var asr: CustomAsrState
    private lateinit var tts: CustomTtsState

    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()

    val conversation: StateFlow<Conversation>
        get() = chatService.getConversationFlow(conversationId)

    // 任务协程
    private var vadJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var asrMonitorJob: Job? = null
    private var interruptDetectJob: Job? = null
    private var lastSpokenText: String = ""

    // 跟踪 AI 消息的增量, 用于流式 TTS
    private var lastAssistantText: String = ""
    private var hasSentCurrentMessage = false

    // 流式 TTS: 记录已发送给 TTS 的文本长度
    private var ttsSentLength: Int = 0

    // 静音状态 (独立于 _uiState.isMuted, 检测循环里直接读这个字段更快)
    private var isMuted: Boolean = false

    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

        fun isRunning(): Boolean = _activeConversationId.value != null

        /**
         * 启动服务: 调用方 (VoiceCallPage) 负责在自己判断"没有冲突"之后才调这个方法.
         */
        fun start(context: Context, conversationId: String) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动 VoiceCallService 失败, conversationId=$conversationId", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceCallService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止 VoiceCallService 失败", e)
            }
        }

        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val ACTION_HANG_UP = "me.rerere.rikkahub.VOICE_CALL_HANG_UP"
        const val NOTIFICATION_ID = 40001
    }

    // Binder, 供 VoiceCallPage bindService 用
    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户点了通知栏上的"挂断"按钮
        if (intent?.action == ACTION_HANG_UP) {
            endCall()
            stopSelf()
            return START_NOT_STICKY
        }

        val convIdStr = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (convIdStr == null) {
            Log.e(TAG, "onStartCommand 缺少 conversationId 参数, 无法启动通话")
            stopSelf()
            return START_NOT_STICKY
        }

        // 已经在跑同一个对话的通话: 不要重复 startCall, 只刷新前台通知
        if (_activeConversationId.value == convIdStr) {
            return START_NOT_STICKY
        }

        // 兜底: 已经在跑别的对话的通话, 防御性丢弃
        if (_activeConversationId.value != null && _activeConversationId.value != convIdStr) {
            Log.w(
                TAG,
                "已有通话 ${_activeConversationId.value} 在进行, 忽略新的 start 请求 $convIdStr"
            )
            return START_NOT_STICKY
        }

        try {
            conversationId = Uuid.parse(convIdStr)
        } catch (e: Exception) {
            Log.e(TAG, "conversationId 解析失败: $convIdStr", e)
            stopSelf()
            return START_NOT_STICKY
        }

        _activeConversationId.value = convIdStr

        // 关键修复: 必须先同步调用 startForeground, 用一个初始状态的通知占位.
        // Android 要求 startForegroundService() 调用后 5 秒内必须调用 startForeground(),
        // 否则触发 ForegroundServiceDidNotStartInTimeException 崩溃.
        // 不能等 ASR/TTS 异步初始化完成后才调用, 真正的初始化放到下面的协程里做.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(_uiState.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败, conversationId=$conversationId", e)
            _activeConversationId.value = null
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                // 关键修复: 两个工厂函数现在是 suspend 函数, 会真正挂起等待
                // provider 设置完成后才返回实例, 消除了之前 controller 为 null 的竞态.
                asr = createCustomAsrState(applicationContext, httpClient, settingsStore)
                tts = createCustomTtsState(applicationContext, settingsStore)

                startCall()

                // 订阅 uiState 变化, 实时刷新通知内容
                launch {
                    uiState.collect { state ->
                        try {
                            val manager =
                                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(state))
                        } catch (e: Exception) {
                            Log.e(TAG, "刷新通话通知失败", e)
                        }
                    }
                }

                // Service 自己订阅 asr.state, 同步振幅数据 + 捕获底层 ASR 错误
                launch {
                    asr.state.collect { asrState ->
                        updateAmplitudes(asrState.amplitudes)
                        if (asrState.status == me.rerere.asr.ASRStatus.Error) {
                            val msg = asrState.errorMessage ?: "语音识别发生未知错误"
                            Log.e(TAG, "ASR 底层报错, conversationId=$conversationId, msg=$msg")
                            _uiState.update {
                                it.copy(
                                    status = VoiceCallStatus.Error,
                                    errorMessage = "语音识别错误: $msg"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化语音通话失败, conversationId=$conversationId", e)
                _uiState.update {
                    it.copy(
                        status = VoiceCallStatus.Error,
                        errorMessage = "初始化失败: ${e.message}"
                    )
                }
            }
        }

        // 不用 START_STICKY: 通话被系统杀死不应该自动重启接着录音
        return START_NOT_STICKY
    }

    /**
     * 开始语音通话
     *
     * ASR 在整个通话期间持续录音 (不再像原来那样只在 Listening 状态开启).
     * 这里只调用一次 asr.start(), 作为整场通话唯一的录音启动点
     * (除非用户中途静音又取消).
     */
    fun startCall() {
        if (_uiState.value.status != VoiceCallStatus.Idle) return
        lastAssistantText = ""
        lastSpokenText = ""
        hasSentCurrentMessage = false
        ttsSentLength = 0
        isMuted = false

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                errorMessage = null,
                isMuted = false
            )
        }

        try {
            asr.start { transcript ->
                _uiState.update { it.copy(userTranscript = transcript) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动 ASR 失败, conversationId=$conversationId", e)
            _uiState.update {
                it.copy(
                    status = VoiceCallStatus.Error,
                    errorMessage = "麦克风启动失败: ${e.message}"
                )
            }
            return
        }

        startVadDetection()
        startAsrMonitor()
        startConversationMonitor()
    }

    /**
     * 从别的状态切回 Listening 时复位状态 + VAD 计时器.
     * 不再调用 asr.stop()/asr.start() (ASR 现在贯穿全程).
     */
    private fun startListening() {
        tts.stop()
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = false

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                errorMessage = null
            )
        }

        // 停止"打断检测"协程 (Speaking 状态才需要它)
        interruptDetectJob?.cancel()

        // 重启 ASR: 非流式 ASR (SiliconFlow) 是“录一段→停”的一次性模式,
        // AI 说完话回到 Listening 时它已停, 不重启则音波球不动、说话发不出去.
        // 流式 ASR 的 start() 有 isRecording 守卫, 重复调用无副作用.
        if (!isMuted) {
            runCatching {
                asr.start { transcript ->
                    _uiState.update { it.copy(userTranscript = transcript) }
                }
            }.onFailure { Log.e(TAG, it.toString(), it) }
        }

        startVadDetection()
    }

    /**
     * VAD: 检测用户停顿后自动发送 (仅 Listening 状态生效).
     *
     * 优化: 更快的响应时间, 更灵敏的检测.
     * 阈值参数 (800ms / 2 字符 / 2 秒音量超时) 保持不变, 不要动.
     */
    private fun startVadDetection() {
        vadJob?.cancel()
        vadJob = serviceScope.launch {
            var lastTranscript = ""
            var silenceStartTime: Long = 0L
            var lastAmplitudeTime: Long = System.currentTimeMillis()
            val silenceThresholdMs = 800L
            val minTranscriptLength = 2
            val amplitudeTimeoutMs = 2000L

            while (true) {
                delay(100)
                if (_uiState.value.status != VoiceCallStatus.Listening) break
                if (isMuted) continue // 静音期间不检测, 也不发送
                if (!_uiState.value.autoSendEnabled) continue

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 检测音量活动 - 如果有声音就重置计时
                if (recentAmplitude > 0.05f) {
                    lastAmplitudeTime = System.currentTimeMillis()
                }

                if (currentTranscript != lastTranscript) {
                    // 转写还在变化, 重置静音计时
                    lastTranscript = currentTranscript
                    silenceStartTime = 0L
                } else if (currentTranscript.length >= minTranscriptLength) {
                    // 转写稳定且有内容, 开始/继续计时
                    if (silenceStartTime == 0L) {
                        silenceStartTime = System.currentTimeMillis()
                    }
                    val silentFor = System.currentTimeMillis() - silenceStartTime
                    val amplitudeSilentFor = System.currentTimeMillis() - lastAmplitudeTime

                    // 触发条件: 转写稳定且静音足够, 或音量持续低迷
                    if (silentFor >= silenceThresholdMs || amplitudeSilentFor >= amplitudeTimeoutMs) {
                        Log.d(
                            TAG,
                            "VAD triggered auto-send: $currentTranscript (silentFor=$silentFor, ampSilent=$amplitudeSilentFor)"
                        )
                        sendCurrentMessage()
                        break
                    }
                }
            }
        }
    }

    /**
     * 发送当前转写的消息.
     * 不再调用 asr.stop() (ASR 要持续跑到整场通话结束).
     */
    private fun sendCurrentMessage() {
        val transcript = _uiState.value.userTranscript.trim()
        vadJob?.cancel()

        if (transcript.isBlank()) {
            // 没有有效内容, 回到监听
            startListening()
            return
        }

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                assistantText = ""
            )
        }
        ttsSentLength = 0
        lastAssistantText = ""

        try {
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Text(transcript))
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "发送消息失败, conversationId=$conversationId, transcript=$transcript",
                e
            )
            _uiState.update {
                it.copy(
                    status = VoiceCallStatus.Error,
                    errorMessage = "发送失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 监听对话流变化, 实现:
     * 1. 流式 TTS (检测到新句子即朗读)
     * 2. AI 开始输出时立即进入 Speaking 状态, 让用户可以打断
     * 3. AI 回复完成后回到 Listening
     */
    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { conv ->
                if (_uiState.value.status != VoiceCallStatus.Processing &&
                    _uiState.value.status != VoiceCallStatus.Speaking
                ) return@collect

                val lastMessage = conv.currentMessages.lastOrNull()
                if (lastMessage?.role != MessageRole.ASSISTANT) return@collect

                val currentText = lastMessage.toText()

                // 更新 UI 显示的 AI 回复
                _uiState.update { it.copy(assistantText = currentText) }

                // 流式 TTS: 只朗读新增的部分
                if (currentText.length > ttsSentLength) {
                    val newText = currentText.substring(ttsSentLength)
                    // 按句子分割, 朗读完整句子
                    val sentences = extractCompleteSentences(newText)
                    for (sentence in sentences) {
                        if (sentence.isNotBlank()) {
                            tts.enqueueText(sentence)
                            Log.d(TAG, "Streaming TTS: $sentence")
                        }
                    }
                    ttsSentLength = currentText.length - getPendingRemainder(newText).length
                }

                // 一旦 AI 有内容输出, 立即切换到 Speaking 状态
                // 这样用户随时可以打断, UI 反馈更即时
                if (_uiState.value.status == VoiceCallStatus.Processing && currentText.isNotBlank()) {
                    _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
                    startInterruptDetection()
                }

                lastAssistantText = currentText
            }
        }

        // 监听生成完成 -> 等待 TTS 播放完成 -> 回到 Listening
        speakingMonitorJob?.cancel()
        speakingMonitorJob = serviceScope.launch {
            chatService.generationDoneFlow.collect { convId ->
                if (convId != conversationId) return@collect
                onGenerationDone()
            }
        }
    }

    private suspend fun onGenerationDone() {
        // 朗读最后剩余的文本
        val finalText = _uiState.value.assistantText
        if (finalText.length > ttsSentLength) {
            val remaining = finalText.substring(ttsSentLength)
            if (remaining.isNotBlank()) {
                tts.enqueueText(remaining)
                ttsSentLength = finalText.length
            }
        }

        _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
        startInterruptDetection()
        waitForTtsToFinish()

        // 回到监听
        if (_uiState.value.status == VoiceCallStatus.Speaking) {
            startListening()
        }
    }

    private suspend fun waitForTtsToFinish() {
        // 等待 TTS 开始播放
        var waitStart = System.currentTimeMillis()
        while (!tts.isSpeaking.value && System.currentTimeMillis() - waitStart < 5000) {
            delay(100)
        }
        // 等待 TTS 播放完成.
        // 不能只靠 isSpeaking: 它在 worker 的 finally 里才会变 false,
        // 一旦 worker 挂在网络请用/音频播放上 (isSpeaking 永远 true),
        // 这里就死循环, 通话永远卡在 "正在传达".
        // 改用 "活动超时": 跟踪 TTS 最后一次处于活动状态的时间,
        // 连续 5 秒没有新的播放活动(不是 Playing/Buffering 且 isSpeaking 为 false)
        // 就认为说完了. 另勠 5 分钟硬截止兜底.
        val idleTimeoutMs = 5_000L
        val hardDeadlineMs = 300_000L
        val startTime = System.currentTimeMillis()
        var lastActiveTime = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val status = tts.playbackState.value.status
            val active = tts.isSpeaking.value ||
                status == me.rerere.tts.model.PlaybackStatus.Playing ||
                status == me.rerere.tts.model.PlaybackStatus.Buffering
            if (active) {
                lastActiveTime = now
            }
            // 连续 idleTimeoutMs 没活动 → 说完了
            if (!active && now - lastActiveTime >= idleTimeoutMs) {
                break
            }
            // 硬截止兜底 (TTS 真卡死)
            if (now - startTime > hardDeadlineMs) {
                Log.w(TAG, "TTS 播放超过 5 分钟未结束, 强制停止以防卡死")
                tts.stop()
                break
            }
            delay(300)
        }
        // 额外等待状态更新
        delay(300)
    }

    /**
     * 从增量文本中提取完整的句子 (以句号/问号/感叹号/换行结尾)
     */
    private fun extractCompleteSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char == '。' || char == '？' || char == '！' || char == '.' ||
                char == '?' || char == '!' || char == '\n'
            ) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) {
                    result.add(sentence)
                }
                current.clear()
            }
        }
        // 保存未完成的部分 (不朗读, 等下次)
        return result
    }

    /**
     * 获取增量文本中未形成完整句子的剩余部分
     */
    private fun getPendingRemainder(text: String): String {
        val lastSentenceEnd =
            text.lastIndexOfAny(charArrayOf('。', '？', '！', '.', '?', '!', '\n'))
        return if (lastSentenceEnd >= 0 && lastSentenceEnd < text.length - 1) {
            text.substring(lastSentenceEnd + 1)
        } else if (lastSentenceEnd < 0) {
            text
        } else {
            ""
        }
    }

    /**
     * Speaking 状态下的打断检测.
     *
     * 与 startVadDetection (判断"该发送了") 职责不同:
     * 这里只关心"用户是否开始说话了", 一旦检测到就立即打断, 不等静音判断.
     *
     * 用比 Listening 状态更高的音量阈值 (0.15f vs 0.05f), 降低被 AI 自己声音误触发的概率.
     * 残留风险: AEC 不是 100% 完美, 外放音量很大或低端机型硬件 AEC 差时仍可能误触发,
     * 后续可加音量差阈值调优, 但不阻塞现在的实现.
     */
    private fun startInterruptDetection() {
        interruptDetectJob?.cancel()
        interruptDetectJob = serviceScope.launch {
            var baselineTranscript = _uiState.value.userTranscript
            while (true) {
                delay(150)
                if (_uiState.value.status != VoiceCallStatus.Speaking) break
                if (isMuted) continue // 静音期间不判断打断

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 转写文本相较于进入 Speaking 时有新增内容, 或者音量突然超过阈值,
                // 都视为"用户开始说话了"
                val hasNewTranscript = currentTranscript.length > baselineTranscript.length + 1
                val hasLoudVoice = recentAmplitude > 0.15f

                if (hasNewTranscript || hasLoudVoice) {
                    Log.d(
                        TAG,
                        "检测到用户打断: transcript=$currentTranscript, amplitude=$recentAmplitude"
                    )
                    interruptSpeaking()
                    break
                }
            }
        }
    }

    /**
     * 用户打断 AI 说话 (Barge-in).
     * 不再调用 asr.start() (ASR 一直是开着的), 只做状态切换 + cancel 协程.
     */
    fun interruptSpeaking() {
        if (_uiState.value.status != VoiceCallStatus.Speaking) return
        speakingMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        startListening()
    }

    /**
     * 监听 ASR 状态 (用于非流式 ASR 如 SiliconFlow).
     * 当 ASR 从 Recording -> Idle 且转写不为空时, 立即发送.
     *
     * 加了 !isMuted 判断, 避免静音操作本身触发的 Recording→非Recording 跳变被误判成"该发送了".
     */
    private fun startAsrMonitor() {
        asrMonitorJob?.cancel()
        asrMonitorJob = serviceScope.launch {
            var wasRecording = false
            asr.state.collect { asrState ->
                val isRecording = asrState.isRecording

                // 检测到从 Recording 变为非 Recording
                if (wasRecording && !isRecording && !isMuted && _uiState.value.status == VoiceCallStatus.Listening) {
                    val transcript = asrState.transcript.trim()
                    if (transcript.isNotEmpty() && _uiState.value.autoSendEnabled) {
                        Log.d(TAG, "ASR monitor: Auto-send after ASR completed: $transcript")
                        sendCurrentMessage()
                    } else {
                        // 转写为空(没说话/未识别到): 非流式 ASR (SiliconFlow)
                        // 此时已停在 Idle, 不重启的话音波球不动、下一句说话发不出去.
                        // 流式 ASR 的 start() 有 isRecording 守卫, 重复调用无副作用.
                        if (!isMuted && _uiState.value.status == VoiceCallStatus.Listening) {
                            runCatching {
                                asr.start { t -> _uiState.update { it.copy(userTranscript = t) } }
                            }.onFailure { Log.e(TAG, it.toString(), it) }
                        }
                    }
                }

                wasRecording = isRecording
            }
        }
    }

    /**
     * 切换静音. 在任何状态下都要能生效/取消, 不再判断 status.
     * 静音 = 模型听不到; 取消静音 = 不管 Listening 还是 Speaking 都重新开始监听.
     */
    fun toggleMute() {
        isMuted = !isMuted
        _uiState.update { it.copy(isMuted = isMuted) }

        try {
            if (isMuted) {
                asr.stop()
            } else {
                // 不管当前是 Listening 还是 Speaking, 取消静音都要重新开始监听
                asr.start { transcript ->
                    _uiState.update { it.copy(userTranscript = transcript) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换静音状态失败, isMuted=$isMuted", e)
            _uiState.update { it.copy(errorMessage = "麦克风切换失败: ${e.message}") }
        }
    }

    /**
     * 切换自动发送模式. UI 上不再挂载按钮, 但保留方法 (autoSendEnabled 字段仍在用).
     */
    fun toggleAutoSend() {
        _uiState.update { it.copy(autoSendEnabled = !it.autoSendEnabled) }
    }

    /**
     * 挂断 / 结束通话.
     * 额外复位 _activeConversationId 和移除前台通知.
     */
    fun endCall() {
        vadJob?.cancel()
        speakingMonitorJob?.cancel()
        conversationMonitorJob?.cancel()
        asrMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        asr.stop()
        tts.stop()
        _uiState.update {
            it.copy(status = VoiceCallStatus.Idle)
        }
        _activeConversationId.value = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground 失败", e)
        }
    }

    /**
     * 更新振幅数据 (供 UI 动画使用)
     */
    fun updateAmplitudes(amplitudes: List<Float>) {
        _uiState.update { it.copy(amplitudes = amplitudes) }
    }

    /**
     * 构建通话通知. 通话中这种更醒目、带操作按钮的通知.
     */
    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val contentText = when (state.status) {
            VoiceCallStatus.Listening -> "正在聆听..."
            VoiceCallStatus.Processing -> "正在思考..."
            VoiceCallStatus.Speaking -> "正在说话..."
            VoiceCallStatus.Error -> state.errorMessage ?: "通话出错"
            VoiceCallStatus.Idle -> "通话中"
        }

        // 点击通知本体: 回到 RouteActivity 并导航到 VoiceCallPage
        val contentIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("openVoiceCallConversationId", conversationId.toString())
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知上的"挂断"按钮: 直接发一个带 ACTION_HANG_UP 的 Intent 给自己这个 Service
        val hangUpIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("语音通话")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "挂断", hangUpIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 兜底, 防止外部通过 stopService 直接杀掉时状态没清理干净
            endCall()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 清理失败", e)
        }
        serviceScope.cancel()
    }
}
