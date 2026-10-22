/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import me.rerere.rikkahub.data.ai.tools.local.BiometricResult
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer

/**
 * 透明 Activity, 专门用来承载系统 [BiometricPrompt] 弹窗.
 *
 * verify_fingerprint 工具运行在后台/IO 协程, 无法直接弹窗; 它先在 [buffer] 里注册一个挂起
 * 等待, 再启动本 Activity. 本 Activity 显示系统指纹/人脸弹窗, 验证结束后把结果回填到
 * [buffer], 工具随即被唤醒.
 *
 * 为什么用 [FragmentActivity] 而不是 AppCompatActivity:
 * BiometricPrompt 只需要一个 FragmentActivity 来托管内部的 DialogFragment, 不需要
 * AppCompatActivity 的功能. 关键区别是 AppCompatActivity 在 ensureSubDecor 时会强制
 * 要求主题是 Theme.AppCompat (或后代), 而 AndroidManifest 里给本 Activity 配的是系统的
 * @android:style/Theme.Translucent.NoTitleBar (非 AppCompat 主题) —— 用 AppCompatActivity
 * 会在 onPostCreate 崩溃. FragmentActivity 不做主题检查, 系统半透明主题可直接用.
 *
 * [buffer] 用单例 (companion object) 而不是 DI 注入, 因为 [me.rerere.rikkahub.data.ai.tools.SystemTools]
 * 是在 ChatService 里按需 new 出来的 (非单例), 工具实例和 Activity 实例无法共享同一个 buffer 引用,
 * 用全局单例最简单可靠.
 *
 * 移植自 rikkahub-agent ToolHostActivity 的生物识别分支.
 */
class BiometricPromptActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: run {
            finish(); return
        }
        val title = intent.getStringExtra(EXTRA_BIO_TITLE) ?: "Authenticate"
        val subtitle = intent.getStringExtra(EXTRA_BIO_SUBTITLE)
        val allowDeviceCredential = intent.getBooleanExtra(EXTRA_BIO_ALLOW_CRED, false)

        val authenticators = if (allowDeviceCredential) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        // 硬件/指纹不可用时直接报错, 不再弹空窗口
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                buffer.complete(requestId, BiometricResult.Error("hardware_unavailable"))
                finish(); return
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                buffer.complete(requestId, BiometricResult.Error("no_biometrics_enrolled"))
                finish(); return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val method = when (result.authenticationType) {
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "device_credential"
                        else -> "biometric"
                    }
                    buffer.complete(requestId, BiometricResult.Success(method))
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val mapped = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> "user_cancelled"
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> "no_biometrics_enrolled"
                        else -> errString.toString()
                    }
                    buffer.complete(requestId, BiometricResult.Error(mapped))
                    finish()
                }

                override fun onAuthenticationFailed() {
                    // 单次验证失败但弹窗仍开着, 不结束 — 用户可重试
                }
            })

        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
        if (subtitle != null) infoBuilder.setSubtitle(subtitle)
        // 未启用 PIN 回退时必须提供否定按钮文案; 启用 PIN 回退时系统用设备凭据流程, 不能再设否定按钮
        if (!allowDeviceCredential) infoBuilder.setNegativeButtonText("Cancel")

        prompt.authenticate(infoBuilder.build())
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // request_id 由调用方 (工具) 通过 Intent 传入; 回退时按用户取消处理
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId != null) {
            buffer.complete(requestId, BiometricResult.Error("user_cancelled"))
        }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_BIO_TITLE = "bio_title"
        const val EXTRA_BIO_SUBTITLE = "bio_subtitle"
        const val EXTRA_BIO_ALLOW_CRED = "bio_allow_cred"

        /**
         * 全局共享的缓冲区. 工具 register, 本 Activity complete.
         */
        val buffer: BiometricResultBuffer = BiometricResultBuffer()
    }
}
