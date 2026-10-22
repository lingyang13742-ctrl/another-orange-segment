/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes

class SoundEffectPlayer(private val context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedSounds = mutableMapOf<Int, Int>()
    private val readySounds = mutableSetOf<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                readySounds.add(sampleId)
                val pending = pendingPlay.remove(sampleId)
                if (pending != null) {
                    soundPool.play(sampleId, pending, pending, 0, 0, 1f)
                }
            }
        }
    }

    private val pendingPlay = mutableMapOf<Int, Float>()

    fun preload(@RawRes vararg resIds: Int) {
        for (resId in resIds) {
            if (resId !in loadedSounds) {
                loadedSounds[resId] = soundPool.load(context, resId, 1)
            }
        }
    }

    fun play(@RawRes resId: Int, volume: Float = 1f) {
        val soundId = loadedSounds[resId] ?: soundPool.load(context, resId, 1).also {
            loadedSounds[resId] = it
        }
        if (soundId in readySounds) {
            soundPool.play(soundId, volume, volume, 0, 0, 1f)
        } else {
            pendingPlay[soundId] = volume
        }
    }

    fun release() {
        soundPool.release()
        loadedSounds.clear()
        readySounds.clear()
        pendingPlay.clear()
    }
}
