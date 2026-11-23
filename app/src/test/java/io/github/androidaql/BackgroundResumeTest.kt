package io.github.androidaql

import android.content.Intent
import io.github.androidaql.service.AqlAutoStartReceiver
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.service.CompanionDeviceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
