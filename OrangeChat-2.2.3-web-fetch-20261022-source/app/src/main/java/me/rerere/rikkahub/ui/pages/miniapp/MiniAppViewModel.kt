/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.miniapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MiniApp
import kotlin.uuid.Uuid

class MiniAppViewModel(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun add(miniApp: MiniApp) {
        update(settings.value.miniApps + miniApp.copy(order = settings.value.miniApps.size))
    }

    fun update(miniApp: MiniApp) {
        update(
            settings.value.miniApps.map { if (it.id == miniApp.id) miniApp else it }
        )
    }

    fun delete(id: Uuid) {
        update(settings.value.miniApps.filterNot { it.id == id })
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        val list = settings.value.miniApps.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            update(list.mapIndexed { index, miniApp -> miniApp.copy(order = index) })
        }
    }

    private fun update(miniApps: List<MiniApp>) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(miniApps = miniApps)
            }
        }
    }
}
