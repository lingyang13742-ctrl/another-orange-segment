/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 插件声明式 UI 定义
 * 插件在 manifest.json 中通过 "ui" 字段声明 UI 组件，
 * App 渲染为原生 Compose Material 3 UI
 */
@Serializable
data class PluginUIDeclaration(
    /**
     * 页面标题
     */
    val title: String = "",

    /**
     * 页面组件列表（从上到下排列）
     */
    val components: List<PluginUIComponent> = emptyList(),

    /**
     * 数据查询定义
     * key 为查询名称，value 为查询配置
     * 组件通过 queryName 引用查询结果
     */
    val queries: Map<String, PluginUIQuery> = emptyMap(),

    /**
     * 操作定义
     * key 为操作名称，value 为操作配置
     * 按钮等组件通过 actionName 引用操作
     */
    val actions: Map<String, PluginUIAction> = emptyMap()
)

/**
 * 数据查询定义
 */
@Serializable
data class PluginUIQuery(
    /**
     * 查询类型
     * - "dataStore_list": 列出 PluginDataStore 中匹配前缀的所有 key
     * - "dataStore_get": 获取指定 key 的数据
     * - "dataStore_search": 搜索匹配的数据
     */
    val type: String,

    /**
     * 查询参数
     * - dataStore_list: { "prefix": "emoji_" }
     * - dataStore_get: { "key": "some_key" }
     * - dataStore_search: { "prefix": "emoji_", "keyword": "xxx", "searchFields": ["name","description"] }
     */
    val params: JsonObject = JsonObject(emptyMap()),

    /**
     * 是否自动刷新（当数据变更时重新查询）
     */
    val autoRefresh: Boolean = true
)

/**
 * 操作定义
 */
@Serializable
data class PluginUIAction(
    /**
     * 操作类型
     * - "dataStore_set": 写入数据到 PluginDataStore
     * - "dataStore_delete": 删除 PluginDataStore 中的数据
     * - "tool_call": 调用插件工具
     * - "file_write": 写入文件
     * - "file_delete": 删除文件
     * - "pick_image": 选择图片
     */
    val type: String,

    /**
     * 操作参数模板
     * 支持 ${field.xxx} 引用表单字段值
     * 支持 ${query.xxx.key} 引用查询结果
     */
    val params: JsonObject = JsonObject(emptyMap()),

    /**
     * 操作成功后的行为
     * - "refresh": 刷新所有查询
     * - "refresh_queries": 刷新指定查询 ["query1", "query2"]
     * - "close_dialog": 关闭当前弹窗
     * - "navigate_back": 返回上一页
     * - "none": 无操作
     */
    val onSuccess: String = "refresh",

    /**
     * 成功后要刷新的查询名称列表
     */
    val refreshQueries: List<String> = emptyList(),

    /**
     * 确认对话框配置
     */
    val confirmDialog: PluginUIConfirmDialog? = null
)

/**
 * 确认对话框
 */
@Serializable
data class PluginUIConfirmDialog(
    val title: String = "确认",
    val message: String = "确定要执行此操作吗？"
)

/**
 * UI 组件基类
 */
@Serializable
data class PluginUIComponent(
    /**
     * 组件类型
     * - "stats": 统计卡片行
     * - "search_bar": 搜索栏
     * - "filter_bar": 过滤标签栏
     * - "card_grid": 卡片网格
     * - "card_list": 卡片列表
     * - "button_row": 按钮行
     * - "dialog_form": 弹窗表单（包含触发按钮）
     * - "section": 分组区域
     * - "text": 文本
     * - "empty_state": 空状态提示
     */
    val type: String,

    /**
     * 组件参数，不同类型有不同参数
     */
    val props: JsonObject = JsonObject(emptyMap())
)

/**
 * Stats 组件参数
 * { "items": [{ "label": "总表情", "queryName": "stats", "field": "total" }] }
 * 或直接指定值: { "items": [{ "label": "总数", "value": "0" }] }
 */

/**
 * SearchBar 组件参数
 * { "placeholder": "搜索...", "filterQueryName": "search" }
 * 搜索输入会作为过滤条件应用到指定的查询
 */

/**
 * FilterBar 组件参数
 * { "queryName": "list", "filterField": "category", "allLabel": "全部" }
 * 根据查询结果中指定字段的值生成过滤标签
 */

/**
 * CardGrid 组件参数
 * {
 *   "queryName": "list",
 *   "columns": 2,
 *   "imageField": "imageUrl",
 *   "titleField": "name",
 *   "subtitleField": "description",
 *   "tagField": "category",
 *   "deleteAction": "delete",
 *   "deleteKeyField": "id",
 *   "onClickAction": "detail",
 *   "onClickKeyField": "id"
 * }
 */

/**
 * CardList 组件参数
 * {
 *   "queryName": "list",
 *   "titleField": "name",
 *   "subtitleField": "description",
 *   "tagField": "category",
 *   "deleteAction": "delete",
 *   "deleteKeyField": "id"
 * }
 */

/**
 * ButtonRow 组件参数
 * { "buttons": [{ "label": "添加", "icon": "+", "action": "add", "variant": "primary" }] }
 * variant: "primary", "secondary", "text"
 */

/**
 * DialogForm 组件参数
 * {
 *   "triggerLabel": "+ 添加",
 *   "triggerVariant": "primary",
 *   "title": "添加表情",
 *   "fields": [
 *     { "name": "name", "label": "名称", "type": "string", "required": true, "placeholder": "输入名称" },
 *     { "name": "description", "label": "描述", "type": "string", "multiline": true },
 *     { "name": "imageUrl", "label": "图片URL", "type": "string", "placeholder": "https://..." },
 *     { "name": "imageFile", "label": "上传图片", "type": "image" },
 *     { "name": "category", "label": "分类", "type": "string", "default": "general" }
 *   ],
 *   "submitAction": "save",
 *   "submitLabel": "保存"
 * }
 * field.type: "string", "integer", "boolean", "select", "image", "multiline"
 */

/**
 * Section 组件参数
 * { "title": "基本设置", "components": [...] }
 */

/**
 * Text 组件参数
 * { "content": "说明文字", "style": "body" }
 * style: "headline", "title", "body", "caption"
 */