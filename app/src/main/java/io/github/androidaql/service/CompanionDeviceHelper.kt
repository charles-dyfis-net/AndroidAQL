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
import java.util.Locale
import java.util.regex.Pattern

/**
 * Encapsulates Android [CompanionDeviceManager] association and BLE device presence observation
 * so the OS wakes AirSlice when a linked Qingping / BTHome v2 monitor reappears after reboots
 * or Google Play auto-updates, and allows AirSlice to sleep when the linked monitor is absent.
 */
object CompanionDeviceHelper {

    private const val UI_PREFS_NAME = "aql_ui_prefs"
    const val PREF_COMPANION_LINK_SUGGESTION_SHOWN = "companion_link_suggestion_shown"
    const val PREF_COMPANION_LINK_SUGGESTION_DISMISSED = "companion_link_suggestion_dismissed"

    private val qingpingNamePattern: Pattern = Pattern.compile("Qingping.*")
    private val validMacRegex = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    fun normalizeBluetoothMac(address: String?): String? {
        val trimmed = address?.trim()?.uppercase(Locale.US) ?: return null
        if (!validMacRegex.matches(trimmed)) return null
        if (trimmed == "00:00:00:00:00:00" || trimmed == "02:00:00:00:00:00") return null
        return trimmed
    }

    fun shouldUseSingleDeviceRequest(targetBleAddress: String?): Boolean =
        normalizeBluetoothMac(targetBleAddress) != null

    fun formatPresenceObservationMac(rawMacAddress: String?): String? =
        normalizeBluetoothMac(rawMacAddress)

    fun isSupported(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            return false
        }
        return getManager(context) != null
    }

    fun getManager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    fun buildAssociationRequest(targetBleAddress: String? = null): AssociationRequest {
        val builder = AssociationRequest.Builder()
        val normalizedMac = normalizeBluetoothMac(targetBleAddress)
        if (normalizedMac != null) {
            val scanFilter = ScanFilter.Builder()
                .setDeviceAddress(normalizedMac)
                .build()
            builder.addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(scanFilter)
                    .build()
            )
            builder.setSingleDevice(true)
            return builder.build()
        }

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

    fun hasAssociations(context: Context): Boolean = getAssociations(context).isNotEmpty()

    fun isLinkSuggestionSuppressed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_COMPANION_LINK_SUGGESTION_DISMISSED, false) ||
            prefs.getBoolean(PREF_COMPANION_LINK_SUGGESTION_SHOWN, false)
    }

    fun markLinkSuggestionShown(context: Context) {
        context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_COMPANION_LINK_SUGGESTION_SHOWN, true)
            .apply()
    }

    fun markLinkSuggestionDismissed(context: Context) {
        context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_COMPANION_LINK_SUGGESTION_DISMISSED, true)
            .apply()
    }

    fun shouldPostProactiveLinkSuggestion(
        isCompanionSupported: Boolean,
        hasExistingAssociations: Boolean,
        isSuggestionSuppressed: Boolean,
        hasNotificationPermission: Boolean,
        isDemoOrScreenshotMode: Boolean,
        liveBleAddress: String?
    ): Boolean =
        isCompanionSupported &&
            !hasExistingAssociations &&
            !isSuggestionSuppressed &&
            hasNotificationPermission &&
            !isDemoOrScreenshotMode &&
            normalizeBluetoothMac(liveBleAddress) != null

    fun startObservingPresence(
        context: Context,
        association: AssociationInfo,
        onError: ((stage: String, error: Exception) -> Unit)? = null
    ): Boolean {
        val cdm = getManager(context) ?: return false
        if (Build.VERSION.SDK_INT >= 36 && startObservingPresenceApi36(cdm, association.id, onError)) {
            return true
        }
        val mac = formatPresenceObservationMac(association.deviceMacAddress?.toString()) ?: return false
        return try {
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(mac)
            true
        } catch (e: Exception) {
            onError?.invoke("legacy_mac_observe", e)
            false
        }
    }

    fun stopObservingPresence(context: Context, association: AssociationInfo) {
        val cdm = getManager(context) ?: return
        if (Build.VERSION.SDK_INT >= 36) {
            stopObservingPresenceApi36(cdm, association.id)
        }
        val mac = formatPresenceObservationMac(association.deviceMacAddress?.toString()) ?: return
        try {
            @Suppress("DEPRECATION")
            cdm.stopObservingDevicePresence(mac)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("NewApi")
    private fun startObservingPresenceApi36(
        cdm: CompanionDeviceManager,
        associationId: Int,
        onError: ((stage: String, error: Exception) -> Unit)? = null
    ): Boolean = try {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        cdm.startObservingDevicePresence(request)
        true
    } catch (e: Exception) {
        onError?.invoke("api36_observe", e)
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

    fun ensureObservingAll(
        context: Context,
        onError: ((stage: String, error: Exception) -> Unit)? = null
    ): Int {
        var count = 0
        for (association in getAssociations(context)) {
            if (startObservingPresence(context, association, onError)) {
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
