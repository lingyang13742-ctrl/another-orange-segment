/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createAlarmTool(context: Context): Tool = Tool(
    name = "set_alarm",
    description = "Set an alarm on the user's device through the system clock app.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "Hour in 24-hour format (0-23).")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "Minute (0-59).")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "A label/name for the alarm (optional)")
                }
            },
            required = listOf("hour", "minute")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val hour = params["hour"]?.jsonPrimitive?.content?.toIntOrNull()
        val minute = params["minute"]?.jsonPrimitive?.content?.toIntOrNull()
        val label = params["label"]?.jsonPrimitive?.content ?: ""

        if (hour == null || minute == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required parameters: hour and minute")
                }.toString()
            ))
        }

        if (hour !in 0..23 || minute !in 0..59) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Invalid time: hour must be 0-23, minute must be 0-59")
                }.toString()
            ))
        }

        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val activities = context.packageManager.queryIntentActivities(intent, 0)
            if (activities.isNullOrEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "No clock app found that supports setting alarms")
                    }.toString()
                ))
            }

            context.startActivity(intent)

            val displayHour = String.format("%02d", hour)
            val displayMinute = String.format("%02d", minute)

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("alarm_time", "$displayHour:$displayMinute")
                    put("label", label)
                    put("message", "Alarm set for $displayHour:$displayMinute${if (label.isNotBlank()) " ($label)" else ""}")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Failed to set alarm")
                }.toString()
            ))
        }
    }
)


fun createTimerTool(context: Context): Tool = Tool(
    name = "set_timer",
    description = "Set a countdown timer on the user's device through the system clock app. Useful for reminders like 'remind me in 10 minutes' or 'set a 5-minute timer'.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Timer duration in seconds. For example, 300 for 5 minutes, 600 for 10 minutes. Must be positive.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "A label/name for the timer (optional)")
                }
            },
            required = listOf("seconds")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val seconds = params["seconds"]?.jsonPrimitive?.content?.toIntOrNull()
        val label = params["label"]?.jsonPrimitive?.content ?: ""

        if (seconds == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required parameter: seconds")
                }.toString()
            ))
        }

        if (seconds <= 0) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Timer duration must be positive (seconds > 0)")
                }.toString()
            ))
        }

        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val activities = context.packageManager.queryIntentActivities(intent, 0)
            if (activities.isNullOrEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "No clock app found that supports setting timers")
                    }.toString()
                ))
            }

            context.startActivity(intent)

            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            val displayTime = if (minutes > 0 && remainingSeconds > 0) {
                "${minutes}m ${remainingSeconds}s"
            } else if (minutes > 0) {
                "${minutes}m"
            } else {
                "${remainingSeconds}s"
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("timer_seconds", seconds)
                    put("timer_display", displayTime)
                    put("label", label)
                    put("message", "Timer set for $displayTime" + if (label.isNotBlank()) " ($label)" else "")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Failed to set timer")
                }.toString()
            ))
        }
    }
)
