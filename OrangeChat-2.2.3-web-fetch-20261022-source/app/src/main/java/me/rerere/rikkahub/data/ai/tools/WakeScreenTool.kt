/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
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

@Suppress("DEPRECATION")
fun createWakeScreenTool(context: Context): Tool = Tool(
    name = "wake_screen",
    description = "Wake up the screen if it is off. Holds a wake lock for a specified duration.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("hold_ms") {
                    put("type", "integer")
                    put("description", "How long to hold the wake lock in milliseconds (500-30000). Default: 3000")
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val holdMs = (params["hold_ms"]?.jsonPrimitive?.intOrNull ?: 3000).coerceIn(500, 30000)

        try {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm == null) {
                Logging.log("WakeScreenTool", "PowerManager unavailable")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "PowerManager unavailable") }.toString()
                ))
            }

            if (pm.isInteractive) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("was_off", false)
                        put("message", "Screen was already on")
                    }.toString()
                ))
            }

            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "Rikkahub:WakeScreenTool"
            )
            wakeLock.acquire(holdMs.toLong())

            val km = context.getSystemService(KeyguardManager::class.java)
            val keyguardLocked = km?.isKeyguardLocked == true
            val keyguardSecure = km?.isDeviceSecure == true

            val result = buildJsonObject {
                put("success", true)
                put("was_off", true)
                put("hold_ms", holdMs)
                put("keyguard_locked", keyguardLocked)
                put("keyguard_secure", keyguardSecure)
                if (keyguardLocked && keyguardSecure) {
                    put("warn", "Screen is now lit but keyguard has password/biometric protection. User needs to unlock manually.")
                }
            }

            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) {
            Logging.log("WakeScreenTool", "Error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)
