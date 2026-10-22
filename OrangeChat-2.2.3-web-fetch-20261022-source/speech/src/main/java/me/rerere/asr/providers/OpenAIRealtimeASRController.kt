/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.asr.stripTrailingEmoji
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "OpenAIRealtimeASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
// 意外断开时的自动重连参数 (修复 ASR 运行一段时间后冻结的 bug)
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val RECONNECT_BASE_DELAY_MS = 1000L

class OpenAIRealtimeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.OpenAIRealtime
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private val partialTranscripts = ConcurrentHashMap<String, String>()

    // 用户主动 stop() 时置 true, 用来区分"用户挂断"和"网络断开需要重连"
    @Volatile
    private var isStopping = false
    // 连续重连失败次数 (成功连上后清零), 超过上限才彻底报错
    @Volatile
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        isStopping = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        completedTranscripts.clear()
        partialTranscripts.clear()
        connect()
    }

    /**
     * 建立 WebSocket 连接 (内部用, 可被 start() 和重连逻辑复用).
     */
    private fun connect() {
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketEndpoint())
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(provider.sessionUpdateEvent().toString())
                // 连上了, 重置重连计数
                reconnectAttempts = 0
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                startRecorder(provider, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Realtime ASR websocket failed", t)
                releaseRecorder()
                handleDisconnect(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Realtime ASR websocket closed: code=$code, reason=$reason")
                releaseRecorder()
                handleDisconnect("ASR 连接已断开")
            }
        })
    }

    /**
     * 处理意外断开: 用户没主动 stop 时自动重连, 否则正常进入 Idle.
     * 这是修复"音波球不动/说话发不出去"卡死 bug 的核心 ——
     * 原来 onClosed 会静默变成 Idle 且清空错误, 导致 VoiceCallService 毫无察觉、永不恢复.
     */
    private fun handleDisconnect(reason: String) {
        if (isStopping) {
            // 用户主动挂断, 正常结束
            _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            return
        }
        // 意外断开: 尝试重连, 超过上限才彻底报错
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "重连次数已达上限 ($MAX_RECONNECT_ATTEMPTS), 放弃重连")
            setError("$reason (重连失败)")
            return
        }
        reconnectAttempts++
        val delayMs = RECONNECT_BASE_DELAY_MS * reconnectAttempts
        Log.w(TAG, "ASR 意外断开, ${delayMs}ms 后第 $reconnectAttempts 次重连...")
        _state.update {
            it.copy(
                status = ASRStatus.Connecting,
                errorMessage = null
            )
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isStopping) connect()
        }
    }

    override fun stop() {
        isStopping = true
        reconnectJob?.cancel()
        recorderJob?.cancel()
        releaseRecorder()
        val socket = webSocket
        if (socket != null) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            scope.launch {
                delay(500)
                socket.close(1000, "stop")
                if (webSocket === socket) {
                    webSocket = null
                    _state.update { it.copy(status = ASRStatus.Idle) }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(
        provider: ASRProviderSetting.OpenAIRealtime,
        socket: WebSocket
    ) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(provider.sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder: AudioRecord
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    provider.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                audioRecord = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException(
                        "AudioRecord 初始化失败, state=${recorder.state}, 请检查录音权限或音频参数"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord 构造/初始化失败", e)
                setError(e.message ?: "麦克风初始化失败")
                return@launch
            }

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                            val event = JSONObject()
                                .put("type", "input_audio_buffer.append")
                                .put("audio", encoded)
                            socket.send(event.toString())
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun handleServerEvent(text: String) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Invalid realtime event: $text", it)
            return
        }

        when (val type = event.optString("type")) {
            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = event.optString("item_id", "default")
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    partialTranscripts[itemId] = (partialTranscripts[itemId] ?: "") + delta
                    publishTranscript()
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val itemId = event.optString("item_id", "default")
                val transcript = event.optString("transcript").trim()
                partialTranscripts.remove(itemId)
                if (transcript.isNotEmpty()) {
                    completedTranscripts.add(transcript)
                }
                publishTranscript()
            }

            "error" -> {
                val error = event.optJSONObject("error")
                setError(error?.optString("message") ?: "ASR realtime error")
            }

            else -> {
                Log.v(TAG, "Ignored realtime event: $type")
            }
        }
    }

    private fun publishTranscript() {
        val rawTranscript = (completedTranscripts + partialTranscripts.values)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val transcript = rawTranscript.stripTrailingEmoji()
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

private fun ASRProviderSetting.OpenAIRealtime.websocketEndpoint(): String {
    val endpoint = websocketUrl.trim()
    if (endpoint.contains("intent=transcription")) return endpoint
    if (endpoint.contains("model=")) return endpoint
    val separator = if (endpoint.contains("?")) "&" else "?"
    return "${endpoint.trimEnd('/')}${separator}intent=transcription"
}

private fun ASRProviderSetting.OpenAIRealtime.sessionUpdateEvent(): JSONObject {
    val transcription = JSONObject()
        .put("model", model)
    if (language.isNotBlank()) transcription.put("language", language)
    if (prompt.isNotBlank()) transcription.put("prompt", prompt)

    return JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject()
                                .put(
                                    "format",
                                    JSONObject()
                                        .put("type", "audio/pcm")
                                        .put("rate", sampleRate)
                                )
                                .put("transcription", transcription)
                                .put(
                                    "noise_reduction",
                                    JSONObject()
                                        .put("type", "near_field")
                                )
                                .put(
                                    "turn_detection",
                                    JSONObject()
                                        .put("type", "server_vad")
                                        .put("threshold", vadThreshold)
                                        .put("prefix_padding_ms", prefixPaddingMs)
                                        .put("silence_duration_ms", silenceDurationMs)
                                )
                        )
                )
        )
}
