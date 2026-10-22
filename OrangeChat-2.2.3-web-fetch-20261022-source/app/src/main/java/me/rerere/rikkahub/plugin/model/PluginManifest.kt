/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件清单文件 (manifest.json) 对应的数据类
 */
@Serializable
data class PluginManifest(
    /**
     * 插件唯一标识，建议使用反向域名格式，如 com.example.plugin.name
     */
    val id: String,
    
    /**
     * 插件显示名称
     */
    val name: String,
    
    /**
     * 插件描述
     */
    val description: String,
    
    /**
     * 插件版本，格式如 1.0.0
     */
    val version: String,
    
    /**
     * 作者名称
     */
    val author: String,
    
    /**
     * 插件图标，可以是 emoji 或 URL
     */
    val icon: String,
    
    /**
     * 入口文件路径，相对于插件目录
     */
    val entry: String,
    
    /**
     * 插件提供的工具列表
     */
    val tools: List<PluginToolDefinition> = emptyList(),
    
    /**
     * 插件配置字段定义
     */
    val config: List<PluginConfigField> = emptyList(),

    /**
     * 内置页面标识，用于插件提供专属管理页面
     * 支持: "memory_bank" - 记忆库管理页面
     * 当 customPageWebView 非空时，此字段忽略
     */
    val customPage: String? = null,

    /**
     * WebView 自定义页面配置
     * 当设置此字段时，插件详情页会显示"管理页面"按钮，
     * 点击后打开 WebView 加载插件目录下的 HTML 文件
     */
    val customPageWebView: PluginWebViewPageConfig? = null,

    /**
     * 插件提示词模板，当 inject_as_prompt 配置开启时，
     * 此模板内容会被注入到系统提示词中，
     * 让 AI 知晓可以使用该插件的工具
     */
    val promptTemplate: String? = null,

    /**
     * 声明式 UI 定义
     * 插件通过此字段声明 UI 组件（列表、表单、按钮等），
     * App 渲染为原生 Compose Material 3 UI
     * 当此字段非空时，优先于 customPageWebView 渲染
     */
    val ui: PluginUIDeclaration? = null,

    /**
     * 插件事件钩子声明
     * 插件通过此字段声明需要监听的事件和对应的处理函数
     * 支持的事件: message_sent, message_received, daily_cron
     */
    val hooks: List<PluginHook> = emptyList(),

    /**
     * 插件权限声明
     * 支持的权限:
     * - "ai_chat": 允许插件调用 AI 生成文本（Bridge.callAI）
     * - "disable_native_selection": 禁用 WebView 原生长按选择菜单，由 JS 自行处理选区
     */
    val permissions: List<String> = emptyList(),

    /**
     * 插件事件钩子配置（结构化版本）
     * key 为事件名，如 "onPageTurn", "onAnnotationAdded"
     * value 为该事件的配置，支持 call_js_function 或 call_ai 两种 action
     */
    val hookConfigs: Map<String, PluginHookConfig> = emptyMap(),

    /**
     * 插件允许访问的网络域名白名单。
     * 插件通过 JS fetch() 发起的 HTTP 请求，目标域名必须在此列表中才会被放行。
     * 空列表表示禁止所有网络请求（仅允许本地 dataStore 和内存库交互）。
     * 特殊值 "*" 表示允许所有域名（不推荐，仅用于开发调试）。
     */
    val allowedHosts: List<String> = emptyList()
)

/**
 * WebView 自定义页面配置
 */
@Serializable
data class PluginWebViewPageConfig(
    /**
     * HTML 入口文件路径，相对于插件目录
     * 例如: "ui/index.html"
     */
    val entry: String
)

/**
 * 插件工具定义
 */
@Serializable
data class PluginToolDefinition(
    /**
     * 工具名称，将作为函数名导出
     */
    val name: String,
    
    /**
     * 工具描述，用于AI理解工具用途
     */
    val description: String,
    
    /**
     * 工具参数列表
     */
    val parameters: List<PluginToolParameter> = emptyList()
)

/**
 * 插件工具参数定义
 */
@Serializable
data class PluginToolParameter(
    /**
     * 参数名称
     */
    val name: String,
    
    /**
     * 参数类型：string, number, integer, boolean, object, array
     */
    val type: String,
    
    /**
     * 参数描述
     */
    val description: String? = null,
    
    /**
     * 是否必填
     */
    val required: Boolean = false
)

/**
 * 插件配置字段定义
 */
@Serializable
data class PluginConfigField(
    /**
     * 配置项键名
     */
    val name: String,
    
    /**
     * 配置项类型：string, number, boolean, select, password, model
     * model 类型会显示模型选择器，保存选中模型的 ID
     */
    val type: String,
    
    /**
     * 显示标签
     */
    val label: String,
    
    /**
     * 配置项描述
     */
    val description: String? = null,
    
    /**
     * 是否必填
     */
    val required: Boolean = false,
    
    /**
     * 默认值
     */
    val default: JsonElement? = null,
    
    /**
     * 选项列表（用于select类型）
     */
    val options: List<ConfigOption>? = null,
    
    /**
     * 输入提示
     */
    val placeholder: String? = null
)

/**
 * 配置选项
 */
@Serializable
data class ConfigOption(
    /**
     * 选项值
     */
    val value: String,
    
    /**
     * 显示标签
     */
    val label: String
)

/**
 * 插件事件钩子
 */
@Serializable
data class PluginHook(
    /**
     * 事件名称
     * 支持: "message_sent", "message_received", "daily_cron"
     */
    val event: String,

    /**
     * 处理函数名称（插件导出的函数名）
     */
    val handler: String,

    /**
     * Cron 表达式（仅 daily_cron 事件使用）
     * 例如: "0 3 * * *" 表示每天凌晨3点
     */
    val schedule: String? = null
)

/**
 * 插件事件钩子配置（结构化版本）
 * 用于 WebView 插件的页面事件（如翻页、添加批注等）
 */
@Serializable
data class PluginHookConfig(
    /**
     * 动作类型
     * - "call_js_function": 调用插件 JS 导出的函数
     * - "call_ai": 调用 AI 生成文本
     */
    val action: String,

    /**
     * JS 函数名（当 action = "call_js_function" 时必填）
     */
    val function: String? = null,

    /**
     * 是否自动触发（当事件发生时自动执行，无需用户手动触发）
     */
    val autoTrigger: Boolean = false,

    /**
     * AI 提示模板（当 action = "call_ai" 时必填）
     * 支持变量替换: {book}, {chapter}, {page}, {quote}, {note}
     * 例如: "用户在《{book}》第{chapter}章第{page}页写了一条批注：\n引用：「{quote}」\n心得：{note}"
     */
    val promptTemplate: String? = null,

    /**
     * 额外参数
     */
    val params: Map<String, String> = emptyMap()
)
