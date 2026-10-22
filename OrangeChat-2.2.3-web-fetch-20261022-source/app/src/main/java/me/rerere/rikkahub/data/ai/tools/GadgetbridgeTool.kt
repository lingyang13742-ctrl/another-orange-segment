/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader

fun createGadgetbridgeTool(customPath: String = ""): Tool = Tool(
    name = "get_gadgetbridge_data",
    needsApproval = true,
    description = "Get health and fitness data from Gadgetbridge (wearable device companion app). " +
        "Returns step count, heart rate, sleep data, blood oxygen, stress, and calories. " +
        "Reads from Gadgetbridge's auto-exported database. " +
        "Requires storage permission and Gadgetbridge auto-export to be enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("data_type") {
                    put("type", "string")
                    put(
                        "description",
                        "Type of health data to retrieve: 'all' (default), 'steps', 'heart_rate', 'sleep', 'daily_summary'"
                    )
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("all"))
                        add(kotlinx.serialization.json.JsonPrimitive("steps"))
                        add(kotlinx.serialization.json.JsonPrimitive("heart_rate"))
                        add(kotlinx.serialization.json.JsonPrimitive("sleep"))
                        add(kotlinx.serialization.json.JsonPrimitive("daily_summary"))
                    })
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val dataType = params["data_type"]?.jsonPrimitive?.content ?: "all"

        try {
            if (!GadgetbridgeReader.dbFileExists(customPath)) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Gadgetbridge database not found. Please enable auto-export in Gadgetbridge settings. Expected path: /sdcard/Download/手环/Gadgetbridge.db")
                    }.toString()
                ))
            }

            val result = when (dataType) {
                "steps" -> {
                    val summaries = GadgetbridgeReader.readDailySummaries(7, customPath)
                    val today = summaries.lastOrNull()
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "steps")
                        put("today_steps", today?.steps ?: 0)
                        put("weekly_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    put("calories", s.calories ?: 0)
                                })
                            }
                        })
                    }.toString()
                }
                "heart_rate" -> {
                    val latest = GadgetbridgeReader.readLatestActivitySample(customPath)
                    val summaries = GadgetbridgeReader.readDailySummaries(7, customPath)
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "heart_rate")
                        put("current_heart_rate", latest?.heartRate ?: 0)
                        put("daily_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("hr_max", s.hrMax ?: 0)
                                    put("hr_min", s.hrMin ?: 0)
                                    put("hr_avg", s.hrAvg ?: 0)
                                    put("hr_resting", s.hrResting ?: 0)
                                })
                            }
                        })
                    }.toString()
                }
                "sleep" -> {
                    val sleepSummaries = GadgetbridgeReader.readSleepSummaries(3, customPath)
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "sleep")
                        put("recent_sleep_list", buildSleepSessionList(sleepSummaries))
                    }.toString()
                }
                "daily_summary" -> {
                    val summaries = GadgetbridgeReader.readDailySummaries(7, customPath)
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "daily_summary")
                        put("summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    put("calories", s.calories ?: 0)
                                    put("hr_max", s.hrMax ?: 0)
                                    put("hr_min", s.hrMin ?: 0)
                                    put("hr_avg", s.hrAvg ?: 0)
                                    put("hr_resting", s.hrResting ?: 0)
                                    put("stress_avg", s.stressAvg ?: 0)
                                    put("spo2_avg", s.spo2Avg ?: 0)
                                })
                            }
                        })
                    }.toString()
                }
                else -> {
                    // "all" - return combined data
                    val latest = GadgetbridgeReader.readLatestActivitySample(customPath)
                    val summaries = GadgetbridgeReader.readDailySummaries(7, customPath)
                    val sleepSummaries = GadgetbridgeReader.readSleepSummaries(3, customPath)
                    val (spo2, stress) = GadgetbridgeReader.readLatestSpo2AndStress(customPath)
                    val today = summaries.lastOrNull()
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "all")
                        // Current status
                        put("current_heart_rate", latest?.heartRate ?: 0)
                        put("current_spo2", spo2 ?: 0)
                        put("current_stress", stress ?: 0)
                        // Today's summary
                        put("today_steps", today?.steps ?: 0)
                        put("today_calories", today?.calories ?: 0)
                        // Sleep data
                        put("recent_sleep_list", buildSleepSessionList(sleepSummaries))
                        // Weekly summaries
                        put("weekly_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    put("calories", s.calories ?: 0)
                                    put("hr_max", s.hrMax ?: 0)
                                    put("hr_min", s.hrMin ?: 0)
                                    put("hr_avg", s.hrAvg ?: 0)
                                    put("stress_avg", s.stressAvg ?: 0)
                                    put("spo2_avg", s.spo2Avg ?: 0)
                                })
                            }
                        })
                    }.toString()
                }
            }

            listOf(UIMessagePart.Text(result))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error reading Gadgetbridge data")
                }.toString()
            ))
        }
    }
)

/**
 * 将睡眠摘要列表构建为 JSON 数组。
 * 每条包含：type（"nap" 或 "sleep"）、start、end、total_minutes、duration_text，
 * 非小憩额外加 deep_sleep_minutes、light_sleep_minutes、rem_sleep_minutes。
 */
private fun buildSleepSessionList(
    sleepSummaries: List<me.rerere.rikkahub.data.gadgetbridge.SleepSummary>,
): kotlinx.serialization.json.JsonArray {
    val sdf = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
    return kotlinx.serialization.json.buildJsonArray {
        sleepSummaries.forEach { summary ->
            add(buildJsonObject {
                put("type", if (summary.isNap) "nap" else "sleep")
                put("start", sdf.format(Date(summary.timestamp)))
                put("end", sdf.format(Date(summary.wakeupTime)))
                put("total_minutes", summary.totalDuration)
                val hours = summary.totalDuration / 60
                val mins = summary.totalDuration % 60
                put("duration_text", "${hours}h ${mins}min")
                if (!summary.isNap) {
                    put("deep_sleep_minutes", summary.deepSleep)
                    put("light_sleep_minutes", summary.lightSleep)
                    put("rem_sleep_minutes", summary.remSleep)
                }
            })
        }
    }
}