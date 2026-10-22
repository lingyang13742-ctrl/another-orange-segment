/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

/**
 * 生成两段文本之间的 unified diff（git diff 风格），用于展示文件编辑前后的变化。
 * 基于 java-diff-utils 库。
 */
fun generateUnifiedDiff(
    original: String,
    updated: String,
    filePath: String,
    contextLines: Int = 3,
): String? {
    if (original == updated) return null
    val originalLines = original.lines()
    val updatedLines = updated.lines()
    val patch = DiffUtils.diff(originalLines, updatedLines)
    if (patch.deltas.isEmpty()) return null
    val unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
        filePath,
        filePath,
        originalLines,
        patch,
        contextLines,
    )
    return unifiedDiff.joinToString("\n")
}