/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingMiMoTest {
    @Test
    fun mimo_defaults_are_expected() {
        val setting = TTSProviderSetting.MiMo()

        assertEquals("MiMo TTS", setting.name)
        assertEquals("https://api.xiaomimimo.com/v1", setting.baseUrl)
        assertEquals("mimo-v2-tts", setting.model)
        assertEquals("mimo_default", setting.voice)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun mimo_is_registered_in_provider_types() {
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.MiMo::class))
    }
}
