/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * 插件运行时信息
 */
data class PluginInfo(
    /**
     * 插件清单
     */
    val manifest: PluginManifest,
    
    /**
     * 插件目录
     */
    val directory: File,
    
    /**
     * 是否启用
     */
    val isEnabled: Boolean,
    
    /**
     * 用户配置值
     */
    val config: Map<String, JsonElement> = emptyMap(),
    
    /**
     * 加载错误信息（如果有）
     */
    val loadError: String? = null,

    val folderId: String? = null
) {
    /**
     * 获取配置值，如果不存在则返回默认值
     */
    fun getConfigValue(key: String): JsonElement? {
        return config[key] ?: manifest.config.find { it.name == key }?.default
    }
    
    /**
     * 检查是否有配置项
     */
    fun hasConfig(): Boolean = manifest.config.isNotEmpty()
    
    /**
     * 获取入口文件的完整路径
     */
    fun getEntryFile(): File = File(directory, manifest.entry)
}