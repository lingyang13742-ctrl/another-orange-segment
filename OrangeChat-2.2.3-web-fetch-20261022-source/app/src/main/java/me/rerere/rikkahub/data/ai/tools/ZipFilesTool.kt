/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件缓存：按 conversationId 隔离，存储最近一次 write_files 工具调用的文件内容
 * 用于后续的增量修改（edits）模式
 * 修复：之前是全局单例，不同对话之间会互相串文件
 */
object WriteFilesCache {
    private val caches = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun get(conversationId: String, name: String): String? =
        caches[conversationId]?.get(name)

    fun put(conversationId: String, name: String, content: String) {
        caches.computeIfAbsent(conversationId) { ConcurrentHashMap() }[name] = content
    }

    fun getAll(conversationId: String): Map<String, String> =
        caches[conversationId]?.toMap() ?: emptyMap()

    fun clear(conversationId: String) {
        caches.remove(conversationId)
    }

    fun clearAll() {
        caches.clear()
    }

    fun updateAll(conversationId: String, files: Map<String, String>) {
        val map = caches.computeIfAbsent(conversationId) { ConcurrentHashMap() }
        map.clear()
        map.putAll(files)
    }
}

/**
 * 构建 write_files 工具
 * AI 可以直接将文件内容打包成 ZIP 供用户下载
 * 支持两种模式：
 * 1. 完整写入模式：传入 files 数组，每个文件包含完整内容
 * 2. 增量修改模式：传入 edits 数组，对已缓存文件进行 search/replace 修改
 *
 * conversationId: 用于隔离不同对话的文件缓存，防止串文件
 */
fun buildWriteFilesTool(conversationId: String? = null): Tool = Tool(
    name = "write_files",
    description = """
        Package files into a ZIP archive for the user to download.

        Two modes available:
        1. **Full write**: Provide `files` array with complete file contents. Use for new files or when rewriting entire files.
           Example: {"zip_name":"project.zip","files":[{"name":"MainActivity.kt","content":"full content..."}]}

        2. **Incremental edit** (saves tokens!): Provide `edits` array with search/replace pairs to modify previously cached files. Use `base_files:"previous"` to reference the last write_files call's files as the base.
           Example: {"zip_name":"project-v2.zip","base_files":"previous","edits":[{"name":"MainActivity.kt","search":"old code","replace":"new code"}]}

        Edit rules:
        - `search` must be an EXACT match of the text to replace (copy it verbatim from the original)
        - You can apply multiple edits to the same file
        - Files not mentioned in `edits` keep their cached content unchanged
        - If `search` is not found, the entire tool call FAILS with an error. Double-check your search text matches the original exactly.
        - If you need to add a new file not in the cache, include it in the `files` array alongside `edits`

        IMPORTANT: Always use actual filenames as code block language tags. For example:
        - Use ```MainActivity.kt instead of ```kotlin
        - Use ```index.html instead of ```html
        If the file is in a subdirectory, include the path: ```src/main/java/com/example/App.kt
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("zip_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the ZIP archive (must end with .zip). Choose a meaningful name like 'my-project.zip'.")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "List of files with complete content. Use this for new files or when rewriting entire files. Each file has 'name' (filename with extension, can include subdirectory path) and 'content' (the full file content as a string).")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename with extension, e.g. 'MainActivity.kt', 'index.html'. Can include subdirectory path like 'src/main/App.kt'.")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "The full content of the file as a string.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("name"))
                            add(JsonPrimitive("content"))
                        })
                    })
                })
                put("base_files", buildJsonObject {
                    put("type", "string")
                    put("description", "Set to 'previous' to use the files from the last write_files call as the base for edits. Required when using edits mode.")
                })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put("description", "List of search/replace edits to apply to cached files. Each edit has 'name' (filename to edit), 'search' (exact text to find), and 'replace' (replacement text). Multiple edits can target the same file and are applied in order. If any search text is not found, the ENTIRE tool call fails with an error.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename to edit. Must exist in the cached files from a previous write_files call.")
                            })
                            put("search", buildJsonObject {
                                put("type", "string")
                                put("description", "The exact text to find in the file. Must be a verbatim copy of the original text. Will be replaced with the 'replace' value. If not found, the tool call FAILS.")
                            })
                            put("replace", buildJsonObject {
                                put("type", "string")
                                put("description", "The replacement text.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("name"))
                            add(JsonPrimitive("search"))
                            add(JsonPrimitive("replace"))
                        })
                    })
                })
            },
            required = listOf("zip_name")
        )
    },
    execute = {
        val params = it.jsonObject
        val zipName = params["zip_name"]?.jsonPrimitive?.contentOrNull
            ?: error("zip_name is required")

        if (!zipName.endsWith(".zip")) {
            error("zip_name must end with .zip")
        }

        val convId = conversationId ?: "default"
        val filesParam = params["files"]?.jsonArray
        val editsParam = params["edits"]?.jsonArray
        val baseFiles = params["base_files"]?.jsonPrimitive?.contentOrNull

        // Build the final file map
        val finalFiles = mutableMapOf<String, String>()

        // Mode 1: Full write - use provided files
        if (filesParam != null && baseFiles != "previous") {
            filesParam.forEach { fileElement ->
                val obj = fileElement.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: error("each file must have a 'name' field")
                val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: error("each file must have a 'content' field")
                if (name.isBlank()) error("file name cannot be empty")
                finalFiles[name] = content
            }
        }

        // Mode 2: Incremental edit - start from cached files
        if (baseFiles == "previous") {
            val cached = WriteFilesCache.getAll(convId)
            if (cached.isEmpty()) {
                error("No previously cached files found. Use 'files' parameter for the first call.")
            }
            finalFiles.putAll(cached)

            // Apply edits - 如果任何一个 search 找不到，整个工具调用失败
            if (editsParam != null) {
                val editResults = mutableListOf<Map<String, String>>()
                editsParam.forEach { editElement ->
                    val obj = editElement.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'name' field")
                    val search = obj["search"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'search' field")
                    val replace = obj["replace"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'replace' field")

                    val currentContent = finalFiles[name]
                    if (currentContent == null) {
                        // 明确报错：文件不存在
                        error("Edit failed: file '$name' not found in cached files. Available files: ${finalFiles.keys.joinToString(", ")}")
                    } else if (!currentContent.contains(search)) {
                        // 明确报错：search 文本未找到
                        error("Edit failed: search text not found in file '$name'. Make sure your search text is an EXACT verbatim copy of the original. Search text was: ${search.take(100)}${if (search.length > 100) "..." else ""}")
                    } else {
                        finalFiles[name] = currentContent.replace(search, replace)
                        editResults.add(mapOf(
                            "name" to name,
                            "status" to "applied"
                        ))
                    }
                }
            }

            // Also merge any new files from filesParam
            if (filesParam != null) {
                filesParam.forEach { fileElement ->
                    val obj = fileElement.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    finalFiles[name] = content
                }
            }
        }

        if (finalFiles.isEmpty()) {
            error("No files to package. Provide 'files' array or use 'base_files':'previous' with 'edits'.")
        }

        // Update cache with the final file contents (按 conversationId 隔离)
        WriteFilesCache.updateAll(convId, finalFiles)

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("zip_name", zipName)
                    put("mode", if (baseFiles == "previous") "edit" else "full")
                    put("files", buildJsonArray {
                        finalFiles.forEach { (name, content) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("size", content.length)
                            })
                        }
                    })
                    // 包含完整文件内容，UI 下载时直接从这里读取，不再猜数据源
                    put("files_content", buildJsonObject {
                        finalFiles.forEach { (name, content) ->
                            put(name, content)
                        }
                    })
                    put("total_files", finalFiles.size)
                    put("message", "ZIP package '$zipName' is ready with ${finalFiles.size} file(s). A download button will appear for the user.")
                }.toString()
            )
        )
    }
)

// Keep backward compatibility alias
@Deprecated("Use buildWriteFilesTool with conversationId instead", ReplaceWith("buildWriteFilesTool(conversationId)"))
fun buildWriteFilesTool(): Tool = buildWriteFilesTool(null)