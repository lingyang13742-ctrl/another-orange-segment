/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.condition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.workflow.model.WorkflowContext

/**
 * Phase 12 — builds a [WorkflowContext] snapshot at evaluation time.
 *
 * Cheap fields (battery, charging, screen, wifi SSID best-effort) are always populated.
 * Expensive fields (location, foreground app, last-chat time) are populated only on request —
 * the engine passes flags indicating which the conditions actually need, so we don't pay for a
 * location lookup or DB query on a workflow that only checks battery.
 */
class ContextProvider(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) {

    suspend fun snapshot(
        needsLocation: Boolean = false,
        needsLastChat: Boolean = false,
    ): WorkflowContext {
        val now = System.currentTimeMillis()
        val (level, charging) = batteryStatus()
        val ssid = currentWifiSsid()
        val screenOn = isScreenOn()
        val foregroundPackage = me.rerere.rikkahub.workflow.trigger.AppForegroundLastKnown.value
        val (lat, lng) = if (needsLocation) lastKnownLocation() else (null to null)
        val lastChat = if (needsLastChat) lastChatMessageMs() else null
        return WorkflowContext(
            nowMs = now,
            batteryLevel = level,
            isCharging = charging,
            wifiSsid = ssid,
            foregroundPackage = foregroundPackage,
            screenOn = screenOn,
            latitude = lat,
            longitude = lng,
            lastChatMs = lastChat,
        )
    }

    /**
     * Epoch ms of the last message in the current assistant's most-recent conversation.
     * Mirrors [me.rerere.rikkahub.data.service.ProactiveMessageService.getLastMessageTime]
     * but lives here so the workflow condition evaluator can access it without going through
     * the proactive-message service. Returns null when there is no conversation / no message.
     */
    @SuppressLint("VisibleForTests")
    private suspend fun lastChatMessageMs(): Long? = try {
        val assistantId = settingsStore.settingsFlow.first().assistantId
        val recent = conversationRepository.getRecentConversations(assistantId, limit = 1)
        if (recent.isEmpty()) null
        else {
            val conv = recent.first()
            val fullConv = conversationRepository.getConversationById(conv.id)
            val createdAt: LocalDateTime? =
                fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
            createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "lastChatMessageMs: lookup failed", e)
        null
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Pair<Double?, Double?> {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null to null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null to null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                return loc.latitude to loc.longitude
            } catch (e: Throwable) {
                // permission revoked between the check and the call — give up
                Log.w(TAG, "lastKnownLocation: provider=$p lookup failed", e)
                return null to null
            }
        }
        return null to null
    }

    private fun batteryStatus(): Pair<Int?, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100 / scale).coerceIn(0, 100) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf {
            it.isNotBlank() && it != "<unknown ssid>"
        }
    } catch (e: Throwable) {
        Log.w(TAG, "currentWifiSsid: lookup failed", e)
        null
    }

    private fun isScreenOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    private companion object {
        private const val TAG = "WorkflowContextProvider"
    }
}
