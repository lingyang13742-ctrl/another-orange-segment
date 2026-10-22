/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.document

import android.util.Log

object CsvParser {

    private const val TAG = "CsvParser"

    fun parse(csv: String): List<List<String>> {
        return try {
            val rows = mutableListOf<List<String>>()
            val currentRow = mutableListOf<String>()
            val currentField = StringBuilder()
            var inQuotes = false
            var i = 0

            while (i < csv.length) {
                val c = csv[i]

                if (inQuotes) {
                    if (c == '"') {
                        // Check for escaped quote ""
                        if (i + 1 < csv.length && csv[i + 1] == '"') {
                            currentField.append('"')
                            i += 2
                            continue
                        } else {
                            // End of quoted field
                            inQuotes = false
                            i++
                            continue
                        }
                    } else {
                        currentField.append(c)
                        i++
                        continue
                    }
                } else {
                    when (c) {
                        ',' -> {
                            currentRow.add(currentField.toString())
                            currentField.clear()
                            i++
                            continue
                        }
                        '"' -> {
                            inQuotes = true
                            i++
                            continue
                        }
                        '\r' -> {
                            currentRow.add(currentField.toString())
                            currentField.clear()
                            if (currentRow.isNotEmpty() || rows.isNotEmpty()) {
                                rows.add(currentRow.toList())
                            }
                            currentRow.clear()
                            // Skip \r\n or just \r
                            if (i + 1 < csv.length && csv[i + 1] == '\n') {
                                i += 2
                            } else {
                                i++
                            }
                            continue
                        }
                        '\n' -> {
                            currentRow.add(currentField.toString())
                            currentField.clear()
                            if (currentRow.isNotEmpty() || rows.isNotEmpty()) {
                                rows.add(currentRow.toList())
                            }
                            currentRow.clear()
                            i++
                            continue
                        }
                        else -> {
                            currentField.append(c)
                            i++
                            continue
                        }
                    }
                }
            }

            // Handle last field/row
            if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
                currentRow.add(currentField.toString())
                rows.add(currentRow.toList())
            } else if (csv.isNotEmpty() && csv.last() in setOf('\n', '\r')) {
                // Trailing newline - don't add empty row
            }

            rows
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: csv.length=${csv.length}", e)
            emptyList()
        }
    }
}