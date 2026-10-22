/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.document

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExcelGenerator {

    private const val TAG = "ExcelGenerator"

    // ========== 原有方法保留 ==========
    fun generate(rows: List<List<String>>): ByteArray {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            ZipOutputStream(byteArrayOutputStream).use { zipOut ->
                writeContentTypesSimple(zipOut)
                writeRelsSimple(zipOut)
                writeWorkbookSimple(zipOut)
                writeWorkbookRelsSimple(zipOut)
                writeStylesSimple(zipOut)
                val sharedStrings = collectSharedStrings(rows)
                writeSharedStrings(zipOut, sharedStrings)
                writeSheet1Simple(zipOut, rows, sharedStrings)
            }
            byteArrayOutputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "generate failed: rows.size=${rows.size}", e)
            ByteArray(0)
        }
    }

    // ========== 新增：带样式方法 ==========
    fun generateStyled(json: String): ByteArray {
        return try {
            val workbook = parseStyledWorkbook(json)
            val byteArrayOutputStream = ByteArrayOutputStream()
            ZipOutputStream(byteArrayOutputStream).use { zipOut ->
                val sharedStrings = workbook.allStrings
                val styles = buildStyles(workbook)

                writeContentTypes(zipOut, workbook.sheets.size)
                writeRels(zipOut)
                writeWorkbook(zipOut, workbook.sheets)
                writeWorkbookRels(zipOut, workbook.sheets.size)
                writeStyles(zipOut, styles)
                writeSharedStrings(zipOut, sharedStrings)

                workbook.sheets.forEachIndexed { index, sheet ->
                    writeSheet(zipOut, sheet, index + 1, sharedStrings, styles)
                }
            }
            byteArrayOutputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "generateStyled failed: jsonLen=${json.length}", e)
            ByteArray(0)
        }
    }

    // ========== 数据模型 ==========
    private data class StyledWorkbook(val sheets: List<StyledSheet>)

    private data class StyledSheet(
        val name: String,
        val merges: List<String>,
        val rows: List<StyledRow>
    )

    private data class StyledRow(val cells: List<StyledCell>, val height: Double?)

    private data class StyledCell(
        val value: String,
        val bold: Boolean,
        val italic: Boolean,
        val fontSize: Int,
        val color: String?,
        val bg: String?,
        val align: String?,
        val wrap: Boolean
    )

    // ========== 样式构建 ==========
    private data class StyleKey(
        val bold: Boolean,
        val italic: Boolean,
        val fontSize: Int,
        val color: String?,
        val bg: String?,
        val align: String?,
        val wrap: Boolean
    )

    private data class BuiltStyles(
        val styleMap: Map<StyleKey, Int>,
        val fonts: List<FontDef>,
        val fills: List<FillDef>,
        val xfAlignments: Map<Int, Pair<String?, Boolean>>
    )

    private data class FontDef(val bold: Boolean, val italic: Boolean, val fontSize: Int, val color: String?)
    private data class FillDef(val bg: String?)

    // ========== JSON 解析 ==========
    private fun parseStyledWorkbook(json: String): StyledWorkbook {
        val root = JSONObject(json)
        val sheetsArray = root.optJSONArray("sheets") ?: JSONArray()
        val sheets = mutableListOf<StyledSheet>()

        for (i in 0 until sheetsArray.length()) {
            val sheetObj = sheetsArray.getJSONObject(i)
            val name = sheetObj.optString("name", "Sheet${i + 1}")

            val merges = mutableListOf<String>()
            val mergesArray = sheetObj.optJSONArray("merges")
            if (mergesArray != null) {
                for (j in 0 until mergesArray.length()) {
                    merges.add(mergesArray.getString(j))
                }
            }

            val rows = mutableListOf<StyledRow>()
            val rowsArray = sheetObj.getJSONArray("rows")
            for (r in 0 until rowsArray.length()) {
                val rowObj = rowsArray.getJSONObject(r)
                val height = if (rowObj.has("height")) rowObj.getDouble("height") else null

                val cells = mutableListOf<StyledCell>()
                val cellsArray = rowObj.getJSONArray("cells")
                for (c in 0 until cellsArray.length()) {
                    val cellObj = cellsArray.getJSONObject(c)
                    cells.add(
                        StyledCell(
                            value = cellObj.optString("value", ""),
                            bold = cellObj.optBoolean("bold", false),
                            italic = cellObj.optBoolean("italic", false),
                            fontSize = cellObj.optInt("fontSize", 11),
                            color = if (!cellObj.isNull("color")) cellObj.getString("color") else null,
                            bg = if (!cellObj.isNull("bg")) cellObj.getString("bg") else null,
                            align = if (!cellObj.isNull("align")) cellObj.getString("align") else null,
                            wrap = cellObj.optBoolean("wrap", false)
                        )
                    )
                }
                rows.add(StyledRow(cells, height))
            }
            sheets.add(StyledSheet(name, merges, rows))
        }

        return StyledWorkbook(sheets)
    }

    private val StyledWorkbook.allStrings: List<String>
        get() {
            val set = LinkedHashSet<String>()
            sheets.forEach { sheet ->
                sheet.rows.forEach { row ->
                    row.cells.forEach { cell ->
                        set.add(cell.value)
                    }
                }
            }
            return set.toList()
        }

    // ========== 样式构建 ==========
    private fun buildStyles(workbook: StyledWorkbook): BuiltStyles {
        val styleKeys = mutableSetOf<StyleKey>()
        workbook.sheets.forEach { sheet ->
            sheet.rows.forEach { row ->
                row.cells.forEach { cell ->
                    styleKeys.add(
                        StyleKey(
                            cell.bold, cell.italic, cell.fontSize,
                            cell.color, cell.bg, cell.align, cell.wrap
                        )
                    )
                }
            }
        }

        // 默认样式
        val defaultKey = StyleKey(false, false, 11, null, null, null, false)
        val allKeys = listOf(defaultKey) + (styleKeys - defaultKey)

        // 收集字体和填充定义
        val fonts = mutableListOf<FontDef>()
        val fills = mutableListOf<FillDef>()
        val fontMap = mutableMapOf<FontDef, Int>()
        val fillMap = mutableMapOf<FillDef, Int>()

        // 默认字体和填充
        val defaultFont = FontDef(false, false, 11, null)
        val defaultFill = FillDef(null)
        fonts.add(defaultFont)
        fills.add(defaultFill)
        fontMap[defaultFont] = 0
        fillMap[defaultFill] = 0

        // 还需要添加 gray125 填充（Excel 默认）
        val gray125Fill = FillDef("__gray125__")
        fills.add(gray125Fill)
        fillMap[gray125Fill] = 1

        val styleMap = mutableMapOf<StyleKey, Int>()
        val xfAlignments = mutableMapOf<Int, Pair<String?, Boolean>>()

        for ((index, key) in allKeys.withIndex()) {
            val fontDef = FontDef(key.bold, key.italic, key.fontSize, key.color)
            val fontId = fontMap.getOrPut(fontDef) {
                val id = fonts.size
                fonts.add(fontDef)
                id
            }

            val fillDef = FillDef(key.bg)
            val fillId = fillMap.getOrPut(fillDef) {
                val id = fills.size
                fills.add(fillDef)
                id
            }

            styleMap[key] = index
            xfAlignments[index] = key.align to key.wrap
        }

        return BuiltStyles(styleMap, fonts, fills, xfAlignments)
    }

    // ========== ZIP 写入（带样式版） ==========
    private fun writeContentTypes(zipOut: ZipOutputStream, sheetCount: Int) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            sb.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
            sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            for (i in 1..sheetCount) {
                sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            sb.append("</Types>")

            zipOut.putNextEntry(ZipEntry("[Content_Types].xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeContentTypes failed: sheetCount=$sheetCount", e)
        }
    }

    private fun writeRels(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            zipOut.putNextEntry(ZipEntry("_rels/.rels"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeRels failed", e)
        }
    }

    private fun writeWorkbook(zipOut: ZipOutputStream, sheets: List<StyledSheet>) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            sb.append("<sheets>")
            sheets.forEachIndexed { index, sheet ->
                val sheetId = index + 1
                val rId = "rId$sheetId"
                val safeName = escapeXml(sheet.name)
                sb.append("<sheet name=\"$safeName\" sheetId=\"$sheetId\" r:id=\"$rId\"/>")
            }
            sb.append("</sheets></workbook>")

            zipOut.putNextEntry(ZipEntry("xl/workbook.xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeWorkbook failed: sheets.size=${sheets.size}", e)
        }
    }

    private fun writeWorkbookRels(zipOut: ZipOutputStream, sheetCount: Int) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            for (i in 1..sheetCount) {
                sb.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
            }
            val sharedStringsId = sheetCount + 1
            val stylesId = sheetCount + 2
            sb.append("<Relationship Id=\"rId$sharedStringsId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>")
            sb.append("<Relationship Id=\"rId$stylesId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            sb.append("</Relationships>")

            zipOut.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeWorkbookRels failed: sheetCount=$sheetCount", e)
        }
    }

    private fun writeStyles(zipOut: ZipOutputStream, styles: BuiltStyles) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")

            // fonts
            sb.append("<fonts count=\"${styles.fonts.size}\">")
            for (font in styles.fonts) {
                sb.append("<font>")
                if (font.bold) sb.append("<b/>")
                if (font.italic) sb.append("<i/>")
                sb.append("<sz val=\"${font.fontSize}\"/>")
                sb.append("<name val=\"Calibri\"/>")
                if (font.color != null) {
                    sb.append("<color rgb=\"FF${font.color}\"/>")
                }
                sb.append("</font>")
            }
            sb.append("</fonts>")

            // fills
            sb.append("<fills count=\"${styles.fills.size}\">")
            for (fill in styles.fills) {
                when {
                    fill.bg == null -> sb.append("<fill><patternFill patternType=\"none\"/></fill>")
                    fill.bg == "__gray125__" -> sb.append("<fill><patternFill patternType=\"gray125\"/></fill>")
                    else -> sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${fill.bg}\"/><bgColor rgb=\"FF${fill.bg}\"/></patternFill></fill>")
                }
            }
            sb.append("</fills>")

            // borders (single empty border)
            sb.append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")

            // cellStyleXfs
            sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")

            // cellXfs
            val xfCount = styles.styleMap.size
            sb.append("<cellXfs count=\"$xfCount\">")
            for (i in 0 until xfCount) {
                val (align, wrap) = styles.xfAlignments[i] ?: (null to false)
                val key = styles.styleMap.entries.first { it.value == i }.key
                val fontDef = FontDef(key.bold, key.italic, key.fontSize, key.color)
                val fillDef = FillDef(key.bg)

                val fontId = styles.fonts.indexOfFirst { it == fontDef }
                val fillId = styles.fills.indexOfFirst { it == fillDef }

                sb.append("<xf numFmtId=\"0\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"0\" xfId=\"0\"")
                if (align != null || wrap) {
                    sb.append(">")
                    val horizontal = when (align) {
                        "left" -> "left"
                        "center" -> "center"
                        "right" -> "right"
                        else -> null
                    }
                    sb.append("<alignment")
                    if (horizontal != null) sb.append(" horizontal=\"$horizontal\"")
                    if (wrap) sb.append(" wrapText=\"1\"")
                    sb.append("/>")
                    sb.append("</xf>")
                } else {
                    sb.append("/>")
                }
            }
            sb.append("</cellXfs>")

            sb.append("</styleSheet>")

            zipOut.putNextEntry(ZipEntry("xl/styles.xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeStyles failed", e)
        }
    }

    private fun writeSharedStrings(zipOut: ZipOutputStream, strings: List<String>) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${strings.size}\" uniqueCount=\"${strings.size}\">")
            for (s in strings) {
                sb.append("<si><t>")
                sb.append(escapeXml(s))
                sb.append("</t></si>")
            }
            sb.append("</sst>")

            zipOut.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeSharedStrings failed: strings.size=${strings.size}", e)
        }
    }

    private fun writeSheet(
        zipOut: ZipOutputStream,
        sheet: StyledSheet,
        sheetIndex: Int,
        sharedStrings: List<String>,
        styles: BuiltStyles
    ) {
        try {
            val stringMap = sharedStrings.withIndex().associate { it.value to it.index }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")

            // 列宽
            val colWidths = calculateColumnWidths(sheet)
            if (colWidths.isNotEmpty()) {
                sb.append("<cols>")
                colWidths.forEachIndexed { colIdx, width ->
                    sb.append("<col min=\"${colIdx + 1}\" max=\"${colIdx + 1}\" width=\"$width\" customWidth=\"1\"/>")
                }
                sb.append("</cols>")
            }

            sb.append("<sheetData>")
            for ((rowIdx, row) in sheet.rows.withIndex()) {
                val rowNum = rowIdx + 1
                val heightAttr = if (row.height != null) " ht=\"${row.height}\" customHeight=\"1\"" else ""
                sb.append("<row r=\"$rowNum\"$heightAttr>")
                for ((colIdx, cell) in row.cells.withIndex()) {
                    val colName = columnName(colIdx)
                    val cellRef = "$colName$rowNum"
                    val si = stringMap[cell.value] ?: 0
                    val styleKey = StyleKey(
                        cell.bold, cell.italic, cell.fontSize,
                        cell.color, cell.bg, cell.align, cell.wrap
                    )
                    val styleId = styles.styleMap[styleKey] ?: 0
                    sb.append("<c r=\"$cellRef\" t=\"s\" s=\"$styleId\"><v>$si</v></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData>")

            // 合并单元格
            if (sheet.merges.isNotEmpty()) {
                sb.append("<mergeCells count=\"${sheet.merges.size}\">")
                for (merge in sheet.merges) {
                    sb.append("<mergeCell ref=\"$merge\"/>")
                }
                sb.append("</mergeCells>")
            }

            sb.append("</worksheet>")

            zipOut.putNextEntry(ZipEntry("xl/worksheets/sheet$sheetIndex.xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeSheet failed: sheetIndex=$sheetIndex, sheet.name=${sheet.name}", e)
        }
    }

    private fun calculateColumnWidths(sheet: StyledSheet): List<Double> {
        if (sheet.rows.isEmpty()) return emptyList()
        val maxCols = sheet.rows.maxOf { it.cells.size }
        val widths = MutableList(maxCols) { 8.0 }
        for (row in sheet.rows) {
            for ((colIdx, cell) in row.cells.withIndex()) {
                val len = cell.value.length
                val w = (len * 1.2).coerceAtLeast(8.0).coerceAtMost(50.0)
                if (w > widths[colIdx]) widths[colIdx] = w
            }
        }
        return widths
    }

    // ========== 简单版 ZIP 写入（保留给 generate() 使用） ==========
    private fun writeContentTypesSimple(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
            zipOut.putNextEntry(ZipEntry("[Content_Types].xml"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeContentTypesSimple failed", e)
        }
    }

    private fun writeRelsSimple(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            zipOut.putNextEntry(ZipEntry("_rels/.rels"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeRelsSimple failed", e)
        }
    }

    private fun writeWorkbookSimple(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
            zipOut.putNextEntry(ZipEntry("xl/workbook.xml"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeWorkbookSimple failed", e)
        }
    }

    private fun writeWorkbookRelsSimple(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            zipOut.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeWorkbookRelsSimple failed", e)
        }
    }

    private fun writeStylesSimple(zipOut: ZipOutputStream) {
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1">
    <font><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
  </cellXfs>
</styleSheet>"""
            zipOut.putNextEntry(ZipEntry("xl/styles.xml"))
            zipOut.write(xml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeStylesSimple failed", e)
        }
    }

    private fun collectSharedStrings(rows: List<List<String>>): List<String> {
        val set = LinkedHashSet<String>()
        for (row in rows) {
            for (cell in row) {
                set.add(cell)
            }
        }
        return set.toList()
    }

    private fun writeSheet1Simple(zipOut: ZipOutputStream, rows: List<List<String>>, sharedStrings: List<String>) {
        try {
            val stringMap = sharedStrings.withIndex().associate { it.value to it.index }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            sb.append("<sheetData>")
            for ((rowIdx, row) in rows.withIndex()) {
                sb.append("<row r=\"${rowIdx + 1}\">")
                for ((colIdx, cell) in row.withIndex()) {
                    val colName = columnName(colIdx)
                    val cellRef = "$colName${rowIdx + 1}"
                    val si = stringMap[cell] ?: 0
                    sb.append("<c r=\"$cellRef\" t=\"s\"><v>$si</v></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")

            zipOut.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zipOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Log.e(TAG, "writeSheet1Simple failed: rows.size=${rows.size}", e)
        }
    }

    // ========== 工具方法 ==========
    private fun columnName(index: Int): String {
        var result = ""
        var i = index
        while (i >= 0) {
            result = ('A' + (i % 26)) + result
            i = (i / 26) - 1
        }
        return result
    }

    private fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> {
                    sb.append('&')
                    sb.append('a')
                    sb.append('m')
                    sb.append('p')
                    sb.append(';')
                }
                '<' -> {
                    sb.append('&')
                    sb.append('l')
                    sb.append('t')
                    sb.append(';')
                }
                '>' -> {
                    sb.append('&')
                    sb.append('g')
                    sb.append('t')
                    sb.append(';')
                }
                '"' -> {
                    sb.append('&')
                    sb.append('q')
                    sb.append('u')
                    sb.append('o')
                    sb.append('t')
                    sb.append(';')
                }
                '\'' -> {
                    sb.append('&')
                    sb.append('a')
                    sb.append('p')
                    sb.append('o')
                    sb.append('s')
                    sb.append(';')
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
