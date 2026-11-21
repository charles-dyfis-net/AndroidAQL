package io.github.androidaql

import io.github.androidaql.ble.QingpingBleParser
import io.github.androidaql.ble.QingpingFrameAssembler
import io.github.androidaql.engine.AlertEvaluator
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.engine.GpsMovementFilter
import io.github.androidaql.export.XlsxExporter
import io.github.androidaql.export.XlsxImporter
import io.github.androidaql.export.XlsxWorkbook
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
import io.github.androidaql.ui.DualPaneSlicerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

class AqlCoreEngineTest {

    @Test
    fun testQingpingBleParserDecodesCgdn1CombinedFrame() {
        // Construct a realistic CGDN1 (0x0E) 0xFDCD service data frame:
        // [0] = 0x08 (frame control)
        // [1] = 0x0E (CGDN1 Air Monitor Lite)
        // [2..7] = CD AB 12 34 2D 58 -> MAC 58:2D:34:12:AB:CD
        // TLV 0x01 (len 4): temp = 22.5 C (225 = 0x00E1), humidity = 43.2% (432 = 0x01B0) -> E1 00 B0 01
        // TLV 0x12 (len 4): pm25 = 12 (0x000C), pm10 = 19 (0x0013) -> 0C 00 13 00
        // TLV 0x13 (len 2): co2 = 1145 ppm (0x0479) -> 79 04
        // TLV 0x02 (len 1): battery = 87% (0x57) -> 57
        val hex = "08 0E CD AB 12 34 2D 58 " +
            "01 04 E1 00 B0 01 " +
            "12 04 0C 00 13 00 " +
            "13 02 79 04 " +
            "02 01 57"
        val raw = QingpingBleParser.fromHexString(hex)
        val decoded = QingpingBleParser.parseServiceData(raw, timestampMs = 1700000000000L, rssiDbm = -61)

        assertNotNull(decoded)
        decoded!!
        assertEquals(0x0E, decoded.productId)
        assertTrue(decoded.isAirMonitorLite)
        assertEquals("58:2D:34:12:AB:CD", decoded.macAddress)
        assertEquals(22.5f, decoded.temperatureC!!, 0.01f)
        assertEquals(43.2f, decoded.humidityPct!!, 0.01f)
        assertEquals(12, decoded.pm25Ugm3)
        assertEquals(19, decoded.pm10Ugm3)
        assertEquals(1145, decoded.co2Ppm)
        assertEquals(87, decoded.batteryPct)
        assertEquals(-61, decoded.rssiDbm)
    }

    @Test
    fun testQingpingFrameAssemblerMergesSplitFrames() {
        val assembler = QingpingFrameAssembler(carryForwardWindowMs = 30_000L)

        // Packet 1 carries Temp/Humidity + PM2.5/PM10
        val packet1 = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("08 0E CD AB 12 34 2D 58 01 04 DC 00 90 01 12 04 08 00 0F 00"),
            timestampMs = 1_000_000L,
            rssiDbm = -64
        )!!
        val res1 = assembler.onFrame(packet1)
        assertNull(res1.interPacketDeltaMs)
        assertEquals(22.0f, res1.mergedTemperatureC, 0.01f)
        assertEquals(8, res1.mergedPm25Ugm3)
        assertEquals(0, res1.mergedCo2Ppm)

        // Packet 2 arrives 2 seconds later carrying CO2 (980 ppm = 0x03D4) and Battery (92% = 0x5C)
        val packet2 = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("08 0E CD AB 12 34 2D 58 13 02 D4 03 02 01 5C"),
            timestampMs = 1_002_000L,
            rssiDbm = -62
        )!!
        val res2 = assembler.onFrame(packet2)
        assertEquals(2_000L, res2.interPacketDeltaMs)
        assertEquals(22.0f, res2.mergedTemperatureC, 0.01f)
        assertEquals(8, res2.mergedPm25Ugm3)
        assertEquals(980, res2.mergedCo2Ppm)
        assertEquals(92, res2.mergedBatteryPct)

        // Packet 3 arrives 3 seconds later with NDIR warmup CO2 = 0 ppm (`13 02 00 00`) while Temp/RH are valid;
        // parser treats 0 ppm as null so assembler preserves the 980 ppm reading instead of dropping to 0.
        val warmupPacket = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("0A 24 CD AB 12 34 2D 58 01 04 DC 00 90 01 12 04 08 00 0F 00 13 02 00 00"),
            timestampMs = 1_005_000L,
            rssiDbm = -60
        )!!
        assertNull(warmupPacket.co2Ppm)
        assertFalse(warmupPacket.isShutdownZeroFrame)
        val res3 = assembler.onFrame(warmupPacket)
        assertEquals(980, res3.mergedCo2Ppm)

        // Packet 4 is an all-zero radio shutdown packet (`08 24 ... 01 04 00000000 12 04 00000000 13 02 0000`)
        val shutdownPacket = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("08 24 CD AB 12 34 2D 58 01 04 00 00 00 00 12 04 00 00 00 00 13 02 00 00"),
            timestampMs = 1_006_000L,
            rssiDbm = -60
        )!!
        assertTrue(shutdownPacket.isShutdownZeroFrame)
        assertFalse(shutdownPacket.hasEnvironmentalMetrics)
    }

    @Test
    fun testBtHomeV2ParserDecodesCombinedAndSplitEspHomeFrames() {
        // BTHome v2 unencrypted header: 0x40 (version = 2, unencrypted, no embedded MAC)
        // 0x00 0x2A       -> packet_id = 42
        // 0x01 0x5E       -> battery = 94%
        // 0x16 0x01       -> battery_charging binary sensor (1 byte) = 1 (verifies cursor alignment across binary sensors)
        // 0x02 CA 08      -> temperature = 2250 * 0.01 = 22.50 °C (0x08CA)
        // 0x03 E0 10      -> humidity = 4320 * 0.01 = 43.20 % (0x10E0)
        // 0x0D 0C 00      -> pm2.5 = 12 µg/m³
        // 0x0E 13 00      -> pm10 = 19 µg/m³
        // 0x12 79 04      -> co2 = 1145 ppm (0x0479)
        val bthomeHex = "40 00 2A 01 5E 16 01 02 CA 08 03 E0 10 0D 0C 00 0E 13 00 12 79 04"
        val decoded = QingpingBleParser.parseBtHomeV2ServiceData(
            rawBytes = QingpingBleParser.fromHexString(bthomeHex),
            deviceAddress = "58:2D:34:12:AB:BC",
            deviceName = "ESPHome-CGDN1",
            timestampMs = 1_700_000_000_000L,
            rssiDbm = -55
        )
        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.isBtHomeV2)
        assertTrue(decoded.isAirMonitorLite)
        assertEquals("58:2D:34:12:AB:BC", decoded.macAddress)
        assertEquals(94, decoded.batteryPct)
        assertEquals(22.50f, decoded.temperatureC!!, 0.01f)
        assertEquals(43.20f, decoded.humidityPct!!, 0.01f)
        assertEquals(12, decoded.pm25Ugm3)
        assertEquals(19, decoded.pm10Ugm3)
        assertEquals(1145, decoded.co2Ppm)

        // Also verify BTHome v2 with embedded reversed MAC (device_info = 0x42) and compact 0x45 (0.1°C) + 0x2E (1% RH)
        val embeddedMacHex = "42 BC AB 12 34 2D 58 45 E1 00 2E 2B 12 58 02"
        val decodedEmbedded = QingpingBleParser.parseBtHomeV2ServiceData(
            rawBytes = QingpingBleParser.fromHexString(embeddedMacHex),
            timestampMs = 1_700_000_001_000L,
            rssiDbm = -57
        )!!
        assertEquals("58:2D:34:12:AB:BC", decodedEmbedded.macAddress)
        assertEquals(22.5f, decodedEmbedded.temperatureC!!, 0.01f)
        assertEquals(43.0f, decodedEmbedded.humidityPct!!, 0.01f)
        assertEquals(600, decodedEmbedded.co2Ppm)
    }

    @Test
    fun testRingBufferConsolidationGapClearingAndPinning() {
        val tmpDir = Files.createTempDirectory("aqlring_test").toFile()
        try {
            val ringFile = File(tmpDir, "device_1.aqlring")
            // Use compact test archive to verify full wrap-around and consolidation quickly
            val specs = listOf(
                AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = 64)
            )
            AqlRingBufferStore(ringFile, deviceId = 1L, archiveSpecs = specs).use { store ->
                // Write two samples into the same 15s bucket (ts = 1500 and 1505)
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1500L,
                        knownPlaceId = 0,
                        co2Ppm = 800,
                        pm25Ugm3 = 10,
                        pm10Ugm3 = 14,
                        tempC = 21.0f,
                        humidityPct = 40.0f,
                        batteryPct = 90,
                        rssiDbm = -58,
                        speedMps = 1.40f,
                        gpsAccuracyM = 12.0f
                    )
                )
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1505L,
                        knownPlaceId = 0,
                        co2Ppm = 1000,
                        pm25Ugm3 = 20,
                        pm10Ugm3 = 26,
                        tempC = 21.4f,
                        humidityPct = 42.0f,
                        batteryPct = 89,
                        rssiDbm = -60,
                        speedMps = 1.60f,
                        gpsAccuracyM = 14.0f
                    )
                )

                // Write a sample in the next 15s bucket (ts = 1515)
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1515L,
                        knownPlaceId = 0,
                        co2Ppm = 1200,
                        pm25Ugm3 = 30,
                        pm10Ugm3 = 35,
                        tempC = 21.6f,
                        humidityPct = 43.0f,
                        batteryPct = 88,
                        rssiDbm = -61,
                        speedMps = 0.0f,
                        gpsAccuracyM = 65.0f
                    )
                )

                val arch0Slots = store.queryRange(0, 1500L, 1530L)
                assertEquals(2, arch0Slots.size)
                assertEquals(2, arch0Slots[0].sampleCount)
                assertEquals(900, arch0Slots[0].co2PpmMean)
                assertEquals(800, arch0Slots[0].co2PpmMin)
                assertEquals(1000, arch0Slots[0].co2PpmMax)
                assertEquals(15.0f, arch0Slots[0].pm25Mean, 0.1f)
                assertEquals(20, arch0Slots[0].pm25Max)

                // Verify Pinned Blob extraction and decoding
                val pinnedBlob = store.extractPinnedBlob(1500L, 1515L)
                val decodedPinned = AqlRingBufferStore.decodePinnedBlob(pinnedBlob)
                assertEquals(2, decodedPinned.size)
                assertEquals(1200, decodedPinned[1].co2PpmMax)

                // Verify statistics calculation
                val stats = AqlRingBufferStore.computeSliceStats(decodedPinned, stepSec = 15)
                assertEquals(800, stats.co2Min)
                assertEquals(1200, stats.co2Max)
                assertEquals(1050.0f, stats.co2Mean, 0.1f)

                // Verify Advance-and-Clear when jumping ahead beyond ring size (64 * 15s = 960s)
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 5000L,
                        knownPlaceId = 1,
                        co2Ppm = 550,
                        pm25Ugm3 = 2,
                        pm10Ugm3 = 3,
                        tempC = 20.0f,
                        humidityPct = 35.0f
                    )
                )
                assertTrue(store.queryRange(0, 1500L, 1530L).isEmpty())
                assertEquals(1, store.queryRange(0, 4995L, 5010L).size)
            }

            // Verify in-place migration from legacy 3-archive layout to single 14-day archive
            val legacyFile = File(tmpDir, "legacy_3arch.aqlring")
            val legacySpecs = listOf(
                AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = 64),
                AqlRingBufferStore.ArchiveSpec(stepSec = 300, slotCount = 32),
                AqlRingBufferStore.ArchiveSpec(stepSec = 3600, slotCount = 16)
            )
            AqlRingBufferStore(legacyFile, deviceId = 1L, archiveSpecs = legacySpecs).use { legacyStore ->
                legacyStore.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1500L,
                        knownPlaceId = 0,
                        co2Ppm = 840,
                        pm25Ugm3 = 11,
                        pm10Ugm3 = 16,
                        tempC = 21.5f,
                        humidityPct = 44.0f
                    )
                )
                legacyStore.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1530L,
                        knownPlaceId = 1,
                        co2Ppm = 620,
                        pm25Ugm3 = 4,
                        pm10Ugm3 = 6,
                        tempC = 20.5f,
                        humidityPct = 41.0f
                    )
                )
            }
            val expectedMigratedSize = (
                AqlRingBufferStore.HEADER_SIZE +
                    AqlRingBufferStore.ARCHIVE_DESC_SIZE +
                    64 * AqlRingBufferStore.SLOT_SIZE_BYTES
                ).toLong()
            assertTrue(legacyFile.length() > expectedMigratedSize)

            AqlRingBufferStore(legacyFile, deviceId = 1L, archiveSpecs = specs).use { migratedStore ->
                assertEquals(expectedMigratedSize, legacyFile.length())
                assertEquals(
                    AqlRingBufferStore.HEADER_SIZE + AqlRingBufferStore.ARCHIVE_DESC_SIZE,
                    migratedStore.getArchiveDescriptor(0).dataOffsetBytes
                )
                val migratedSlots = migratedStore.queryRange(startEpochSec = 1500L, endEpochSec = 1530L)
                assertEquals(2, migratedSlots.size)
                assertEquals(1500L, migratedSlots[0].bucketEpochSec)
                assertEquals(840, migratedSlots[0].co2PpmMean)
                assertEquals(1530L, migratedSlots[1].bucketEpochSec)
                assertEquals(620, migratedSlots[1].co2PpmMean)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testStateMachineDecoupledTimeoutsAndBleModes() {
        val sm = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L,
            wifiLossDebounceSec = 0L
        )
        // Verify default mode is PERIODIC_GATT_KEEPALIVE
        assertEquals(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, sm.userExcursionBleMode)

        // 1. At Home (Known Place #1): GPS must be OFF and BLE mode stays PERIODIC_GATT_KEEPALIVE
        //    so offline setup / time-sync keep-alive operates without blocking passive listeners.
        val t1 = sm.onBleAdvertisement(timestampEpochSec = 1000L, connectedKnownPlaceId = 1)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, t1.currentState)
        assertFalse(t1.shouldRunGps)
        assertEquals(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, t1.effectiveBleMode)
        assertEquals(1, t1.activeKnownPlaceId)

        // 2. Leave Home Wi-Fi while BLE is still active -> enters FIELD_EXCURSION_ACTIVE, GPS ON, PERIODIC_GATT_KEEPALIVE
        val t2 = sm.onWifiContextChanged(timestampEpochSec = 1030L, connectedKnownPlaceId = 0)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, t2.currentState)
        assertTrue(t2.shouldRunGps)
        assertTrue(t2.startedNewExcursion)
        assertEquals(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, t2.effectiveBleMode)

        // 3. No BLE packet for 120s (> 90s gpsPauseTimeout, < 2700s excursionCloseTimeout):
        // GPS pauses immediately, but Excursion stays open in FIELD_EXCURSION_SIGNAL_PAUSED!
        val t3 = sm.onTimerTick(timestampEpochSec = 1130L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED, t3.currentState)
        assertFalse(t3.shouldRunGps)
        assertFalse(t3.closedExcursion)

        // 4. BLE packet arrives again 2 minutes later -> resumes GPS in the same Excursion!
        val t4 = sm.onBleAdvertisement(timestampEpochSec = 1250L, connectedKnownPlaceId = 0)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, t4.currentState)
        assertTrue(t4.shouldRunGps)
        assertFalse(t4.startedNewExcursion)

        // 5. Return to Home Wi-Fi 60s later (while BLE is still fresh) -> closes Excursion, stops GPS, keeps PERIODIC_GATT_KEEPALIVE
        val t5 = sm.onWifiContextChanged(timestampEpochSec = 1310L, connectedKnownPlaceId = 1)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, t5.currentState)
        assertFalse(t5.shouldRunGps)
        assertTrue(t5.closedExcursion)
        assertEquals(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, t5.effectiveBleMode)

        // 6. If user explicitly selects PASSIVE_ONLY (e.g. BTHome v2 firmware or external hub), STATIONARY_KNOWN_AP honors PASSIVE_ONLY
        sm.userExcursionBleMode = BleCollectionMode.PASSIVE_ONLY
        val t6 = sm.onTimerTick(timestampEpochSec = 1320L)
        assertEquals(BleCollectionMode.PASSIVE_ONLY, t6.effectiveBleMode)
    }

    @Test
    fun testShutdownZeroFrameIsIgnoredByMetricsAndAssembler() {
        val assembler = QingpingFrameAssembler(carryForwardWindowMs = 30_000L)
        val liveFrame = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("0A24BAAB12342D5801041001950112040100010013023002"),
            timestampMs = 10_000L,
            rssiDbm = -40
        )!!
        assertFalse(liveFrame.isShutdownZeroFrame)
        assertTrue(liveFrame.hasEnvironmentalMetrics)
        val a1 = assembler.onFrame(liveFrame)
        assertEquals(560, a1.mergedCo2Ppm)
        assertEquals(27.2f, a1.mergedTemperatureC, 0.05f)

        // Empirical all-zero shutdown frame emitted when CGDN1 exits unbound pairing mode
        val shutdownFrame = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("0824BAAB12342D5801040000000012040000000013020000"),
            timestampMs = 12_000L,
            rssiDbm = -41
        )!!
        assertTrue(shutdownFrame.isShutdownZeroFrame)
        assertFalse(shutdownFrame.hasEnvironmentalMetrics)
        val a2 = assembler.onFrame(shutdownFrame)
        assertEquals(560, a2.mergedCo2Ppm)
        assertEquals(27.2f, a2.mergedTemperatureC, 0.05f)
    }

    @Test
    fun testAlertEvaluatorInPlaceLifecycleHysteresisAndSnooze() {
        val evaluator = AlertEvaluator(
            co2WarningThresholdPpm = 1000,
            co2CriticalThresholdPpm = 2000,
            co2HysteresisPpm = 150,
            persistenceWindowSec = 30L
        )

        // Initial spike at t=100s (1100 ppm): within 30s persistence window, does not fire yet
        val a1 = evaluator.evaluateSample(100L, co2Ppm = 1100, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.NO_CHANGE, a1.directive)

        // Sustained at t=130s (1250 ppm): triggers new alert
        val a2 = evaluator.evaluateSample(130L, co2Ppm = 1250, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.TRIGGER_NEW_OR_ESCALATE, a2.directive)
        assertEquals(AlertEvaluator.SeverityTier.WARNING, a2.severity)
        assertEquals(1250, a2.peakValueInSpike)

        // Continued elevated at t=145s (1300 ppm): updates in-place silently
        val a3 = evaluator.evaluateSample(145L, co2Ppm = 1300, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.UPDATE_IN_PLACE_SILENT, a3.directive)
        assertEquals(1300, a3.peakValueInSpike)

        // User swipes away notification -> snoozes WARNING tier
        evaluator.onUserDismissedNotification(AlertEvaluator.MetricType.CO2)
        val a4 = evaluator.evaluateSample(160L, co2Ppm = 1350, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.NO_CHANGE, a4.directive)

        // Escalates to CRITICAL (2100 ppm) -> breaks through snooze
        val a5 = evaluator.evaluateSample(175L, co2Ppm = 2100, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.TRIGGER_NEW_OR_ESCALATE, a5.directive)
        assertEquals(AlertEvaluator.SeverityTier.CRITICAL, a5.severity)

        // Drops to 900 ppm (above 1000 - 150 = 850 recovery threshold): still active due to hysteresis
        val a6 = evaluator.evaluateSample(190L, co2Ppm = 900, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.UPDATE_IN_PLACE_SILENT, a6.directive)

        // Drops to 820 ppm (<= 850 recovery threshold): auto-clears in-place!
        val a7 = evaluator.evaluateSample(205L, co2Ppm = 820, pm25Ugm3 = 5).first { it.metric == AlertEvaluator.MetricType.CO2 }
        assertEquals(AlertEvaluator.NotificationDirective.CANCEL_IN_PLACE, a7.directive)

        // Low-Battery Alert Lifecycle (Warning <= 20%, Critical <= 10%, Recovery >= 25%)
        val b1 = evaluator.evaluateSample(300L, co2Ppm = 600, pm25Ugm3 = 4, batteryPct = 18)
            .first { it.metric == AlertEvaluator.MetricType.BATTERY_LOW }
        assertEquals(AlertEvaluator.NotificationDirective.NO_CHANGE, b1.directive)

        val b2 = evaluator.evaluateSample(335L, co2Ppm = 600, pm25Ugm3 = 4, batteryPct = 17)
            .first { it.metric == AlertEvaluator.MetricType.BATTERY_LOW }
        assertEquals(AlertEvaluator.NotificationDirective.TRIGGER_NEW_OR_ESCALATE, b2.directive)
        assertEquals(AlertEvaluator.SeverityTier.WARNING, b2.severity)
        assertEquals(17, b2.peakValueInSpike)

        val b3 = evaluator.evaluateSample(350L, co2Ppm = 600, pm25Ugm3 = 4, batteryPct = 9)
            .first { it.metric == AlertEvaluator.MetricType.BATTERY_LOW }
        assertEquals(AlertEvaluator.NotificationDirective.TRIGGER_NEW_OR_ESCALATE, b3.directive)
        assertEquals(AlertEvaluator.SeverityTier.CRITICAL, b3.severity)
        assertEquals(9, b3.peakValueInSpike)

        // Plugged into USB-C and charged to 26% (>= 20 + 5): auto-clears in-place!
        val b4 = evaluator.evaluateSample(400L, co2Ppm = 600, pm25Ugm3 = 4, batteryPct = 26)
            .first { it.metric == AlertEvaluator.MetricType.BATTERY_LOW }
        assertEquals(AlertEvaluator.NotificationDirective.CANCEL_IN_PLACE, b4.directive)
    }

    @Test
    fun testGpsMovementFilterSuppressesJitterWhilePreservingCumulativeWalking() {
        val filter = GpsMovementFilter(minDeadbandMeters = 6.0f, weakGpsThresholdMeters = 50.0f)
        val baseLat = 41.881832 // Downtown Chicago
        val baseLon = -87.623177
        // 1 meter North in degrees latitude is ~ 1.0 / 111_111.0
        val degPerMeterLat = 1.0 / 111_111.0

        // 1. Initial Anchor Fix (accuracy = 15m)
        val f0 = filter.onLocationFix(baseLat, baseLon, timestampMs = 1_000_000L, horizontalAccuracyM = 15.0f)
        assertEquals(0.0f, f0.speedMps, 0.001f)
        assertTrue(f0.suppressedWithinNoiseRadius)

        // 2. Stationary jitter bouncing back and forth within 8m of the anchor (combined sigma = hypot(15, 15) = 21.2m):
        // All jitter fixes must be suppressed to 0 m/s!
        val jitter1 = filter.onLocationFix(baseLat + 7.0 * degPerMeterLat, baseLon, 1_005_000L, 15.0f)
        val jitter2 = filter.onLocationFix(baseLat - 6.0 * degPerMeterLat, baseLon, 1_010_000L, 15.0f)
        assertEquals(0.0f, jitter1.speedMps, 0.001f)
        assertEquals(0.0f, jitter2.speedMps, 0.001f)
        assertTrue(jitter1.suppressedWithinNoiseRadius)
        assertTrue(jitter2.suppressedWithinNoiseRadius)

        // 3. Steady walking North at 6.5m per 5s fix (1.3 m/s):
        // Even though EACH individual 5-second step (6.5m) is smaller than the 15m GPS inaccuracy radius,
        // by step 4 (cumulative 26.0m > 21.2m combined uncertainty radius), the cumulative anchor filter breaks out!
        val walk1 = filter.onLocationFix(baseLat + 6.5 * degPerMeterLat, baseLon, 1_015_000L, 15.0f)
        val walk2 = filter.onLocationFix(baseLat + 13.0 * degPerMeterLat, baseLon, 1_020_000L, 15.0f)
        val walk3 = filter.onLocationFix(baseLat + 19.5 * degPerMeterLat, baseLon, 1_025_000L, 15.0f)
        assertTrue(walk3.suppressedWithinNoiseRadius)
        val walk4 = filter.onLocationFix(baseLat + 26.0 * degPerMeterLat, baseLon, 1_030_000L, 15.0f)
        assertFalse(walk4.suppressedWithinNoiseRadius)
        assertTrue(walk4.deltaDistanceM >= 25.0f)
        assertTrue(walk4.speedMps > 0.8f)
    }

    @Test
    fun testXlsxWorkbookColumnRefsXmlEscapingAndSharedStringsRead() {
        assertEquals("A", XlsxWorkbook.columnIndexToLetters(0))
        assertEquals("Z", XlsxWorkbook.columnIndexToLetters(25))
        assertEquals("AA", XlsxWorkbook.columnIndexToLetters(26))
        assertEquals("AB", XlsxWorkbook.columnIndexToLetters(27))
        assertEquals(0, XlsxWorkbook.cellRefToColumnIndex("A1"))
        assertEquals(25, XlsxWorkbook.cellRefToColumnIndex("Z99"))
        assertEquals(27, XlsxWorkbook.cellRefToColumnIndex("AB12"))

        val bytes = XlsxWorkbook.writeToBytes(
            listOf(
                XlsxWorkbook.XlsxSheet(
                    name = "Test:Sheet/1?",
                    rows = listOf(
                        listOf(
                            XlsxWorkbook.XlsxCell.Str("col_a"),
                            XlsxWorkbook.XlsxCell.Str("col_b"),
                            XlsxWorkbook.XlsxCell.Str("col_c")
                        ),
                        listOf(
                            XlsxWorkbook.XlsxCell.Str("Quotes \" & <tags>\nLine2 📍"),
                            XlsxWorkbook.XlsxCell.Blank,
                            XlsxWorkbook.XlsxCell.FloatVal(12.34, decimals = 2)
                        )
                    )
                )
            )
        )
        val parsed = java.io.ByteArrayInputStream(bytes).use { XlsxWorkbook.read(it) }
        val sheetRows = parsed["Test_Sheet_1_"]!!
        assertEquals(2, sheetRows.size)
        assertEquals(listOf("col_a", "col_b", "col_c"), sheetRows[0])
        assertEquals("Quotes \" & <tags>\nLine2 📍", sheetRows[1][0])
        assertEquals("", sheetRows[1][1])
        assertEquals("12.34", sheetRows[1][2])

        // Verify sharedStrings.xml (including rich text <r><t>) + sparse column cell refs read properly
        val sharedBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos, Charsets.UTF_8).use { zos ->
                fun put(name: String, xml: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
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
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet1.xml"/></Relationships>"""
                )
                put(
                    "xl/sharedStrings.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2"><si><t>Header A</t></si><si><r><t>Rich </t></r><r><t>Text</t></r></si></sst>"""
                )
                put(
                    "xl/worksheets/sheet1.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="b"><v>1</v></c></row><row r="2"><c r="A2" t="s"><v>1</v></c><c r="C2"><v>1.794495285E9</v></c></row></sheetData></worksheet>"""
                )
            }
        }.toByteArray()

        val sharedRead = java.io.ByteArrayInputStream(sharedBytes).use { XlsxWorkbook.read(it) }
        val rows = sharedRead["Samples"]!!
        assertEquals(listOf("Header A", "", "true"), rows[0])
        assertEquals(listOf("Rich Text", "", "1.794495285E9"), rows[1])
    }

    @Test
    fun testXlsxExporterWholeTripAndSingleSliceFourSheets() {
        val device = MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Travel Monitor", true)
        val excursion = ExcursionRecord(7L, 1L, 1500L, 1545L, true, "Downtown Doctor Visit", "Clinic note")
        val slices = listOf(
            SessionSliceRecord(10L, 7L, 1500L, 1515L, true, "CTA Bus", ""),
            SessionSliceRecord(11L, 7L, 1515L, 1545L, true, "Waiting Room, Fl 10", "Busy")
        )
        val slots = listOf(
            AqlRingBufferStore.RingSlotRecord(1500L, 2, 0, 950, 920, 980, 80, 10, 120, 15, 215, 400, 90, -60, 450, 150),
            AqlRingBufferStore.RingSlotRecord(1515L, 3, 0, 1480, 1450, 1510, 110, 14, 160, 19, 228, 445, 89, -58, 0, 850),
            AqlRingBufferStore.RingSlotRecord(1545L, 4, 0, 1520, 1490, 1550, 120, 16, 170, 20, 230, 450, 88, -59, 0, 850)
        )
        val notes = listOf(
            TimelineNoteRecord(1L, 1500L, "Boarded bus", 1500L),
            TimelineNoteRecord(2L, 1515L, "Entered lobby", 1515L),
            TimelineNoteRecord(3L, 1545L, "Left clinic", 1545L)
        )

        val wholeBytes = XlsxExporter.exportExcursionXlsx(
            device = device,
            excursion = excursion,
            slices = slices,
            slots = slots,
            notes = notes,
            zoneId = ZoneOffset.UTC
        )
        val sheets = java.io.ByteArrayInputStream(wholeBytes).use { XlsxWorkbook.read(it) }
        assertEquals(listOf("Trip", "Segments", "Notes", "Samples"), sheets.keys.toList())

        val tripMap = sheets["Trip"]!!.drop(1).associate { it[0] to it[1] }
        assertEquals("EXCURSION", tripMap["export_scope"])
        assertEquals("Downtown Doctor Visit", tripMap["excursion_title"])
        assertEquals("Clinic note", tripMap["excursion_notes"])
        assertEquals("true", tripMap["is_pinned"])

        val segRows = sheets["Segments"]!!
        assertEquals(3, segRows.size) // header + 2 slices
        assertEquals("1", segRows[1][0])
        assertEquals("CTA Bus", segRows[1][7])
        assertEquals("1", segRows[1][10]) // 1500L only ([1500, 1515))
        assertEquals("2", segRows[2][0])
        assertEquals("2", segRows[2][10]) // 1515L & 1545L ([1515, 1545])

        val noteRows = sheets["Notes"]!!
        assertEquals(4, noteRows.size)
        assertEquals("1", noteRows[1][5]) // 1500 in seg 1
        assertEquals("2", noteRows[2][5]) // 1515 boundary -> seg 2 only
        assertEquals("2", noteRows[3][5]) // 1545 end -> seg 2

        val sliceBytes = XlsxExporter.exportSliceXlsx(
            device = device,
            excursion = excursion,
            slice = slices[1],
            slots = slots,
            notes = notes,
            zoneId = ZoneOffset.UTC
        )
        val sliceSheets = java.io.ByteArrayInputStream(sliceBytes).use { XlsxWorkbook.read(it) }
        val sliceTripMap = sliceSheets["Trip"]!!.drop(1).associate { it[0] to it[1] }
        assertEquals("SLICE", sliceTripMap["export_scope"])
        assertEquals(2, sliceSheets["Segments"]!!.size)
        assertEquals(3, sliceSheets["Notes"]!!.size) // 1515 & 1545 only
        assertEquals(3, sliceSheets["Samples"]!!.size) // 1515 & 1545 only
    }

    @Test
    fun testScrubberBadgeNonOverlapAtPaneBoundariesAndCrossings() {
        val minY = 9.0f
        val maxY = 91.0f
        val minDist = 19.5f

        // 1. Both curves pinned near the bottom border (e.g. CO2=449ppm and PM2.5=0 ug/m3):
        val bottomPair = DualPaneSlicerView.resolveNonOverlappingBadgePair(98.0f, 100.0f, minY, maxY, minDist)
        assertTrue(bottomPair.first >= minY - 0.001f && bottomPair.first <= maxY + 0.001f)
        assertTrue(bottomPair.second >= minY - 0.001f && bottomPair.second <= maxY + 0.001f)
        assertTrue(kotlin.math.abs(bottomPair.second - bottomPair.first) >= minDist - 0.001f)
        assertTrue(bottomPair.first < bottomPair.second)

        // 2. Both curves pinned near the top border:
        val topPair = DualPaneSlicerView.resolveNonOverlappingBadgePair(4.0f, 2.0f, minY, maxY, minDist)
        assertTrue(topPair.first >= minY - 0.001f && topPair.first <= maxY + 0.001f)
        assertTrue(topPair.second >= minY - 0.001f && topPair.second <= maxY + 0.001f)
        assertTrue(kotlin.math.abs(topPair.second - topPair.first) >= minDist - 0.001f)
        // Since yB (2.0f) < yA (4.0f), labelYB must remain above labelYA:
        assertTrue(topPair.second < topPair.first)

        // 3. Curves close together in the middle of the pane:
        val midPair = DualPaneSlicerView.resolveNonOverlappingBadgePair(52.0f, 49.0f, minY, maxY, minDist)
        assertTrue(kotlin.math.abs(midPair.second - midPair.first) >= minDist - 0.001f)
        assertTrue(midPair.second < midPair.first)
    }

    @Test
    fun testClearKnownPlaceInRangeRestoresExcursionSlots() {
        val tempFile = Files.createTempFile("aql_clear_place_test", ".aqlring").toFile()
        try {
            AqlRingBufferStore(tempFile).use { store ->
                val t0 = 1_794_495_300L
                store.writeSample(AqlRingBufferStore.RawSampleInput(t0, knownPlaceId = 1, co2Ppm = 580, pm25Ugm3 = 2, pm10Ugm3 = 2, tempC = 23.8f, humidityPct = 39.0f))
                store.writeSample(AqlRingBufferStore.RawSampleInput(t0 + 15, knownPlaceId = 1, co2Ppm = 415, pm25Ugm3 = 3, pm10Ugm3 = 3, tempC = 18.0f, humidityPct = 42.0f))
                store.writeSample(AqlRingBufferStore.RawSampleInput(t0 + 30, knownPlaceId = 1, co2Ppm = 620, pm25Ugm3 = 1, pm10Ugm3 = 1, tempC = 24.5f, humidityPct = 35.0f))

                // Clear knownPlaceId on the middle slot [t0..t0+15]
                store.clearKnownPlaceInRange(t0, t0 + 15)
                val slots = store.queryRange(0, t0, t0 + 30)
                assertEquals(3, slots.size)
                assertEquals(0, slots[0].knownPlaceId)
                assertEquals(0, slots[1].knownPlaceId)
                assertEquals(1, slots[2].knownPlaceId)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testPairBGattLtvAndWifiCredentialChunkEncoding() {
        // "\"test_ssid_123\",\"test_pass_5678\"" is 32 bytes; + 1 type byte (0x01) = 33 (0x21)
        val chunks = QingpingBleParser.encodePairBWifiCredentialsChunks(
            ssid = "test_ssid_123",
            password = "test_pass_5678"
        )
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(14, chunks[1].size)
        assertEquals(0x21, chunks[0][0].toInt() and 0xFF)
        assertEquals(0x01, chunks[0][1].toInt() and 0xFF)
        assertEquals(0x22, chunks[0][2].toInt() and 0xFF)

        val reassembled = chunks[0] + chunks[1]
        val decodedAscii = String(reassembled.copyOfRange(2, reassembled.size), Charsets.UTF_8)
        assertEquals("\"test_ssid_123\",\"test_pass_5678\"", decodedAscii)
    }

    @Test
    fun testProvisionedNormalModeFrameCtrl0x88() {
        val provisioned0x88 = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("8824BAAB12342D580104F900560112040100010013024402"),
            timestampMs = 1_700_000_000_000L,
            rssiDbm = -45
        )!!
        assertTrue(provisioned0x88.isProvisionedNormalMode)

        val pairing0x0A = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("0A24BAAB12342D580104F900560112040100010013024402"),
            timestampMs = 1_700_000_001_000L,
            rssiDbm = -45
        )!!
        assertFalse(pairing0x0A.isProvisionedNormalMode)

        val pairingConnected0x8A = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("8A24BAAB12342D580104F900560112040100010013024402"),
            timestampMs = 1_700_000_002_000L,
            rssiDbm = -45
        )!!
        assertFalse(pairingConnected0x8A.isProvisionedNormalMode)

        val shutdownZero0x08 = QingpingBleParser.parseServiceData(
            QingpingBleParser.fromHexString("0824BAAB12342D5801040000000012040000000013020000"),
            timestampMs = 1_700_000_003_000L,
            rssiDbm = -45
        )!!
        assertTrue(shutdownZero0x08.isShutdownZeroFrame)
        assertFalse(shutdownZero0x08.isProvisionedNormalMode)
    }

    @Test
    fun testPairBOtaAndMqttChunkEncoding() {
        val otaUrl = "http://192.168.1.50:8000/esphome-cgdn1.bin"
        val otaChunks = QingpingBleParser.encodePairBOtaUpgradeChunks(otaUrl)
        assertEquals(3, otaChunks.size)
        assertEquals(20, otaChunks[0].size)
        assertEquals(20, otaChunks[1].size)
        assertEquals(4, otaChunks[2].size)
        assertEquals(otaUrl.length + 1, otaChunks[0][0].toInt() and 0xFF)
        assertEquals(0x03, otaChunks[0][1].toInt() and 0xFF)
        val reassembledUrl = otaChunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertEquals(otaUrl, String(reassembledUrl.copyOfRange(2, reassembledUrl.size), Charsets.UTF_8))

        val mqttSpec = "127.0.0.1,1883,u,p"
        val mqttChunks = QingpingBleParser.encodePairBMqttServerChunks(mqttSpec)
        assertEquals(1, mqttChunks.size)
        assertEquals(mqttSpec.length + 2, mqttChunks[0].size)
        assertEquals(mqttSpec.length + 1, mqttChunks[0][0].toInt() and 0xFF)
        assertEquals(0x02, mqttChunks[0][1].toInt() and 0xFF)
        assertEquals(mqttSpec, String(mqttChunks[0].copyOfRange(2, mqttChunks[0].size), Charsets.UTF_8))
    }

    @Test
    fun testPairBOtaUrlAndWifiCredentialValidationAndNotifyDecoding() {
        assertFalse(QingpingBleParser.isValidPairBOtaUrl(""))
        assertFalse(QingpingBleParser.isValidPairBOtaUrl("   "))
        val max254Url = "http://x.co/" + "a".repeat(242)
        assertEquals(254, max254Url.toByteArray(Charsets.UTF_8).size)
        assertTrue(QingpingBleParser.isValidPairBOtaUrl(max254Url))
        assertTrue(QingpingBleParser.isValidPairBOtaUrl("http://192.168.1.10/fw?a=1&b=2;v=3"))

        val over255Url = "http://x.co/" + "a".repeat(243)
        assertEquals(255, over255Url.toByteArray(Charsets.UTF_8).size)
        assertFalse(QingpingBleParser.isValidPairBOtaUrl(over255Url))

        assertFalse(QingpingBleParser.isValidPairBWifiCredentials("", "secret123"))
        assertFalse(QingpingBleParser.isValidPairBWifiCredentials("   ", "secret123"))
        assertTrue(QingpingBleParser.isValidPairBWifiCredentials("Guest:2.4G", "pass;with:specials"))
        val maxCredPwd = "b".repeat(254 - "\"s\",\"\"".length)
        assertTrue(QingpingBleParser.isValidPairBWifiCredentials("s", maxCredPwd))
        assertFalse(QingpingBleParser.isValidPairBWifiCredentials("s", maxCredPwd + "c"))

        val p0 = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("020300"))!!
        assertEquals("progress", p0.state)
        assertEquals(0, p0.progressPct)

        val p75 = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("02034B"))!!
        assertEquals("progress", p75.state)
        assertEquals(75, p75.progressPct)

        val p100 = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("020364"))!!
        assertEquals("complete", p100.state)
        assertEquals(100, p100.progressPct)

        val rejShort = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("0203FF"))!!
        assertEquals("rejected", rejShort.state)
        assertEquals(255, rejShort.rawValue)
        assertNull(rejShort.progressPct)

        for ((hex, expectedVal) in listOf("0203FA" to 250, "0203FB" to 251, "0203FC" to 252, "0203FE" to 254)) {
            val errStatus = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString(hex))!!
            assertEquals("rejected", errStatus.state)
            assertEquals(expectedVal, errStatus.rawValue)
            assertNull(errStatus.progressPct)
        }
        assertTrue(QingpingBleParser.describePairBOtaRejectCode(0xFA).contains("CRC32"))

        val ackZero = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("04FF030000"))!!
        assertEquals("progress", ackZero.state)
        assertEquals(0, ackZero.rawValue)
        assertEquals(0, ackZero.progressPct)

        val unauthErr = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("04FF030100"))!!
        assertEquals("rejected", unauthErr.state)
        assertEquals(1, unauthErr.rawValue)
        assertNull(unauthErr.progressPct)

        val rejStd = QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("04FF03FF00"))!!
        assertEquals("rejected", rejStd.state)
        assertEquals(255, rejStd.rawValue)
        assertNull(rejStd.progressPct)

        assertNull(QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("0708BCAB12342D58")))
        assertNull(QingpingBleParser.parsePairBOtaNotify(QingpingBleParser.fromHexString("04FF020000")))
    }

    @Test
    fun testKeepAliveStrategyCleanupAndLegacyCompat() {
        val auto = io.github.androidaql.service.AqlCollectorService.KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP
        val passive = io.github.androidaql.service.AqlCollectorService.KeepAlivePayloadStrategy.NONE_PASSIVE
        val fromId = io.github.androidaql.service.AqlCollectorService.KeepAlivePayloadStrategy::fromId

        assertEquals(auto, fromId("AUTO_OFFLINE_SETUP"))
        assertEquals(auto, fromId("AUTO_EXCURSION_1101"))
        assertEquals(auto, fromId("UNKNOWN_DISPROVEN_STRATEGY"))
        assertEquals(passive, fromId("NONE_PASSIVE"))

        assertEquals(
            listOf(auto, passive),
            io.github.androidaql.service.AqlCollectorService.KeepAlivePayloadStrategy.userAccessibleStrategies
        )
        assertEquals(
            listOf(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, BleCollectionMode.PASSIVE_ONLY),
            BleCollectionMode.entries
        )
    }

    @Test
    fun testLogSpeedScaleMakesWalkingVisibleWhilePreservingFastSeparation() {
        val norm = io.github.androidaql.ui.DualPaneSlicerView.Companion::normalizeLogSpeed

        assertEquals(0f, norm(0f, 30f), 1e-5f)
        assertEquals(0f, norm(-2f, 30f), 1e-5f)
        assertEquals(1f, norm(30f, 30f), 1e-5f)
        assertEquals(1f, norm(50f, 30f), 1e-5f)

        // Walking pace (~1.3 m/s) should sit well above 0 (over 25% height) even on a 30 m/s highway trip
        val walkRatioHighway = norm(1.3f, 30f)
        val busRatioHighway = norm(10.0f, 30f)
        val highwayRatio = norm(30.0f, 30f)
        assertTrue("Expected walking >= 0.25 of pane on 30m/s scale, was $walkRatioHighway", walkRatioHighway >= 0.25f)
        assertTrue(busRatioHighway > walkRatioHighway + 0.30f)
        assertTrue(highwayRatio > busRatioHighway + 0.20f)

        // On default 12 m/s max scale, walking (~1.3 m/s) is clearly visible and distinct from 5 m/s / 12 m/s
        val walkRatioCity = norm(1.3f, 12f)
        val bikeRatioCity = norm(5.0f, 12f)
        assertTrue("Expected walking >= 0.35 of pane on 12m/s scale, was $walkRatioCity", walkRatioCity >= 0.35f)
        assertTrue(bikeRatioCity > walkRatioCity + 0.25f)
    }

    @Test
    fun testStepMovementEstimatorAndWeakGpsFusion() {
        val estimator = io.github.androidaql.engine.StepMovementEstimator()
        val t0 = 100_000L

        // Fewer than 3 steps should not trigger false walking
        estimator.onStepDetected(t0)
        estimator.onStepDetected(t0 + 600L)
        assertNull(estimator.estimateSpeedMps(t0 + 700L))

        // Steady ~1.8 steps/sec walk produces ~1.4 m/s
        for (i in 2..10) {
            estimator.onStepDetected(t0 + i * 550L)
        }
        val walkingSpeed = estimator.estimateSpeedMps(t0 + 5_800L)
        assertNotNull(walkingSpeed)
        assertTrue("Expected walking speed in [1.1, 1.8], got $walkingSpeed", walkingSpeed!! in 1.1f..1.8f)

        // Decays cleanly to null after >4s without steps
        assertNull(estimator.estimateSpeedMps(t0 + 10_500L))

        // Step counter batch fallback ignores boot baseline and estimates delta cleanly
        estimator.reset()
        estimator.onStepCounterTotal(totalStepsSinceBoot = 4_200L, timestampMs = t0)
        assertNull(estimator.estimateSpeedMps(t0 + 100L))
        estimator.onStepCounterTotal(totalStepsSinceBoot = 4_210L, timestampMs = t0 + 6_000L)
        val counterWalk = estimator.estimateSpeedMps(t0 + 6_500L)
        assertNotNull(counterWalk)
        assertTrue("Expected step counter walk in [0.8, 2.0], got $counterWalk", counterWalk!! in 0.8f..2.0f)

        // Fusion rules:
        // 1. Weak GPS (±100m, even with a coarse 4.5 m/s AP snap) + walking steps -> STEP_CADENCE wins
        val weakGpsWalking = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = 4.5f,
            gpsAccuracyM = 100f,
            stepSpeedMps = 1.35f,
            isStepSensorActive = true
        )
        assertEquals(1.35f, weakGpsWalking.speedMps!!, 1e-4f)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.STEP_CADENCE, weakGpsWalking.source)

        // 2. Healthy GPS (±25m) meandering slowly (0.35 m/s net anchor displacement) + brisk steps -> max() picks 1.40 m/s
        val outdoorMeander = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = 0.35f,
            gpsAccuracyM = 25f,
            stepSpeedMps = 1.40f,
            isStepSensorActive = true
        )
        assertEquals(1.40f, outdoorMeander.speedMps!!, 1e-4f)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.STEP_CADENCE, outdoorMeander.source)

        // 3. Strong GPS driving/transit (8.5 m/s, ±8m) -> max() picks 8.5 m/s
        val strongGpsDriving = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = 8.5f,
            gpsAccuracyM = 8f,
            stepSpeedMps = 1.2f,
            isStepSensorActive = true
        )
        assertEquals(8.5f, strongGpsDriving.speedMps!!, 1e-4f)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.GPS, strongGpsDriving.source)

        // 4. Weak GPS (±100m, 0.0 m/s) + stationary (null steps) -> 0.0 m/s GPS
        val weakGpsStill = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = 0f,
            gpsAccuracyM = 100f,
            stepSpeedMps = null,
            isStepSensorActive = true
        )
        assertEquals(0f, weakGpsStill.speedMps!!, 1e-4f)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.GPS, weakGpsStill.source)

        // 5. GPS Off + step sensor active while stationary -> 0f (continuous curve, not null / "GPS Off")
        val gpsOffStepStill = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = null,
            gpsAccuracyM = null,
            stepSpeedMps = null,
            isStepSensorActive = true
        )
        assertEquals(0f, gpsOffStepStill.speedMps!!, 1e-4f)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.STEP_CADENCE, gpsOffStepStill.source)

        // 6. Both GPS Off and step sensor off -> null (NONE -> "GPS Off")
        val bothOff = io.github.androidaql.engine.StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = null,
            gpsAccuracyM = null,
            stepSpeedMps = null,
            isStepSensorActive = false
        )
        assertNull(bothOff.speedMps)
        assertEquals(io.github.androidaql.engine.StepMovementEstimator.SpeedSource.NONE, bothOff.source)

        // 7. Ring buffer clears stale weak-GPS accuracy when a step-estimated sample supersedes it
        val tmpDir = kotlin.io.path.createTempDirectory("aql_step_ring_test").toFile()
        try {
            val store = AqlRingBufferStore(File(tmpDir, "step_test.aqlring"))
            val bucketBaseSec = 1_700_000_010L
            // First packet: stationary weak GPS
            store.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = bucketBaseSec,
                    knownPlaceId = 0,
                    co2Ppm = 780,
                    pm25Ugm3 = 2,
                    pm10Ugm3 = 3,
                    tempC = 21.0f,
                    humidityPct = 42.0f,
                    speedMps = 0.0f,
                    gpsAccuracyM = 100.0f
                )
            )
            // Second packet in same 15s bucket: step-derived walking (null GPS accuracy)
            store.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = bucketBaseSec + 3,
                    knownPlaceId = 0,
                    co2Ppm = 785,
                    pm25Ugm3 = 2,
                    pm10Ugm3 = 3,
                    tempC = 21.0f,
                    humidityPct = 42.0f,
                    speedMps = 1.4f,
                    gpsAccuracyM = null
                )
            )
            // Third packet in same 15s bucket: user pauses (GPS emits 0.0 m/s with 11.5m accuracy)
            // Must NOT overwrite the step-estimated bucket back into a GPS-attributed bucket.
            store.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = bucketBaseSec + 4,
                    knownPlaceId = 0,
                    co2Ppm = 786,
                    pm25Ugm3 = 2,
                    pm10Ugm3 = 3,
                    tempC = 21.0f,
                    humidityPct = 42.0f,
                    speedMps = 0.0f,
                    gpsAccuracyM = 11.5f
                )
            )
            val slots = store.queryRange(0, bucketBaseSec - 15, bucketBaseSec + 15)
            assertEquals(1, slots.size)
            assertNull("Expected gpsAccuracyM cleared to null for step-estimated bucket", slots[0].gpsAccuracyM)
            assertTrue("Expected positive step-derived speed, got ${slots[0].speedMps}", (slots[0].speedMps ?: 0f) > 0.3f)
            store.close()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testComputeSliceStatsUniformPercentilesAcrossAllMetrics() {
        val tmpDir = Files.createTempDirectory("aql_slice_stats_test").toFile()
        try {
            val ringFile = File(tmpDir, "test_stats.aqlring")
            val store = AqlRingBufferStore(
                file = ringFile,
                archiveSpecs = listOf(AqlRingBufferStore.ArchiveSpec(15, 64))
            )
            val baseSec = 1_700_000_010L // aligned+offset within bucket 1_700_000_010 / 15 * 15 = 1_700_000_010? Wait: use exact multiple
            val alignedBase = (baseSec / 15L) * 15L
            val points = listOf(
                listOf(500, 5, 10, 19.0f, 35.0f),
                listOf(700, 10, 16, 20.0f, 40.0f),
                listOf(900, 15, 22, 21.0f, 45.0f),
                listOf(1100, 20, 28, 22.0f, 50.0f)
            )
            for ((i, p) in points.withIndex()) {
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = alignedBase + i * 15L,
                        knownPlaceId = 0,
                        co2Ppm = p[0] as Int,
                        pm25Ugm3 = p[1] as Int,
                        pm10Ugm3 = p[2] as Int,
                        tempC = p[3] as Float,
                        humidityPct = p[4] as Float
                    )
                )
            }
            // Bucket 4 gets two samples so slot mean < intra-slot raw max for CO2/PM2.5/PM10
            store.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = alignedBase + 60L,
                    knownPlaceId = 0,
                    co2Ppm = 1150,
                    pm25Ugm3 = 25,
                    pm10Ugm3 = 34,
                    tempC = 24.0f,
                    humidityPct = 55.0f
                )
            )
            store.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = alignedBase + 63L,
                    knownPlaceId = 0,
                    co2Ppm = 1450,
                    pm25Ugm3 = 35,
                    pm10Ugm3 = 48,
                    tempC = 26.0f,
                    humidityPct = 65.0f
                )
            )

            val slots = store.queryRange(0, alignedBase, alignedBase + 60L)
            assertEquals(5, slots.size)
            val stats = AqlRingBufferStore.computeSliceStats(slots, stepSec = 15)

            assertEquals(900, stats.co2Median)
            assertEquals(1100, stats.co2P95)
            assertEquals(1450, stats.co2Max)
            assertEquals(900.0f, stats.co2Mean, 0.1f)

            assertEquals(15.0f, stats.pm25P50, 0.05f)
            assertEquals(20.0f, stats.pm25P95, 0.05f)
            assertEquals(35, stats.pm25Max)
            assertEquals(16.0f, stats.pm25Mean, 0.1f)

            assertEquals(22.0f, stats.pm10P50, 0.05f)
            assertEquals(28.0f, stats.pm10P95, 0.05f)
            assertEquals(48, stats.pm10Max)
            assertEquals(23.4f, stats.pm10Mean, 0.15f)

            assertEquals(21.0f, stats.tempCP50, 0.05f)
            assertEquals(22.0f, stats.tempCP95, 0.05f)
            assertEquals(25.0f, stats.tempCMax, 0.05f)
            assertEquals(21.4f, stats.tempCMean, 0.1f)

            assertEquals(45.0f, stats.humidityP50, 0.05f)
            assertEquals(50.0f, stats.humidityP95, 0.05f)
            assertEquals(60.0f, stats.humidityMax, 0.05f)
            assertEquals(46.0f, stats.humidityMean, 0.1f)

            store.close()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testRingBufferContinuousWrapAroundAndRejectsExpiredHistoricalWrite() {
        val tmpDir = Files.createTempDirectory("aql_wrap_test").toFile()
        try {
            val ringFile = File(tmpDir, "wrap_test.aqlring")
            val specs = listOf(
                AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = 64)
            )
            AqlRingBufferStore(ringFile, deviceId = 1L, archiveSpecs = specs).use { store ->
                assertEquals(960L, store.getArchiveWindowSec(0))

                // Write 150 consecutive 15s slots (t = 1500 .. 3735, >2 full rotations of Archive 0)
                for (i in 0 until 150) {
                    val ts = 1500L + i * 15L
                    store.writeSample(
                        AqlRingBufferStore.RawSampleInput(
                            timestampEpochSec = ts,
                            knownPlaceId = if (i % 2 == 0) 0 else 1,
                            co2Ppm = 500 + i * 5,
                            pm25Ugm3 = 5 + (i % 10),
                            pm10Ugm3 = 10 + (i % 10),
                            tempC = 20.0f,
                            humidityPct = 40.0f
                        )
                    )
                }

                // Archive 0 retains the exact 64 most recent slots: i = 86..149 -> 2790..3735
                val arch0 = store.queryRange(0, 1500L, 3735L)
                assertEquals(64, arch0.size)
                assertEquals(2790L, arch0.first().bucketEpochSec)
                assertEquals(3735L, arch0.last().bucketEpochSec)
                for (k in 0 until 64) {
                    val expectedTs = 2790L + k * 15L
                    assertEquals(expectedTs, arch0[k].bucketEpochSec)
                    assertEquals(500 + (86 + k) * 5, arch0[k].co2PpmMean)
                }

                // Historical write at ts = 1500 maps to the same modulo-64 index as ts = 3420 (i = 128, co2 = 1140)
                store.writeSample(
                    AqlRingBufferStore.RawSampleInput(
                        timestampEpochSec = 1500L,
                        knownPlaceId = 1,
                        co2Ppm = 9999,
                        pm25Ugm3 = 99,
                        pm10Ugm3 = 99,
                        tempC = 10.0f,
                        humidityPct = 10.0f
                    )
                )
                val slot3420 = store.queryRange(0, 3420L, 3420L)
                assertEquals("Expired write must NOT clobber newer slot at same modulo index", 1, slot3420.size)
                assertEquals(3420L, slot3420[0].bucketEpochSec)
                assertEquals(1140, slot3420[0].co2PpmMean)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testTripSelectionAdvancesToNewAutoTripWithoutBreakingManualPickOrEditMode() {
        val exc1 = ExcursionRecord(1L, 1L, 1000L, 1500L, false, "Trip 1", "")
        val exc2 = ExcursionRecord(2L, 1L, 2000L, null, false, "Trip 2", "")

        // Initial load with only exc1
        val (selInit, newestInit) = MetadataDatabase.resolveSelectedExcursionId(
            excursions = listOf(exc1),
            currentSelectedId = null,
            lastSeenLatestId = null,
            isTripEditMode = false
        )
        assertEquals(1L, selInit)
        assertEquals(1L, newestInit)

        // New trip exc2 arrives in background while not in edit mode -> advances selection to exc2
        val (selAuto, newestAuto) = MetadataDatabase.resolveSelectedExcursionId(
            excursions = listOf(exc2, exc1),
            currentSelectedId = selInit,
            lastSeenLatestId = newestInit,
            isTripEditMode = false
        )
        assertEquals(2L, selAuto)
        assertEquals(2L, newestAuto)

        // User manually picks exc1 while newest is already exc2 -> stays on exc1 (no fight!)
        val (selManual, newestManual) = MetadataDatabase.resolveSelectedExcursionId(
            excursions = listOf(exc2, exc1),
            currentSelectedId = 1L,
            lastSeenLatestId = 2L,
            isTripEditMode = false
        )
        assertEquals(1L, selManual)
        assertEquals(2L, newestManual)

        // While actively editing exc1, a new trip exc2 arrives -> stays on exc1 until user finishes editing
        val (selEditing, newestEditing) = MetadataDatabase.resolveSelectedExcursionId(
            excursions = listOf(exc2, exc1),
            currentSelectedId = 1L,
            lastSeenLatestId = 1L,
            isTripEditMode = true
        )
        assertEquals(1L, selEditing)
        assertEquals(2L, newestEditing)
    }

    @Test
    fun testAutoExcursionDisabledUntilAtLeastOneKnownPlaceConfigured() {
        val sm = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L,
            wifiLossDebounceSec = 0L
        )

        // 1. Before any Saved Wi-Fi Place exists, live BLE advertisements must NOT auto-start
        //    a trip or enable GPS, while keeping offline 0x88 setup / periodic keep-alive ready.
        val t1 = sm.onBleAdvertisement(
            timestampEpochSec = 1000L,
            connectedKnownPlaceId = 0,
            hasConfiguredKnownPlaces = false
        )
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, t1.currentState)
        assertFalse(t1.startedNewExcursion)
        assertFalse(t1.shouldRunGps)
        assertEquals(0, t1.activeKnownPlaceId)
        assertEquals(BleCollectionMode.PERIODIC_GATT_KEEPALIVE, t1.effectiveBleMode)

        // 2. User saves their Home Wi-Fi while still connected -> stays stationary with knownPlaceId = 1.
        val t2 = sm.onWifiContextChanged(
            timestampEpochSec = 1015L,
            connectedKnownPlaceId = 1,
            hasConfiguredKnownPlaces = true
        )
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, t2.currentState)
        assertFalse(t2.startedNewExcursion)
        assertFalse(t2.shouldRunGps)
        assertEquals(1, t2.activeKnownPlaceId)

        // 3. Leaving Home Wi-Fi while BLE is fresh now auto-starts a Field Excursion with GPS.
        val t3 = sm.onWifiContextChanged(
            timestampEpochSec = 1030L,
            connectedKnownPlaceId = 0,
            hasConfiguredKnownPlaces = true
        )
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, t3.currentState)
        assertTrue(t3.startedNewExcursion)
        assertTrue(t3.shouldRunGps)
        assertEquals(0, t3.activeKnownPlaceId)
    }

    @Test
    fun testManualTripStartAndEndOverride() {
        // Case 1 & 2: No Saved Places configured + fresh BLE -> manual start opens trip with GPS,
        // and manual end closes it without auto-restarting on subsequent BLE packets.
        val sm = ExcursionStateMachine(gpsPauseTimeoutSec = 90L, excursionCloseTimeoutSec = 2700L)
        val tIdle = sm.onBleAdvertisement(
            timestampEpochSec = 1000L,
            connectedKnownPlaceId = 0,
            hasConfiguredKnownPlaces = false
        )
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tIdle.currentState)
        assertFalse(tIdle.startedNewExcursion)

        val tStart = sm.onManualTripStart(timestampEpochSec = 1005L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tStart.currentState)
        assertTrue(tStart.startedNewExcursion)
        assertTrue(tStart.shouldRunGps)
        assertEquals(0, tStart.activeKnownPlaceId)

        val tBleDuringManual = sm.onBleAdvertisement(
            timestampEpochSec = 1020L,
            connectedKnownPlaceId = 0,
            hasConfiguredKnownPlaces = false
        )
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tBleDuringManual.currentState)
        assertFalse(tBleDuringManual.startedNewExcursion)
        assertTrue(tBleDuringManual.shouldRunGps)

        val tEnd = sm.onManualTripEnd(timestampEpochSec = 1100L)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tEnd.currentState)
        assertTrue(tEnd.closedExcursion)
        assertFalse(tEnd.shouldRunGps)

        val tAfterEnd = sm.onBleAdvertisement(
            timestampEpochSec = 1105L,
            connectedKnownPlaceId = 0,
            hasConfiguredKnownPlaces = false
        )
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tAfterEnd.currentState)
        assertFalse(tAfterEnd.startedNewExcursion)
        assertFalse(tAfterEnd.shouldRunGps)

        // Case 3: Manual trip started BEFORE any BLE packet arrives enters SIGNAL_PAUSED (GPS off),
        // upgrades to ACTIVE as soon as first BLE packet arrives, and can also be manually ended from SIGNAL_PAUSED.
        val smPreBle = ExcursionStateMachine(gpsPauseTimeoutSec = 90L, excursionCloseTimeoutSec = 2700L)
        val p0 = smPreBle.onWifiContextChanged(1000L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = false)
        assertEquals(ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE, p0.currentState)

        val pStart = smPreBle.onManualTripStart(1005L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED, pStart.currentState)
        assertTrue(pStart.startedNewExcursion)
        assertFalse(pStart.shouldRunGps)

        val pFirstBle = smPreBle.onBleAdvertisement(1020L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = false)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, pFirstBle.currentState)
        assertFalse(pFirstBle.startedNewExcursion)
        assertTrue(pFirstBle.shouldRunGps)

        val smPauseEnd = ExcursionStateMachine(gpsPauseTimeoutSec = 90L, excursionCloseTimeoutSec = 2700L)
        smPauseEnd.onWifiContextChanged(1000L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        val peStart = smPauseEnd.onManualTripStart(1005L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED, peStart.currentState)
        val peEnd = smPauseEnd.onManualTripEnd(1010L)
        assertTrue(peEnd.closedExcursion)
        assertEquals(ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE, peEnd.currentState)

        // Case 4: Starting manually while still connected to Saved Place #1 starts immediately,
        // stays active after leaving #1, and auto-closes upon returning to #1.
        val smAtHome = ExcursionStateMachine(gpsPauseTimeoutSec = 90L, excursionCloseTimeoutSec = 2700L)
        smAtHome.onBleAdvertisement(1000L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        val hStart = smAtHome.onManualTripStart(1005L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, hStart.currentState)
        assertTrue(hStart.startedNewExcursion)
        assertTrue(hStart.shouldRunGps)
        assertEquals(0, hStart.activeKnownPlaceId)

        val hLeave = smAtHome.onWifiContextChanged(1030L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, hLeave.currentState)
        assertFalse(hLeave.closedExcursion)

        smAtHome.onBleAdvertisement(1150L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        val hReturn = smAtHome.onWifiContextChanged(1200L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, hReturn.currentState)
        assertTrue(hReturn.closedExcursion)
        assertFalse(hReturn.shouldRunGps)
        assertEquals(1, hReturn.activeKnownPlaceId)
    }

    @Test
    fun testXlsxImporterRoundTripPreservesSplitsNotesEmptySegmentAndTimeShift() {
        val device = MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Travel Monitor", true)
        val excursion = ExcursionRecord(7L, 1L, 1500L, 1545L, true, "Morning Walk, Loop", "Evening outing")
        val slices = listOf(
            SessionSliceRecord(10L, 7L, 1500L, 1515L, true, "Walk Out", "Brisk"),
            SessionSliceRecord(11L, 7L, 1515L, 1530L, false, "Tunnel Dropout", "No BLE"),
            SessionSliceRecord(12L, 7L, 1530L, 1545L, true, "Coffee, Fl 2", "Quote \"test\"")
        )
        // Intentionally omit 1515L so segment #2 ("Tunnel Dropout") has 0 samples
        val slots = listOf(
            AqlRingBufferStore.RingSlotRecord(1500L, 2, 0, 520, 510, 535, 84, 11, 122, 15, 215, 405, 90, -60, 145, 120),
            AqlRingBufferStore.RingSlotRecord(1545L, 3, 0, 1180, 1150, 1210, 142, 19, 184, 24, 228, 445, 89, -58, 0, 850)
        )
        val notes = listOf(
            TimelineNoteRecord(1L, 1500L, "Left gate", 1500L),
            TimelineNoteRecord(2L, 1520L, "Signal lost", 1520L)
        )

        val xlsxBytes = XlsxExporter.exportExcursionXlsx(
            device = device,
            excursion = excursion,
            slices = slices,
            slots = slots,
            notes = notes,
            onlySelectedForExport = false,
            zoneId = ZoneOffset.UTC
        )
        val parsed = java.io.ByteArrayInputStream(xlsxBytes).use { XlsxImporter.parseXlsx(it) }
        assertEquals("Morning Walk, Loop", parsed.trip.excursionTitle)
        assertEquals("Evening outing", parsed.trip.excursionNotes)
        assertEquals("58:2D:34:12:AB:CD", parsed.deviceMac)
        assertEquals("Travel Monitor", parsed.deviceNickname)
        assertEquals(3, parsed.segments.size)
        assertEquals("Tunnel Dropout", parsed.segments[1].label)
        assertEquals("No BLE", parsed.segments[1].notes)
        assertFalse(parsed.segments[1].includeInExport)
        assertEquals("Coffee, Fl 2", parsed.segments[2].label)
        assertEquals("Quote \"test\"", parsed.segments[2].notes)
        assertEquals(2, parsed.notes.size)
        assertEquals("Signal lost", parsed.notes[1].text)
        assertEquals(2, parsed.rows.size)

        val r0 = parsed.rows[0]
        assertEquals(1500L, r0.slot.bucketEpochSec)
        assertEquals(520, r0.slot.co2PpmMean)
        assertEquals(510, r0.slot.co2PpmMin)
        assertEquals(535, r0.slot.co2PpmMax)
        assertEquals(84, r0.slot.pm25MeanX10)
        assertEquals(11, r0.slot.pm25Max)
        assertEquals(145, r0.slot.speedMpsX100)
        assertEquals(120, r0.slot.gpsAccuracyMX10)
        assertEquals("Walk Out", r0.sliceLabel)

        val shifted = parsed.timeShifted(targetEndEpochSec = 3000L)
        val delta = 3000L - 1545L
        assertEquals(1500L + delta, shifted.trip.startEpochSec)
        assertEquals(3000L, shifted.trip.endEpochSec)
        assertEquals(1515L + delta, shifted.segments[1].startEpochSec)
        assertEquals(1530L + delta, shifted.segments[1].endEpochSec)
        assertEquals(1520L + delta, shifted.notes[1].timestampEpochSec)
        assertEquals(1500L + delta, shifted.rows[0].slot.bucketEpochSec)
        assertEquals(3000L, shifted.rows[1].slot.bucketEpochSec)

        // Fallback derivation from Samples when Segments sheet is absent
        val fallbackSpans = XlsxImporter.extractFallbackSegments(shifted.rows)
        assertEquals(2, fallbackSpans.size)
        assertEquals(1500L + delta, fallbackSpans[0].startEpochSec)
        assertEquals(3000L, fallbackSpans[0].endEpochSec)
        assertEquals("Walk Out", fallbackSpans[0].label)
        assertEquals("Coffee, Fl 2", fallbackSpans[1].label)

        // Verify AqlRingBufferStore.writeSlot preserves exact RingSlotRecord values
        val tmpRing = Files.createTempFile("aql_replay_test_", ".aqlring").toFile()
        try {
            AqlRingBufferStore(tmpRing).use { store ->
                shifted.rows.forEach { store.writeSlot(it.slot) }
                val readBack = store.queryRange(0, 1500L + delta, 3000L)
                assertEquals(2, readBack.size)
                assertEquals(510, readBack[0].co2PpmMin)
                assertEquals(535, readBack[0].co2PpmMax)
                assertEquals(84, readBack[0].pm25MeanX10)
                assertEquals(145, readBack[0].speedMpsX100)
            }
        } finally {
            tmpRing.delete()
        }
    }

    @Test
    fun testBundledDemoXlsxAssetsParseCleanly() {
        val demoDir = listOf(
            File("app/src/main/assets/demo"),
            File("src/main/assets/demo")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected bundled demo assets directory", demoDir)

        val tripParsed = File(demoDir!!, "sample_trip.xlsx").inputStream().use { XlsxImporter.parseXlsx(it) }
        assertEquals(67, tripParsed.rows.size)
        assertEquals(3, tripParsed.segments.size)
        assertEquals("Demo: Breakfast Trip", tripParsed.excursionTitle)
        assertFalse(tripParsed.isStationaryOnly)

        val statParsed = File(demoDir, "sample_now_stationary.xlsx").inputStream().use { XlsxImporter.parseXlsx(it) }
        assertEquals(60, statParsed.rows.size)
        assertTrue(statParsed.segments.isEmpty())
        assertTrue(statParsed.isStationaryOnly)
    }

    @Test
    fun testTimelineViewportClampAndMinimumSpan() {
        val tripStart = 900L
        val tripEnd = 8100L // 2h, 15s-aligned

        // Requests narrower than MIN_VIEWPORT_SPAN_SEC (180s) expand to 180s and clamp negative offset to tripStart
        val leftClamped = DualPaneSlicerView.clampViewport(tripStart, tripEnd, 850L, 1000L)
        assertEquals(900L, leftClamped.first)
        assertEquals(1080L, leftClamped.second)
        assertEquals(0L, leftClamped.first % DualPaneSlicerView.STEP_SEC)
        assertEquals(0L, leftClamped.second % DualPaneSlicerView.STEP_SEC)

        // Requests exceeding tripEnd clamp cleanly to the right boundary while preserving snapped span
        val rightClamped = DualPaneSlicerView.clampViewport(tripStart, tripEnd, 8000L, 8200L)
        assertEquals(7905L, rightClamped.first)
        assertEquals(8100L, rightClamped.second)
        assertEquals(195L, rightClamped.second - rightClamped.first)

        // Unaligned interior bounds snap cleanly to 15s grid
        val snapped = DualPaneSlicerView.clampViewport(tripStart, tripEnd, 1806L, 2708L)
        assertEquals(1800L, snapped.first)
        assertEquals(2700L, snapped.second)

        // Trips shorter than 180s gracefully return [tripStart, tripEnd]
        val shortTrip = DualPaneSlicerView.clampViewport(900L, 990L, 915L, 960L)
        assertEquals(900L, shortTrip.first)
        assertEquals(990L, shortTrip.second)
    }

    @Test
    fun testTimelineViewportZoomAroundAnchorPreservesFocalTimestamp() {
        val tripStart = 900L
        val tripEnd = 8100L // 7200s (2h)
        val anchorRatio = 0.25f
        // Focal timestamp at 0.25 of [900, 8100] is 2700L
        val zoomedIn = DualPaneSlicerView.zoomViewportAroundAnchor(
            tripStartSec = tripStart,
            tripEndSec = tripEnd,
            curStartSec = tripStart,
            curEndSec = tripEnd,
            scaleFactor = 4.0f,
            anchorRatio = anchorRatio
        )
        assertEquals(2250L, zoomedIn.first)
        assertEquals(4050L, zoomedIn.second)
        val span = zoomedIn.second - zoomedIn.first
        assertEquals(1800L, span)
        assertEquals(2700L, zoomedIn.first + (anchorRatio * span).toLong())

        // Zooming back out past 1.0x clamps cleanly to full trip bounds
        val zoomedOut = DualPaneSlicerView.zoomViewportAroundAnchor(
            tripStartSec = tripStart,
            tripEndSec = tripEnd,
            curStartSec = zoomedIn.first,
            curEndSec = zoomedIn.second,
            scaleFactor = 0.10f,
            anchorRatio = 0.5f
        )
        assertEquals(tripStart, zoomedOut.first)
        assertEquals(tripEnd, zoomedOut.second)
    }

    @Test
    fun testTimelineViewportPanAndEnsureTimestampVisible() {
        val tripStart = 900L
        val tripEnd = 8100L

        // Panning past left and right edges preserves exact span
        val pannedLeft = DualPaneSlicerView.panViewportByDeltaSec(tripStart, tripEnd, 1200L, 3000L, -600L)
        assertEquals(900L, pannedLeft.first)
        assertEquals(2700L, pannedLeft.second)

        val pannedRight = DualPaneSlicerView.panViewportByDeltaSec(tripStart, tripEnd, 6000L, 7800L, +900L)
        assertEquals(6300L, pannedRight.first)
        assertEquals(8100L, pannedRight.second)

        // Target already inside safe interior -> no jump
        val noJump = DualPaneSlicerView.ensureTimestampVisible(tripStart, tripEnd, 1800L, 3600L, 2700L)
        assertEquals(1800L, noJump.first)
        assertEquals(3600L, noJump.second)

        // Target near/beyond right edge with centerIfOutside=false -> minimal shift leaving 10% (180s) margin
        val shiftedMin = DualPaneSlicerView.ensureTimestampVisible(
            tripStartSec = tripStart,
            tripEndSec = tripEnd,
            curStartSec = 1800L,
            curEndSec = 3600L,
            targetTs = 3510L,
            marginRatio = 0.10f,
            centerIfOutside = false
        )
        assertEquals(1890L, shiftedMin.first)
        assertEquals(3690L, shiftedMin.second)

        // Target outside with centerIfOutside=true -> centers around targetTs
        val centered = DualPaneSlicerView.ensureTimestampVisible(
            tripStartSec = tripStart,
            tripEndSec = tripEnd,
            curStartSec = 1800L,
            curEndSec = 3600L,
            targetTs = 5400L,
            marginRatio = 0.10f,
            centerIfOutside = true
        )
        assertEquals(4500L, centered.first)
        assertEquals(6300L, centered.second)
    }

    @Test
    fun testComputeSegmentFocusViewportIncludesBothDividerMargins() {
        val tripStart = 900L
        val tripEnd = 8100L

        // 10-minute segment (3000..3600) gets 15% (90s) margin on both sides so both dividers remain visible
        val focused = DualPaneSlicerView.computeSegmentFocusViewport(tripStart, tripEnd, 3000L, 3600L)
        assertEquals(2910L, focused.first)
        assertEquals(3690L, focused.second)
        assertTrue(3000L > focused.first && 3600L < focused.second)

        // 30-second micro-segment expands symmetrically to MIN_VIEWPORT_SPAN_SEC (180s)
        val microFocused = DualPaneSlicerView.computeSegmentFocusViewport(tripStart, tripEnd, 3000L, 3030L)
        assertEquals(2925L, microFocused.first)
        assertEquals(3105L, microFocused.second)
        assertEquals(DualPaneSlicerView.MIN_VIEWPORT_SPAN_SEC, microFocused.second - microFocused.first)
    }

    @Test
    fun testBottomChromeMetricsNeverOverlapAcrossFormFactors() {
        val cases = listOf(
            // (heightDp, density, hasSegmentStrip, hasMinimap, textAscentPx, textDescentPx)
            listOf(260f, 2.625f, true, true, 26f, 7f),   // Phone landscape (compact)
            listOf(390f, 2.625f, true, true, 26f, 7f),   // Phone portrait
            listOf(390f, 2.625f, true, true, 42f, 12f),  // Large accessibility font metrics
            listOf(390f, 2.625f, false, true, 26f, 7f),  // 14d Window preview (no segments, minimap enabled)
            listOf(720f, 2.0f, true, true, 20f, 6f)      // 10-inch tablet
        )
        for (c in cases) {
            val heightDp = c[0] as Float
            val density = c[1] as Float
            val hasSegments = c[2] as Boolean
            val hasMinimap = c[3] as Boolean
            val ascentPx = c[4] as Float
            val descentPx = c[5] as Float
            val viewHeightPx = heightDp * density

            val chrome = DualPaneSlicerView.computeBottomChromeMetrics(
                viewHeightPx = viewHeightPx,
                density = density,
                hasSegmentStrip = hasSegments,
                hasMinimap = hasMinimap,
                textAscentPx = ascentPx,
                textDescentPx = descentPx
            )
            val botPaneBot = viewHeightPx - chrome.effectivePadBottomPx
            val segTop = botPaneBot + chrome.segTopGapPx
            val segBot = segTop + chrome.segHeightPx
            val timeAxisTop = segBot + chrome.timeAxisTopGapPx
            val timeAxisBaseline = timeAxisTop + chrome.timeAxisAscentPx
            val timeAxisBot = timeAxisTop + chrome.timeAxisTextHeightPx
            val minimapTop = timeAxisBot + chrome.minimapTopGapPx
            val minimapBot = minimapTop + chrome.minimapHeightPx

            assertTrue("Plot height collapsed for heightDp=$heightDp", botPaneBot > (DualPaneSlicerView.PAD_TOP_DP + 2f * DualPaneSlicerView.PANE_GAP_DP) * density)
            assertTrue("Segment strip overlapped bottom pane for heightDp=$heightDp", segTop >= botPaneBot)
            assertTrue("Time axis overlapped segment strip for heightDp=$heightDp", timeAxisTop > segBot)
            assertTrue("Time axis baseline above top for heightDp=$heightDp", timeAxisBaseline >= timeAxisTop + ascentPx - 0.01f)
            assertTrue("Time axis bottom below baseline for heightDp=$heightDp", timeAxisBot >= timeAxisBaseline + descentPx - 0.01f)
            if (hasMinimap) {
                assertTrue("Minimap overlapped time axis for heightDp=$heightDp", minimapTop > timeAxisBot)
                assertTrue("Minimap clipped view bottom for heightDp=$heightDp", minimapBot <= viewHeightPx + 0.01f)
            } else {
                assertTrue("Time axis clipped view bottom for heightDp=$heightDp", timeAxisBot <= viewHeightPx + 0.01f)
            }
        }

        // Responsive portrait height clamping across compact phone, Pixel 9a, and 10" tablet
        assertEquals(320, io.github.androidaql.ui.MainActivity.computePortraitSlicerHeightDp(640))
        assertEquals(400, io.github.androidaql.ui.MainActivity.computePortraitSlicerHeightDp(890))
        assertEquals(460, io.github.androidaql.ui.MainActivity.computePortraitSlicerHeightDp(1280))
        assertEquals(
            DualPaneSlicerView.DEFAULT_PORTRAIT_HEIGHT_DP,
            io.github.androidaql.ui.MainActivity.computePortraitSlicerHeightDp(0)
        )
    }

    @Test
    fun testWifiLossDebounceDelaysAutoExcursionAndBackdatesStart() {
        assertEquals(30L, ExcursionStateMachine.DEFAULT_WIFI_LOSS_DEBOUNCE_SEC)
        val sm = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L
        )
        // Stationary at Home
        val tHome = sm.onBleAdvertisement(
            timestampEpochSec = 1000L,
            connectedKnownPlaceId = 1,
            hasConfiguredKnownPlaces = true
        )
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tHome.currentState)
        assertFalse(sm.isPendingWifiLossDebounce)

        // Momentary Wi-Fi drop for 15s -> stays stationary without starting trip or waking GPS
        val tDrop1 = sm.onWifiContextChanged(1010L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tDrop1.currentState)
        assertFalse(tDrop1.startedNewExcursion)
        assertFalse(tDrop1.shouldRunGps)
        assertTrue(sm.isPendingWifiLossDebounce)

        val tWait1 = sm.onTimerTick(1025L)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tWait1.currentState)
        assertFalse(tWait1.startedNewExcursion)

        // Wi-Fi recovers before 30s -> resets debounce cleanly
        val tRecover = sm.onWifiContextChanged(1030L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tRecover.currentState)
        assertFalse(sm.isPendingWifiLossDebounce)

        // Real departure: Wi-Fi stays dropped across 30s threshold with BLE still present -> confirms excursion back-dated to 1100L
        sm.onBleAdvertisement(1095L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        val tDrop2 = sm.onWifiContextChanged(1100L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tDrop2.currentState)
        assertFalse(tDrop2.startedNewExcursion)
        val tBefore = sm.onTimerTick(1120L)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tBefore.currentState)
        assertFalse(tBefore.startedNewExcursion)

        val tConfirm = sm.onBleAdvertisement(1130L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tConfirm.currentState)
        assertTrue(tConfirm.startedNewExcursion)
        assertTrue(tConfirm.shouldRunGps)
        assertEquals(1100L, tConfirm.startedExcursionStartEpochSec)
        assertFalse(tConfirm.startedByManualOverride)
        assertFalse(sm.isPendingWifiLossDebounce)

        // Manual trip start bypasses Wi-Fi-loss debounce even while pending
        val smManual = ExcursionStateMachine(wifiLossDebounceSec = 30L)
        smManual.onBleAdvertisement(2000L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        smManual.onWifiContextChanged(2010L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertTrue(smManual.isPendingWifiLossDebounce)
        val tManual = smManual.onManualTripStart(2015L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tManual.currentState)
        assertTrue(tManual.startedNewExcursion)
        assertEquals(2015L, tManual.startedExcursionStartEpochSec)
        assertTrue(tManual.startedByManualOverride)
        assertFalse(smManual.isPendingWifiLossDebounce)

        // Starting away from home out of IDLE_OUT_OF_RANGE does not apply Wi-Fi-loss debounce
        val smAway = ExcursionStateMachine(wifiLossDebounceSec = 30L)
        val tAway = smAway.onBleAdvertisement(3000L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tAway.currentState)
        assertTrue(tAway.startedNewExcursion)
        assertEquals(3000L, tAway.startedExcursionStartEpochSec)
        assertFalse(tAway.startedByManualOverride)
    }

    @Test
    fun testAutoDiscardBlipPredicateProtectsManualPinnedAndEditedTrips() {
        val baseStart = 1_794_500_000L
        fun untouchedSlice(endSec: Long) = listOf(
            SessionSliceRecord(
                id = 1L,
                excursionId = 10L,
                startEpochSec = baseStart,
                endEpochSec = endSec,
                includeInExport = true,
                label = MetadataDatabase.DEFAULT_SLICE_LABEL,
                notes = ""
            )
        )
        fun autoExc(
            durationSec: Long,
            endNull: Boolean = false,
            isManual: Boolean = false,
            isPinned: Boolean = false,
            title: String = "Trip (Nov 18, 2:14 PM)",
            notes: String = ""
        ) = ExcursionRecord(
            id = 10L,
            deviceId = 1L,
            startEpochSec = baseStart,
            endEpochSec = if (endNull) null else baseStart + durationSec,
            isPinned = isPinned,
            title = title,
            notes = notes,
            isManual = isManual
        )

        // Closed untouched 120s auto-trip past 90s grace window -> discarded
        val end120 = baseStart + 120L
        assertTrue(MetadataDatabase.isAutoDiscardableBlip(autoExc(120L), untouchedSlice(end120), end120 + 91L))

        // Inside 90s coalesce grace window -> kept until grace elapses
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(120L), untouchedSlice(end120), end120 + 90L))

        // 180s (3m) auto-trip past grace window -> kept
        val end180 = baseStart + 180L
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(180L), untouchedSlice(end180), end180 + 120L))

        // Ongoing, manual, pinned, renamed, annotated, split, or relabeled -> all protected
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L, endNull = true), untouchedSlice(baseStart + 60L), baseStart + 300L))
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L, isManual = true), untouchedSlice(baseStart + 60L), baseStart + 300L))
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L, isPinned = true), untouchedSlice(baseStart + 60L), baseStart + 300L))
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L, title = "Elevator Check"), untouchedSlice(baseStart + 60L), baseStart + 300L))
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L, notes = "Hallway"), untouchedSlice(baseStart + 60L), baseStart + 300L))

        val end60 = baseStart + 60L
        val renamedSlice = listOf(untouchedSlice(end60).first().copy(label = "Elevator"))
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L), renamedSlice, end60 + 200L))
        val splitSlices = listOf(
            SessionSliceRecord(1L, 10L, baseStart, baseStart + 30L, true, MetadataDatabase.DEFAULT_SLICE_LABEL, ""),
            SessionSliceRecord(2L, 10L, baseStart + 30L, end60, true, MetadataDatabase.DEFAULT_SLICE_LABEL, "")
        )
        assertFalse(MetadataDatabase.isAutoDiscardableBlip(autoExc(60L), splitSlices, end60 + 200L))
    }

    @Test
    fun testLeavingSavedWifiWithoutMonitorDoesNotStartGhostExcursion() {
        val sm = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L,
            wifiLossDebounceSec = 30L
        )
        // Stationary at Home with monitor broadcasting at 08:20:00
        val t0 = 1_795_184_400L
        val tHome = sm.onBleAdvertisement(t0, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tHome.currentState)

        // Leave Home Wi-Fi at 08:20:32 without the monitor (even with one stray porch packet at +8s)
        val tWifiDrop = t0 + 32L
        val tDrop = sm.onWifiContextChanged(tWifiDrop, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tDrop.currentState)
        assertFalse(tDrop.startedNewExcursion)

        val tPorch = sm.onBleAdvertisement(tWifiDrop + 8L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tPorch.currentState)
        assertFalse(tPorch.startedNewExcursion)

        // 30s debounce elapses at 08:21:02, but no post-debounce BLE packet arrived -> must NOT start ghost trip
        val tDebounce = sm.onTimerTick(tWifiDrop + 30L)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tDebounce.currentState)
        assertFalse(tDebounce.startedNewExcursion)
        assertFalse(tDebounce.shouldRunGps)

        // Once BLE freshness expires (>90s after stray porch packet), transitions cleanly to IDLE_OUT_OF_RANGE
        val tIdle = sm.onTimerTick(tWifiDrop + 8L + 91L)
        assertEquals(ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE, tIdle.currentState)
        assertFalse(tIdle.startedNewExcursion)

        // Returning home 20m later: BLE seen 7s before Wi-Fi reconnects -> starts at return time, NOT 08:20:32
        val tReturnBle = tWifiDrop + 1198L
        val tReturnStart = sm.onBleAdvertisement(tReturnBle, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE, tReturnStart.currentState)
        assertTrue(tReturnStart.startedNewExcursion)
        assertEquals(tReturnBle, tReturnStart.startedExcursionStartEpochSec)

        val tReturnWifi = sm.onWifiContextChanged(tReturnBle + 7L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP, tReturnWifi.currentState)
        assertTrue(tReturnWifi.closedExcursion)
        assertEquals(tReturnBle + 7L, tReturnWifi.closedExcursionEndEpochSec)
    }

    @Test
    fun testClosingFromSignalPausedClampsEndToLastBlePacket() {
        val sm = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L,
            wifiLossDebounceSec = 0L
        )
        // Start excursion away from home and receive BLE for 10 minutes (1000..1600)
        val tStart = sm.onBleAdvertisement(1000L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertTrue(tStart.startedNewExcursion)
        sm.onBleAdvertisement(1600L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)

        // BLE stops -> enters FIELD_EXCURSION_SIGNAL_PAUSED at 1691
        val tPaused = sm.onTimerTick(1691L)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED, tPaused.currentState)
        assertFalse(tPaused.closedExcursion)

        // Reconnect to Saved Wi-Fi 15 minutes later (2500) -> closes with end clamped to last BLE (1600), not 2500
        val tCloseWifi = sm.onWifiContextChanged(2500L, connectedKnownPlaceId = 1, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE, tCloseWifi.currentState)
        assertTrue(tCloseWifi.closedExcursion)
        assertEquals(1600L, tCloseWifi.closedExcursionEndEpochSec)

        // Same clamping on 45-minute signal-paused timeout close
        val smTimeout = ExcursionStateMachine(
            gpsPauseTimeoutSec = 90L,
            excursionCloseTimeoutSec = 2700L,
            wifiLossDebounceSec = 0L
        )
        smTimeout.onBleAdvertisement(1000L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        smTimeout.onBleAdvertisement(1600L, connectedKnownPlaceId = 0, hasConfiguredKnownPlaces = true)
        assertEquals(ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED, smTimeout.onTimerTick(1691L).currentState)
        val tTimeoutClose = smTimeout.onTimerTick(1600L + 2701L)
        assertEquals(ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE, tTimeoutClose.currentState)
        assertTrue(tTimeoutClose.closedExcursion)
        assertEquals(1600L, tTimeoutClose.closedExcursionEndEpochSec)
    }

    @Test
    fun testAutoDiscardBlipPrunesSparseSignalPausedGhostTrips() {
        val start = 1_795_184_432L
        val end = 1_795_185_637L // 20m 5s (1205s) wall-clock span
        val ghostExc = ExcursionRecord(
            id = 15L,
            deviceId = 1L,
            startEpochSec = start,
            endEpochSec = end,
            isPinned = false,
            title = "Trip (Nov 20, 8:20 AM)",
            notes = "",
            isManual = false
        )
        val untouched = listOf(
            SessionSliceRecord(18L, 15L, start, end, true, MetadataDatabase.DEFAULT_SLICE_LABEL, "")
        )
        val afterGrace = end + 120L

        // Exact reported profile: 20m 5s wall-clock span, 1 single 15s bucket at the end -> discarded
        assertTrue(
            MetadataDatabase.isAutoDiscardableBlip(
                excursion = ghostExc,
                slices = untouched,
                nowEpochSec = afterGrace,
                recordedSampleSpanSec = 15L,
                recordedSlotCount = 1
            )
        )

        // Sparse 2-slot ghost (1 slot at start + 1 slot at end across 20m gap) -> discarded
        assertTrue(
            MetadataDatabase.isAutoDiscardableBlip(
                excursion = ghostExc,
                slices = untouched,
                nowEpochSec = afterGrace,
                recordedSampleSpanSec = 1205L,
                recordedSlotCount = 2
            )
        )

        // Genuine trip with >= 3m recorded sample span and >= 3 slots -> kept
        assertFalse(
            MetadataDatabase.isAutoDiscardableBlip(
                excursion = ghostExc,
                slices = untouched,
                nowEpochSec = afterGrace,
                recordedSampleSpanSec = 600L,
                recordedSlotCount = 40
            )
        )

        // Pinned / manual / renamed ghost trips remain protected
        assertFalse(
            MetadataDatabase.isAutoDiscardableBlip(
                excursion = ghostExc.copy(isPinned = true),
                slices = untouched,
                nowEpochSec = afterGrace,
                recordedSampleSpanSec = 15L,
                recordedSlotCount = 1
            )
        )
        assertFalse(
            MetadataDatabase.isAutoDiscardableBlip(
                excursion = ghostExc.copy(isManual = true),
                slices = untouched,
                nowEpochSec = afterGrace,
                recordedSampleSpanSec = 15L,
                recordedSlotCount = 1
            )
        )
    }

    @Test
    fun testFormatTripPickerLabelShowsDurationAndLiveStatus() {
        val baseStart = 1_794_500_000L
        val closedShort = ExcursionRecord(1L, 1L, baseStart, baseStart + 45L, false, "Trip (Nov 18, 3:00 PM)", "")
        assertEquals(
            "Trip (Nov 18, 3:00 PM) • 45s • ⏳ 14d",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(closedShort, baseStart + 100L)
        )

        val closed42m = ExcursionRecord(2L, 1L, baseStart, baseStart + 42L * 60L, false, "Trip (Nov 18, 2:14 PM)", "")
        assertEquals(
            "Trip (Nov 18, 2:14 PM) • 42m • ⏳ 12d",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(closed42m, baseStart + 2L * 86_400L)
        )

        assertEquals(
            "Trip (Nov 18, 2:14 PM) • 42m • ⏳ <1d",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(closed42m, baseStart + 13L * 86_400L + 12L * 3_600L)
        )

        val closedPinned = ExcursionRecord(3L, 1L, baseStart, baseStart + 75L * 60L, true, "Downtown Clinic", "")
        assertEquals(
            "Downtown Clinic • 1h 15m • 📌 Pinned",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(closedPinned, baseStart + 5000L)
        )

        val liveUnpinned = ExcursionRecord(4L, 1L, baseStart, null, false, "Trip (Nov 18, 7:20 PM)", "", isManual = true)
        assertEquals(
            "Trip (Nov 18, 7:20 PM) • Live (12m 30s)",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(liveUnpinned, baseStart + 12L * 60L + 30L)
        )

        val livePinned = ExcursionRecord(5L, 1L, baseStart, null, true, "Evening Walk", "", isManual = true)
        assertEquals(
            "Evening Walk • Live (5m) • 📌 Pinned",
            io.github.androidaql.ui.MainActivity.formatTripPickerLabel(livePinned, baseStart + 5L * 60L)
        )
    }

    @Test
    fun testAssignNotesToSlicesHandlesBoundariesWithoutDuplication() {
        val slices = listOf(
            SessionSliceRecord(101L, 10L, 1000L, 1180L, false, "Walk", ""),
            SessionSliceRecord(102L, 10L, 1180L, 1360L, false, "Train", ""),
            SessionSliceRecord(103L, 10L, 1360L, 1540L, false, "Office", "")
        )
        val notes = listOf(
            TimelineNoteRecord(1L, 985L, "Before trip", 985L),
            TimelineNoteRecord(2L, 1000L, "At start", 1000L),
            TimelineNoteRecord(3L, 1090L, "Mid walk", 1090L),
            TimelineNoteRecord(4L, 1180L, "Boarded train", 1180L),
            TimelineNoteRecord(5L, 1360L, "Arrived office", 1360L),
            TimelineNoteRecord(6L, 1540L, "At end", 1540L),
            TimelineNoteRecord(7L, 1555L, "After trip", 1555L)
        )

        val grouped = MetadataDatabase.assignNotesToSlices(slices, notes)
        assertEquals(listOf(2L, 3L), grouped[101L]?.map { it.id })
        assertEquals(listOf(4L), grouped[102L]?.map { it.id })
        assertEquals(listOf(5L, 6L), grouped[103L]?.map { it.id })
    }

    @Test
    fun testComputeAlignedSplitForNoteSnapsTo15sGridAndRejectsEndpoints() {
        org.junit.Assert.assertNull(MetadataDatabase.computeAlignedSplitForNote(1500L, 1500L, 1560L))
        org.junit.Assert.assertNull(MetadataDatabase.computeAlignedSplitForNote(1507L, 1500L, 1560L))
        assertEquals(1515L, MetadataDatabase.computeAlignedSplitForNote(1508L, 1500L, 1560L))
        assertEquals(1515L, MetadataDatabase.computeAlignedSplitForNote(1522L, 1500L, 1560L))
        assertEquals(1530L, MetadataDatabase.computeAlignedSplitForNote(1523L, 1500L, 1560L))
        assertEquals(1545L, MetadataDatabase.computeAlignedSplitForNote(1552L, 1500L, 1560L))
        org.junit.Assert.assertNull(MetadataDatabase.computeAlignedSplitForNote(1553L, 1500L, 1560L))
        org.junit.Assert.assertNull(MetadataDatabase.computeAlignedSplitForNote(1560L, 1500L, 1560L))
    }

    @Test
    fun testAutoDiscardBlipProtectsExcursionWithTimelineNotes() {
        val baseStart = 1_700_000_000L
        val blip = ExcursionRecord(12L, 1L, baseStart, baseStart + 120L, false, "Trip (12:00)", "", isManual = false)
        val slices = listOf(
            SessionSliceRecord(20L, 12L, baseStart, baseStart + 120L, false, "Unlabeled Trip Span", "")
        )
        val nowAfterGrace = baseStart + 600L

        assertTrue(
            MetadataDatabase.isAutoDiscardableBlip(
                blip,
                slices,
                nowAfterGrace,
                hasTimelineNotes = false
            )
        )
        assertFalse(
            MetadataDatabase.isAutoDiscardableBlip(
                blip,
                slices,
                nowAfterGrace,
                hasTimelineNotes = true
            )
        )
    }

    @Test
    fun testLiveEdgeCursorFollowsLatestDetachesOnScrubAndReattachesOnEdgeOrScrollOff() {
        // 1. Initial load: live chart follows latest slot; Trips chart initializes at midpoint
        assertEquals(
            1890L to true,
            DualPaneSlicerView.resolveCursorOnSlotsUpdated(
                followLatestEdge = true,
                isFollowingLiveEdge = true,
                currentCursorEpochSec = 0L,
                tripStartEpochSec = 1000L,
                tripEndEpochSec = 1900L,
                firstSlotEpochSec = 1005L,
                midSlotEpochSec = 1455L,
                lastSlotEpochSec = 1890L
            )
        )
        assertEquals(
            1455L to false,
            DualPaneSlicerView.resolveCursorOnSlotsUpdated(
                followLatestEdge = false,
                isFollowingLiveEdge = true,
                currentCursorEpochSec = 0L,
                tripStartEpochSec = 1000L,
                tripEndEpochSec = 1900L,
                firstSlotEpochSec = 1005L,
                midSlotEpochSec = 1455L,
                lastSlotEpochSec = 1890L
            )
        )

        // 2. Rolling updates while following advance cursor to newest sample
        assertEquals(
            1920L to true,
            DualPaneSlicerView.resolveCursorOnSlotsUpdated(
                followLatestEdge = true,
                isFollowingLiveEdge = true,
                currentCursorEpochSec = 1890L,
                tripStartEpochSec = 1020L,
                tripEndEpochSec = 1920L,
                firstSlotEpochSec = 1020L,
                midSlotEpochSec = 1470L,
                lastSlotEpochSec = 1920L
            )
        )

        // 3. Scrubbing left detaches from live edge; subsequent rolling updates preserve selection
        assertFalse(
            DualPaneSlicerView.shouldFollowLiveEdgeAfterScrub(
                targetEpochSec = 1500L,
                latestSlotEpochSec = 1920L,
                windowEndEpochSec = 1920L
            )
        )
        assertEquals(
            1500L to false,
            DualPaneSlicerView.resolveCursorOnSlotsUpdated(
                followLatestEdge = true,
                isFollowingLiveEdge = false,
                currentCursorEpochSec = 1500L,
                tripStartEpochSec = 1050L,
                tripEndEpochSec = 1950L,
                firstSlotEpochSec = 1050L,
                midSlotEpochSec = 1500L,
                lastSlotEpochSec = 1950L
            )
        )

        // 4. Scrubbing back to the right edge or latest slot re-attaches live follow
        assertTrue(
            DualPaneSlicerView.shouldFollowLiveEdgeAfterScrub(
                targetEpochSec = 1950L,
                latestSlotEpochSec = 1950L,
                windowEndEpochSec = 1950L
            )
        )
        assertTrue(
            DualPaneSlicerView.shouldFollowLiveEdgeAfterScrub(
                targetEpochSec = 1940L,
                latestSlotEpochSec = 1940L,
                windowEndEpochSec = 1950L
            )
        )

        // 5. Detached cursor scrolling off the left edge auto-reattaches to live latest slot
        assertEquals(
            2415L to true,
            DualPaneSlicerView.resolveCursorOnSlotsUpdated(
                followLatestEdge = true,
                isFollowingLiveEdge = false,
                currentCursorEpochSec = 1500L,
                tripStartEpochSec = 1515L,
                tripEndEpochSec = 2415L,
                firstSlotEpochSec = 1515L,
                midSlotEpochSec = 1965L,
                lastSlotEpochSec = 2415L
            )
        )

        // 6. Tapping in a stale disconnect gap or on an old last reading stays detached
        assertFalse(
            DualPaneSlicerView.shouldFollowLiveEdgeAfterScrub(
                targetEpochSec = 1500L,
                latestSlotEpochSec = 1200L,
                windowEndEpochSec = 1950L
            )
        )
        assertFalse(
            DualPaneSlicerView.shouldFollowLiveEdgeAfterScrub(
                targetEpochSec = 1200L,
                latestSlotEpochSec = 1200L,
                windowEndEpochSec = 1950L
            )
        )
    }

    @Test
    fun testGeneratedAndBundledXlsxPassOpenXmlAudit() {
        val home = System.getProperty("user.home").orEmpty()
        val candidateDirs = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.let { addAll(it) }
            if (home.isNotBlank()) {
                add("$home/.nix-profile/bin")
                add("$home/.local/bin")
            }
            add("/opt/homebrew/bin")
            add("/usr/local/bin")
        }.distinct()

        fun findExecutable(name: String): File? =
            candidateDirs.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }

        val directTool = findExecutable("openxml-audit")
        val uvxTool = if (directTool == null) findExecutable("uvx") else null
        val requireAudit = System.getenv("AIRSLICE_REQUIRE_OPENXML_AUDIT") == "1"
        if (directTool == null && uvxTool == null) {
            if (requireAudit) {
                org.junit.Assert.fail("AIRSLICE_REQUIRE_OPENXML_AUDIT=1 is set, but neither openxml-audit nor uvx was found.")
            }
            org.junit.Assume.assumeTrue("Skipping host openxml-audit validation (tool not installed).", false)
        }

        fun runOpenXmlAudit(targetFile: File) {
            val attempts = if (directTool != null) {
                listOf(listOf(directTool.absolutePath, "--output", "json", targetFile.absolutePath))
            } else {
                listOf(
                    listOf(uvxTool!!.absolutePath, "--offline", "openxml-audit", "--output", "json", targetFile.absolutePath),
                    listOf(uvxTool.absolutePath, "openxml-audit", "--output", "json", targetFile.absolutePath)
                )
            }
            var lastExit = -1
            var lastStdout = ""
            var lastStderr = ""
            for (cmd in attempts) {
                val proc = ProcessBuilder(cmd).start()
                val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    org.junit.Assert.fail("Timed out running ${cmd.joinToString(" ")}")
                }
                lastStdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                lastStderr = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
                lastExit = proc.exitValue()
                if (lastExit == 0 || lastStdout.contains("\"valid\"")) {
                    break
                }
            }
            assertTrue(
                "openxml-audit failed for ${targetFile.name} (exit=$lastExit):\nSTDOUT:\n$lastStdout\nSTDERR:\n$lastStderr",
                lastExit == 0 &&
                    Regex("\"valid\"\\s*:\\s*true\\b").containsMatchIn(lastStdout) &&
                    Regex("\"error_count\"\\s*:\\s*0\\b").containsMatchIn(lastStdout) &&
                    Regex("\"warning_count\"\\s*:\\s*0\\b").containsMatchIn(lastStdout)
            )
        }

        val tmpDir = Files.createTempDirectory("aql_openxml_audit_").toFile()
        try {
            val device = MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Travel Monitor", true)
            val excursion = ExcursionRecord(7L, 1L, 1500L, 1560L, true, "Clinic & Walk <Test>", " Multi-line\nnote ")
            val slices = listOf(
                SessionSliceRecord(10L, 7L, 1500L, 1515L, true, "Home & Porch", "Start"),
                SessionSliceRecord(11L, 7L, 1515L, 1530L, false, "Tunnel Dropout", "No BLE"),
                SessionSliceRecord(12L, 7L, 1530L, 1560L, true, "Coffee, Fl 2", "Quote \"test\"")
            )
            val slots = listOf(
                AqlRingBufferStore.RingSlotRecord(1500L, 2, 1, 520, 510, 535, 84, 11, 122, 15, 215, 405, 90, -60, AqlRingBufferStore.SENTINEL_UINT16, AqlRingBufferStore.SENTINEL_UINT16),
                AqlRingBufferStore.RingSlotRecord(1545L, 3, 0, 1180, 1150, 1210, 142, 19, 184, 24, 228, 445, 255, -128, 145, 120)
            )
            val notes = listOf(
                TimelineNoteRecord(1L, 1500L, " 📍 Marker ", 1500L),
                TimelineNoteRecord(2L, 1520L, "Signal <lost> & resumed", 1520L)
            )
            val places = mapOf(1 to io.github.androidaql.storage.KnownPlace(1, "Home"))

            val wholeFile = File(tmpDir, "whole_trip.xlsx")
            wholeFile.writeBytes(
                XlsxExporter.exportExcursionXlsx(
                    device = device,
                    excursion = excursion,
                    slices = slices,
                    slots = slots,
                    notes = notes,
                    knownPlacesById = places,
                    onlySelectedForExport = false,
                    zoneId = ZoneOffset.UTC
                )
            )

            val sliceFile = File(tmpDir, "single_slice.xlsx")
            sliceFile.writeBytes(
                XlsxExporter.exportSliceXlsx(
                    device = device,
                    excursion = excursion,
                    slice = slices[2],
                    slots = slots,
                    notes = notes,
                    knownPlacesById = places,
                    zoneId = ZoneOffset.UTC
                )
            )

            val demoDir = listOf(
                File("app/src/main/assets/demo"),
                File("src/main/assets/demo")
            ).firstOrNull { it.isDirectory }
            assertNotNull("Expected bundled demo assets directory", demoDir)

            val demoTripFile = File(demoDir!!, "sample_trip.xlsx")
            val demoStatFile = File(demoDir, "sample_now_stationary.xlsx")

            for (target in listOf(wholeFile, sliceFile, demoTripFile, demoStatFile)) {
                runOpenXmlAudit(target)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testParseXmlDocumentNamespacesEntitiesBomMalformedAndXxeSafety() {
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
        val firstSheet = sheets.item(0) as org.w3c.dom.Element
        assertEquals("Morning & Noon", firstSheet.getAttribute("name"))
        assertEquals(
            "rId1",
            firstSheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        )
        val secondSheet = sheets.item(1) as org.w3c.dom.Element
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

        val secretPath = Files.createTempFile("aql_xxe_probe_", ".txt")
        try {
            Files.write(secretPath, "XXE_SECRET_TOKEN_12345".toByteArray(Charsets.UTF_8))
            val xxeXml = """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE root [
                    <!ENTITY ext SYSTEM "${secretPath.toUri().toASCIIString()}">
                ]>
                <root>&ext;</root>""".trimIndent()
            val xxeOutcome = runCatching { XlsxWorkbook.parseXmlDocument(xxeXml.toByteArray(Charsets.UTF_8)) }
            val leaked = xxeOutcome.getOrNull()?.documentElement?.textContent.orEmpty()
            assertFalse(
                "External entity must not leak local file contents",
                leaked.contains("XXE_SECRET_TOKEN_12345")
            )
        } finally {
            Files.deleteIfExists(secretPath)
        }
    }
}
