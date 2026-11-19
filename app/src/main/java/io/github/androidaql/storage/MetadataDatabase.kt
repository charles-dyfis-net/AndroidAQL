package io.github.androidaql.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class BleCollectionMode(val displayName: String, val description: String) {
    PERIODIC_GATT_KEEPALIVE(
        "Automatic Offline Setup & Keep-Awake (Default)",
        "Passive 0xFDCD / BTHome v2 scanning, plus automatic one-time offline setup (0x88) and brief GATT time-sync keep-alive when needed."
    ),
    PASSIVE_ONLY(
        "Passive Broadcasts Only (External Hub)",
        "Never connects GATT. Use when running BTHome v2 custom firmware or when another hub already keeps the CGDN1 awake."
    )
}

data class MonitorDevice(
    val id: Long,
    val macAddress: String,
    val modelCode: String,
    val nickname: String,
    val isActivePortable: Boolean
)

data class KnownPlace(
    val id: Int,
    val label: String,
    val networks: List<KnownWifiNetwork> = emptyList()
)

data class KnownWifiNetwork(
    val id: Long,
    val knownPlaceId: Int,
    val ssid: String,
    val bssidListCsv: String
)

data class SegmentPreset(
    val id: Long,
    val label: String,
    val iconEmoji: String,
    val sortOrder: Int
)

data class ExcursionRecord(
    val id: Long,
    val deviceId: Long,
    val startEpochSec: Long,
    val endEpochSec: Long?,
    val isPinned: Boolean,
    val title: String,
    val notes: String,
    val isManual: Boolean = false
)

data class SessionSliceRecord(
    val id: Long,
    val excursionId: Long,
    val startEpochSec: Long,
    val endEpochSec: Long,
    val includeInExport: Boolean,
    val label: String,
    val notes: String
)

data class TimelineNoteRecord(
    val id: Long,
    val timestampEpochSec: Long,
    val text: String,
    val createdEpochSec: Long
)

data class ImportedSliceSpec(
    val startEpochSec: Long,
    val endEpochSec: Long,
    val label: String,
    val notes: String,
    val includeInExport: Boolean = true
)

/**
 * Relational metadata store using Android's SQLiteOpenHelper strictly for low-volume
 * metadata: devices, multi-network Saved Places, customizable Segment Presets, Excursions,
 * shared-divider Session Slices, and Pinned Slice binary blobs.
 */
class MetadataDatabase(context: Context, dbName: String = DB_NAME) :
    SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE monitor_device (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mac_address TEXT NOT NULL UNIQUE,
                model_code TEXT NOT NULL,
                nickname TEXT NOT NULL,
                is_active_portable INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE known_place (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE known_wifi_network (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                known_place_id INTEGER NOT NULL,
                ssid TEXT NOT NULL,
                bssid_list TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(known_place_id) REFERENCES known_place(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE segment_preset (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                icon_emoji TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE excursion (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                is_manual INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE session_slice (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                excursion_id INTEGER NOT NULL,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER NOT NULL,
                include_in_export INTEGER NOT NULL DEFAULT 1,
                label TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(excursion_id) REFERENCES excursion(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE pinned_slice_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                excursion_id INTEGER NOT NULL,
                slice_id INTEGER,
                start_epoch_sec INTEGER NOT NULL,
                end_epoch_sec INTEGER NOT NULL,
                raw_samples_payload BLOB NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE timeline_note (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_epoch_sec INTEGER NOT NULL,
                text TEXT NOT NULL,
                created_epoch_sec INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_timeline_note_ts ON timeline_note(timestamp_epoch_sec)"
        )

        seedDefaultSegmentPresets(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE excursion ADD COLUMN is_manual INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS timeline_note (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_epoch_sec INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    created_epoch_sec INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_timeline_note_ts ON timeline_note(timestamp_epoch_sec)"
            )
        }
    }

    private fun seedDefaultSegmentPresets(db: SQLiteDatabase) {
        val defaults = listOf(
            "Bus" to "🚌",
            "Train / L" to "🚇",
            "Cab / Car" to "🚕",
            "Coffee Shop" to "☕",
            "Restaurant" to "🍽️",
            "Indoor Visit" to "🏢",
            "Outdoor Walk" to "🚶"
        )
        for ((idx, item) in defaults.withIndex()) {
            val cv = ContentValues().apply {
                put("label", item.first)
                put("icon_emoji", item.second)
                put("sort_order", idx)
            }
            db.insert("segment_preset", null, cv)
        }
    }

    fun upsertMonitorDevice(macAddress: String, modelCode: String, nickname: String): MonitorDevice {
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, mac_address, model_code, nickname, is_active_portable FROM monitor_device WHERE mac_address = ?",
            arrayOf(macAddress)
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return MonitorDevice(id, c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0)
            }
        }
        val cv = ContentValues().apply {
            put("mac_address", macAddress)
            put("model_code", modelCode)
            put("nickname", nickname)
            put("is_active_portable", 1)
        }
        val id = db.insert("monitor_device", null, cv)
        return MonitorDevice(id, macAddress, modelCode, nickname, true)
    }

    fun getPrimaryDevice(): MonitorDevice? {
        readableDatabase.rawQuery(
            "SELECT id, mac_address, model_code, nickname, is_active_portable FROM monitor_device ORDER BY id ASC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return MonitorDevice(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0)
            }
        }
        return null
    }

    fun getDeviceById(deviceId: Long): MonitorDevice? {
        readableDatabase.rawQuery(
            "SELECT id, mac_address, model_code, nickname, is_active_portable FROM monitor_device WHERE id = ?",
            arrayOf(deviceId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return MonitorDevice(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0)
            }
        }
        return null
    }

    /**
     * Creates a new Saved Place or links an additional Wi-Fi SSID/BSSID to an existing Saved Place.
     */
    fun createKnownPlaceWithWifi(placeLabel: String, ssid: String, bssid: String? = null): KnownPlace {
        val db = writableDatabase
        val placeCv = ContentValues().apply { put("label", placeLabel) }
        val placeId = db.insert("known_place", null, placeCv).toInt()
        linkWifiToKnownPlace(placeId, ssid, bssid)
        return getKnownPlaces().first { it.id == placeId }
    }

    fun linkWifiToKnownPlace(knownPlaceId: Int, ssid: String, bssid: String? = null) {
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        if (cleanSsid.isEmpty()) return
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, bssid_list FROM known_wifi_network WHERE known_place_id = ? AND ssid = ?",
            arrayOf(knownPlaceId.toString(), cleanSsid)
        ).use { c ->
            if (c.moveToFirst()) {
                val netId = c.getLong(0)
                val existingBssids = c.getString(1).split(",").filter { it.isNotBlank() }.toMutableSet()
                if (!bssid.isNullOrBlank()) {
                    existingBssids.add(bssid.uppercase())
                    val cv = ContentValues().apply { put("bssid_list", existingBssids.joinToString(",")) }
                    db.update("known_wifi_network", cv, "id = ?", arrayOf(netId.toString()))
                }
                return
            }
        }
        val cv = ContentValues().apply {
            put("known_place_id", knownPlaceId)
            put("ssid", cleanSsid)
            put("bssid_list", bssid?.uppercase() ?: "")
        }
        db.insert("known_wifi_network", null, cv)
    }

    fun getKnownPlaces(): List<KnownPlace> {
        val db = readableDatabase
        val networksByPlace = mutableMapOf<Int, MutableList<KnownWifiNetwork>>()
        db.rawQuery("SELECT id, known_place_id, ssid, bssid_list FROM known_wifi_network", null).use { c ->
            while (c.moveToNext()) {
                val placeId = c.getInt(1)
                networksByPlace.getOrPut(placeId) { mutableListOf() }.add(
                    KnownWifiNetwork(c.getLong(0), placeId, c.getString(2), c.getString(3))
                )
            }
        }
        val places = mutableListOf<KnownPlace>()
        db.rawQuery("SELECT id, label FROM known_place ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                places.add(KnownPlace(id, c.getString(1), networksByPlace[id] ?: emptyList()))
            }
        }
        return places
    }

    fun matchKnownPlaceByWifi(ssid: String?, bssid: String?): KnownPlace? {
        val cleanSsid = ssid?.trim()?.removeSurrounding("\"") ?: ""
        val cleanBssid = bssid?.trim()?.uppercase() ?: ""
        if (cleanSsid.isEmpty() && cleanBssid.isEmpty()) return null

        val places = getKnownPlaces()
        for (place in places) {
            for (net in place.networks) {
                val bssids = net.bssidListCsv.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                if (cleanBssid.isNotEmpty() && cleanBssid in bssids) {
                    return place
                }
                if (cleanSsid.isNotEmpty() && net.ssid.equals(cleanSsid, ignoreCase = true)) {
                    // Passively learn new mesh BSSID if seen on matching SSID
                    if (cleanBssid.isNotEmpty() && cleanBssid != "02:00:00:00:00:00") {
                        linkWifiToKnownPlace(place.id, cleanSsid, cleanBssid)
                    }
                    return place
                }
            }
        }
        return null
    }

    fun getSegmentPresets(): List<SegmentPreset> {
        val out = mutableListOf<SegmentPreset>()
        readableDatabase.rawQuery(
            "SELECT id, label, icon_emoji, sort_order FROM segment_preset ORDER BY sort_order ASC, id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(SegmentPreset(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)))
            }
        }
        return out
    }

    fun addSegmentPreset(label: String, iconEmoji: String): SegmentPreset {
        val db = writableDatabase
        val nextOrder = getSegmentPresets().size
        val cv = ContentValues().apply {
            put("label", label.trim())
            put("icon_emoji", iconEmoji.ifBlank { "🏷️" })
            put("sort_order", nextOrder)
        }
        val id = db.insert("segment_preset", null, cv)
        return SegmentPreset(id, label.trim(), iconEmoji.ifBlank { "🏷️" }, nextOrder)
    }

    fun startExcursion(
        deviceId: Long,
        startEpochSec: Long,
        title: String,
        isManual: Boolean = false
    ): ExcursionRecord {
        val db = writableDatabase
        // Coalesce hallway/elevator Wi-Fi edge flicker: if the most recent excursion for this device
        // ended within <= MICRO_EXCURSION_COALESCE_GAP_SEC of startEpochSec (or is still open),
        // resume it instead of creating a fragmented micro-excursion.
        db.rawQuery(
            """
            SELECT id, start_epoch_sec, end_epoch_sec, is_pinned, title, notes, is_manual
            FROM excursion
            WHERE device_id = ?
            ORDER BY start_epoch_sec DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(deviceId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                val prevId = c.getLong(0)
                val prevStart = c.getLong(1)
                val prevEnd = if (c.isNull(2)) null else c.getLong(2)
                val prevPinned = c.getInt(3) != 0
                val prevTitle = c.getString(4)
                val prevNotes = c.getString(5)
                val prevManual = c.getInt(6) != 0
                val prevSlices = getSlicesForExcursion(prevId)
                val isUntouchedDefault = prevSlices.size == 1 &&
                    prevSlices[0].label == DEFAULT_SLICE_LABEL &&
                    prevSlices[0].notes.isBlank() &&
                    prevSlices[0].startEpochSec == prevStart
                val canCoalesceClosedAuto = !isManual &&
                    prevEnd != null &&
                    isUntouchedDefault &&
                    startEpochSec >= prevStart &&
                    (startEpochSec - prevEnd) <= MICRO_EXCURSION_COALESCE_GAP_SEC
                if (prevEnd == null || canCoalesceClosedAuto) {
                    val updatedManual = prevManual || isManual
                    val reopenCv = ContentValues().apply {
                        putNull("end_epoch_sec")
                        if (updatedManual && !prevManual) {
                            put("is_manual", 1)
                        }
                    }
                    db.update("excursion", reopenCv, "id = ?", arrayOf(prevId.toString()))
                    updateOngoingExcursionEnd(prevId, maxOf(startEpochSec, prevEnd ?: startEpochSec), closeExcursion = false)
                    return ExcursionRecord(prevId, deviceId, prevStart, null, prevPinned, prevTitle, prevNotes, updatedManual)
                }
            }
        }

        val cv = ContentValues().apply {
            put("device_id", deviceId)
            put("start_epoch_sec", startEpochSec)
            putNull("end_epoch_sec")
            put("is_pinned", 0)
            put("title", title)
            put("notes", "")
            put("is_manual", if (isManual) 1 else 0)
        }
        val excursionId = db.insert("excursion", null, cv)
        // v1 Manual Slicing: Start with a single contiguous slice spanning the excursion
        val sliceCv = ContentValues().apply {
            put("excursion_id", excursionId)
            put("start_epoch_sec", startEpochSec)
            put("end_epoch_sec", startEpochSec + 15)
            put("include_in_export", 1)
            put("label", DEFAULT_SLICE_LABEL)
            put("notes", "")
        }
        db.insert("session_slice", null, sliceCv)
        return ExcursionRecord(excursionId, deviceId, startEpochSec, null, false, title, "", isManual)
    }

    fun markExcursionManual(excursionId: Long) {
        val cv = ContentValues().apply { put("is_manual", 1) }
        writableDatabase.update("excursion", cv, "id = ?", arrayOf(excursionId.toString()))
    }

    /**
     * Coalesces fragmented micro-excursions separated by `<= 90s` of fringe Wi-Fi (such as in
     * an elevator shaft or hallway), automatically removes standalone closed, unpinned, untouched
     * auto-created Wi-Fi blips shorter than `180s` (3m) once the 90s coalesce grace window has
     * elapsed (never deleting manually started, pinned, renamed, or edited trips), and ensures
     * `knownPlaceId = 0` in [ringStore] across confirmed/kept excursion windows.
     */
    fun coalesceMicroExcursionsAndRestoreRingPlaceIds(
        ringStore: AqlRingBufferStore,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ) {
        val db = writableDatabase
        val chronological = getExcursions().sortedWith(compareBy<ExcursionRecord> { it.startEpochSec }.thenBy { it.id })
        if (chronological.isEmpty()) return

        var current = chronological.first()
        for (i in 1 until chronological.size) {
            val next = chronological[i]
            val currEnd = current.endEpochSec ?: next.startEpochSec
            val gapSec = next.startEpochSec - currEnd
            val nextSlices = getSlicesForExcursion(next.id)
            val nextHasCustomSlices = nextSlices.size > 1 || nextSlices.any { it.label != DEFAULT_SLICE_LABEL || it.notes.isNotBlank() }

            if (current.deviceId == next.deviceId && gapSec < 0L) {
                val nextEnd = next.endEpochSec ?: Long.MAX_VALUE
                if (next.startEpochSec >= current.startEpochSec && nextEnd <= currEnd && !nextHasCustomSlices) {
                    val mergedPinned = current.isPinned || next.isPinned
                    val mergedManual = current.isManual || next.isManual
                    if (mergedManual && !current.isManual) {
                        markExcursionManual(current.id)
                    }
                    deleteExcursion(next.id)
                    if (mergedPinned) {
                        setExcursionPinned(current.id, true, ringStore)
                    }
                    current = current.copy(isPinned = mergedPinned, isManual = mergedManual)
                } else {
                    current = if (nextEnd > currEnd) next else current
                }
            } else if (current.deviceId == next.deviceId && gapSec in 0L..MICRO_EXCURSION_COALESCE_GAP_SEC) {
                val mergedEnd = if (next.endEpochSec == null) null else maxOf(currEnd, next.endEpochSec)
                val mergedPinned = current.isPinned || next.isPinned
                val mergedManual = current.isManual || next.isManual
                // Prefer the title of whichever excursion was longer
                val currDur = (current.endEpochSec ?: current.startEpochSec) - current.startEpochSec
                val nextDur = (next.endEpochSec ?: next.startEpochSec) - next.startEpochSec
                val mergedTitle = if (nextDur > currDur) next.title else current.title
                val mergedNotes = listOf(current.notes, next.notes).filter { it.isNotBlank() }.joinToString("\n")

                val cv = ContentValues().apply {
                    if (mergedEnd == null) putNull("end_epoch_sec") else put("end_epoch_sec", mergedEnd)
                    put("is_pinned", if (mergedPinned) 1 else 0)
                    put("is_manual", if (mergedManual) 1 else 0)
                    put("title", mergedTitle)
                    put("notes", mergedNotes)
                }
                db.update("excursion", cv, "id = ?", arrayOf(current.id.toString()))

                // If next had custom slices (> 1 slice or labeled), re-parent them; otherwise extend current's last slice
                if (nextHasCustomSlices) {
                    val currSlices = getSlicesForExcursion(current.id)
                    val lastCurrSlice = currSlices.lastOrNull()
                    if (lastCurrSlice != null && nextSlices.first().startEpochSec > lastCurrSlice.startEpochSec) {
                        val extCv = ContentValues().apply { put("end_epoch_sec", nextSlices.first().startEpochSec) }
                        db.update("session_slice", extCv, "id = ?", arrayOf(lastCurrSlice.id.toString()))
                    }
                    val reparentCv = ContentValues().apply { put("excursion_id", current.id) }
                    db.update("session_slice", reparentCv, "excursion_id = ?", arrayOf(next.id.toString()))
                } else {
                    updateOngoingExcursionEnd(current.id, mergedEnd ?: next.startEpochSec, closeExcursion = false)
                }
                deleteExcursion(next.id)
                current = current.copy(
                    endEpochSec = mergedEnd,
                    isPinned = mergedPinned,
                    isManual = mergedManual,
                    title = mergedTitle,
                    notes = mergedNotes
                )
                if (mergedPinned) {
                    setExcursionPinned(current.id, true, ringStore)
                }
            } else {
                current = next
            }
        }

        // Clean up standalone closed, unpinned, untouched < 3m Wi-Fi blips after the 90s grace period,
        // prune expired or rotated-out unpinned excursions, restore knownPlaceId = 0 in ringStore
        // across kept excursions (manual, pinned, or >= 3m), and ensure pinned excursions hold their full span.
        val retentionWindowSec = ringStore.getArchiveWindowSec(0)
        for (exc in getExcursions()) {
            val endSec = exc.endEpochSec
            if (endSec != null) {
                val duration = endSec - exc.startEpochSec
                val hasNotes = getTimelineNotesInRange(exc.startEpochSec, endSec).isNotEmpty()
                if (!exc.isPinned) {
                    val closedBlip = isAutoDiscardableBlip(
                        excursion = exc,
                        slices = getSlicesForExcursion(exc.id),
                        nowEpochSec = nowSec,
                        hasTimelineNotes = hasNotes
                    )
                    val expiredByClock = (nowSec - endSec) > retentionWindowSec
                    val rotatedOutOfRing = (nowSec - endSec) > MICRO_EXCURSION_COALESCE_GAP_SEC &&
                        ringStore.queryRange(0, exc.startEpochSec, endSec).isEmpty()
                    if (closedBlip || expiredByClock || rotatedOutOfRing) {
                        deleteExcursion(exc.id)
                        continue
                    }
                }
                if (exc.isManual || exc.isPinned || hasNotes || duration >= AUTO_TRIP_MIN_DURATION_SEC) {
                    ringStore.clearKnownPlaceInRange(exc.startEpochSec, endSec)
                }
            }
            if (exc.isPinned) {
                val pinnedCount = getPinnedSlotsForExcursion(exc.id)?.size ?: 0
                val resolvedSlots = resolveSlotsForExcursion(exc.id, ringStore)
                if (resolvedSlots.size > pinnedCount) {
                    setExcursionPinned(exc.id, true, ringStore)
                }
            }
        }
        pruneExpiredUnpinnedTimelineNotes(retentionWindowSec, nowSec)
    }

    fun updateOngoingExcursionEnd(
        excursionId: Long,
        latestEpochSec: Long,
        closeExcursion: Boolean,
        ringStore: AqlRingBufferStore? = null
    ) {
        val db = writableDatabase
        if (closeExcursion) {
            val cv = ContentValues().apply { put("end_epoch_sec", latestEpochSec) }
            db.update("excursion", cv, "id = ?", arrayOf(excursionId.toString()))
        }
        // Extend the final slice of this excursion so the partition remains contiguous
        db.rawQuery(
            "SELECT id, start_epoch_sec FROM session_slice WHERE excursion_id = ? ORDER BY end_epoch_sec DESC, id DESC LIMIT 1",
            arrayOf(excursionId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                val sliceId = c.getLong(0)
                val startSec = c.getLong(1)
                if (latestEpochSec >= startSec) {
                    val sliceCv = ContentValues().apply { put("end_epoch_sec", latestEpochSec) }
                    db.update("session_slice", sliceCv, "id = ?", arrayOf(sliceId.toString()))
                }
            }
        }
        if (closeExcursion && ringStore != null) {
            val isPinned = readableDatabase.rawQuery(
                "SELECT is_pinned FROM excursion WHERE id = ? LIMIT 1",
                arrayOf(excursionId.toString())
            ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
            if (isPinned) {
                setExcursionPinned(excursionId, true, ringStore)
            }
        }
    }

    fun getExcursions(): List<ExcursionRecord> {
        val list = mutableListOf<ExcursionRecord>()
        readableDatabase.rawQuery(
            "SELECT id, device_id, start_epoch_sec, end_epoch_sec, is_pinned, title, notes, is_manual FROM excursion ORDER BY start_epoch_sec DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val endSec = if (c.isNull(3)) null else c.getLong(3)
                list.add(
                    ExcursionRecord(
                        id = c.getLong(0),
                        deviceId = c.getLong(1),
                        startEpochSec = c.getLong(2),
                        endEpochSec = endSec,
                        isPinned = c.getInt(4) != 0,
                        title = c.getString(5),
                        notes = c.getString(6),
                        isManual = c.getInt(7) != 0
                    )
                )
            }
        }
        return list
    }

    fun getSlicesForExcursion(excursionId: Long): List<SessionSliceRecord> {
        val list = mutableListOf<SessionSliceRecord>()
        readableDatabase.rawQuery(
            """
            SELECT id, excursion_id, start_epoch_sec, end_epoch_sec, include_in_export, label, notes
            FROM session_slice
            WHERE excursion_id = ?
            ORDER BY start_epoch_sec ASC, id ASC
            """.trimIndent(),
            arrayOf(excursionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SessionSliceRecord(
                        id = c.getLong(0),
                        excursionId = c.getLong(1),
                        startEpochSec = c.getLong(2),
                        endEpochSec = c.getLong(3),
                        includeInExport = c.getInt(4) != 0,
                        label = c.getString(5),
                        notes = c.getString(6)
                    )
                )
            }
        }
        return list
    }

    /**
     * Shared-Divider Split: Splits `sliceId` at `splitEpochSec` into two contiguous adjacent slices
     * (`[start, splitEpochSec]` and `[splitEpochSec, end]`).
     */
    fun splitSliceAt(
        sliceId: Long,
        splitEpochSec: Long,
        @Suppress("UNUSED_PARAMETER") ringStore: AqlRingBufferStore? = null,
        newRightSliceLabel: String? = null
    ): Boolean {
        val db = writableDatabase
        val target = readableDatabase.rawQuery(
            "SELECT id, excursion_id, start_epoch_sec, end_epoch_sec, include_in_export, label, notes FROM session_slice WHERE id = ?",
            arrayOf(sliceId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return false
            SessionSliceRecord(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                c.getInt(4) != 0, c.getString(5), c.getString(6)
            )
        }
        val alignedSplit = (splitEpochSec / 15L) * 15L
        if (alignedSplit <= target.startEpochSec || alignedSplit >= target.endEpochSec) {
            return false
        }
        db.beginTransaction()
        try {
            val existingCount = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM session_slice WHERE excursion_id = ?",
                arrayOf(target.excursionId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }

            val updateCv = ContentValues().apply { put("end_epoch_sec", alignedSplit) }
            db.update("session_slice", updateCv, "id = ?", arrayOf(target.id.toString()))

            val resolvedRightLabel = newRightSliceLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Segment ${existingCount + 1}"
            val insertCv = ContentValues().apply {
                put("excursion_id", target.excursionId)
                put("start_epoch_sec", alignedSplit)
                put("end_epoch_sec", target.endEpochSec)
                put("include_in_export", if (target.includeInExport) 1 else 0)
                put("label", resolvedRightLabel)
                put("notes", "")
            }
            db.insert("session_slice", null, insertCv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * Shared-Divider Boundary Nudge/Drag: Moves the shared boundary between `leftSliceId` and `rightSliceId`
     * to `newBoundaryEpochSec` while preserving strict non-overlapping contiguity.
     */
    fun moveSharedDivider(
        leftSliceId: Long,
        rightSliceId: Long,
        newBoundaryEpochSec: Long,
        @Suppress("UNUSED_PARAMETER") ringStore: AqlRingBufferStore? = null
    ): Boolean {
        val aligned = (newBoundaryEpochSec / 15L) * 15L
        val db = writableDatabase
        val slices = readableDatabase.rawQuery(
            "SELECT id, start_epoch_sec, end_epoch_sec FROM session_slice WHERE id IN (?, ?)",
            arrayOf(leftSliceId.toString(), rightSliceId.toString())
        ).use { c ->
            val map = mutableMapOf<Long, Pair<Long, Long>>()
            while (c.moveToNext()) {
                map[c.getLong(0)] = c.getLong(1) to c.getLong(2)
            }
            map
        }
        val left = slices[leftSliceId] ?: return false
        val right = slices[rightSliceId] ?: return false
        if (aligned <= left.first || aligned >= right.second) return false

        db.beginTransaction()
        try {
            val leftCv = ContentValues().apply { put("end_epoch_sec", aligned) }
            val rightCv = ContentValues().apply { put("start_epoch_sec", aligned) }
            db.update("session_slice", leftCv, "id = ?", arrayOf(leftSliceId.toString()))
            db.update("session_slice", rightCv, "id = ?", arrayOf(rightSliceId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * Merges `leftSliceId` and `rightSliceId` back into a single contiguous slice.
     */
    fun mergeAdjacentSlices(
        leftSliceId: Long,
        rightSliceId: Long,
        @Suppress("UNUSED_PARAMETER") ringStore: AqlRingBufferStore? = null
    ): Boolean {
        val db = writableDatabase
        val endMap = readableDatabase.rawQuery(
            "SELECT id, end_epoch_sec FROM session_slice WHERE id IN (?, ?)",
            arrayOf(leftSliceId.toString(), rightSliceId.toString())
        ).use { c ->
            val map = mutableMapOf<Long, Long>()
            while (c.moveToNext()) {
                map[c.getLong(0)] = c.getLong(1)
            }
            map
        }
        if (!endMap.containsKey(leftSliceId)) return false
        val rightEnd = endMap[rightSliceId] ?: return false

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("end_epoch_sec", rightEnd)
            }
            db.update("session_slice", cv, "id = ?", arrayOf(leftSliceId.toString()))
            db.delete("session_slice", "id = ?", arrayOf(rightSliceId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * Trims the start of `excursionId` forward to `newStartEpochSec` (15s-aligned), deleting any slices
     * that ended at or before `newStartEpochSec` and clamping any slice that spans across it.
     */
    fun trimExcursionStart(
        excursionId: Long,
        newStartEpochSec: Long,
        ringStore: AqlRingBufferStore? = null
    ): Boolean = trimExcursionBoundary(excursionId, newStartEpochSec, isStart = true, ringStore = ringStore)

    /**
     * Trims the end of `excursionId` backward to `newEndEpochSec` (15s-aligned), deleting any slices
     * that started at or after `newEndEpochSec` and clamping any slice that spans across it.
     */
    fun trimExcursionEnd(
        excursionId: Long,
        newEndEpochSec: Long,
        ringStore: AqlRingBufferStore? = null
    ): Boolean = trimExcursionBoundary(excursionId, newEndEpochSec, isStart = false, ringStore = ringStore)

    private fun trimExcursionBoundary(
        excursionId: Long,
        boundaryEpochSec: Long,
        isStart: Boolean,
        ringStore: AqlRingBufferStore?
    ): Boolean {
        val aligned = (boundaryEpochSec / 15L) * 15L
        val exc = getExcursions().firstOrNull { it.id == excursionId } ?: return false
        val slices = getSlicesForExcursion(excursionId)
        val effectiveEnd = exc.endEpochSec ?: slices.lastOrNull()?.endEpochSec ?: return false
        if (aligned <= exc.startEpochSec || aligned >= effectiveEnd) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in slices) {
                val dropSlice = if (isStart) (s.endEpochSec <= aligned) else (s.startEpochSec >= aligned)
                val clampSlice = if (isStart) (s.startEpochSec < aligned) else (s.endEpochSec > aligned)
                if (dropSlice) {
                    db.delete("session_slice", "id = ?", arrayOf(s.id.toString()))
                } else if (clampSlice) {
                    val field = if (isStart) "start_epoch_sec" else "end_epoch_sec"
                    val cv = ContentValues().apply { put(field, aligned) }
                    db.update("session_slice", cv, "id = ?", arrayOf(s.id.toString()))
                }
            }
            val excField = if (isStart) "start_epoch_sec" else "end_epoch_sec"
            val excCv = ContentValues().apply { put(excField, aligned) }
            db.update("excursion", excCv, "id = ?", arrayOf(excursionId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (ringStore != null && exc.isPinned) {
            setExcursionPinned(excursionId, true, ringStore)
        }
        return true
    }

    fun updateSliceMetadata(
        sliceId: Long,
        label: String,
        notes: String,
        includeInExport: Boolean = true
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("label", label)
            put("notes", notes)
            put("include_in_export", if (includeInExport) 1 else 0)
        }
        db.update("session_slice", cv, "id = ?", arrayOf(sliceId.toString()))
    }

    private fun insertPinnedBlob(
        db: SQLiteDatabase,
        excursionId: Long,
        sliceId: Long?,
        startSec: Long,
        endSec: Long,
        blob: ByteArray
    ) {
        val pinCv = ContentValues().apply {
            put("excursion_id", excursionId)
            if (sliceId == null) putNull("slice_id") else put("slice_id", sliceId)
            put("start_epoch_sec", startSec)
            put("end_epoch_sec", endSec)
            put("raw_samples_payload", blob)
        }
        db.insert("pinned_slice_data", null, pinCv)
    }

    fun setExcursionPinned(excursionId: Long, isPinned: Boolean, ringStore: AqlRingBufferStore? = null) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("is_pinned", if (isPinned) 1 else 0) }
        db.update("excursion", cv, "id = ?", arrayOf(excursionId.toString()))
        if (isPinned && ringStore != null) {
            val exc = getExcursions().firstOrNull { it.id == excursionId } ?: return
            val endSec = exc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
            val merged = resolveSlotsForExcursion(excursionId, ringStore)
                .filter { it.bucketEpochSec in exc.startEpochSec..endSec }
            val blob = AqlRingBufferStore.encodePinnedBlob(merged)
            db.delete("pinned_slice_data", "excursion_id = ?", arrayOf(excursionId.toString()))
            insertPinnedBlob(db, excursionId, null, exc.startEpochSec, endSec, blob)
        } else {
            db.delete("pinned_slice_data", "excursion_id = ?", arrayOf(excursionId.toString()))
        }
    }

    fun renameExcursion(excursionId: Long, newTitle: String) {
        val clean = newTitle.trim().ifEmpty { "Trip" }
        val cv = ContentValues().apply { put("title", clean) }
        writableDatabase.update("excursion", cv, "id = ?", arrayOf(excursionId.toString()))
    }

    fun deleteExcursion(excursionId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pinned_slice_data", "excursion_id = ?", arrayOf(excursionId.toString()))
            db.delete("session_slice", "excursion_id = ?", arrayOf(excursionId.toString()))
            db.delete("excursion", "id = ?", arrayOf(excursionId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPinnedSlotsForExcursion(excursionId: Long): List<AqlRingBufferStore.RingSlotRecord>? {
        val mergedByEpoch = mutableMapOf<Long, AqlRingBufferStore.RingSlotRecord>()
        var found = false
        readableDatabase.rawQuery(
            "SELECT raw_samples_payload FROM pinned_slice_data WHERE excursion_id = ? ORDER BY id ASC",
            arrayOf(excursionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                found = true
                AqlRingBufferStore.decodePinnedBlob(c.getBlob(0)).forEach { slot ->
                    mergedByEpoch[slot.bucketEpochSec] = slot
                }
            }
        }
        return if (found) mergedByEpoch.values.sortedBy { it.bucketEpochSec } else null
    }

    fun isExcursionOutsideRingWindow(
        exc: ExcursionRecord,
        ringStore: AqlRingBufferStore,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val retentionWindowSec = ringStore.getArchiveWindowSec(0)
        val endSec = exc.endEpochSec ?: nowEpochSec
        val ringCutoffSec = nowEpochSec - retentionWindowSec
        if (exc.startEpochSec < ringCutoffSec) return true
        val pinnedSlots = getPinnedSlotsForExcursion(exc.id).orEmpty()
        if (pinnedSlots.isNotEmpty()) {
            val liveRingSlots = ringStore.queryRange(0, exc.startEpochSec, endSec)
            if (liveRingSlots.size < pinnedSlots.size) return true
        }
        return false
    }

    fun createImportedExcursion(
        deviceId: Long,
        startEpochSec: Long,
        endEpochSec: Long,
        title: String,
        notes: String,
        isPinned: Boolean,
        slices: List<ImportedSliceSpec>,
        pinnedSlots: List<AqlRingBufferStore.RingSlotRecord>? = null
    ): Long {
        val db = writableDatabase
        val safeEnd = endEpochSec.coerceAtLeast(startEpochSec)
        val cleanTitle = title.trim().ifEmpty { "Imported Trip" }
        val cleanSlices = if (slices.isNotEmpty()) {
            slices
        } else {
            listOf(
                ImportedSliceSpec(
                    startEpochSec = startEpochSec,
                    endEpochSec = safeEnd.coerceAtLeast(startEpochSec + 15L),
                    label = DEFAULT_SLICE_LABEL,
                    notes = "",
                    includeInExport = true
                )
            )
        }
        val blob = if (isPinned && pinnedSlots != null) {
            AqlRingBufferStore.encodePinnedBlob(pinnedSlots.sortedBy { it.bucketEpochSec })
        } else {
            null
        }

        db.beginTransaction()
        try {
            val excCv = ContentValues().apply {
                put("device_id", deviceId)
                put("start_epoch_sec", startEpochSec)
                put("end_epoch_sec", safeEnd)
                put("is_pinned", if (isPinned) 1 else 0)
                put("title", cleanTitle)
                put("notes", notes)
                put("is_manual", 1)
            }
            val excursionId = db.insert("excursion", null, excCv)
            for (slice in cleanSlices) {
                val sliceCv = ContentValues().apply {
                    put("excursion_id", excursionId)
                    put("start_epoch_sec", slice.startEpochSec)
                    put("end_epoch_sec", slice.endEpochSec.coerceAtLeast(slice.startEpochSec))
                    put("include_in_export", if (slice.includeInExport) 1 else 0)
                    put("label", slice.label.trim().ifEmpty { DEFAULT_SLICE_LABEL })
                    put("notes", slice.notes)
                }
                db.insert("session_slice", null, sliceCv)
            }
            if (blob != null) {
                insertPinnedBlob(db, excursionId, null, startEpochSec, safeEnd, blob)
            }
            db.setTransactionSuccessful()
            return excursionId
        } finally {
            db.endTransaction()
        }
    }

    fun resolveSlotsForExcursion(
        excursionId: Long,
        ringStore: AqlRingBufferStore
    ): List<AqlRingBufferStore.RingSlotRecord> {
        val exc = getExcursions().firstOrNull { it.id == excursionId } ?: return emptyList()
        val endSec = exc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
        val mergedByEpoch = mutableMapOf<Long, AqlRingBufferStore.RingSlotRecord>()

        ringStore.queryRange(0, exc.startEpochSec, endSec).forEach { slot ->
            mergedByEpoch[slot.bucketEpochSec] = slot
        }
        if (exc.isPinned) {
            getPinnedSlotsForExcursion(excursionId)?.forEach { slot ->
                if (slot.bucketEpochSec in exc.startEpochSec..endSec) {
                    mergedByEpoch[slot.bucketEpochSec] = slot
                }
            }
        }
        return mergedByEpoch.values.sortedBy { it.bucketEpochSec }
    }

    fun addTimelineNote(
        timestampEpochSec: Long,
        text: String,
        createdEpochSec: Long = System.currentTimeMillis() / 1000L
    ): TimelineNoteRecord {
        val cleanText = text.trim().ifEmpty { DEFAULT_QUICK_NOTE_TEXT }
        val cv = ContentValues().apply {
            put("timestamp_epoch_sec", timestampEpochSec)
            put("text", cleanText)
            put("created_epoch_sec", createdEpochSec)
        }
        val id = writableDatabase.insert("timeline_note", null, cv)
        return TimelineNoteRecord(id, timestampEpochSec, cleanText, createdEpochSec)
    }

    fun updateTimelineNote(noteId: Long, text: String) {
        val cleanText = text.trim().ifEmpty { DEFAULT_QUICK_NOTE_TEXT }
        val cv = ContentValues().apply { put("text", cleanText) }
        writableDatabase.update("timeline_note", cv, "id = ?", arrayOf(noteId.toString()))
    }

    fun deleteTimelineNote(noteId: Long) {
        writableDatabase.delete("timeline_note", "id = ?", arrayOf(noteId.toString()))
    }

    fun getTimelineNotesInRange(
        startEpochSec: Long,
        endEpochSec: Long
    ): List<TimelineNoteRecord> {
        val list = mutableListOf<TimelineNoteRecord>()
        readableDatabase.rawQuery(
            """
            SELECT id, timestamp_epoch_sec, text, created_epoch_sec
            FROM timeline_note
            WHERE timestamp_epoch_sec >= ? AND timestamp_epoch_sec <= ?
            ORDER BY timestamp_epoch_sec ASC, id ASC
            """.trimIndent(),
            arrayOf(startEpochSec.toString(), endEpochSec.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TimelineNoteRecord(
                        id = c.getLong(0),
                        timestampEpochSec = c.getLong(1),
                        text = c.getString(2),
                        createdEpochSec = c.getLong(3)
                    )
                )
            }
        }
        return list
    }

    fun pruneExpiredUnpinnedTimelineNotes(
        retentionWindowSec: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ) {
        val cutoffSec = nowSec - retentionWindowSec
        val staleNotes = mutableListOf<TimelineNoteRecord>()
        readableDatabase.rawQuery(
            """
            SELECT id, timestamp_epoch_sec, text, created_epoch_sec
            FROM timeline_note
            WHERE timestamp_epoch_sec < ?
            """.trimIndent(),
            arrayOf(cutoffSec.toString())
        ).use { c ->
            while (c.moveToNext()) {
                staleNotes.add(
                    TimelineNoteRecord(
                        id = c.getLong(0),
                        timestampEpochSec = c.getLong(1),
                        text = c.getString(2),
                        createdEpochSec = c.getLong(3)
                    )
                )
            }
        }
        if (staleNotes.isEmpty()) return
        val pinnedExcursions = getExcursions().filter { it.isPinned }
        val db = writableDatabase
        for (note in staleNotes) {
            val coveredByPinned = pinnedExcursions.any { exc ->
                note.timestampEpochSec in exc.startEpochSec..(exc.endEpochSec ?: nowSec)
            }
            if (!coveredByPinned) {
                db.delete("timeline_note", "id = ?", arrayOf(note.id.toString()))
            }
        }
    }

    companion object {
        const val DB_NAME = "android_aql_metadata.db"
        const val DB_VERSION = 3
        const val MICRO_EXCURSION_COALESCE_GAP_SEC = 90L
        const val AUTO_TRIP_MIN_DURATION_SEC = 180L
        const val AUTO_TRIP_TITLE_PREFIX = "Trip ("
        const val DEFAULT_SLICE_LABEL = "Unlabeled Trip Span"
        const val DEFAULT_QUICK_NOTE_TEXT = "📍 Marker"

        /**
         * Returns true when a closed auto-recorded excursion is safe to discard as a momentary
         * Wi-Fi dropout: unpinned, not manually started/carved, shorter than 3 minutes, past the
         * 90-second coalesce grace window, and untouched by user edits or timeline notes.
         */
        fun isAutoDiscardableBlip(
            excursion: ExcursionRecord,
            slices: List<SessionSliceRecord>,
            nowEpochSec: Long,
            hasTimelineNotes: Boolean = false
        ): Boolean {
            if (excursion.isPinned || excursion.isManual || hasTimelineNotes) return false
            val endSec = excursion.endEpochSec ?: return false
            val durationSec = (endSec - excursion.startEpochSec).coerceAtLeast(0L)
            if (durationSec >= AUTO_TRIP_MIN_DURATION_SEC) return false
            if ((nowEpochSec - endSec) <= MICRO_EXCURSION_COALESCE_GAP_SEC) return false
            if (excursion.notes.isNotBlank() || !excursion.title.startsWith(AUTO_TRIP_TITLE_PREFIX)) return false
            if (slices.size > 1 || slices.any { it.label != DEFAULT_SLICE_LABEL || it.notes.isNotBlank() }) {
                return false
            }
            return true
        }

        /**
         * Associates timestamped timeline notes with contiguous slices using `[start, end)` for
         * non-final segments and `[start, end]` for the final segment so boundary notes appear once.
         */
        fun assignNotesToSlices(
            slices: List<SessionSliceRecord>,
            notes: List<TimelineNoteRecord>
        ): Map<Long, List<TimelineNoteRecord>> {
            if (slices.isEmpty() || notes.isEmpty()) return emptyMap()
            val out = LinkedHashMap<Long, MutableList<TimelineNoteRecord>>()
            for (note in notes) {
                val ts = note.timestampEpochSec
                val match = slices.withIndex().firstOrNull { (idx, slice) ->
                    if (idx < slices.lastIndex) {
                        ts >= slice.startEpochSec && ts < slice.endEpochSec
                    } else {
                        ts >= slice.startEpochSec && ts <= slice.endEpochSec
                    }
                }?.value ?: continue
                out.getOrPut(match.id) { mutableListOf() }.add(note)
            }
            return out
        }

        /**
         * Snaps [noteEpochSec] to the nearest [stepSec] grid boundary strictly inside
         * `(sliceStartEpochSec, sliceEndEpochSec)`, returning null if it falls on an endpoint.
         */
        fun computeAlignedSplitForNote(
            noteEpochSec: Long,
            sliceStartEpochSec: Long,
            sliceEndEpochSec: Long,
            stepSec: Long = 15L
        ): Long? {
            if (stepSec <= 0L) return null
            val aligned = ((noteEpochSec + stepSec / 2L) / stepSec) * stepSec
            return if (aligned > sliceStartEpochSec && aligned < sliceEndEpochSec) aligned else null
        }

        fun resolveSelectedExcursionId(
            excursions: List<ExcursionRecord>,
            currentSelectedId: Long?,
            lastSeenLatestId: Long?,
            isTripEditMode: Boolean
        ): Pair<Long?, Long?> {
            if (excursions.isEmpty()) return null to null
            val newestId = excursions.first().id
            if (lastSeenLatestId != null && newestId != lastSeenLatestId && !isTripEditMode) {
                return newestId to newestId
            }
            val picked = excursions.firstOrNull { it.id == currentSelectedId } ?: excursions.first()
            return picked.id to newestId
        }
    }
}
