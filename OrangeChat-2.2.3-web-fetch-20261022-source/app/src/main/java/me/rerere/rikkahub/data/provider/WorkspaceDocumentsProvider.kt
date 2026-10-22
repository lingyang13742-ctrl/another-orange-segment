/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * 通过 Storage Access Framework 暴露各 Workspace 的「files」区域，
 * 让系统「文件」App / 文档选择器可以浏览、读写工作区文件。
 *
 * Document ID 约定：
 * - 根：`<workspaceRoot>`
 * - 文件/目录：`<workspaceRoot>/<relativePath>`
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private val manager: WorkspaceManager by lazy { getKoin().get() }
    private val dao: WorkspaceDAO by lazy { getKoin().get() }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return result
        val workspaces = runCatching { runBlocking { dao.getAll() } }.getOrDefault(emptyList())
        for (workspace in workspaces) {
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, workspace.root)
                add(Root.COLUMN_DOCUMENT_ID, workspace.root)
                add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, workspace.name)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (root, file) = resolve(documentId)
        includeFile(result, root, file)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (root, parent) = resolve(parentDocumentId)
        parent.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )?.forEach { child ->
            includeFile(result, root, child)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (_, file) = resolve(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val (root, parent) = resolve(parentDocumentId)
        val target = File(parent, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!target.mkdirs() && !target.isDirectory) {
                error("Failed to create directory: $displayName")
            }
        } else {
            target.parentFile?.mkdirs()
            if (!target.createNewFile() && !target.isFile) {
                error("Failed to create file: $displayName")
            }
        }
        return documentIdOf(root, target)
    }

    override fun deleteDocument(documentId: String) {
        val (_, file) = resolve(documentId)
        if (!file.deleteRecursively()) {
            error("Failed to delete: $documentId")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    private fun includeFile(cursor: MatrixCursor, root: String, file: File) {
        if (!file.exists()) return
        var flags = 0
        if (file.isDirectory && file.canWrite()) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        }
        val mime = if (file.isDirectory) Document.MIME_TYPE_DIR else mimeOf(file)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentIdOf(root, file))
            add(Document.COLUMN_DISPLAY_NAME, file.name.ifBlank { root })
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    /** documentId -> (workspaceRoot, 实际文件) */
    private fun resolve(documentId: String): Pair<String, File> {
        val root = documentId.substringBefore('/')
        val relative = documentId.substringAfter('/', "")
        val filesDir = manager.filesDir(root)
        val target = if (relative.isBlank()) {
            filesDir
        } else {
            File(filesDir, relative)
        }
        // 防止越界访问
        val canonicalRoot = filesDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(
            canonicalTarget.path == canonicalRoot.path ||
                canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) { "Path escapes workspace: $documentId" }
        return root to target
    }

    private fun documentIdOf(root: String, file: File): String {
        val filesDir = manager.filesDir(root).canonicalFile
        val canonical = file.canonicalFile
        val relative = canonical.path.removePrefix(filesDir.path).trimStart(File.separatorChar)
        return if (relative.isBlank()) root else "$root/$relative"
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}