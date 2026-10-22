/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import me.rerere.rikkahub.ui.activity.AppLockAccessibilityPromptActivity

private const val TAG = "AppLockTool"

private data class InstalledAppInfo(val label: String, val packageName: String)

private fun listLaunchableApps(context: Context): List<InstalledAppInfo> {
    val pm = context.packageManager
    val selfPackage = context.packageName
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.packageName != selfPackage }
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { InstalledAppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
        .distinctBy { it.packageName }
        .toList()
}

private sealed class ResolveResult {
    data class Resolved(val packageName: String, val appName: String) : ResolveResult()
    data class Ambiguous(val candidates: List<InstalledAppInfo>) : ResolveResult()
    data class NotFound(val query: String) : ResolveResult()
}

private fun resolvePackage(context: Context, packageName: String?, appName: String?): ResolveResult {
    if (!packageName.isNullOrBlank()) {
        val label = try {
            val info: ApplicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
        return ResolveResult.Resolved(packageName, label)
    }
    if (appName.isNullOrBlank()) return ResolveResult.NotFound("")
    val apps = listLaunchableApps(context)
    val exact = apps.filter { it.label.equals(appName, ignoreCase = true) }
    if (exact.size == 1) return ResolveResult.Resolved(exact[0].packageName, exact[0].label)
    val contains = apps.filter { it.label.contains(appName, ignoreCase = true) }
    if (contains.size == 1) return ResolveResult.Resolved(contains[0].packageName, contains[0].label)
    if (contains.isNotEmpty()) return ResolveResult.Ambiguous(contains)
    return ResolveResult.NotFound(appName)
}

fun createAppLockTool(context: Context): Tool = Tool(
    name = "app_lock",
    needsApproval = true,
    description = "Lock or unlock specific apps on the device. When a locked app is opened, the user is " +
        "bounced to the home screen and shown a PIN-entry overlay before they can use it again. " +
        "This is an interception layer built on the accessibility service, NOT an OS-level 'cannot launch' " +
        "restriction - a user who disables the accessibility service can bypass it. Actions: " +
        "'set_pin' (set/change the unlock PIN, required before locking any app in require_pin=true mode, 4-6 digits), " +
        "'lock_app' (lock an app by package_name or app_name; requires a 'message' from you explaining why you locked it, " +
        "and a 'require_pin' boolean choosing whether the user can unlock by entering a PIN or only you via unlock_app), " +
        "'unlock_app' (remove an app from the locked list by package_name or app_name), " +
        "'list_locked_apps' (list currently locked apps).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "Action to perform")
                    putJsonArray("enum") {
                        add("set_pin")
                        add("lock_app")
                        add("unlock_app")
                        add("list_locked_apps")
                    }
                }
                putJsonObject("pin") {
                    put("type", "string")
                    put("description", "For 'set_pin': a 4-6 digit numeric PIN")
                }
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "For 'lock_app'/'unlock_app': exact package name. Prefer this over app_name if known.")
                }
                putJsonObject("app_name") {
                    put("type", "string")
                    put("description", "For 'lock_app'/'unlock_app': the app's display name to fuzzy-match when package_name is not known.")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "A short message you (the AI) want the user to see on the lock screen when they try to open this locked app. Required — write something in your own voice explaining why you locked it.")
                }
                putJsonObject("require_pin") {
                    put("type", "boolean")
                    put("description",
                        "Whether unlocking this app requires the user to enter a PIN on the lock screen (true), " +
                        "or whether it's a message-only block that can ONLY be removed by you calling unlock_app " +
                        "later (false). Use true when you want a real security barrier the user can pass on their own " +
                        "with the correct PIN (requires set_pin to have been called first). Use false when you want to " +
                        "personally gatekeep access — e.g. you locked an app to stop the user doing something, and you " +
                        "want them to have to come back and convince YOU to unlock it, with no technical way around it. " +
                        "In false mode, the lock screen shows only the app icon and your message — no PIN pad, no buttons " +
                        "the user can tap to unlock themselves."
                    )
                }
            },
            required = listOf("action", "message", "require_pin")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: ""
        try {
            when (action) {
                "set_pin" -> {
                    val pin = params["pin"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (!pin.matches(Regex("^\\d{4,6}$"))) {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "pin must be 4-6 digits")
                        }.toString()))
                    } else {
                        AppLockStore.setPin(context, pin)
                        Log.i(TAG, "PIN updated")
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", true)
                            put("action", "set_pin")
                        }.toString()))
                    }
                }

                "lock_app" -> {
                    val message = params["message"]?.jsonPrimitive?.contentOrNull
                    if (message.isNullOrBlank()) {
                        return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false); put("error", "message is required and cannot be blank")
                        }.toString()))
                    }
                    if (!AccessibilityServiceHandle.isEnabledInSettings(context)) {
                        context.startActivity(
                            Intent(context, AppLockAccessibilityPromptActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        var waitedMs = 0
                        val timeoutMs = 120_000 // 2分钟
                        while (!AccessibilityServiceHandle.isEnabledInSettings(context) && waitedMs < timeoutMs) {
                            delay(1000)
                            waitedMs += 1000
                        }
                        if (!AccessibilityServiceHandle.isEnabledInSettings(context)) {
                            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", false)
                                put("error", "accessibility_not_enabled")
                                put("message", "用户在2分钟内未开启无障碍权限，锁机未生效")
                            }.toString()))
                        }
                    }
                    val requirePin = params["require_pin"]?.jsonPrimitive?.booleanOrNull
                        ?: params["require_pin"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        ?: true
                    if (requirePin && !AppLockStore.hasPin(context)) {
                        return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "no_pin_set")
                            put("message", "No unlock PIN set yet. Call app_lock with action=set_pin first.")
                        }.toString()))
                    }
                    val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
                    val appName = params["app_name"]?.jsonPrimitive?.contentOrNull
                    when (val resolved = resolvePackage(context, packageName, appName)) {
                        is ResolveResult.Resolved -> {
                            if (resolved.packageName == context.packageName) {
                                listOf(UIMessagePart.Text(buildJsonObject {
                                    put("success", false)
                                    put("error", "cannot_lock_self")
                                }.toString()))
                            } else {
                                AppLockStore.lockApp(context, resolved.packageName)
                                AppLockStore.setLockMessage(context, resolved.packageName, message)
                                AppLockStore.setRequirePin(context, resolved.packageName, requirePin)
                                AppLockGuard.reArmLock(resolved.packageName)
                                AppLockGuard.refresh()
                                Log.i(TAG, "Locked app: ${resolved.packageName}")
                                listOf(UIMessagePart.Text(buildJsonObject {
                                    put("success", true)
                                    put("action", "lock_app")
                                    put("package_name", resolved.packageName)
                                    put("app_name", resolved.appName)
                                }.toString()))
                            }
                        }
                        is ResolveResult.Ambiguous -> listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "ambiguous")
                            put("candidates", buildJsonArray {
                                resolved.candidates.forEach { c ->
                                    add(buildJsonObject { put("app_name", c.label); put("package_name", c.packageName) })
                                }
                            })
                        }.toString()))
                        is ResolveResult.NotFound -> listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false); put("error", "app_not_found"); put("query", resolved.query)
                        }.toString()))
                    }
                }

                "unlock_app" -> {
                    val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
                    val appName = params["app_name"]?.jsonPrimitive?.contentOrNull
                    when (val resolved = resolvePackage(context, packageName, appName)) {
                        is ResolveResult.Resolved -> {
                            AppLockStore.unlockApp(context, resolved.packageName)
                            AppLockGuard.refresh()
                            Log.i(TAG, "Unlocked app: ${resolved.packageName}")
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", true)
                                put("action", "unlock_app")
                                put("package_name", resolved.packageName)
                                put("app_name", resolved.appName)
                            }.toString()))
                        }
                        is ResolveResult.Ambiguous -> listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "ambiguous")
                            put("candidates", buildJsonArray {
                                resolved.candidates.forEach { c ->
                                    add(buildJsonObject { put("app_name", c.label); put("package_name", c.packageName) })
                                }
                            })
                        }.toString()))
                        is ResolveResult.NotFound -> listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false); put("error", "app_not_found"); put("query", resolved.query)
                        }.toString()))
                    }
                }

                "list_locked_apps" -> {
                    val locked = AppLockStore.getLockedPackages(context)
                    val arr = buildJsonArray {
                        locked.forEach { pkg ->
                            val label = try {
                                val info = context.packageManager.getApplicationInfo(pkg, 0)
                                context.packageManager.getApplicationLabel(info).toString()
                            } catch (_: Exception) { pkg }
                            add(buildJsonObject { put("package_name", pkg); put("app_name", label) })
                        }
                    }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", true); put("count", locked.size); put("locked_apps", arr)
                    }.toString()))
                }

                else -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false); put("error", "unknown action: $action")
                }.toString()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "app_lock tool failed", e)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false); put("error", e.message ?: "Unknown error")
            }.toString()))
        }
    }
)
