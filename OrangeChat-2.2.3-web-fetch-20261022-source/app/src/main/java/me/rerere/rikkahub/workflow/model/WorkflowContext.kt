/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.model

/**
 * Snapshot of device state at condition-evaluation time. Lazy fields (location, foreground app,
 * recent notifications) are filled only when at least one condition needs them — see
 * [me.rerere.rikkahub.workflow.condition.ContextProvider].
 *
 * Time zone for [time_between] / day-of-week / sunrise-sunset condition evaluation is device-local.
 * Daily-cap rollover also keys off the device-local "yyyy-MM-dd" date.
 */
data class WorkflowContext(
    val nowMs: Long,
    val batteryLevel: Int?,        // 0..100 or null if unknown
    val isCharging: Boolean,
    val wifiSsid: String?,
    val foregroundPackage: String?,
    val screenOn: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val lastChatMs: Long? = null,  // epoch ms of last chat message; null = unknown/no history
)
