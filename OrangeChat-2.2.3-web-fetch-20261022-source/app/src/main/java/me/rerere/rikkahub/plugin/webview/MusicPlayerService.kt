/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import me.rerere.rikkahub.MUSIC_PLAYER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.io.File

private const val TAG = "MusicPlayerService"
private const val NOTIFICATION_ID = 2001

private const val ACTION_PLAY = "me.rerere.rikkahub.MUSIC_PLAY"
private const val ACTION_PAUSE = "me.rerere.rikkahub.MUSIC_PAUSE"
private const val ACTION_RESUME = "me.rerere.rikkahub.MUSIC_RESUME"
private const val ACTION_STOP = "me.rerere.rikkahub.MUSIC_STOP"
private const val EXTRA_FILE_PATH = "filePath"
private const val EXTRA_TITLE = "title"
private const val EXTRA_ARTIST = "artist"

class MusicPlayerService : Service() {

    companion object {
        const val ACTION_MUSIC_COMPLETED = "me.rerere.rikkahub.MUSIC_COMPLETED"
        private const val ACTION_SEEK = "me.rerere.rikkahub.MUSIC_SEEK"
        private const val EXTRA_POSITION = "position"

        // 所有共享可变状态的读写都必须经过这把锁：onStartCommand 处理的各种 action、
        // ExoPlayer 的播放状态/错误回调、onDestroy，可能在不同时间点并发触碰这些字段
        private val stateLock = Any()

        private var exoPlayer: ExoPlayer? = null
        private var currentState: Int = STATE_STOPPED
        private var currentTitle: String = ""
        private var currentArtist: String = ""
        private var currentFilePath: String = ""

        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2
        private const val STATE_STOPPED = 3

        fun play(context: Context, filePath: String, title: String, artist: String) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isPlaying(): Boolean = synchronized(stateLock) { currentState == STATE_PLAYING }

        fun getState(): String = synchronized(stateLock) {
            when (currentState) {
                STATE_PLAYING -> "playing"
                STATE_PAUSED -> "paused"
                else -> "stopped"
            }
        }

        fun getNowPlaying(): Map<String, String> = synchronized(stateLock) {
            mapOf(
                "title" to currentTitle,
                "artist" to currentArtist,
                "filePath" to currentFilePath,
                "state" to getState()
            )
        }

        fun seekTo(context: Context, position: Int) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION, position)
            }
            context.startService(intent)
        }

        // ExoPlayer 的 currentPosition/duration 本身是 Long（毫秒），且只能在创建它的那个线程
        // （这里是 Service 所在的主线程）访问，不能跨线程调用，这点跟原来 MediaPlayer 比要求更严格
        fun getCurrentPosition(): Int = synchronized(stateLock) {
            try {
                exoPlayer?.currentPosition?.toInt() ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "getCurrentPosition failed, err=${e.message}", e)
                0
            }
        }

        fun getDuration(): Int = synchronized(stateLock) {
            try {
                val d = exoPlayer?.duration ?: 0L
                // 播放器还没真正 ready 时，duration 会是 C.TIME_UNSET（一个特殊的很大负数），
                // 不能直接转 Int 返回给前端，否则会显示一个荒谬的数字
                if (d == C.TIME_UNSET || d < 0) 0 else d.toInt()
            } catch (e: Exception) {
                Log.e(TAG, "getDuration failed, err=${e.message}", e)
                0
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须最先调用 startForeground()，否则 Android 12+ 会抛出 ForegroundServiceStartNotAllowedException
        startForeground(NOTIFICATION_ID, buildNotification(currentTitle.ifEmpty { "Music" }, currentArtist))

        when (intent?.action) {
            ACTION_PLAY -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                if (filePath.isNotEmpty()) {
                    startPlayback(filePath, title, artist)
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> {
                val position = intent.getIntExtra(EXTRA_POSITION, 0)
                seekPlayback(position)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(filePath: String, title: String, artist: String) {
        var startedOk = false

        synchronized(stateLock) {
            try {
                exoPlayer?.release()
            } catch (_: Exception) {}
            exoPlayer = null

            currentTitle = title
            currentArtist = artist
            currentFilePath = filePath

            try {
                val player = ExoPlayer.Builder(applicationContext).build()

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                player.setAudioAttributes(audioAttributes, true)

                // ExoPlayer 的 prepare() 是异步的：调用后立即返回，真正"是否能正常播放/是否出错"
                // 都要靠下面这个监听器异步上报，不再像原来 MediaPlayer 那样能在同一行代码里 try-catch 拿到结果。
                // STATE_ENDED 是 ExoPlayer 官方标准的"播放到底了"信号，比原来 MediaPlayer 的 onCompletion 更可靠。
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            var shouldNotify = false
                            synchronized(stateLock) {
                                // 过期检查：如果这个回调对应的播放器已经不是当前播放器了，就什么都不做，
                                // 避免把新歌的状态错误地改回"已停止"
                                if (exoPlayer === player) {
                                    currentState = STATE_STOPPED
                                    shouldNotify = true
                                }
                            }
                            if (shouldNotify) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                LocalBroadcastManager.getInstance(this@MusicPlayerService)
                                    .sendBroadcast(Intent(ACTION_MUSIC_COMPLETED))
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            TAG,
                            "ExoPlayer error: ${error.errorCodeName}, msg=${error.message}, filePath=$filePath, title=$title",
                            error
                        )
                        synchronized(stateLock) {
                            if (exoPlayer === player) {
                                currentState = STATE_STOPPED
                                exoPlayer = null
                            }
                        }
                    }
                })

                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(filePath))))
                player.prepare()
                player.play()

                exoPlayer = player
                currentState = STATE_PLAYING
                startedOk = true
                Log.i(TAG, "Playing: $artist - $title")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play (unexpected): ${e.message}, filePath=$filePath", e)
                currentState = STATE_STOPPED
            }
        }

        if (startedOk) {
            showNotification(title, artist)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pausePlayback() {
        var shouldNotify = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        currentState = STATE_PAUSED
                        shouldNotify = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pause error: ${e.message}", e)
            }
        }
        if (shouldNotify) showNotification(currentTitle, currentArtist)
    }

    private fun resumePlayback() {
        var shouldNotify = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (!it.isPlaying && currentState == STATE_PAUSED) {
                        it.play()
                        currentState = STATE_PLAYING
                        shouldNotify = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resume error: ${e.message}", e)
            }
        }
        if (shouldNotify) showNotification(currentTitle, currentArtist)
    }

    private fun stopPlayback() {
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            exoPlayer = null
            currentState = STATE_STOPPED
            currentTitle = ""
            currentArtist = ""
            currentFilePath = ""
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun seekPlayback(position: Int) {
        synchronized(stateLock) {
            try {
                exoPlayer?.seekTo(position.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed, position=$position, err=${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            exoPlayer = null
            currentState = STATE_STOPPED
        }
        super.onDestroy()
    }

    private fun buildNotification(title: String, artist: String): android.app.Notification {
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, MUSIC_PLAYER_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🎵 $title")
            .setContentText(artist)
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(currentState == STATE_PLAYING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showNotification(title: String, artist: String) {
        val notification = buildNotification(title, artist)

        if (currentState == STATE_PLAYING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}