package io.github.androidaql.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build

/**
 * Bound by the system whenever an associated air quality monitor comes into BLE range.
 * Android grants a background foreground-service start exemption while bound, allowing
 * [AqlCollectorService] to resume passive logging after phone reboots or app updates.
 */
class AqlCompanionDeviceService : CompanionDeviceService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        handleMonitorAppeared("companion_device_appeared")
    }

    @SuppressLint("NewApi")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT >= 36 && event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            handleMonitorAppeared("companion_presence_event")
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Keep AqlCollectorService running so existing excursion pause / out-of-range logic works normally.
    }

    private fun handleMonitorAppeared(reason: String) {
        AqlCollectorService.tryAutoStartService(this, reason)
    }
}
