/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import me.rerere.rikkahub.plugin.model.PluginInfo

/**
 * 已加载的插件
 */
data class LoadedPlugin(
    /**
     * 插件信息
     */
    val info: PluginInfo,
    
    /**
     * JS沙箱
     */
    val sandbox: PluginSandbox
) {
    /**
     * 获取插件ID
     */
    val id: String get() = info.manifest.id
    
    /**
     * 获取插件名称
     */
    val name: String get() = info.manifest.name
    
    /**
     * 检查是否有某个工具
     */
    fun hasTool(toolName: String): Boolean {
        return info.manifest.tools.any { it.name == toolName }
    }
    
    /**
     * 获取工具定义
     */
    fun getTool(toolName: String) = info.manifest.tools.find { it.name == toolName }
}