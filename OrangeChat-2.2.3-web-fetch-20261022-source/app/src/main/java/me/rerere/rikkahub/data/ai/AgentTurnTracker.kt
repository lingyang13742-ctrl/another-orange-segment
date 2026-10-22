/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

/**
 * No-op stub of the agent fork's AgentTurnTracker.
 *
 * The upstream agent uses this to record that an automation action (tap / swipe / gesture)
 * happened during the current turn, so the UI can show an "automation happened" indicator and
 * the safety system can rate-limit. orangechat does not port that tracking surface, so
 * [recordAutomationAction] is a cheap no-op. The screen automation tools call it unconditionally;
 * this stub keeps their source identical to upstream.
 */
object AgentTurnTracker {
    fun recordAutomationAction() = Unit
}
