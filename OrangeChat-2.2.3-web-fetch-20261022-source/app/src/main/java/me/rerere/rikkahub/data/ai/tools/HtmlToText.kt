/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

/**
 * HTML to plain text converter.
 * Extracted to a separate file to avoid editor auto-formatting issues with HTML entity strings.
 */
object HtmlToText {

    private const val TAG = "HtmlToText"

    /**
     * Convert HTML to readable plain text.
     * - Removes script/style/noscript/svg/head tags and their content
     * - Removes all HTML tags
     * - Decodes HTML entities (named and numeric)
     * - Preserves paragraph breaks
     */
    fun convert(html: String): String {
        var text = html

        // Remove script/style/noscript/svg/head tags and their content
        val removeTags = listOf("script", "style", "noscript", "svg", "head")
        for (tag in removeTags) {
            val pattern = Regex("(?is)<" + tag + "[^>]*>.*?</" + tag + ">")
            text = pattern.replace(text, "")
        }

        // Remove HTML comments
        text = Regex("(?s)<!--.*?-->").replace(text, "")

        // Convert block-level elements to newlines
        val blockTags = listOf("p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "hr")
        for (tag in blockTags) {
            text = Regex("(?i)<" + tag + "[^>]*>", RegexOption.IGNORE_CASE).replace(text, "\n")
            text = Regex("(?i)</" + tag + ">", RegexOption.IGNORE_CASE).replace(text, "\n")
        }

        // Remove all remaining HTML tags
        text = Regex("(?s)<[^>]+>").replace(text, "")

        // Decode HTML entities using char-by-char construction to avoid editor issues
        val entityAmp = buildString { append('&'); append('a'); append('m'); append('p'); append(';') }
        val entityLt = buildString { append('&'); append('l'); append('t'); append(';') }
        val entityGt = buildString { append('&'); append('g'); append('t'); append(';') }
        val entityQuot = buildString { append('&'); append('q'); append('u'); append('o'); append('t'); append(';') }
        val entityApos = buildString { append('&'); append('a'); append('p'); append('o'); append('s'); append(';') }
        val entityNbsp = buildString { append('&'); append('n'); append('b'); append('s'); append('p'); append(';') }
        val entityCopy = buildString { append('&'); append('c'); append('o'); append('p'); append('y'); append(';') }
        val entityReg = buildString { append('&'); append('r'); append('e'); append('g'); append(';') }
        val entityTrade = buildString { append('&'); append('t'); append('r'); append('a'); append('d'); append('e'); append(';') }
        val entityHash39 = buildString { append('&'); append('#'); append('3'); append('9'); append(';') }

        text = text
            .replace(entityNbsp, " ")
            .replace(entityAmp, "&")
            .replace(entityLt, "<")
            .replace(entityGt, ">")
            .replace(entityQuot, "\"")
            .replace(entityHash39, "'")
            .replace(entityApos, "'")
            .replace(entityCopy, "\u00A9")
            .replace(entityReg, "\u00AE")
            .replace(entityTrade, "\u2122")

        // Decode numeric HTML entities: &#NNN; and &#xHHH;
        val hashPrefix = buildString { append('&'); append('#') }
        text = Regex(Regex.escape(hashPrefix) + "(\\d+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        text = Regex(Regex.escape(hashPrefix) + "x([0-9a-fA-F]+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }

        // Clean up whitespace
        text = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex(" +"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return text
    }
}