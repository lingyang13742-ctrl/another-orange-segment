/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging

private fun networkTypeToString(type: Int): String = when (type) {
    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
    TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
    TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
    TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
    TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
    TelephonyManager.NETWORK_TYPE_NR -> "NR"
    else -> "Unknown($type)"
}

private fun phoneTypeToString(type: Int): String = when (type) {
    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
    TelephonyManager.PHONE_TYPE_NONE -> "NONE"
    else -> "Unknown"
}

private fun simStateToString(state: Int): String = when (state) {
    TelephonyManager.SIM_STATE_READY -> "READY"
    TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
    TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
    TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
    TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
    else -> "Unknown"
}

fun createTelephonyInfoTool(context: Context): Tool = Tool(
    name = "get_telephony_info",
    description = "Get SIM card info, carrier, and network type. Requires READ_PHONE_STATE permission.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {}
        )
    },
    execute = { _ ->
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "READ_PHONE_STATE permission not granted")
                    put("needs_permission", "READ_PHONE_STATE")
                }.toString()
            ))
        }

        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (tm == null) {
                Logging.log("TelephonyInfoTool", "TelephonyManager unavailable")
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "TelephonyManager unavailable") }.toString()
                ))
            }

            val simState = try { tm.simState } catch (e: Exception) {
                Logging.log("TelephonyInfoTool", "Error reading simState: ${e.message}")
                TelephonyManager.SIM_STATE_UNKNOWN
            }
            val hasSim = simState == TelephonyManager.SIM_STATE_READY

            val simOperator = try { tm.simOperator ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading simOperator: ${e.message}"); "" }
            val simOperatorName = try { tm.simOperatorName ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading simOperatorName: ${e.message}"); "" }
            val simCountryIso = try { tm.simCountryIso ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading simCountryIso: ${e.message}"); "" }
            val networkOperator = try { tm.networkOperator ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading networkOperator: ${e.message}"); "" }
            val networkOperatorName = try { tm.networkOperatorName ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading networkOperatorName: ${e.message}"); "" }
            val networkCountryIso = try { tm.networkCountryIso ?: "" } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading networkCountryIso: ${e.message}"); "" }

            val networkTypeNum = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType else @Suppress("DEPRECATION") tm.networkType
            } catch (e: SecurityException) {
                Logging.log("TelephonyInfoTool", "SecurityException reading dataNetworkType, falling back: ${e.message}")
                try { @Suppress("DEPRECATION") tm.networkType } catch (e2: Exception) { Logging.log("TelephonyInfoTool", "Fallback networkType also failed: ${e2.message}"); 0 }
            }

            val phoneType = try { tm.phoneType } catch (e: Exception) { Logging.log("TelephonyInfoTool", "Error reading phoneType: ${e.message}"); TelephonyManager.PHONE_TYPE_NONE }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("has_sim", hasSim)
                    put("sim_state", simStateToString(simState))
                    put("sim_operator", simOperator)
                    put("sim_operator_name", simOperatorName)
                    put("sim_country", simCountryIso)
                    put("network_operator", networkOperator)
                    put("network_operator_name", networkOperatorName)
                    put("network_country", networkCountryIso)
                    put("network_type", networkTypeToString(networkTypeNum))
                    put("phone_type", phoneTypeToString(phoneType))
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("TelephonyInfoTool", "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()
            ))
        }
    }
)
