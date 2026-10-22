/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

private const val CHANNEL_ID = "rikkahub_ai_tool"

fun createNotificationPostTool(context: Context): Tool = Tool(
    name = "post_notification",
    description = "Post a system notification to the user. Requires notification permission.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Notification title (required)")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Notification body text (optional)")
                }
                putJsonObject("id") {
                    put("type", "integer")
                    put("description", "Notification ID. If not provided, uses current timestamp in seconds.")
                }
            },
            required = listOf("title")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val body = params["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val id = params["id"]?.jsonPrimitive?.intOrNull ?: (System.currentTimeMillis() / 1000).toInt()

        if (title.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", "Missing required parameter 'title'") }.toString()
            ))
        }

        try {
            val manager = NotificationManagerCompat.from(context)

            if (!manager.areNotificationsEnabled()) {
                Logging.log("NotificationPostTool", "Notifications not enabled")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Notifications are not enabled for this app")
                        put("needs_permission", "POST_NOTIFICATIONS")
                    }.toString()
                ))
            }

            // Create channel if not exists
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                val existingChannel = nm.getNotificationChannel(CHANNEL_ID)
                if (existingChannel == null) {
                    try {
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            "AI Tool Notifications",
                            NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = "Notifications posted by AI tools"
                        }
                        nm.createNotificationChannel(channel)
                    } catch (e: Exception) {
                        Logging.log("NotificationPostTool", "Failed to create notification channel: ${e.message}")
                    }
                }
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(id, notification)

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("notification_id", id)
                    put("title", title)
                    put("message", "Notification posted: $title")
                }.toString()
            ))
        } catch (e: SecurityException) {
            Logging.log("NotificationPostTool", "SecurityException: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "SecurityException: ${e.message}")
                    put("needs_permission", "POST_NOTIFICATIONS")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("NotificationPostTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)
