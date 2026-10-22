/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.common.android

import android.content.Context
import java.io.File

val Context.appTempFolder: File
    get() {
        val dir = File(cacheDir, "temp")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

fun Context.getCacheDirectory(namespace: String): File {
    val dir = File(cacheDir, "disk_cache/$namespace")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}
