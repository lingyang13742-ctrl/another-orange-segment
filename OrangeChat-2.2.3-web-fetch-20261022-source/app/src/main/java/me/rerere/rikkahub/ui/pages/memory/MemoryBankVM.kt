/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.MemoryBankEntity
import me.rerere.rikkahub.data.service.MemoryBankService

class MemoryBankVM(
    private val memoryBankService: MemoryBankService,
) : ViewModel() {

    private val _memories = MutableStateFlow<List<MemoryBankEntity>>(emptyList())
    val memories: StateFlow<List<MemoryBankEntity>> = _memories.asStateFlow()

    private val _todayPhaseSummaries = MutableStateFlow<List<MemoryBankEntity>>(emptyList())
    val todayPhaseSummaries: StateFlow<List<MemoryBankEntity>> = _todayPhaseSummaries.asStateFlow()

    private val _dailySummaries = MutableStateFlow<List<MemoryBankEntity>>(emptyList())
    val dailySummaries: StateFlow<List<MemoryBankEntity>> = _dailySummaries.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedType = MutableStateFlow("")
    val selectedType: StateFlow<String> = _selectedType.asStateFlow()

    private val _selectedAssistantId = MutableStateFlow<String?>(null)
    val selectedAssistantId: StateFlow<String?> = _selectedAssistantId.asStateFlow()

    private val _assistantIds = MutableStateFlow<List<String>>(emptyList())
    val assistantIds: StateFlow<List<String>> = _assistantIds.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _stats = MutableStateFlow(MemoryBankService.MemoryStats())
    val stats: StateFlow<MemoryBankService.MemoryStats> = _stats.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            _loading.value = true
            try {
                // Load assistant IDs for filter
                _assistantIds.value = memoryBankService.getAssistantIds()

                // Update stats using COUNT queries (fast, no full load)
                _stats.value = memoryBankService.getStats(_selectedAssistantId.value)

                // Load display data based on type filter
                val selectedType = _selectedType.value
                val assistantId = _selectedAssistantId.value

                if (selectedType.isEmpty()) {
                    // No type filter: show today's phase summaries + daily summaries (diaries)
                    _todayPhaseSummaries.value = memoryBankService.getTodayPhaseSummaries(assistantId)
                    _dailySummaries.value = memoryBankService.getDailySummaries(assistantId)
                    _memories.value = emptyList()
                } else {
                    // Type filter active: show matching memories in list
                    _memories.value = memoryBankService.searchMemories(
                        keyword = _searchQuery.value,
                        type = selectedType,
                        limit = 100,
                        assistantId = assistantId,
                    )
                    _todayPhaseSummaries.value = emptyList()
                    _dailySummaries.value = emptyList()
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        loadMemories()
    }

    fun setSelectedType(type: String) {
        _selectedType.value = type
        loadMemories()
    }

    fun setSelectedAssistantId(assistantId: String?) {
        _selectedAssistantId.value = assistantId
        loadMemories()
    }

    fun deleteMemory(id: Int) {
        viewModelScope.launch {
            memoryBankService.deleteMemory(id)
            loadMemories()
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            _loading.value = true
            try {
                memoryBankService.rebuildIndex()
                loadMemories()
            } finally {
                _loading.value = false
            }
        }
    }

    fun processPendingVectors() {
        viewModelScope.launch {
            _loading.value = true
            try {
                memoryBankService.processPendingVectors()
                loadMemories()
            } finally {
                _loading.value = false
            }
        }
    }
}