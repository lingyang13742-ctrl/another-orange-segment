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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.uuid.Uuid

private const val TAG = "VolcengineASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
// 意外断开时的自动重连参数 (修复 ASR 运行一段时间后冻结的 bug)
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val RECONNECT_BASE_DELAY_MS = 1000L

class VolcengineASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Volcengine
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var lastText = ""

    // 用户主动 stop() 时置 true, 用来区分"用户挂断"和"网络断开需要重连"
    @Volatile
    private var isStopping = false
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
        lastText = ""
        isStopping = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        connect()
    }

    private fun connect() {
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketUrl)
            .addHeader("X-Api-Key", provider.apiKey)
            .addHeader("X-Api-Resource-Id", provider.resourceId)
            .addHeader("X-Api-Request-Id", Uuid.random().toString())
            .addHeader("X-Api-Sequence", "-1")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = buildFullClientRequestPayload()
                val compressed = gzipCompress(payload)
                val frame = buildFrame(
                    messageType = MSG_FULL_CLIENT_REQUEST,
                    flags = 0x00,
                    serialization = SER_JSON,
                    compression = COMP_GZIP,
                    payload = compressed
                )
                webSocket.send(frame.toByteString())
                reconnectAttempts = 0
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                startRecorder(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryResponse(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Volcengine ASR websocket failed", t)
                releaseRecorder()
                handleDisconnect(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Volcengine ASR websocket closed: code=$code, reason=$reason")
                releaseRecorder()
                handleDisconnect("ASR 连接已断开")
            }
        })
    }

    /**
     * 处理意外断开: 用户没主动 stop 时自动重连, 否则正常进入 Idle.
     * 修复 ASR 运行一段时间后冻结 (音波球不动/说话发不出去) 的 bug.
     */
    private fun handleDisconnect(reason: String) {
        if (isStopping) {
            _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            return
        }
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
            val lastFrame = buildFrame(
                messageType = MSG_AUDIO_ONLY,
                flags = FLAG_LAST_PACKET,
                serialization = SER_NONE,
                compression = COMP_NONE,
                payload = ByteArray(0)
            )
            socket.send(lastFrame.toByteString())
            scope.launch {
                delay(1000)
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

    private fun buildFullClientRequestPayload(): ByteArray {
        val audio = JSONObject()
            .put("format", "pcm")
            .put("rate", SAMPLE_RATE)
            .put("bits", 16)
            .put("channel", 1)
        if (provider.language.isNotBlank()) {
            audio.put("language", provider.language)
        }

        val json = JSONObject()
            .put("user", JSONObject().put("uid", "rikkahub"))
            .put("audio", audio)
            .put(
                "request", JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("show_utterances", true)
                    .put("result_type", "full")
            )
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun handleBinaryResponse(data: ByteArray) {
        if (data.size < 4) return

        val byte1 = data[1].toInt() and 0xFF
        val byte2 = data[2].toInt() and 0xFF
        val messageType = (byte1 shr 4) and 0x0F
        val messageFlags = byte1 and 0x0F
        val compression = byte2 and 0x0F

        var offset = 4

        when (messageType) {
            0x09 -> {
                val hasSequence = (messageFlags and 0x01) != 0
                if (hasSequence) offset += 4

                if (offset + 4 > data.size) return
                val payloadSize = ByteBuffer.wrap(data, offset, 4)
                    .order(ByteOrder.BIG_ENDIAN).int
                offset += 4

                if (payloadSize <= 0 || offset + payloadSize > data.size) return

                var payload = data.copyOfRange(offset, offset + payloadSize)
                if (compression == COMP_GZIP) {
                    payload = runCatching { gzipDecompress(payload) }.getOrElse {
                        Log.w(TAG, "Gzip decompression failed", it)
                        return
                    }
                }

                val json = runCatching {
                    JSONObject(String(payload, Charsets.UTF_8))
                }.getOrElse {
                    Log.w(TAG, "Failed to parse response JSON", it)
                    return
                }

                val rawText = json.optJSONObject("result")?.optString("text", "") ?: ""
                val text = rawText.stripTrailingEmoji()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    _state.update { it.copy(transcript = text, errorMessage = null) }
                    scope.launch { onTranscriptChange?.invoke(text) }
                }
            }

            0x0F -> {
                if (offset + 4 > data.size) return
                offset += 4 // skip error code

                if (offset + 4 > data.size) return
                val msgSize = ByteBuffer.wrap(data, offset, 4)
                    .order(ByteOrder.BIG_ENDIAN).int
                offset += 4

                val errorMsg = if (msgSize > 0 && offset + msgSize <= data.size) {
                    String(data, offset, msgSize, Charsets.UTF_8)
                } else {
                    "Volcengine ASR error"
                }
                Log.e(TAG, "Volcengine ASR error: $errorMsg")
                setError(errorMsg)
            }

            else -> Log.v(TAG, "Ignored message type: $messageType")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val chunkSize = (SAMPLE_RATE * 2 * 200 / 1000).coerceAtLeast(minBufferSize)

            val recorder: AudioRecord
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    chunkSize * 2
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
                val buffer = ByteArray(chunkSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            val frame = buildFrame(
                                messageType = MSG_AUDIO_ONLY,
                                flags = 0x00,
                                serialization = SER_NONE,
                                compression = COMP_NONE,
                                payload = buffer.copyOfRange(0, read)
                            )
                            socket.send(frame.toByteString())
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

    private fun setError(message: String) {
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MSG_FULL_CLIENT_REQUEST = 0x01
        private const val MSG_AUDIO_ONLY = 0x02
        private const val SER_NONE = 0x00
        private const val SER_JSON = 0x01
        private const val COMP_NONE = 0x00
        private const val COMP_GZIP = 0x01
        private const val FLAG_LAST_PACKET = 0x02

        private fun buildFrame(
            messageType: Int,
            flags: Int,
            serialization: Int,
            compression: Int,
            payload: ByteArray
        ): ByteArray {
            val header = byteArrayOf(
                0x11.toByte(),
                ((messageType shl 4) or (flags and 0x0F)).toByte(),
                ((serialization shl 4) or (compression and 0x0F)).toByte(),
                0x00
            )
            val size = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.size)
                .array()
            return header + size + payload
        }

        private fun gzipCompress(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            return bos.toByteArray()
        }

        private fun gzipDecompress(data: ByteArray): ByteArray {
            return GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }
    }
}
