/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}

val LocalDisplaySettings = compositionLocalOf<DisplaySetting> {
    error("LocalDisplaySettings not provided")
}

val LocalProviders = compositionLocalOf<List<ProviderSetting>> {
    error("LocalProviders not provided")
}

val LocalMcpServers = compositionLocalOf<List<McpServerConfig>> {
    error("LocalMcpServers not provided")
}

val LocalQuickMessages = compositionLocalOf<List<QuickMessage>> {
    error("LocalQuickMessages not provided")
}

val LocalCurrentAssistant = compositionLocalOf<Assistant> {
    error("LocalCurrentAssistant not provided")
}

val LocalCurrentChatModel = compositionLocalOf<Model?> {
    error("LocalCurrentChatModel not provided")
}
