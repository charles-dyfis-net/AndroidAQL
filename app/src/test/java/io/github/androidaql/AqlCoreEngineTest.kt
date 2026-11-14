package io.github.androidaql

import io.github.androidaql.ble.QingpingBleParser
import io.github.androidaql.ble.QingpingFrameAssembler
import io.github.androidaql.engine.AlertEvaluator
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.engine.GpsMovementFilter
import io.github.androidaql.export.CsvExporter
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.SliceCategory
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
            // Use compact test archives to verify full wrap-around and consolidation quickly
            val specs = listOf(
                AqlRingBufferStore.ArchiveSpec(stepSec = 15, slotCount = 64),
                AqlRingBufferStore.ArchiveSpec(stepSec = 300, slotCount = 32),
                AqlRingBufferStore.ArchiveSpec(stepSec = 3600, slotCount = 16)
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

                // Write a sample in the next 15s bucket (ts = 1515), still in the same 300s Archive 1 bucket (1500)
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

                // Archive 1 (300s step) should have consolidated all 3 samples into bucket 1500
                val arch1Slots = store.queryRange(1, 1500L, 1500L)
                assertEquals(1, arch1Slots.size)
                assertEquals(3, arch1Slots[0].sampleCount)
                assertEquals(1000, arch1Slots[0].co2PpmMean)
                assertEquals(800, arch1Slots[0].co2PpmMin)
                assertEquals(1200, arch1Slots[0].co2PpmMax)

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
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testStateMachineDecoupledTimeoutsAndBleModes() {
        val sm = ExcursionStateMachine(gpsPauseTimeoutSec = 90L, excursionCloseTimeoutSec = 2700L)
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
    fun testCsvExporterSelfContainedOutput() {
        val device = MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Travel Monitor", true)
        val excursion = ExcursionRecord(7L, 1L, 1500L, 1530L, true, "Downtown Doctor Visit", "")
        val slices = listOf(
            SessionSliceRecord(10L, 7L, 1500L, 1500L, false, false, SliceCategory.TRANSIT, "CTA Bus", ""),
            SessionSliceRecord(11L, 7L, 1515L, 1530L, true, true, SliceCategory.DESTINATION, "Waiting Room, Fl 10", "Busy")
        )
        val slots = listOf(
            AqlRingBufferStore.RingSlotRecord(1500L, 2, 0, 950, 920, 980, 80, 10, 120, 15, 215, 400, 90, -60, 450, 150),
            AqlRingBufferStore.RingSlotRecord(1515L, 3, 0, 1480, 1450, 1510, 110, 14, 160, 19, 228, 445, 89, -58, 0, 850)
        )

        val csv = CsvExporter.exportExcursionCsv(
            device = device,
            excursion = excursion,
            slices = slices,
            slots = slots,
            onlySelectedForExport = true,
            zoneId = ZoneOffset.UTC
        )
        val lines = csv.trim().lines()
        // Header + 1 row (since slice 10 had includeInExport = false)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"Waiting Room, Fl 10\""))
        assertTrue(lines[1].contains(",1480,1450,1510,"))
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
}
