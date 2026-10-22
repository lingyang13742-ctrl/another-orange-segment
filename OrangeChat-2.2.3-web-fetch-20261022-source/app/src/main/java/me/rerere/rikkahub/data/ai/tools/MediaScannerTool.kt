/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

fun createMediaScannerTool(context: Context): Tool = Tool(
    name = "scan_media",
    description = "Notify the media scanner to scan specified file paths so they appear in gallery apps.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("paths") {
                    put("type", "array")
                    put("description", "Array of absolute file paths to scan")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
            },
            required = listOf("paths")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val pathsArray = params["paths"] as? JsonArray

        if (pathsArray == null || pathsArray.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing or empty required parameter 'paths'")
                }.toString()
            ))
        }

        try {
            val paths = pathsArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (paths.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "No valid paths found in 'paths' array")
                    }.toString()
                ))
            }

            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("scanned", paths.size)
                    put("message", "Media scan initiated for ${paths.size} file(s)")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("MediaScannerTool", "Error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)
