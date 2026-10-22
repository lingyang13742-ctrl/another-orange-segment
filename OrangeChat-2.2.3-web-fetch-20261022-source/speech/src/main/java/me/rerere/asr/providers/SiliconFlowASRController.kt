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
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.asr.stripTrailingEmoji
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SiliconFlowASR"

class SiliconFlowASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.SiliconFlow
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var vadJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var audioBuffer: ByteArrayOutputStream? = null
    private var recordingStartTime: Long = 0L
    private var amplitudesBuffer = mutableListOf<Float>()

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
        audioBuffer = ByteArrayOutputStream()
        amplitudesBuffer = mutableListOf()
        recordingStartTime = System.currentTimeMillis()
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true,
                transcript = ""
            )
        }
        startRecorder()
        startLocalVad()
    }

    override fun stop() {
        vadJob?.cancel()
        recorderJob?.cancel()
        val buffer = audioBuffer
        audioBuffer = null
        releaseRecorder()

        if (buffer != null && buffer.size() > 0) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            scope.launch {
                transcribeAudio(buffer.toByteArray())
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
    private fun startRecorder() {
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
                        audioBuffer?.write(buffer, 0, read)
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

    private suspend fun transcribeAudio(pcmData: ByteArray) {
        val durationMs = System.currentTimeMillis() - recordingStartTime

        withContext(Dispatchers.IO) {
            try {
                // Convert PCM to WAV
                val wavData = pcmToWav(pcmData, provider.sampleRate)

                // Save to persistent voice file
                val voiceDir = File(context.filesDir, "voice_messages")
                voiceDir.mkdirs()
                val audioFile = File(voiceDir, "voice_${System.currentTimeMillis()}.wav")
                FileOutputStream(audioFile).use { it.write(wavData) }

                // Build multipart request
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", provider.model)
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        audioFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .apply {
                        if (provider.language.isNotBlank()) {
                            addFormDataPart("language", provider.language)
                        }
                    }
                    .build()

                val request = Request.Builder()
                    .url(provider.baseUrl.trim())
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody == null) {
                    audioFile.delete()
                    setError("API error: empty response")
                    return@withContext
                }

                Log.d(TAG, "API response: ${response.code} $responseBody")
                val json = JSONObject(responseBody)

                // SiliconFlow response: { code, message, data }
                val code = json.optInt("code", -1)
                val message = json.optString("message", "")

                // Check for error: code present and non-zero
                if (code != -1 && code != 0) {
                    audioFile.delete()
                    setError(message.ifEmpty { "API error code: $code" })
                    return@withContext
                }

                val rawText = json.optString("data", "").trim().ifEmpty {
                    json.optString("text", "").trim()
                }
                val text = rawText.stripTrailingEmoji()

                if (text.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            transcript = text,
                            status = ASRStatus.Idle,
                            errorMessage = null,
                            audioFilePath = audioFile.absolutePath,
                            durationMs = durationMs
                        )
                    }
                    onTranscriptChange?.invoke(text)
                } else {
                    _state.update {
                        it.copy(
                            status = ASRStatus.Idle,
                            errorMessage = null,
                            audioFilePath = audioFile.absolutePath,
                            durationMs = durationMs
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                setError(e.message ?: "Transcription failed")
            }
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val wav = ByteArrayOutputStream(44 + dataSize)
        // RIFF header
        wav.write("RIFF".toByteArray())
        writeIntLE(wav, totalSize)
        wav.write("WAVE".toByteArray())
        // fmt chunk
        wav.write("fmt ".toByteArray())
        writeIntLE(wav, 16) // chunk size
        writeShortLE(wav, 1) // PCM format
        writeShortLE(wav, numChannels.toShort())
        writeIntLE(wav, sampleRate)
        writeIntLE(wav, byteRate)
        writeShortLE(wav, blockAlign.toShort())
        writeShortLE(wav, bitsPerSample.toShort())
        // data chunk
        wav.write("data".toByteArray())
        writeIntLE(wav, dataSize)
        wav.write(pcmData)

        return wav.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Short) {
        out.write(value.toInt() and 0xFF)
        out.write((value.toInt() shr 8) and 0xFF)
    }

    /**
     * 本地 VAD: 检测用户说完话后自动停止录音
     * 优化: 检测到 500ms 静音就自动停止, 不需要手动点停止
     */
    private fun startLocalVad() {
        vadJob?.cancel()
        vadJob = scope.launch {
            var lastAmplitudeTime = System.currentTimeMillis()
            var speechDetected = false
            val silenceThresholdMs = 500L // 500ms 静音就停止
            val minSpeechDurationMs = 400L // 至少说 400ms 才有效
            val maxRecordingDurationMs = 30_000L // 最多录 30 秒

            while (isActive) {
                delay(50)

                // 检查最大录音时长
                val recordingDuration = System.currentTimeMillis() - recordingStartTime
                if (recordingDuration > maxRecordingDurationMs) {
                    Log.d(TAG, "VAD: Max recording duration reached")
                    stop()
                    break
                }

                val amplitudes = _state.value.amplitudes
                val recentAmplitude = if (amplitudes.isNotEmpty()) {
                    amplitudes.takeLast(3).average().toFloat()
                } else 0f

                // 检测是否有语音活动
                if (recentAmplitude > 0.03f) {
                    lastAmplitudeTime = System.currentTimeMillis()
                    if (!speechDetected) {
                        speechDetected = true
                        Log.d(TAG, "VAD: Speech detected")
                    }
                }

                // 如果已经检测到语音, 且静音超过阈值, 就自动停止
                if (speechDetected) {
                    val silentFor = System.currentTimeMillis() - lastAmplitudeTime
                    val speechDuration = lastAmplitudeTime - recordingStartTime

                    if (silentFor >= silenceThresholdMs && speechDuration >= minSpeechDurationMs) {
                        Log.d(TAG, "VAD: Auto-stop after ${silentFor}ms silence")
                        stop()
                        break
                    }
                }
            }
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