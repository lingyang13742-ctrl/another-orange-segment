/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.document.CsvParser
import me.rerere.document.ExcelGenerator
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightText
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toDp
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val displaySettings = LocalDisplaySettings.current
    val normalizedLanguage = remember(language) { resolveHighlightLanguage(language) }
    val canInlinePreview = completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES
    var previewMode by remember(canInlinePreview, code, normalizedLanguage) {
        mutableStateOf(canInlinePreview)
    }

    var isExpanded by remember(displaySettings.codeBlockAutoCollapse) {
        mutableStateOf(!displaySettings.codeBlockAutoCollapse)
    }
    val autoWrap = displaySettings.codeBlockAutoWrap
    val showLineNumbers = displaySettings.showLineNumbers

    val isExcelLanguage = remember(normalizedLanguage) {
        normalizedLanguage in setOf("csv", "excel", "xlsx")
    }

    var pendingExcelBytes by remember { mutableStateOf<ByteArray?>(null) }

    val excelDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri: Uri? ->
        val bytesToWrite = pendingExcelBytes
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        bytesToWrite?.let { bytes -> outputStream.write(bytes) }
                    }
                } catch (e: Exception) {
                    Log.e("HighlightCodeBlock", "Excel save failed: uri=$it, size=${bytesToWrite?.size}", e)
                } finally {
                    pendingExcelBytes = null
                }
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(code.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            HighlightCodeActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
                createDocumentLauncher = createDocumentLauncher,
                navController = navController,
                completeCodeBlock = completeCodeBlock,
                previewMode = previewMode,
                canInlinePreview = canInlinePreview,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
                isExcelLanguage = isExcelLanguage,
                onExportExcel = if (isExcelLanguage) {
                    {
                        scope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    if (normalizedLanguage == "csv") {
                                        val rows = CsvParser.parse(code)
                                        ExcelGenerator.generate(rows)
                                    } else {
                                        // excel / xlsx 语言标签：带样式 JSON 格式
                                        ExcelGenerator.generateStyled(code)
                                    }
                                }
                                pendingExcelBytes = bytes
                                val ts = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                val baseName = language.substringAfterLast("/").substringBeforeLast(".")
                                val fileName = if (baseName.isNotBlank() && baseName != language) {
                                    "$baseName.xlsx"
                                } else {
                                    "export_${ts.year}${ts.month.toString().padStart(2, '0')}${ts.day.toString().padStart(2, '0')}_${ts.hour.toString().padStart(2, '0')}${ts.minute.toString().padStart(2, '0')}${ts.second.toString().padStart(2, '0')}.xlsx"
                                }
                                excelDocumentLauncher.launch(fileName)
                            } catch (e: Exception) {
                                Log.e("HighlightCodeBlock", "Excel export failed: lang=$normalizedLanguage, codeLen=${code.length}", e)
                            }
                        }
                    }
                } else null,
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                canInlinePreview && previewMode -> {
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
                completeCodeBlock && normalizedLanguage == "mermaid" -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    val textStyle = LocalTextStyle.current.merge(style)
                    val codeLines = remember(code) { code.lines() }
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = normalizedLanguage,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = normalizedLanguage,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    // 代码折叠按钮
                    if (displaySettings.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
                        Box(
                            modifier = Modifier
                                .onClick {
                                    isExpanded = !isExpanded
                                }
                                .fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(textStyle.fontSize.toDp())
                                )
                                Text(
                                    text = if (isExpanded) {
                                        stringResource(id = R.string.code_block_collapse)
                                    } else {
                                        stringResource(id = R.string.code_block_expand)
                                    },
                                    fontSize = textStyle.fontSize,
                                    lineHeight = textStyle.lineHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    HighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        // 代码列
        SelectionContainer {
            HighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canInlinePreview: Boolean = false,
    onTogglePreviewMode: () -> Unit = {},
    isExcelLanguage: Boolean = false,
    onExportExcel: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            if (isExcelLanguage && onExportExcel != null) {
                Text(
                    text = "xlsx",
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    color = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick { onExportExcel() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(id = R.string.chat_page_save),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        val fileName = if (language.contains(".") || language.contains("/")) {
                            // 语言标签是文件名或路径（如 MainActivity.kt, src/main.py）
                            language.substringAfterLast("/")
                        } else {
                            // 纯语言名，根据语言生成文件名
                            val extension = when (language.lowercase()) {
                                "kotlin" -> "kt"
                                "java" -> "java"
                                "python" -> "py"
                                "javascript" -> "js"
                                "typescript" -> "ts"
                                "cpp", "c++" -> "cpp"
                                "c" -> "c"
                                "html" -> "html"
                                "css" -> "css"
                                "xml" -> "xml"
                                "json" -> "json"
                                "yaml", "yml" -> "yml"
                                "markdown", "md" -> "md"
                                "sql" -> "sql"
                                "sh", "bash" -> "sh"
                                "svg" -> "svg"
                                "rust" -> "rs"
                                "go" -> "go"
                                "php" -> "php"
                                "ruby" -> "rb"
                                "swift" -> "swift"
                                "vue" -> "vue"
                                "csharp", "cs" -> "cs"
                                "dart" -> "dart"
                                "lua" -> "lua"
                                "perl" -> "pl"
                                "r" -> "r"
                                "scala" -> "scala"
                                "csv" -> "csv"
                                "excel", "xlsx" -> "xlsx"
                                "jsx" -> "jsx"
                                "tsx" -> "tsx"
                                else -> "txt"
                            }
                            "code_${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            }.$extension"
                        }
                        createDocumentLauncher.launch(fileName)
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(id = R.string.code_block_copy),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            val resolvedLanguage = resolveHighlightLanguage(language)
            if (canInlinePreview) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
                    contentDescription = if (previewMode) "Code" else stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            onTogglePreviewMode()
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            if (completeCodeBlock && resolvedLanguage in PREVIEWABLE_LANGUAGES) {
                Icon(
                    imageVector = HugeIcons.Eye,
                    contentDescription = stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            val content = buildCodePreviewHtml(code = code, language = resolvedLanguage)
                            navController.navigate(Screen.WebView(content = content.base64Encode()))
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
        }
    )

    WebView(
        state = state,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}

private fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        code
    }
}

// 从语言标签解析用于语法高亮的语言标识
// 支持文件名格式（如 MainActivity.kt, src/main.py, manifest.json）
private fun resolveHighlightLanguage(languageTag: String): String {
    // 如果包含路径分隔符，取最后一段作为文件名
    val fileName = languageTag.substringAfterLast("/")
    val extension = fileName.substringAfterLast(".", "").lowercase()

    // 如果标签本身就是语言名（没有扩展名），直接返回小写形式
if (extension.isEmpty()) {
        return languageTag.lowercase()
    }

    // 从扩展名映射到语言标识
    return when (extension) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "mjs" -> "javascript"
        "ts" -> "typescript"
        "jsx" -> "jsx"
        "tsx" -> "tsx"
        "cpp", "cc", "cxx" -> "cpp"
        "c" -> "c"
        "html" -> "html"
        "css" -> "css"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "md" -> "markdown"
        "sql" -> "sql"
        "sh" -> "bash"
        "svg" -> "svg"
        "rs" -> "rust"
        "go" -> "go"
        "php" -> "php"
        "rb" -> "ruby"
        "swift" -> "swift"
        "vue" -> "vue"
        "toml" -> "toml"
        "ini" -> "ini"
        "cs" -> "csharp"
        "dart" -> "dart"
        "lua" -> "lua"
        "pl" -> "perl"
        "scala" -> "scala"
        "csv" -> "csv"
        "xlsx" -> "xlsx"
        "gradle" -> "gradle"
        else -> {
            when (fileName.lowercase()) {
                "dockerfile" -> "dockerfile"
                "makefile" -> "makefile"
                else -> extension.ifBlank { languageTag.lowercase() }
            }
        }
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: Highlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                runBlocking {
                    val tokens = highlighter.highlight(text.text, language)
                    buildAnnotatedString {
                        tokens.forEach { token ->
                            buildHighlightText(token, colorPalette)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }

    companion object {
        @Composable
        fun regex() = HighlightCodeVisualTransformation(
            language = "regex",
            highlighter = LocalHighlighter.current,
            darkMode = LocalDarkMode.current,
        )
    }
}
