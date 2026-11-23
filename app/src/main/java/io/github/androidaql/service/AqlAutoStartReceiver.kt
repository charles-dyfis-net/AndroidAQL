package io.github.androidaql.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Attempts to resume [AqlCollectorService] automatically after phone reboots (`BOOT_COMPLETED`)
 * and app updates (`MY_PACKAGE_REPLACED`), re-arms Companion Device presence observation, and
 * posts a tappable fallback notification if Android blocks background FGS startup.
 */
class AqlAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val reason = shouldAttemptAutoStart(
            action = intent?.action,
            hasBluetoothScanPermission = AqlCollectorService.hasBluetoothScanPermission(context)
        ) ?: return

        CompanionDeviceHelper.ensureObservingAll(context)
        AqlCollectorService.tryAutoStartService(context, reason)
    }

    companion object {
        const val REASON_BOOT_COMPLETED = "boot_completed"
        const val REASON_PACKAGE_REPLACED = "package_replaced"

        fun shouldAttemptAutoStart(
            action: String?,
            hasBluetoothScanPermission: Boolean
        ): String? {
            if (!hasBluetoothScanPermission) return null
            return when (action) {
                Intent.ACTION_BOOT_COMPLETED -> REASON_BOOT_COMPLETED
                Intent.ACTION_MY_PACKAGE_REPLACED -> REASON_PACKAGE_REPLACED
                else -> null
            }
        }
    }
}
