/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

@Composable
fun AssistantBackground(setting: Settings) {
    val assistant = setting.getCurrentAssistant()
    val chatBackgroundColor = setting.displaySetting.chatBackgroundColor?.let { it.toComposeColor() }

    when {
        assistant.background != null -> {
            // 用户手动为助手设置的背景图，优先级最高
            val backgroundColor = chatBackgroundColor ?: MaterialTheme.colorScheme.background
            val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
            Box {
                AsyncImage(
                    model = assistant.background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(backgroundOpacity)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    backgroundColor.copy(alpha = 0.2f),
                                    backgroundColor.copy(alpha = 0.5f)
                                )
                            )
                        )
                )
            }
        }

        chatBackgroundColor != null -> {
            // 用户设置了自定义纯色背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatBackgroundColor)
            )
        }
    }
}
