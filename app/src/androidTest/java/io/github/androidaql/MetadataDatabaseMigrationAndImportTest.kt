package io.github.androidaql

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.androidaql.export.XlsxExporter
import io.github.androidaql.export.XlsxImporter
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.KnownPlace
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class MetadataDatabaseMigrationAndImportTest {

    private fun compactSpecs(slotCount: Int = 64) = listOf(
        AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = slotCount)
    )

    private fun writeRingSample(
        ringStore: AqlRingBufferStore,
        ts: Long,
        co2: Int,
        knownPlaceId: Int = 0
    ) {
        ringStore.writeSample(
            AqlRingBufferStore.RawSampleInput(
                timestampEpochSec = ts,
                knownPlaceId = knownPlaceId,
                co2Ppm = co2,
                pm25Ugm3 = 8,
                pm10Ugm3 = 12,
                tempC = 20.5f,
                humidityPct = 43.0f,
                batteryPct = 85,
                rssiDbm = -60,
                speedMps = if (knownPlaceId == 0) 1.4f else null,
                gpsAccuracyM = null
            )
        )
    }

    private fun createLegacyBaseSchema(db: SQLiteDatabase, includeIsManual: Boolean) {
        db.execSQL(
            """
            CREATE TABLE monitor_device (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mac_address TEXT NOT NULL UNIQUE,
                model_code TEXT NOT NULL,
                nickname TEXT NOT NULL,
                is_active_portable INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE known_place (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE known_wifi_network (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                known_place_id INTEGER NOT NULL,
                ssid TEXT NOT NULL,
                bssid_list TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(known_place_id) REFERENCES known_place(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE segment_preset (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                icon_emoji TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val manualCol = if (includeIsManual) ", is_manual INTEGER NOT NULL DEFAULT 0" else ""
        db.execSQL(
            """
            CREATE TABLE excursion (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT ''$manualCol
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE session_slice (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                excursion_id INTEGER NOT NULL,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER NOT NULL,
                include_in_export INTEGER NOT NULL DEFAULT 1,
                label TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(excursion_id) REFERENCES excursion(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE pinned_slice_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                excursion_id INTEGER NOT NULL,
                slice_id INTEGER,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER NOT NULL,
                raw_samples_payload BLOB NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun readColumnNames(db: SQLiteDatabase, table: String): List<String> {
        val cols = mutableListOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                cols.add(c.getString(nameIdx))
            }
        }
        return cols
    }

    private fun readIndexNames(db: SQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        db.rawQuery("PRAGMA index_list($table)", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                names.add(c.getString(nameIdx))
            }
        }
        return names
    }

    @Test
    fun testSchemaUpgradeFromV1ToV3PreservesDataAndAddsManualFlagAndTimelineNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_upgrade_v1_v3_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            createLegacyBaseSchema(rawDb, includeIsManual = false)
            rawDb.insert(
                "monitor_device",
                null,
                ContentValues().apply {
                    put("mac_address", "58:2D:34:12:AB:CD")
                    put("model_code", "CGDN1")
                    put("nickname", "Legacy Monitor")
                    put("is_active_portable", 1)
                }
            )
            val excId = rawDb.insert(
                "excursion",
                null,
                ContentValues().apply {
                    put("device_id", 1L)
                    put("start_epoch_sec", 1500L)
                    put("end_epoch_sec", 1800L)
                    put("is_pinned", 0)
                    put("title", "Legacy Walk")
                    put("notes", "v1 notes")
                }
            )
            rawDb.insert(
                "session_slice",
                null,
                ContentValues().apply {
                    put("excursion_id", excId)
                    put("start_epoch_sec", 1500L)
                    put("end_epoch_sec", 1800L)
                    put("include_in_export", 1)
                    put("label", "Walk")
                    put("notes", "Slice note")
                }
            )
            rawDb.version = 1
        } finally {
            rawDb.close()
        }

        val db = MetadataDatabase(context, dbName)
        try {
            assertEquals(MetadataDatabase.DB_VERSION, db.readableDatabase.version)

            val preserved = db.getExcursions()
            assertEquals(1, preserved.size)
            assertEquals("Legacy Walk", preserved[0].title)
            assertEquals("v1 notes", preserved[0].notes)
            assertFalse(preserved[0].isManual)

            val slices = db.getSlicesForExcursion(preserved[0].id)
            assertEquals(1, slices.size)
            assertEquals("Walk", slices[0].label)
            assertEquals("Slice note", slices[0].notes)

            assertEquals(
                listOf("id", "timestamp_epoch_sec", "text", "created_epoch_sec"),
                readColumnNames(db.readableDatabase, "timeline_note")
            )
            assertTrue(readIndexNames(db.readableDatabase, "timeline_note").contains("idx_timeline_note_ts"))

            val note = db.addTimelineNote(1600L, "v1 upgraded note", createdEpochSec = 1600L)
            assertEquals(1, db.getTimelineNotesInRange(1500L, 1800L).size)
            db.updateTimelineNote(note.id, "edited v1 note")
            assertEquals("edited v1 note", db.getTimelineNotesInRange(1500L, 1800L).single().text)
            db.deleteTimelineNote(note.id)
            assertTrue(db.getTimelineNotesInRange(1500L, 1800L).isEmpty())

            val newManual = db.startExcursion(1L, 2400L, "Manual V3", isManual = true)
            assertTrue(newManual.isManual)
            assertTrue(db.getExcursions().first { it.id == newManual.id }.isManual)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun testSchemaUpgradeFromV2ToV3PreservesManualFlagAndAddsTimelineNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_upgrade_v2_v3_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            createLegacyBaseSchema(rawDb, includeIsManual = true)
            rawDb.insert(
                "monitor_device",
                null,
                ContentValues().apply {
                    put("mac_address", "58:2D:34:12:AB:CD")
                    put("model_code", "CGDN1")
                    put("nickname", "V2 Monitor")
                    put("is_active_portable", 1)
                }
            )
            rawDb.insert(
                "excursion",
                null,
                ContentValues().apply {
                    put("device_id", 1L)
                    put("start_epoch_sec", 1005L)
                    put("end_epoch_sec", 1305L)
                    put("is_pinned", 0)
                    put("title", "Auto V2")
                    put("notes", "")
                    put("is_manual", 0)
                }
            )
            rawDb.insert(
                "excursion",
                null,
                ContentValues().apply {
                    put("device_id", 1L)
                    put("start_epoch_sec", 2010L)
                    put("end_epoch_sec", 2310L)
                    put("is_pinned", 0)
                    put("title", "Manual V2")
                    put("notes", "")
                    put("is_manual", 1)
                }
            )
            rawDb.version = 2
        } finally {
            rawDb.close()
        }

        val db = MetadataDatabase(context, dbName)
        try {
            assertEquals(MetadataDatabase.DB_VERSION, db.readableDatabase.version)

            val byTitle = db.getExcursions().associateBy { it.title }
            assertEquals(2, byTitle.size)
            assertFalse(byTitle.getValue("Auto V2").isManual)
            assertTrue(byTitle.getValue("Manual V2").isManual)

            assertEquals(
                listOf("id", "timestamp_epoch_sec", "text", "created_epoch_sec"),
                readColumnNames(db.readableDatabase, "timeline_note")
            )
            assertTrue(readIndexNames(db.readableDatabase, "timeline_note").contains("idx_timeline_note_ts"))

            db.addTimelineNote(2100L, "Post-upgrade marker", createdEpochSec = 2100L)
            val notes = db.getTimelineNotesInRange(2000L, 2400L)
            assertEquals(1, notes.size)
            assertEquals("Post-upgrade marker", notes[0].text)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun testTimelineNotesCrudPruningAndShortBlipProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_notes_pruning_${System.nanoTime()}.db"
        val tmpDir = Files.createTempDirectory("aql_notes_prune").toFile()
        val db = MetadataDatabase(context, dbName)
        val ringStore = AqlRingBufferStore(File(tmpDir, "ring.aqlring"), 1L, compactSpecs(64))
        try {
            db.upsertMonitorDevice("58:2D:34:12:AB:CD", "CGDN1", "Monitor")

            // 1. Timeline Note CRUD, blank fallback, range ordering, and delete
            val nLate = db.addTimelineNote(1560L, "Temporary marker", createdEpochSec = 1560L)
            val nEarly = db.addTimelineNote(1515L, "First marker", createdEpochSec = 1515L)
            val nMid = db.addTimelineNote(1530L, "Middle marker", createdEpochSec = 1530L)
            db.updateTimelineNote(nMid.id, "   ")
            assertEquals(
                MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT,
                db.getTimelineNotesInRange(1530L, 1530L).single().text
            )
            db.updateTimelineNote(nMid.id, "Updated note")
            db.deleteTimelineNote(nLate.id)
            val crudRange = db.getTimelineNotesInRange(1515L, 1560L)
            assertEquals(listOf(1515L, 1530L), crudRange.map { it.timestampEpochSec })
            assertEquals(listOf("First marker", "Updated note"), crudRange.map { it.text })
            db.deleteTimelineNote(nEarly.id)
            db.deleteTimelineNote(nMid.id)
            assertTrue(db.getTimelineNotesInRange(0L, 5000L).isEmpty())

            // 2. Write ring samples in strictly ascending 15s-aligned chronological order.
            // Window = 64 * 15s = 960s. First trip (1005..1125) is pinned before the ring rotates.
            for (ts in 1005L..1125L step 15L) {
                writeRingSample(ringStore, ts, co2 = 620, knownPlaceId = 0)
            }
            val pinnedTrip = db.startExcursion(1L, 1005L, "Trip (Old Pinned)")
            db.updateOngoingExcursionEnd(pinnedTrip.id, 1125L, closeExcursion = true)
            db.setExcursionPinned(pinnedTrip.id, true, ringStore)
            val expiredPinnedNote = db.addTimelineNote(1065L, "Pinned historical note", createdEpochSec = 1065L)
            val expiredLooseNote = db.addTimelineNote(900L, "Expired unpinned note", createdEpochSec = 900L)

            // Two 60s (< 180s auto-discard threshold) auto-titled unpinned blips inside active ring window
            for (ts in 1995L..2055L step 15L) {
                writeRingSample(ringStore, ts, co2 = 740, knownPlaceId = 1)
            }
            val protectedBlip = db.startExcursion(1L, 1995L, "${MetadataDatabase.AUTO_TRIP_TITLE_PREFIX}10:00)")
            db.updateOngoingExcursionEnd(protectedBlip.id, 2055L, closeExcursion = true)
            val blipNote = db.addTimelineNote(2025L, "Kept blip alive", createdEpochSec = 2025L)

            for (ts in 2295L..2355L step 15L) {
                writeRingSample(ringStore, ts, co2 = 540, knownPlaceId = 1)
            }
            val untouchedBlip = db.startExcursion(1L, 2295L, "${MetadataDatabase.AUTO_TRIP_TITLE_PREFIX}10:05)")
            db.updateOngoingExcursionEnd(untouchedBlip.id, 2355L, closeExcursion = true)

            writeRingSample(ringStore, 2505L, co2 = 480, knownPlaceId = 1)
            val recentNote = db.addTimelineNote(2505L, "Recent active-window note", createdEpochSec = 2505L)

            // nowSec = 2610 -> cutoff = 2610 - 960 = 1650; both blips are > 90s past end and still in ring
            db.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec = 2610L)

            val keptIds = db.getExcursions().map { it.id }.toSet()
            assertTrue("Pinned old trip must remain", pinnedTrip.id in keptIds)
            assertTrue("Short auto blip with timeline note must be protected", protectedBlip.id in keptIds)
            assertFalse("Untouched <3m blip without notes must be auto-discarded", untouchedBlip.id in keptIds)

            val protectedRingSlots = ringStore.queryRange(0, 1995L, 2055L)
            assertEquals(5, protectedRingSlots.size)
            assertTrue(
                "Note-protected blip must clear knownPlaceId=0 in ring buffer",
                protectedRingSlots.all { it.knownPlaceId == 0 }
            )

            val survivingNoteIds = db.getTimelineNotesInRange(0L, 5000L).map { it.id }.toSet()
            assertFalse("Expired unpinned note outside pinned trips must be pruned", expiredLooseNote.id in survivingNoteIds)
            assertTrue("Expired note inside pinned trip must survive", expiredPinnedNote.id in survivingNoteIds)
            assertTrue("Note protecting recent blip must survive", blipNote.id in survivingNoteIds)
            assertTrue("Recent active-window note must survive", recentNote.id in survivingNoteIds)
        } finally {
            ringStore.close()
            db.close()
            context.deleteDatabase(dbName)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testXlsxImportPreservingTimestampsPinnedOverlayAndNoteDeduplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_xlsx_preserving_${System.nanoTime()}.db"
        val tmpDir = Files.createTempDirectory("aql_xlsx_preserve").toFile()
        val db = MetadataDatabase(context, dbName)
        val ringStore = AqlRingBufferStore(File(tmpDir, "ring.aqlring"), 1L, compactSpecs(64))
        try {
            val device = MonitorDevice(1L, "AA:BB:CC:DD:EE:FF", "CGDN1", "Imported Monitor", true)
            val excursion = ExcursionRecord(7L, 1L, 1500L, 1545L, true, "Exported Commute", "Round-trip notes")
            val slices = listOf(
                SessionSliceRecord(10L, 7L, 1500L, 1515L, true, "Outdoor Walk", "Dry air"),
                SessionSliceRecord(11L, 7L, 1515L, 1530L, false, "Excluded Gap", "No samples"),
                SessionSliceRecord(12L, 7L, 1530L, 1545L, true, "Home Arrival", "Settled")
            )
            val exportedSlots = listOf(
                AqlRingBufferStore.RingSlotRecord(1500L, 2, 0, 520, 510, 535, 84, 11, 122, 15, 215, 405, 90, -60, 145, 120),
                AqlRingBufferStore.RingSlotRecord(
                    1545L,
                    4,
                    1,
                    1180,
                    1150,
                    1210,
                    142,
                    19,
                    184,
                    24,
                    228,
                    445,
                    88,
                    -58,
                    AqlRingBufferStore.SENTINEL_UINT16,
                    AqlRingBufferStore.SENTINEL_UINT16
                )
            )
            val exportedNotes = listOf(
                TimelineNoteRecord(1L, 1500L, "Left gate", 1500L),
                TimelineNoteRecord(2L, 1530L, "Back at Home", 1530L)
            )
            val xlsxBytes = XlsxExporter.exportExcursionXlsx(
                device = device,
                excursion = excursion,
                slices = slices,
                slots = exportedSlots,
                notes = exportedNotes,
                knownPlacesById = mapOf(1 to KnownPlace(1, "Home")),
                onlySelectedForExport = false
            )
            val parsed = ByteArrayInputStream(xlsxBytes).use { XlsxImporter.parseXlsx(it) }

            // Seed conflicting values in the ring buffer BEFORE importing to verify pinned-blob overlay wins
            writeRingSample(ringStore, 1500L, co2 = 9999, knownPlaceId = 0)
            writeRingSample(ringStore, 1545L, co2 = 8888, knownPlaceId = 0)

            val firstImport = XlsxImporter.importPreservingTimestamps(parsed, db)
            val excId = firstImport.excursionId
            assertNotNull(excId)
            excId!!
            assertEquals(2, firstImport.importedSlotCount)
            assertEquals(3, firstImport.importedSegmentCount)
            assertEquals(2, firstImport.importedNoteCount)

            val exc = db.getExcursions().single { it.id == excId }
            assertTrue(exc.isPinned)
            assertTrue(exc.isManual)
            assertEquals("Exported Commute", exc.title)

            val importedDev = db.getDeviceById(exc.deviceId)
            assertNotNull(importedDev)
            assertEquals("AA:BB:CC:DD:EE:FF", importedDev!!.macAddress)
            assertEquals("Imported Monitor", importedDev.nickname)

            val restoredSlices = db.getSlicesForExcursion(excId)
            assertEquals(3, restoredSlices.size)
            assertEquals(listOf("Outdoor Walk", "Excluded Gap", "Home Arrival"), restoredSlices.map { it.label })
            assertEquals(listOf("Dry air", "No samples", "Settled"), restoredSlices.map { it.notes })
            assertEquals(listOf(true, false, true), restoredSlices.map { it.includeInExport })

            val resolvedInitial = db.resolveSlotsForExcursion(excId, ringStore)
            assertEquals(listOf(520, 1180), resolvedInitial.map { it.co2PpmMean })

            // Rotate ring buffer completely past 1545 (64 slots @ 15s = 960s -> past 4100)
            for (ts in 3000L..4110L step 15L) {
                writeRingSample(ringStore, ts, co2 = 420, knownPlaceId = 1)
            }
            assertTrue(ringStore.queryRange(0, 1500L, 1545L).isEmpty())

            val resolvedAfterRotation = db.resolveSlotsForExcursion(excId, ringStore)
            assertEquals(listOf(1500L, 1545L), resolvedAfterRotation.map { it.bucketEpochSec })
            assertEquals(listOf(520, 1180), resolvedAfterRotation.map { it.co2PpmMean })

            // Re-import the same workbook -> notes must deduplicate cleanly
            val secondImport = XlsxImporter.importPreservingTimestamps(parsed, db)
            assertEquals(0, secondImport.importedNoteCount)
            assertEquals(2, db.getTimelineNotesInRange(1500L, 1545L).size)
        } finally {
            ringStore.close()
            db.close()
            context.deleteDatabase(dbName)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testXlsxImportTimeShiftedForDemoReplayWithBundledAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targetEndEpochSec = 1_800_000_000L // divisible by 15

        val dbName1 = "test_demo_trip_${System.nanoTime()}.db"
        val tmpDir1 = Files.createTempDirectory("aql_demo_trip").toFile()
        val db1 = MetadataDatabase(context, dbName1)
        val ring1 = AqlRingBufferStore(File(tmpDir1, "ring1.aqlring"), 1L, compactSpecs(256))
        try {
            val parsedTrip = context.assets.open("demo/sample_trip.xlsx").use { XlsxImporter.parseXlsx(it) }
            val tripRes = XlsxImporter.importTimeShiftedForDemoReplay(
                parsed = parsedTrip,
                metadataDb = db1,
                ringStore = ring1,
                targetEndEpochSec = targetEndEpochSec
            )
            val excId = tripRes.excursionId
            assertNotNull(excId)
            excId!!
            assertEquals(67, tripRes.importedSlotCount)
            assertEquals(3, tripRes.importedSegmentCount)

            val exc = db1.getExcursions().single { it.id == excId }
            assertFalse(exc.isPinned)
            assertEquals(targetEndEpochSec, exc.endEpochSec)
            assertEquals(3, db1.getSlicesForExcursion(excId).size)

            val resolved = db1.resolveSlotsForExcursion(excId, ring1)
            assertEquals(67, resolved.size)
            assertEquals(targetEndEpochSec, resolved.last().bucketEpochSec)
        } finally {
            ring1.close()
            db1.close()
            context.deleteDatabase(dbName1)
            tmpDir1.deleteRecursively()
        }

        val dbName2 = "test_demo_stat_${System.nanoTime()}.db"
        val tmpDir2 = Files.createTempDirectory("aql_demo_stat").toFile()
        val db2 = MetadataDatabase(context, dbName2)
        val ring2 = AqlRingBufferStore(File(tmpDir2, "ring2.aqlring"), 1L, compactSpecs(256))
        try {
            val parsedStat = context.assets.open("demo/sample_now_stationary.xlsx").use { XlsxImporter.parseXlsx(it) }
            val statRes = XlsxImporter.importTimeShiftedForDemoReplay(
                parsed = parsedStat,
                metadataDb = db2,
                ringStore = ring2,
                targetEndEpochSec = targetEndEpochSec
            )
            assertNull(statRes.excursionId)
            assertTrue(db2.getExcursions().isEmpty())
            assertEquals(60, statRes.importedSlotCount)
            assertTrue(statRes.knownPlaceIds.isNotEmpty())

            val ringSlots = ring2.queryRange(0, targetEndEpochSec - 3600L, targetEndEpochSec)
            assertEquals(60, ringSlots.size)
            assertTrue(ringSlots.all { it.knownPlaceId > 0 })
        } finally {
            ring2.close()
            db2.close()
            context.deleteDatabase(dbName2)
            tmpDir2.deleteRecursively()
        }
    }
}
