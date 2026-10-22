/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.di

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.plugin.ui.PluginViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 插件模块依赖注入
 */
val pluginModule = module {
    // Scanner
    single { PluginScanner(androidContext(), get()) }

    // Repository
    single { PluginRepository(androidContext()) }

    // Loader - 需要OkHttpClient、MemoryBankService和SettingsStore
    single { PluginLoader(androidContext(), get<OkHttpClient>(), get<MemoryBankService>(), get<SettingsStore>()) }

    // Manager
    single { PluginManager(androidContext(), get(), get(), get(), get()) }

    // Provider - 需要PluginManager来确保插件已初始化
    single { PluginToolProvider(get(), get()) }

    // ViewModel
    viewModel { PluginViewModel(get()) }
}
