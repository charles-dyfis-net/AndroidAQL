package io.github.androidaql.export

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Zero-dependency pure-Kotlin OOXML (`.xlsx`) workbook reader and writer backed by Android's
 * standard `java.util.zip` and `javax.xml.parsers` APIs.
 *
 * Writes `t="inlineStr"` strings, unquoted decimal numbers, booleans, and frozen bold header rows;
 * reads both `inlineStr` and `xl/sharedStrings.xml` workbooks produced by Excel, Google Sheets,
 * LibreOffice Calc, Apple Numbers, or Python/pandas.
 */
object XlsxWorkbook {

    sealed class XlsxCell {
        data class Str(val value: String) : XlsxCell()
        data class IntVal(val value: Long) : XlsxCell()
        data class FloatVal(val value: Double, val decimals: Int = 1) : XlsxCell()
        data class BoolVal(val value: Boolean) : XlsxCell()
        object Blank : XlsxCell()
    }

    data class XlsxSheet(
        val name: String,
        val rows: List<List<XlsxCell>>
    )

    private const val MAX_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L

    fun writeToBytes(sheets: List<XlsxSheet>): ByteArray {
        val baos = ByteArrayOutputStream(16 * 1024)
        write(sheets, baos)
        return baos.toByteArray()
    }

    private class SharedStringBuilder {
        private val indexByText = LinkedHashMap<String, Int>()
        private val unique = ArrayList<String>()
        var totalRefs: Int = 0
            private set

        fun intern(rawSanitized: String): Int {
            totalRefs++
            return indexByText.getOrPut(rawSanitized) {
                val idx = unique.size
                unique.add(rawSanitized)
                idx
            }
        }

        fun items(): List<String> = unique
    }

    fun write(sheets: List<XlsxSheet>, output: OutputStream) {
        val safeSheets = sanitizeSheetNames(sheets)
        val sst = SharedStringBuilder()
        val sheetXmlList = ArrayList<String>(safeSheets.size)
        for (i in safeSheets.indices) {
            sheetXmlList.add(buildSheetXml(safeSheets[i], sst, isSelectedTab = (i == 0)))
        }
        val zos = ZipOutputStream(output, Charsets.UTF_8)
        try {
            writeEntry(zos, "[Content_Types].xml", buildContentTypesXml(safeSheets.size))
            writeEntry(zos, "_rels/.rels", buildRootRelsXml())
            writeEntry(zos, "xl/workbook.xml", buildWorkbookXml(safeSheets))
            writeEntry(zos, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml(safeSheets.size))
            writeEntry(zos, "xl/styles.xml", buildStylesXml())
            writeEntry(zos, "xl/theme/theme1.xml", buildThemeXml())
            writeEntry(zos, "xl/sharedStrings.xml", buildSharedStringsXml(sst))
            for (i in sheetXmlList.indices) {
                writeEntry(zos, "xl/worksheets/sheet${i + 1}.xml", sheetXmlList[i])
            }
            zos.finish()
        } finally {
            zos.flush()
        }
    }

    fun read(input: InputStream): Map<String, List<List<String>>> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input, Charsets.UTF_8).use { zis ->
            val buf = ByteArray(8192)
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val normName = entry.name.replace('\\', '/').trimStart('/')
                val out = ByteArrayOutputStream()
                while (true) {
                    val read = zis.read(buf)
                    if (read <= 0) break
                    totalBytes += read.toLong()
                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                        "XLSX archive exceeds maximum uncompressed limit ($MAX_UNCOMPRESSED_BYTES bytes)."
                    }
                    out.write(buf, 0, read)
                }
                entries[normName] = out.toByteArray()
                zis.closeEntry()
            }
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetPaths = resolveSheetPaths(entries)

        val result = LinkedHashMap<String, List<List<String>>>()
        for ((sheetName, path) in sheetPaths) {
            val xmlBytes = entries[path] ?: continue
            result[sheetName] = parseSheetXml(xmlBytes, sharedStrings)
        }
        return result
    }

    fun columnIndexToLetters(colIdx: Int): String {
        require(colIdx >= 0) { "Column index must be non-negative: $colIdx" }
        var n = colIdx + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun cellRefToColumnIndex(cellRef: String): Int {
        var col = 0
        var seenLetter = false
        for (ch in cellRef) {
            val upper = ch.uppercaseChar()
            if (upper in 'A'..'Z') {
                seenLetter = true
                col = col * 26 + (upper.code - 'A'.code + 1)
            } else if (seenLetter) {
                break
            }
        }
        return if (seenLetter) (col - 1).coerceAtLeast(0) else 0
    }

    private fun sanitizeSheetNames(sheets: List<XlsxSheet>): List<XlsxSheet> {
        val used = HashSet<String>()
        return sheets.mapIndexed { idx, sheet ->
            val cleaned = sheet.name
                .replace(Regex("[:\\\\/?*\\[\\]]"), "_")
                .trim('\'', ' ')
                .ifEmpty { "Sheet${idx + 1}" }
            var candidate = cleaned.take(31)
            var suffix = 2
            while (used.contains(candidate.lowercase(Locale.US))) {
                val tag = "_$suffix"
                candidate = cleaned.take((31 - tag.length).coerceAtLeast(1)) + tag
                suffix++
            }
            used.add(candidate.lowercase(Locale.US))
            sheet.copy(name = candidate)
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, xmlText: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(xmlText.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypesXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sb.append("""<Override PartName="/xl/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        sb.append("""<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>""")
        for (i in 1..sheetCount) {
            sb.append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun buildRootRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun buildWorkbookXml(sheets: List<XlsxSheet>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("<sheets>")
        for (i in sheets.indices) {
            sb.append("""<sheet name="${escapeXmlAttr(sheets[i].name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets>")
        sb.append("</workbook>")
        return sb.toString()
    }

    private fun buildWorkbookRelsXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            sb.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        val nextId = sheetCount + 1
        sb.append("""<Relationship Id="rId$nextId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("""<Relationship Id="rId${nextId + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>""")
        sb.append("""<Relationship Id="rId${nextId + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun buildStylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FFE2E8F0"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""

    private fun buildThemeXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">""" +
            """<a:themeElements>""" +
            """<a:clrScheme name="Office">""" +
            """<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>""" +
            """<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="1E293B"/></a:dk2>""" +
            """<a:lt2><a:srgbClr val="E2E8F0"/></a:lt2>""" +
            """<a:accent1><a:srgbClr val="0284C7"/></a:accent1>""" +
            """<a:accent2><a:srgbClr val="0D9488"/></a:accent2>""" +
            """<a:accent3><a:srgbClr val="D97706"/></a:accent3>""" +
            """<a:accent4><a:srgbClr val="7C3AED"/></a:accent4>""" +
            """<a:accent5><a:srgbClr val="059669"/></a:accent5>""" +
            """<a:accent6><a:srgbClr val="DC2626"/></a:accent6>""" +
            """<a:hlink><a:srgbClr val="0369A1"/></a:hlink>""" +
            """<a:folHlink><a:srgbClr val="6D28D9"/></a:folHlink>""" +
            """</a:clrScheme>""" +
            """<a:fontScheme name="Office">""" +
            """<a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>""" +
            """</a:fontScheme>""" +
            """<a:fmtScheme name="Office">""" +
            """<a:fillStyleLst>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"/></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"/></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>""" +
            """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"/></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"/></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>""" +
            """</a:fillStyleLst>""" +
            """<a:lnStyleLst>""" +
            """<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
            """<a:ln w="25400"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
            """<a:ln w="38100"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
            """</a:lnStyleLst>""" +
            """<a:effectStyleLst>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """</a:effectStyleLst>""" +
            """<a:bgFillStyleLst>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"/></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"/></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>""" +
            """</a:bgFillStyleLst>""" +
            """</a:fmtScheme>""" +
            """</a:themeElements>""" +
            """<a:objectDefaults/>""" +
            """<a:extraClrSchemeLst/>""" +
            """</a:theme>"""

    private fun buildSharedStringsXml(sst: SharedStringBuilder): String {
        val items = sst.items()
        val sb = StringBuilder(maxOf(256, items.size * 32))
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sst.totalRefs}" uniqueCount="${items.size}">""")
        for (item in items) {
            val preserve = item.isNotEmpty() &&
                (item.first().isWhitespace() || item.last().isWhitespace() || item.contains('\n'))
            val spaceAttr = if (preserve) """ xml:space="preserve"""" else ""
            sb.append("""<si><t$spaceAttr>${escapeXmlText(item)}</t></si>""")
        }
        sb.append("</sst>")
        return sb.toString()
    }

    private fun buildSheetXml(
        sheet: XlsxSheet,
        sst: SharedStringBuilder,
        isSelectedTab: Boolean
    ): String {
        val sb = StringBuilder(4096)
        val rows = sheet.rows
        val maxCols = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val dimRef = if (rows.isEmpty()) {
            "A1"
        } else {
            "A1:${columnIndexToLetters(maxCols - 1)}${rows.size}"
        }
        val tabSelAttr = if (isSelectedTab) """ tabSelected="1"""" else ""

        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<dimension ref="$dimRef"/>""")
        sb.append("""<sheetViews><sheetView$tabSelAttr workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("""<sheetFormatPr defaultRowHeight="15"/>""")
        sb.append("<sheetData>")

        for (rIdx in rows.indices) {
            val rowNum = rIdx + 1
            val isHeader = (rIdx == 0)
            val cells = rows[rIdx]
            sb.append("""<row r="$rowNum">""")
            for (cIdx in cells.indices) {
                val cell = cells[cIdx]
                if (cell is XlsxCell.Blank && !isHeader) continue
                val ref = "${columnIndexToLetters(cIdx)}$rowNum"
                val styleAttr = if (isHeader) """ s="1"""" else ""
                when (cell) {
                    is XlsxCell.Blank -> {
                        if (isHeader) {
                            sb.append("""<c r="$ref"$styleAttr/>""")
                        }
                    }
                    is XlsxCell.Str -> {
                        val sanitized = stripIllegalXmlChars(cell.value)
                        val sstIdx = sst.intern(sanitized)
                        sb.append("""<c r="$ref"$styleAttr t="s"><v>$sstIdx</v></c>""")
                    }
                    is XlsxCell.IntVal -> {
                        sb.append("""<c r="$ref"$styleAttr><v>${cell.value}</v></c>""")
                    }
                    is XlsxCell.FloatVal -> {
                        if (cell.value.isFinite()) {
                            val dec = cell.decimals.coerceIn(0, 6)
                            val formatted = String.format(Locale.US, "%.${dec}f", cell.value)
                            sb.append("""<c r="$ref"$styleAttr><v>$formatted</v></c>""")
                        }
                    }
                    is XlsxCell.BoolVal -> {
                        val bit = if (cell.value) "1" else "0"
                        sb.append("""<c r="$ref"$styleAttr t="b"><v>$bit</v></c>""")
                    }
                }
            }
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun stripIllegalXmlChars(text: String): String {
        var needsStrip = false
        for (ch in text) {
            val code = ch.code
            if (code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                needsStrip = true
                break
            }
        }
        if (!needsStrip) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if (code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun escapeXmlText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun escapeXmlAttr(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in stripIllegalXmlChars(text)) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseXmlDocument(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        isXIncludeAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun localNameOf(node: Node): String =
        node.localName ?: node.nodeName.substringAfter(':')

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = parseXmlDocument(bytes)
        val result = mutableListOf<String>()
        val root = doc.documentElement ?: return result
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localNameOf(child) != "si") continue
            val sb = StringBuilder()
            collectTextNodes(child, sb)
            result.add(sb.toString())
        }
        return result
    }

    private fun collectTextNodes(el: Element, out: StringBuilder) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) {
                if (localNameOf(n) == "t") {
                    out.append(n.textContent ?: "")
                } else {
                    collectTextNodes(n, out)
                }
            }
        }
    }

    private fun resolveSheetPaths(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val relTargetById = mutableMapOf<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { relBytes ->
            val relDoc = parseXmlDocument(relBytes)
            val root = relDoc.documentElement
            if (root != null) {
                val nodes = root.childNodes
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    if (localNameOf(el) != "Relationship") continue
                    val id = el.getAttribute("Id")
                    val target = el.getAttribute("Target").replace('\\', '/')
                    if (id.isNotBlank() && target.isNotBlank()) {
                        val normalized = if (target.startsWith("/")) {
                            target.trimStart('/')
                        } else if (target.startsWith("xl/")) {
                            target
                        } else {
                            "xl/${target.trimStart('/')}"
                        }
                        relTargetById[id] = normalized
                    }
                }
            }
        }

        val ordered = mutableListOf<Pair<String, String>>()
        entries["xl/workbook.xml"]?.let { wbBytes ->
            val wbDoc = parseXmlDocument(wbBytes)
            val sheetsList = wbDoc.getElementsByTagNameNS("*", "sheet")
            for (i in 0 until sheetsList.length) {
                val el = sheetsList.item(i) as? Element ?: continue
                val name = el.getAttribute("name").ifBlank { "Sheet${i + 1}" }
                val rId = el.getAttributeNS(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                    "id"
                ).ifBlank { el.getAttribute("r:id") }
                val target = relTargetById[rId] ?: "xl/worksheets/sheet${i + 1}.xml"
                if (entries.containsKey(target)) {
                    ordered.add(name to target)
                }
            }
        }

        if (ordered.isEmpty()) {
            val fallbackSheets = entries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
            for ((idx, path) in fallbackSheets.withIndex()) {
                ordered.add("Sheet${idx + 1}" to path)
            }
        }
        return ordered
    }

    private fun parseSheetXml(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val doc = parseXmlDocument(bytes)
        val rowsOut = mutableListOf<List<String>>()
        val rowElements = doc.getElementsByTagNameNS("*", "row")
        for (i in 0 until rowElements.length) {
            val rowEl = rowElements.item(i) as? Element ?: continue
            val rowCells = mutableListOf<String>()
            var fallbackCol = 0
            val childNodes = rowEl.childNodes
            for (j in 0 until childNodes.length) {
                val cellEl = childNodes.item(j) as? Element ?: continue
                if (localNameOf(cellEl) != "c") continue
                val ref = cellEl.getAttribute("r")
                val colIdx = if (ref.isNotBlank()) cellRefToColumnIndex(ref) else fallbackCol
                fallbackCol = colIdx + 1
                while (rowCells.size <= colIdx) {
                    rowCells.add("")
                }
                rowCells[colIdx] = extractCellValue(cellEl, sharedStrings)
            }
            rowsOut.add(rowCells)
        }
        return rowsOut
    }

    private fun extractCellValue(cellEl: Element, sharedStrings: List<String>): String {
        val type = cellEl.getAttribute("t")
        var vText: String? = null
        var isEl: Element? = null
        val children = cellEl.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            when (localNameOf(child)) {
                "v" -> vText = child.textContent ?: ""
                "is" -> isEl = child
            }
        }
        return when (type) {
            "inlineStr" -> {
                if (isEl != null) {
                    val sb = StringBuilder()
                    collectTextNodes(isEl, sb)
                    sb.toString()
                } else {
                    vText ?: ""
                }
            }
            "s" -> {
                val idx = vText?.trim()?.toIntOrNull()
                if (idx != null) sharedStrings.getOrElse(idx) { "" } else ""
            }
            "b" -> {
                val raw = vText?.trim().orEmpty()
                if (raw == "1" || raw.equals("true", ignoreCase = true)) "true" else "false"
            }
            "str", "e" -> vText ?: ""
            else -> {
                if (vText != null) {
                    vText
                } else if (isEl != null) {
                    val sb = StringBuilder()
                    collectTextNodes(isEl, sb)
                    sb.toString()
                } else {
                    ""
                }
            }
        }
    }
}
