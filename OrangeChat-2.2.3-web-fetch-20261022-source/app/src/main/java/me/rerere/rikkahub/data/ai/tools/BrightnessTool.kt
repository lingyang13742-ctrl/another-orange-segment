/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

fun createGetBrightnessTool(context: Context): Tool = Tool(
    name = "get_brightness",
    description = "Get the current screen brightness level (0-255) and whether auto-brightness is enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {}
        )
    },
    execute = { _ ->
        try {
            val cr = context.contentResolver
            val brightness = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: SettingNotFoundException) {
                Logging.log("BrightnessTool", "SCREEN_BRIGHTNESS not found, using default 128: ${e.message}")
                128
            } catch (e: Exception) {
                Logging.log("BrightnessTool", "Error reading brightness, using default 128: ${e.message}")
                128
            }

            val autoBrightness = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (e: Exception) {
                Logging.log("BrightnessTool", "Error reading brightness mode: ${e.message}")
                false
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("brightness", brightness)
                    put("max_brightness", 255)
                    put("auto_brightness", autoBrightness)
                    put("message", "Brightness: $brightness/255, Auto: $autoBrightness")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("BrightnessTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)

fun createSetBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = "Set the screen brightness (1-255). Requires WRITE_SETTINGS special permission. Will disable auto-brightness first.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("value") {
                    put("type", "integer")
                    put("description", "Brightness value (1-255). Values below 1 will be clamped to 1.")
                }
            },
            required = listOf("value")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val value = params["value"]?.jsonPrimitive?.intOrNull
        if (value == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required parameter 'value' (integer)")
                }.toString()
            ))
        }

        try {
            if (!Settings.System.canWrite(context)) {
                Logging.log("BrightnessTool", "WRITE_SETTINGS not granted")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "WRITE_SETTINGS not granted")
                        put("needs_permission", "WRITE_SETTINGS")
                        put("hint", "Go to Settings > Apps > 橘瓣 > Modify system settings to grant this permission")
                    }.toString()
                ))
            }

            val clampedValue = value.coerceAtLeast(1).coerceAtMost(255)
            val cr = context.contentResolver

            try {
                Settings.System.putInt(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            } catch (e: Exception) {
                Logging.log("BrightnessTool", "Failed to set manual brightness mode: ${e.message}")
            }

            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, clampedValue)

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("brightness", clampedValue)
                    put("auto_brightness", false)
                    put("message", "Brightness set to $clampedValue/255, auto-brightness disabled")
                }.toString()
            ))
        } catch (e: SecurityException) {
            Logging.log("BrightnessTool", "SecurityException: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "WRITE_SETTINGS not granted: ${e.message}")
                    put("needs_permission", "WRITE_SETTINGS")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("BrightnessTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)