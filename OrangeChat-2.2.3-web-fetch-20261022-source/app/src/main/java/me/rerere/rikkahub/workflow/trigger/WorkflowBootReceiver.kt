/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared receiver for BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON.
 * Forwards to [WorkflowBootDispatcher.onBoot] so any `boot_completed` workflow fires after
 * the device boots. The dispatcher is a no-op until [TriggerRegistry.start] has bound the
 * boot trigger family; the family's cold-boot fallback then reads enabled workflows straight
 * from the repository.
 */
class WorkflowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> WorkflowBootDispatcher.onBoot()
        }
    }
}
