# XLSX Export & Import Schema Specification (`xlsx_export_schema.md`)

## 1. Purpose
`AirSlice` exports trips and individual trip segments as self-contained 4-sheet OOXML workbooks (`.xlsx`) so downstream spreadsheet workflows, charting tools, PDF advocacy report generators, and whole-trip backups/restores receive the complete trip context—splits, labels, notes, and 15-second telemetry—in a single portable file (`📤 Export Trip (.xlsx)` inside **Manage Trip** or `📤 Export XLSX` on any Segment Card).

## 2. Workbook Structure & OOXML Conventions
* **Format:** Standard OOXML (ECMA-376 `.xlsx`), MIME type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
* **Worksheets (in fixed tab order):**
  1. `Trip`
  2. `Segments`
  3. `Notes`
  4. `Samples`
* **Headers:** Row 1 on every sheet is bolded, shaded, and frozen (`ySplit="1"`).
* **Cell Types & Editing Compatibility:** `AirSlice` emits strict ECMA-376 OOXML packages including `xl/sharedStrings.xml` (`t="s"` string references), `xl/theme/theme1.xml`, unquoted decimal numbers (`Locale.US`), and OOXML booleans (`t="b"`), validated on the host against `openxml-audit` with zero errors and zero warnings. The importer (`XlsxImporter`) accepts both `xl/sharedStrings.xml` (including rich text `<r><t>`) and `inlineStr` so workbooks edited and re-saved in Excel, Google Sheets, LibreOffice Calc, or Apple Numbers re-import cleanly.

---

## 3. Sheet Specifications

### 3.1 `Trip` Sheet (Key–Value Summary)
Columns: `field`, `value`

| Field | Type | Example | Description |
| :--- | :--- | :--- | :--- |
| `schema_version` | `int` | `1` | Workbook schema version |
| `export_scope` | `string` | `EXCURSION` | `EXCURSION` (whole trip) or `SLICE` (single segment) |
| `excursion_id` | `int64` | `42` | Source excursion ID (blank for stationary-only fixtures) |
| `excursion_title` | `string` | `Loop Coffee Trip` | User-assigned trip title |
| `excursion_notes` | `string` | `Morning errand` | Optional trip-level notes |
| `start_iso8601` | `string` | `2026-11-12T08:54:45-06:00` | Scope start timestamp in local ISO-8601 |
| `start_epoch_sec` | `uint32` | `1794495285` | Scope start UTC epoch seconds |
| `end_iso8601` | `string` | `2026-11-12T09:11:15-06:00` | Scope end timestamp (blank if live/ongoing) |
| `end_epoch_sec` | `uint32` | `1794496275` | Scope end UTC epoch seconds (blank if live/ongoing) |
| `is_pinned` | `bool` | `true` | Whether the source trip is pinned |
| `is_manual` | `bool` | `true` | Whether the source trip is manual/carved/imported |
| `device_mac` | `string` | `58:2D:34:12:AB:CD` | Monitor hardware identifier |
| `device_nickname` | `string` | `Travel CGDN1` | Monitor nickname |
| `resolution_sec` | `uint32` | `15` | Sample bucket width in seconds (`15`) |

### 3.2 `Segments` Sheet (Contiguous Splits & Labels)
Columns (14):

| # | Column Name | Type | Description |
| :- | :--- | :--- | :--- |
| 1 | `segment_index` | `int` | 1-based contiguous segment index within this export |
| 2 | `slice_id` | `int64` | Source `SESSION_SLICE.id` |
| 3 | `start_iso8601` | `string` | Segment start timestamp in local ISO-8601 |
| 4 | `start_epoch_sec` | `uint32` | Segment start UTC epoch seconds |
| 5 | `end_iso8601` | `string` | Segment end timestamp in local ISO-8601 |
| 6 | `end_epoch_sec` | `uint32` | Segment end UTC epoch seconds |
| 7 | `duration_sec` | `uint32` | `end_epoch_sec - start_epoch_sec` |
| 8 | `label` | `string` | Segment label (from preset or custom text) |
| 9 | `notes` | `string` | Optional segment-level notes |
| 10 | `include_in_export` | `bool` | Whether the slice is marked for export |
| 11 | `sample_count` | `int` | Number of 15s samples in this segment |
| 12 | `co2_ppm_mean` | `int` | Mean CO₂ across segment samples (blank if 0 samples) |
| 13 | `co2_ppm_max` | `int` | Peak CO₂ across segment samples (blank if 0 samples) |
| 14 | `pm25_ugm3_mean` | `float` | Mean PM2.5 across segment samples (blank if 0 samples) |

### 3.3 `Notes` Sheet (Timestamped Timeline Notes)
Columns (6):

| # | Column Name | Type | Description |
| :- | :--- | :--- | :--- |
| 1 | `note_id` | `int64` | Source `TIMELINE_NOTE.id` |
| 2 | `timestamp_iso8601` | `string` | Note timestamp in local ISO-8601 |
| 3 | `timestamp_epoch_sec` | `uint32` | Note UTC epoch seconds |
| 4 | `text` | `string` | Note text (or `📍 Marker`) |
| 5 | `created_epoch_sec` | `uint32` | Creation UTC epoch seconds |
| 6 | `segment_index` | `int` | 1-based `Segments` index enclosing this note (`[start, end)` except `[start, end]` for final segment) |

### 3.4 `Samples` Sheet (15-Second Time Series)
Columns (21, ordered chronologically):

| # | Column Name | Type | Example | Description |
| :- | :--- | :--- | :--- | :--- |
| 1 | `timestamp_iso8601` | `string` | `2026-11-12T08:54:45-06:00` | Local ISO-8601 timestamp with timezone offset |
| 2 | `timestamp_epoch_sec` | `uint32` | `1794495285` | Aligned slot UTC epoch timestamp in seconds |
| 3 | `resolution_sec` | `uint32` | `15` | Bucket width in seconds (`15`) |
| 4 | `sample_count` | `uint16` | `3` | Number of BLE frames merged in this bucket |
| 5 | `context_state` | `string` | `FIELD_EXCURSION` | `STATIONARY_KNOWN_AP` or `FIELD_EXCURSION` |
| 6 | `known_place_label` | `string` | `Home` | Populated when `context_state == STATIONARY_KNOWN_AP` |
| 7 | `slice_id` | `int64` | `108` | Enclosing segment slice ID (`[start, end)` / final `[start, end]`) |
| 8 | `slice_label` | `string` | `Outdoor Walk` | Enclosing segment label (informational) |
| 9 | `co2_ppm_mean` | `uint16` | `1420` | Mean CO₂ concentration (`ppm`) |
| 10 | `co2_ppm_min` | `uint16` | `1395` | Minimum CO₂ in bucket (`ppm`) |
| 11 | `co2_ppm_max` | `uint16` | `1455` | Peak CO₂ in bucket (`ppm`) |
| 12 | `pm25_ugm3_mean` | `float` | `8.4` | Mean PM2.5 concentration (`µg/m³`) |
| 13 | `pm25_ugm3_max` | `uint16` | `11` | Peak PM2.5 in bucket (`µg/m³`) |
| 14 | `pm10_ugm3_mean` | `float` | `14.2` | Mean PM10 concentration (`µg/m³`) |
| 15 | `pm10_ugm3_max` | `uint16` | `18` | Peak PM10 in bucket (`µg/m³`) |
| 16 | `temp_c_mean` | `float` | `22.3` | Mean temperature (`°C`) |
| 17 | `humidity_pct_mean` | `float` | `38.5` | Mean relative humidity (`%`) |
| 18 | `battery_pct` | `uint8` | `84` | Monitor battery percentage (`0..100`, blank if unsupported) |
| 19 | `rssi_dbm` | `int8` | `-61` | BLE signal strength (`dBm`, blank if absent) |
| 20 | `speed_mps` | `float` | `1.42` | Movement speed (`m/s`, blank when GPS/Steps off) |
| 21 | `gps_accuracy_m` | `float` | `12.0` | Horizontal GPS accuracy radius (`m`, blank when GPS off) |

---

## 4. Import Round-Trip Semantics
* **Authoritative Splits:** When importing, `Segments` defines the restored `SESSION_SLICE` boundaries, labels, notes, and `include_in_export` flags (even for empty/dropout segments with 0 samples). If a workbook only supplies `Samples`, contiguous spans are derived from `(slice_id, slice_label)`.
* **Regular Trip Import (`📥 Import Trip (.xlsx)...`):** Always enabled in Trips; preserves original timestamps as a permanently **pinned** trip (`PINNED_SLICE_DATA`) without overwriting the live 14-day ring buffer, and restores timeline notes from `Notes` (deduplicated by `(timestamp_epoch_sec, text)`).
* **Demo Mode Replay (`📂 Demo Replay XLSX (Time-Shifted)...`):** Shifts trip bounds, segments, notes, and samples uniformly so the excursion ends near `now`, writing slots into the 14-day ring buffer and restoring segments and notes.
