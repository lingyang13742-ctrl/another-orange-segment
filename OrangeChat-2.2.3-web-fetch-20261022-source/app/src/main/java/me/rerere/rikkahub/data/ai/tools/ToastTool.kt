/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

fun createToastTool(context: Context): Tool = Tool(
    name = "show_toast",
    description = "Show a brief Toast notification on the screen. Use sparingly only for short feedback. Do not use frequently.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to display in the toast.")
                }
                putJsonObject("long") {
                    put("type", "boolean")
                    put("description", "Whether to use long duration (3.5s) instead of short (2s). Default false.")
                }
            },
            required = listOf("text")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val long = params["long"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        if (text.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required parameter 'text'")
                }.toString()
            ))
        }

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Logging.log("ToastTool", "Failed to show toast on main thread: ${e.message}\n${e.stackTraceToString()}")
                }
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("text", text)
                    put("long", long)
                    put("message", "Toast shown: $text")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("ToastTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)