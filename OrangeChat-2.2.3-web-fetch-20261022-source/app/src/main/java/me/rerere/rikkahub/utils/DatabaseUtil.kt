/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.database.CursorWindow
import android.util.Log

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 已fork io.requery.android.database 修改了window size，避免无法反射修改final字段
//        try {
//            val field =
//                io.requery.android.database.CursorWindow::class.java.getDeclaredField("sDefaultCursorWindowSize")
//            field.isAccessible = true
//            val oldValue = field.get(null) as Int
//            field.set(null, size)
//            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}
