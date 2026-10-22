/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.provider.Telephony
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createSmsTool(context: Context): Tool = Tool(
    name = "read_sms",
    needsApproval = true,
    description = "Read SMS messages from the device inbox. " +
        "Can filter by sender, keyword, and time range. " +
        "Returns sender, content, timestamp, and read status. " +
        "Requires READ_SMS permission to be granted.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of SMS messages to return (default 20)"))
                }
                putJsonObject("sender") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Filter by sender phone number or name (optional)"))
                }
                putJsonObject("keyword") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Filter by keyword in SMS content (optional)"))
                }
                putJsonObject("since_days") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Only return SMS from the last N days (default 7)"))
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val sender = params["sender"]?.jsonPrimitive?.contentOrNull
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
        val sinceDays = params["since_days"]?.jsonPrimitive?.intOrNull ?: 7

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        try {
            val sinceTime = System.currentTimeMillis() - (sinceDays.toLong() * 24 * 60 * 60 * 1000)

            val selection = buildString {
                append("${Telephony.Sms.DATE} >= ?")
                if (!sender.isNullOrBlank()) {
                    append(" AND ${Telephony.Sms.ADDRESS} LIKE ?")
                }
                if (!keyword.isNullOrBlank()) {
                    append(" AND ${Telephony.Sms.BODY} LIKE ?")
                }
            }

            val selectionArgs = mutableListOf<String>().apply {
                add(sinceTime.toString())
                if (!sender.isNullOrBlank()) {
                    add("%$sender%")
                }
                if (!keyword.isNullOrBlank()) {
                    add("%$keyword%")
                }
            }

            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ
                ),
                selection,
                selectionArgs.toTypedArray(),
                "${Telephony.Sms.DATE} DESC"
            )

            if (cursor == null) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", JsonPrimitive("Unable to access SMS inbox. Permission may not be granted."))
                    }.toString()
                ))
            }

            val messages = buildJsonArray {
                cursor.use {
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        val address = it.getString(0) ?: ""
                        val body = it.getString(1) ?: ""
                        val date = it.getLong(2)
                        val read = it.getInt(3)

                        add(buildJsonObject {
                            put("sender", JsonPrimitive(address))
                            put("content", JsonPrimitive(body))
                            put("date", JsonPrimitive(dateFormat.format(Date(date))))
                            put("timestamp", date)
                            put("is_read", read == 1)
                        })
                        count++
                    }
                }
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", messages.size)
                    put("messages", messages)
                }.toString()
            ))
        } catch (e: SecurityException) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", JsonPrimitive("READ_SMS permission not granted. Please grant SMS permission in app settings."))
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", JsonPrimitive(e.message ?: "Unknown error"))
                }.toString()
            ))
        }
    }
)