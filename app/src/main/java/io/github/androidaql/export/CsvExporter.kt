package io.github.androidaql.export

import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.KnownPlace
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates RFC 4180 CSV exports conforming to `docs/csv_export_schema.md` (27 columns).
 * Self-contained so external PDF/advocacy report generators can reconstruct trip slices,
 * air quality stats, and GPS confidence bands directly from a single CSV file.
 */
object CsvExporter {

    val HEADER_COLUMNS = listOf(
        "timestamp_iso8601",
        "timestamp_epoch_sec",
        "resolution_sec",
        "sample_count",
        "device_mac",
        "device_nickname",
        "context_state",
        "known_place_label",
        "excursion_id",
        "excursion_title",
        "slice_id",
        "slice_category",
        "slice_label",
        "slice_notes",
        "co2_ppm_mean",
        "co2_ppm_min",
        "co2_ppm_max",
        "pm25_ugm3_mean",
        "pm25_ugm3_max",
        "pm10_ugm3_mean",
        "pm10_ugm3_max",
        "temp_c_mean",
        "humidity_pct_mean",
        "battery_pct",
        "rssi_dbm",
        "speed_mps",
        "gps_accuracy_m"
    )

    fun exportExcursionCsv(
        device: MonitorDevice,
        excursion: ExcursionRecord,
        slices: List<SessionSliceRecord>,
        slots: List<AqlRingBufferStore.RingSlotRecord>,
        knownPlacesById: Map<Int, KnownPlace> = emptyMap(),
        resolutionSec: Int = 15,
        onlySelectedForExport: Boolean = true,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val sb = StringBuilder()
        sb.append(HEADER_COLUMNS.joinToString(",")).append('\n')

        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zoneId)
        val activeSlices = if (onlySelectedForExport) {
            slices.filter { it.includeInExport }
        } else {
            slices
        }

        for (slot in slots) {
            val matchingSlice = activeSlices.firstOrNull {
                slot.bucketEpochSec >= it.startEpochSec && slot.bucketEpochSec <= it.endEpochSec
            }
            if (onlySelectedForExport && slices.isNotEmpty() && matchingSlice == null) {
                continue
            }

            val isoTime = formatter.format(Instant.ofEpochSecond(slot.bucketEpochSec))
            val isStationary = slot.knownPlaceId > 0
            val contextState = if (isStationary) "STATIONARY_KNOWN_AP" else "FIELD_EXCURSION"
            val placeLabel = if (isStationary) (knownPlacesById[slot.knownPlaceId]?.label ?: "Place #${slot.knownPlaceId}") else ""

            val batteryStr = if (slot.batteryPctLast in 0..100) slot.batteryPctLast.toString() else ""
            val rssiStr = if (slot.rssiDbmLast > -128) slot.rssiDbmLast.toString() else ""
            val speedStr = slot.speedMps?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val accStr = slot.gpsAccuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: ""

            val row = listOf(
                escapeCsv(isoTime),
                slot.bucketEpochSec.toString(),
                resolutionSec.toString(),
                slot.sampleCount.toString(),
                escapeCsv(device.macAddress),
                escapeCsv(device.nickname),
                contextState,
                escapeCsv(placeLabel),
                excursion.id.toString(),
                escapeCsv(excursion.title),
                matchingSlice?.id?.toString() ?: "",
                matchingSlice?.category?.name ?: "",
                escapeCsv(matchingSlice?.label ?: ""),
                escapeCsv(matchingSlice?.notes ?: ""),
                slot.co2PpmMean.toString(),
                slot.co2PpmMin.toString(),
                slot.co2PpmMax.toString(),
                String.format(Locale.US, "%.1f", slot.pm25Mean),
                slot.pm25Max.toString(),
                String.format(Locale.US, "%.1f", slot.pm10Mean),
                slot.pm10Max.toString(),
                String.format(Locale.US, "%.1f", slot.tempCMean),
                String.format(Locale.US, "%.1f", slot.humidityPctMean),
                batteryStr,
                rssiStr,
                speedStr,
                accStr
            )
            sb.append(row.joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    fun escapeCsv(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
