/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web.routes

import me.rerere.rikkahub.web.dto.ConversationDto
import me.rerere.rikkahub.web.dto.MessageNodeDto

internal data class NodeDiff(
    val nodeIndex: Int,
    val node: MessageNodeDto
)

internal fun ConversationDto.singleNodeDiffOrNull(current: ConversationDto): NodeDiff? {
    if (id != current.id || assistantId != current.assistantId || createAt != current.createAt) {
        return null
    }

    if (
        title != current.title ||
        chatSuggestions != current.chatSuggestions ||
        isPinned != current.isPinned
    ) {
        return null
    }

    if (messages.size > current.messages.size) {
        return null
    }

    var changedIndex = -1
    val maxSize = maxOf(messages.size, current.messages.size)
    for (index in 0 until maxSize) {
        val previousNode = messages.getOrNull(index)
        val currentNode = current.messages.getOrNull(index)
        if (previousNode == currentNode) continue

        if (changedIndex != -1) {
            return null
        }
        changedIndex = index
    }

    if (changedIndex == -1) {
        return null
    }

    val changedNode = current.messages.getOrNull(changedIndex) ?: return null
    return NodeDiff(nodeIndex = changedIndex, node = changedNode)
}
