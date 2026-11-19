package io.github.androidaql.export

import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.MetadataDatabase
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Parses and replays/imports 26-column AirSlice CSVs conforming to `docs/csv_export_schema.md`.
 *
 * Used both for bundled real-world demo/screenshot traces (`assets/demo/`) and for testers
 * loading out-of-band exported `.csv` files in Demo Data Mode without hardware.
 */
object CsvReplayImporter {

    data class ParsedCsvRow(
        val slot: AqlRingBufferStore.RingSlotRecord,
        val deviceMac: String,
        val deviceNickname: String,
        val contextState: String,
        val knownPlaceLabel: String,
        val excursionId: Long?,
        val excursionTitle: String,
        val sliceId: Long?,
        val sliceLabel: String,
        val sliceNotes: String
    )

    data class ParsedCsvReplay(
        val rows: List<ParsedCsvRow>
    ) {
        val deviceMac: String
            get() = rows.firstOrNull { it.deviceMac.isNotBlank() }?.deviceMac ?: "58:2D:34:12:AB:CD"

        val deviceNickname: String
            get() = rows.firstOrNull { it.deviceNickname.isNotBlank() }?.deviceNickname ?: "Qingping Air Monitor Lite"

        val excursionTitle: String
            get() = rows.firstOrNull { it.excursionTitle.isNotBlank() }?.excursionTitle ?: "Imported CSV Trip"

        val isStationaryOnly: Boolean
            get() = rows.isNotEmpty() && rows.all { it.contextState == "STATIONARY_KNOWN_AP" && it.excursionId == null }

        fun timeShifted(targetEndEpochSec: Long, stepSec: Long = 15L): ParsedCsvReplay {
            if (rows.isEmpty()) return this
            val alignedEnd = (targetEndEpochSec / stepSec) * stepSec
            val maxBucket = rows.maxOf { it.slot.bucketEpochSec }
            val deltaSec = alignedEnd - maxBucket
            if (deltaSec == 0L) return this
            return ParsedCsvReplay(
                rows.map { r ->
                    r.copy(slot = r.slot.copy(bucketEpochSec = r.slot.bucketEpochSec + deltaSec))
                }
            )
        }
    }

    data class SliceSpan(
        val startEpochSec: Long,
        val endEpochSec: Long,
        val label: String,
        val notes: String
    )

    data class ImportResult(
        val excursionId: Long?,
        val knownPlaceIds: Map<String, Int>,
        val importedSlotCount: Int,
        val lastRow: ParsedCsvRow?
    )

    fun parseCsv(input: InputStream): ParsedCsvReplay {
        val text = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        return parseCsvText(text)
    }

    fun parseCsvText(csvText: String): ParsedCsvReplay {
        val cleaned = csvText.removePrefix("\uFEFF")
        val records = parseRfc4180Records(cleaned)
        if (records.isEmpty()) return ParsedCsvReplay(emptyList())

        val header = records.first().map { it.trim() }
        val colIndex = header.withIndex().associate { (idx, name) -> name to idx }
        require(colIndex.containsKey("timestamp_epoch_sec") && colIndex.containsKey("co2_ppm_mean")) {
            "CSV is missing required AirSlice columns (timestamp_epoch_sec, co2_ppm_mean)."
        }

        fun col(fields: List<String>, name: String): String {
            val idx = colIndex[name] ?: return ""
            return if (idx in fields.indices) fields[idx].trim() else ""
        }

        val parsed = mutableListOf<ParsedCsvRow>()
        for (fields in records.drop(1)) {
            if (fields.all { it.isBlank() }) continue
            val epochSec = col(fields, "timestamp_epoch_sec").toLongOrNull() ?: continue
            val stepSec = (col(fields, "resolution_sec").toLongOrNull() ?: 15L).coerceAtLeast(1L)
            val alignedBucket = (epochSec / stepSec) * stepSec

            val sampleCount = (col(fields, "sample_count").toIntOrNull() ?: 1).coerceIn(1, 65535)
            val co2Mean = (col(fields, "co2_ppm_mean").toIntOrNull() ?: 0).coerceIn(0, 65535)
            val co2Min = (col(fields, "co2_ppm_min").toIntOrNull() ?: co2Mean).coerceIn(0, 65535)
            val co2Max = (col(fields, "co2_ppm_max").toIntOrNull() ?: co2Mean).coerceIn(0, 65535)

            val pm25MeanF = col(fields, "pm25_ugm3_mean").toFloatOrNull() ?: 0f
            val pm25MeanX10 = (pm25MeanF * 10.0f).roundToInt().coerceIn(0, 65535)
            val pm25Max = (col(fields, "pm25_ugm3_max").toIntOrNull() ?: pm25MeanF.roundToInt()).coerceIn(0, 65535)

            val pm10MeanF = col(fields, "pm10_ugm3_mean").toFloatOrNull() ?: pm25MeanF
            val pm10MeanX10 = (pm10MeanF * 10.0f).roundToInt().coerceIn(0, 65535)
            val pm10Max = (col(fields, "pm10_ugm3_max").toIntOrNull() ?: pm10MeanF.roundToInt()).coerceIn(0, 65535)

            val tempC = col(fields, "temp_c_mean").toFloatOrNull() ?: 22.0f
            val tempX10 = (tempC * 10.0f).roundToInt().coerceIn(-32768, 32767)
            val humPct = col(fields, "humidity_pct_mean").toFloatOrNull() ?: 45.0f
            val humX10 = (humPct * 10.0f).roundToInt().coerceIn(0, 1000)

            val batteryPct = (col(fields, "battery_pct").toIntOrNull() ?: 255).coerceIn(0, 255)
            val rssiDbm = (col(fields, "rssi_dbm").toIntOrNull() ?: -128).coerceIn(-128, 127)

            val speedMps = col(fields, "speed_mps").toFloatOrNull()
            val speedX100 = speedMps?.let { (it * 100.0f).roundToInt().coerceIn(0, 65534) }
                ?: AqlRingBufferStore.SENTINEL_UINT16

            val gpsAccM = col(fields, "gps_accuracy_m").toFloatOrNull()
            val gpsAccX10 = gpsAccM?.let { (it * 10.0f).roundToInt().coerceIn(0, 65534) }
                ?: AqlRingBufferStore.SENTINEL_UINT16

            val contextState = col(fields, "context_state").ifEmpty {
                if (col(fields, "known_place_label").isNotEmpty()) "STATIONARY_KNOWN_AP" else "FIELD_EXCURSION"
            }
            val knownPlaceLabel = col(fields, "known_place_label")
            val excursionId = col(fields, "excursion_id").toLongOrNull()
            val excursionTitle = col(fields, "excursion_title")
            val sliceId = col(fields, "slice_id").toLongOrNull()
            val sliceLabel = col(fields, "slice_label")
            val sliceNotes = col(fields, "slice_notes")

            parsed.add(
                ParsedCsvRow(
                    slot = AqlRingBufferStore.RingSlotRecord(
                        bucketEpochSec = alignedBucket,
                        sampleCount = sampleCount,
                        knownPlaceId = 0, // resolved when materializing into MetadataDatabase
                        co2PpmMean = co2Mean,
                        co2PpmMin = co2Min,
                        co2PpmMax = co2Max,
                        pm25MeanX10 = pm25MeanX10,
                        pm25Max = pm25Max,
                        pm10MeanX10 = pm10MeanX10,
                        pm10Max = pm10Max,
                        tempCMeanX10 = tempX10,
                        humidityMeanX10 = humX10,
                        batteryPctLast = batteryPct,
                        rssiDbmLast = rssiDbm,
                        speedMpsX100 = speedX100,
                        gpsAccuracyMX10 = gpsAccX10
                    ),
                    deviceMac = col(fields, "device_mac"),
                    deviceNickname = col(fields, "device_nickname"),
                    contextState = contextState,
                    knownPlaceLabel = knownPlaceLabel,
                    excursionId = excursionId,
                    excursionTitle = excursionTitle,
                    sliceId = sliceId,
                    sliceLabel = sliceLabel,
                    sliceNotes = sliceNotes
                )
            )
        }

        parsed.sortBy { it.slot.bucketEpochSec }
        return ParsedCsvReplay(parsed)
    }

    /**
     * Extracts contiguous [SliceSpan]s from chronologically sorted [rows] by grouping consecutive
     * rows that share the same `(sliceId, sliceLabel, sliceNotes)`.
     */
    fun extractSliceSpans(rows: List<ParsedCsvRow>): List<SliceSpan> {
        if (rows.isEmpty()) return emptyList()
        val spans = mutableListOf<SliceSpan>()
        var currentKey: Triple<Long?, String, String>? = null
        var spanStart = rows.first().slot.bucketEpochSec
        var currentLabel = ""
        var currentNotes = ""

        for (row in rows) {
            val key = Triple(row.sliceId, row.sliceLabel, row.sliceNotes)
            if (currentKey == null) {
                currentKey = key
                spanStart = row.slot.bucketEpochSec
                currentLabel = row.sliceLabel.ifBlank { "Segment 1" }
                currentNotes = row.sliceNotes
            } else if (key != currentKey) {
                val boundary = row.slot.bucketEpochSec
                spans.add(SliceSpan(spanStart, boundary, currentLabel, currentNotes))
                currentKey = key
                spanStart = boundary
                currentLabel = row.sliceLabel.ifBlank { "Segment ${spans.size + 1}" }
                currentNotes = row.sliceNotes
            }
        }
        val finalEnd = rows.last().slot.bucketEpochSec
        spans.add(SliceSpan(spanStart, finalEnd.coerceAtLeast(spanStart), currentLabel, currentNotes))
        return spans
    }

    /**
     * Time-shifts [replay] so its final slot lands at [targetEndEpochSec], writes all slots into
     * [ringStore], and—unless [createExcursion] is false (stationary-only mode)—creates the
     * [ExcursionRecord] and contiguous [SessionSliceRecord]s in [metadataDb].
     */
    fun importIntoStore(
        replay: ParsedCsvReplay,
        metadataDb: MetadataDatabase,
        ringStore: AqlRingBufferStore,
        targetEndEpochSec: Long = (System.currentTimeMillis() / 1000L / 15L) * 15L,
        createExcursion: Boolean = !replay.isStationaryOnly,
        defaultHomeSsid: String = "ChicagoHome-5G",
        titleOverride: String? = null
    ): ImportResult {
        if (replay.rows.isEmpty()) {
            return ImportResult(null, emptyMap(), 0, null)
        }
        val shifted = replay.timeShifted(targetEndEpochSec)
        val dev = metadataDb.upsertMonitorDevice(
            macAddress = shifted.deviceMac,
            modelCode = "CGDN1 (0x0E)",
            nickname = shifted.deviceNickname
        )

        val placeIdsByLabel = mutableMapOf<String, Int>()
        metadataDb.getKnownPlaces().forEach { placeIdsByLabel[it.label] = it.id }

        for (row in shifted.rows) {
            val isStationary = row.contextState == "STATIONARY_KNOWN_AP" || row.knownPlaceLabel.isNotBlank()
            val placeId = if (isStationary) {
                val label = row.knownPlaceLabel.ifBlank { "Home" }
                placeIdsByLabel.getOrPut(label) {
                    val ssid = if (label.equals("Home", ignoreCase = true)) defaultHomeSsid else "$label-WiFi"
                    metadataDb.createKnownPlaceWithWifi(label, ssid, "AA:BB:CC:11:22:33").id
                }
            } else {
                0
            }
            ringStore.writeSlot(row.slot.copy(knownPlaceId = placeId))
        }

        var createdExcursionId: Long? = null
        if (createExcursion) {
            val startSec = shifted.rows.first().slot.bucketEpochSec
            val endSec = shifted.rows.last().slot.bucketEpochSec
            val title = titleOverride?.takeIf { it.isNotBlank() } ?: shifted.excursionTitle
            val exc = metadataDb.startExcursion(dev.id, startSec, title, isManual = true)
            metadataDb.updateOngoingExcursionEnd(exc.id, endSec, closeExcursion = true)

            val spans = extractSliceSpans(shifted.rows)
            if (spans.isNotEmpty()) {
                for (i in 0 until spans.size - 1) {
                    val currentSlices = metadataDb.getSlicesForExcursion(exc.id)
                    val tailSlice = currentSlices.lastOrNull() ?: break
                    metadataDb.splitSliceAt(tailSlice.id, spans[i].endEpochSec)
                }
                val finalSlices = metadataDb.getSlicesForExcursion(exc.id)
                for (i in finalSlices.indices) {
                    val span = spans.getOrNull(i) ?: continue
                    metadataDb.updateSliceMetadata(
                        sliceId = finalSlices[i].id,
                        label = span.label,
                        notes = span.notes,
                        includeInExport = true
                    )
                }
            }
            createdExcursionId = exc.id
        }

        return ImportResult(
            excursionId = createdExcursionId,
            knownPlaceIds = placeIdsByLabel,
            importedSlotCount = shifted.rows.size,
            lastRow = shifted.rows.lastOrNull()
        )
    }

    internal fun parseRfc4180Records(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < n && text[i + 1] == '\n') i++
                        currentRow.add(field.toString())
                        field.setLength(0)
                        records.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        records.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            records.add(currentRow)
        }
        return records
    }
}
