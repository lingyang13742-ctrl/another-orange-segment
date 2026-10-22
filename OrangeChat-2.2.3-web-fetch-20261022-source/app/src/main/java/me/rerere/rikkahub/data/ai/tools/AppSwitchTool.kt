/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity

private const val TAG = "AppSwitchTool"

/**
 * 创建应用切换工具
 * - action: "launch_app" - 启动指定包名的应用
 * - action: "bring_chat_to_front" - 把橘瓣聊天界面拉到屏幕最前面
 * - action: "open_url" - 用浏览器打开指定URL
 */
fun createAppSwitchTool(context: Context): Tool = Tool(
    name = "app_switch",
    needsApproval = true,
    description = "Switch the device screen to another app or bring the chat app to the front. " +
        "Actions: 'launch_app' (launch an app by package name), " +
        "'bring_chat_to_front' (bring this chat app to the foreground), " +
        "'open_url' (open a URL in the browser). " +
        "Use this when the user asks to open another app, or when you want to pull the user back to the chat.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "Action to perform: 'launch_app', 'bring_chat_to_front', or 'open_url'")
                    putJsonArray("enum") {
                        add("launch_app")
                        add("bring_chat_to_front")
                        add("open_url")
                    }
                }
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "For 'launch_app': the package name of the app to launch (e.g. com.tencent.mm for WeChat, com.netease.cloudmusic for NetEase Music)")
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "For 'open_url': the URL to open in browser")
                }
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.toString()?.trim('"') ?: ""
        try {
            when (action) {
                "launch_app" -> {
                    val packageName = params["package_name"]?.toString()?.trim('"') ?: ""
                    if (packageName.isBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "package_name is required for launch_app") }.toString()
                        ))
                    }
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Log.d(TAG, "Launched app: $packageName")
                        listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", true); put("action", "launch_app"); put("package_name", packageName) }.toString()
                        ))
                    } else {
                        listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "App not found: $packageName") }.toString()
                        ))
                    }
                }
                "bring_chat_to_front" -> {
                    val intent = Intent(context, RouteActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                    Log.d(TAG, "Brought chat to front")
                    listOf(UIMessagePart.Text(
                        buildJsonObject { put("success", true); put("action", "bring_chat_to_front") }.toString()
                    ))
                }
                "open_url" -> {
                    val url = params["url"]?.toString()?.trim('"') ?: ""
                    if (url.isBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "url is required for open_url") }.toString()
                        ))
                    }
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.d(TAG, "Opened URL: $url")
                    listOf(UIMessagePart.Text(
                        buildJsonObject { put("success", true); put("action", "open_url"); put("url", url) }.toString()
                    ))
                }
                else -> {
                    listOf(UIMessagePart.Text(
                        buildJsonObject { put("success", false); put("error", "Unknown action: $action") }.toString()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "App switch failed", e)
            listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()
            ))
        }
    }
)