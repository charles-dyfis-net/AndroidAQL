# Privacy Policy for AndroidAQL (`net.dyfis.androidaql`)

**Effective Date:** November 11, 2026  
**Developer:** Charles Duffy (`charles-dyfis-net` / `dyfis.net`)

## 1. Overview — 100% Local On-Device Operation
**AndroidAQL** is an open-source, local-first Android application for collecting, visualizing, slicing, and exporting environmental air quality telemetry (CO₂, PM2.5, PM10, temperature, and relative humidity) from a user-owned **Qingping Air Monitor Lite (`CGDN1`)** over Bluetooth Low Energy (BLE).

- **No Cloud Backend:** AndroidAQL does not connect to any cloud server, Qingping Cloud, Xiaomi Home, or remote API.
- **No Analytics, Telemetry, or Ads:** AndroidAQL contains zero analytics SDKs, crash-reporting trackers, advertising identifiers, or third-party data collectors.
- **No Automatic Data Transmission:** No data leaves your device unless you explicitly initiate a file export (CSV or diagnostic JSONL) via the Android system share sheet.

---

## 2. Permissions & Data Processed On-Device

### A. Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`)
- **Purpose:** Used to scan for Bluetooth Low Energy (`0xFDCD` and `0xFE95`) advertisements from your physical Qingping Air Monitor Lite (`CGDN1`) and to perform periodic local GATT keep-alive handshakes so the monitor continues broadcasting while in range.
- **Storage:** Environmental readings (CO₂, PM2.5, PM10, temperature, humidity, battery percentage, and BLE RSSI) are stored locally in a fixed-size, 14-day circular ring buffer (`device_1.aqlring`) inside the app's private internal storage directory. When optional **`🛠️ Developer Mode`** is explicitly enabled in **Settings** (off by default), AndroidAQL also writes nearby BLE advertisement metadata (`BLE_ADV_NON_FDCD` MAC addresses, device names, RSSI, service UUIDs, and raw payloads) and raw monitor frames to the local `hw_bringup_trace.jsonl` diagnostic log for hardware debugging.

### B. Location & Connected Wi-Fi (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_WIFI_STATE`)
- **Prominent Disclosure:** AndroidAQL accesses location and connected Wi-Fi information in the foreground and background (while the foreground collector service is active) for two strictly local purposes:
  1. **Saved Wi-Fi Place Detection (Automatic GPS Off):** Android requires Location permission to read the connected Wi-Fi network SSID/BSSID. When your phone is connected to a user-configured "Saved Place" Wi-Fi network (such as *Home*), AndroidAQL automatically disables GPS collection to save battery and protect indoor privacy.
  2. **Field Excursion Movement Speed:** When your Qingping air quality monitor is actively broadcasting outside of a Saved Wi-Fi Place, AndroidAQL calculates instantaneous movement speed (meters per second / mph) and horizontal GPS accuracy (meters) alongside your air quality readings so you can distinguish transit segments from stationary indoor destinations on the Trip Slicer chart.
- **No Raw Coordinates Persisted:** By default, AndroidAQL converts GPS updates into scalar movement speed (m/s) and horizontal accuracy (m) in memory and **does not store raw latitude or longitude coordinates to disk**.

### C. Notifications (`POST_NOTIFICATIONS`)
- **Purpose:** Used to display the ongoing foreground collector status notification (showing live CO₂ and PM2.5 levels) and optional user-configurable high-CO₂, high-PM2.5, or low-battery alerts.

---

## 3. Data Retention, Sharing, and Deletion
- **Automatic Rolling Expiration:** High-resolution 15-second telemetry is stored in a constant-size 14-day ring buffer (`~1.55 MB`) that automatically overwrites slots older than 14 days, unless you explicitly **Pin** a trip or slice.
- **User-Initiated Exports:** You may optionally export trip summaries as CSV files or share a hardware diagnostic trace (JSONL) using the Android system share sheet. You control the recipient of any shared file.
- **Complete Deletion:** You can delete individual trips, slices, or Saved Wi-Fi Places inside the app at any time, or erase all local data immediately by clearing app storage or uninstalling AndroidAQL in Android Settings.

---

## 4. Contact
If you have questions about this Privacy Policy or AndroidAQL's privacy practices, please open an issue at:  
https://github.com/charles-dyfis-net/AndroidAQL
