/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 保活服务重启广播接收器。
 *
 * 当用户从最近任务列表划掉 App 时，KeepAliveService.onTaskRemoved 会发送
 * [KeepAliveService.ACTION_RESTART_KEEP_ALIVE] 广播；本接收器捕获后重新拉起
 * 前台服务，以提升在小米/华为/OPPO/vivo/荣耀等国产 ROM 上的后台存活率。
 *
 * 注意：部分深度定制的 ROM 可能限制后台自启动，建议用户同时把本 App 加入
 * 系统电池优化白名单。
 */
class KeepAliveRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == KeepAliveService.ACTION_RESTART_KEEP_ALIVE) {
            Log.d(TAG, "收到保活重启广播")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, KeepAliveService::class.java))
                } else {
                    context.startService(Intent(context, KeepAliveService::class.java))
                }
                Log.d(TAG, "保活服务已通过广播重新启动")
            } catch (e: Exception) {
                Log.e(TAG, "广播重启保活服务失败", e)
            }
        }
    }
}
