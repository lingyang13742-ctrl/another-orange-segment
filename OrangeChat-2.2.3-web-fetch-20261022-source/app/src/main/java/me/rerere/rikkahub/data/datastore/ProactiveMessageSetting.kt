/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
    // 激进模式：每次手机切换应用/开屏锁屏/回桌面都触发AI思考
    val aggressiveModeEnabled: Boolean = false,
    // 激进模式下两次AI思考之间的最小间隔（秒），防抖+限流
    val aggressiveMinIntervalSeconds: Int = 60,
    // 激进模式下，检测到设备事件（切应用/开关屏/回桌面）后，
    // 等待多少秒的防抖时间才真正触发 AI 思考。原来硬编码 30 秒，现在可调节。
    val aggressiveDebounceSeconds: Int = 30,
    // 悬浮球：主动消息到达时以 Telegram 风格悬浮球提醒，点击直接进入聊天页
    val floatingBubbleEnabled: Boolean = false,
)