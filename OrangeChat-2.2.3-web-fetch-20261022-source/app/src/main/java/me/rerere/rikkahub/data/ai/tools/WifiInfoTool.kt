/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

fun createWifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info",
    description = "Get current WiFi connection info including SSID, BSSID, IP address, signal strength and link speed. Requires location permission.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {}
        )
    },
    execute = { _ ->
        if (!SystemTools.hasLocationPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "Location permission not granted (required for WiFi info on Android 10+)")
                    put("needs_permission", "ACCESS_FINE_LOCATION")
                }.toString()
            ))
        }

        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm == null) {
                Logging.log("WifiInfoTool", "ConnectivityManager unavailable")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "ConnectivityManager unavailable") }.toString()
                ))
            }

            val activeNetwork = cm?.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            if (!isWifi) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("connected", false)
                        put("message", "Not connected to WiFi")
                    }.toString()
                ))
            }

            val wm = context.getSystemService(WifiManager::class.java)
            if (wm == null) {
                Logging.log("WifiInfoTool", "WifiManager unavailable")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "WifiManager unavailable") }.toString()
                ))
            }

            val connectionInfo = wm?.connectionInfo
            val result = buildJsonObject {
                put("success", true)
                put("connected", true)

                if (connectionInfo != null) {
                    val ssid = connectionInfo.ssid ?: ""
                    val bssid = connectionInfo.bssid ?: ""
                    val ssidRedacted = ssid == "<unknown ssid>" || bssid == "02:00:00:00:00:00"
                    put("ssid_redacted", ssidRedacted)
                    if (!ssidRedacted) {
                        put("ssid", ssid.removeSurrounding("\""))
                        put("bssid", bssid)
                    }
                    put("link_speed_mbps", connectionInfo.linkSpeed)
                    put("rssi_dbm", connectionInfo.rssi)
                    put("frequency_mhz", connectionInfo.frequency)
                    put("hidden", connectionInfo.hiddenSSID)
                }

                val lp = if (activeNetwork != null) cm.getLinkProperties(activeNetwork) else null
                if (lp != null) {
                    val ip = lp.linkAddresses.firstOrNull()?.address?.hostAddress
                    if (ip != null) put("ip_address", ip)
                    put("interface_name", lp.interfaceName ?: "")
                }
            }

            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: SecurityException) {
            Logging.log("WifiInfoTool", "SecurityException: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "SecurityException: ${e.message}")
                    put("needs_permission", "ACCESS_FINE_LOCATION")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("WifiInfoTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()
            ))
        }
    }
)
