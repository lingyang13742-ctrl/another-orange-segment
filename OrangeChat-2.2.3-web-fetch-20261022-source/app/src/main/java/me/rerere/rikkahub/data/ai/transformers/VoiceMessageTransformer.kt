/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Transforms VoiceMessage parts into Text parts for AI providers.
 * Voice messages are displayed as voice bubbles in the UI but sent as text to the AI.
 */
object VoiceMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.VoiceMessage -> {
                            if (part.transcript.isNotBlank()) {
                                UIMessagePart.Text(text = part.transcript)
                            } else {
                                UIMessagePart.Text(text = "[语音消息]")
                            }
                        }
                        else -> part
                    }
                }
            )
        }
    }
}