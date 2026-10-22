/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.uuid.Uuid
 
/**
 * 插件加载器
 * 负责加载和管理插件生命周期
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null,
    private val settingsStore: SettingsStore? = null
) {
 
    companion object {
        private const val TAG = "PluginLoader"
        /**
         * 单个插件 hook 执行的协程级超时。
         *
         * 设为略大于 nativeFetch 的最长超时上限 (15s), 避免协程层先于网络层触发,
         * 误把"请求还在正常进行"报成"超时"。
         *
         * 重要限制 (如实说明): 这层 withTimeoutOrNull 只能让"等待这次 hook 调用结果"
         * 提前放弃, 无法真正打断 QuickJS 引擎内部正在执行的同步 JS 代码 (比如
         * nativeFetch 内部还在跑的 OkHttp 请求)。因为 callEvent 全程跑在单线程
         * pluginDispatcher 上, 即使外层已放弃等待, 这个单线程仍会被卡住的那次调用
         * 占用, 直到底层请求真正结束 (最长 15s)。期间排在后面的其它 hook 调用、
         * 以及 callTool (也走这个 dispatcher) 都要继续排队。这是 QuickJS 单线程
         * 模型的本质限制, 核心阻塞问题已由 ChatService 改为 fire-and-forget 解决,
         * 这一层只是给"事件处理"一个明确失败信号和日志, 不是彻底防死锁。
         */
        private const val HOOK_TIMEOUT_MS = 16_500L
    }
 
    // 单线程调度器，确保所有 QuickJS 操作在同一线程执行
    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
 
    // 已加载的插件缓存
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()
 
    /**
     * 加载插件
     */
    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }
 
            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }
 
            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }
 
            // 为此插件创建独立的 PluginDataStore，并注入沙箱
            val dataStore = PluginDataStore(context, pluginInfo.manifest.id)

            val sandbox = PluginSandbox(context, okHttpClient, memoryBankService, dataStore)
            sandbox.allowedHosts = pluginInfo.manifest.allowedHosts
            sandbox.initialize()
 
            val resolvedConfig = resolveModelConfig(pluginInfo)
            sandbox.injectConfig(resolvedConfig)
 
            sandbox.evaluateFile(entryFile)
 
            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = sandbox
            )
 
            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")
 
            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }
 
            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin
            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            Result.failure(e)
        }
    }
 
    private fun doUnloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)?.let { plugin ->
            plugin.sandbox.destroy()
            Log.d(TAG, "Unloaded plugin: $pluginId")
        }
    }
 
    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }
 
    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = loadPlugin(pluginInfo)
 
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]
 
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()
 
    fun getEnabledPlugins(): List<LoadedPlugin> = loadedPlugins.values.filter { it.info.isEnabled }
 
    /**
     * 调用插件工具
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))
 
                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }
 
                val result = plugin.sandbox.callFunction(toolName, params)
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool=$toolName in plugin=$pluginId", e)
                Result.failure(e)
            }
        }
    }
 
    /**
     * 触发插件事件
     *
     * 对订阅了该事件的每个插件 hook, 在单线程 pluginDispatcher 上串行执行。
     * 每次调用包一层 [HOOK_TIMEOUT_MS] 超时, 超时后记录警告并跳过, 继续处理
     * 同批次其它插件的 hook, 不让单个插件拖累整批。
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            for (plugin in loadedPlugins.values) {
                if (!plugin.info.isEnabled) continue
                val matchingHooks = plugin.info.manifest.hooks.filter { it.event == event }
                for (hook in matchingHooks) {
                    try {
                        if (!plugin.sandbox.hasFunction(hook.handler)) {
                            Log.w(TAG, "Hook handler '${hook.handler}' not found in plugin ${plugin.id}")
                            continue
                        }
                        val completed = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                            plugin.sandbox.callFunction(hook.handler, params)
                        }
                        if (completed == null) {
                            Log.w(
                                TAG,
                                "Plugin hook timed out after ${HOOK_TIMEOUT_MS}ms: " +
                                    "plugin=${plugin.id}, handler='${hook.handler}', event='$event'"
                            )
                        } else {
                            Log.d(TAG, "Event '$event' handled by plugin ${plugin.id}.${hook.handler}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to handle event='$event' in plugin=${plugin.id}.${hook.handler}", e)
                    }
                }
            }
        }
    }
 
    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }
 
    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> {
        val config = pluginInfo.config.toMutableMap()
        val store = settingsStore ?: return config
        val settings = store.settingsFlow.value
 
        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach
 
                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Claude -> provider.baseUrl
                    }
                    val apiKey = when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey
                        is ProviderSetting.Google -> provider.apiKey
                        is ProviderSetting.Claude -> provider.apiKey
                    }
 
                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}, baseUrl=$baseUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
        return config
    }
 
    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }
}