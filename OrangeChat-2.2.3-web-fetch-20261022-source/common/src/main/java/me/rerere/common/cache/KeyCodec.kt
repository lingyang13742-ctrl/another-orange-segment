/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.common.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64

interface KeyCodec<K : Any> {
    fun toFileName(key: K): String
    fun fromFileName(name: String): K?
}

class Base64JsonKeyCodec<K : Any>(
    private val keySerializer: KSerializer<K>,
    private val json: Json = Json { allowStructuredMapKeys = true }
) : KeyCodec<K> {
    override fun toFileName(key: K): String {
        val jsonStr = json.encodeToString(keySerializer, key)
        val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun fromFileName(name: String): K? = try {
        val decoded = Base64.getUrlDecoder().decode(name)
        val jsonStr = String(decoded, StandardCharsets.UTF_8)
        json.decodeFromString(keySerializer, jsonStr)
    } catch (_: Exception) {
        null
    }
}

