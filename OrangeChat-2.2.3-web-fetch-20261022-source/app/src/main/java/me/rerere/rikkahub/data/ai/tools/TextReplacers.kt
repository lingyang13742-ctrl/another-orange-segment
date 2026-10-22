/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools
 
/**
 * 文本搜索/替换的多级匹配策略, 供 workspace_edit_file 使用。
 *
 * 逐级尝试:
 *  1. ExactReplacer       - 精确字符串匹配
 *  2. LineTrimmedReplacer - 逐行匹配, 忽略每行首尾空白(应对缩进/尾随空格差异), 替换时按实际命中位置的缩进重排 new_text
 *  3. BlockAnchorReplacer - 用首尾行作为锚点匹配中间的块(应对块内细微差异), 替换时同样按实际命中位置重排缩进
 *
 * 任一级命中即返回, 否则抛出 IllegalArgumentException。
 */
 
data class ReplaceResult(
    val updated: String,
    val replacements: Int,
    val strategy: String,
)
 
interface TextReplacer {
    val name: String
 
    /**
     * 尝试在 [content] 中用 [newText] 替换 [oldText]。
     * @return 替换后的文本与替换次数; 若未命中返回 null 交由下一级处理。
     */
    fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceResult?
}
 
object ExactReplacer : TextReplacer {
    override val name: String = "exact"
 
    override fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceResult? {
        val firstIndex = content.indexOf(oldText)
        if (firstIndex < 0) return null
        return if (replaceAll) {
            val count = countOccurrences(content, oldText)
            ReplaceResult(content.replace(oldText, newText), count, name)
        } else {
            val secondIndex = content.indexOf(oldText, firstIndex + oldText.length)
            require(secondIndex < 0) {
                "old_text occurs multiple times; set replace_all=true or provide a more specific match"
            }
            val updated = content.substring(0, firstIndex) + newText + content.substring(firstIndex + oldText.length)
            ReplaceResult(updated, 1, name)
        }
    }
 
    private fun countOccurrences(content: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = content.indexOf(target)
        while (index >= 0) {
            count++
            index = content.indexOf(target, index + target.length)
        }
        return count
    }
}
 
object LineTrimmedReplacer : TextReplacer {
    override val name: String = "line_trimmed"
 
    override fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceResult? {
        val contentLines = content.split("\n")
        val oldLines = oldText.split("\n").let { lines ->
            // 去掉 oldText 末尾的空行(由结尾换行产生)
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
        if (oldLines.isEmpty()) return null
 
        val matches = mutableListOf<IntRange>()
        var searchStart = 0
        while (searchStart + oldLines.size <= contentLines.size) {
            var matched = true
            for (i in oldLines.indices) {
                if (contentLines[searchStart + i].trim() != oldLines[i].trim()) {
                    matched = false
                    break
                }
            }
            if (matched) {
                matches += searchStart until (searchStart + oldLines.size)
                searchStart += oldLines.size
            } else {
                searchStart++
            }
        }
 
        if (matches.isEmpty()) return null
        if (!replaceAll) {
            require(matches.size == 1) {
                "old_text matches multiple locations (whitespace-tolerant); set replace_all=true or be more specific"
            }
        }
 
        val newBlockLines = newText.split("\n").let { lines ->
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
        // old_text 第一行的原始缩进, 作为 reindent 的基准(AI 传来的 new_text 缩进通常是相对这个基准写的)
        val oldIndent = indentOf(oldLines.first())
 
        val resultLines = mutableListOf<String>()
        var cursor = 0
        var replacements = 0
        val targets = if (replaceAll) matches else listOf(matches.first())
        for (range in targets) {
            while (cursor < range.first) {
                resultLines += contentLines[cursor]
                cursor++
            }
            // 按本次命中位置首行在文件里的实际缩进, 重排 new_text 的缩进, 避免替换后格式错位
            val newIndent = indentOf(contentLines[range.first])
            resultLines += reindentLines(newBlockLines, oldIndent, newIndent)
            cursor = range.last + 1
            replacements++
        }
        while (cursor < contentLines.size) {
            resultLines += contentLines[cursor]
            cursor++
        }
 
        return ReplaceResult(resultLines.joinToString("\n"), replacements, name)
    }
}
 
object BlockAnchorReplacer : TextReplacer {
    override val name: String = "block_anchor"
 
    override fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceResult? {
        val contentLines = content.split("\n")
        val oldLines = oldText.split("\n").let { lines ->
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
        // 仅当块至少有 3 行时使用首尾锚点(否则退化等同于 line_trimmed)
        if (oldLines.size < 3) return null
 
        val firstAnchor = oldLines.first().trim()
        val lastAnchor = oldLines.last().trim()
        val blockSize = oldLines.size
 
        val matches = mutableListOf<IntRange>()
        var i = 0
        while (i + blockSize <= contentLines.size) {
            if (contentLines[i].trim() == firstAnchor &&
                contentLines[i + blockSize - 1].trim() == lastAnchor
            ) {
                matches += i until (i + blockSize)
                i += blockSize
            } else {
                i++
            }
        }
 
        if (matches.isEmpty()) return null
        if (!replaceAll) {
            require(matches.size == 1) {
                "old_text anchor matches multiple blocks; set replace_all=true or be more specific"
            }
        }
 
        val newBlockLines = newText.split("\n").let { lines ->
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
        val oldIndent = indentOf(oldLines.first())
 
        val resultLines = mutableListOf<String>()
        var cursor = 0
        var replacements = 0
        val targets = if (replaceAll) matches else listOf(matches.first())
        for (range in targets) {
            while (cursor < range.first) {
                resultLines += contentLines[cursor]
                cursor++
            }
            val newIndent = indentOf(contentLines[range.first])
            resultLines += reindentLines(newBlockLines, oldIndent, newIndent)
            cursor = range.last + 1
            replacements++
        }
        while (cursor < contentLines.size) {
            resultLines += contentLines[cursor]
            cursor++
        }
 
        return ReplaceResult(resultLines.joinToString("\n"), replacements, name)
    }
}
 
/** 取一行开头连续的空格/制表符作为这一行的缩进 */
private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }
 
/**
 * 把 [lines] 中每一行的缩进从 [oldIndent] 替换成 [newIndent]。
 * 只替换确实以 oldIndent 开头的行; 空白行原样保留; 不以 oldIndent 开头的行(缩进比基准还浅/用了不同字符)也原样保留,
 * 不强行裁剪, 避免把本就不规则的缩进改得更乱。
 */
private fun reindentLines(lines: List<String>, oldIndent: String, newIndent: String): List<String> {
    if (oldIndent == newIndent) return lines
    return lines.map { line ->
        when {
            line.isBlank() -> line
            line.startsWith(oldIndent) -> newIndent + line.removePrefix(oldIndent)
            else -> line
        }
    }
}
 
private val REPLACERS = listOf(ExactReplacer, LineTrimmedReplacer, BlockAnchorReplacer)
 
/**
 * 逐级尝试替换器, 返回首个命中的结果。若全部未命中则抛出 IllegalArgumentException。
 */
fun replaceText(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean,
): ReplaceResult {
    for (replacer in REPLACERS) {
        val result = replacer.replace(content, oldText, newText, replaceAll)
        if (result != null) return result
    }
    throw IllegalArgumentException("old_text not found in file")
}