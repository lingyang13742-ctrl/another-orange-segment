/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.gadgetbridge

import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "GadgetbridgeReader"

object GadgetbridgeDbPath {
    /** 使用 Environment API 获取主路径 */
    val DB_PATH: String
        get() = File(
            Environment.getExternalStorageDirectory(),
            "Download/手环/Gadgetbridge.db"
        ).absolutePath

    /**
     * 获取所有可能的路径变体列表
     * @param customPath 用户自定义路径，为空则使用默认路径列表
     */
    fun getPossiblePaths(customPath: String = ""): List<String> {
        val paths = mutableListOf<String>()

        // 如果有自定义路径，优先使用
        if (customPath.isNotBlank()) {
            paths.add(customPath)
        }

        // 默认路径列表（同时包含 .db 和 .sqlite3 变体）
        val defaultPaths = listOf(
            DB_PATH,
            "/sdcard/Download/手环/Gadgetbridge.db",
            "/storage/emulated/0/Download/手环/Gadgetbridge.db",
            "/sdcard/下载/手环/Gadgetbridge.db",
            "/storage/emulated/0/下载/手环/Gadgetbridge.db",
            File(Environment.getExternalStorageDirectory(), "下载/手环/Gadgetbridge.db").absolutePath,
        )
        paths.addAll(defaultPaths)

        // 为每个 .db 路径添加 .sqlite3 变体
        val sqlite3Variants = paths
            .filter { it.endsWith(".db") }
            .map { it.removeSuffix(".db") + ".sqlite3" }
        paths.addAll(sqlite3Variants)

        return paths.distinct()
    }
}

object GadgetbridgeReader {

    // 缓存上次找到的路径，避免重复搜索
    private var cachedDbPath: String? = null

    fun dbFileExists(customPath: String = ""): Boolean {
        val paths = GadgetbridgeDbPath.getPossiblePaths(customPath)
        for (path in paths) {
            try {
                val file = File(path)
                Log.d(TAG, "检查数据库文件: $path, exists=${file.exists()}, length=${if (file.exists()) file.length() else 0}")
                if (file.exists() && file.length() > 0) {
                    cachedDbPath = path
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查数据库文件失败: $path", e)
            }
        }
        return false
    }

    /**
     * 获取实际存在的数据库文件路径
     */
    private fun findDbPath(customPath: String = ""): String? {
        // 如果有缓存的路径且文件仍存在，直接返回
        cachedDbPath?.let { cached ->
            if (File(cached).exists() && File(cached).length() > 0) {
                return cached
            }
            cachedDbPath = null
        }

        val paths = GadgetbridgeDbPath.getPossiblePaths(customPath)
        for (path in paths) {
            try {
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    Log.d(TAG, "找到数据库文件: $path")
                    cachedDbPath = path
                    return path
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun <T> withDatabase(customPath: String = "", block: (SQLiteDatabase) -> T): Result<T> {
        val dbPath = findDbPath(customPath) ?: return Result.failure(
            IllegalStateException("Gadgetbridge 数据库文件不存在")
        )
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbPath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            Result.success(block(db))
        } catch (e: Exception) {
            Log.e(TAG, "打开数据库失败: $dbPath", e)
            Result.failure(e)
        } finally {
            db?.close()
        }
    }

    // ==================== 厂商路由 ====================

    /**
     * 设备厂商（用于按厂商分流到不同的协议表实现）
     */
    private enum class Manufacturer { XIAOMI, HUAWEI }

    /**
     * 查询 DEVICE 表最新一条记录的 MANUFACTURER 字段判断当前设备厂商。
     *
     * - 取最新一条 DEVICE 记录（按 _id DESC，兼容老库无排序字段的情况）。
     * - MANUFACTURER 字段不区分大小写包含 "huawei" 即判定为华为。
     * - 查询异常 / 表不存在 / 字段为空 / 无法识别的厂商：默认走小米逻辑，保证向后兼容。
     */
    private fun detectManufacturer(db: SQLiteDatabase): Manufacturer {
        return try {
            // DEVICE 表的 MANUFACTURER 字段实测值如 "Huawei"
            val cursor = db.query(
                "DEVICE",
                arrayOf("MANUFACTURER"),
                null, null, null, null,
                "_id DESC", "1"
            )
            cursor.use {
                if (!it.moveToFirst()) {
                    Log.w(TAG, "厂商判断: DEVICE 表为空, 默认走小米逻辑")
                    Manufacturer.XIAOMI
                } else {
                    val manufacturer = it.getString(0)?.lowercase()?.trim().orEmpty()
                    Log.d(TAG, "厂商判断: MANUFACTURER=$manufacturer")
                    when {
                        manufacturer.contains("huawei") -> Manufacturer.HUAWEI
                        // 未识别的厂商一律走小米，保持向后兼容
                        else -> Manufacturer.XIAOMI
                    }
                }
            }
        } catch (e: Exception) {
            // DEVICE 表不存在或字段缺失时不阻断主流程
            Log.e(TAG, "厂商判断失败, 默认走小米逻辑", e)
            Manufacturer.XIAOMI
        }
    }

    // ==================== 公开方法（4 个，签名保持不变，内部按厂商分流） ====================

    fun readDailySummaries(days: Int, customPath: String = ""): List<DailySummary> {
        return withDatabase(customPath) { db ->
            when (detectManufacturer(db)) {
                Manufacturer.HUAWEI -> readDailySummariesHuawei(db, days)
                Manufacturer.XIAOMI -> readDailySummariesXiaomi(db, days)
            }
        }.getOrDefault(emptyList())
    }

    fun readLatestActivitySample(customPath: String = ""): ActivitySample? {
        return withDatabase(customPath) { db ->
            when (detectManufacturer(db)) {
                Manufacturer.HUAWEI -> readLatestActivitySampleHuawei(db)
                Manufacturer.XIAOMI -> readLatestActivitySampleXiaomi(db)
            }
        }.getOrDefault(null)
    }

    fun readSleepSummaries(days: Int, customPath: String = ""): List<SleepSummary> {
        return withDatabase(customPath) { db ->
            when (detectManufacturer(db)) {
                Manufacturer.HUAWEI -> readSleepSummariesHuawei(db, days)
                Manufacturer.XIAOMI -> readSleepSummariesXiaomi(db, days)
            }
        }.getOrDefault(emptyList())
    }

    fun readLatestSpo2AndStress(customPath: String = ""): Pair<Int?, Int?> {
        return withDatabase(customPath) { db ->
            when (detectManufacturer(db)) {
                Manufacturer.HUAWEI -> readLatestSpo2AndStressHuawei(db)
                Manufacturer.XIAOMI -> readLatestSpo2AndStressXiaomi(db)
            }
        }.getOrDefault(null to null)
    }

    // ==================== 小米实现（原样保留，一行未改） ====================

    private fun readDailySummariesXiaomi(db: SQLiteDatabase, days: Int): List<DailySummary> {
        val now = LocalDate.now()
        val startTime = now.minusDays(days.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val summaries = mutableListOf<DailySummary>()
        val cursor = db.query(
            "XIAOMI_DAILY_SUMMARY_SAMPLE",
            arrayOf("TIMESTAMP", "STEPS", "HR_RESTING", "HR_MAX", "HR_MIN", "HR_AVG", "STRESS_AVG", "CALORIES", "SPO2_AVG"),
            "TIMESTAMP >= ?",
            arrayOf(startTime.toString()),
            null, null, "TIMESTAMP ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val timestamp = it.getLong(0)
                val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                summaries.add(DailySummary(timestamp, date, it.getInt(1), getIntOrNull(it, 2), getIntOrNull(it, 3), getIntOrNull(it, 4), getIntOrNull(it, 5), getIntOrNull(it, 6), getIntOrNull(it, 7), getIntOrNull(it, 8)))
            }
        }
        return summaries
    }

    private fun readLatestActivitySampleXiaomi(db: SQLiteDatabase): ActivitySample? {
        val cursor = db.query("XIAOMI_ACTIVITY_SAMPLE", arrayOf("TIMESTAMP", "HEART_RATE", "STEPS", "STRESS", "SPO2", "RAW_INTENSITY"), "HEART_RATE IS NOT NULL AND HEART_RATE > 0", null, null, null, "TIMESTAMP DESC", "1")
        cursor.use {
            return if (it.moveToFirst()) ActivitySample(it.getLong(0), getIntOrNull(it, 1), getIntOrNull(it, 2), getIntOrNull(it, 3), getIntOrNull(it, 4), getIntOrNull(it, 5)) else null
        }
    }

    private fun readSleepSummariesXiaomi(db: SQLiteDatabase, days: Int): List<SleepSummary> {
        val now = System.currentTimeMillis()
        val startTime = now - days.toLong() * 24 * 60 * 60 * 1000L
        val summaries = mutableListOf<SleepSummary>()
        val cursor = db.query(
            "XIAOMI_SLEEP_TIME_SAMPLE",
            arrayOf("TIMESTAMP", "WAKEUP_TIME", "TOTAL_DURATION", "DEEP_SLEEP_DURATION",
                "LIGHT_SLEEP_DURATION", "REM_SLEEP_DURATION", "AWAKE_DURATION", "IS_AWAKE"),
            "TIMESTAMP >= ?",
            arrayOf(startTime.toString()),
            null, null, "TIMESTAMP DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                summaries.add(SleepSummary(
                    timestamp = it.getLong(0),
                    wakeupTime = it.getLong(1),
                    totalDuration = it.getInt(2),
                    deepSleep = it.getInt(3),
                    lightSleep = it.getInt(4),
                    remSleep = it.getInt(5),
                    awakeDuration = it.getInt(6),
                    isAwake = it.getInt(7) == 1,
                ))
            }
        }
        return summaries
    }

    private fun readLatestSpo2AndStressXiaomi(db: SQLiteDatabase): Pair<Int?, Int?> {
        val startSec = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond
        var spo2: Int? = null
        var stress: Int? = null
        val c1 = db.query("XIAOMI_ACTIVITY_SAMPLE", arrayOf("SPO2"), "TIMESTAMP >= ? AND SPO2 IS NOT NULL AND SPO2 > 0", arrayOf(startSec.toString()), null, null, "TIMESTAMP DESC", "1")
        c1.use { if (it.moveToFirst()) spo2 = getIntOrNull(it, 0) }
        val c2 = db.query("XIAOMI_ACTIVITY_SAMPLE", arrayOf("STRESS"), "TIMESTAMP >= ? AND STRESS IS NOT NULL AND STRESS > 0", arrayOf(startSec.toString()), null, null, "TIMESTAMP DESC", "1")
        c2.use { if (it.moveToFirst()) stress = getIntOrNull(it, 0) }
        return Pair(spo2, stress)
    }

    // ==================== 华为实现 ====================
    //
    // 已验证事实（基于 HUAWEI Band 9 / Band 8 两份实测数据库 + Gadgetbridge 官方 GitHub 源码 HuaweiSampleProvider.java）：
    // 1. HUAWEI_ACTIVITY_SAMPLE.TIMESTAMP 单位是【秒】（不是毫秒）
    // 2. 数值字段无数据用字面值 -1（NOT_MEASURED）表示，不是 SQL NULL
    // 3. 每条真实采样会同时写入一条占位/标记行，timestamp 与 otherTimestamp 互相指向对方
    //    判断真实数据行的官方方法：TIMESTAMP <= OTHER_TIMESTAMP（来自 getGBActivitySamplesHighRes）
    // 4. CALORIES 原始整数需 /1000 才是 kcal（对应官方 getActiveCalories()，即运动活跃消耗，不含基础代谢）
    // 5. DISTANCE 单位是【米】（官方 getDistanceCm() = getDistance() * 100）
    // 6. HEART_RATE / RESTING_HEART_RATE / SPO 有效性用 > 0
    // 7. STEPS / CALORIES / DISTANCE 有效性用 != -1（0 是合法值）
    //
    // 注意时间单位差异（各表不同，必须分别换算比较范围）：
    // - HUAWEI_ACTIVITY_SAMPLE    TIMESTAMP 单位 = 秒
    // - HUAWEI_STRESS_SAMPLE      TIMESTAMP 单位 = 毫秒
    // - HUAWEI_SLEEP_STATS_SAMPLE TIMESTAMP 单位 = 毫秒（已用真实数据验证）

    /**
     * 华为：读取最近 [days] 天的每日汇总。
     *
     * 华为没有现成的每日汇总表，对 HUAWEI_ACTIVITY_SAMPLE 按本地日期逐天聚合。
     * 压力数据来自 HUAWEI_STRESS_SAMPLE（注意该表时间戳为毫秒，需单独换算范围）。
     */
    private fun readDailySummariesHuawei(db: SQLiteDatabase, days: Int): List<DailySummary> {
        val summaries = mutableListOf<DailySummary>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        for (i in 0 until days) {
            try {
                val date = today.minusDays(i.toLong())
                // 活动表时间戳单位是秒
                val dayStartSec = date.atStartOfDay(zone).toInstant().epochSecond
                val dayEndSec = date.plusDays(1).atStartOfDay(zone).toInstant().epochSecond
                // 压力表时间戳单位是毫秒
                val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

                // 占位行过滤条件：TIMESTAMP <= OTHER_TIMESTAMP（仅活动表需要）
                val realRowFilter = "TIMESTAMP <= OTHER_TIMESTAMP"

                // 步数：SUM(STEPS)，过滤 STEPS != -1
                val steps = try {
                    db.query(
                        "HUAWEI_ACTIVITY_SAMPLE",
                        arrayOf("SUM(STEPS)"),
                        "$realRowFilter AND STEPS != -1 AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartSec.toString(), dayEndSec.toString()),
                        null, null, null
                    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0 }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 步数查询失败 date=$date", e); 0
                }

                // 卡路里：SUM(CALORIES)，过滤 CALORIES != -1；原始整数 /1000 转 kcal
                // （CALORIES 对应官方 getActiveCalories()，即运动活跃消耗，不含基础代谢）
                val calories = try {
                    db.query(
                        "HUAWEI_ACTIVITY_SAMPLE",
                        arrayOf("SUM(CALORIES)"),
                        "$realRowFilter AND CALORIES != -1 AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartSec.toString(), dayEndSec.toString()),
                        null, null, null
                    ).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) (c.getInt(0) / 1000) else null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 卡路里查询失败 date=$date", e); null
                }

                // 心率：MAX/MIN/AVG(HEART_RATE)，过滤 HEART_RATE > 0
                val (hrMax, hrMin, hrAvg) = try {
                    db.query(
                        "HUAWEI_ACTIVITY_SAMPLE",
                        arrayOf("MAX(HEART_RATE)", "MIN(HEART_RATE)", "AVG(HEART_RATE)"),
                        "$realRowFilter AND HEART_RATE > 0 AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartSec.toString(), dayEndSec.toString()),
                        null, null, null
                    ).use { c ->
                        if (c.moveToFirst()) Triple(
                            if (c.isNull(0)) null else c.getInt(0),
                            if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getDouble(2).toInt()
                        ) else Triple(null, null, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 心率查询失败 date=$date", e)
                    Triple(null, null, null)
                }

                // 静息心率：当天 RESTING_HEART_RATE > 0 的最新一条（取真实测量值，比 AVG 更贴近设备读数）
                val hrResting = try {
                    db.query(
                        "HUAWEI_ACTIVITY_SAMPLE",
                        arrayOf("RESTING_HEART_RATE"),
                        "$realRowFilter AND RESTING_HEART_RATE > 0 AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartSec.toString(), dayEndSec.toString()),
                        null, null, "TIMESTAMP DESC", "1"
                    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 静息心率查询失败 date=$date", e); null
                }

                // 血氧：AVG(SPO)，过滤 SPO > 0
                val spo2Avg = try {
                    db.query(
                        "HUAWEI_ACTIVITY_SAMPLE",
                        arrayOf("AVG(SPO)"),
                        "$realRowFilter AND SPO > 0 AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartSec.toString(), dayEndSec.toString()),
                        null, null, null
                    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).toInt() else null }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 血氧查询失败 date=$date", e); null
                }

                // 压力：HUAWEI_STRESS_SAMPLE 该表无占位行问题，直接 AVG(STRESS)
                // 注意该表时间戳为毫秒，使用 dayStartMs/dayEndMs
                val stressAvg = try {
                    db.query(
                        "HUAWEI_STRESS_SAMPLE",
                        arrayOf("AVG(STRESS)"),
                        "TIMESTAMP >= ? AND TIMESTAMP < ?",
                        arrayOf(dayStartMs.toString(), dayEndMs.toString()),
                        null, null, null
                    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).toInt() else null }
                } catch (e: Exception) {
                    Log.e(TAG, "华为 daily 压力查询失败 date=$date", e); null
                }

                // 时间戳用当天 0 点的毫秒值（与小米 daily 表对齐，便于 UI 按日期展示）
                val timestampMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
                summaries.add(
                    DailySummary(
                        timestamp = timestampMs,
                        date = date,
                        steps = steps,
                        hrResting = hrResting,
                        hrMax = hrMax,
                        hrMin = hrMin,
                        hrAvg = hrAvg,
                        stressAvg = stressAvg,
                        calories = calories,
                        spo2Avg = spo2Avg,
                    )
                )
            } catch (e: Exception) {
                // 单天异常不影响其他天
                Log.e(TAG, "华为 daily 汇总失败 date=${today.minusDays(i.toLong())}", e)
            }
        }

        // 升序返回，与小米分支行为一致
        return summaries.sortedBy { it.timestamp }
    }

    /**
     * 华为：读取最新一条真实活动采样（带心率）。
     *
     * 过滤占位行：TIMESTAMP <= OTHER_TIMESTAMP
     * 心率有效性：HEART_RATE > 0
     * -1 sentinel 字段在映射时转为 null（如 STEPS / SPO 为 -1 视为未测量）
     *
     * 注意：受 ActivitySample 数据模型约束（无 calories 字段），单条采样的卡路里
     * 不在此方法返回；CALORIES 的 /1000 换算仅在 readDailySummariesHuawei 的日聚合中体现。
     */
    private fun readLatestActivitySampleHuawei(db: SQLiteDatabase): ActivitySample? {
        return try {
            // 活动表时间戳单位是秒
            val cursor = db.query(
                "HUAWEI_ACTIVITY_SAMPLE",
                arrayOf(
                    "TIMESTAMP", "HEART_RATE", "STEPS", "SPO", "RAW_INTENSITY"
                ),
                "TIMESTAMP <= OTHER_TIMESTAMP AND HEART_RATE > 0",
                null, null, null,
                "TIMESTAMP DESC", "1"
            )
            cursor.use {
                if (!it.moveToFirst()) return null
                val timestampSec = it.getLong(0)
                val heartRate = if (it.isNull(1) || it.getInt(1) <= 0) null else it.getInt(1)
                // STEPS / SPO：-1 表示未测量，映射为 null
                val stepsRaw = if (it.isNull(2)) null else it.getInt(2)
                val steps = if (stepsRaw != null && stepsRaw != -1) stepsRaw else null
                val spoRaw = if (it.isNull(3)) null else it.getInt(3)
                val spo = if (spoRaw != null && spoRaw > 0) spoRaw else null
                val rawIntensity = if (it.isNull(4)) null else it.getInt(4)
                ActivitySample(
                    // 统一转换为毫秒，与小米分支 ActivitySample.timestamp 对齐
                    timestamp = timestampSec * 1000L,
                    heartRate = heartRate,
                    steps = steps,
                    stress = null, // 华为活动表无 stress 字段
                    spo2 = spo,
                    rawIntensity = rawIntensity,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "华为 latest activity 查询失败", e)
            null
        }
    }

    /**
     * 华为：读取最近 [days] 天的睡眠汇总。
     *
     * 已验证事实（基于 HUAWEI Band 9 / Band 8 真实数据库 + 与 HUAWEI_SLEEP_STAGE_SAMPLE 交叉验证）：
     * - 整张表时间戳单位是【毫秒】。
     * - BED_TIME 字段实测为坏的（值是 -1000 或 0，非真实时间戳），不要读取/参与计算。
     * - 真正的"入睡时刻" = 这一行的 TIMESTAMP 列（已与 HUAWEI_SLEEP_STAGE_SAMPLE 逐分钟阶段表
     *   交叉验证：每段睡眠最早一条阶段记录的时间戳，正好等于对应本表行的 TIMESTAMP）。
     * - WAKEUP_TIME 字段可靠，是真实的醒来时刻（毫秒）。
     * - 总时长公式：(WAKEUP_TIME - TIMESTAMP) / 1000 / 60（分钟）。已用真实三条记录验证，
     *   结果 7.43h / 4.28h / 1.13h 均合理，不需要任何 >24h 清零兜底逻辑。
     * - deepSleep / lightSleep / remSleep / awakeDuration：本表无现成字段，改为查询
     *   HUAWEI_SLEEP_STAGE_SAMPLE 逐分钟阶段表，按 STAGE 归类累加（每行 = 1 分钟）。
     *   STAGE 语义已通过 Gadgetbridge 官方源码 HuaweiSampleProvider.java::toActivityKind() 验证，
     *   并已用真实数据库交叉验证（各阶段分钟数加总正好等于总时长，无缺口）：
     *     1=浅睡(lightSleep) 2=REM(remSleep) 3=深睡(deepSleep) 4=清醒(awakeDuration)
     *     5=小睡片段(NAP，官方归并计入 lightSleep) 其它值=未知(仅打日志，不计入)
     *   时间边界按官方写法：起点向下取整到整分钟，终点向下取整后扣 1 分钟（"醒来那一分钟"不算）。
     * - DEEP_PART 列不再使用（实测为 0-100 的占比值，不是分钟数，旧逻辑直接当深睡分钟用是错的）。
     * - isAwake：华为本表无此语义，置 false（本次不重新设计小睡判断，留作后续单独处理）。
     */
    private fun readSleepSummariesHuawei(db: SQLiteDatabase, days: Int): List<SleepSummary> {
        val summaries = mutableListOf<SleepSummary>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // 查询起点：往前推 days 天的 0 点，本表时间戳单位为毫秒
        val startMs = today.minusDays(days.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

        return try {
            val cursor = db.query(
                "HUAWEI_SLEEP_STATS_SAMPLE",
                arrayOf("TIMESTAMP", "WAKEUP_TIME"),
                "TIMESTAMP >= ?",
                arrayOf(startMs.toString()),
                null, null, "TIMESTAMP DESC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    try {
                        // 入睡时刻 = 行自身 TIMESTAMP（毫秒）；不读 BED_TIME（该字段实测坏的）
                        val sleepStartMs = it.getLong(it.getColumnIndexOrThrow("TIMESTAMP"))
                        val wakeupTimeMs = it.getLong(it.getColumnIndexOrThrow("WAKEUP_TIME"))

                        // 总时长（分钟）：(WAKEUP_TIME - TIMESTAMP) / 1000 / 60
                        // 已用真实数据验证，公式正确，不加任何 >24h 清零兜底
                        // 注意：故意保持独立计算，不等于四个阶段分钟数之和，以防阶段数据缺失时总时长塌成 0
                        val totalDuration = try {
                            if (wakeupTimeMs > sleepStartMs) {
                                ((wakeupTimeMs - sleepStartMs) / 1000L / 60L).toInt()
                            } else {
                                0
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "华为 sleep 总时长计算失败 sleepStartMs=$sleepStartMs wakeupTimeMs=$wakeupTimeMs", e)
                            0
                        }

                        // 逐分钟睡眠阶段分布：查 HUAWEI_SLEEP_STAGE_SAMPLE，按 STAGE 分组计数（每行=1分钟）。
                        // 边界按 Gadgetbridge 官方源码写法：起点向下取整到整分钟，终点向下取整后扣 1 分钟
                        // （"醒来那一分钟"本身不算在睡眠阶段里）。
                        var deepMinutes = 0
                        var lightMinutes = 0
                        var remMinutes = 0
                        var awakeMinutes = 0
                        try {
                            val stageFromMs = (sleepStartMs / 60000L) * 60000L
                            val stageToMs = (wakeupTimeMs / 60000L) * 60000L - 60000L
                            db.query(
                                "HUAWEI_SLEEP_STAGE_SAMPLE",
                                arrayOf("STAGE", "COUNT(*)"),
                                "TIMESTAMP >= ? AND TIMESTAMP <= ?",
                                arrayOf(stageFromMs.toString(), stageToMs.toString()),
                                "STAGE", null, null
                            ).use { c ->
                                var hasRows = false
                                while (c.moveToNext()) {
                                    hasRows = true
                                    // 用位置索引读取（与 readDailySummariesHuawei 聚合查询风格一致，
                                    // 不用 getColumnIndexOrThrow("COUNT(*)")，聚合列名跨驱动不一致）
                                    val stage = c.getInt(0)
                                    val count = c.getInt(1)
                                    when (stage) {
                                        3 -> deepMinutes += count
                                        // STAGE=1(浅睡) 和 STAGE=5(小睡片段) 都计入 lightSleep
                                        1, 5 -> lightMinutes += count
                                        2 -> remMinutes += count
                                        4 -> awakeMinutes += count
                                        else -> Log.w(TAG, "出现未知STAGE值=$stage, 忽略")
                                    }
                                }
                                if (!hasRows) {
                                    // 完全没有阶段细分数据（设备未上传/快照不完整）：如实保留 0，不是 bug
                                    Log.w(TAG, "华为 sleep 阶段数据为空 sleepStartMs=$sleepStartMs wakeupTimeMs=$wakeupTimeMs")
                                }
                            }
                        } catch (e: Exception) {
                            // 阶段查询失败不阻断本条 session 其它字段（如 totalDuration）的计算
                            Log.e(TAG, "华为 sleep 阶段查询失败 sleepStartMs=$sleepStartMs wakeupTimeMs=$wakeupTimeMs", e)
                        }

                        val deepSleep = deepMinutes
                        val lightSleep = lightMinutes
                        val remSleep = remMinutes
                        val awakeDuration = awakeMinutes
                        // 华为本表无"是否短暂清醒"语义（本次不重新设计小睡判断逻辑）
                        val isAwake = false

                        summaries.add(
                            SleepSummary(
                                timestamp = sleepStartMs,
                                wakeupTime = wakeupTimeMs,
                                totalDuration = totalDuration,
                                deepSleep = deepSleep,
                                lightSleep = lightSleep,
                                remSleep = remSleep,
                                awakeDuration = awakeDuration,
                                isAwake = isAwake,
                            )
                        )
                    } catch (e: Exception) {
                        // 单条异常不影响其他记录
                        Log.e(TAG, "华为 sleep 单条映射失败", e)
                    }
                }
            }
            summaries
        } catch (e: Exception) {
            Log.e(TAG, "华为 sleep 查询失败 startMs=$startMs", e)
            summaries
        }
    }

    /**
     * 华为：读取当天最新血氧与压力。
     *
     * - 血氧：HUAWEI_ACTIVITY_SAMPLE 当天 SPO > 0 的最新一条（时间戳秒级）
     * - 压力：HUAWEI_STRESS_SAMPLE 当天最新一条（时间戳毫秒级）
     *
     * 注意：两张表时间戳单位不同，分别用 epochSecond / toEpochMilli 计算当天范围。
     */
    private fun readLatestSpo2AndStressHuawei(db: SQLiteDatabase): Pair<Int?, Int?> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // 活动表：秒级
        val activityStartSec = today.atStartOfDay(zone).toInstant().epochSecond
        // 压力表：毫秒级
        val stressStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

        var spo2: Int? = null
        var stress: Int? = null

        // 血氧（过滤占位行 + SPO > 0）
        try {
            db.query(
                "HUAWEI_ACTIVITY_SAMPLE",
                arrayOf("SPO"),
                "TIMESTAMP <= OTHER_TIMESTAMP AND SPO > 0 AND TIMESTAMP >= ?",
                arrayOf(activityStartSec.toString()),
                null, null, "TIMESTAMP DESC", "1"
            ).use { if (it.moveToFirst() && !it.isNull(0)) spo2 = it.getInt(0) }
        } catch (e: Exception) {
            Log.e(TAG, "华为 latest spo2 查询失败 startSec=$activityStartSec", e)
        }

        // 压力（该表无占位行问题，直接按时间取最新）
        try {
            db.query(
                "HUAWEI_STRESS_SAMPLE",
                arrayOf("STRESS"),
                "TIMESTAMP >= ?",
                arrayOf(stressStartMs.toString()),
                null, null, "TIMESTAMP DESC", "1"
            ).use { if (it.moveToFirst() && !it.isNull(0)) stress = it.getInt(0) }
        } catch (e: Exception) {
            Log.e(TAG, "华为 latest stress 查询失败 startMs=$stressStartMs", e)
        }

        return Pair(spo2, stress)
    }

    private fun getIntOrNull(cursor: android.database.Cursor, index: Int): Int? {
        return try { if (cursor.isNull(index)) null else cursor.getInt(index) } catch (_: Exception) { null }
    }
}