/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.activity.AppLockUnlockActivity
import me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher

object AppLockGuard {
    private const val TAG = "AppLockGuard"
    private const val REENTRY_GUARD_MS = 1_000L

    private lateinit var appContext: Context
    private val unlockedPackages = java.util.concurrent.CopyOnWriteArraySet<String>()

    @Volatile private var listening = false
    @Volatile private var lastInterceptAt: Long = 0L

    private val listener: (String?) -> Unit = { pkg -> onForegroundChange(pkg) }

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        if (!::appContext.isInitialized) return
        val shouldListen = AppLockStore.getLockedPackages(appContext).isNotEmpty()
        if (shouldListen && !listening) {
            AppForegroundDispatcher.addListener(listener)
            listening = true
        } else if (!shouldListen && listening) {
            AppForegroundDispatcher.removeListener(listener)
            listening = false
        }
    }

    fun grantGraceUnlock(packageName: String) {
        unlockedPackages.add(packageName)
    }

    fun reArmLock(packageName: String) {
        unlockedPackages.remove(packageName)
    }

    fun goHome() {
        if (!::appContext.isInitialized) return
        runCatching {
            RikkaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }.onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME failed", it) }
    }

    private fun onForegroundChange(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (!::appContext.isInitialized) return
        if (pkg == appContext.packageName) return

        val locked = AppLockStore.getLockedPackages(appContext)
        if (pkg !in locked) return
        if (pkg in unlockedPackages) return

        val now = System.currentTimeMillis()
        if (now - lastInterceptAt < REENTRY_GUARD_MS) return
        lastInterceptAt = now

        Log.i(TAG, "Intercepting locked app: $pkg")
        runCatching {
            val intent = Intent(appContext, AppLockUnlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(AppLockUnlockActivity.EXTRA_TARGET_PACKAGE, pkg)
            }
            appContext.startActivity(intent)
        }.onFailure { Log.e(TAG, "Failed to start unlock activity", it) }
    }
}