# Privacy Policy for AirSlice: Portable Air Log (`net.dyfis.androidaql`)

**Effective Date:** November 25, 2026
**Developer:** Charles Duffy (`charles-dyfis-net` / `dyfis.net`)

## 1. Overview — 100% Local On-Device Operation
**AirSlice: Portable Air Log** (**AirSlice**) is an open-source, local-first Android application for recording, visualizing, slicing, and exporting environmental air quality telemetry (CO₂, PM2.5, PM10, temperature, and relative humidity) from a user-owned **Qingping Air Monitor Lite (`CGDN1`)** over Bluetooth Low Energy (BLE) strictly on-device.

- **No Cloud Backend & No Data "Collected" Off-Device:** Under Google Play's Data Safety definition, no user data is "collected" or shared off-device—all sensor, Wi-Fi, location, and step processing and storage occur strictly locally on your phone, and AirSlice does not connect to any cloud server, Qingping Cloud, Xiaomi Home, or remote API.
- **No Analytics, Telemetry, or Ads:** AirSlice contains zero analytics SDKs, crash-reporting trackers, advertising identifiers, or third-party data trackers.
- **No Automatic Data Transmission:** No data leaves your device unless you explicitly initiate a file export (XLSX workbook or diagnostic JSONL) via the Android system share sheet.

---

## 2. Permissions & Data Processed On-Device

### A. Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`)
- **Purpose:** Used to passively scan for Bluetooth Low Energy (`0xFDCD`, `0xFE95`, and `BTHome v2` `0xFCD2`) advertisements from your physical Qingping Air Monitor Lite (`CGDN1`) or compatible BTHome v2 monitor, and (when an unconfigured `CGDN1` is in Wi-Fi setup mode) to perform a one-shot offline local setup handshake so the monitor broadcasts continuously without cloud configuration.
- **Storage:** Environmental readings (CO₂, PM2.5, PM10, temperature, humidity, battery percentage, and BLE RSSI) are stored locally in a fixed-size, 14-day circular ring buffer (`device_1.aqlring`) inside the app's private internal storage directory. When optional **`🛠️ Developer Mode`** is explicitly enabled in **Settings** (off by default), AirSlice also writes nearby BLE advertisement metadata (`BLE_ADV_NON_FDCD` MAC addresses, device names, RSSI, service UUIDs, and raw payloads) and raw monitor frames to the local `hw_bringup_trace.jsonl` diagnostic log for hardware debugging.

### B. Location & Connected Wi-Fi (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_WIFI_STATE`)
- **Prominent Disclosure:** AirSlice accesses location and connected Wi-Fi information in the foreground and background (while the foreground service is active), and immediately discards almost all of that raw data in memory—retaining only scalar movement speed (m/s), horizontal GPS accuracy (m), and whether a user-configured Saved Place matched—for two strictly local on-device purposes:
  1. **Explicit User Activation & Saved Wi-Fi Place Detection (Automatic GPS Off):** Android requires Location permission to read the connected Wi-Fi network SSID/BSSID. Out of the box, GPS and step sensing remain disabled until you either configure a Saved Wi-Fi Place or explicitly tap **`🚶 Start Trip Manually`**—and whenever your phone is connected to a user-configured "Saved Place" Wi-Fi network (such as *Home* or *Office*), AirSlice automatically disables GPS and step polling both to save battery and because there is no need to track movement within your saved locations (non-matching Wi-Fi SSIDs and BSSIDs are immediately discarded).
  2. **Field Excursion Movement Speed:** When you explicitly tap **`🚶 Start Trip Manually`** or your Qingping air quality monitor is actively broadcasting outside of a configured Saved Wi-Fi Place, AirSlice calculates instantaneous movement speed (meters per second / mph) and horizontal GPS accuracy (meters) locally on-device alongside your air quality readings so you can distinguish transit segments from stationary destinations on the Trip Slicer chart.
- **No Raw Coordinates Persisted:** AirSlice converts each GPS update into scalar movement speed (m/s) and horizontal accuracy (m) in memory and **immediately discards raw latitude and longitude coordinates**, never storing them to disk.

### C. Physical Activity (`ACTIVITY_RECOGNITION`)
- **Purpose:** Used strictly on-device throughout active Field Excursions to read Android's hardware step detector / step counter so walking pace stays visible on the movement chart whenever GPS is unavailable, weak (> 50 m), or slower to respond than step cadence (for example, indoors, in urban canyons, during meandering walks, or before GPS anchor breakout). Automatically stopped at Saved Wi-Fi Places or when the monitor is out of range.
- **Storage & Privacy:** Step events remain strictly in a short-lived (~10-second) in-memory sliding window to estimate scalar walking speed (m/s). Ring-buffer and exported XLSX sample rows attributed to steps leave GPS accuracy unset (`gps_accuracy_m` blank), and cumulative step counts are never stored to disk, logged, or transmitted.

### D. Notifications (`POST_NOTIFICATIONS`)
- **Purpose:** Used to display the ongoing foreground collector status notification (showing live CO₂ and PM2.5 levels) and optional user-configurable high-CO₂, high-PM2.5, or low-battery alerts.

### E. Companion Device Presence & Auto-Resume (`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, `RECEIVE_BOOT_COMPLETED`)
- **Purpose:** Strictly optional and initiated only when you choose to link your monitor under **Settings → Background Logging → Auto-Resume & Battery Savings**. Allows Android to wake `AirSlice` when your linked monitor re-enters Bluetooth range (so `AirSlice` can sleep without a persistent notification while the monitor is away) and to resume background monitoring after device reboots or app updates unless you have explicitly stopped the service.
- **Storage & Privacy:** Association metadata is managed locally by Android's `CompanionDeviceManager` on-device and can be removed at any time by tapping **Unlink Monitor** in Settings. No device identifiers or presence events are sent off-device.

---

## 3. Data Retention, Sharing, and Deletion
- **Automatic Rolling Expiration:** High-resolution 15-second telemetry is stored in a constant-size 14-day ring buffer that automatically overwrites slots older than 14 days, unless you explicitly **Pin** a trip.
- **User-Initiated Exports:** You may optionally export trip or segment workbooks as XLSX files or share a hardware diagnostic trace (JSONL) using the Android system share sheet. You control the recipient of any shared file.
- **Complete Deletion:** You can delete individual trips, slices, or Saved Wi-Fi Places inside the app at any time, or erase all local data immediately by clearing app storage or uninstalling AirSlice in Android Settings.

---

## 4. Contact
If you have questions about this Privacy Policy or AirSlice's privacy practices, please open an issue at:
https://github.com/charles-dyfis-net/AndroidAQL
