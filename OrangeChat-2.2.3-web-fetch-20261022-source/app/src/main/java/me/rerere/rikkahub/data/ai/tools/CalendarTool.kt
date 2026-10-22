/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
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
import java.util.TimeZone

private fun getDefaultCalendarId(context: Context): Long? {
    val cursor = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.IS_PRIMARY} = 1",
        null,
        null
    )
    cursor?.use {
        if (it.moveToFirst()) return it.getLong(0)
    }
    // Fallback: get first writable calendar
    val cursor2 = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        null, null, null
    )
    cursor2?.use {
        if (it.moveToFirst()) return it.getLong(0)
    }
    return null
}

fun createCalendarTool(context: Context): Tool = Tool(
    name = "calendar_tool",
    needsApproval = true,
    description = "Read and manage calendar events on the device. " +
        "Supports reading events in a time range, creating new events, and deleting events by ID. " +
        "Requires READ_CALENDAR and WRITE_CALENDAR permissions.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action to perform: 'read' (query events), 'create' (add new event), 'delete' (remove event by ID)"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("read"))
                        add(JsonPrimitive("create"))
                        add(JsonPrimitive("delete"))
                    })
                }
                putJsonObject("start_time_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Start time in ms since epoch. For 'read': query start (default: now). For 'create': event start (required)."))
                }
                putJsonObject("end_time_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("End time in ms since epoch. For 'read': query end (default: 7 days from now). For 'create': event end (required)."))
                }
                putJsonObject("title") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event title. Required for 'create' action."))
                }
                putJsonObject("description") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event description. Optional for 'create' action."))
                }
                putJsonObject("calendar_id") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Calendar ID. For 'create': target calendar (default: primary). For 'read': filter by calendar (optional)."))
                }
                putJsonObject("event_id") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Event ID. Required for 'delete' action."))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Max events to return for 'read' action (default 20)"))
                }
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: ""
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        try {
            when (action) {
                "read" -> {
                    val now = System.currentTimeMillis()
                    val startTimeMs = params["start_time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now
                    val endTimeMs = params["end_time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: (now + 7L * 24 * 60 * 60 * 1000)
                    val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
                    val calendarId = params["calendar_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

                    val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                        .appendPath(startTimeMs.toString())
                        .appendPath(endTimeMs.toString())
                        .build()

                    val cursor = context.contentResolver.query(
                        instancesUri,
                        arrayOf(
                            CalendarContract.Instances.EVENT_ID,
                            CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.DESCRIPTION,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.END,
                            CalendarContract.Instances.CALENDAR_ID,
                            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                            CalendarContract.Instances.EVENT_LOCATION
                        ),
                        if (calendarId != null) "${CalendarContract.Instances.CALENDAR_ID} = ?" else null,
                        if (calendarId != null) arrayOf(calendarId.toString()) else null,
                        "${CalendarContract.Instances.BEGIN} ASC"
                    )

                    if (cursor == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("Unable to access calendar. Permission may not be granted."))
                            }.toString()
                        ))
                    }

                    val events = buildJsonArray {
                        cursor.use {
                            var count = 0
                            while (it.moveToNext() && count < limit) {
                                add(buildJsonObject {
                                    put("event_id", it.getLong(0))
                                    put("title", JsonPrimitive(it.getString(1) ?: ""))
                                    put("description", JsonPrimitive(it.getString(2) ?: ""))
                                    put("start_time", JsonPrimitive(dateFormat.format(Date(it.getLong(3)))))
                                    put("end_time", JsonPrimitive(dateFormat.format(Date(it.getLong(4)))))
                                    put("start_time_ms", it.getLong(3))
                                    put("end_time_ms", it.getLong(4))
                                    put("calendar_id", it.getLong(5))
                                    put("calendar_name", JsonPrimitive(it.getString(6) ?: ""))
                                    put("location", JsonPrimitive(it.getString(7) ?: ""))
                                })
                                count++
                            }
                        }
                    }

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive("read"))
                            put("count", events.size)
                            put("events", events)
                        }.toString()
                    ))
                }

                "create" -> {
                    val title = params["title"]?.jsonPrimitive?.contentOrNull
                    if (title.isNullOrBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("Title is required for creating an event"))
                            }.toString()
                        ))
                    }
                    val startTimeMs = params["start_time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val endTimeMs = params["end_time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (startTimeMs == null || endTimeMs == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("start_time_ms and end_time_ms are required for creating an event"))
                            }.toString()
                        ))
                    }
                    val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val calendarId = params["calendar_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: getDefaultCalendarId(context)
                        ?: return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("No writable calendar found on device"))
                            }.toString()
                        ))

                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DESCRIPTION, description)
                        put(CalendarContract.Events.DTSTART, startTimeMs)
                        put(CalendarContract.Events.DTEND, endTimeMs)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    if (uri != null) {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("action", JsonPrimitive("create"))
                                put("event_id", uri.lastPathSegment?.toLongOrNull() ?: -1)
                                put("title", JsonPrimitive(title))
                                put("start_time", JsonPrimitive(dateFormat.format(Date(startTimeMs))))
                                put("end_time", JsonPrimitive(dateFormat.format(Date(endTimeMs))))
                                put("calendar_id", calendarId)
                                put("message", JsonPrimitive("Event created successfully"))
                            }.toString()
                        ))
                    } else {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("Failed to create event"))
                            }.toString()
                        ))
                    }
                }

                "delete" -> {
                    val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (eventId == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("event_id is required for deleting an event"))
                            }.toString()
                        ))
                    }

                    val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                        .appendPath(eventId.toString())
                        .build()
                    val deletedRows = context.contentResolver.delete(uri, null, null)

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", deletedRows > 0)
                            put("action", JsonPrimitive("delete"))
                            put("event_id", eventId)
                            if (deletedRows > 0) put("message", JsonPrimitive("Event deleted successfully")) else put("error", JsonPrimitive("Event not found or already deleted"))
                        }.toString()
                    ))
                }

                else -> listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", JsonPrimitive("Unknown action: $action. Supported: read, create, delete"))
                    }.toString()
                ))
            }
        } catch (e: SecurityException) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", JsonPrimitive("Calendar permission not granted: ${e.message}"))
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