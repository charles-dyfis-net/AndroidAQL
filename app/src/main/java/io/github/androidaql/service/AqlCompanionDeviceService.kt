package io.github.androidaql.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build

/**
 * Bound by the system whenever an associated air quality monitor comes into BLE range.
 * Android grants a background foreground-service start exemption while bound, allowing
 * [AqlCollectorService] to resume passive logging after phone reboots, app updates, or
 * companion idle sleep.
 */
class AqlCompanionDeviceService : CompanionDeviceService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        handleMonitorAppeared("companion_device_appeared")
    }

    @SuppressLint("NewApi")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT >= 36) {
            when (event.event) {
                DevicePresenceEvent.EVENT_BLE_APPEARED,
                DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                    handleMonitorAppeared("companion_presence_event")
                }
                DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
                DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                    handleMonitorDisappeared("companion_presence_disappeared")
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        handleMonitorDisappeared("companion_device_disappeared")
    }

    private fun handleMonitorAppeared(reason: String) {
        AqlCollectorService.onCompanionDeviceAppeared(this, reason)
    }

    private fun handleMonitorDisappeared(reason: String) {
        AqlCollectorService.onCompanionDeviceDisappeared(reason)
    }
}
