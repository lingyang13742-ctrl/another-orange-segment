/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rerere.rikkahub.POMODORO_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val TAG = "PomodoroTimerService"

class PomodoroTimerService : android.app.Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.POMODORO_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.POMODORO_STOP"
        const val ACTION_BOOT_RESTORE = "me.rerere.rikkahub.action.POMODORO_BOOT_RESTORE"
        const val EXTRA_SECONDS = "seconds"
        const val NOTIFICATION_ID = 3001

        const val ACTION_TIMER_END = "me.rerere.rikkahub.TIMER_END"

        private const val PREFS_NAME = "pomodoro_timer_prefs"
        private const val KEY_END_TIMESTAMP = "end_timestamp"
        private const val KEY_TOTAL_SECONDS = "total_seconds"

        @Volatile
        private var remainingSeconds: Int = 0

        @Volatile
        private var running: Boolean = false

        fun getRemainingSeconds(): Int = remainingSeconds

        fun isRunning(): Boolean = running

        fun start(context: Context, seconds: Int) {
            val intent = Intent(context, PomodoroTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SECONDS, seconds)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PomodoroTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var scheduler: ScheduledExecutorService? = null
    private var endTimestamp: Long = 0L
    private var totalSeconds: Int = 0

    private val timerEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Timer end broadcast received - handled locally
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            timerEndReceiver,
            IntentFilter(ACTION_TIMER_END)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduler?.shutdownNow()
        scheduler = null
        running = false
        remainingSeconds = 0
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(timerEndReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 25 * 60)
                startCountdown(seconds)
            }

            ACTION_STOP -> {
                stopCountdown()
            }

            ACTION_BOOT_RESTORE -> {
                restoreFromPrefs()
            }

            null -> {
                // START_STICKY restart - try to restore
                restoreFromPrefs()
            }
        }
        return START_STICKY
    }

    private fun startCountdown(seconds: Int) {
        // Cancel any existing countdown
        scheduler?.shutdownNow()

        totalSeconds = seconds
        remainingSeconds = seconds
        running = true
        endTimestamp = System.currentTimeMillis() + seconds * 1000L

        // Save to preferences for boot restore
        saveToPrefs()

        // Start foreground notification
        startForegroundCompat(buildNotification(remainingSeconds))

        // Schedule countdown
        scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleAtFixedRate({
                try {
                    val newRemaining = ((endTimestamp - System.currentTimeMillis()) / 1000).toInt()
                    if (newRemaining <= 0) {
                        remainingSeconds = 0
                        running = false
                        clearPrefs()
                        onTimerEnd()
                        scheduler?.shutdownNow()
                    } else {
                        remainingSeconds = newRemaining
                        updateNotification(buildNotification(newRemaining))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Timer tick error", e)
                }
            }, 0, 1, TimeUnit.SECONDS)
        }
    }

    private fun stopCountdown() {
        scheduler?.shutdownNow()
        scheduler = null
        running = false
        remainingSeconds = 0
        clearPrefs()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEndTimestamp = prefs.getLong(KEY_END_TIMESTAMP, 0)
        val savedTotalSeconds = prefs.getInt(KEY_TOTAL_SECONDS, 0)

        if (savedEndTimestamp > 0) {
            val remaining = ((savedEndTimestamp - System.currentTimeMillis()) / 1000).toInt()
            if (remaining > 0) {
                totalSeconds = savedTotalSeconds
                endTimestamp = savedEndTimestamp
                remainingSeconds = remaining
                running = true

                startForegroundCompat(buildNotification(remaining))

                scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
                    executor.scheduleAtFixedRate({
                        try {
                            val newRemaining =
                                ((endTimestamp - System.currentTimeMillis()) / 1000).toInt()
                            if (newRemaining <= 0) {
                                remainingSeconds = 0
                                running = false
                                clearPrefs()
                                onTimerEnd()
                                scheduler?.shutdownNow()
                            } else {
                                remainingSeconds = newRemaining
                                updateNotification(buildNotification(newRemaining))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Timer restore tick error", e)
                        }
                    }, 0, 1, TimeUnit.SECONDS)
                }
            } else {
                // Timer already expired while device was off
                clearPrefs()
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun onTimerEnd() {
        // Send local broadcast for TIMER_END
        val intent = Intent(ACTION_TIMER_END)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.i(TAG, "Pomodoro timer ended")

        // Stop the service after a brief moment
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveToPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_END_TIMESTAMP, endTimestamp)
            .putInt(KEY_TOTAL_SECONDS, totalSeconds)
            .apply()
    }

    private fun clearPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(remainingSeconds: Int): Notification {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeText = String.format("%02d:%02d", minutes, seconds)

        val stopIntent = Intent(this, PomodoroTimerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, POMODORO_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("番茄钟")
            .setContentText("剩余时间: $timeText")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopPendingIntent)
            .build()
    }
}