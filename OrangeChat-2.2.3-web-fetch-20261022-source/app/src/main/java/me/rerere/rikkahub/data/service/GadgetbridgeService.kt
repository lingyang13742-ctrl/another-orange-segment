/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rikka.shizuku.Shizuku
import java.io.File

class GadgetbridgeService {

    companion object {
        private const val TAG = "GadgetbridgeService"
        private const val GB_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
        private const val DB_PATH = "/data/data/$GB_PACKAGE/databases/Gadgetbridge.db"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 10001

        fun isShizukuAvailable(): Boolean {
            return try {
                val uid = Shizuku.getUid()
                Log.d(TAG, "isShizukuAvailable: uid=$uid")
                uid != -1
            } catch (e: Exception) {
                Log.e(TAG, "isShizukuAvailable: exception", e)
                false
            }
        }

        fun hasShizukuPermission(): Boolean {
            return try {
                val result = Shizuku.checkSelfPermission()
                Log.d(TAG, "hasShizukuPermission: checkSelfPermission result=$result, PERMISSION_GRANTED=${android.content.pm.PackageManager.PERMISSION_GRANTED}")
                result == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.e(TAG, "hasShizukuPermission: exception", e)
                false
            }
        }

        /**
         * Request Shizuku permission via API.
         * This will show a system dialog asking the user to grant permission.
         */
        fun requestShizukuPermission() {
            try {
                val available = isShizukuAvailable()
                val permitted = hasShizukuPermission()
                Log.d(TAG, "requestShizukuPermission: available=$available, permitted=$permitted")
                if (available && !permitted) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    Log.d(TAG, "requestShizukuPermission: requestPermission called")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
            }
        }
    }

    /**
     * Copy Gadgetbridge database to app's private directory via Shizuku,
     * then query using Android's built-in SQLite API.
     * This avoids depending on the sqlite3 command-line tool which is not available on most devices.
     */
    private suspend fun executeQuery(sql: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isShizukuAvailable()) {
                throw Exception("Shizuku is not running. Please start Shizuku first.")
            }
            if (!hasShizukuPermission()) {
                throw Exception("Shizuku permission not granted.")
            }

            Log.d(TAG, "executeQuery: sql=$sql")

            // Copy database to app's cache directory using Shizuku
            val context = me.rerere.rikkahub.RikkaHubApp.INSTANCE
                ?: throw Exception("Application context not available")
            val cacheDbFile = File(context.cacheDir, "gadgetbridge_query.db")

            // Use Shizuku to copy the database file
            // First try direct copy (works if Shizuku has root), then fallback to run-as
            val dstPath = cacheDbFile.absolutePath
            val directCopy = Shizuku.newProcess(
                arrayOf("sh", "-c", "cat $DB_PATH > $dstPath && chmod 644 $dstPath"),
                null, null
            )
            directCopy.waitFor()
            val directCopyError = directCopy.errorStream.bufferedReader().readText().trim()

            if (directCopy.exitValue() != 0) {
                Log.w(TAG, "Direct copy failed: $directCopyError, trying run-as fallback...")
                // Fallback: use run-as to copy as Gadgetbridge's user
                val runAsCopy = Shizuku.newProcess(
                    arrayOf("sh", "-c", "run-as $GB_PACKAGE cat databases/Gadgetbridge.db > $dstPath && chmod 644 $dstPath"),
                    null, null
                )
                runAsCopy.waitFor()
                val runAsError = runAsCopy.errorStream.bufferedReader().readText().trim()

                if (runAsCopy.exitValue() != 0) {
                    Log.e(TAG, "run-as copy also failed: $runAsError")
                    // Last fallback: try with su if available
                    val suCopy = Shizuku.newProcess(
                        arrayOf("su", "-c", "cat $DB_PATH > $dstPath && chmod 644 $dstPath"),
                        null, null
                    )
                    suCopy.waitFor()
                    val suError = suCopy.errorStream.bufferedReader().readText().trim()
                    if (suCopy.exitValue() != 0) {
                        Log.e(TAG, "All copy methods failed. su error: $suError")
                        throw Exception("Failed to access Gadgetbridge database. Please ensure Shizuku is running with ADB or root.")
                    }
                }
            }

            // Also copy the WAL and SHM files if they exist
            for (suffix in listOf("-wal", "-shm")) {
                val srcPath = DB_PATH + suffix
                val suffixDstPath = dstPath + suffix
                val copyWalProcess = Shizuku.newProcess(
                    arrayOf("sh", "-c", "cat $srcPath > $suffixDstPath 2>/dev/null; chmod 644 $suffixDstPath 2>/dev/null; exit 0"),
                    null, null
                )
                copyWalProcess.waitFor()
            }

            Log.d(TAG, "Database copied successfully, querying...")

            // Open the copied database using Android's SQLite API
            val db = SQLiteDatabase.openDatabase(
                cacheDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            try {
                val cursor = db.rawQuery(sql, null)
                val result = buildJsonArray {
                    val columnNames = cursor.columnNames
                    while (cursor.moveToNext()) {
                        add(buildJsonObject {
                            for (col in columnNames) {
                                val index = cursor.getColumnIndex(col)
                                if (index >= 0) {
                                    val value = when (cursor.getType(index)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> null
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
                                        else -> cursor.getString(index)
                                    }
                                    if (value != null) {
                                        put(col, value)
                                    }
                                }
                            }
                        })
                    }
                }
                cursor.close()
                Log.d(TAG, "Query result: ${result.size} rows")
                result.toString()
            } finally {
                db.close()
                // Clean up copied database files
                cacheDbFile.delete()
                File(cacheDbFile.absolutePath + "-wal").delete()
                File(cacheDbFile.absolutePath + "-shm").delete()
            }
        }
    }

    /**
     * Check if Gadgetbridge is installed and database is accessible
     */
    suspend fun checkAvailability(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val result = executeQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1")
            result.isSuccess
        }
    }

    /**
     * Get device info from Gadgetbridge
     */
    suspend fun getDeviceInfo(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            executeQuery(
                "SELECT name, manufacturer, model, type, firmware_version, address FROM GB_DEVICE ORDER BY _id DESC LIMIT 5"
            ).getOrThrow()
        }
    }

    /**
     * Get today's activity data (steps, heart rate, etc.)
     */
    suspend fun getTodayActivity(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val todayStart = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % 86400
            executeQuery(
                """
                SELECT 
                    TIMESTAMP,
                    DEVICE_ID,
                    STEP_COUNT,
                    HEART_RATE,
                    RAW_INTENSITY,
                    SLEEP_SAMPLE_RAW_KIND
                FROM ACTIVITY_SAMPLE 
                WHERE TIMESTAMP >= $todayStart
                ORDER BY TIMESTAMP DESC 
                LIMIT 288
                """.trimIndent()
            ).getOrThrow()
        }
    }

    /**
     * Get step summary for today
     */
    suspend fun getTodaySteps(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val todayStart = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % 86400
            executeQuery(
                """
                SELECT 
                    SUM(STEP_COUNT) as total_steps,
                    MAX(STEP_COUNT) as max_steps_per_sample,
                    MIN(TIMESTAMP) as first_record,
                    MAX(TIMESTAMP) as last_record,
                    COUNT(*) as sample_count
                FROM ACTIVITY_SAMPLE 
                WHERE TIMESTAMP >= $todayStart AND STEP_COUNT > 0
                """.trimIndent()
            ).getOrThrow()
        }
    }

    /**
     * Get heart rate data for today
     */
    suspend fun getTodayHeartRate(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val todayStart = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % 86400
            executeQuery(
                """
                SELECT 
                    TIMESTAMP,
                    HEART_RATE
                FROM ACTIVITY_SAMPLE 
                WHERE TIMESTAMP >= $todayStart AND HEART_RATE > 0
                ORDER BY TIMESTAMP DESC
                LIMIT 100
                """.trimIndent()
            ).getOrThrow()
        }
    }

    /**
     * Get sleep data for today
     */
    suspend fun getTodaySleep(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val todayStart = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % 86400
            executeQuery(
                """
                SELECT 
                    TIMESTAMP,
                    SLEEP_SAMPLE_RAW_KIND,
                    DEVICE_ID
                FROM ACTIVITY_SAMPLE 
                WHERE TIMESTAMP >= ${todayStart - 86400} AND SLEEP_SAMPLE_RAW_KIND IS NOT NULL
                ORDER BY TIMESTAMP ASC
                LIMIT 500
                """.trimIndent()
            ).getOrThrow()
        }
    }

    /**
     * Get all health data combined as a formatted JSON result
     */
    suspend fun getAllHealthData(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val steps = getTodaySteps().getOrDefault("[]")
            val heartRate = getTodayHeartRate().getOrDefault("[]")
            val sleep = getTodaySleep().getOrDefault("[]")
            val deviceInfo = getDeviceInfo().getOrDefault("[]")

            buildJsonObject {
                put("success", true)
                put("steps", steps)
                put("heart_rate", heartRate)
                put("sleep", sleep)
                put("device_info", deviceInfo)
            }.toString()
        }
    }
}