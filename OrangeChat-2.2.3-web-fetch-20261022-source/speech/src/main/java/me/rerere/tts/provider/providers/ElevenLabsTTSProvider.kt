/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsTTSProvider"

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        try {
            // clamp 到 API 要求的 0..1 区间, 防止迁移/导入的旧设置超范围触发 400
            val stability = providerSetting.stability.coerceIn(0f, 1f)
            val similarityBoost = providerSetting.similarityBoost.coerceIn(0f, 1f)

            val requestBody = JSONObject().apply {
                put("text", request.text)
                put("model_id", providerSetting.modelId)
                // 显式锁定输出格式为 mp3, 与下面 AudioFormat.MP3 一致。
                // eleven_v3 等模型默认返回 PCM, 不显式指定会导致拿到 PCM 却按 MP3
                // 解析, 播放杂音或 PlaybackException。
                put("output_format", "mp3_44100_128")
                put("voice_settings", JSONObject().apply {
                    put("stability", stability)
                    put("similarity_boost", similarityBoost)
                })
            }

            Log.i(TAG, "generateSpeech request: voiceId=${providerSetting.voiceId}, modelId=${providerSetting.modelId}, body=$requestBody")

            val httpRequest = Request.Builder()
                .url("${providerSetting.baseUrl}/text-to-speech/${providerSetting.voiceId}")
                .addHeader("xi-api-key", providerSetting.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errorBody = try {
                    response.body.string()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read error body", e)
                    null
                }
                Log.e(TAG, "generateSpeech failed: code=${response.code}, message=${response.message}, body=$errorBody, voiceId=${providerSetting.voiceId}")
                throw Exception("ElevenLabs TTS request failed: ${response.code} ${response.message}")
            }

            val audioData = response.body.bytes()

            emit(
                AudioChunk(
                    data = audioData,
                    format = AudioFormat.MP3,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "elevenlabs",
                        "model" to providerSetting.modelId,
                        "voice" to providerSetting.voiceId
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "generateSpeech exception: voiceId=${providerSetting.voiceId}, modelId=${providerSetting.modelId}", e)
            throw e
        }
    }
}