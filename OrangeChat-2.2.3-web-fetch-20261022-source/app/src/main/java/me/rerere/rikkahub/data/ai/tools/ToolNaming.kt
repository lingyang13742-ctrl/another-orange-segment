/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid

/**
 * 工具命名工具
 *
 * 给来自不同 MCP server / 插件 的同名工具加上来源命名空间,
 * 防止发给模型 Provider 的 tools 数组里出现重复 name(会触发 400 "Tool names must be unique")。
 *
 * 为什么用 8 位短哈希而不是完整 UUID/pluginId:
 * - Anthropic / OpenAI 兼容接口对 tool name 有 64 字符上限
 * - 完整 UUID(36 字符) + 分隔符会让前缀吃掉 43+ 字符,原始工具名只剩 21 字符,容易超限
 * - pluginId(反向域名,如 com.orangechat.plugin.marketplace)更长且含点号(非法字符)
 * - 8 位十六进制(只含 0-9a-f):前缀固定 13 字符,给原始工具名留 51 字符,且不引入非法字符
 */
object ToolNaming {
    private const val MCP_PREFIX = "mcp_"
    private const val PLUGIN_PREFIX = "plg_"
    private const val SHORT_KEY_LENGTH = 8
    // 前缀(4) + 短键(8) + 分隔符(1) = 13
    private const val HEADER_LENGTH = MCP_PREFIX.length + SHORT_KEY_LENGTH + 1

    /**
     * 构造 MCP 工具最终名字
     *
     * 格式: mcp_ + 8位十六进制(serverId.toString() 的 hashCode) + _ + 原始工具名
     *
     * @param serverId MCP server 的唯一 id
     * @param toolName MCP 工具原始名字
     */
    fun buildMcpToolName(serverId: Uuid, toolName: String): String {
        val shortKey = String.format("%08x", serverId.toString().hashCode())
        return "$MCP_PREFIX${shortKey}_$toolName"
    }

    /**
     * 构造插件工具最终名字
     *
     * 格式: plg_ + 8位十六进制(pluginId 的 hashCode) + _ + 原始工具名
     *
     * @param pluginId 插件 id(反向域名格式,如 com.orangechat.plugin.marketplace)
     * @param toolName 插件工具原始名字
     */
    fun buildPluginToolName(pluginId: String, toolName: String): String {
        val shortKey = String.format("%08x", pluginId.hashCode())
        return "$PLUGIN_PREFIX${shortKey}_$toolName"
    }

    /**
     * 还原显示名
     *
     * 若以 mcp_ 或 plg_ 开头,按固定长度(前缀4 + 短键8 + 分隔符1 = 13)截掉头部,
     * 返回剩余的原始工具名;不是这两种前缀的(本地/系统/技能工具等)原样返回。
     *
     * @param name 工具最终名字(可能带命名空间前缀)
     * @return 给用户看的干净工具名
     */
    fun toDisplayName(name: String): String {
        return when {
            name.length > HEADER_LENGTH && name.startsWith(MCP_PREFIX) ->
                name.substring(HEADER_LENGTH)
            name.length > HEADER_LENGTH && name.startsWith(PLUGIN_PREFIX) ->
                name.substring(HEADER_LENGTH)
            else -> name
        }
    }
}