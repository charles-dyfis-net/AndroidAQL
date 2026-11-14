# Binary Ring-Buffer Storage Format Specification (`.aqlring`)

## 1. Overview & Design Goals
`AndroidAQL` stores continuous environmental and scalar movement time-series data in a constant-size, multi-archive circular binary file (`.aqlring`), inspired by RRDtool and Graphite Whisper.

* **Strictly Bounded Disk Footprint:** Each monitored device is allocated a single `.aqlring` file of **3,970,720 bytes (~3.79 MB)**.
* **$O(1)$ Inline Consolidation & Gap Clearing:** Writing a sample updates Archive 0 (15s), Archive 1 (5m), and Archive 2 (1h) in-place via `java.nio.channels.FileChannel` without background compaction or SQLite WAL/VACUUM overhead.
* **Endianness:** All multi-byte integers are stored in **Little-Endian** (`ByteOrder.LITTLE_ENDIAN`) format.

---

## 2. File Layout Summary

| Byte Offset Range | Size (Bytes) | Section |
| :--- | :--- | :--- |
| `0 .. 63` | `64` | **File Header** (`AQLRING1` magic, version, device metadata) |
| `64 .. 159` | `96` (`3 × 32`) | **Archive Descriptor Table** (3 archives × 32 bytes) |
| `160 .. 2,580,639` | `2,580,480` | **Archive 0 Slots:** `80,640` slots × `32` bytes (`15s` step, `14d` retention) |
| `2,580,640 .. 3,410,079` | `829,440` | **Archive 1 Slots:** `25,920` slots × `32` bytes (`300s` / `5m` step, `90d` retention) |
| `3,410,080 .. 3,970,719` | `560,640` | **Archive 2 Slots:** `17,520` slots × `32` bytes (`3600s` / `1h` step, `730d` retention) |

---

## 3. Header & Archive Descriptor Layout

### 3.1 File Header (`64` bytes at offset `0`)
| Offset | Type | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `0..7` | `char[8]` | `magic` | ASCII `"AQLRING1"` (`0x41 0x51 0x4C 0x52 0x49 0x4E 0x47 0x31`) |
| `8..11` | `uint32` | `version` | Format version (`1`) |
| `12..15` | `uint32` | `archive_count` | Number of archives (`3`) |
| `16..23` | `int64` | `device_id` | Internal monitor device ID |
| `24..31` | `int64` | `created_epoch_ms` | File creation timestamp (UTC epoch milliseconds) |
| `32..63` | `uint8[32]` | `reserved` | Zero-filled reserved bytes |

### 3.2 Archive Descriptor (`32` bytes per archive, starting at offset `64`)
| Relative Offset | Type | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `+0..3` | `uint32` | `step_sec` | Bucket width in seconds (`15`, `300`, `3600`) |
| `+4..7` | `uint32` | `slot_count` | Number of slots in ring (`80640`, `25920`, `17520`) |
| `+8..11` | `uint32` | `slot_size_bytes` | Bytes per slot (`32`) |
| `+12..15` | `uint32` | `data_offset_bytes` | Absolute byte offset in file where Slot `0` begins |
| `+16..23` | `int64` | `last_bucket_sec` | Aligned epoch seconds of most recently written slot (`-1` if never written) |
| `+24..31` | `int64` | `reserved` | Zero-filled reserved bytes |

---

## 4. Slot Binary Record Layout (`32` bytes per slot)

For a given timestamp `ts_sec` (in UTC epoch seconds) and archive parameters `(step_sec, slot_count, data_offset_bytes)`:
* **Aligned Bucket Timestamp:** `bucket_sec = ts_sec - (ts_sec % step_sec)`
* **Slot Index:** `slot_index = ((ts_sec / step_sec) % slot_count)`
* **Byte Offset:** `byte_offset = data_offset_bytes + (slot_index * 32)`

| Relative Offset | Type | Field Name | Units / Scaling | Sentinel / Empty Value |
| :--- | :--- | :--- | :--- | :--- |
| `+0..3` | `uint32` | `bucket_epoch_sec` | UTC epoch seconds | `0` when slot is empty/cleared |
| `+4..5` | `uint16` | `sample_count` | Count of raw frames merged in slot | `0` indicates invalid/gap slot |
| `+6..7` | `uint16` | `known_place_id` | `0` = Field Excursion; `>0` = `KNOWN_PLACE.id` | `0` |
| `+8..9` | `uint16` | `co2_ppm_mean` | CO₂ mean (`ppm`) | `0` |
| `+10..11` | `uint16` | `co2_ppm_min` | CO₂ minimum (`ppm`) | `0` |
| `+12..13` | `uint16` | `co2_ppm_max` | CO₂ maximum (`ppm`) | `0` |
| `+14..15` | `uint16` | `pm25_mean_x10` | PM2.5 mean in `0.1 µg/m³` (`val / 10.0`) | `0` |
| `+16..17` | `uint16` | `pm25_max` | PM2.5 peak in `1 µg/m³` | `0` |
| `+18..19` | `uint16` | `pm10_mean_x10` | PM10 mean in `0.1 µg/m³` (`val / 10.0`) | `0` |
| `+20..21` | `uint16` | `pm10_max` | PM10 peak in `1 µg/m³` | `0` |
| `+22..23` | `int16` | `temp_c_mean_x10` | Temperature mean in `0.1 °C` (`val / 10.0`) | `0` |
| `+24..25` | `uint16` | `humidity_mean_x10` | Relative humidity mean in `0.1%` (`val / 10.0`) | `0` |
| `+26` | `uint8` | `battery_pct_last` | Battery level (`0..100%`) | `255` (`0xFF`) if unknown |
| `+27` | `int8` | `rssi_dbm_last` | Signed BLE RSSI (`dBm`, e.g., `-65`) | `-128` (`0x80`) if unknown |
| `+28..29` | `uint16` | `speed_mps_x100` | Movement speed in `0.01 m/s` (`val / 100.0`) | `65535` (`0xFFFF`) when GPS off |
| `+30..31` | `uint16` | `gps_accuracy_m_x10` | GPS horizontal accuracy in `0.1 m` (`val / 10.0`) | `65535` (`0xFFFF`) when GPS off |

---

## 5. Write, Consolidation & Gap-Clearing Algorithm

When a sample arrives at `ts_sec`, the writer updates each archive $i \in \{0, 1, 2\}$ independently:
1. Compute `bucket_sec = (ts_sec / step_sec) * step_sec`.
2. **Advance-and-Clear Skipped Slots:**
   * If `last_bucket_sec >= 0` and `bucket_sec > last_bucket_sec`:
     * Let `steps_elapsed = (bucket_sec - last_bucket_sec) / step_sec`.
     * If `steps_elapsed >= slot_count`, zero-fill the entire archive data region (`slot_count * 32` bytes).
     * Otherwise, for each skipped step `k` in `1 .. steps_elapsed`, zero out the 32-byte slot at `((last_bucket_sec / step_sec + k) % slot_count)`.
3. **In-Slot Running Consolidation (`bucket_sec == existingSlot.bucket_epoch_sec` and `existingSlot.sample_count > 0`):**
   * `new_count = old_count + 1`
   * `mean_new = round((mean_old * old_count + sample_val) / new_count)`
   * `min_new = min(min_old, sample_val)`
   * `max_new = max(max_old, sample_val)`
4. **First Sample in Slot (`existingSlot.sample_count == 0` or `existingSlot.bucket_epoch_sec != bucket_sec`):**
   * Initialize `sample_count = 1`, `mean = min = max = sample_val`.
5. Update `last_bucket_sec = max(last_bucket_sec, bucket_sec)` in the Archive Descriptor.

---

## 6. Pinned Slice Binary Payload Format (`PINNED_SLICE_DATA`)
When an excursion or session slice is pinned (`is_pinned = true`), the contiguous sequence of valid 32-byte slots from Archive 0 within `[start_ts_sec, end_ts_sec]` is extracted verbatim into a compact blob prefixed with an 8-byte header (`magic = "AQLP"`, `uint32 slot_count`), followed by `slot_count * 32` bytes using the identical Section 4 slot layout.
