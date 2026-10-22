/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr

/**
 * Strips trailing emoji/emoticon characters from ASR transcript text.
 * Some ASR services (e.g., Volcengine with ITN enabled) may append
 * unwanted emoji that don't match the speaker's actual intent.
 */
fun String.stripTrailingEmoji(): String {
    var end = this.length
    while (end > 0) {
        val cp = this.codePointBefore(end)
        val charCount = Character.charCount(cp)
        when {
            // Skip whitespace, variation selector, zero-width joiner
            cp == ' '.code || cp == 0xFE0F || cp == 0x200D -> end -= charCount
            // Variation selectors (FE00-FE0F)
            cp in 0xFE00..0xFE0F -> end -= charCount
            // Emoji blocks (supplementary plane)
            cp in 0x1F000..0x1FFFF -> end -= charCount
            // Miscellaneous symbols and dingbats
            cp in 0x2600..0x27BF -> end -= charCount
            else -> {
                val type = Character.getType(cp)
                if (type == Character.OTHER_SYMBOL.toInt() ||
                    type == Character.MODIFIER_SYMBOL.toInt() ||
                    type == Character.CURRENCY_SYMBOL.toInt()
                ) {
                    end -= charCount
                } else {
                    break
                }
            }
        }
    }
    return this.substring(0, end)
}