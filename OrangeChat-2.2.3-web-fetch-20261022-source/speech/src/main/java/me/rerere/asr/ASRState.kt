/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr

enum class ASRStatus {
    Idle,
    Connecting,
    Listening,
    Stopping,
    Error
}

data class ASRState(
    val status: ASRStatus = ASRStatus.Idle,
    val isAvailable: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val audioFilePath: String? = null,
    val durationMs: Long = 0L,
) {
    val isRecording: Boolean
        get() = status == ASRStatus.Connecting || status == ASRStatus.Listening || status == ASRStatus.Stopping
}
