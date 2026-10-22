/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
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

fun createShareTool(context: Context): Tool = Tool(
    name = "share",
    description = "Share text or URL via the system share sheet.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text content to share (optional)")
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "URL to share (optional)")
                }
                putJsonObject("subject") {
                    put("type", "string")
                    put("description", "Subject for email-type sharing (optional)")
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val url = params["url"]?.jsonPrimitive?.contentOrNull
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull

        if (text.isNullOrBlank() && url.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "At least one of 'text' or 'url' must be provided")
                }.toString()
            ))
        }

        try {
            val combinedText = listOfNotNull(text, url).joinToString("\n")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, combinedText)
                if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("message", "Share sheet opened")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("ShareTool", "Error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)
