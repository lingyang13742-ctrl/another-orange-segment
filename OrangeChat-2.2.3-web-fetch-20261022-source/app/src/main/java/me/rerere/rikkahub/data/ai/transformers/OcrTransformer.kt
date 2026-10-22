/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        // 检测消息中是否包含图片: 既检查最外层 parts, 也检查 Tool.output 里的图片
        // (camera_capture 等工具返回的图片存放在 Tool.output 中, 不在最外层 parts)
        val hasImages = messages.any { message ->
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Image -> part.url.startsWith("file:")
                    is UIMessagePart.Tool -> part.output.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
                    else -> false
                }
            }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                // 最外层图片: OCR 转文字
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                // Tool.output 里的图片: 递归扫描, 把图片替换成 OCR 文字
                                part is UIMessagePart.Tool -> {
                                    part.copy(
                                        output = part.output.map { outputPart ->
                                            when {
                                                outputPart is UIMessagePart.Image && outputPart.url.startsWith("file:") -> {
                                                    UIMessagePart.Text(performOcr(outputPart))
                                                }
                                                else -> outputPart
                                            }
                                        }
                                    )
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(part.url))
                )
            ),
            params = TextGenerationParams(
                // OCR 是受控的内部调用, 必须保证图片被序列化发送。
                // 本仓库在序列化层(ChatCompletionsAPI.buildMessages)依据 inputModalities 决定是否下发 image_url,
                // 若用户所选 OCR 模型未被标记 IMAGE 模态(自建模型 / 注册表未收录的视觉模型常见),
                // 图片会在序列化时被静默剥离, 模型收到空 content, 从而回复 "no visible content" / "no image was provided"。
                // 这里强制注入 IMAGE 模态, 确保图片一定发送(对齐上游 rikkahub 总是序列化图片的行为);
                // 若模型确实不支持图片, 会由 API 返回明确错误(已被下方 runCatching 兜底捕获)。
                model = model.copy(inputModalities = (model.inputModalities + Modality.IMAGE).distinct()),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, OCR failed]"
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }
}
