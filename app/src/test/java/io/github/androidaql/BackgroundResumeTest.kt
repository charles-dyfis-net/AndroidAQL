package io.github.androidaql

import android.bluetooth.le.ScanSettings
import android.content.Intent
import io.github.androidaql.engine.ExcursionStateMachine.CollectorState
import io.github.androidaql.service.AqlAutoStartReceiver
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.service.CompanionDeviceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundResumeTest {

    @Test
    fun shouldAttemptAutoStartHandlesBootAndPackageReplaced() {
        assertEquals(
            AqlAutoStartReceiver.REASON_BOOT_COMPLETED,
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_BOOT_COMPLETED,
                hasBluetoothScanPermission = true
            )
        )
        assertEquals(
            AqlAutoStartReceiver.REASON_PACKAGE_REPLACED,
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                hasBluetoothScanPermission = true
            )
        )
    }

    @Test
    fun shouldAttemptAutoStartRejectsMissingPermissionAndUnknownActions() {
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_BOOT_COMPLETED,
                hasBluetoothScanPermission = false
            )
        )
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                hasBluetoothScanPermission = false
            )
        )
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_POWER_CONNECTED,
                hasBluetoothScanPermission = true
            )
        )
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = null,
                hasBluetoothScanPermission = true
            )
        )
    }

    @Test
    fun shouldAttemptAutoStartRespectsUserStoppedFlag() {
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_BOOT_COMPLETED,
                hasBluetoothScanPermission = true,
                isUserStopped = true
            )
        )
        assertNull(
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                hasBluetoothScanPermission = true,
                isUserStopped = true
            )
        )
        assertEquals(
            AqlAutoStartReceiver.REASON_BOOT_COMPLETED,
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_BOOT_COMPLETED,
                hasBluetoothScanPermission = true,
                isUserStopped = false
            )
        )
        assertEquals(
            AqlAutoStartReceiver.REASON_PACKAGE_REPLACED,
            AqlAutoStartReceiver.shouldAttemptAutoStart(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                hasBluetoothScanPermission = true,
                isUserStopped = false
            )
        )
    }

    @Test
    fun stopNotificationActionAndCompanionWakeGatesMatchExpectedConditions() {
        assertTrue(AqlCollectorService.shouldShowStopNotificationAction(hasActiveExcursion = false))
        assertFalse(AqlCollectorService.shouldShowStopNotificationAction(hasActiveExcursion = true))
        assertFalse(AqlCollectorService.shouldAllowCompanionAutoWake(isUserStopped = true))
        assertTrue(AqlCollectorService.shouldAllowCompanionAutoWake(isUserStopped = false))
    }

    @Test
    fun buildResumeNotificationCopyMatchesExpectedReasons() {
        assertEquals(
            "AirSlice paused after reboot" to "Tap to resume passive air quality logging.",
            AqlCollectorService.buildResumeNotificationCopy(AqlAutoStartReceiver.REASON_BOOT_COMPLETED)
        )
        assertEquals(
            "AirSlice paused after app update" to "Tap to resume passive air quality logging.",
            AqlCollectorService.buildResumeNotificationCopy(AqlAutoStartReceiver.REASON_PACKAGE_REPLACED)
        )
        assertEquals(
            "AirSlice passive logging paused" to "Tap to resume air quality monitoring.",
            AqlCollectorService.buildResumeNotificationCopy("companion_device_appeared")
        )
    }

    @Test
    fun formatAssociationLabelFormatsDisplayNameAndMacCleanly() {
        assertEquals(
            "Qingping Air Monitor Lite (58:2D:34:12:AB:CD)",
            CompanionDeviceHelper.formatAssociationLabel("Qingping Air Monitor Lite", "58:2D:34:12:AB:CD")
        )
        assertEquals(
            "Qingping Air Monitor Lite",
            CompanionDeviceHelper.formatAssociationLabel("  Qingping Air Monitor Lite  ", null)
        )
        assertEquals(
            "58:2D:34:12:AB:CD",
            CompanionDeviceHelper.formatAssociationLabel("58:2d:34:12:ab:cd", "58:2D:34:12:AB:CD")
        )
        assertEquals(
            "58:2D:34:12:AB:CD",
            CompanionDeviceHelper.formatAssociationLabel("", "58:2D:34:12:AB:CD")
        )
        assertNull(CompanionDeviceHelper.formatAssociationLabel("  ", "  "))
    }

    @Test
    fun buildManualTripStopSuggestionCopyFormatsNamedAndFallbackPlaces() {
        assertEquals(
            "Back at Home — End manual trip?" to
                "You reconnected to Home Wi-Fi. Your manual trip is still recording.",
            AqlCollectorService.buildManualTripStopSuggestionCopy("  Home  ")
        )
        assertEquals(
            "Back on Saved Wi-Fi — End manual trip?" to
                "You reconnected to a Saved Wi-Fi Place. Your manual trip is still recording.",
            AqlCollectorService.buildManualTripStopSuggestionCopy(null)
        )
        assertEquals(
            "Back on Saved Wi-Fi — End manual trip?" to
                "You reconnected to a Saved Wi-Fi Place. Your manual trip is still recording.",
            AqlCollectorService.buildManualTripStopSuggestionCopy("   ")
        )
    }

    @Test
    fun normalizeBluetoothMacAndSingleDeviceFlagValidateAddresses() {
        assertEquals(
            "58:2D:34:12:AB:BC",
            CompanionDeviceHelper.normalizeBluetoothMac("  58:2d:34:12:ab:bc  ")
        )
        assertTrue(CompanionDeviceHelper.shouldUseSingleDeviceRequest("58:2d:34:12:ab:bc"))

        val invalidAddresses = listOf(
            null,
            "",
            "   ",
            "00:00:00:00:00:00",
            "02:00:00:00:00:00",
            "58:2D:34:12:AB",
            "58-2D-34-12-AB-BC",
            "ZZ:2D:34:12:AB:BC"
        )
        for (addr in invalidAddresses) {
            assertNull("Expected null for '$addr'", CompanionDeviceHelper.normalizeBluetoothMac(addr))
            assertFalse("Expected false for '$addr'", CompanionDeviceHelper.shouldUseSingleDeviceRequest(addr))
            assertNull("Expected null observation MAC for '$addr'", CompanionDeviceHelper.formatPresenceObservationMac(addr))
        }
        assertEquals(
            "C1:0B:80:1D:1F:35",
            CompanionDeviceHelper.formatPresenceObservationMac("c1:0b:80:1d:1f:35")
        )
    }

    @Test
    fun shouldPostProactiveLinkSuggestionChecksAllGates() {
        assertTrue(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = false,
                isSuggestionSuppressed = false,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "58:2d:34:12:ab:bc"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = false,
                hasExistingAssociations = false,
                isSuggestionSuppressed = false,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "58:2D:34:12:AB:BC"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = true,
                isSuggestionSuppressed = false,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "58:2D:34:12:AB:BC"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = false,
                isSuggestionSuppressed = true,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "58:2D:34:12:AB:BC"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = false,
                isSuggestionSuppressed = false,
                hasNotificationPermission = false,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "58:2D:34:12:AB:BC"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = false,
                isSuggestionSuppressed = false,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = true,
                liveBleAddress = "58:2D:34:12:AB:BC"
            )
        )
        assertFalse(
            CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
                isCompanionSupported = true,
                hasExistingAssociations = false,
                isSuggestionSuppressed = false,
                hasNotificationPermission = true,
                isDemoOrScreenshotMode = false,
                liveBleAddress = "00:00:00:00:00:00"
            )
        )
    }

    @Test
    fun shouldEagerlyStartCollectorAfterSystemEventOnlyWhenNoCompanionObserved() {
        assertTrue(AqlAutoStartReceiver.shouldEagerlyStartCollectorAfterSystemEvent(0))
        assertTrue(AqlAutoStartReceiver.shouldEagerlyStartCollectorAfterSystemEvent(-1))
        assertFalse(AqlAutoStartReceiver.shouldEagerlyStartCollectorAfterSystemEvent(1))
        assertFalse(AqlAutoStartReceiver.shouldEagerlyStartCollectorAfterSystemEvent(2))
    }

    private fun baseSleepInput(nowMs: Long = 1_000_000L): AqlCollectorService.CompanionIdleSleepInput =
        AqlCollectorService.CompanionIdleSleepInput(
            hasLinkedCompanionDevice = true,
            collectorState = CollectorState.IDLE_OUT_OF_RANGE,
            hasActiveExcursion = false,
            hasPendingBlipCleanup = false,
            hasBoundUiClients = false,
            isDemoStreamRunning = false,
            isOtaUpgradeInFlight = false,
            hasActiveGattSession = false,
            isInUnprovisionedGattKeepAliveWindow = false,
            isManualExcursionOverride = false,
            nowMs = nowMs,
            idleOutOfRangeSinceMs = nowMs - 300_000L,
            lastUiUnbindMs = 0L,
            serviceStartedAtMs = nowMs - 300_000L,
            lastCompanionAppearedMs = 0L,
            companionDisappearedAtMs = null,
            lastLiveBleHeardMs = nowMs - 400_000L
        )

    @Test
    fun shouldSleepForAbsentCompanionRespectsStatesAndSafetyGates() {
        val base = baseSleepInput()
        assertTrue(AqlCollectorService.shouldSleepForAbsentCompanion(base))

        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(hasLinkedCompanionDevice = false)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(collectorState = CollectorState.STATIONARY_KNOWN_AP)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(collectorState = CollectorState.FIELD_EXCURSION_ACTIVE)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(collectorState = CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED)))

        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(hasActiveExcursion = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(hasPendingBlipCleanup = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(hasBoundUiClients = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(isDemoStreamRunning = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(isOtaUpgradeInFlight = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(hasActiveGattSession = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(isInUnprovisionedGattKeepAliveWindow = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(isManualExcursionOverride = true)))
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(base.copy(idleOutOfRangeSinceMs = null)))
    }

    @Test
    fun shouldSleepForAbsentCompanionAppliesGraceWindows() {
        val now = 1_000_000L

        // Cold-start (no live BLE heard yet): 60s grace
        val coldJustStarted = baseSleepInput(now).copy(
            idleOutOfRangeSinceMs = now - 59_000L,
            serviceStartedAtMs = now - 59_000L,
            lastLiveBleHeardMs = 0L
        )
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(coldJustStarted))
        assertTrue(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                coldJustStarted.copy(
                    idleOutOfRangeSinceMs = now - 60_000L,
                    serviceStartedAtMs = now - 60_000L
                )
            )
        )

        // Normal BLE-silence fallback: 180s grace when OS hasn't reported disappearance
        val silentBeforeGrace = baseSleepInput(now).copy(
            idleOutOfRangeSinceMs = now - 179_000L,
            lastLiveBleHeardMs = now - 270_000L,
            companionDisappearedAtMs = null
        )
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(silentBeforeGrace))
        assertTrue(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                silentBeforeGrace.copy(idleOutOfRangeSinceMs = now - 180_000L)
            )
        )

        // Explicit OS disappearance >= lastLiveBleHeardMs: 60s grace
        val disappearedAfterLastBle = baseSleepInput(now).copy(
            idleOutOfRangeSinceMs = now - 60_000L,
            lastLiveBleHeardMs = now - 150_000L,
            companionDisappearedAtMs = now - 70_000L
        )
        assertTrue(AqlCollectorService.shouldSleepForAbsentCompanion(disappearedAfterLastBle))
        assertFalse(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                disappearedAfterLastBle.copy(idleOutOfRangeSinceMs = now - 59_000L)
            )
        )

        // Stale OS disappearance older than lastLiveBleHeardMs falls back to 180s grace
        val staleDisappeared = disappearedAfterLastBle.copy(
            idleOutOfRangeSinceMs = now - 120_000L,
            lastLiveBleHeardMs = now - 210_000L,
            companionDisappearedAtMs = now - 220_000L
        )
        assertFalse(AqlCollectorService.shouldSleepForAbsentCompanion(staleDisappeared))
        assertTrue(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                staleDisappeared.copy(idleOutOfRangeSinceMs = now - 180_000L)
            )
        )

        // Recent UI unbind or recent companion-appeared wake resets grace countdown
        assertFalse(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                baseSleepInput(now).copy(lastUiUnbindMs = now - 30_000L)
            )
        )
        assertFalse(
            AqlCollectorService.shouldSleepForAbsentCompanion(
                baseSleepInput(now).copy(lastCompanionAppearedMs = now - 30_000L)
            )
        )
    }

    @Test
    fun chooseBleScanModeAdaptsToStateAndDiscoveryNeeds() {
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = false,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = false
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.STATIONARY_KNOWN_AP,
                hasActiveGattOrOta = true,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = false
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.STATIONARY_KNOWN_AP,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = true,
                isRecentCompanionWake = false
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_POWER,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = false
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_BALANCED,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = true
            )
        )
        for (activeState in listOf(
            CollectorState.STATIONARY_KNOWN_AP,
            CollectorState.FIELD_EXCURSION_ACTIVE,
            CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
        )) {
            assertEquals(
                ScanSettings.SCAN_MODE_BALANCED,
                AqlCollectorService.chooseBleScanMode(
                    strictFdcdFilterEnabled = true,
                    collectorState = activeState,
                    hasActiveGattOrOta = false,
                    needsActiveUnprovisionedGattDiscovery = false,
                    isRecentCompanionWake = false,
                    isExternalPowerConnected = false
                )
            )
            assertEquals(
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                AqlCollectorService.chooseBleScanMode(
                    strictFdcdFilterEnabled = true,
                    collectorState = activeState,
                    hasActiveGattOrOta = false,
                    needsActiveUnprovisionedGattDiscovery = false,
                    isRecentCompanionWake = false,
                    isExternalPowerConnected = true
                )
            )
            assertEquals(
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                AqlCollectorService.chooseBleScanMode(
                    strictFdcdFilterEnabled = true,
                    collectorState = activeState,
                    hasActiveGattOrOta = false,
                    needsActiveUnprovisionedGattDiscovery = false,
                    isRecentCompanionWake = false,
                    isExternalPowerConnected = false,
                    preferMaxSamplesOnBattery = true
                )
            )
        }
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_POWER,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = false,
                isExternalPowerConnected = true,
                preferMaxSamplesOnBattery = true
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = true,
                isExternalPowerConnected = true
            )
        )
        assertEquals(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            AqlCollectorService.chooseBleScanMode(
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                hasActiveGattOrOta = false,
                needsActiveUnprovisionedGattDiscovery = false,
                isRecentCompanionWake = true,
                isExternalPowerConnected = false,
                preferMaxSamplesOnBattery = true
            )
        )
    }

    @Test
    fun shouldRestartBleScanAvoidsTwoMinuteChurnWhenIdle() {
        assertNull(
            AqlCollectorService.shouldRestartBleScan(
                isScanning = false,
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.STATIONARY_KNOWN_AP,
                msSinceStart = 300_000L,
                msSinceAnyResult = 300_000L
            )
        )
        assertEquals(
            "broad_refresh",
            AqlCollectorService.shouldRestartBleScan(
                isScanning = true,
                strictFdcdFilterEnabled = false,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                msSinceStart = 4 * 60_000L,
                msSinceAnyResult = 10_000L
            )
        )
        // In strict IDLE_OUT_OF_RANGE, do NOT restart at 2m; only refresh at 15m
        assertNull(
            AqlCollectorService.shouldRestartBleScan(
                isScanning = true,
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                msSinceStart = 120_000L,
                msSinceAnyResult = 120_000L
            )
        )
        assertEquals(
            "idle_refresh",
            AqlCollectorService.shouldRestartBleScan(
                isScanning = true,
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.IDLE_OUT_OF_RANGE,
                msSinceStart = 15 * 60_000L,
                msSinceAnyResult = 15 * 60_000L
            )
        )
        assertEquals(
            "no_results",
            AqlCollectorService.shouldRestartBleScan(
                isScanning = true,
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.STATIONARY_KNOWN_AP,
                msSinceStart = 120_000L,
                msSinceAnyResult = 60_000L
            )
        )
        assertNull(
            AqlCollectorService.shouldRestartBleScan(
                isScanning = true,
                strictFdcdFilterEnabled = true,
                collectorState = CollectorState.STATIONARY_KNOWN_AP,
                msSinceStart = 120_000L,
                msSinceAnyResult = 59_000L
            )
        )
    }

    @Test
    fun buildCompanionLinkSuggestionCopyFormatsNamedAndUnnamedMonitors() {
        assertEquals(
            "Link Qingping Air Monitor Lite for battery savings?" to
                "Tap to link Qingping Air Monitor Lite (58:2D:34:12:AB:BC). AirSlice will sleep when it's away and auto-resume when nearby.",
            AqlCollectorService.buildCompanionLinkSuggestionCopy("  Qingping Air Monitor Lite  ", "58:2D:34:12:AB:BC")
        )
        assertEquals(
            "Link 58:2D:34:12:AB:BC for battery savings?" to
                "Tap to link 58:2D:34:12:AB:BC. AirSlice will sleep when it's away and auto-resume when nearby.",
            AqlCollectorService.buildCompanionLinkSuggestionCopy(null, "58:2D:34:12:AB:BC")
        )
        assertEquals(
            "Link 58:2D:34:12:AB:BC for battery savings?" to
                "Tap to link 58:2D:34:12:AB:BC. AirSlice will sleep when it's away and auto-resume when nearby.",
            AqlCollectorService.buildCompanionLinkSuggestionCopy("   ", "58:2D:34:12:AB:BC")
        )
        assertEquals(
            "Link 58:2D:34:12:AB:BC for battery savings?" to
                "Tap to link 58:2D:34:12:AB:BC. AirSlice will sleep when it's away and auto-resume when nearby.",
            AqlCollectorService.buildCompanionLinkSuggestionCopy("58:2d:34:12:ab:bc", "58:2D:34:12:AB:BC")
        )
    }
}
