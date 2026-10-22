/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.activity.BiometricPromptActivity
import java.util.UUID

/**
 * verify_fingerprint 工具: 弹出系统生物识别提示框 (指纹/人脸) 验证用户身份.
 *
 * 工具本身在后台/IO 协程执行, 不能直接显示 BiometricPrompt (需要前台 Activity),
 * 因此通过 [BiometricResultBuffer] 注册一个挂起等待, 再启动一个透明 Activity
 * ([BiometricPromptActivity]) 来承载系统弹窗, 弹窗结束后回填结果.
 *
 * 移植自 rikkahub-agent 的 FingerprintTool.kt, 把宿主 Activity 从 ToolHostActivity
 * 改为 orangechat 专用的 BiometricPromptActivity.
 */
fun fingerprintTool(context: Context, buffer: BiometricResultBuffer): Tool = Tool(
    name = "verify_fingerprint",
    needsApproval = true,
    description = """
        Show the system biometric prompt to verify the user's identity using fingerprint,
        face, or other registered biometric. The user must explicitly authenticate.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Title shown at the top of the biometric prompt")
                })
                put("subtitle", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional subtitle below the title")
                })
                put("allow_device_credential", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, allow falling back to device PIN/pattern/password if no biometric is enrolled or after biometric attempts fail")
                })
            },
            required = listOf("title")
        )
    },
    execute = {
        val params = it.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: error("title is required")
        val subtitle = params["subtitle"]?.jsonPrimitive?.contentOrNull
        val allowDeviceCredential = params["allow_device_credential"]?.jsonPrimitive?.booleanOrNull ?: false

        val authenticators = if (allowDeviceCredential) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        when (BiometricManager.from(context).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> { /* proceed */ }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> return@Tool listOf(
                UIMessagePart.Text(buildJsonObject { put("error", "hardware_unavailable") }.toString())
            )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> return@Tool listOf(
                UIMessagePart.Text(buildJsonObject { put("error", "no_biometrics_enrolled") }.toString())
            )
            else -> return@Tool listOf(
                UIMessagePart.Text(buildJsonObject { put("error", "hardware_unavailable") }.toString())
            )
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)

        val intent = Intent(context, BiometricPromptActivity::class.java).apply {
            putExtra(BiometricPromptActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(BiometricPromptActivity.EXTRA_BIO_TITLE, title)
            putExtra(BiometricPromptActivity.EXTRA_BIO_SUBTITLE, subtitle)
            putExtra(BiometricPromptActivity.EXTRA_BIO_ALLOW_CRED, allowDeviceCredential)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        val result = withTimeoutOrNull(300_000L) { deferred.await() }
        if (result == null) {
            buffer.complete(requestId, BiometricResult.Error("timeout"))
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", "timeout") }.toString()))
        }
        val payload = when (result) {
            is BiometricResult.Success -> buildJsonObject {
                put("success", true)
                put("method", result.method)
            }
            is BiometricResult.Error -> buildJsonObject {
                put("error", result.code)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
