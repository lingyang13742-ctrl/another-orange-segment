/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus

@Composable
fun TTSController() {
    val context = LocalContext.current
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) {
            isVisible = true
        }
    }

    FloatingWindow(
        tag = "tts_controller",
        visibility = isVisible
    ) {
        val playbackState by ttsState.playbackState.collectAsState()
        TelegramVoiceBar(
            playbackState = playbackState,
            ttsState = ttsState,
            onClose = {
                ttsState.stop()
                isVisible = false
            }
        )
    }
}

@Composable
private fun TelegramVoiceBar(
    playbackState: PlaybackState,
    ttsState: CustomTtsState,
    onClose: () -> Unit
) {
    val progress = if (playbackState.durationMs > 0) {
        playbackState.positionMs.toFloat() / playbackState.durationMs
    } else 0f

    // Pseudo-random waveform bars
    val waveformBars = remember {
        val rnd = java.util.Random(42)
        List(32) { 0.15f + rnd.nextFloat() * 0.85f }
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val buffering = playbackState.status == PlaybackStatus.Buffering

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Play / Pause button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        when (playbackState.status) {
                            PlaybackStatus.Playing -> ttsState.pause()
                            else -> ttsState.resume()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.status == PlaybackStatus.Playing) HugeIcons.Pause else HugeIcons.Play,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Waveform
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
            ) {
                val barCount = waveformBars.size
                val totalWidth = size.width
                val barWidth = 2.5f
                val gap = (totalWidth - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
                val playedBarCount = (progress * barCount).toInt()

                waveformBars.forEachIndexed { index, barRatio ->
                    val barHeight = size.height * barRatio.coerceIn(0.15f, 1f)
                    val x = index * (barWidth + gap)
                    val y = (size.height - barHeight) / 2f
                    drawRoundRect(
                        color = if (index < playedBarCount) activeColor else inactiveColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Time display
            val remainingSec = ((playbackState.durationMs - playbackState.positionMs) / 1000).coerceAtLeast(0)
            Text(
                text = String.format("%d:%02d", remainingSec / 60, remainingSec % 60),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.width(2.dp))

            // Speed button
            TextButton(
                onClick = {
                    when (playbackState.speed) {
                        0.8f -> ttsState.setSpeed(1.0f)
                        1.0f -> ttsState.setSpeed(1.2f)
                        1.2f -> ttsState.setSpeed(1.5f)
                        1.5f -> ttsState.setSpeed(0.8f)
                        else -> ttsState.setSpeed(1.0f)
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = "${"%.1f".format(playbackState.speed)}x",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}