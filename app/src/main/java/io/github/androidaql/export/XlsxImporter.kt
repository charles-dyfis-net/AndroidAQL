package io.github.androidaql.export

import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.ImportedSliceSpec
import io.github.androidaql.storage.MetadataDatabase
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Parses and imports 4-sheet OOXML `.xlsx` workbooks (`Trip`, `Segments`, `Notes`, `Samples`)
 * conforming to `docs/xlsx_export_schema.md`.
 *
 * Supports:
 * - Preserving original timestamps as a permanently pinned excursion (`importPreservingTimestamps`)
 *   without touching the live 14-day ring buffer.
 * - Time-shifted replay into the 14-day ring buffer for bundled/manual Demo Mode testing
 *   (`importTimeShiftedForDemoReplay`).
 */
object XlsxImporter {

    data class ParsedTripMetadata(
        val schemaVersion: Int,
        val exportScope: String,
        val excursionId: Long?,
        val excursionTitle: String,
        val excursionNotes: String,
        val startEpochSec: Long?,
        val endEpochSec: Long?,
        val isPinned: Boolean,
        val isManual: Boolean,
        val deviceMac: String,
        val deviceNickname: String,
        val resolutionSec: Int
    )

    data class ParsedSegment(
        val segmentIndex: Int,
        val startEpochSec: Long,
        val endEpochSec: Long,
        val label: String,
        val notes: String,
        val includeInExport: Boolean
    )

    data class ParsedNote(
        val timestampEpochSec: Long,
        val text: String,
        val createdEpochSec: Long
    )

    data class ParsedSampleRow(
        val slot: AqlRingBufferStore.RingSlotRecord,
        val contextState: String,
        val knownPlaceLabel: String,
        val sliceId: Long?,
        val sliceLabel: String
    )

    data class ParsedXlsxWorkbook(
        val trip: ParsedTripMetadata,
        val segments: List<ParsedSegment>,
        val notes: List<ParsedNote>,
        val rows: List<ParsedSampleRow>
    ) {
        val deviceMac: String
            get() = trip.deviceMac.ifBlank { "58:2D:34:12:AB:CD" }

        val deviceNickname: String
            get() = trip.deviceNickname.ifBlank { "Qingping Air Monitor Lite" }

        val excursionTitle: String
            get() = trip.excursionTitle.ifBlank { "Imported Trip" }

        val isStationaryOnly: Boolean
            get() = rows.isNotEmpty() &&
                segments.isEmpty() &&
                trip.excursionId == null &&
                rows.all {
                    it.contextState == "STATIONARY_KNOWN_AP" &&
                        it.sliceId == null &&
                        it.sliceLabel.isBlank()
                }

        fun timeShifted(targetEndEpochSec: Long, stepSec: Long = 15L): ParsedXlsxWorkbook {
            if (rows.isEmpty()) return this
            val alignedEnd = (targetEndEpochSec / stepSec) * stepSec
            val maxBucket = rows.maxOf { it.slot.bucketEpochSec }
            val deltaSec = alignedEnd - maxBucket
            if (deltaSec == 0L) return this
            return copy(
                trip = trip.copy(
                    startEpochSec = trip.startEpochSec?.let { it + deltaSec },
                    endEpochSec = trip.endEpochSec?.let { it + deltaSec }
                ),
                segments = segments.map { s ->
                    s.copy(
                        startEpochSec = s.startEpochSec + deltaSec,
                        endEpochSec = s.endEpochSec + deltaSec
                    )
                },
                notes = notes.map { n ->
                    n.copy(
                        timestampEpochSec = n.timestampEpochSec + deltaSec,
                        createdEpochSec = n.createdEpochSec + deltaSec
                    )
                },
                rows = rows.map { r ->
                    r.copy(slot = r.slot.copy(bucketEpochSec = r.slot.bucketEpochSec + deltaSec))
                }
            )
        }
    }

    data class ImportResult(
        val excursionId: Long?,
        val knownPlaceIds: Map<String, Int>,
        val importedSlotCount: Int,
        val importedSegmentCount: Int,
        val importedNoteCount: Int,
        val lastRow: ParsedSampleRow?
    )

    fun parseXlsx(input: InputStream): ParsedXlsxWorkbook {
        val sheets = XlsxWorkbook.read(input)
        val sheetByLower = sheets.entries.associate { (k, v) -> k.lowercase(Locale.US) to v }

        val samplesTable = sheetByLower[XlsxExporter.SHEET_SAMPLES.lowercase(Locale.US)]
            ?: throw IllegalArgumentException("XLSX workbook is missing required '${XlsxExporter.SHEET_SAMPLES}' sheet.")
        if (samplesTable.isEmpty()) {
            return ParsedXlsxWorkbook(
                trip = defaultTripMetadata(),
                segments = emptyList(),
                notes = emptyList(),
                rows = emptyList()
            )
        }

        val tripMetadata = parseTripSheet(sheetByLower[XlsxExporter.SHEET_TRIP.lowercase(Locale.US)])
        val rows = parseSamplesSheet(samplesTable, defaultResolutionSec = tripMetadata.resolutionSec)
        val segments = parseSegmentsSheet(sheetByLower[XlsxExporter.SHEET_SEGMENTS.lowercase(Locale.US)])
        val notes = parseNotesSheet(sheetByLower[XlsxExporter.SHEET_NOTES.lowercase(Locale.US)])

        return ParsedXlsxWorkbook(
            trip = tripMetadata,
            segments = segments,
            notes = notes,
            rows = rows
        )
    }

    fun extractFallbackSegments(rows: List<ParsedSampleRow>): List<ImportedSliceSpec> {
        if (rows.isEmpty()) return emptyList()
        val spans = mutableListOf<ImportedSliceSpec>()
        var currentKey: Pair<Long?, String>? = null
        var spanStart = rows.first().slot.bucketEpochSec
        var currentLabel = ""

        for (row in rows) {
            val key = row.sliceId to row.sliceLabel
            if (currentKey == null) {
                currentKey = key
                spanStart = row.slot.bucketEpochSec
                currentLabel = row.sliceLabel.ifBlank { "Segment 1" }
            } else if (key != currentKey) {
                val boundary = row.slot.bucketEpochSec
                spans.add(
                    ImportedSliceSpec(
                        startEpochSec = spanStart,
                        endEpochSec = boundary,
                        label = currentLabel,
                        notes = "",
                        includeInExport = true
                    )
                )
                currentKey = key
                spanStart = boundary
                currentLabel = row.sliceLabel.ifBlank { "Segment ${spans.size + 1}" }
            }
        }
        val finalEnd = rows.last().slot.bucketEpochSec
        spans.add(
            ImportedSliceSpec(
                startEpochSec = spanStart,
                endEpochSec = finalEnd.coerceAtLeast(spanStart),
                label = currentLabel,
                notes = "",
                includeInExport = true
            )
        )
        return spans
    }

    fun importPreservingTimestamps(
        parsed: ParsedXlsxWorkbook,
        metadataDb: MetadataDatabase,
        defaultHomeSsid: String = "ChicagoHome-5G",
        titleOverride: String? = null
    ): ImportResult {
        if (parsed.rows.isEmpty()) {
            return ImportResult(null, emptyMap(), 0, 0, 0, null)
        }
        val dev = metadataDb.upsertMonitorDevice(
            macAddress = parsed.deviceMac,
            modelCode = "CGDN1 (0x0E)",
            nickname = parsed.deviceNickname
        )
        val placeIdsByLabel = resolveKnownPlacesMap(metadataDb)
        val resolvedSlots = parsed.rows.map { row ->
            val placeId = resolvePlaceIdForRow(row, metadataDb, placeIdsByLabel, defaultHomeSsid)
            row.slot.copy(knownPlaceId = placeId)
        }

        val sliceSpecs = buildImportedSliceSpecs(parsed)
        val startSec = parsed.trip.startEpochSec
            ?: sliceSpecs.firstOrNull()?.startEpochSec
            ?: resolvedSlots.first().bucketEpochSec
        val endSec = (parsed.trip.endEpochSec
            ?: sliceSpecs.lastOrNull()?.endEpochSec
            ?: resolvedSlots.last().bucketEpochSec).coerceAtLeast(startSec)
        val title = titleOverride?.takeIf { it.isNotBlank() } ?: parsed.excursionTitle

        val excursionId = metadataDb.createImportedExcursion(
            deviceId = dev.id,
            startEpochSec = startSec,
            endEpochSec = endSec,
            title = title,
            notes = parsed.trip.excursionNotes,
            isPinned = true,
            slices = sliceSpecs,
            pinnedSlots = resolvedSlots
        )
        val notesCount = restoreNotesDeduplicated(parsed.notes, startSec, endSec, metadataDb)

        return ImportResult(
            excursionId = excursionId,
            knownPlaceIds = placeIdsByLabel,
            importedSlotCount = resolvedSlots.size,
            importedSegmentCount = sliceSpecs.size,
            importedNoteCount = notesCount,
            lastRow = parsed.rows.lastOrNull()
        )
    }

    fun importTimeShiftedForDemoReplay(
        parsed: ParsedXlsxWorkbook,
        metadataDb: MetadataDatabase,
        ringStore: AqlRingBufferStore,
        targetEndEpochSec: Long = (System.currentTimeMillis() / 1000L / 15L) * 15L,
        createExcursion: Boolean = !parsed.isStationaryOnly,
        importNotes: Boolean = true,
        defaultHomeSsid: String = "ChicagoHome-5G",
        titleOverride: String? = null
    ): ImportResult {
        if (parsed.rows.isEmpty()) {
            return ImportResult(null, emptyMap(), 0, 0, 0, null)
        }
        val shifted = parsed.timeShifted(targetEndEpochSec)
        val dev = metadataDb.upsertMonitorDevice(
            macAddress = shifted.deviceMac,
            modelCode = "CGDN1 (0x0E)",
            nickname = shifted.deviceNickname
        )
        val placeIdsByLabel = resolveKnownPlacesMap(metadataDb)

        for (row in shifted.rows) {
            val placeId = resolvePlaceIdForRow(row, metadataDb, placeIdsByLabel, defaultHomeSsid)
            ringStore.writeSlot(row.slot.copy(knownPlaceId = placeId))
        }

        var createdExcursionId: Long? = null
        var segmentCount = 0
        var noteCount = 0

        if (createExcursion) {
            val sliceSpecs = buildImportedSliceSpecs(shifted)
            val startSec = shifted.trip.startEpochSec
                ?: sliceSpecs.firstOrNull()?.startEpochSec
                ?: shifted.rows.first().slot.bucketEpochSec
            val endSec = (shifted.trip.endEpochSec
                ?: sliceSpecs.lastOrNull()?.endEpochSec
                ?: shifted.rows.last().slot.bucketEpochSec).coerceAtLeast(startSec)
            val title = titleOverride?.takeIf { it.isNotBlank() } ?: shifted.excursionTitle

            createdExcursionId = metadataDb.createImportedExcursion(
                deviceId = dev.id,
                startEpochSec = startSec,
                endEpochSec = endSec,
                title = title,
                notes = shifted.trip.excursionNotes,
                isPinned = false,
                slices = sliceSpecs,
                pinnedSlots = null
            )
            segmentCount = sliceSpecs.size
            if (importNotes) {
                noteCount = restoreNotesDeduplicated(shifted.notes, startSec, endSec, metadataDb)
            }
        } else if (importNotes && shifted.notes.isNotEmpty()) {
            val minTs = shifted.rows.first().slot.bucketEpochSec
            val maxTs = shifted.rows.last().slot.bucketEpochSec
            noteCount = restoreNotesDeduplicated(shifted.notes, minTs, maxTs, metadataDb)
        }

        return ImportResult(
            excursionId = createdExcursionId,
            knownPlaceIds = placeIdsByLabel,
            importedSlotCount = shifted.rows.size,
            importedSegmentCount = segmentCount,
            importedNoteCount = noteCount,
            lastRow = shifted.rows.lastOrNull()
        )
    }

    private fun buildImportedSliceSpecs(parsed: ParsedXlsxWorkbook): List<ImportedSliceSpec> {
        if (parsed.segments.isNotEmpty()) {
            return parsed.segments.sortedBy { it.startEpochSec }.map { seg ->
                ImportedSliceSpec(
                    startEpochSec = seg.startEpochSec,
                    endEpochSec = seg.endEpochSec,
                    label = seg.label,
                    notes = seg.notes,
                    includeInExport = seg.includeInExport
                )
            }
        }
        return extractFallbackSegments(parsed.rows)
    }

    private fun resolveKnownPlacesMap(metadataDb: MetadataDatabase): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        metadataDb.getKnownPlaces().forEach { map[it.label] = it.id }
        return map
    }

    private fun resolvePlaceIdForRow(
        row: ParsedSampleRow,
        metadataDb: MetadataDatabase,
        placeIdsByLabel: MutableMap<String, Int>,
        defaultHomeSsid: String
    ): Int {
        val isStationary = row.contextState == "STATIONARY_KNOWN_AP" || row.knownPlaceLabel.isNotBlank()
        if (!isStationary) return 0
        val label = row.knownPlaceLabel.ifBlank { "Home" }
        return placeIdsByLabel.getOrPut(label) {
            val ssid = if (label.equals("Home", ignoreCase = true)) defaultHomeSsid else "$label-WiFi"
            metadataDb.createKnownPlaceWithWifi(label, ssid, "AA:BB:CC:11:22:33").id
        }
    }

    private fun restoreNotesDeduplicated(
        notes: List<ParsedNote>,
        windowStartSec: Long,
        windowEndSec: Long,
        metadataDb: MetadataDatabase
    ): Int {
        if (notes.isEmpty()) return 0
        val minTs = minOf(windowStartSec, notes.minOf { it.timestampEpochSec })
        val maxTs = maxOf(windowEndSec, notes.maxOf { it.timestampEpochSec })
        val existingKeys = metadataDb.getTimelineNotesInRange(minTs, maxTs)
            .mapTo(HashSet()) { it.timestampEpochSec to it.text }

        var added = 0
        for (note in notes) {
            val cleanText = note.text.trim().ifEmpty { MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT }
            val key = note.timestampEpochSec to cleanText
            if (existingKeys.add(key)) {
                metadataDb.addTimelineNote(
                    timestampEpochSec = note.timestampEpochSec,
                    text = cleanText,
                    createdEpochSec = note.createdEpochSec
                )
                added++
            }
        }
        return added
    }

    private fun defaultTripMetadata(): ParsedTripMetadata = ParsedTripMetadata(
        schemaVersion = XlsxExporter.SCHEMA_VERSION,
        exportScope = "EXCURSION",
        excursionId = null,
        excursionTitle = "Imported Trip",
        excursionNotes = "",
        startEpochSec = null,
        endEpochSec = null,
        isPinned = true,
        isManual = true,
        deviceMac = "58:2D:34:12:AB:CD",
        deviceNickname = "Qingping Air Monitor Lite",
        resolutionSec = 15
    )

    private fun parseTripSheet(table: List<List<String>>?): ParsedTripMetadata {
        val defaults = defaultTripMetadata()
        if (table == null || table.isEmpty()) return defaults

        val kv = LinkedHashMap<String, String>()
        val header = table.first().map { it.trim().lowercase(Locale.US) }
        val fieldIdx = header.indexOf("field").let { if (it >= 0) it else 0 }
        val valueIdx = header.indexOf("value").let { if (it >= 0) it else 1 }
        val startRow = if (header.getOrNull(fieldIdx) == "field" && header.getOrNull(valueIdx) == "value") 1 else 0
        for (row in table.drop(startRow)) {
            val key = row.getOrNull(fieldIdx)?.trim()?.lowercase(Locale.US).orEmpty()
            if (key.isEmpty()) continue
            val value = row.getOrNull(valueIdx)?.trim().orEmpty()
            kv[key] = value
        }

        return ParsedTripMetadata(
            schemaVersion = parseFlexInt(kv["schema_version"].orEmpty()) ?: defaults.schemaVersion,
            exportScope = kv["export_scope"]?.ifBlank { defaults.exportScope } ?: defaults.exportScope,
            excursionId = parseFlexLong(kv["excursion_id"].orEmpty()),
            excursionTitle = kv["excursion_title"]?.ifBlank { defaults.excursionTitle } ?: defaults.excursionTitle,
            excursionNotes = kv["excursion_notes"].orEmpty(),
            startEpochSec = parseFlexLong(kv["start_epoch_sec"].orEmpty()),
            endEpochSec = parseFlexLong(kv["end_epoch_sec"].orEmpty()),
            isPinned = parseFlexBool(kv["is_pinned"].orEmpty(), defaults.isPinned),
            isManual = parseFlexBool(kv["is_manual"].orEmpty(), defaults.isManual),
            deviceMac = kv["device_mac"]?.ifBlank { defaults.deviceMac } ?: defaults.deviceMac,
            deviceNickname = kv["device_nickname"]?.ifBlank { defaults.deviceNickname } ?: defaults.deviceNickname,
            resolutionSec = (parseFlexInt(kv["resolution_sec"].orEmpty()) ?: defaults.resolutionSec).coerceAtLeast(1)
        )
    }

    private fun parseSegmentsSheet(table: List<List<String>>?): List<ParsedSegment> {
        if (table == null || table.size <= 1) return emptyList()
        val header = table.first().map { it.trim().lowercase(Locale.US) }
        val colIndex = header.withIndex().associate { (idx, name) -> name to idx }
        fun col(row: List<String>, name: String): String =
            colIndex[name]?.let { row.getOrNull(it)?.trim() }.orEmpty()

        val out = mutableListOf<ParsedSegment>()
        for (row in table.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val startSec = parseFlexLong(col(row, "start_epoch_sec")) ?: continue
            val endSec = parseFlexLong(col(row, "end_epoch_sec")) ?: startSec
            val segIdx = parseFlexInt(col(row, "segment_index")) ?: (out.size + 1)
            val label = col(row, "label").ifBlank { "Segment ${out.size + 1}" }
            val notes = col(row, "notes")
            val include = parseFlexBool(col(row, "include_in_export"), true)
            out.add(
                ParsedSegment(
                    segmentIndex = segIdx,
                    startEpochSec = startSec,
                    endEpochSec = endSec.coerceAtLeast(startSec),
                    label = label,
                    notes = notes,
                    includeInExport = include
                )
            )
        }
        return out.sortedBy { it.startEpochSec }
    }

    private fun parseNotesSheet(table: List<List<String>>?): List<ParsedNote> {
        if (table == null || table.size <= 1) return emptyList()
        val header = table.first().map { it.trim().lowercase(Locale.US) }
        val colIndex = header.withIndex().associate { (idx, name) -> name to idx }
        fun col(row: List<String>, name: String): String =
            colIndex[name]?.let { row.getOrNull(it)?.trim() }.orEmpty()

        val out = mutableListOf<ParsedNote>()
        for (row in table.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val ts = parseFlexLong(col(row, "timestamp_epoch_sec")) ?: continue
            val text = col(row, "text").ifBlank { MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT }
            val created = parseFlexLong(col(row, "created_epoch_sec")) ?: ts
            out.add(
                ParsedNote(
                    timestampEpochSec = ts,
                    text = text,
                    createdEpochSec = created
                )
            )
        }
        return out.sortedBy { it.timestampEpochSec }
    }

    private fun parseSamplesSheet(
        table: List<List<String>>,
        defaultResolutionSec: Int
    ): List<ParsedSampleRow> {
        val header = table.first().map { it.trim().lowercase(Locale.US) }
        val colIndex = header.withIndex().associate { (idx, name) -> name to idx }
        require(colIndex.containsKey("timestamp_epoch_sec") && colIndex.containsKey("co2_ppm_mean")) {
            "Samples sheet is missing required columns (timestamp_epoch_sec, co2_ppm_mean)."
        }

        fun col(row: List<String>, name: String): String =
            colIndex[name]?.let { row.getOrNull(it)?.trim() }.orEmpty()

        val parsed = mutableListOf<ParsedSampleRow>()
        for (row in table.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val epochSec = parseFlexLong(col(row, "timestamp_epoch_sec")) ?: continue
            val stepSec = (parseFlexLong(col(row, "resolution_sec")) ?: defaultResolutionSec.toLong()).coerceAtLeast(1L)
            val alignedBucket = (epochSec / stepSec) * stepSec

            val sampleCount = (parseFlexInt(col(row, "sample_count")) ?: 1).coerceIn(1, 65535)
            val co2Mean = (parseFlexInt(col(row, "co2_ppm_mean")) ?: 0).coerceIn(0, 65535)
            val co2Min = (parseFlexInt(col(row, "co2_ppm_min")) ?: co2Mean).coerceIn(0, 65535)
            val co2Max = (parseFlexInt(col(row, "co2_ppm_max")) ?: co2Mean).coerceIn(0, 65535)

            val pm25MeanF = col(row, "pm25_ugm3_mean").toFloatOrNull() ?: 0f
            val pm25MeanX10 = (pm25MeanF * 10.0f).roundToInt().coerceIn(0, 65535)
            val pm25Max = (parseFlexInt(col(row, "pm25_ugm3_max")) ?: pm25MeanF.roundToInt()).coerceIn(0, 65535)

            val pm10MeanF = col(row, "pm10_ugm3_mean").toFloatOrNull() ?: pm25MeanF
            val pm10MeanX10 = (pm10MeanF * 10.0f).roundToInt().coerceIn(0, 65535)
            val pm10Max = (parseFlexInt(col(row, "pm10_ugm3_max")) ?: pm10MeanF.roundToInt()).coerceIn(0, 65535)

            val tempC = col(row, "temp_c_mean").toFloatOrNull() ?: 22.0f
            val tempX10 = (tempC * 10.0f).roundToInt().coerceIn(-32768, 32767)
            val humPct = col(row, "humidity_pct_mean").toFloatOrNull() ?: 45.0f
            val humX10 = (humPct * 10.0f).roundToInt().coerceIn(0, 1000)

            val batteryPct = (parseFlexInt(col(row, "battery_pct")) ?: 255).coerceIn(0, 255)
            val rssiDbm = (parseFlexInt(col(row, "rssi_dbm")) ?: -128).coerceIn(-128, 127)

            val speedMps = col(row, "speed_mps").toFloatOrNull()
            val speedX100 = speedMps?.let { (it * 100.0f).roundToInt().coerceIn(0, 65534) }
                ?: AqlRingBufferStore.SENTINEL_UINT16

            val gpsAccM = col(row, "gps_accuracy_m").toFloatOrNull()
            val gpsAccX10 = gpsAccM?.let { (it * 10.0f).roundToInt().coerceIn(0, 65534) }
                ?: AqlRingBufferStore.SENTINEL_UINT16

            val knownPlaceLabel = col(row, "known_place_label")
            val contextState = col(row, "context_state").ifEmpty {
                if (knownPlaceLabel.isNotEmpty()) "STATIONARY_KNOWN_AP" else "FIELD_EXCURSION"
            }
            val sliceId = parseFlexLong(col(row, "slice_id"))
            val sliceLabel = col(row, "slice_label")

            parsed.add(
                ParsedSampleRow(
                    slot = AqlRingBufferStore.RingSlotRecord(
                        bucketEpochSec = alignedBucket,
                        sampleCount = sampleCount,
                        knownPlaceId = 0,
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
                    contextState = contextState,
                    knownPlaceLabel = knownPlaceLabel,
                    sliceId = sliceId,
                    sliceLabel = sliceLabel
                )
            )
        }
        parsed.sortBy { it.slot.bucketEpochSec }
        return parsed
    }

    private fun parseFlexLong(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToLong()
    }

    private fun parseFlexInt(raw: String): Int? = parseFlexLong(raw)?.toInt()

    private fun parseFlexBool(raw: String, defaultValue: Boolean): Boolean {
        return when (raw.trim().lowercase(Locale.US)) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> defaultValue
        }
    }
}
