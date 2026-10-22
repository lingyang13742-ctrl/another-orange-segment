/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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

fun createVibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = "Vibrate the device. Provide either duration_ms (single vibration) or pattern (waveform of off/on milliseconds).",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "Duration of vibration in milliseconds (1-5000). Default 500. Use this for a single vibration.")
                }
                putJsonObject("pattern") {
                    put("type", "array")
                    put("description", "Waveform pattern of alternating off/on durations in ms (e.g. [0,500,200,500]). Max 20 elements. Mutually exclusive with duration_ms.")
                    putJsonObject("items") {
                        put("type", "integer")
                    }
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val durationMs = params["duration_ms"]?.jsonPrimitive?.intOrNull
        val patternArray = params["pattern"] as? JsonArray

        if (durationMs != null && patternArray != null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "provide either duration_ms or pattern, not both")
                }.toString()
            ))
        }

        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator == null) {
                Logging.log("VibrateTool", "Vibrator service unavailable")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Vibrator service unavailable")
                    }.toString()
                ))
            }

            if (!vibrator.hasVibrator()) {
                Logging.log("VibrateTool", "Device has no vibrator")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Device has no vibrator")
                    }.toString()
                ))
            }

            if (patternArray != null) {
                if (patternArray.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "pattern array is empty")
                        }.toString()
                    ))
                }
                if (patternArray.size > 20) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "pattern array exceeds 20 elements")
                        }.toString()
                    ))
                }

                val timings = patternArray.mapNotNull { it.jsonPrimitive.intOrNull?.toLong() }
                if (timings.size != patternArray.size) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "pattern array contains non-integer values")
                        }.toString()
                    ))
                }

                val effect = VibrationEffect.createWaveform(timings.toLongArray(), -1)
                vibrator.vibrate(effect)

                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("mode", "pattern")
                        put("timings", JsonArray(timings.map { JsonPrimitive(it) }))
                        put("message", "Vibration pattern started")
                    }.toString()
                ))
            } else {
                val ms = (durationMs ?: 500).coerceIn(1, 5000)
                val effect = VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)

                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("mode", "oneshot")
                        put("duration_ms", ms)
                        put("message", "Vibrated for ${ms}ms")
                    }.toString()
                ))
            }
        } catch (e: Exception) {
            Logging.log("VibrateTool", "Error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)