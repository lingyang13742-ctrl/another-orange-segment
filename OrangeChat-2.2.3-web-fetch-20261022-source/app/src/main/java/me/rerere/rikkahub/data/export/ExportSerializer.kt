/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    // 支持的文件扩展名
    val supportedExtensions = listOf("json", "txt", "md")

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            importList(context, uri).getOrThrow().firstOrNull()
                ?: throw IllegalArgumentException("导入失败：文件内容为空")
        }
    }

    /**
     * 导入为列表（支持酒馆预设一个文件生成多条 ModeInjection）
     */
    fun importList(context: Context, uri: Uri): Result<List<PromptInjection.ModeInjection>> {
        return runCatching {
            val content = readUri(context, uri)
            val fileName = getUriFileName(context, uri)

            // 根据文件扩展名选择导入方式
            val extension = fileName?.substringAfterLast(".")?.lowercase()

            val result = when (extension) {
                "txt", "md" -> tryImportPlainText(content, fileName)
                "json" -> tryImportSillyTavernPreset(content, fileName?.removeSuffix(".json"))
                    ?: tryImportNative(content)
                    ?: tryImportDirectJson(content)
                else -> {
                    // 尝试所有格式
                    tryImportSillyTavernPreset(content, fileName?.removeSuffix(".json"))
                        ?: tryImportNative(content)
                        ?: tryImportDirectJson(content)
                        ?: tryImportPlainText(content, fileName)
                }
            }

            result ?: throw IllegalArgumentException("不支持的格式。请使用从本应用导出的JSON文件、酒馆预设、TXT或MD文件")
        }
    }

    private fun tryImportNative(json: String): List<PromptInjection.ModeInjection>? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            listOf(
                ExportSerializer.DefaultJson
                    .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                    .copy(id = Uuid.random())
            )
        }.getOrNull()
    }

    /**
     * 尝试直接解析为 ModeInjection JSON（不需要包裹在 ExportData 中）
     * 验证解析结果必须有实际内容，防止酒馆预设等格式被误解析为空 ModeInjection
     */
    private fun tryImportDirectJson(json: String): List<PromptInjection.ModeInjection>? {
        return runCatching {
            val result = ExportSerializer.DefaultJson
                .decodeFromString<PromptInjection.ModeInjection>(json)
                .copy(id = Uuid.random())
            // 必须有实际内容才算有效，防止其他 JSON 格式被误解析
            if (result.content.isBlank()) return null
            listOf(result)
        }.getOrNull()
    }

    /**
     * 从纯文本文件导入（TXT/MD）
     * 文件内容作为 content，文件名作为 name
     */
    private fun tryImportPlainText(content: String, fileName: String?): List<PromptInjection.ModeInjection>? {
        if (content.isBlank()) return null
        val name = fileName?.removeSuffix(".txt")?.removeSuffix(".md") ?: "导入的提示词"
        return listOf(
            PromptInjection.ModeInjection(
                id = Uuid.random(),
                name = name,
                content = content.trim(),
                enabled = true
            )
        )
    }

    /**
     * 从 SillyTavern 预设格式导入
     * 将每个 prompt 条目转为独立的 ModeInjection
     */
    private fun tryImportSillyTavernPreset(json: String, fileName: String?): List<PromptInjection.ModeInjection>? {
        return runCatching {
            val stPreset = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernPreset.serializer(),
                json
            )

            // 将每个有内容的 prompt 转换为独立的 ModeInjection
            // 忽略 marker 条目（它们是位置标记，没有实际内容）
            val injections = stPreset.prompts
                .filter { !it.marker && it.content.isNotBlank() }
                .mapIndexed { index, prompt ->
                    // injection_position: 0=after main prompt, 1=after scenario, 2=before char desc,
                    // 3=after examples, 4=after chat history, 5=at depth
                    val position = when (prompt.injection_position) {
                        0 -> InjectionPosition.AFTER_SYSTEM_PROMPT
                        1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
                        2 -> InjectionPosition.AFTER_SYSTEM_PROMPT
                        3 -> InjectionPosition.TOP_OF_CHAT
                        4 -> InjectionPosition.BOTTOM_OF_CHAT
                        5 -> InjectionPosition.AT_DEPTH
                        else -> when (prompt.role) {
                            "system" -> InjectionPosition.AFTER_SYSTEM_PROMPT
                            "assistant" -> InjectionPosition.BOTTOM_OF_CHAT
                            else -> InjectionPosition.BOTTOM_OF_CHAT
                        }
                    }
                    val role = when (prompt.role) {
                        "assistant" -> MessageRole.ASSISTANT
                        else -> MessageRole.USER
                    }
                    PromptInjection.ModeInjection(
                        id = Uuid.random(),
                        name = prompt.name.ifEmpty { prompt.identifier.ifEmpty { "${fileName ?: "酒馆预设"} - 条目${index + 1}" } },
                        enabled = true,
                        position = position,
                        content = prompt.content.trim(),
                        injectDepth = prompt.injection_depth,
                        role = role,
                    )
                }

            if (injections.isEmpty()) return null

            injections
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("不支持的格式。请使用从本应用导出的JSON文件，或SillyTavern格式的Lorebook JSON")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                entries = stLorebook.entries.values.map { entry ->
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position),
                        injectDepth = entry.depth,
                        content = entry.content,
                        keywords = entry.key,
                        useRegex = false, // SillyTavern 格式不支持 useRegex
                        caseSensitive = entry.caseSensitive ?: false,
                        scanDepth = entry.scanDepth ?: 4,
                        constantActive = entry.constant,
                    )
                }
            )
        }.getOrNull()
    }

    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2 -> InjectionPosition.TOP_OF_CHAT
            3 -> InjectionPosition.TOP_OF_CHAT // After Examples -> 聊天历史开头
            4 -> InjectionPosition.AT_DEPTH    // @Depth 模式
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
}

@Serializable
private data class SillyTavernPreset(
    val prompts: List<SillyTavernPrompt> = emptyList(),
)

@Serializable
private data class SillyTavernPrompt(
    val name: String = "",
    val role: String = "system",
    val content: String = "",
    val identifier: String = "",
    val system_prompt: Boolean = false,
    val marker: Boolean = false,
    val forbid_overrides: Boolean = false,
    val injection_position: Int = 0,
    val injection_depth: Int = 4,
)

@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
)
