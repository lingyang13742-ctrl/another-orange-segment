/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.hooks
 
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.SiliconFlowASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
 
private const val ASR_TAG = "CustomAsrState"
 
@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
 
    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }
 
    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }
 
    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }
 
    return asrState
}
 
/**
 * 非 Compose 版本的工厂函数，供 Service 等非 UI 场景创建独立的 ASR 实例。
 * 与 rememberCustomAsrState() 创建的实例互相独立，互不影响。
 *
 * 【重要修复说明】
 * 这是一个 suspend 函数，会真正挂起等待 provider 设置完成后才返回实例。
 * 之前的实现签名是 `fun createCustomAsrState(..., scope: CoroutineScope): CustomAsrState`，
 * 内部把 `updateProvider(...)` 丢进 `scope.launch { ... }` 里"发射后不管"，然后立刻
 * return 一个 controller 还是 null 的半成品实例。调用方（VoiceCallService）紧接着
 * 同步调用 asr.start()，此时那个负责设置 controller 的协程根本还没被调度执行，
 * controller 必然是 null，CustomAsrStateImpl.start() 里 `controller?.start(...)`
 * 这个 safe call 遇到 null 会静默跳过、不报错也不崩溃，导致录音从未真正启动，
 * 转写永远是空字符串，UI 因此永远卡在"聆听中"不动。
 *
 * 现在改成 suspend 函数，调用方必须在协程里 `asr = createCustomAsrState(...)`，
 * 这行代码执行完毕、拿到返回值时，可以保证 provider 已经真正设置好。
 */
suspend fun createCustomAsrState(
    context: Context,
    httpClient: OkHttpClient,
    settingsStore: SettingsStore,
): CustomAsrState {
    val asrState = CustomAsrStateImpl(context.applicationContext, httpClient)
    try {
        val settings = settingsStore.settingsFlow.first()
        asrState.updateProvider(settings.getSelectedASRProvider())
    } catch (e: Exception) {
        Log.e(ASR_TAG, "createCustomAsrState: 初始化 ASR provider 失败", e)
    }
    return asrState
}
 
interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cleanup()
}
 
internal class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())
 
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()
 
    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState
 
    fun updateProvider(provider: ASRProviderSetting?) {
        try {
            controller?.dispose()
        } catch (e: Exception) {
            Log.e(ASR_TAG, "updateProvider: 释放旧 controller 失败", e)
        }
        controller = provider?.let { createController(it) }
        if (controller == null) {
            idleState.value = ASRState()
            Log.w(ASR_TAG, "updateProvider: provider 为空或不合法, controller 未创建, provider=$provider")
        }
    }
 
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (controller == null) {
            Log.e(ASR_TAG, "start: controller 为 null, 无法启动录音, 请检查 ASR provider 是否已正确配置")
            throw IllegalStateException("ASR provider 未配置或 API Key 为空，无法启动语音识别")
        }
        try {
            val result = audioManager.requestAudioFocus(audioFocusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                controller?.start(onTranscriptChange)
            } else {
                Log.w(ASR_TAG, "start: requestAudioFocus 未获得焦点, result=$result, 无法启动录音")
                throw IllegalStateException("无法获取麦克风焦点 (result=$result)")
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.e(ASR_TAG, "start: 启动录音失败", e)
            throw e
        }
    }
 
    override fun stop() {
        try {
            controller?.stop()
        } catch (e: Exception) {
            Log.e(ASR_TAG, "stop: 停止录音失败", e)
        }
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (e: Exception) {
            Log.e(ASR_TAG, "stop: abandonAudioFocusRequest 失败", e)
        }
    }
 
    override fun cleanup() {
        try {
            controller?.dispose()
        } catch (e: Exception) {
            Log.e(ASR_TAG, "cleanup: dispose controller 失败", e)
        }
        controller = null
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (e: Exception) {
            Log.e(ASR_TAG, "cleanup: abandonAudioFocusRequest 失败", e)
        }
    }
 
    private fun createController(provider: ASRProviderSetting): ASRController? {
        return try {
            when (provider) {
                is ASRProviderSetting.OpenAIRealtime -> {
                    if (provider.apiKey.isBlank()) {
                        Log.w(ASR_TAG, "createController: OpenAIRealtime apiKey 为空")
                        return null
                    }
                    OpenAIRealtimeASRController(context, httpClient, provider)
                }
 
                is ASRProviderSetting.SiliconFlow -> {
                    if (provider.apiKey.isBlank()) {
                        Log.w(ASR_TAG, "createController: SiliconFlow apiKey 为空")
                        return null
                    }
                    SiliconFlowASRController(context, httpClient, provider)
                }
 
                is ASRProviderSetting.Volcengine -> {
                    if (provider.apiKey.isBlank()) {
                        Log.w(ASR_TAG, "createController: Volcengine apiKey 为空")
                        return null
                    }
                    VolcengineASRController(context, httpClient, provider)
                }

                is ASRProviderSetting.MiMo -> {
                    if (provider.apiKey.isBlank()) {
                        Log.w(ASR_TAG, "createController: MiMo apiKey 为空")
                        return null
                    }
                    MiMoASRController(context, httpClient, provider)
                }
            }
        } catch (e: Exception) {
            Log.e(ASR_TAG, "createController: 创建 ASRController 失败, provider=$provider", e)
            null
        }
    }
}