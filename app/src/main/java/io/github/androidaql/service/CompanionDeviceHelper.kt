package io.github.androidaql.service

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.androidaql.ble.QingpingBleParser
import java.util.regex.Pattern

/**
 * Encapsulates Android [CompanionDeviceManager] association and BLE device presence observation
 * so the OS wakes AirSlice when a linked Qingping / BTHome v2 monitor reappears after reboots
 * or Google Play auto-updates.
 */
object CompanionDeviceHelper {

    private val qingpingNamePattern: Pattern = Pattern.compile("Qingping.*")

    fun isSupported(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            return false
        }
        return getManager(context) != null
    }

    fun getManager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    fun buildAssociationRequest(): AssociationRequest {
        val builder = AssociationRequest.Builder()
        val serviceUuids = listOf(
            QingpingBleParser.QINGPING_SERVICE_UUID_STRING,
            QingpingBleParser.BTHOME_V2_SERVICE_UUID_STRING,
            AqlCollectorService.XIAOMI_MIBEACON_SERVICE_UUID_STRING
        )
        for (uuidString in serviceUuids) {
            val scanFilter = ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(uuidString), ByteArray(0))
                .build()
            builder.addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(scanFilter)
                    .build()
            )
        }
        builder.addDeviceFilter(
            BluetoothLeDeviceFilter.Builder()
                .setNamePattern(qingpingNamePattern)
                .build()
        )
        return builder.build()
    }

    fun getAssociations(context: Context): List<AssociationInfo> {
        if (!isSupported(context)) return emptyList()
        val cdm = getManager(context) ?: return emptyList()
        return runCatching { cdm.myAssociations }.getOrDefault(emptyList())
    }

    fun startObservingPresence(context: Context, association: AssociationInfo): Boolean {
        val cdm = getManager(context) ?: return false
        if (Build.VERSION.SDK_INT >= 36 && startObservingPresenceApi36(cdm, association.id)) {
            return true
        }
        val mac = association.deviceMacAddress?.toString() ?: return false
        return try {
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(mac)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stopObservingPresence(context: Context, association: AssociationInfo) {
        val cdm = getManager(context) ?: return
        if (Build.VERSION.SDK_INT >= 36) {
            stopObservingPresenceApi36(cdm, association.id)
        }
        val mac = association.deviceMacAddress?.toString() ?: return
        try {
            @Suppress("DEPRECATION")
            cdm.stopObservingDevicePresence(mac)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("NewApi")
    private fun startObservingPresenceApi36(
        cdm: CompanionDeviceManager,
        associationId: Int
    ): Boolean = try {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        cdm.startObservingDevicePresence(request)
        true
    } catch (_: Exception) {
        false
    }

    @SuppressLint("NewApi")
    private fun stopObservingPresenceApi36(
        cdm: CompanionDeviceManager,
        associationId: Int
    ) {
        try {
            val request = ObservingDevicePresenceRequest.Builder()
                .setAssociationId(associationId)
                .build()
            cdm.stopObservingDevicePresence(request)
        } catch (_: Exception) {
        }
    }

    fun ensureObservingAll(context: Context): Int {
        var count = 0
        for (association in getAssociations(context)) {
            if (startObservingPresence(context, association)) {
                count++
            }
        }
        return count
    }

    fun disassociateAll(context: Context) {
        val cdm = getManager(context) ?: return
        for (association in getAssociations(context)) {
            stopObservingPresence(context, association)
            try {
                cdm.disassociate(association.id)
            } catch (_: Exception) {
            }
        }
    }

    fun formatAssociationLabel(displayName: CharSequence?, macAddress: String?): String? {
        val name = displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val mac = macAddress?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name != null && mac != null && name.equals(mac, ignoreCase = true) -> mac
            name != null && mac != null -> "$name ($mac)"
            name != null -> name
            mac != null -> mac
            else -> null
        }
    }

    fun formatAssociationSummary(associations: List<AssociationInfo>): String? {
        val labels = associations.mapNotNull { assoc ->
            formatAssociationLabel(assoc.displayName, assoc.deviceMacAddress?.toString())
        }
        return if (labels.isEmpty()) null else labels.joinToString(", ")
    }
}
