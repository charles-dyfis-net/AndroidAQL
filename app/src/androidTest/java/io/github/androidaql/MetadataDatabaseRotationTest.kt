package io.github.androidaql

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.androidaql.export.XlsxExporter
import io.github.androidaql.export.XlsxImporter
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class MetadataDatabaseRotationTest {

    private fun compactSpecs() = listOf(
        AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = 64)
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
                tempC = 19.5f,
                humidityPct = 42.0f,
                batteryPct = 85,
                rssiDbm = -60,
                speedMps = if (knownPlaceId == 0) 1.4f else null,
                gpsAccuracyM = null
            )
        )
    }

    @Test
    fun testInProgressPinnedTripRetainsFullSpanAfterCloseAndRingRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_inprogress_pin_${System.nanoTime()}.db"
        val tmpDir = Files.createTempDirectory("aql_inprog_pin").toFile()
        val db = MetadataDatabase(context, dbName)
        val ringStore = AqlRingBufferStore(File(tmpDir, "ring.aqlring"), 1L, compactSpecs())
        try {
            db.upsertMonitorDevice("EC:4D:3E:7D:79:BC", "CGDN1", "Monitor")
            val exc = db.startExcursion(1L, 1500L, "Live Pinned Walk")
            for (ts in 1500L..1530L step 15L) {
                writeRingSample(ringStore, ts, 600 + (ts - 1500L).toInt())
                db.updateOngoingExcursionEnd(exc.id, ts, closeExcursion = false)
            }

            // Pin while still in progress
            db.setExcursionPinned(exc.id, true, ringStore)

            // Trip continues for several more slots after pinning, then closes via coalesce recovery
            for (ts in 1545L..1590L step 15L) {
                writeRingSample(ringStore, ts, 600 + (ts - 1500L).toInt())
                db.updateOngoingExcursionEnd(exc.id, ts, closeExcursion = false)
            }
            db.updateOngoingExcursionEnd(exc.id, 1590L, closeExcursion = true)
            db.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec = 1600L)

            // Rotate ring buffer completely past 1500..1590 (window = 960s)
            for (ts in 3000L..4100L step 15L) {
                writeRingSample(ringStore, ts, 500, knownPlaceId = 1)
            }
            assertTrue(ringStore.queryRange(0, 1500L, 1590L).isEmpty())

            val resolved = db.resolveSlotsForExcursion(exc.id, ringStore)
            assertEquals(
                listOf(1500L, 1515L, 1530L, 1545L, 1560L, 1575L, 1590L),
                resolved.map { it.bucketEpochSec }
            )
            assertEquals(690, resolved.last().co2PpmMean)
        } finally {
            ringStore.close()
            db.close()
            context.deleteDatabase(dbName)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testCoalesceMicroExcursionsRefreshesPinnedBlobAndProtectsCustomSlices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_coalesce_${System.nanoTime()}.db"
        val tmpDir = Files.createTempDirectory("aql_coalesce").toFile()
        val db = MetadataDatabase(context, dbName)
        val ringStore = AqlRingBufferStore(File(tmpDir, "ring.aqlring"), 1L, compactSpecs())
        try {
            db.upsertMonitorDevice("EC:4D:3E:7D:79:BC", "CGDN1", "Monitor")

            // Part A: Sequential micro-excursions across 30s Wi-Fi flicker where first is pinned
            val e1 = db.startExcursion(1L, 1500L, "Part 1")
            for (ts in 1500L..1560L step 15L) {
                writeRingSample(ringStore, ts, 700 + (ts - 1500L).toInt())
            }
            db.updateOngoingExcursionEnd(e1.id, 1560L, closeExcursion = true, ringStore = ringStore)
            val e1Slice = db.getSlicesForExcursion(e1.id).single()
            db.updateSliceMetadata(e1Slice.id, "Hallway", "Custom label")
            db.setExcursionPinned(e1.id, true, ringStore)

            val e2 = db.startExcursion(1L, 1590L, "Part 2")
            for (ts in 1590L..1650L step 15L) {
                writeRingSample(ringStore, ts, 700 + (ts - 1500L).toInt())
            }
            db.updateOngoingExcursionEnd(e2.id, 1650L, closeExcursion = true, ringStore = ringStore)

            db.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec = 1700L)

            // Rotate ring buffer past 1650
            for (ts in 2700L..3800L step 15L) {
                writeRingSample(ringStore, ts, 500, knownPlaceId = 1)
            }
            assertTrue(ringStore.queryRange(0, 1500L, 1650L).isEmpty())

            val merged = db.getExcursions()
            assertEquals(1, merged.size)
            assertTrue(merged[0].isPinned)
            val slots = db.resolveSlotsForExcursion(merged[0].id, ringStore)
            assertEquals(
                listOf(1500L, 1515L, 1530L, 1545L, 1560L, 1590L, 1605L, 1620L, 1635L, 1650L),
                slots.map { it.bucketEpochSec }
            )

            // Part B: Carved multi-slice trip enclosing a simultaneous/overlapping custom-sliced trip
            for (ts in 4005L..4605L step 15L) {
                writeRingSample(ringStore, ts, 650)
            }
            val carved = db.startExcursion(1L, 4005L, "Carved 4005")
            db.updateOngoingExcursionEnd(carved.id, 4605L, closeExcursion = true)
            val carvedInit = db.getSlicesForExcursion(carved.id)
            assertTrue(db.splitSliceAt(carvedInit[0].id, 4305L))

            val overlap = db.startExcursion(1L, 4110L, "Overlap 4110")
            db.updateOngoingExcursionEnd(overlap.id, 4500L, closeExcursion = true)
            val ovInit = db.getSlicesForExcursion(overlap.id)
            assertTrue(db.splitSliceAt(ovInit[0].id, 4200L))

            db.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec = 4700L)

            val recentExcs = db.getExcursions().filter { it.startEpochSec >= 4000L }
            assertEquals(2, recentExcs.size)
            for (exc in recentExcs) {
                val slices = db.getSlicesForExcursion(exc.id)
                assertEquals(2, slices.size)
                for (s in slices) {
                    assertTrue(
                        "Inverted slice detected: ${s.startEpochSec} > ${s.endEpochSec}",
                        s.endEpochSec > s.startEpochSec
                    )
                }
            }
        } finally {
            ringStore.close()
            db.close()
            context.deleteDatabase(dbName)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testOutOfWindowPinnedSlicingTrimmingXlsxExportAndUnpinnedPruning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_outofwindow_${System.nanoTime()}.db"
        val tmpDir = Files.createTempDirectory("aql_oow").toFile()
        val db = MetadataDatabase(context, dbName)
        val ringStore = AqlRingBufferStore(File(tmpDir, "ring.aqlring"), 1L, compactSpecs())
        try {
            val dev = db.upsertMonitorDevice("EC:4D:3E:7D:79:BC", "CGDN1", "Monitor")

            // Write 1500..1860 continuous slots
            for (ts in 1500L..1860L step 15L) {
                writeRingSample(ringStore, ts, 800 + (ts - 1500L).toInt())
            }

            // Trip 1 (Pinned): 1500..1590
            val trip1 = db.startExcursion(1L, 1500L, "Pinned Old Trip")
            db.updateOngoingExcursionEnd(trip1.id, 1590L, closeExcursion = true)
            db.setExcursionPinned(trip1.id, true, ringStore)

            // Trip 2 (Unpinned, rotated out of ring): 1650..1710 (gap from 1590 is 60s? Keep gap > 90s: 1710..1770)
            val trip2 = db.startExcursion(1L, 1710L, "Unpinned Rotated")
            db.updateOngoingExcursionEnd(trip2.id, 1770L, closeExcursion = true)

            // Trip 3 (Unpinned, still in ring slots if monitor turned off later): 1905..1965
            for (ts in 1905L..1965L step 15L) {
                writeRingSample(ringStore, ts, 900)
            }
            val trip3 = db.startExcursion(1L, 1905L, "Unpinned Monitor Off")
            db.updateOngoingExcursionEnd(trip3.id, 1965L, closeExcursion = true)

            // Rotate ring buffer up to 2700 (64 slots @ 15s = 960s -> oldest live slot = 1755)
            // Oops: make 1755 > 1770 -> rotate to 2850 -> oldest live slot = 1905 (so Trip 2 is empty in ring, Trip 3 is still in ring!)
            for (ts in 1980L..2850L step 15L) {
                writeRingSample(ringStore, ts, 500, knownPlaceId = 1)
            }
            assertTrue(ringStore.queryRange(0, 1500L, 1590L).isEmpty())
            assertTrue(ringStore.queryRange(0, 1710L, 1770L).isEmpty())
            assertFalse(ringStore.queryRange(0, 1905L, 1965L).isEmpty())

            // Simulate monitor off until nowSec = 3100 (3100 - 1965 = 1135s > 960s window)
            db.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec = 3100L)

            val remainingExcs = db.getExcursions()
            assertEquals(
                "Both expired/rotated unpinned trips should be pruned; only pinned Trip 1 remains",
                listOf(trip1.id),
                remainingExcs.map { it.id }
            )
            assertTrue(db.isExcursionOutsideRingWindow(remainingExcs[0], ringStore, nowEpochSec = 3100L))

            // Perform full slicing + divider move + trimming while Trip 1 is completely outside ring window
            val s0 = db.getSlicesForExcursion(trip1.id).single()
            assertTrue(db.splitSliceAt(s0.id, 1545L, ringStore))
            val splitSlices = db.getSlicesForExcursion(trip1.id)
            assertEquals(2, splitSlices.size)
            assertTrue(db.moveSharedDivider(splitSlices[0].id, splitSlices[1].id, 1560L, ringStore))

            assertTrue(db.trimExcursionStart(trip1.id, 1515L, ringStore))
            assertTrue(db.trimExcursionEnd(trip1.id, 1575L, ringStore))

            val trimmedExc = db.getExcursions().single()
            assertEquals(1515L, trimmedExc.startEpochSec)
            assertEquals(1575L, trimmedExc.endEpochSec)

            val trimmedSlices = db.getSlicesForExcursion(trip1.id)
            assertEquals(2, trimmedSlices.size)
            assertEquals(1515L, trimmedSlices[0].startEpochSec)
            assertEquals(1560L, trimmedSlices[0].endEpochSec)
            assertEquals(1560L, trimmedSlices[1].startEpochSec)
            assertEquals(1575L, trimmedSlices[1].endEpochSec)

            val resolvedSlots = db.resolveSlotsForExcursion(trip1.id, ringStore)
            assertEquals(listOf(1515L, 1530L, 1545L, 1560L, 1575L), resolvedSlots.map { it.bucketEpochSec })
            assertEquals(listOf(815, 830, 845, 860, 875), resolvedSlots.map { it.co2PpmMean })

            val xlsxBytes = XlsxExporter.exportSliceXlsx(dev, trimmedExc, trimmedSlices[0], resolvedSlots)
            val parsed = ByteArrayInputStream(xlsxBytes).use { XlsxImporter.parseXlsx(it) }
            val exportedEpochs = parsed.rows.map { it.slot.bucketEpochSec }
            assertEquals(listOf(1515L, 1530L, 1545L, 1560L), exportedEpochs)
            assertTrue(exportedEpochs.contains(1515L))
            assertTrue(exportedEpochs.contains(1560L))
            assertFalse(exportedEpochs.contains(1500L))
            assertFalse(exportedEpochs.contains(1575L))
        } finally {
            ringStore.close()
            db.close()
            context.deleteDatabase(dbName)
            tmpDir.deleteRecursively()
        }
    }
}
