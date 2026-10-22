/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.service.AmapService
import me.rerere.rikkahub.data.service.DeviceLocationFetcher
import me.rerere.rikkahub.data.service.RikkaNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
sealed class SystemToolOption {
    @Serializable @SerialName("location") data object Location : SystemToolOption()
    @Serializable @SerialName("notifications") data object Notifications : SystemToolOption()
    @Serializable @SerialName("app_usage") data object AppUsage : SystemToolOption()
    @Serializable @SerialName("camera") data object Camera : SystemToolOption()
    @Serializable @SerialName("explore_nearby") data object ExploreNearby : SystemToolOption()
    @Serializable @SerialName("gadgetbridge") data object Gadgetbridge : SystemToolOption()
    @Serializable @SerialName("alarm") data object Alarm : SystemToolOption()
    @Serializable @SerialName("timer") data object Timer : SystemToolOption()
    @Serializable @SerialName("battery") data object Battery : SystemToolOption()
    @Serializable @SerialName("music") data object Music : SystemToolOption()
    @Serializable @SerialName("sms") data object Sms : SystemToolOption()
    @Serializable @SerialName("supabase_query") data object SupabaseQuery : SystemToolOption()
    @Serializable @SerialName("torch") data object Torch : SystemToolOption()
    @Serializable @SerialName("toast") data object Toast : SystemToolOption()
    @Serializable @SerialName("vibrate") data object Vibrate : SystemToolOption()
    @Serializable @SerialName("brightness") data object Brightness : SystemToolOption()
    @Serializable @SerialName("volume") data object Volume : SystemToolOption()
    @Serializable @SerialName("wifi_info") data object WifiInfo : SystemToolOption()
    @Serializable @SerialName("telephony_info") data object TelephonyInfo : SystemToolOption()
    @Serializable @SerialName("share") data object Share : SystemToolOption()
    @Serializable @SerialName("set_wallpaper") data object SetWallpaper : SystemToolOption()
    @Serializable @SerialName("wake_screen") data object WakeScreen : SystemToolOption()
    @Serializable @SerialName("scan_media") data object ScanMedia : SystemToolOption()
    @Serializable @SerialName("post_notification") data object PostNotification : SystemToolOption()
    @Serializable @SerialName("storage_info") data object StorageInfo : SystemToolOption()
    @Serializable @SerialName("app_switch") data object AppSwitch : SystemToolOption()
    @Serializable @SerialName("app_lock") data object AppLock : SystemToolOption()
    @Serializable @SerialName("fingerprint") data object Fingerprint : SystemToolOption()
}

class SystemTools(private val context: Context, private val settings: Settings) {

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasNotificationPermission(context: Context): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true

        fun hasAppUsagePermission(context: Context): Boolean =
            (context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager)
                .checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    // ==================== 位置工具 ====================

    val locationTool: Tool by lazy {
        Tool(
            name = "get_location",
            description = "Get the current device location with coordinates and address. Uses Amap API for reverse geocoding if API key is configured.",
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        putJsonObject("include_address") {
                            put("type", "boolean")
                            put("description", "Whether to include address info (reverse geocoding)")
                        }
                    }
                )
            },
            execute = { _ ->
                if (!hasLocationPermission(context)) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject { put("success", false); put("error", "Location permission not granted") }.toString()
                    ))
                }
                try {
                    val fetched = DeviceLocationFetcher.fetch(context)

                    if (fetched == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "Unable to get location") }.toString()
                        ))
                    }
                    val loc = fetched.location

                    val result = buildJsonObject {
                        put("success", true)
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("altitude", loc.altitude)
                        put("accuracy", loc.accuracy.toDouble())
                        put("timestamp", loc.time)
                        put("time", dateFormat.format(Date(loc.time)))
                        // 明确告知 AI 这份数据是不是刚定位到的，避免 AI 把过期缓存当实时位置
                        put("is_fresh", fetched.isFresh)

                        val apiKey = settings.systemToolsSetting.amapApiKey
                        var addressResolved = false

                        if (apiKey.isNotBlank()) {
                            try {
                                val amapService = AmapService(apiKey)
                                val addressResult = amapService.getAddressFromGps(loc.latitude, loc.longitude)
                                if (addressResult.success) {
                                    addressResolved = true
                                    put("address", addressResult.formattedAddress ?: "")
                                    put("province", addressResult.province ?: "")
                                    put("city", addressResult.city ?: "")
                                    put("district", addressResult.district ?: "")
                                    put("street", addressResult.street ?: "")
                                    put("neighborhood", addressResult.neighborhood ?: "")
                                    put("building", addressResult.building ?: "")
                                }
                            } catch (_: Exception) { }
                        }

                        if (!addressResolved) {
                            try {
                                val geocoder = Geocoder(context, Locale.getDefault())
                                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                                if (!addresses.isNullOrEmpty()) {
                                    val addr = addresses[0]
                                    val addressLines = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
                                    put("address", addressLines.joinToString(", ").ifBlank { addr.featureName ?: "" })
                                    put("country", addr.countryName ?: "")
                                    put("province", addr.adminArea ?: "")
                                    put("city", addr.locality ?: "")
                                    put("district", addr.subLocality ?: "")
                                    put("street", addr.thoroughfare ?: "")
                                } else {
                                    put("address", "Unknown address")
                                }
                            } catch (e: Exception) {
                                put("address", "Unknown address (geocoder failed: ${e.message})")
                            }
                        }
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                } catch (e: Exception) {
                    listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()))
                }
            }
        )
    }

    // ==================== 通知工具 ====================

    val notificationsTool: Tool by lazy {
        Tool(
            name = "get_notifications",
            description = "Get today's notifications from the device. Returns notification titles, content, app names, and timestamps.",
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "Maximum number of notifications to return (default 20)")
                        }
                    }
                )
            },
            execute = { args ->
                val params = args.jsonObject
                try {
                    val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
                    val notifications = RikkaNotificationListenerService.getTodayNotifications().take(limit)

                    if (notifications.isEmpty()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", true); put("count", 0); put("message", "No notifications found for today") }.toString()
                        ))
                    }

                    val arr = kotlinx.serialization.json.buildJsonArray {
                        notifications.forEach { notif ->
                            add(buildJsonObject {
                                put("app_name", notif.appName)
                                put("package_name", notif.packageName)
                                put("title", notif.title)
                                put("content", notif.content)
                                put("time", dateFormat.format(Date(notif.timestamp)))
                                put("category", notif.category ?: "")
                            })
                        }
                    }

                    listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("count", notifications.size); put("notifications", arr) }.toString()))
                } catch (e: Exception) {
                    listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()))
                }
            }
        )
    }

    // ==================== Supabase 查询工具 ====================

    private val supabaseQueryTool by lazy {
        Tool(
            name = "supabase_query",
            description = "Query data from Supabase tables. Supports two operations: (1) query_recent_messages - get the most recent N rows from a table ordered by created_at descending; (2) search_messages - search rows containing a keyword using case-insensitive matching on the content column.",
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        putJsonObject("operation") {
                            put("type", "string")
                            put("description", "Operation type: 'query_recent_messages' or 'search_messages'")
                            putJsonArray("enum") {
                                add("query_recent_messages")
                                add("search_messages")
                            }
                        }
                        putJsonObject("table") {
                            put("type", "string")
                            put("description", "Table name to query. Common tables: 'chat_messages' (chat history), 'memory_summaries' (diary summaries), 'device_data' (device events). Defaults to 'chat_messages'.")
                        }
                        putJsonObject("count") {
                            put("type", "integer")
                            put("description", "For query_recent_messages: number of recent rows to return (default 10, max 50)")
                        }
                        putJsonObject("keyword") {
                            put("type", "string")
                            put("description", "For search_messages: keyword to search in the content column")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "For search_messages: maximum results to return (default 10, max 50)")
                        }
                    },
                    required = listOf("operation")
                )
            },
            execute = { args ->
                val params = args.jsonObject
                val operation = params["operation"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "Missing required parameter 'operation'")
                    }.toString()))

                val externalMemories = settings.externalMemories.filter { it.enabled }
                if (externalMemories.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "No enabled external memory configured. Please add a Supabase external memory in settings.")
                    }.toString()))
                }

                val table = params["table"]?.jsonPrimitive?.contentOrNull ?: "chat_messages"
                val memory = externalMemories.firstOrNull { it.tableName == table || it.summariesTableName == table }
                    ?: externalMemories.first()
                val baseUrl = memory.supabaseUrl.trimEnd('/')
                val apiKey = memory.supabaseKey

                try {
                    when (operation) {
                        "query_recent_messages" -> {
                            val count = (params["count"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
                            val url = java.net.URL("$baseUrl/rest/v1/$table?select=*&order=created_at.desc&limit=$count")
                            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("apikey", apiKey)
                                setRequestProperty("Authorization", "Bearer ${apiKey}")
                                setRequestProperty("Accept", "application/json")
                                connectTimeout = 15000
                                readTimeout = 15000
                            }
                            val responseCode = connection.responseCode
                            val responseText = if (responseCode in 200..299) {
                                connection.inputStream.bufferedReader().readText()
                            } else {
                                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                            }
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", responseCode in 200..299)
                                put("operation", "query_recent_messages")
                                put("table", table)
                                put("count_requested", count)
                                if (responseCode !in 200..299) put("error", "HTTP $responseCode: $responseText")
                                else put("data", Json.parseToJsonElement(responseText))
                            }.toString()))
                        }
                        "search_messages" -> {
                            val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
                                ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                                    put("success", false)
                                    put("error", "Missing required parameter 'keyword' for search_messages")
                                }.toString()))
                            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
                            // Use ilike for case-insensitive search on content column
                            val encodedKeyword = java.net.URLEncoder.encode("%$keyword%", "UTF-8")
                            val url = java.net.URL("$baseUrl/rest/v1/$table?select=*&content=ilike.$encodedKeyword&limit=$limit")
                            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("apikey", apiKey)
                                setRequestProperty("Authorization", "Bearer ${apiKey}")
                                setRequestProperty("Accept", "application/json")
                                connectTimeout = 15000
                                readTimeout = 15000
                            }
                            val responseCode = connection.responseCode
                            val responseText = if (responseCode in 200..299) {
                                connection.inputStream.bufferedReader().readText()
                            } else {
                                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                            }
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", responseCode in 200..299)
                                put("operation", "search_messages")
                                put("table", table)
                                put("keyword", keyword)
                                put("limit_requested", limit)
                                if (responseCode !in 200..299) put("error", "HTTP $responseCode: $responseText")
                                else put("data", Json.parseToJsonElement(responseText))
                            }.toString()))
                        }
                        else -> listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "Unknown operation: $operation. Use 'query_recent_messages' or 'search_messages'.")
                        }.toString()))
                    }
                } catch (e: Exception) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", e.message ?: "Unknown error")
                    }.toString()))
                }
            }
        )
    }

    // ==================== 外部工具实例 ====================

    private val appUsageTool by lazy { createAppUsageTool(context) }
    private val exploreNearbyTool by lazy { createExploreNearbyTool(context, settings) }
    private val cameraTool by lazy { createCameraTool(context) }
    private val gadgetbridgeTool by lazy { createGadgetbridgeTool(settings.systemToolsSetting.gadgetbridgeDbPath) }
    private val alarmTool by lazy { createAlarmTool(context) }
    private val timerTool by lazy { createTimerTool(context) }
    private val batteryTool by lazy { createBatteryTool(context) }
    private val musicTool by lazy { createMusicTool(context) }
    private val smsTool by lazy { createSmsTool(context) }

    // 新增工具实例
    private val torchTool by lazy { createTorchTool(context) }
    private val toastTool by lazy { createToastTool(context) }
    private val vibrateTool by lazy { createVibrateTool(context) }
    private val getBrightnessTool by lazy { createGetBrightnessTool(context) }
    private val setBrightnessTool by lazy { createSetBrightnessTool(context) }
    private val getVolumeTool by lazy { createGetVolumeTool(context) }
    private val setVolumeTool by lazy { createSetVolumeTool(context) }
    private val wifiInfoTool by lazy { createWifiInfoTool(context) }
    private val telephonyInfoTool by lazy { createTelephonyInfoTool(context) }
    private val shareTool by lazy { createShareTool(context) }
    private val wakeScreenTool by lazy { createWakeScreenTool(context) }
    private val mediaScannerTool by lazy { createMediaScannerTool(context) }
    private val notificationPostTool by lazy { createNotificationPostTool(context) }
    private val storageInfoTool by lazy { createStorageInfoTool(context) }
    private val appSwitchTool by lazy { createAppSwitchTool(context) }
    private val appLockTool by lazy { createAppLockTool(context) }
    // 指纹验证: 共用 BiometricPromptActivity.buffer 单例, 保证工具与弹窗 Activity 同一个 buffer
    private val fingerprintTool by lazy {
        me.rerere.rikkahub.data.ai.tools.local.fingerprintTool(
            context,
            me.rerere.rikkahub.ui.activity.BiometricPromptActivity.buffer,
        )
    }

    // ==================== 获取工具列表 ====================

    fun getTools(
        enabledTools: Set<SystemToolOption>,
        recentMessages: List<UIMessage> = emptyList(),
        filesManager: FilesManager? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (SystemToolOption.Location in enabledTools) tools.add(locationTool)
        if (SystemToolOption.Notifications in enabledTools) tools.add(notificationsTool)
        if (SystemToolOption.AppUsage in enabledTools) tools.add(appUsageTool)
        if (SystemToolOption.ExploreNearby in enabledTools) tools.add(exploreNearbyTool)
        if (SystemToolOption.Camera in enabledTools) tools.add(cameraTool)
        if (SystemToolOption.Gadgetbridge in enabledTools) tools.add(gadgetbridgeTool)
        if (SystemToolOption.Alarm in enabledTools) tools.add(alarmTool)
        if (SystemToolOption.Timer in enabledTools) tools.add(timerTool)
        if (SystemToolOption.Battery in enabledTools) tools.add(batteryTool)
        if (SystemToolOption.Music in enabledTools) tools.add(musicTool)
        if (SystemToolOption.Sms in enabledTools) tools.add(smsTool)
        if (SystemToolOption.SupabaseQuery in enabledTools) tools.add(supabaseQueryTool)
        if (SystemToolOption.Torch in enabledTools) tools.add(torchTool)
        if (SystemToolOption.Toast in enabledTools) tools.add(toastTool)
        if (SystemToolOption.Vibrate in enabledTools) tools.add(vibrateTool)
        if (SystemToolOption.Brightness in enabledTools) {
            tools.add(getBrightnessTool)
            tools.add(setBrightnessTool)
        }
        if (SystemToolOption.Volume in enabledTools) {
            tools.add(getVolumeTool)
            tools.add(setVolumeTool)
        }
        if (SystemToolOption.WifiInfo in enabledTools) tools.add(wifiInfoTool)
        if (SystemToolOption.TelephonyInfo in enabledTools) tools.add(telephonyInfoTool)
        if (SystemToolOption.Share in enabledTools) tools.add(shareTool)
        if (SystemToolOption.SetWallpaper in enabledTools) tools.add(createSetWallpaperTool(context, recentMessages, filesManager))
        if (SystemToolOption.WakeScreen in enabledTools) tools.add(wakeScreenTool)
        if (SystemToolOption.ScanMedia in enabledTools) tools.add(mediaScannerTool)
        if (SystemToolOption.PostNotification in enabledTools) tools.add(notificationPostTool)
        if (SystemToolOption.StorageInfo in enabledTools) tools.add(storageInfoTool)
        if (SystemToolOption.AppSwitch in enabledTools) tools.add(appSwitchTool)
        if (SystemToolOption.AppLock in enabledTools) tools.add(appLockTool)
        if (SystemToolOption.Fingerprint in enabledTools) tools.add(fingerprintTool)
        return tools
    }
}
