/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * No-op stub of the agent fork's interactive tool streamer.
 *
 * In the upstream agent, this streams progress text back to the user during headless runs
 * (Telegram / cron / sub-agent) so long-running tool chains show live status. orangechat does
 * not port that streaming surface, so [NoOp] discards every [streamIfHeadless] call. The screen
 * automation tools call it unconditionally - this stub keeps their source identical to upstream
 * while making the calls cheap no-ops.
 */
interface InteractiveToolStreamer {
    fun streamIfHeadless(invocationContext: ToolInvocationContext, text: String)

    object NoOp : InteractiveToolStreamer {
        override fun streamIfHeadless(invocationContext: ToolInvocationContext, text: String) = Unit
    }
}
