# CSV Export Schema Specification (`csv_export_schema.md`)

## 1. Purpose
The CSV export produced by `AndroidAQL` is designed to be **self-contained** so that external analysis scripts, charting workflows, or PDF advocacy report generators can reconstruct an exported segment's full 15-second time series (`1` row per sample slot in chronological order), air quality stats, and movement/GPS confidence bands from a single attached `.csv` file (`📤 Export CSV` on any segment card) without requiring access to the phone's SQLite or `.aqlring` files.

## 2. File Format Conventions
* **Encoding:** UTF-8 (no BOM)
* **Line Terminator:** `\n` (LF)
* **Header Row:** Always present on line 1
* **Quoting:** Standard RFC 4180 double-quoting for string fields (`excursion_title`, `slice_label`, `slice_notes`, `known_place_label`) containing commas, quotes, or newlines.
* **Missing / Not Applicable Values:** Empty string `""` (e.g., `speed_mps` and `gps_accuracy_m` are empty when stationary at a Saved Wi-Fi Place with GPS disabled).

## 3. Column Definitions (Ordered)

| # | Column Name | Type | Example | Description |
| :- | :--- | :--- | :--- | :--- |
| 1 | `timestamp_iso8601` | `string` | `2026-11-11T14:22:15-06:00` | Local ISO-8601 timestamp with timezone offset |
| 2 | `timestamp_epoch_sec` | `uint32` | `1794428535` | Aligned slot UTC epoch timestamp in seconds |
| 3 | `resolution_sec` | `uint32` | `15` | Bucket width in seconds (`15`, `300`, or `3600`) |
| 4 | `sample_count` | `uint16` | `3` | Number of BLE frames merged in this bucket |
| 5 | `device_mac` | `string` | `58:2D:34:12:AB:CD` | Monitor hardware identifier |
| 6 | `device_nickname` | `string` | `Travel CGDN1` | User-assigned monitor name |
| 7 | `context_state` | `string` | `FIELD_EXCURSION` | `STATIONARY_KNOWN_AP` or `FIELD_EXCURSION` |
| 8 | `known_place_label` | `string` | `""` or `"Home"` | Populated when `context_state == STATIONARY_KNOWN_AP` |
| 9 | `excursion_id` | `int64` | `42` | Parent excursion ID (empty if stationary) |
| 10 | `excursion_title` | `string` | `"Northwestern Clinic Visit"` | User-assigned excursion title |
| 11 | `slice_id` | `int64` | `108` | Session slice ID covering this timestamp |
| 12 | `slice_label` | `string` | `"10th Floor Waiting Room"` | User-selected preset or custom segment label |
| 13 | `slice_notes` | `string` | `"Crowded, windows sealed"` | Optional free-text notes on the segment |
| 14 | `co2_ppm_mean` | `uint16` | `1420` | Mean CO₂ concentration (`ppm`) |
| 15 | `co2_ppm_min` | `uint16` | `1395` | Minimum CO₂ in bucket (`ppm`) |
| 16 | `co2_ppm_max` | `uint16` | `1455` | Peak CO₂ in bucket (`ppm`) |
| 17 | `pm25_ugm3_mean` | `float` | `8.4` | Mean PM2.5 concentration (`µg/m³`) |
| 18 | `pm25_ugm3_max` | `uint16` | `11` | Peak PM2.5 in bucket (`µg/m³`) |
| 19 | `pm10_ugm3_mean` | `float` | `14.2` | Mean PM10 concentration (`µg/m³`) |
| 20 | `pm10_ugm3_max` | `uint16` | `18` | Peak PM10 in bucket (`µg/m³`) |
| 21 | `temp_c_mean` | `float` | `22.3` | Mean temperature (`°C`) |
| 22 | `humidity_pct_mean` | `float` | `38.5` | Mean relative humidity (`%`) |
| 23 | `battery_pct` | `uint8` | `84` | Monitor battery percentage (`0..100`) |
| 24 | `rssi_dbm` | `int8` | `-61` | BLE signal strength (`dBm`) |
| 25 | `speed_mps` | `float` | `0.00` | Movement speed (`m/s`); empty when GPS off |
| 26 | `gps_accuracy_m` | `float` | `68.5` | Horizontal GPS accuracy radius (`m`); empty when GPS off |
