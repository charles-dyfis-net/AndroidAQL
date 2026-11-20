package io.github.androidaql

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.androidaql.export.XlsxExporter
import io.github.androidaql.export.XlsxImporter
import io.github.androidaql.export.XlsxWorkbook
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class XlsxAndroidRuntimeTest {

    @Test
    fun testParseXmlDocumentOnAndroidRuntimeCoversNamespacesEntitiesBomMalformedAndXxeSafety() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val nsDocXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet name="Morning &amp; Noon" sheetId="1" r:id="rId1"/>
                    <sheet name="Clinic Visit" sheetId="2" r:id="rId2"/>
                </sheets>
            </workbook>""".trimIndent()
        val nsDoc = XlsxWorkbook.parseXmlDocument(nsDocXml.toByteArray(Charsets.UTF_8))
        val sheets = nsDoc.getElementsByTagNameNS("*", "sheet")
        assertEquals(2, sheets.length)
        val firstSheet = sheets.item(0) as Element
        assertEquals("Morning & Noon", firstSheet.getAttribute("name"))
        assertEquals(
            "rId1",
            firstSheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        )
        val secondSheet = sheets.item(1) as Element
        assertEquals(
            "rId2",
            secondSheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        )

        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val richXml = """<?xml version="1.0" encoding="UTF-8"?>
            <!-- top comment -->
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si><t xml:space="preserve">  &amp;&lt;&gt;&quot;&apos;&#10;&#x1F4CD; literal: 📍  </t></si>
                <si><t><![CDATA[a < b & c]]></t></si>
            </sst>""".trimIndent()
        val richDoc = XlsxWorkbook.parseXmlDocument(bom + richXml.toByteArray(Charsets.UTF_8))
        val textNodes = richDoc.getElementsByTagNameNS("*", "t")
        assertEquals(2, textNodes.length)
        assertEquals(
            "  &<>\"'\n📍 literal: 📍  ",
            textNodes.item(0).textContent
        )
        assertEquals("a < b & c", textNodes.item(1).textContent)

        val invalidPayloads = listOf(
            ByteArray(0),
            "<root><unclosed>".toByteArray(Charsets.UTF_8),
            """<?xml version="1.0" encoding="UTF-8"?><workbook><sheets><sheet""".toByteArray(Charsets.UTF_8)
        )
        for (bad in invalidPayloads) {
            val threw = runCatching { XlsxWorkbook.parseXmlDocument(bad) }.isFailure
            assertTrue("Malformed/empty XML must fail parsing", threw)
        }

        val secretFile = File(context.cacheDir, "xxe_probe_${System.nanoTime()}.txt")
        try {
            secretFile.writeText("XXE_SECRET_TOKEN_12345", Charsets.UTF_8)
            val xxeXml = """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE root [
                    <!ENTITY ext SYSTEM "${secretFile.toURI().toASCIIString()}">
                ]>
                <root>&ext;</root>""".trimIndent()
            val xxeOutcome = runCatching { XlsxWorkbook.parseXmlDocument(xxeXml.toByteArray(Charsets.UTF_8)) }
            val leaked = xxeOutcome.getOrNull()?.documentElement?.textContent.orEmpty()
            assertFalse(
                "External entity must not leak local file contents on Android XML parser",
                leaked.contains("XXE_SECRET_TOKEN_12345")
            )
        } finally {
            secretFile.delete()
        }
    }

    @Test
    fun testBundledDemoXlsxAssetsParseCleanlyOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val tripParsed = context.assets.open("demo/sample_trip.xlsx").use { XlsxImporter.parseXlsx(it) }
        assertEquals(67, tripParsed.rows.size)
        assertEquals(3, tripParsed.segments.size)
        assertTrue(tripParsed.notes.isEmpty())
        assertEquals("Demo: Breakfast Trip", tripParsed.excursionTitle)
        assertFalse(tripParsed.isStationaryOnly)
        assertTrue(tripParsed.rows.all { it.slot.co2PpmMean > 0 })

        val statParsed = context.assets.open("demo/sample_now_stationary.xlsx").use { XlsxImporter.parseXlsx(it) }
        assertEquals(60, statParsed.rows.size)
        assertTrue(statParsed.segments.isEmpty())
        assertTrue(statParsed.notes.isEmpty())
        assertTrue(statParsed.isStationaryOnly)
    }

    @Test
    fun testWorkbookReadHandlesSharedStringsInlineStrRichTextAndSparseRefsOnAndroid() {
        val roundTripBytes = XlsxWorkbook.writeToBytes(
            listOf(
                XlsxWorkbook.XlsxSheet(
                    name = "Samples",
                    rows = listOf(
                        listOf(
                            XlsxWorkbook.XlsxCell.Str("header_a"),
                            XlsxWorkbook.XlsxCell.Str("header_b"),
                            XlsxWorkbook.XlsxCell.Str("header_c")
                        ),
                        listOf(
                            XlsxWorkbook.XlsxCell.Str("  & < > \" ' \n 📍  "),
                            XlsxWorkbook.XlsxCell.BoolVal(true),
                            XlsxWorkbook.XlsxCell.FloatVal(42.5, decimals = 1)
                        )
                    )
                )
            )
        )
        val roundTrip = ByteArrayInputStream(roundTripBytes).use { XlsxWorkbook.read(it) }
        val roundTripRows = roundTrip["Samples"]
        assertNotNull(roundTripRows)
        roundTripRows!!
        assertEquals(2, roundTripRows.size)
        assertEquals(listOf("header_a", "header_b", "header_c"), roundTripRows[0])
        assertEquals("  & < > \" ' \n 📍  ", roundTripRows[1][0])
        assertEquals("true", roundTripRows[1][1])
        assertEquals("42.5", roundTripRows[1][2])

        val handCraftedBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos, Charsets.UTF_8).use { zos ->
                fun put(name: String, xml: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(xml.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                put(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/></Types>"""
                )
                put(
                    "_rels/.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
                )
                put(
                    "xl/workbook.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Samples" sheetId="1" r:id="rId1"/></sheets></workbook>"""
                )
                put(
                    "xl/_rels/workbook.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
                )
                put(
                    "xl/sharedStrings.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2"><si><t>Header A</t></si><si><r><t>Rich </t></r><r><t>Text</t></r></si></sst>"""
                )
                put(
                    "xl/worksheets/sheet1.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="b"><v>1</v></c></row><row r="2"><c r="A2" t="s"><v>1</v></c><c r="C2" t="inlineStr"><is><t>Inline 📍</t></is></c><c r="AA2"><v>1.794495285E9</v></c></row></sheetData></worksheet>"""
                )
            }
        }.toByteArray()

        val parsed = ByteArrayInputStream(handCraftedBytes).use { XlsxWorkbook.read(it) }
        val rows = parsed["Samples"]!!
        assertEquals(2, rows.size)
        assertEquals(listOf("Header A", "", "true"), rows[0])
        assertEquals(27, rows[1].size)
        assertEquals("Rich Text", rows[1][0])
        assertEquals("", rows[1][1])
        assertEquals("Inline 📍", rows[1][2])
        assertEquals("1.794495285E9", rows[1][26])
    }

    @Test
    fun testFileProviderResolvesExportedXlsxUriAndAllowsRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Travel Monitor", true)
        val excursion = ExcursionRecord(7L, 1L, 1500L, 1545L, true, "Morning Walk & Transit", "Clinic note", isManual = true)
        val slices = listOf(
            SessionSliceRecord(10L, 7L, 1500L, 1515L, true, "Bus", "Outbound"),
            SessionSliceRecord(11L, 7L, 1515L, 1545L, true, "Lobby", "Waiting")
        )
        val slots = listOf(
            AqlRingBufferStore.RingSlotRecord(1500L, 2, 0, 720, 700, 740, 80, 10, 110, 14, 215, 410, 90, -60, 150, 100),
            AqlRingBufferStore.RingSlotRecord(1545L, 3, 0, 1150, 1120, 1180, 120, 15, 160, 20, 226, 440, 88, -58, 0, 120)
        )
        val notes = listOf(
            TimelineNoteRecord(1L, 1500L, "Boarded bus", 1500L),
            TimelineNoteRecord(2L, 1530L, "Arrived", 1530L)
        )

        val xlsxBytes = XlsxExporter.exportExcursionXlsx(
            device = device,
            excursion = excursion,
            slices = slices,
            slots = slots,
            notes = notes
        )
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "android_test_export_${System.nanoTime()}.xlsx")
        try {
            outFile.writeBytes(xlsxBytes)
            val expectedAuthority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, expectedAuthority, outFile)
            assertEquals("content", contentUri.scheme)
            assertEquals(expectedAuthority, contentUri.authority)

            val parsed = context.contentResolver.openInputStream(contentUri)?.use { input ->
                XlsxImporter.parseXlsx(input)
            }
            assertNotNull("Expected ContentResolver to open exported FileProvider URI", parsed)
            parsed!!
            assertEquals("Morning Walk & Transit", parsed.excursionTitle)
            assertEquals(2, parsed.segments.size)
            assertEquals(2, parsed.notes.size)
            assertEquals(2, parsed.rows.size)
            assertEquals(listOf(1500L, 1545L), parsed.rows.map { it.slot.bucketEpochSec })
            assertEquals(listOf(720, 1150), parsed.rows.map { it.slot.co2PpmMean })
        } finally {
            outFile.delete()
        }
    }
}
