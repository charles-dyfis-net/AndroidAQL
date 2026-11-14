# Physical Hardware Observations: Qingping Air Monitor Lite (`CGDN1`)

This document records empirical findings gathered during physical bring-up of a retail **Qingping Air Monitor Lite (`CGDN1`)** paired with a **Google Pixel 6a** (running Android 17 / API 37) via [`AqlCollectorService.kt`](../app/src/main/java/io/github/androidaql/service/AqlCollectorService.kt) and [`HardwareDiagnosticLogger.kt`](../app/src/main/java/io/github/androidaql/util/HardwareDiagnosticLogger.kt).

---

## 1. Hardware & Firmware Identification

Queried over BLE GATT Services `0x1800` (Generic Access), `0x180A` (Device Information), and `0xFE95` (Xiaomi MiBeacon) on `58:2D:34:12:AB:BC`, and cross-referenced against static reverse-engineering of the stock `4.8.5` application image (`CGDN1_ESP_4.8.5_*.bin`):

| Field | GATT Service / Characteristic | Raw Hex | Decoded Value |
| :--- | :--- | :--- | :--- |
| **Device Name** | `0x1800` → `0x2A00` | `51696E6770696E6720416972204D6F6E69746F72204C697465` | `"Qingping Air Monitor Lite"` |
| **Appearance** | `0x1800` → `0x2A01` | `0000` | `0x0000` (Unknown) |
| **Central Address Resolution** | `0x1800` → `0x2AA6` | `00` | `0` (Not supported) |
| **Manufacturer Name** | `0x180A` → `0x2A29` | `51696E6770696E6720546563686E6F6C6F677920284265696A696E672920436F2E2C204C74642E` | `"Qingping Technology (Beijing) Co., Ltd."` |
| **Serial Number** | `0x180A` → `0x2A25` | `<16-byte ASCII serial>` | `"SXXXXXXXXXXXXXXX"` (redacted) |
| **Hardware Revision** | `0x180A` → `0x2A27` | `30303031` | `"0001"` |
| **Firmware Revision** | `0x180A` → `0x2A26` | `342E382E35` | `"4.8.5"` |
| **MiBeacon Stack Version** | `0xFE95` → `0x0004` | `312E302E305F3030383800006F70636F64655F73` | `"1.0.0_0088\x00\x00opcode_s"` |
| **`0xFDCD` Product ID** | `0xFDCD` Service Data Byte `1` | `24` | `0x24` (`CGDN1` v2 hardware revision; older revision was `0x0E`) |
| **`0xFE95` Product ID** | `0xFE95` Service Data Bytes `2..3` | `3D0C` | `0x0C3D` (`3133` = Xiaomi MiBeacon ID for `CGDN1`) |

### 1.1 Internal SoC, Peripherals & Sensor Map (Confirmed from Stock `4.8.5` Static RE)
* **SoC & Flash:** Single **ESP32-WROVER-B** module ("Dany-ESP" board) with **4 MB DIO flash @ 80 MHz**, running ESP-IDF 4.0.3 FreeRTOS tasks (no separate BLE coprocessor; both Wi-Fi/MQTT and the dual-stack HomeKit/MiIO BLE provisioning server run on the ESP32).
* **I2C Bus 0 (`SDA=GPIO26`, `SCL=GPIO27`, 20 kHz):**
  * `0x62`: **Sensirion SCD40** photoacoustic NDIR CO₂ sensor.
  * `0x12`: **Plantower PMSA003I** laser particulate sensor (measures PM1.0, PM2.5, and PM10; stock firmware only displays and broadcasts PM2.5 and PM10).
  * `0x44`: **Sensirion SHT4x** temperature & relative humidity sensor.
  * `0x10`: **Apple MFi authentication coprocessor** (used by stock HomeKit pairing).
* **I2C Bus 1 (`SDA=GPIO12`, `SCL=GPIO14`, 400 kHz):**
  * `0x0C`: **ATtiny814** capacitive touch-slider controller (`GPIO32` active-low data-ready interrupt).
* **Power & Physical Controls:**
  * `GPIO36`: Physical **back button** (active low; short click toggles display, holding `>= 2 s` on battery releases the `GPIO23` power-hold latch to power off; has no BLE/pairing role).
  * `GPIO39` (`ADC1 ch3`) & `GPIO35` (`ADC1 ch7`): Battery voltage divider and charger current sense, polled by stock `Battery_Task` (`FUN_400f1c90`).

---

## 2. Touch-Slider Network-Reset Gesture & BLE Broadcast Lifecycle

### 2.1 Out-of-the-Box BLE Silence & the 7.2-Second Touch-Slider Network-Reset Trigger
1. **BLE's Role in Stock Firmware `4.8.5`:** In stock firmware, day-to-day sensor telemetry is designed to be published over **Wi-Fi / MQTT** (`type 13` message roughly once per minute). The stock BLE stack exists **solely for HomeKit and MiIO provisioning/pairing**, which is why an unpaired `CGDN1` is completely BLE-silent upon initial power-on.
2. **Exact Touch-Slider Gesture (`7.2 s` Hold → "Trigger Network-Reset Operation"):**
   * Stock's `Touch_Task` (`FUN_400e2268`) samples the ATtiny814 touch slider at 100 Hz (`10 ms`). Holding a still finger (`max - min <= 37` slider units) while `FUN_400ea1ac` counts `40 × 180 ms` ticks (**`7.2 seconds`**) fires stock's **"触发重置网络操作" (*trigger network-reset operation*)**.
   * This network-reset operation switches the OLED from its normal sensor-paging UI (which intentionally has **no** fixed Wi-Fi or battery status overlay in order to prevent OLED burn-in) into a **full-screen Wi-Fi setup / provisioning mode**, and simultaneously starts the dual-MAC BLE advertisement window (`0xFDCD` on `:BA` and `0xFE95` on `:BC`).

### 2.2 Three Distinct BLE Broadcast Timers on Stock Firmware `4.8.5`
Across passive and active GATT bring-up traces, we measured three distinct timers governing how long the stock `4.8.5` BLE stack continues broadcasting after the 7.2-second touch-slider gesture:

1. **Timer 0 — The ~60-Second (`58.98 s`) Unconnected Initial Window:**
   * If no BLE central connects to the GATT server on `:BC` after the 7.2-second touch-slider wake, `:BA` and `:BC` broadcast for **`58.975 seconds` (`121` frames)** and then shut off completely.
2. **Timer 1 — The 10-Minute (`609.8 s` = `10m 09.8s`) Post-GATT Inactivity Shutdown Timer:**
   * Once a BLE central connects to `:BC` and performs a valid custom command write on service `22210000-...` (such as `05 09 <epoch_le32>` `RTC_TIME_SYNC` or `11 01 <16B token>`), the ~60-second timer is replaced by a **`609.8 s` (`10m 09.8s`)** post-GATT inactivity timer counted from the end of the last GATT session.
   * When `609.8 s` elapses after the last GATT disconnect without a qualifying write (`610.77 s` in Run 1; `609.76 s` in Run 2), `:BA` emits a single final `0xFDCD` frame with `frame_ctrl = 0x08` and all-zero sensor values (`0824BAAB12342D5801040000000012040000000013020000`, logged as `BLE_ADV_FDCD_SHUTDOWN_ZERO`) and powers down the BLE stack.
3. **Timer 2 — The Hard 30-Minute (`1,797.0 s` = `29m 57.0s`) Continuous `0x0A` Broadcast Ceiling:**
   * Independently of the 10-minute post-GATT timer, stock `4.8.5` enforces a hard **`1,797.0 s` (`29 minutes 57.0 seconds`)** ceiling on continuous `0x0A` (`0xFDCD`) and `0xFE95` broadcasting from the start of a provisioning broadcast window:

| Run | Start of `0x0A` Window | Last GATT Session Before Silence | End of Continuous `0x0A` + `0xFE95` Advs | Elapsed `0x0A` Broadcast Duration | Final `0824...0000` (`SHUTDOWN_ZERO`) | Elapsed After Last GATT Session |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Run 1** *(Night run)* | `23:34:21.955` *(after `11 01` soft-reset)* | `23:59:33.912` *(Read `0x2A26`)* | **`00:04:18.934`** | **`1,796.98 s` (`29m 56.98s`)** | `00:09:44.686` | **`610.77 s` (`10m 10.77s`)** |
| **Run 2** *(Morning `0509_ONLY`)* | `06:24:56.967` *(touch-slider wake)* | `06:54:26.955` *(`05 09` → `04FF090000`)* | **`06:54:54.001`** | **`1,797.03 s` (`29m 57.03s`)** | `07:04:36.716` | **`609.76 s` (`10m 09.76s`)** |

   * **Key Implication:** Writing `05 09 <epoch_le32>` (`RTC_TIME_SYNC`) every 3.5 minutes resets **Timer 1** (the 10-minute post-GATT inactivity timer) without touching the provisioning state machine or the OLED UI, **but `05 09` alone does NOT reset Timer 2** (the 30-minute / `1,797.0 s` broadcast ceiling). In Run 2, nine consecutive `05 09` sessions succeeded (`04 FF 09 00 00`), yet continuous `0x0A` advertisements stopped at `06:54:54.001` (`29m 57.03s` after wake, and just 27 seconds after Session #5 succeeded at `06:54:26.955`), while Timer 1 continued ticking in the background for another `9m 42.7s` until emitting `0824...0000` at `07:04:36.716` (`609.76s` after `06:54:26.955`).

---

## 3. Dual-MAC Radio Architecture (`MAC` vs. `MAC + 2`)

The `CGDN1` (`Firmware 4.8.5`) transmits simultaneously from **two distinct Bluetooth MAC addresses** offset by `+2` in the final octet:

### 3.1 `58:2D:34:12:AB:BA` (`MAC` — `0xFDCD` Telemetry Beacon)
* **Role:** Broadcasts unencrypted **Qingping `0xFDCD` Service Data** containing live environmental sensor readings.
* **Connectability:** **Non-connectable** (or rejects GATT connections with Android GATT status `147` / `0x93` timeout after 30s if `connectGatt` is attempted on `:BA`).
* **Cadence:** Bursts of 2–3 advertisements spaced **`~315–350 ms`** apart, repeating every **`~1.0–1.7 seconds`**.
* **Complete 31-Byte BLE Advertisement PDU:**
  ```text
  02 01 06                                         <-- AD Type 0x01 (Flags, 3 bytes)
  1B 16 CD FD                                      <-- AD Type 0x16 (16-bit Service Data 0xFDCD, 28 bytes total)
     0A 24 BA AB 12 34 2D 58                       <-- 8-byte Header: Ctrl=0x0A, PID=0x24, Reversed MAC (:BA)
     01 04 E7 00 A8 01                             <-- TLV 0x01 (Len 4): Temp = 23.1°C (0x00E7), RH = 42.4% (0x01A8)
     12 04 01 00 01 00                             <-- TLV 0x12 (Len 4): PM2.5 = 1 µg/m³, PM10 = 1 µg/m³
     13 02 9B 02                                   <-- TLV 0x13 (Len 2): CO2 = 667 ppm (0x029B)
  ```

### 3.2 `58:2D:34:12:AB:BC` (`MAC + 2` — `0xFE95` MiBeacon & Connectable GATT Server)
* **Role:** Broadcasts **Xiaomi MiBeacon (`0xFE95`)** discovery packets with the Complete Local Name `"Qingping Air Monitor Lite"` and hosts the **connectable BLE GATT server**.
* **Cross-Reference to Telemetry MAC:** Notice that the last two bytes of the `0xFE95` Service Data (`BA AB`) embed the lower 16 bits of the companion `:BA` telemetry MAC address!
* **Raw Advertisement + Scan Response Payload:**
  ```text
  02 01 06                                         <-- AD Type 0x01 (Flags)
  11 16 95 FE 30 58 3D 0C 43 BC AB 12 34 2D 58 1D BA AB
                                                   <-- AD Type 0x16 (Service Data 0xFE95):
                                                       FrameCtrl = 0x5830
                                                       ProductID = 0x0C3D (3133 = CGDN1)
                                                       FrameCnt  = 0x43 (increments 0x00..0xFF)
                                                       MAC       = BC:AB:12:34:2D:58 (Reversed :BC)
                                                       Cap/Extra = 1D BA AB (references :BA)
  1A 09 51 69 6E 67 70 69 6E 67 20 41 69 72 20 4D 6F 6E 69 74 6F 72 20 4C 69 74 65
                                                   <-- AD Type 0x09 (Complete Local Name):
                                                       "Qingping Air Monitor Lite"
  ```

---

## 4. Battery (`TLV 0x02`) Absence, Display Modes & BLE Self-Heating

### 4.1 Why Battery (`TLV 0x02`) Is Absent from `0xFDCD` Broadcasts
We tested the physical `CGDN1` both while plugged into USB-C external wall power and after unplugging it to run on its internal `2,000 mAh` Li-ion battery. While static reverse-engineering of stock `4.8.5` confirms an on-device `Battery_Task` (`FUN_400f1c90`, reading battery voltage via `ADC1` on `GPIO39` and charge current on `GPIO35`), every `0xFDCD` broadcast in both power states contained identical TLV keys (`0x01`, `0x12`, `0x13`) and omitted `TLV 0x02` (Battery).

Standard BLE 4.x Legacy Advertisement PDUs (`ADV_IND` / `ADV_NONCONN_IND`) have a strict hardware/protocol ceiling of **`31 bytes`**:

| Component | Byte Count | Hex Example |
| :--- | :---: | :--- |
| AD Flags (`0x01`) | `3 B` | `02 01 06` |
| Service Data Header (`0x16` + `0xFDCD`) | `4 B` | `1B 16 CD FD` |
| Qingping Frame Header (`Ctrl` + `PID` + `MAC`) | `8 B` | `0A 24 BA AB 12 34 2D 58` |
| TLV `0x01` (Temperature + Humidity) | `6 B` | `01 04 E7 00 A8 01` |
| TLV `0x12` (PM2.5 + PM10) | `6 B` | `12 04 01 00 01 00` |
| TLV `0x13` (CO₂ ppm) | `4 B` | `13 02 9B 02` |
| **Total Legacy Advertisement PDU Size** | **`31 B` / `31 B`** | **100% Full (`0 bytes` free)** |

Because `CGDN1` packs five environmental metrics (Temp, RH, PM2.5, PM10, CO₂) into every `0xFDCD` packet (`24 bytes` of Service Data + `4 bytes` AD header + `3 bytes` Flags = `31 bytes`), there are zero bytes free in the primary 31-byte legacy advertisement PDU for the 3-byte Battery TLV (`02 01 <pct>`).

### 4.2 Uncompensated `+1.6 °C` BLE Self-Heating on Stock Firmware `4.8.5`
Static analysis of stock `4.8.5`'s temperature compensation task (`Sensor_Temp_Humi_Temp_Fix_Task`, `FUN_400e8d9c`) and thermal step-response measurements on the `CGDN1` hardware reveal an important thermal side-effect of long-lived BLE sessions:
* The internal Sensirion SHT4x (`0x44`) sits adjacent to the ESP32 and OLED heat sources. Stock `4.8.5` compensates for power-on warmup (`PREHEAT`), battery charging (`CHARGING`/`CHGDONE`), and OLED display brightness (`-1.0 °C` full ramp), **but has no compensation term for BLE radio activity**, because stock assumes BLE is only active briefly during provisioning.
* Keeping the ESP32's BLE stack active continuously adds a measured **`+1.6 °C`** self-heating offset to the SHT4x reading with a **`~1,070 s` (`~18 min`)** thermal lag constant (causing reported temperature to drift warm by up to `+1.6 °C` and relative humidity to drift correspondingly lower over the first 20–30 minutes of continuous BLE operation).

---

## 5. Complete GATT Service & Characteristic Map (`58:2D:34:12:AB:BC`)

Connecting to `58:2D:34:12:AB:BC` exposes **5 GATT Services** (note that standard BLE Battery Service `0x180F` is **not** present):

1. **`00001801-0000-1000-8000-00805f9b34fb` (Generic Attribute)**
   * `00002a05-0000-1000-8000-00805f9b34fb` (`props=0x20` Indicate, CCCD `0x2902`)
2. **`00001800-0000-1000-8000-00805f9b34fb` (Generic Access)**
   * `00002a00-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"Qingping Air Monitor Lite"`
   * `00002a01-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `0000`
   * `00002aa6-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `00`
3. **`0000180a-0000-1000-8000-00805f9b34fb` (Device Information)**
   * `00002a29-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"Qingping Technology (Beijing) Co., Ltd."`
   * `00002a24-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → Model Number (`"CGDN1"`)
   * `00002a25-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"SXXXXXXXXXXXXXXX"` (16-char unit serial, redacted)
   * `00002a27-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"0001"`
   * `00002a26-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"4.8.5"`
4. **`0000fe95-0000-1000-8000-00805f9b34fb` (Xiaomi MiBeacon Service)**
   * `00000001-0000-1000-8000-00805f9b34fb` (`props=0x18` Write/Notify, CCCD `0x2902`)
   * `00000002-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `""` (empty)
   * `00000004-0000-1000-8000-00805f9b34fb` (`props=0x02` Read) → `"1.0.0_0088\x00\x00opcode_s"`
   * `00000005-0000-1000-8000-00805f9b34fb` (`props=0x18` Write/Notify, CCCD `0x2902`)
   * `00000010-0000-1000-8000-00805f9b34fb` (`props=0x14` WriteNoResp/Notify, CCCD `0x2902`)
   * `00000019-0000-1000-8000-00805f9b34fb` (`props=0x14` WriteNoResp/Notify, CCCD `0x2902`)
5. **`22210000-554a-4546-5542-46534450464d` (Qingping Custom Service)**
   Organized as three Write (`0x0C`) + Notify (`0x10`) command/response channel pairs:
   * **Pair A:** `00000001-...` (Write) ↔ `00000002-...` (Notify, CCCD `0x2902`)
   * **Pair B:** `0000000d-...` (Write) ↔ `0000000e-...` (Notify, CCCD `0x2902`)
   * **Pair C:** `00000017-...` (Write) ↔ `00000018-...` (Notify, CCCD `0x2902`)

### 5.1 Custom GATT Command-Response Probe Results
When subscribed to `0002` and `000e` notifications, writing unauthenticated length-prefixed command frames (`<len> <cmd> ...`) to `0001` or `000d` elicits immediate 5-byte response notifications of the form `04 FF <cmd> <status_lo> <status_hi>`:

| Write Characteristic | Probe Payload Written | Notify Characteristic | Notification Received | Interpretation |
| :--- | :--- | :--- | :--- | :--- |
| `00000001-...` | `01 02` (Len 1, Cmd `0x02`) | `00000002-...` | `04 FF 02 01 00` | ACK (`0xFF`) for Cmd `0x02`, status `0x0001` (Auth required / unbound) |
| `00000001-...` | `01 00` (Len 1, Cmd `0x00`) | `00000002-...` | `04 FF 00 02 00` | ACK (`0xFF`) for Cmd `0x00`, status `0x0002` (Invalid/unsupported opcode) |
| `00000001-...` | `01 0F` (Len 1, Cmd `0x0F`) | `00000002-...` | `04 FF 0F 02 00` | ACK (`0xFF`) for Cmd `0x0F`, status `0x0002` (Invalid/unsupported opcode) |
| `0000000d-...` | `01 02` (Len 1, Cmd `0x02`) | `0000000e-...` | `04 FF 02 01 00` | ACK (`0xFF`) for Cmd `0x02`, status `0x0001` (Auth required / unbound) |
| `0000000d-...` | `01 00` (Len 1, Cmd `0x00`) | `0000000e-...` | `04 FF 00 02 00` | ACK (`0xFF`) for Cmd `0x00`, status `0x0002` (Invalid/unsupported opcode) |

### 5.2 Important Implementation Lesson: Payload MAC (`:BA`) vs. Over-the-Air BLE Address (`:BC`)
Inside the `0xFDCD` service data payload (bytes `2..7`), the firmware embeds the ESP32's base Wi-Fi MAC (`:BA`). However, the actual over-the-air BLE transmitter address (`ScanResult.getDevice().getAddress()`) for **both** `0xFDCD` and `0xFE95` advertisements is already the Bluetooth MAC (`:BC`, i.e., `Wi-Fi MAC + 2`). Never add `+ 2` to `ScanResult.getDevice().getAddress()`, or `:BC` will be incremented to a non-existent `:BE` address (`status = 147`). Only add `+ 2` if deriving the BLE address from the 6-byte `:BA` ID inside the `0xFDCD` payload.

### 5.3 Token Bonding (`11 01` + 16B Token) & Android 17 Unfiltered Scan Demotion
1. **Qingping Token Bonding (`11 01` + 16B Token & `11 02` + 16B Token on `0001`):**
   * Cross-referencing our empirical `04 FF <cmd> <status_lo> <status_hi>` notifications against [`MrBoombastic/clOwOck`](https://github.com/MrBoombastic/clOwOck), [`rjocoleman/qingping-cgd1`](https://github.com/rjocoleman/qingping-cgd1), and [`bitrate16/qingping`](https://github.com/bitrate16/qingping) confirms that `22210000-554a-4546-5542-46534450464d` uses the length-prefixed Qingping provisioning protocol:
     * **Auth Init / Token Bond (`11 01 [16-byte token]` on `0001`):** Enters the provisioning/binding flow (`frame_ctrl` flips `0x0A -> 0x8A`, switching the OLED to the full-screen Wi-Fi setup screen) and registers the 16-byte token (`04 FF 01 00 00` = `BOND_OK`).
     * **Auth Confirm / Verify Token (`11 02 [16-byte token]` on `0001`):** Verifies the session token (`04 FF 02 00 00` = `AUTH_OK` when sent after `11 01` in the same session; `04 FF 02 01 00` = `UNAUTHORIZED` if sent in a later session after disconnecting without completing Wi-Fi setup).
     * **Time Sync (`05 09 [4-byte epoch sec LE]` on `0001`):** Synchronizes the monitor's internal RTC (`04 FF 09 00 00` = `OK`, accepted without prior `11 01` / `11 02` authentication).
2. **Android 17 (`BtScan.ScanManager`) Unfiltered Scan Demotion & Shutdown Packet (`0x08` All-Zero):**
   * **Unfiltered Scan Timeout (`5m 00s`):** On Pixel 6a (Android 17), an unfiltered `BluetoothLeScanner.startScan(emptyList(), ...)` is automatically demoted after ~5 minutes (`W BtScan.ScanManager: regularScanTimeout ... Moving unfiltered scan to opportunistic scan`), halting all BLE callbacks unless hardware `ScanFilter`s (`0xFDCD`, `0xFE95`, device name `"Qingping Air Monitor Lite"`) are supplied and/or the scan watchdog restarts stalled scans.
   * **Pairing-Mode Exit / Shutdown Frame (`frame_ctrl = 0x08`, all-zero metrics):** When the `CGDN1`'s 10-minute (`609.8 s`) post-GATT timer expires, it emits a single final `0xFDCD` frame with `frame_ctrl = 0x08` (bit `0x02` binding flag cleared) and all-zero sensor payloads (`0824BAAB12342D5801040000000012040000000013020000`) immediately before turning off its BLE stack.

### 5.4 Forensic Comparison of GATT Operations Against Timer 1 (`609.8 s`) & Timer 2 (`1,797.0 s`)
Comparing GATT keep-alive strategies across multi-hour runs established four empirical rules for `CGDN1` stock firmware `4.8.5`:

1. **`01 07` on `000d` Triggers a 2.4 GHz Wi-Fi AP Scan (`13 07 <total> <seq> <ASCII>` on `000e`):**
   * Roughly 3 seconds after receiving `01 07` on `000d` following `11 01` / `11 02`, the `CGDN1` streams 21–32 back-to-back 20-byte notifications on `0000000e-...` with opcode `0x07`, containing a tab-delimited list of nearby 2.4 GHz Wi-Fi networks (`"<SSID>",<security_type>,<rssi_dbm>\t`).
2. **Read-Only GATT (`0x2A26` on `0x180A`) Resets Neither Timer:**
   * Reading standard GATT characteristics without writing to `22210000-...` does not reset even the 10-minute (`609.8 s`) post-GATT timer.
3. **Unauthenticated `05 09 <4B Unix epoch LE>` (`RTC Time Sync`) Resets Timer 1 (`609.8 s`) Quietly, But Does NOT Reset Timer 2 (`1,797.0 s`):**
   * Writing `05 09 <4B epoch LE>` to `00000001-...` returns `04 FF 09 00 00` (`Status = 0x00 = OK`) without requiring `11 01` or `11 02`, and without switching the OLED display to the full-screen Wi-Fi setup mode.
   * However, because `05 09` does not re-enter or restart the provisioning state machine, continuous `0x0A` (`0xFDCD`) and `0xFE95` advertisements still hit the hard **`1,797.0 s` (`29m 57.0s`)** broadcast ceiling (`Timer 2`).
4. **`11 01 <16B token> + 11 02 <16B token>` Resets Both Timer 1 (`609.8 s`) AND Timer 2 (`1,797.0 s`) via a 4.6-Second Post-Disconnect Soft-Reset (at the Cost of Entering the Full-Screen Wi-Fi UI):**
   * Writing `11 01 <16B token>` (`04 FF 01 00 00`) + `11 02 <16B token>` (`04 FF 02 00 00`) enters the provisioning state machine (`frame_ctrl` flips `0x0A -> 0x8A`, switching the OLED to the full-screen Wi-Fi setup screen).
   * When that GATT session disconnects without completing Wi-Fi provisioning, `:BA` aborts out of `0x8A` (`0x8A -> 0x08 -> 0x0A` ~4.6 seconds later, with `:BC` `FrameCnt` resetting to `0` and 1 frame of `co2_ppm = 0` NDIR warmup) and **restarts a fresh 30-minute (`1,797.0 s`) broadcast window from zero** without any physical touch-slider press (verified across three consecutive 24-minute renewals for 83+ minutes).
5. **Distinguishing Firmware BLE Broadcast Cutoffs (`10m` / `30m`) from Unplugged Battery Shutdown (`~1.5–3 Hours`):**
   * A BLE-only radio soft-reset (while the monitor remains powered) produces at most **1 frame (`~0.3 s`)** of `co2_ppm = 0` (`BLE_WARMUP_ZERO_CO2`), because the STM32 host and SenseAir S8 NDIR lamp never lost power.
   * By contrast, running unplugged on internal battery for `~1h 46m` (`08:52 -> 10:38:01`) resulted in a full device power-off: when USB-C power was restored at `10:50:36`, the monitor emitted **11+ seconds (`35+ frames`, `10:50:36 -> 10:50:47`)** of `BLE_WARMUP_ZERO_CO2` (`co2_ppm = 0, pm25 = 0, pm10 = 0`) while the SenseAir S8 NDIR lamp and PM2008H laser fan cold-booted.
   * Because an unplugged battery power-off looks identical to a BLE broadcast cutoff until the device is woken up again, multi-hour tests and extended field excursions (such as clinic/hospital visits) should run with an external USB-C power bank attached.

---

## 6. Hardware Bring-Up & Forensic Debugging Playbook

This section documents how to run hypothesis tests and inspect the on-device app-private diagnostic trace (`hw_bringup_trace.jsonl` inside the Android app's internal `Context.getFilesDir()` sandbox, pulled into the local gitignored `tmp/` directory during debugging) without needing to rebuild/reinstall the APK.

### 6.0 Developer Mode and Security / Privacy Surface

Developer diagnostics and synthetic demo controls are hidden behind **`⚙️ Settings → Diagnostics & Support → 🛠️ Developer Mode (Show HW Test Tab & Demo Controls)`** (`developer_mode` in `aql_ui_prefs`, **off by default**).

* **UI & Demo Controls (brief):** Enabling Developer Mode renumbers the bottom tabs (`1. Now`, `2. Trips`), reveals the **`3. HW Test`** diagnostic tab (keep-alive experiment controls, BLE scan filter toggle, GATT inspector, raw frame console, JSONL export), and exposes demo/simulation shortcuts (`🎭 Toggle Live 3s Demo BLE Stream (Emulator)` on **Now**, **`Simulate`** buttons next to Saved Places in **⚙️ Settings**, and **`🔄 Reload 75-min Demo Chicago Trip`** in **Trips → Select Trip**). Disabling it hides those controls and returns to **Now** if `3. HW Test` was active.
* **Security & Privacy Impact 1 — Persistent Logging of Nearby BLE Devices (`files/hw_bringup_trace.jsonl`):**
  * While Developer Mode is **off**, `AqlCollectorService` skips writing high-volume BLE advertisement frame events (`BLE_ADV_FDCD`, `BLE_ADV_FDCD_UNPARSED`, `BLE_ADV_NON_FDCD`) to disk.
  * When Developer Mode is **enabled**, `AqlCollectorService` logs all three event types into the rotating `5 MB` × 2 diagnostic files (`hw_bringup_trace.jsonl` / `.prev`). In particular, **`BLE_ADV_NON_FDCD`** captures nearby third-party BLE devices once every 15 seconds per MAC—persisting each device's **`mac`**, **`device_name`**, **`rssi_dbm`**, MiBeacon header fields (`fe95_cnt` / `fe95_ctrl`), **`service_uuids`**, **`service_data_uuids`**, and full **`raw_hex`** advertisement payload. Because these logs remain on disk across restarts and are included when sharing diagnostics (`📤 Share Diagnostic Log (JSONL)` / `📤 Share / Export HW Bring-Up JSONL Trace`), disable Developer Mode after debugging and treat exported JSONL files as containing nearby BLE device identifiers.
* **Security & Privacy Impact 2 — External `DEV_COMMAND` Control Surface on Release Builds (`net.dyfis.androidaql.DEV_COMMAND`):**
  * [`MainActivity`](../app/src/main/java/io/github/androidaql/ui/MainActivity.kt) is declared `android:exported="true"` in [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) so Android launchers can open the app, and forwards incoming `net.dyfis.androidaql.DEV_COMMAND` intents to [`AqlCollectorService.handleDevCommandIntent`](../app/src/main/java/io/github/androidaql/service/AqlCollectorService.kt) whenever `isDevCommandAuthorized(context)` returns `true` (`FLAG_DEBUGGABLE` is set **or** Developer Mode is enabled).
  * On **release builds with Developer Mode off**, incoming `DEV_COMMAND` intents are ignored.
  * When **Developer Mode is enabled on a release build** (or on any debug build), **any local process or `adb` caller able to launch `MainActivity`** can dispatch `DEV_COMMAND` extras to queue Pair B `cmd 0x03` HTTP firmware OTA URLs (`ota_url`, `force_ota`), push Pair B Wi-Fi credentials (`wifi_ssid`, `wifi_password`), execute arbitrary raw GATT command scripts (`gatt_script`), trigger immediate GATT pokes (`poke_now`), alter keep-alive strategy/intervals/deduplication (`strategy`, `interval_sec`, `retry_sec`, `trig_fe95`, `dedup_fdcd`), or append trace notes (`note`). Turn Developer Mode back **off** once hardware bring-up or provisioning is complete.

### 6.1 One-Command Trace Pull & Forensic Summary (`scripts/analyze-hw-trace`)
The repository includes a zero-dependency Python 3 CLI tool, [`scripts/analyze-hw-trace`](../scripts/analyze-hw-trace), which automatically pulls both on-device trace files (`files/hw_bringup_trace.jsonl.prev` if rotated and `files/hw_bringup_trace.jsonl`) via `adb exec-out run-as net.dyfis.androidaql`, saves the merged output into the local gitignored `tmp/hw_bringup_trace_merged.jsonl`, and prints four structured tables:
1. **Configuration Epochs, Service Starts & Observation Markers (`SERVICE_START`, `DEV_CONFIG_STATE`, `DEV_CONFIG_CHANGED`, `DEV_USER_NOTE`)**
2. **GATT Keep-Alive Sessions (`GATT_SESSION_SUMMARY`)** with duration, connection/disconnect status, hex payloads written (`0509...`, `1102`, `1101`), and notifications received (`04FF090000`).
3. **Monitor Radio Resets (`BLE_FE95_FRAME_CNT_RESET`), NDIR Sensor Warmups (`BLE_WARMUP_ZERO_CO2`), & Shutdown Frames (`BLE_ADV_FDCD_SHUTDOWN_ZERO`)**
4. **Telemetry Broadcast Gaps (`>= 10s`)** with before/after timestamps and `frame_ctrl_hex` transitions.

```sh
# Pull live trace from connected phone and print full forensic summary:
./scripts/analyze-hw-trace --pull

# Filter analysis to a single keep-alive strategy or configuration epoch:
./scripts/analyze-hw-trace --pull --strategy 0509_ONLY
./scripts/analyze-hw-trace --pull --epoch 3
```

### 6.2 User-Accessible & Developer Keep-Alive Controls (Zero-Restart Switching)
End users can select the keep-alive mode directly in **`⚙️ Settings → Monitor Keep-Alive Mode (>30m Unplugged)`** (`AUTO_OFFLINE_SETUP` default or `NONE_PASSIVE`), and the active mode is displayed on the **Now** tab status pill (e.g., `BLE: Auto (Offline 0x88 Setup)`). Developers (when Developer Mode is enabled, or on debug builds) can also switch diagnostic strategies (`0509_24M_1101`, `0509_ONLY`), intervals, and trace deduplication on the fly from **`3. HW Test`** (`🧪 Keep-Alive Hypothesis & Trace Experiment Controls`) or from a terminal via `scripts/analyze-hw-trace` (`net.dyfis.androidaql.DEV_COMMAND`):

| Strategy ID | Availability | GATT Operations Performed on `:BC` (`MAC + 2`) | Behavior |
| :--- | :--- | :--- | :--- |
| **`AUTO_OFFLINE_SETUP`** *(Default; alias `AUTO_EXCURSION_1101`)* | End Users + Dev | When stock firmware is in `0x0A`/`0x8A` Wi-Fi setup mode: `11 01` + `11 02` + `05 09` + Pair B `01 08` + `127.0.0.1` MQTT placeholder (`~5s`). Once `0x88` or BTHome v2 is active: 100% passive. | **Default production mode:** Automatically restores the normal anti-burn-in CO₂ OLED screen and continuous `0x88` BLE telemetry without Wi-Fi or cloud pairing. |
| **`NONE_PASSIVE`** | End Users + Dev | Never initiates GATT connections | Pure passive scanning for custom BTHome v2 firmware, external hubs, or USB-powered setups. |
| **`0509_24M_1101`** | Developer Mode (`3. HW Test` / CLI) | Write `05 09` every interval (`210s`), plus `11 01 <16B token> + 11 02 <16B token>` every **24 minutes** | Diagnostic baseline forcing 24-minute `11 01` window resets unconditionally (places static Wi-Fi icon on OLED on each renewal). |
| **`0509_ONLY`** | Developer Mode (`3. HW Test` / CLI) | Enable `0002` CCCD + write `05 09 <4B epoch LE>` (`RTC Time Sync`) | Diagnostic baseline refreshing the 10-minute timer up to the 30-minute (`1,797.0s`) `0x0A` ceiling without touching the OLED UI. |

```sh
# Switch live device to 0509_24M_1101 and trigger an immediate keep-alive poke:
./scripts/analyze-hw-trace --set-strategy 0509_24M_1101 --set-interval 210 --poke-now

# Record a timestamped physical observation marker directly into hw_bringup_trace.jsonl:
./scripts/analyze-hw-trace --note "Monitor OLED: Normal CO2 anti-burn-in UI"
```

### 6.3 Compact JSONL Schema & Rotation Design (`5 MB` × 2 Files)
Because the `CGDN1` transmits `0xFDCD` packets every `~350 ms` (`~250,000` packets/day), logging every identical 350 ms packet verbatim would rotate the 5 MB trace file every ~90 minutes. To preserve **multi-day forensic history** inside the `5 MB` cap (`hw_bringup_trace.jsonl` + `hw_bringup_trace.jsonl.prev`):
1. **Lossless 350 ms Deduplication (`dedup_fdcd = true` by default):**
   * `BLE_ADV_FDCD` is written immediately whenever `raw_hex` changes (any sensor value or `frameControl` bit changes), whenever `delta_ms >= 3000`, whenever `isWarmupZeroCo2` is true, or every **15 seconds** as a heartbeat.
   * When identical 350 ms packets were coalesced between logged lines, `"rep": N` records the exact count of identical packets received since the previous logged `BLE_ADV_FDCD` line. (Unchecking `Compact Trace` on `3. HW Test` or passing `--dedup false` logs every raw 350 ms packet.)
2. **Rotation-Safe `DEV_CONFIG_STATE` Header:**
   * Whenever `hw_bringup_trace.jsonl` is created or rotates to `.prev`, `HardwareDiagnosticLogger` automatically writes a `DEV_CONFIG_STATE` event as line 1 of the new file with the current `cfg_epoch`, `strategy`, `interval_sec`, and `dedup_fdcd` settings.
3. **Key Hardware Signatures to Look For:**
   * **Provisioning Soft-Reset (Restarts 30-Minute Window + Enters Full-Screen Wi-Fi UI):** `GATT_SESSION_SUMMARY` (with `1101` written) → `:BA` emits `frame_ctrl_hex = "0x08"` on disconnect → ~4.6s later `BLE_FE95_FRAME_CNT_RESET` (`prev_cnt` in `5..245` → `0..3`) + `BLE_WARMUP_ZERO_CO2` (`raw_hex` ending in `13020000` for ~4s while the SCD40 CO₂ reading warms up).
   * **30-Minute (`1,797.0 s`) Broadcast Ceiling followed by 10-Minute (`609.8 s`) Post-GATT Shutdown:** Exactly `1,797.0 s` (`29m 57.0s`) after the `0x0A` broadcast window started, `:BA` and `:BC` stop continuous advertising; then `609.8 s` (`10m 09.8s`) after the last successful GATT session, `:BA` emits `BLE_ADV_FDCD_SHUTDOWN_ZERO` (`0824...01040000000012040000000013020000`).

### 6.4 Provisioned Normal Mode (`frameControl = 0x88`) vs. Pairing Mode (`0x0A` / `0x8A`) & Pair B/C GATT Mapping

1. **Custom GATT Service (`22210000-554a-4546-5542-46534450464d`) Characteristic UUIDs (`BluetoothGatt.discoverServices()`):**
   * **Pair A (Auth / Time Sync / Misc):** Write `00000001-0000-1000-8000-00805f9b34fb`, Notify `00000002-0000-1000-8000-00805f9b34fb` (CCCD `0x2902`).
   * **Pair B (Provisioning / Wi-Fi Config):** Write `0000000d-0000-1000-8000-00805f9b34fb`, Notify `0000000e-0000-1000-8000-00805f9b34fb` (CCCD `0x2902`).
   * **Pair C (Secondary / Unknown):** Write `00000017-0000-1000-8000-00805f9b34fb`, Notify `00000018-0000-1000-8000-00805f9b34fb` (CCCD `0x2902`).

2. **Indefinite Passive `0xFDCD` Broadcasting in Provisioned Normal Mode (`frame_ctrl = 0x88`):**
   * Once the `CGDN1` has been Wi-Fi provisioned over Pair B, its `0xFDCD` header flips from `0x0A`/`0x8A` (bit 1 `0x02 == 1`) to **`0x88`** (bit 7 `0x80` set, pairing/unprovisioned bit 1 `0x02 == 0`).
   * In `frame_ctrl = 0x88`:
     * Unified `0xFDCD` packets carrying all 5 metrics (`T`, `RH`, `PM2.5`, `PM10`, `CO2`) broadcast **indefinitely** in pure `NONE_PASSIVE` mode (`0` GATT connections) while the OLED remains on its normal anti-burn-in drifting CO₂ screen and `0xFE95` advertising stays off.
     * Verified on live hardware for **>22 hours with Wi-Fi active** AND for **`1h 16m 47s` (`4,607s`) after disabling the guest Wi-Fi AP** (`1h 56m` total continuous `NONE_PASSIVE` session, clearing both the `609.8s` 10-minute and `1,797.0s` 30-minute pairing-mode shutdown ceilings with zero gaps).
   * Sending `11 01` on Pair A (`00000001`) immediately knocks a `0x88` monitor back into `frame_ctrl = 0x0A` (and `0x8A` while connected), re-enables `0xFE95` advertisements, and locks the OLED onto the static Wi-Fi setup icon.
   * [`DecodedQingpingFrame.isProvisionedNormalMode`](../app/src/main/java/io/github/androidaql/ble/QingpingBleParser.kt) (`(frameControl and 0x82) == 0x80`) and [`AqlCollectorService.kt`](../app/src/main/java/io/github/androidaql/service/AqlCollectorService.kt) now keep all automatic keep-alive strategies passive whenever `0x88` is observed, preventing accidental `11 01` pokes from knocking a provisioned monitor back into pairing mode. Explicit diagnostic `DEV_COMMAND` `poke_now` / `gatt_script` intents remain unaffected.

3. **Pair A / Pair B Recovery & Provisioning Hardware Test Results:**
   * **Pair A `11 01` + `11 02` Without Provisioning (`a:1101;wait:1500;a:1102;wait:1500`, `NONE_PASSIVE`):** Both `11 01` and `11 02` were accepted (`0002:04FF010100`, `0002:04FF020100`); `9.8s` after disconnect, `:BA` flipped `0x88 → 0x0A`, `:BC` started `0xFE95`, and the OLED displayed the static Wi-Fi icon. Neither GATT disconnect nor `11 02` cleared the icon or restored `0x88`.
   * **Pair B `0108`, `0107`, and `cmd 01` Invalid Wi-Fi Credentials:**
     * `b:0108` returned `000e:0708BCAB12342D58` (6-byte device MAC).
     * `b:0107` streamed 30 AP-scan notifications (`~6.8s` total): chunks `1..29` prefixed `13 07 <total_chunks> <idx> ...` and final chunk `05 07 1E 1E ...`, carrying `\t`-delimited ASCII `"<ssid>",<auth>,<rssi>` entries.
     * Isolating single-packet `b_wifi:a:12345678` (`0F012261222C22313233343536373822` on `0000000d`, `rc=0`) without `0107` scan overlap over a `15s` wait produced no notification on `000e`, left `frame_ctrl_hex = 0x8A` (`0x0A` after disconnect + `BLE_FE95_FRAME_CNT_RESET`), and kept the static Wi-Fi icon on the OLED.
   * **Pair B `b:0102`..`b:0106` Probe After `a:1101;a:1102`:**
     * `b:0102` → `000e:04FF020100` (`status=0x01`)
     * `b:0103` → `000e:0203FF`
     * `b:0104` → streamed AP SSID list after `~3.5s` (`13 04 18 <idx> "<ssid1>","<ssid2>",...`, comma-separated quoted SSIDs)
     * `b:0105` → `000e:03050F00`
     * `b:0106` → `000e:04FF060200` (`status=0x02`)
     * None of `b:0102`–`b:0106` exited pairing mode (`frame_ctrl_hex` returned to `0x0A` with the static Wi-Fi icon still showing).
   * **Offline BLE-Only `0x8A → 0x88` Transition & 33.4m Endurance Verification via Pair B `cmd 2` (`b_mqtt`):**
     * After a `7.2 s` touch-slider reset (`fe95_cnt = 0`), sending `a:1101;wait:600;a:1102;wait:600;b:0108;wait:800;b_hist_std;wait:1000;b_mqtt:127.0.0.1,1883,u,p;wait:4000` (`cfg_epoch=11`, `session_id=1`) with no Wi-Fi AP and no `cmd 1`:
       * `b_hist_std` (`130616CDFD00420000424016F9FF004200004240`) → `000e:04FF060200` (`status=0x02`)
       * `b_mqtt:127.0.0.1,1883,u,p` (`13023132372E302E302E312C313838332C752C70`) → **`000e:04FF020000`** (`status=0x00`)
     * `3.015 s` after `000e:04FF020000` (`12:49:14.793`, while still connected in GATT, before disconnect at `12:49:16.617`), `:BA` flipped directly from `0x8A` to **`frame_ctrl_hex = "0x88"`**, emitted `BLE_ADV_FDCD_SHUTDOWN_ZERO` (`0x88`) + `BLE_WARMUP_ZERO_CO2` (`0x88`), and then broadcast continuous live CO₂/sensor `0x88` packets with `0xFE95` silent. The OLED exited the static Wi-Fi icon back to the **main (monitor-results) CO₂ screen** (briefly showing an overlaid "Wi-Fi connecting" animation before settling on the normal pixel-drifting anti-burn-in display).
     * **Verified 30-Minute Endurance (`NONE_PASSIVE`, `cfg_epoch = 11`):** With `0` subsequent GATT connections after `12:49:16.617` and no Wi-Fi credentials, `:BA` continued broadcasting `frame_ctrl_hex = "0x88"` uninterrupted through at least `13:22:24.024` (**`33m 09.2s` = `1,989.2 s`** elapsed, `max_gap = 49.2 s`, `0` `FE95` resets, `CO2 = 564 ppm` at end of check), clearing both the `609.8 s` (~10m) and `1,797.0 s` (~30m) pairing-mode ceilings offline.

### 6.5 Pair B `cmd 0x03` Firmware OTA Hook (`--ota-url`) Protocol & Empirical Hardware Verification

**Use at your own risk:** Pair B `cmd 0x03` targets the **ESP32 app slot** via stock firmware's OTA handler and CRC32-trailer check (matching MQTT-triggered upgrades, with no cryptographic signature check). While plain-HTTP flashing via `cmd 0x03` is hardware-verified end-to-end, flashing an invalid or wrong-target image can permanently brick the unit.

[`scripts/analyze-hw-trace`](../scripts/analyze-hw-trace) (`--ota-url <http://...>`) and [`AqlCollectorService.kt`](../app/src/main/java/io/github/androidaql/service/AqlCollectorService.kt) enforce the following Pair B OTA rules:
0. **Stock Firmware Only (`22210000-...` Pair B `000D`):** `cmd 0x03` is implemented only by stock Qingping firmware. Once booted into **ESPHome**, the proprietary Qingping GATT service is replaced by ESPHome's stack and updates must use ESPHome's own OTA tools (`esphome run` / web OTA).
1. **Direct `http://` `200 OK` With `Content-Length` & No Redirects / No `https://`:** Stock firmware's HTTP OTA downloader uses a manual `connect → read-headers → read-body` loop that does **not** follow HTTP redirects and leaves root CAs unconfigured for TLS. Live hardware tests across all three URL failure/success modes established:
   * **HTTP `301` redirect (`http://` → `https://`):** Downloaded the `301` response body and failed CRC verification with `000e:0203FA` (`-6` CRC/write error).
   * **Direct `https://` URL:** Accepted `11 02` (`04FF020000`), `01 08` (`0708BCAB12342D58`), and all URL chunks (`ota_b_cmd3_c0..c2`), then `~2.2 s` later emitted `BLE_ADV_FDCD_SHUTDOWN_ZERO`, dropped BLE with `HCI_ERR_CONNECTION_TOUT` (`status=147`) at `+5.3 s` with zero `000e` `02 03 *` notifications, and rebooted back into stock `4.8.5`.
   * **Direct LAN `http://` (`200 OK` `.crc.bin`, `1,496,644` bytes, `esp_rom_crc32_le(0)` trailer):** Fetched via `GET /firmware.ota.crc.bin HTTP/1.1` `200`, streamed `02 03 01` (`1%`), `02 03 05` (`5%`) .. `02 03 5F` (`95%`) every `~3.2 s`, and two `02 03 64` (`100%`) replies (`duration_ms = 75849`, `disconnect_status = 0`), then rebooted into ESPHome (ESPHome native API port `6053` reachable over Wi-Fi; stock `0xFDCD` broadcasts stopped).
2. **Pre-Discovery BLE Scan Pause & `status=147` Retry (`connectGattInternal`):** Active `SCAN_MODE_LOW_LATENCY` scanning on the phone during GATT connection initiation caused intermittent `HCI_ERR_CONN_FAILED_ESTABLISHMENT` (`status=147`, followed by duplicate `status=133`). `AqlCollectorService.connectGattInternal` pauses the BLE scan via `stopBleScan()` before `connectGatt()`, retries pre-discovery `STATE_DISCONNECTED` (`!connectedOk`) up to 5 times at `1,500 ms` intervals (deduplicated via `disconnectHandled`), and resumes scanning in `onServicesDiscovered()` and terminal `STATE_DISCONNECTED`.
3. **Pair A `11 02`-Only Session Auth (No `11 01`):** Unauthenticated Pair B calls (`b:0108`, `b_ota`) are rejected with `04 FF <cmd> 01 00` (`AUTH_REQUIRED`), whereas sending `11 01` knocks a `0x88` monitor back into `0x0A` setup mode. On an already-bonded `0x88` monitor, sending **only Pair A `11 02 <token>`** unlocks Pair B (`0002:04FF020000`, `b:0108` → `0708...`) while staying in `0x88` (`reads` `0x2A26`, sends `11 02`, `b:0108`, and `b_ota`).
4. **Online `0x88` Guard:** `handleDevCommandIntent` refuses `--ota-url` (`DEV_OTA_REFUSED`) unless a fresh (`<= 120 s`) advert showed `frame_ctrl = 0x88` (or `--force-ota` / `force_ota = true` is explicitly passed).
5. **Held GATT Link, 60s Flash-Erase Watchdog & `02 03 <byte>` Status Decoding:** Keeps `BluetoothGatt` open after sending `b_ota` chunks, restarts a **`60,000 ms`** watchdog between `000E` `cmd 0x03` replies (to cover blocking `esp_ota_begin` flash erase before `1%`), and logs `DEV_OTA_STATUS`:
   * `0..99` (`0x00..0x63`): download progress (`1%`, then every `5%`: `02 03 01`, `02 03 05`, `02 03 0A`, ...).
   * `100` (`0x64`): `complete` (`esp_ota` write & CRC-32 trailer passed).
   * `101..255` (`0x65..0xFF`): signed negative error codes (`rejected`), including `0xFF` (`-1` network gate), `0xFE` (`-2` URL parse), `0xFC` (`-4` connect/TLS), `0xFB` (`-5` missing/zero `Content-Length`), and `0xFA` (`-6` flash write / CRC check failed, verified empirically on HTTP `301` redirect), plus `04 FF 03 <non-zero> 00` generic command errors (e.g. `04 FF 03 01 00` = `AUTH_REQUIRED`).
6. **Keep-Alive Suspension & Pre/Post `0x2A26`:** Suspends automatic and periodic keep-alive GATT activity while `isOtaUpgradeInFlight` is active, reads `0x2A26` before `b_ota` and after `complete`, and expects reboot/link drop on completion.
7. **URL Shell Quoting & 254-Byte Framing Cap:** Both CLI and Android validate `1..254` UTF-8 bytes before connecting; `analyze-hw-trace` quotes all `adb shell am start` arguments with `shlex.quote(...)`; Android stores `pendingOtaUrl` separately from `;`-delimited `gatt_script` and catches GATT setup exceptions (`DEV_GATT_SCRIPT_ERROR`).
8. **Operational Prerequisites:** Maintain external USB-C power throughout OTA, and do not assume stored MQTT or `0x88` provisioning survives an upgrade.
9. **ESPHome Post-Migration Passive Telemetry (`0xFCD2` BTHome v2):** Default ESPHome `esp32_ble_tracker` / `bluetooth_proxy` configs do not advertise local sensors. To feed [`QingpingBleParser.kt`](../app/src/main/java/io/github/androidaql/ble/QingpingBleParser.kt) (`parseBtHomeV2ServiceData`), advertise unencrypted non-connectable (`ADV_TYPE_NONCONN_IND`) raw payloads containing Flags AD `02 01 06` + `0x16 D2 FC` BTHome v2 service data (`device_info = 0x40`) with ascending object IDs: `0x01` (battery `uint8` %), `0x02` (temperature `sint16` `0.01 °C`), `0x03` (humidity `uint16` `0.01 %`), `0x0D` (PM2.5 `uint16` `µg/m³`), `0x0E` (PM10 `uint16` `µg/m³`), and `0x12` (CO₂ `uint16` `ppm`, omitting `0` during warmup), fitting in 25 bytes (≤ 31-byte legacy limit).







