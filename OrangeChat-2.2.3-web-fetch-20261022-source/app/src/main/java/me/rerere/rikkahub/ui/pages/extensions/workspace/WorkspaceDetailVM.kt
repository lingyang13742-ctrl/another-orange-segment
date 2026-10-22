/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "WorkspaceDetailVM"

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val currentPath: String = "",
    val files: List<WorkspaceFileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val installing: Boolean = false,
    val installProgress: RootfsInstallProgress? = null,
    val errorMessage: String? = null,
) {
    val shellStatus: WorkspaceShellStatus
        get() = runCatching {
            WorkspaceShellStatus.valueOf(workspace?.shellStatus ?: WorkspaceShellStatus.DISABLED.name)
        }.getOrDefault(WorkspaceShellStatus.DISABLED)

    val rootfsReady: Boolean get() = shellStatus == WorkspaceShellStatus.READY
}

/** 终端会话条目，用于在 UI 列出已打开的终端 */
data class WorkspaceTerminalEntry(
    val id: String,
    val title: String,
)

/** 终端整体状态 */
data class WorkspaceTerminalState(
    val sessions: List<WorkspaceTerminalEntry> = emptyList(),
    val activeSessionId: String? = null,
)

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {

    val workspaceFlow: StateFlow<WorkspaceEntity?> = repository.listFlow()
        .map { list -> list.find { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state: StateFlow<WorkspaceDetailState> = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState: StateFlow<WorkspaceTerminalState> = _terminalState.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            workspaceFlow.collect { workspace ->
                _state.value = _state.value.copy(workspace = workspace)
                if (workspace != null) {
                    refreshFiles()
                }
            }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(id, name) }
                .onFailure { setError(it.message) }
        }
    }

    fun switchArea(area: WorkspaceStorageArea) {
        if (_state.value.area == area) return
        _state.value = _state.value.copy(area = area, currentPath = "")
        refreshFiles()
    }

    fun navigateTo(path: String) {
        _state.value = _state.value.copy(currentPath = path)
        refreshFiles()
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current.isBlank()) return
        val parent = current.trimEnd('/').substringBeforeLast('/', "")
        navigateTo(parent)
    }

    fun refreshFiles() {
        val workspace = _state.value.workspace ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(filesLoading = true)
            val result = runCatching {
                repository.listFiles(workspace.id, _state.value.area, _state.value.currentPath)
            }
            _state.value = result.fold(
                onSuccess = { entries ->
                    _state.value.copy(
                        files = entries.sortedWith(
                            compareByDescending<WorkspaceFileEntry> { it.isDirectory }
                                .thenBy { it.name.lowercase() }
                        ),
                        filesLoading = false,
                    )
                },
                onFailure = {
                    Log.e(TAG, "refreshFiles failed", it)
                    _state.value.copy(filesLoading = false, errorMessage = it.message)
                }
            )
        }
    }

    fun deleteFile(entry: WorkspaceFileEntry) {
        val workspace = _state.value.workspace ?: return
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(workspace.id, _state.value.area, entry.path, recursive = entry.isDirectory)
            }.onSuccess { refreshFiles() }
                .onFailure { setError(it.message) }
        }
    }

    /**
     * 导入文件到当前正在浏览的目录。
     *
     * 导入到工作区文件视图(FILES)还是 Rootfs 视图(LINUX)由 [_state.value.area] 决定，
     * 目标路径为 [_state.value.currentPath]。InputStream 由 repository/manager 层的
     * `inputStream.use { ... }` 自动关闭，VM 层无需重复关闭；但若 workspace 为空导致
     * 提前返回，需在此处关闭流以避免泄漏。
     */
    fun importFile(fileName: String, inputStream: InputStream) {
        val workspace = _state.value.workspace ?: run {
            inputStream.close()
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = workspace.id,
                    area = _state.value.area,
                    destinationPath = _state.value.currentPath,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess { refreshFiles() }
                .onFailure { setError(it.message) }
        }
    }

    /**
     * 导出文件到用户指定的输出流。
     *
     * OutputStream 由 repository/manager 层的 `outputStream.use { ... }` 自动关闭，
     * VM 层不需要重复关闭。
     */
    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        val workspace = _state.value.workspace ?: run {
            outputStream.close()
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = workspace.id,
                    area = _state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { setError(it.message) }
        }
    }

    /**
     * 分享文件：将工作区内部文件复制到 [cacheDir] 下的临时文件，
     * 复制完成后回调 [onReady]，由 UI 层生成分享用的 content:// uri。
     *
     * 读文件是 IO 操作，在协程中执行避免卡主线程。
     * repository.exportFile 内部使用 `outputStream.use { ... }` 自动关闭传入的流，
     * VM 层无需重复关闭；临时文件的 outputStream 由 repository 内部关闭。
     */
    fun shareFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        val workspace = _state.value.workspace ?: return
        viewModelScope.launch {
            runCatching {
                val tempFile = File(cacheDir, "workspace_share_${System.currentTimeMillis()}_${entry.name}")
                tempFile.outputStream().use { out ->
                    repository.exportFile(
                        id = workspace.id,
                        area = _state.value.area,
                        path = entry.path,
                        outputStream = out,
                    )
                }
                onReady(tempFile)
            }.onFailure { setError(it.message) }
        }
    }

    fun installRootfs(url: String) {
        require(url.isNotBlank()) { "Rootfs download URL must not be blank" }
        if (_state.value.installing) return
        installJob = viewModelScope.launch {
            _state.value = _state.value.copy(installing = true, installProgress = null, errorMessage = null)
            val result = runCatching {
                repository.installRootfs(id, url) { progress ->
                    _state.value = _state.value.copy(installProgress = progress)
                }
            }
            _state.value = result.fold(
                onSuccess = { _state.value.copy(installing = false) },
                onFailure = {
                    Log.e(TAG, "installRootfs failed", it)
                    _state.value.copy(installing = false, errorMessage = it.message)
                }
            )
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        _state.value = _state.value.copy(installing = false)
    }

    /** 返回当前工作区各工具的实际审批设置（含默认值） */
    fun toolApprovals(workspace: WorkspaceEntity?): Map<String, Boolean> {
        val overrides = workspace?.toolApprovalOverrides().orEmpty()
        return WorkspaceToolDefaultApprovals.keys.associateWith { name ->
            resolveWorkspaceToolApproval(name, overrides)
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setToolApproval(id, toolName, needsApproval) }
                .onFailure { setError(it.message) }
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun setError(message: String?) {
        _state.value = _state.value.copy(errorMessage = message)
    }
}