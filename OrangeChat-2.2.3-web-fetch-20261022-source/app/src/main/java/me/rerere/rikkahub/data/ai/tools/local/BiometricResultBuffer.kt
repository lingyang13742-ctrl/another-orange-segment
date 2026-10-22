/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 生物识别验证结果. 由 verify_fingerprint 工具注册一个 [CompletableDeferred],
 * 前台 Activity (BiometricPromptActivity) 完成验证后调用 [BiometricResultBuffer.complete] 回填结果,
 * 从而让在后台/IO 协程里挂起等待的工具拿到结果继续执行.
 *
 * 移植自 rikkahub-agent 的 BiometricResultBuffer.kt, 适配 orangechat 的枚举驱动工具架构.
 */
sealed class BiometricResult {
    data class Success(val method: String) : BiometricResult()
    data class Error(val code: String) : BiometricResult()
}

class BiometricResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<BiometricResult>>()

    fun register(requestId: String): CompletableDeferred<BiometricResult> {
        val deferred = CompletableDeferred<BiometricResult>()
        pending[requestId] = deferred
        return deferred
    }

    fun complete(requestId: String, result: BiometricResult) {
        pending.remove(requestId)?.complete(result)
    }
}
