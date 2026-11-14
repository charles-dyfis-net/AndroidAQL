package io.github.androidaql.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class SliceCategory {
    TRANSIT,
    DESTINATION,
    OUTDOOR_WALK,
    OTHER
}

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
    val defaultCategory: SliceCategory,
    val sortOrder: Int
)

data class ExcursionRecord(
    val id: Long,
    val deviceId: Long,
    val startEpochSec: Long,
    val endEpochSec: Long?,
    val isPinned: Boolean,
    val title: String,
    val notes: String
)

data class SessionSliceRecord(
    val id: Long,
    val excursionId: Long,
    val startEpochSec: Long,
    val endEpochSec: Long,
    val isPinned: Boolean,
    val includeInExport: Boolean,
    val category: SliceCategory,
    val label: String,
    val notes: String
)

/**
 * Relational metadata store using Android's SQLiteOpenHelper strictly for low-volume
 * metadata: devices, multi-network Saved Places, customizable Segment Presets, Excursions,
 * shared-divider Session Slices, and Pinned Slice binary blobs.
 */
class MetadataDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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
                default_category TEXT NOT NULL,
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
                notes TEXT NOT NULL DEFAULT ''
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
                is_pinned INTEGER NOT NULL DEFAULT 0,
                include_in_export INTEGER NOT NULL DEFAULT 1,
                category TEXT NOT NULL,
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

        seedDefaultSegmentPresets(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 initial schema
    }

    private fun seedDefaultSegmentPresets(db: SQLiteDatabase) {
        val defaults = listOf(
            Triple("Bus", "🚌", SliceCategory.TRANSIT),
            Triple("Train / L", "🚇", SliceCategory.TRANSIT),
            Triple("Cab / Car", "🚕", SliceCategory.TRANSIT),
            Triple("Waiting Room", "🏥", SliceCategory.DESTINATION),
            Triple("Exam Room", "🩺", SliceCategory.DESTINATION),
            Triple("Indoor Visit", "🏢", SliceCategory.DESTINATION),
            Triple("Outdoor Walk", "🚶", SliceCategory.OUTDOOR_WALK)
        )
        for ((idx, item) in defaults.withIndex()) {
            val cv = ContentValues().apply {
                put("label", item.first)
                put("icon_emoji", item.second)
                put("default_category", item.third.name)
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
            "SELECT id, label, icon_emoji, default_category, sort_order FROM segment_preset ORDER BY sort_order ASC, id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val cat = runCatching { SliceCategory.valueOf(c.getString(3)) }.getOrDefault(SliceCategory.OTHER)
                out.add(SegmentPreset(c.getLong(0), c.getString(1), c.getString(2), cat, c.getInt(4)))
            }
        }
        return out
    }

    fun addSegmentPreset(label: String, iconEmoji: String, category: SliceCategory): SegmentPreset {
        val db = writableDatabase
        val nextOrder = getSegmentPresets().size
        val cv = ContentValues().apply {
            put("label", label.trim())
            put("icon_emoji", iconEmoji.ifBlank { "🏷️" })
            put("default_category", category.name)
            put("sort_order", nextOrder)
        }
        val id = db.insert("segment_preset", null, cv)
        return SegmentPreset(id, label.trim(), iconEmoji.ifBlank { "🏷️" }, category, nextOrder)
    }

    fun startExcursion(deviceId: Long, startEpochSec: Long, title: String): ExcursionRecord {
        val db = writableDatabase
        // Coalesce hallway/elevator Wi-Fi edge flicker: if the most recent excursion for this device
        // ended within <= 90 seconds of startEpochSec (or is still open), resume it instead of
        // creating a fragmented 1-second micro-excursion.
        db.rawQuery(
            """
            SELECT id, start_epoch_sec, end_epoch_sec, is_pinned, title, notes
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
                if (prevEnd == null || (startEpochSec >= prevStart && (startEpochSec - prevEnd) <= 90L)) {
                    val reopenCv = ContentValues().apply { putNull("end_epoch_sec") }
                    db.update("excursion", reopenCv, "id = ?", arrayOf(prevId.toString()))
                    updateOngoingExcursionEnd(prevId, maxOf(startEpochSec, prevEnd ?: startEpochSec), closeExcursion = false)
                    return ExcursionRecord(prevId, deviceId, prevStart, null, prevPinned, prevTitle, prevNotes)
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
        }
        val excursionId = db.insert("excursion", null, cv)
        // v1 Manual Slicing: Start with a single contiguous slice spanning the excursion
        val sliceCv = ContentValues().apply {
            put("excursion_id", excursionId)
            put("start_epoch_sec", startEpochSec)
            put("end_epoch_sec", startEpochSec + 15)
            put("is_pinned", 0)
            put("include_in_export", 1)
            put("category", SliceCategory.OTHER.name)
            put("label", "Unlabeled Trip Span")
            put("notes", "")
        }
        db.insert("session_slice", null, sliceCv)
        return ExcursionRecord(excursionId, deviceId, startEpochSec, null, false, title, "")
    }

    /**
     * Coalesces fragmented micro-excursions separated by `<= 90s` of fringe Wi-Fi (such as in
     * an elevator shaft or hallway), removes standalone unpinned `< 30s` Wi-Fi blips, and ensures
     * `knownPlaceId = 0` in [ringStore] across all confirmed excursion windows (`>= 60s`).
     */
    fun coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore: AqlRingBufferStore) {
        val db = writableDatabase
        val chronological = getExcursions().sortedWith(compareBy<ExcursionRecord> { it.startEpochSec }.thenBy { it.id })
        if (chronological.isEmpty()) return

        var current = chronological.first()
        for (i in 1 until chronological.size) {
            val next = chronological[i]
            val currEnd = current.endEpochSec ?: next.startEpochSec
            val gapSec = next.startEpochSec - currEnd
            if (current.deviceId == next.deviceId && gapSec <= 90L) {
                val mergedEnd = if (next.endEpochSec == null) null else maxOf(currEnd, next.endEpochSec)
                val mergedPinned = current.isPinned || next.isPinned
                // Prefer the title of whichever excursion was longer
                val currDur = (current.endEpochSec ?: current.startEpochSec) - current.startEpochSec
                val nextDur = (next.endEpochSec ?: next.startEpochSec) - next.startEpochSec
                val mergedTitle = if (nextDur > currDur) next.title else current.title
                val mergedNotes = listOf(current.notes, next.notes).filter { it.isNotBlank() }.joinToString("\n")

                val cv = ContentValues().apply {
                    if (mergedEnd == null) putNull("end_epoch_sec") else put("end_epoch_sec", mergedEnd)
                    put("is_pinned", if (mergedPinned) 1 else 0)
                    put("title", mergedTitle)
                    put("notes", mergedNotes)
                }
                db.update("excursion", cv, "id = ?", arrayOf(current.id.toString()))

                // If next had custom slices (> 1 slice or labeled), re-parent them; otherwise extend current's last slice
                val nextSlices = getSlicesForExcursion(next.id)
                val nextHasCustomSlices = nextSlices.size > 1 || nextSlices.any { it.label != "Unlabeled Trip Span" || it.notes.isNotBlank() }
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
                    db.delete("session_slice", "excursion_id = ?", arrayOf(next.id.toString()))
                    updateOngoingExcursionEnd(current.id, mergedEnd ?: next.startEpochSec, closeExcursion = false)
                }
                db.delete("excursion", "id = ?", arrayOf(next.id.toString()))
                current = current.copy(
                    endEpochSec = mergedEnd,
                    isPinned = mergedPinned,
                    title = mergedTitle,
                    notes = mergedNotes
                )
            } else {
                current = next
            }
        }

        // Clean up any standalone unpinned < 30s Wi-Fi blips that closed more than 90s ago,
        // and restore knownPlaceId = 0 in ringStore for all confirmed excursions (>= 60s).
        val nowSec = System.currentTimeMillis() / 1000L
        for (exc in getExcursions()) {
            val endSec = exc.endEpochSec
            if (endSec != null) {
                val duration = endSec - exc.startEpochSec
                if (!exc.isPinned && duration < 30L && (nowSec - endSec) > 90L) {
                    db.delete("session_slice", "excursion_id = ?", arrayOf(exc.id.toString()))
                    db.delete("excursion", "id = ?", arrayOf(exc.id.toString()))
                } else if (duration >= 60L) {
                    ringStore.clearKnownPlaceInRange(exc.startEpochSec, endSec)
                }
            }
        }
    }

    fun updateOngoingExcursionEnd(excursionId: Long, latestEpochSec: Long, closeExcursion: Boolean) {
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
    }

    fun getExcursions(): List<ExcursionRecord> {
        val list = mutableListOf<ExcursionRecord>()
        readableDatabase.rawQuery(
            "SELECT id, device_id, start_epoch_sec, end_epoch_sec, is_pinned, title, notes FROM excursion ORDER BY start_epoch_sec DESC",
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
                        notes = c.getString(6)
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
            SELECT id, excursion_id, start_epoch_sec, end_epoch_sec, is_pinned, include_in_export, category, label, notes
            FROM session_slice
            WHERE excursion_id = ?
            ORDER BY start_epoch_sec ASC, id ASC
            """.trimIndent(),
            arrayOf(excursionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val cat = runCatching { SliceCategory.valueOf(c.getString(6)) }.getOrDefault(SliceCategory.OTHER)
                list.add(
                    SessionSliceRecord(
                        id = c.getLong(0),
                        excursionId = c.getLong(1),
                        startEpochSec = c.getLong(2),
                        endEpochSec = c.getLong(3),
                        isPinned = c.getInt(4) != 0,
                        includeInExport = c.getInt(5) != 0,
                        category = cat,
                        label = c.getString(7),
                        notes = c.getString(8)
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
    fun splitSliceAt(sliceId: Long, splitEpochSec: Long): Boolean {
        val db = writableDatabase
        val target = readableDatabase.rawQuery(
            "SELECT id, excursion_id, start_epoch_sec, end_epoch_sec, is_pinned, include_in_export, category, label, notes FROM session_slice WHERE id = ?",
            arrayOf(sliceId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return false
            val cat = runCatching { SliceCategory.valueOf(c.getString(6)) }.getOrDefault(SliceCategory.OTHER)
            SessionSliceRecord(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                c.getInt(4) != 0, c.getInt(5) != 0, cat, c.getString(7), c.getString(8)
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

            val insertCv = ContentValues().apply {
                put("excursion_id", target.excursionId)
                put("start_epoch_sec", alignedSplit)
                put("end_epoch_sec", target.endEpochSec)
                put("is_pinned", if (target.isPinned) 1 else 0)
                put("include_in_export", if (target.includeInExport) 1 else 0)
                put("category", SliceCategory.DESTINATION.name)
                put("label", "Segment ${existingCount + 1}")
                put("notes", "")
            }
            db.insert("session_slice", null, insertCv)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Shared-Divider Boundary Nudge/Drag: Moves the shared boundary between `leftSliceId` and `rightSliceId`
     * to `newBoundaryEpochSec` while preserving strict non-overlapping contiguity.
     */
    fun moveSharedDivider(leftSliceId: Long, rightSliceId: Long, newBoundaryEpochSec: Long): Boolean {
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
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Merges `leftSliceId` and `rightSliceId` back into a single contiguous slice.
     */
    fun mergeAdjacentSlices(leftSliceId: Long, rightSliceId: Long): Boolean {
        val db = writableDatabase
        val rightEnd = readableDatabase.rawQuery(
            "SELECT end_epoch_sec FROM session_slice WHERE id = ?",
            arrayOf(rightSliceId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return false
            c.getLong(0)
        }
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("end_epoch_sec", rightEnd) }
            db.update("session_slice", cv, "id = ?", arrayOf(leftSliceId.toString()))
            db.delete("session_slice", "id = ?", arrayOf(rightSliceId.toString()))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun updateSliceMetadata(
        sliceId: Long,
        label: String,
        category: SliceCategory,
        notes: String,
        isPinned: Boolean,
        includeInExport: Boolean,
        ringStore: AqlRingBufferStore? = null
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("label", label)
            put("category", category.name)
            put("notes", notes)
            put("is_pinned", if (isPinned) 1 else 0)
            put("include_in_export", if (includeInExport) 1 else 0)
        }
        db.update("session_slice", cv, "id = ?", arrayOf(sliceId.toString()))

        if (ringStore != null) {
            readableDatabase.rawQuery(
                "SELECT excursion_id, start_epoch_sec, end_epoch_sec FROM session_slice WHERE id = ?",
                arrayOf(sliceId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    val excId = c.getLong(0)
                    val startSec = c.getLong(1)
                    val endSec = c.getLong(2)
                    db.delete("pinned_slice_data", "slice_id = ?", arrayOf(sliceId.toString()))
                    if (isPinned) {
                        val blob = ringStore.extractPinnedBlob(startSec, endSec)
                        val pinCv = ContentValues().apply {
                            put("excursion_id", excId)
                            put("slice_id", sliceId)
                            put("start_epoch_sec", startSec)
                            put("end_epoch_sec", endSec)
                            put("raw_samples_payload", blob)
                        }
                        db.insert("pinned_slice_data", null, pinCv)
                    }
                }
            }
        }
    }

    fun setExcursionPinned(excursionId: Long, isPinned: Boolean, ringStore: AqlRingBufferStore? = null) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("is_pinned", if (isPinned) 1 else 0) }
        db.update("excursion", cv, "id = ?", arrayOf(excursionId.toString()))
        db.delete("pinned_slice_data", "excursion_id = ? AND slice_id IS NULL", arrayOf(excursionId.toString()))
        if (isPinned && ringStore != null) {
            val exc = getExcursions().firstOrNull { it.id == excursionId } ?: return
            val endSec = exc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
            val blob = ringStore.extractPinnedBlob(exc.startEpochSec, endSec)
            val pinCv = ContentValues().apply {
                put("excursion_id", excursionId)
                putNull("slice_id")
                put("start_epoch_sec", exc.startEpochSec)
                put("end_epoch_sec", endSec)
                put("raw_samples_payload", blob)
            }
            db.insert("pinned_slice_data", null, pinCv)
        }
    }

    fun getPinnedSlotsForSlice(sliceId: Long): List<AqlRingBufferStore.RingSlotRecord>? {
        readableDatabase.rawQuery(
            "SELECT raw_samples_payload FROM pinned_slice_data WHERE slice_id = ? LIMIT 1",
            arrayOf(sliceId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return AqlRingBufferStore.decodePinnedBlob(c.getBlob(0))
            }
        }
        return null
    }

    companion object {
        const val DB_NAME = "android_aql_metadata.db"
        const val DB_VERSION = 1
    }
}
