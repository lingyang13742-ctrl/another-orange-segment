/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import me.rerere.common.android.Logging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            val errorMsg = "generateText: HTTP ${response.code} error response body: $errorBody"
            Log.e(TAG, errorMsg)
            Logging.log(TAG, errorMsg)
            throw Exception("Failed to get response: ${response.code} $errorBody")
        }

        val bodyStr = response.body?.string() ?: ""

        // 检测响应是否为 SSE 格式（某些 API 即使 stream=false 也返回 SSE）
        val isSSE = bodyStr.trimStart().startsWith("data:")

        if (isSSE) {
            // 解析 SSE 流，合并所有 delta 为完整消息。
            // 这里拿到的是整个响应体,需要按 SSE 协议处理:事件之间用空行分隔,事件内
            // 可能出现多行 data: 字段(中间链路可能把一条消息物理断行),必须把它们用
            // '\n' 拼接还原为一条完整消息后再作为单个 JSON 解析。否则一旦某条消息被
            // 断成多行,按行取 data: 会把完整 JSON 拆碎导致解析失败(Unexpected EOF)。
            val sseChunks = parseSseEvents(bodyStr)
                .mapNotNull { data ->
                    if (data.isNotBlank() && data != "[DONE]") {
                        json.parseToJsonElement(data).jsonObject
                    } else null
                }

            if (sseChunks.isEmpty()) {
                throw Exception("No valid data found in SSE response")
            }

            // 从最后一个有 choices 的 chunk 获取 id、model、usage、finishReason
            val lastChunk = sseChunks.last()
            val id = lastChunk["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val model = lastChunk["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val finishReason = lastChunk["choices"]
                ?.jsonArray?.get(0)?.jsonObject
                ?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: "unknown"
            val usage = parseTokenUsage(lastChunk["usage"] as? JsonObject)

            // 合并所有 delta 为完整 message
            val mergedContent = buildString {
                for (chunk in sseChunks) {
                    val delta = chunk["choices"]
                        ?.jsonArray?.get(0)?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?: chunk["choices"]?.jsonArray?.get(0)?.jsonObject
                            ?.get("message")?.jsonObject
                        ?: continue
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let { append(it) }
                }
            }

            // 尝试从最后一个 chunk 的 delta 或 message 获取 tool_calls 等其他字段
            val lastDelta = lastChunk["choices"]
                ?.jsonArray?.get(0)?.jsonObject
                ?.get("delta")?.jsonObject
                ?: lastChunk["choices"]?.jsonArray?.get(0)?.jsonObject
                    ?.get("message")?.jsonObject
                ?: buildJsonObject { put("content", mergedContent) }

            // 构建 merged message：用合并后的 content 替换原始 content
            val mergedMessage = buildJsonObject {
                for ((key, value) in lastDelta) {
                    if (key == "content") {
                        put("content", mergedContent)
                    } else {
                        put(key, value)
                    }
                }
                // 如果 lastDelta 没有 content 字段，确保添加
                if (!lastDelta.containsKey("content")) {
                    put("content", mergedContent)
                }
            }

            MessageChunk(
                id = id,
                model = model,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = parseMessage(mergedMessage),
                        finishReason = finishReason
                    )
                ),
                usage = usage
            )
        } else {
            // 普通 JSON 响应
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

            val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
            val finishReason = choice["finish_reason"]
                ?.jsonPrimitive
                ?.content
                ?: "unknown"
            val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

            MessageChunk(
                id = id,
                model = model,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = parseMessage(message),
                        finishReason = finishReason
                    )
                ),
                usage = usage
            )
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    println("[onEvent] (done) 结束流: $data")
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $data")
                // okhttp-sse 的 onEvent 拿到的 data 已经是按 SSE 协议规范拼好的完整
                // 消息(网关把一条 data: 物理拆成多行发送时, okHttp 会用 '\n' 还原)。
                // 这里直接当一个完整 JSON 解析即可, 不能再按 '\n' 二次拆分 —— 否则当
                // tool_call 的 arguments 含真实换行且被中间链路断行时, 会把完整 JSON
                // 拆成不完整碎片导致 Unexpected EOF。
                val chunkJson = try {
                    json.parseToJsonElement(data).jsonObject
                } catch (e: Throwable) {
                    // 上游真的发了坏数据时不要让整个流直接崩掉裸抛 Unexpected EOF。
                    // 记录长度和前后片段便于定位, 但避免把整个超长内容打进日志。
                    val preview = if (data.length > 200) {
                        "${data.take(100)}...(${data.length} chars)...${data.takeLast(100)}"
                    } else {
                        data
                    }
                    Log.w(
                        TAG,
                        "onEvent: failed to parse SSE data (len=${data.length}, preview=$preview)",
                        e
                    )
                    close(
                        Exception("Failed to parse stream data: ${e.message} (data length=${data.length})", e)
                    )
                    return
                }
                if (chunkJson["error"] != null) {
                    // 流式响应中携带了 error 字段 (HTTP 连接本身是200, 但错误包在流数据里)
                    // 记录完整的原始 error JSON 内容, 便于排查上游返回的具体错误原因
                    val errorRawJson = chunkJson["error"].toString()
                    val model = chunkJson["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val errorMsg = "onEvent stream error | model=$model | time=${System.currentTimeMillis()} | raw error JSON: $errorRawJson"
                    Log.e(TAG, errorMsg)
                    Logging.log(TAG, errorMsg)
                    val error = chunkJson["error"]!!.parseErrorDetail()
                    Logging.log(TAG, "onEvent stream error parsed: $error")
                    close(error)
                    return
                }
                // 某些网关 (如 silas.zeabur.app) 在上游调用失败时, 不返回标准 error 字段,
                // 而是把错误信息伪装成正常的 content delta 返回 (id="chatcmpl-error")。
                // 如果不拦截, 错误信息会被当成 AI 正常回复存入历史, 导致后续请求全部失败。
                val chunkId = chunkJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
                if (chunkId == "chatcmpl-error") {
                    val errorContent = chunkJson["choices"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("delta")?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contentOrNull ?: "unknown gateway error"
                    Log.e(TAG, "onEvent: gateway returned error disguised as content: $errorContent")
                    Logging.log(TAG, "onEvent: gateway error disguised as content: $errorContent")
                    close(Exception("Gateway error: $errorContent"))
                    return
                }
                val id = chunkJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val model = chunkJson["model"]?.jsonPrimitive?.contentOrNull ?: ""

                val choices = chunkJson["choices"]?.jsonArray ?: JsonArray(emptyList())
                val choiceList = buildList {
                    if (choices.isNotEmpty()) {
                        val choice = choices[0].jsonObject
                        val message =
                            choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                            ?: throw Exception("delta/message is null")
                        val finishReason =
                            choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                ?: "unknown"
                        add(
                            UIMessageChoice(
                                index = 0,
                                delta = parseMessage(message),
                                message = null,
                                finishReason = finishReason,
                            )
                        )
                    }
                }
                val usage = parseTokenUsage(chunkJson["usage"] as? JsonObject)

                val messageChunk = MessageChunk(
                    id = id,
                    model = model,
                    choices = choiceList,
                    usage = usage
                )
                trySend(messageChunk)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                val failureMsg = "onFailure: ${t?.javaClass?.name} ${t?.message} / response=$response"
                Log.e(TAG, failureMsg)
                Logging.log(TAG, failureMsg)

                val bodyRaw = response?.body?.stringSafe()
                // 记录上游返回的原始响应体, 便于排查 400/500 等错误的具体原因
                if (!bodyRaw.isNullOrBlank()) {
                    val bodyMsg = "onFailure: raw response body (HTTP ${response?.code}): $bodyRaw"
                    Log.e(TAG, bodyMsg)
                    Logging.log(TAG, bodyMsg)
                }
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                        val detailMsg = "onFailure: parsed error detail: $exception"
                        Log.e(TAG, detailMsg)
                        Logging.log(TAG, detailMsg)
                    }
                } catch (e: Throwable) {
                    val parseMsg = "onFailure: failed to parse error body: $bodyRaw"
                    Log.w(TAG, parseMsg, e)
                    Logging.log(TAG, parseMsg)
                    exception = e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta, 导致回复中间缺字 (#1295), 因此缓冲必须无界。
        // 与上游 rikkahub 对齐: 在 callbackFlow 上叠加 Channel.UNLIMITED 缓冲。
    }.buffer(Channel.UNLIMITED)


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages, params.model))

            // 智谱 GLM 等 thinking 模型在开启深度思考时不允许设置 temperature/top_p,
            // 否则触发 "Invalid request body" (InvalidParameter) 400。
            // moonshot/deepseek 的 thinking 字段结构与智谱一致, 一并处理。
            val thinkingEnabled = params.model.abilities.contains(ModelAbility.REASONING) &&
                params.reasoningLevel.isEnabled &&
                host in setOf("open.bigmodel.cn", "api.moonshot.cn", "api.deepseek.com")
            if (isModelAllowTemperature(params.model) && !thinkingEnabled) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                // parameters() may return null (e.g. MCP gateway tools without
                                // inputSchema). Serializing null → "parameters": null is rejected
                                // by strict providers (Zhipu GLM returns "Invalid request body").
                                // Fall back to an empty object schema so the request is always valid.
                                val schema = tool.parameters()
                                if (schema != null) {
                                    put("parameters", json.encodeToJsonElement(schema))
                                } else {
                                    put("parameters", buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject { })
                                    })
                                }
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun buildMessages(messages: List<UIMessage>, model: Model) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }
        // 纯文本模型 (如 GLM-5.2) 不接受 image_url, 收到会报 "Model only support text input"。
        // OcrTransformer 只覆盖 file: 图片, http/base64 图片会漏网; 这里在序列化层兜底,
        // 模型不支持 IMAGE 时直接跳过 Image part, 不再发给 API。
        val supportsImage = model.inputModalities.contains(Modality.IMAGE)

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(message, includeReasoning = true, supportsImage = supportsImage)
            } else {
                addNonAssistantMessage(message, supportsImage = supportsImage)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(message: UIMessage, includeReasoning: Boolean, supportsImage: Boolean = true) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || (supportsImage && it is UIMessagePart.Image) }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart,
                        supportsImage = supportsImage,
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        val textOutput = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        val imageOutput = if (supportsImage) tool.output.filterIsInstance<UIMessagePart.Image>() else emptyList()

                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put("content", textOutput)
                        })

                        // If tool output contains images, inject a user message with the images
                        // so the AI model can "see" them (tool result content only supports text)
                        if (imageOutput.isNotEmpty()) {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "[Tool ${tool.toolName} returned an image]")
                                    })
                                    imageOutput.forEach { imagePart ->
                                        add(buildJsonObject {
                                            imagePart.encodeBase64().onSuccess { encodedImage ->
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", encodedImage.base64)
                                                })
                                            }.onFailure {
                                                put("type", "text")
                                                put("text", "[Image encoding failed: ${it.message}]")
                                            }
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart,
                supportsImage = supportsImage,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
        supportsImage: Boolean = true,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> supportsImage && part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (supportsImage) {
                                    add(buildJsonObject {
                                        part.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure {
                                            it.printStackTrace()
                                            put("type", "text")
                                            put("text", "")
                                        }
                                    })
                                }
                                // 模型不支持图片时跳过, 不序列化 image_url
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                put("arguments", tool.input)
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage, supportsImage: Boolean = true) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            // 模型不支持图片时, 先过滤掉 Image part, 避免发送 image_url 触发 "Model only support text input"
            val parts = if (supportsImage) message.parts else message.parts.filter { it !is UIMessagePart.Image }
            if (parts.isOnlyTextPart()) {
                put("content", parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val content = jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }

    /**
     * 把一段完整的 SSE 响应体解析成 data 事件列表。
     *
     * 遵循 SSE 协议: 事件之间用空行分隔, 一个事件内可以有多行 `data:` 字段, 多行 data
     * 必须用 '\n' 拼接成一条完整消息。这样即使中间链路(Zeabur 等反代)把一条 data:
     * 物理断成多行发送, 也能还原成原始的完整 JSON, 避免按行粗暴切分导致 Unexpected EOF。
     *
     * 注意: 这里解析的是一次性拿到的整个响应体(generateText 非 stream 场景, 但服务端
     * 仍返回了 SSE), 不是 okHttp EventSource 拼好的单条事件, 所以需要自己做事件边界识别。
     */
    private fun parseSseEvents(body: String): List<String> {
        val events = mutableListOf<String>()
        val dataLines = mutableListOf<String>()

        fun flush() {
            if (dataLines.isNotEmpty()) {
                events.add(dataLines.joinToString("\n"))
                dataLines.clear()
            }
        }

        for (line in body.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // 空行 = 事件边界
                flush()
            } else if (trimmed.startsWith("data:")) {
                dataLines.add(trimmed.removePrefix("data:").trim())
            }
            // 忽略 event:/id:/retry: 等其它 SSE 字段, 这里只关心 data
        }
        // body 末尾可能没有空行收尾
        flush()
        return events
    }
}
