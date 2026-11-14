# How Your `CGDN1` Monitor Works with `AndroidAQL`

This guide explains what is happening inside a **Qingping Air Monitor Lite (`CGDN1`)**, why its Bluetooth behavior out of the box can be surprising, and how **`AndroidAQL`** works with the monitor to give you continuous mobile air-quality recording without Wi-Fi, cloud accounts, or OLED screen burn-in.

> **Looking for byte-level protocol details?** If you are a developer looking for GATT UUIDs, hex opcodes, frame headers, or hardware trace logs, see **[Physical Hardware Observations (`CGDN1`)](hardware_observations.md)**.

---

## 1. What's Inside the `CGDN1` (and Why Battery Runtime Is ~1.5–3 Hours)

Inside its compact case, the **Qingping Air Monitor Lite (`CGDN1`)** houses research-grade environmental sensing hardware driven by an **`ESP32-WROVER-B`** dual-radio processor and a `2,000 mAh` internal battery:

* **CO₂ Sensor (Sensirion SCD40):** Photoacoustic NDIR carbon dioxide sensor.
* **Particulate Sensor (Plantower PMSA003I):** Laser-scattering particle counter with a miniature internal intake fan that measures **PM2.5** and **PM10**.
* **Temperature & Humidity Sensor (Sensirion SHT4x):** Digital thermal/hygrometer sensor mounted inside the enclosure.
* **OLED Display with Anti-Burn-In Protection:** In normal monitoring mode, the OLED slowly shifts/pages its CO₂ display elements and omits static status icons specifically to prevent permanent OLED burn-in.

### Practical Hardware Notes for Mobile Use
* **Internal Battery Runtime (`~1.5 to 3 hours`):** Continuously powering an NDIR CO₂ emitter, a laser particulate fan, an OLED screen, and wireless radios draws significant power from a `2,000 mAh` battery. For long appointments, travel days, or hospital visits, plugging the `CGDN1` into a small **USB-C power bank** gives you all-day runtime.
* **Why Battery Percentage Is Not Shown Over Bluetooth on Stock Firmware:** Each standard Bluetooth Low Energy (BLE) advertisement packet has a strict `31-byte` size limit. Stock firmware fills all `31` bytes with Temperature, Humidity, PM2.5, PM10, and CO₂, leaving zero room in the broadcast packet for battery level. (Battery level *is* supported when running custom **ESPHome / BTHome v2** firmware.)
* **Warmth When Bluetooth Stays Active (`~1.6 °C`):** Stock firmware was written assuming Bluetooth would only stay on briefly during setup, so its built-in temperature compensation accounts for warm-up, charging, and display brightness—but not continuous BLE radio activity. Keeping Bluetooth transmitting continuously adds roughly **`+1.6 °C`** (`~2.9 °F`) of gentle internal self-heating over the first 20–30 minutes (slightly nudging relative humidity lower in proportion).

---

## 2. The Monitor's Two Operating Modes (Setup Mode vs. Normal Mode)

The key to understanding the `CGDN1` is that its **stock firmware (`4.8.5`) was designed around stationary Wi-Fi reporting**. In Qingping's intended desktop workflow, day-to-day readings go over Wi-Fi, and Bluetooth exists mainly for initial phone pairing.

Because of that design, the monitor behaves very differently depending on which of its two states it is in:

### A. Unconfigured / "Wi-Fi Setup Mode"
* **Out of the Box (Bluetooth Off):** When a `CGDN1` has not been set up (or after a network reset and timeout), Bluetooth starts out **off** so the phone won't see any broadcasts.
* **Waking Setup Mode (~7-Second Touch-Bar Hold):** Holding a still finger on the top capacitive touch-bar for **`~7.2 seconds`** triggers a network reset. That puts the monitor into **Wi-Fi Setup Mode**:
  1. The OLED screen switches from its normal CO₂ readout to a **static Wi-Fi setup icon**.
  2. Both Bluetooth advertisements and the monitor's setup GATT server turn on.
* **Why Setup Mode Turns Bluetooth Back Off (1m / 10m / 30m Limits):**
  * **~1 minute (`59 s`):** If no phone connects over Bluetooth after you wake Setup Mode, Bluetooth shuts back off.
  * **~10 minutes (`609.8 s`):** Once a phone connects and talks to the monitor in Setup Mode, Bluetooth shuts off ~10 minutes after the last interaction unless refreshed.
  * **30 minutes (`1,797.0 s`):** Even if an app periodically refreshes the 10-minute inactivity timer (and even on USB-C power), stock firmware enforces a hard **30-minute** broadcast ceiling in Setup Mode unless the monitor is either reset back into a fresh setup window or transitioned into Normal Mode.
* **OLED Burn-In Trade-Off in Setup Mode:** Any time the monitor stays in Setup Mode (or receives a periodic pairing-window reset), its OLED displays the **static Wi-Fi setup icon** rather than the drifting CO₂ screen.

### B. Provisioned / "Normal Mode"
Once the monitor considers initial setup finished, it switches into **Normal Mode**:
* **Anti-Burn-In OLED Restored:** The static Wi-Fi setup icon disappears, and the OLED returns to its normal pixel-shifting CO₂ readout.
* **Indefinite Continuous Bluetooth Broadcasts:** Even without periodic Bluetooth connections—and **even when no Wi-Fi network is configured or anywhere nearby**—the monitor broadcasts live CO₂, PM2.5, PM10, Temperature, and Humidity readings roughly once per second **indefinitely**.

---

## 3. How `AndroidAQL` Completes Setup Offline in ~5 Seconds

Normally, moving a `CGDN1` out of Setup Mode and into Normal Mode requires installing the proprietary **Qingping+** (or Xiaomi Home) app, signing into an account, and giving the monitor credentials for a live 2.4 GHz Wi-Fi network.

However, the `CGDN1`'s stock firmware does **not** actually require a working Wi-Fi connection to leave Setup Mode—it transitions into Normal Mode as soon as an authenticated Bluetooth client syncs its clock and configures a reporting server address.

In its default mode, **`AndroidAQL`** takes advantage of that behavior transparently:

1. **If Your Monitor Is Already in Normal Mode (or Running ESPHome):**
   `AndroidAQL` never disturbs it. The app operates **100% passively** (`BLE: Provisioned 0x88 (Passive)` or `BLE: BTHome v2 (Passive)`), listening to live broadcasts with zero Bluetooth connections and zero OLED interruptions.
2. **If Your Monitor Is in Wi-Fi Setup Mode** *(either because it is brand new after a 7-second touch-bar hold, or because a previous setup was cleared)*:
   `AndroidAQL` automatically connects over Bluetooth for **~5 seconds**, authenticates with the monitor, syncs its real-time clock, and sends a harmless local loopback server placeholder (`127.0.0.1`) with **no Wi-Fi credentials**.
3. **Transition Within ~3 Seconds:**
   The monitor briefly shows its "connecting" overlay on the OLED, clears the static Wi-Fi setup icon back to the normal anti-burn-in CO₂ screen, turns off pairing beacons, and begins broadcasting continuous Normal Mode telemetry indefinitely over passive Bluetooth.

From that moment on, your `CGDN1` stays in Normal Mode across normal trips and home use—with **zero Wi-Fi networks, zero vendor apps, zero cloud accounts, and zero periodic keep-alive pokes needed**.

---

## 4. Understanding `AndroidAQL`'s Status Banner & Keep-Alive Settings

On the **Now** tab, the top status banner shows both your movement/privacy context and how `AndroidAQL` is talking to the monitor:

* **`🏠 At <Saved Place> • GPS Off`** — Your phone is connected to one of your configured Saved Wi-Fi networks, so GPS polling stays completely off (`0 mW`).
* **`🚶 On a Trip • GPS Active`** — Your monitor is in range and you are away from Saved Wi-Fi Places, so `AndroidAQL` records GPS speed and accuracy alongside air quality.
* **`📡 Monitor Out of Range • GPS Off`** — The monitor hasn't been heard recently (either turned off, asleep, or left behind), so GPS turns back off.

### Fallback & Diagnostic Keep-Alive Options (`⚙️ Settings`)
Under **⚙️ Settings → Monitor Keep-Alive Mode (>30m Unplugged)**, legacy diagnostic strategies remain available if you ever intentionally want to test an **unprovisioned** monitor without completing offline setup:

* **`Always Anti-Burn-In Quiet Screen (<30m Max)` (`0509_ONLY`):** Sends quiet clock-sync messages every 3.5 minutes without changing the OLED screen. This keeps an unprovisioned monitor awake past the 10-minute timeout up to the 30-minute Setup Mode ceiling.
* **`Always Guaranteed Keep-Alive • 24m Reset` (`0509_24M_1101`):** Sends clock syncs every 3.5 minutes plus a pairing-window reset every 24 minutes so an unprovisioned monitor stays awake indefinitely (at the cost of placing the static Wi-Fi setup icon on the OLED every 24 minutes unless dismissed with a touch-bar tap).
* **`Passive Listener Only` (`NONE_PASSIVE`):** Never initiates Bluetooth GATT connections at all.

With a monitor that has completed `AndroidAQL`'s 5-second offline setup (or runs ESPHome), `AndroidAQL` stays automatically in passive listening mode even if a fallback keep-alive mode was previously selected.

---

## 5. Configuring Wi-Fi Without the First-Party App (`--wifi-ssid`) & Custom Firmware (`ESPHome` / `BTHome v2`)

### Out-of-the-Box Passive Support for Custom Firmware (`BTHome v2`)
If your monitor runs custom firmware such as **ESPHome** broadcasting **BTHome v2**, `AndroidAQL` automatically detects and decodes Temperature, Humidity, CO₂, PM2.5, PM10, Pressure, Illuminance, and Battery % passively (`BLE: BTHome v2 (Passive)`).

**Important for ESPHome configs (`esp32_ble_tracker` / `bluetooth_proxy`):** ESPHome's built-in `esp32_ble_tracker` and `bluetooth_proxy` components only configure the ESP32 as a BLE **scanner/client**—they do **not** broadcast the monitor's own onboard sensors over BLE by default. Without adding passive BLE advertising, `AndroidAQL` will stop receiving new readings as soon as the `CGDN1` boots into ESPHome.

`AndroidAQL` scans for unencrypted **BTHome v2** service data on UUID **`0xFCD2`** (`device_info = 0x40`, ascending object IDs):

| BTHome Object ID | Metric | Encoding | Scale |
| :--- | :--- | :--- | :--- |
| `0x01` | Battery | `uint8` (1 byte) | `1 %` |
| `0x02` | Temperature | `sint16` LE (2 bytes) | `0.01 °C` |
| `0x03` | Relative Humidity | `uint16` LE (2 bytes) | `0.01 %` |
| `0x0D` | PM2.5 | `uint16` LE (2 bytes) | `1 µg/m³` |
| `0x0E` | PM10 | `uint16` LE (2 bytes) | `1 µg/m³` |
| `0x12` | CO₂ | `uint16` LE (2 bytes) | `1 ppm` (`0` skipped as warmup) |

To reproduce our hardware-tested ESPHome setup directly, check out the **`dev` branch** of [`charles-dyfis-net/esphome-qingping-cgdn1`](https://github.com/charles-dyfis-net/esphome-qingping-cgdn1/tree/dev) (forked from [`nemethhh/esphome-qingping-cgdn1`](https://github.com/nemethhh/esphome-qingping-cgdn1)) and compile with `-s portable true -s bthome true` to build a configuration tuned for `AndroidAQL` (rather than stationary Home Assistant / ESPHome server use):

```bash
# Permanent build tuned for AndroidAQL (add `-s trial_mode true` for a reversible test build first):
esphome -s portable true -s bthome true compile cgdn1.yaml
```

Alternatively, add the 5-second `interval:` block below to any custom ESPHome config that already enables `esp32_ble:` (`id: ble`) with sensors `battery_level`, `temp_comp`, `rh_comp`, `pm25`, `pm10`, and `co2`:

```yaml
interval:
  - interval: 5s
    then:
      - lambda: |-
          if (!id(ble).is_active()) return;
          uint8_t adv[31];
          uint8_t n = 0;
          adv[n++] = 0x02;
          adv[n++] = 0x01;
          adv[n++] = 0x06;
          const uint8_t svc_len_idx = n++;
          adv[n++] = 0x16;
          adv[n++] = 0xD2;
          adv[n++] = 0xFC;
          adv[n++] = 0x40;
          if (!std::isnan(id(battery_level).state)) {
            int bat = (int) lroundf(id(battery_level).state);
            if (bat < 0) bat = 0;
            if (bat > 100) bat = 100;
            adv[n++] = 0x01;
            adv[n++] = (uint8_t) bat;
          }
          if (!std::isnan(id(temp_comp).state)) {
            int16_t t_c100 = (int16_t) lroundf(id(temp_comp).state * 100.0f);
            adv[n++] = 0x02;
            adv[n++] = (uint8_t) (t_c100 & 0xFF);
            adv[n++] = (uint8_t) ((t_c100 >> 8) & 0xFF);
          }
          if (!std::isnan(id(rh_comp).state)) {
            int rh_c100 = (int) lroundf(id(rh_comp).state * 100.0f);
            if (rh_c100 < 0) rh_c100 = 0;
            if (rh_c100 > 10000) rh_c100 = 10000;
            adv[n++] = 0x03;
            adv[n++] = (uint8_t) (rh_c100 & 0xFF);
            adv[n++] = (uint8_t) ((rh_c100 >> 8) & 0xFF);
          }
          if (!std::isnan(id(pm25).state)) {
            int pm25_u16 = (int) lroundf(id(pm25).state);
            if (pm25_u16 < 0) pm25_u16 = 0;
            if (pm25_u16 > 65535) pm25_u16 = 65535;
            adv[n++] = 0x0D;
            adv[n++] = (uint8_t) (pm25_u16 & 0xFF);
            adv[n++] = (uint8_t) ((pm25_u16 >> 8) & 0xFF);
          }
          if (!std::isnan(id(pm10).state)) {
            int pm10_u16 = (int) lroundf(id(pm10).state);
            if (pm10_u16 < 0) pm10_u16 = 0;
            if (pm10_u16 > 65535) pm10_u16 = 65535;
            adv[n++] = 0x0E;
            adv[n++] = (uint8_t) (pm10_u16 & 0xFF);
            adv[n++] = (uint8_t) ((pm10_u16 >> 8) & 0xFF);
          }
          if (!std::isnan(id(co2).state) && id(co2).state > 0.0f) {
            int co2_u16 = (int) lroundf(id(co2).state);
            if (co2_u16 > 65535) co2_u16 = 65535;
            adv[n++] = 0x12;
            adv[n++] = (uint8_t) (co2_u16 & 0xFF);
            adv[n++] = (uint8_t) ((co2_u16 >> 8) & 0xFF);
          }
          if (n <= 8) return;
          adv[svc_len_idx] = n - svc_len_idx - 1;
          esp_ble_adv_params_t adv_params = {};
          adv_params.adv_int_min = 0x0200;  // 320 ms
          adv_params.adv_int_max = 0x0280;  // 400 ms
          adv_params.adv_type = ADV_TYPE_NONCONN_IND;
          adv_params.own_addr_type = BLE_ADDR_TYPE_PUBLIC;
          adv_params.channel_map = ADV_CHNL_ALL;
          adv_params.adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY;
          esp_ble_gap_config_adv_data_raw(adv, n);
          esp_ble_gap_start_advertising(&adv_params);
```

If your config exposes a runtime Bluetooth switch, call `esp_ble_gap_stop_advertising()` before `ble.disable:` so advertising stops cleanly.

**`trial_mode` fallback behavior (`esphome-qingping-cgdn1`):** If you compile a reversible first test build with `-s trial_mode true`, every ESPHome boot marks the other OTA slot (stock firmware) as the next boot target. Because ESPHome's `api:` component enables a default 15-minute `reboot_timeout` when no Home Assistant API client connects, an unattended unit in `trial_mode` will automatically reboot back into stock `4.8.5` after ~15 minutes unless an API client connects or you later flash a permanent `-s trial_mode false` build. While running in `trial_mode`, do **not** run ESPHome's own OTA updater, as it writes to the inactive slot and overwrites stock.

### Configuring 2.4 GHz Wi-Fi Over Bluetooth Without Qingping+ / Xiaomi Home
For normal portable air-quality logging, you do **not** need Wi-Fi at all. However, if you want to connect a stock `CGDN1` to a 2.4 GHz Wi-Fi network (for example, as a prerequisite for trying an HTTP OTA upgrade) without installing the first-party app, [`scripts/analyze-hw-trace`](../scripts/analyze-hw-trace) provides a dedicated CLI flag:

```bash
printf '%s\n' 'your-2.4ghz-password' | python3 ./scripts/analyze-hw-trace --wifi-ssid "Your-2.4GHz-SSID" --wifi-password-stdin
```

(Omit `--wifi-password-stdin` to be prompted interactively without putting the password in shell history.) Over a single ~40-second Bluetooth session, `AndroidAQL` authenticates, reads the Wi-Fi MAC, runs the required 2.4 GHz AP scan, submits credentials (handling colons and semicolons cleanly while redacting the password from trace logs), waits for `02 01 01` Wi-Fi association confirmation, and commits local loopback MQTT provisioning so the monitor remains in Normal Mode (`0x88`) with live Wi-Fi connectivity. Wait ~45 seconds and inspect the result with `python3 ./scripts/analyze-hw-trace --pull`.

### Pair B HTTP Firmware Upgrade Hook (`--ota-url`)

> [!CAUTION]
> **Use at your own risk — Brick Warning:** The `--ota-url` diagnostic hook in [`scripts/analyze-hw-trace`](../scripts/analyze-hw-trace) exposes stock firmware's Bluetooth firmware-upgrade trigger (`cmd 0x03`, which targets the **ESP32 app slot** rather than the secondary co-MCU). This path uses the **same OTA handler and CRC32-trailer check as an MQTT-triggered firmware upgrade** (no cryptographic signature check, requiring only Wi-Fi and no MQTT server). While plain-HTTP migration from stock `4.8.5` to ESPHome is **hardware-verified**, flashing an improperly formatted or wrong-target image can permanently brick your monitor. Always review third-party flashing documentation (including hardware/serial warnings in the ESPHome repository) before attempting any firmware modification.

If you use the `--ota-url <http://...>` hook with compatible CRC-trailed firmware (`.crc.bin`):
* **Stock Firmware Only (One-Way Migration Trigger):** The Pair B `cmd 0x03` BLE upgrade command is part of Qingping's stock firmware GATT server (`22210000-...`). Once the device boots into **ESPHome**, stock BLE GATT commands are no longer supported—use **ESPHome's own OTA update tools** (`esphome run` / web OTA) for all subsequent updates.
* **Plain `http://` `200 OK` URL Required (No `https://` and No `301`/`302` Redirects):** Stock `4.8.5`'s HTTP OTA downloader uses a raw read loop that does **not** follow HTTP redirects (`301`/`302` -> downloads the redirect HTML body -> fails CRC check with `02 03 FA`) and omits a configured root CA bundle for TLS—verified on live hardware, giving `cmd 0x03` an `https://` URL causes the `CGDN1` to crash and reboot back into stock `4.8.5` ~2.2 seconds later without emitting any `02 03` reply. Host the `.crc.bin` file on a direct plain `http://` endpoint returning `200 OK` with `Content-Length`.
* **External USB-C Power Required:** Keep the `CGDN1` plugged into reliable external USB-C power throughout the upgrade.
* **Live Wi-Fi Connection Required (`0x88` Mode):** The monitor must already be online in provisioned Normal Mode (`0x88`) on Wi-Fi so it can fetch the HTTP URL (`AndroidAQL` refuses `--ota-url` otherwise unless `--force-ota` is passed).
* **Progress, Flash-Erase Delay & Reboot:** `cmd 0x03` emits `02 03 <pct>` at `1%`, every `5%`, and `100%` (`0x64`)—verified end-to-end flashing ESPHome in **~76 seconds** (`02 03 01` .. `02 03 5F` .. `02 03 64`)—or signed negative error codes (`0xFA`..`0xFF`, e.g., `0xFA` = CRC/flash error). Because the initial flash erase (`esp_ota_begin`) happens before the `1%` reply, `AndroidAQL` waits up to **60 seconds** between replies. Expect the monitor to reboot and drop BLE on completion, and do not assume prior `0x88` or MQTT settings survive.

---

## Further Reading

* **[`README.md`](../README.md)** — Quick-start overview, Trip Slicer workflow, and app build instructions.
* **[Physical Hardware Observations (`CGDN1`)](hardware_observations.md)** — Complete wire-level BLE advertisement layouts, GATT service/characteristic UUIDs, hex opcodes, and trace logs.
* **[Requirements & Architecture Specification](requirements_spec.md)** — App state machine, ring-buffer design, and UI specification.
