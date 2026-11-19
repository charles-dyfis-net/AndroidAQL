package io.github.androidaql.export

import io.github.androidaql.export.XlsxWorkbook.XlsxCell
import io.github.androidaql.export.XlsxWorkbook.XlsxSheet
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.KnownPlace
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * Generates self-contained 4-sheet OOXML `.xlsx` exports (`Trip`, `Segments`, `Notes`, `Samples`)
 * conforming to `docs/xlsx_export_schema.md`.
 */
object XlsxExporter {

    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    const val SHEET_TRIP = "Trip"
    const val SHEET_SEGMENTS = "Segments"
    const val SHEET_NOTES = "Notes"
    const val SHEET_SAMPLES = "Samples"

    val TRIP_HEADERS = listOf("field", "value")

    val SEGMENTS_HEADERS = listOf(
        "segment_index",
        "slice_id",
        "start_iso8601",
        "start_epoch_sec",
        "end_iso8601",
        "end_epoch_sec",
        "duration_sec",
        "label",
        "notes",
        "include_in_export",
        "sample_count",
        "co2_ppm_mean",
        "co2_ppm_max",
        "pm25_ugm3_mean"
    )

    val NOTES_HEADERS = listOf(
        "note_id",
        "timestamp_iso8601",
        "timestamp_epoch_sec",
        "text",
        "created_epoch_sec",
        "segment_index"
    )

    val SAMPLES_HEADERS = listOf(
        "timestamp_iso8601",
        "timestamp_epoch_sec",
        "resolution_sec",
        "sample_count",
        "context_state",
        "known_place_label",
        "slice_id",
        "slice_label",
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

    fun exportExcursionXlsx(
        device: MonitorDevice,
        excursion: ExcursionRecord,
        slices: List<SessionSliceRecord>,
        slots: List<AqlRingBufferStore.RingSlotRecord>,
        notes: List<TimelineNoteRecord> = emptyList(),
        knownPlacesById: Map<Int, KnownPlace> = emptyMap(),
        resolutionSec: Int = 15,
        onlySelectedForExport: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ByteArray {
        val sortedSlices = (if (onlySelectedForExport) slices.filter { it.includeInExport } else slices)
            .sortedBy { it.startEpochSec }
        val sortedSlots = slots.sortedBy { it.bucketEpochSec }
        val scopeStart = excursion.startEpochSec
        val scopeEnd = excursion.endEpochSec ?: sortedSlots.maxOfOrNull { it.bucketEpochSec }

        return buildWorkbookBytes(
            exportScope = "EXCURSION",
            scopeStartEpochSec = scopeStart,
            scopeEndEpochSec = scopeEnd,
            device = device,
            excursion = excursion,
            activeSlices = sortedSlices,
            filterSlotsToSlices = onlySelectedForExport && slices.isNotEmpty(),
            slots = sortedSlots,
            notes = notes.sortedBy { it.timestampEpochSec },
            knownPlacesById = knownPlacesById,
            resolutionSec = resolutionSec,
            zoneId = zoneId
        )
    }

    fun exportSliceXlsx(
        device: MonitorDevice,
        excursion: ExcursionRecord,
        slice: SessionSliceRecord,
        slots: List<AqlRingBufferStore.RingSlotRecord>,
        notes: List<TimelineNoteRecord> = emptyList(),
        knownPlacesById: Map<Int, KnownPlace> = emptyMap(),
        resolutionSec: Int = 15,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ByteArray {
        val sliceSlots = slots
            .filter { it.bucketEpochSec in slice.startEpochSec..slice.endEpochSec }
            .sortedBy { it.bucketEpochSec }
        val sliceNotes = notes
            .filter { it.timestampEpochSec in slice.startEpochSec..slice.endEpochSec }
            .sortedBy { it.timestampEpochSec }

        return buildWorkbookBytes(
            exportScope = "SLICE",
            scopeStartEpochSec = slice.startEpochSec,
            scopeEndEpochSec = slice.endEpochSec,
            device = device,
            excursion = excursion,
            activeSlices = listOf(slice),
            filterSlotsToSlices = false,
            slots = sliceSlots,
            notes = sliceNotes,
            knownPlacesById = knownPlacesById,
            resolutionSec = resolutionSec,
            zoneId = zoneId
        )
    }

    private fun buildWorkbookBytes(
        exportScope: String,
        scopeStartEpochSec: Long,
        scopeEndEpochSec: Long?,
        device: MonitorDevice,
        excursion: ExcursionRecord,
        activeSlices: List<SessionSliceRecord>,
        filterSlotsToSlices: Boolean,
        slots: List<AqlRingBufferStore.RingSlotRecord>,
        notes: List<TimelineNoteRecord>,
        knownPlacesById: Map<Int, KnownPlace>,
        resolutionSec: Int,
        zoneId: ZoneId
    ): ByteArray {
        val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zoneId)
        val fmtIso = { ts: Long -> isoFormatter.format(Instant.ofEpochSecond(ts)) }

        // 1. Trip sheet
        val tripRows = mutableListOf<List<XlsxCell>>(
            TRIP_HEADERS.map { XlsxCell.Str(it) },
            listOf(XlsxCell.Str("schema_version"), XlsxCell.IntVal(SCHEMA_VERSION.toLong())),
            listOf(XlsxCell.Str("export_scope"), XlsxCell.Str(exportScope)),
            listOf(XlsxCell.Str("excursion_id"), XlsxCell.IntVal(excursion.id)),
            listOf(XlsxCell.Str("excursion_title"), XlsxCell.Str(excursion.title)),
            listOf(XlsxCell.Str("excursion_notes"), XlsxCell.Str(excursion.notes)),
            listOf(XlsxCell.Str("start_iso8601"), XlsxCell.Str(fmtIso(scopeStartEpochSec))),
            listOf(XlsxCell.Str("start_epoch_sec"), XlsxCell.IntVal(scopeStartEpochSec)),
            listOf(
                XlsxCell.Str("end_iso8601"),
                scopeEndEpochSec?.let { XlsxCell.Str(fmtIso(it)) } ?: XlsxCell.Blank
            ),
            listOf(
                XlsxCell.Str("end_epoch_sec"),
                scopeEndEpochSec?.let { XlsxCell.IntVal(it) } ?: XlsxCell.Blank
            ),
            listOf(XlsxCell.Str("is_pinned"), XlsxCell.BoolVal(excursion.isPinned)),
            listOf(XlsxCell.Str("is_manual"), XlsxCell.BoolVal(excursion.isManual)),
            listOf(XlsxCell.Str("device_mac"), XlsxCell.Str(device.macAddress)),
            listOf(XlsxCell.Str("device_nickname"), XlsxCell.Str(device.nickname)),
            listOf(XlsxCell.Str("resolution_sec"), XlsxCell.IntVal(resolutionSec.toLong()))
        )

        // Assign slots cleanly to contiguous slices using [start, end) for non-last and [start, end] for final slice
        fun sliceForTimestamp(ts: Long): SessionSliceRecord? {
            if (activeSlices.isEmpty()) return null
            return activeSlices.withIndex().firstOrNull { (idx, slice) ->
                if (idx < activeSlices.lastIndex) {
                    ts >= slice.startEpochSec && ts < slice.endEpochSec
                } else {
                    ts >= slice.startEpochSec && ts <= slice.endEpochSec
                }
            }?.value
        }

        val slotsBySliceId = LinkedHashMap<Long, MutableList<AqlRingBufferStore.RingSlotRecord>>()
        val sampleRows = mutableListOf<List<XlsxCell>>(SAMPLES_HEADERS.map { XlsxCell.Str(it) })

        for (slot in slots) {
            val matchingSlice = sliceForTimestamp(slot.bucketEpochSec)
            if (filterSlotsToSlices && matchingSlice == null) {
                continue
            }
            if (matchingSlice != null) {
                slotsBySliceId.getOrPut(matchingSlice.id) { mutableListOf() }.add(slot)
            }

            val isStationary = slot.knownPlaceId > 0
            val contextState = if (isStationary) "STATIONARY_KNOWN_AP" else "FIELD_EXCURSION"
            val placeLabel = if (isStationary) {
                knownPlacesById[slot.knownPlaceId]?.label ?: "Place #${slot.knownPlaceId}"
            } else {
                ""
            }

            sampleRows.add(
                listOf(
                    XlsxCell.Str(fmtIso(slot.bucketEpochSec)),
                    XlsxCell.IntVal(slot.bucketEpochSec),
                    XlsxCell.IntVal(resolutionSec.toLong()),
                    XlsxCell.IntVal(slot.sampleCount.toLong()),
                    XlsxCell.Str(contextState),
                    if (placeLabel.isEmpty()) XlsxCell.Blank else XlsxCell.Str(placeLabel),
                    matchingSlice?.let { XlsxCell.IntVal(it.id) } ?: XlsxCell.Blank,
                    matchingSlice?.let { XlsxCell.Str(it.label) } ?: XlsxCell.Blank,
                    XlsxCell.IntVal(slot.co2PpmMean.toLong()),
                    XlsxCell.IntVal(slot.co2PpmMin.toLong()),
                    XlsxCell.IntVal(slot.co2PpmMax.toLong()),
                    XlsxCell.FloatVal(slot.pm25Mean.toDouble(), 1),
                    XlsxCell.IntVal(slot.pm25Max.toLong()),
                    XlsxCell.FloatVal(slot.pm10Mean.toDouble(), 1),
                    XlsxCell.IntVal(slot.pm10Max.toLong()),
                    XlsxCell.FloatVal(slot.tempCMean.toDouble(), 1),
                    XlsxCell.FloatVal(slot.humidityPctMean.toDouble(), 1),
                    if (slot.batteryPctLast in 0..100) XlsxCell.IntVal(slot.batteryPctLast.toLong()) else XlsxCell.Blank,
                    if (slot.rssiDbmLast > -128) XlsxCell.IntVal(slot.rssiDbmLast.toLong()) else XlsxCell.Blank,
                    slot.speedMps?.let { XlsxCell.FloatVal(it.toDouble(), 2) } ?: XlsxCell.Blank,
                    slot.gpsAccuracyM?.let { XlsxCell.FloatVal(it.toDouble(), 1) } ?: XlsxCell.Blank
                )
            )
        }

        // 2. Segments sheet
        val segmentRows = mutableListOf<List<XlsxCell>>(SEGMENTS_HEADERS.map { XlsxCell.Str(it) })
        val segmentIndexBySliceId = LinkedHashMap<Long, Int>()
        for ((idx, slice) in activeSlices.withIndex()) {
            val segIndex = idx + 1
            segmentIndexBySliceId[slice.id] = segIndex
            val segSlots = slotsBySliceId[slice.id].orEmpty()
            val sampleCount = segSlots.size.toLong()
            val co2MeanCell: XlsxCell = if (segSlots.isEmpty()) {
                XlsxCell.Blank
            } else {
                XlsxCell.IntVal((segSlots.sumOf { it.co2PpmMean.toDouble() } / segSlots.size).roundToLong())
            }
            val co2MaxCell: XlsxCell = if (segSlots.isEmpty()) {
                XlsxCell.Blank
            } else {
                XlsxCell.IntVal(segSlots.maxOf { it.co2PpmMax.toLong() })
            }
            val pm25MeanCell: XlsxCell = if (segSlots.isEmpty()) {
                XlsxCell.Blank
            } else {
                XlsxCell.FloatVal(segSlots.sumOf { it.pm25Mean.toDouble() } / segSlots.size, 1)
            }
            segmentRows.add(
                listOf(
                    XlsxCell.IntVal(segIndex.toLong()),
                    XlsxCell.IntVal(slice.id),
                    XlsxCell.Str(fmtIso(slice.startEpochSec)),
                    XlsxCell.IntVal(slice.startEpochSec),
                    XlsxCell.Str(fmtIso(slice.endEpochSec)),
                    XlsxCell.IntVal(slice.endEpochSec),
                    XlsxCell.IntVal((slice.endEpochSec - slice.startEpochSec).coerceAtLeast(0L)),
                    XlsxCell.Str(slice.label),
                    XlsxCell.Str(slice.notes),
                    XlsxCell.BoolVal(slice.includeInExport),
                    XlsxCell.IntVal(sampleCount),
                    co2MeanCell,
                    co2MaxCell,
                    pm25MeanCell
                )
            )
        }

        // 3. Notes sheet
        val notesBySliceId = MetadataDatabase.assignNotesToSlices(activeSlices, notes)
        val noteToSegmentIdx = HashMap<Long, Int>()
        for ((sliceId, sliceNotes) in notesBySliceId) {
            val segIdx = segmentIndexBySliceId[sliceId] ?: continue
            for (n in sliceNotes) {
                noteToSegmentIdx[n.id] = segIdx
            }
        }
        val noteRows = mutableListOf<List<XlsxCell>>(NOTES_HEADERS.map { XlsxCell.Str(it) })
        for (note in notes) {
            val segIdx = noteToSegmentIdx[note.id]
            noteRows.add(
                listOf(
                    XlsxCell.IntVal(note.id),
                    XlsxCell.Str(fmtIso(note.timestampEpochSec)),
                    XlsxCell.IntVal(note.timestampEpochSec),
                    XlsxCell.Str(note.text),
                    XlsxCell.IntVal(note.createdEpochSec),
                    segIdx?.let { XlsxCell.IntVal(it.toLong()) } ?: XlsxCell.Blank
                )
            )
        }

        val sheets = listOf(
            XlsxSheet(SHEET_TRIP, tripRows),
            XlsxSheet(SHEET_SEGMENTS, segmentRows),
            XlsxSheet(SHEET_NOTES, noteRows),
            XlsxSheet(SHEET_SAMPLES, sampleRows)
        )
        return XlsxWorkbook.writeToBytes(sheets)
    }
}
