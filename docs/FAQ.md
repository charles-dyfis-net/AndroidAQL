# AirSlice Frequently Asked Questions (FAQ)

Questions and answers about **`AirSlice: Portable Air Log`** (`net.dyfis.androidaql`)—covering how to evaluate the app with or without hardware, why it works the way it does, privacy/permissions, and custom ESPHome firmware.

> **More documentation:** See the **[README](../README.md)**, **[How Trips, Segments, and Splits Work](trip_slicing_guide.md)**, **[How Your Monitor Works with `AirSlice`](how_cgdn1_works.md)**, and the **[Privacy Policy](PRIVACY_POLICY.md)**. You can also ask questions or follow updates on the **[`androidaql-users` Google Group](https://groups.google.com/g/androidaql-users)** or open an issue on **[GitHub](https://github.com/charles-dyfis-net/AndroidAQL)**.

---

## Testing & Using `AirSlice` Without a Monitor

### I don't own a Qingping monitor. Is `AirSlice` useful to me at all?

Yes, in three ways:

1. **Try it with built-in Demo Data Mode (for testers & evaluators):**
   Even without sensor hardware, you can stream synthetic live readings on the **Now** tab, load a canned sample trip on the **Trips** tab, simulate Saved Wi-Fi Places, and test how the app behaves across phones, tablets, Chromebooks, or with optional permissions turned off.
2. **View, slice, and re-export someone else's `.xlsx` trip logs:**
   Any `.xlsx` workbook exported by another `AirSlice` user can be imported back into the **Trips** tab (`📥 Import Trip (.xlsx)...`), inspected across the 3-pane timeline, split/annotated into segments, and re-exported.
3. **Other BTHome v2 (`0xFCD2`) sensors:**
   While `AirSlice` has primarily been tested with the **Qingping Air Monitor Lite (`CGDN1`)** (stock BLE format and ESPHome BTHome), it passively decodes standard unencrypted **BTHome v2** environmental broadcasts (`CO₂`, `PM2.5`, `PM10`, temperature, humidity, battery). If you own another device that emits BTHome v2, please open an issue or post on `androidaql-users` with your results so any quirks can be investigated.

---

### How does Demo Data Mode ("emulating a live device") work?

You can turn on Demo Data Mode either directly from **`❓ Help`** in the top bar (tap **`🎭 Enable Demo Data Mode`**) or manually in Settings:

1. Tap **`⚙️ Settings`** in the top-right corner of the app.
2. Scroll to the bottom under **Diagnostics & Support** and check **`🎭 Demo Data Mode (Synthetic BLE Stream, Sample Trip & Place Sim)`**.
3. Tap **`Done`**.
4. Back on the **Now** tab, tap **`🎭 Toggle Live Demo Replay Stream (No Hardware Needed)`** to start a simulated live sensor stream.
5. On the **Trips** tab, tap **`🎭 Load Canned Demo Trip`** (from the empty state or the trip selector) to explore the 3-pane Trip Slicer, or use **`📂 Demo Replay XLSX (Time-Shifted)...`** to replay an exported `.xlsx` file shifted to the current time.

---

## Hardware, Air Quality & Comparison

### How is the Qingping `CGDN1` + `AirSlice` different from an Aranet?

Aranet makes a dedicated CO₂ monitor and a separate particulate monitor, whereas the **Qingping Air Monitor Lite (`CGDN1`)** combines **CO₂**, **PM2.5**, **PM10**, **Temperature**, and **Relative Humidity** in a single compact enclosure. (Open to corrections if Aranet releases a combined unit.)

---

### Why does it matter to measure both CO₂ and particulate counts (PM2.5 / PM10)?

Unless you are on a submarine or a spacecraft, ordinary public-space HVAC systems do not scrub CO₂—so **CO₂** tells you how much **fresh outside air** is being brought in relative to the number of people breathing in the space.

However, CO₂ alone does not tell you whether the air is being **filtered**. Running indoor air through a HEPA or high-MERV filter removes airborne particulates (**PM2.5** and **PM10**) without changing CO₂ at all. Measuring both lets you see both sides of the picture: *ventilation* (outside air exchange) and *filtration* (particulate removal).

---

### Why not just use the official Qingping+ app?

1. **Cloud-first vs. direct phone-to-monitor Bluetooth:**
   The official app connects your `CGDN1` to a Wi-Fi network and configures it to upload readings over the internet (along with your Wi-Fi access point's SSID and BSSID/MAC address, though not the Wi-Fi password) to a vendor-configured MQTT server. When you open the vendor app, data travels *monitor → vendor cloud → phone* rather than directly from the monitor to your phone. That stops working as soon as you carry the monitor away from Wi-Fi, and it sends readings off-device before you have reviewed them.
2. **Remote firmware push surface:**
   Vendor-managed MQTT also allows the remote server to send an MQTT command instructing the monitor to download and flash new firmware from a URL. Combined with account, IP, and Wi-Fi metadata, that means a remote party could push firmware specifically to your device.
3. **What `AirSlice` does instead:**
   * When `AirSlice` initializes an unconfigured `CGDN1` in Wi-Fi Setup Mode, it provisions it offline over Bluetooth and points its MQTT target at `127.0.0.1` (a nonexistent local loopback address)—not a corporate server or external broker—so the monitor switches into continuous passive BLE broadcast mode with zero cloud traffic.
   * If you have **already** configured your `CGDN1` (for example, to talk to your own local MQTT server or the vendor cloud), `AirSlice` leaves that configuration completely untouched and simply listens passively to its Bluetooth broadcasts.

See **[How Your Monitor Works with `AirSlice`](how_cgdn1_works.md)** for a fuller walkthrough.

---

## Permissions & Privacy

### Why does `AirSlice` request Location and Physical Activity permissions?

Both are optional for basic live BLE reading (`Not Now (BLE Only)` works for CO₂/PM/Temp/RH without them), and both are processed **strictly on-device**:

* **Movement speed on the Trip timeline:** Location (GPS) and Physical Activity (hardware step cadence) are converted in memory into **scalar speed (`m/s`)** and **horizontal GPS accuracy (`m`)** alongside your air-quality readings. Seeing speed changes makes it easy to spot trip transitions—when you got into or out of a cab, walked between rooms, or stepped indoors/outdoors—so you can slice a trip into clean, labeled segments and export metrics per segment.
* **Why steps are included alongside GPS:** Much of air-quality monitoring happens indoors where GPS is unavailable or noisy; step-cadence estimation keeps walking movement visible when GPS drops out.
* **Automatic GPS-off via Saved Wi-Fi Places:** Android requires Location permission to read the name (SSID) of your connected Wi-Fi network. Once you save places like *Home* or *Office*, `AirSlice` automatically turns GPS and step polling off while connected there.
* **No coordinates or step counts saved:** Raw latitude/longitude coordinates, unmatched Wi-Fi names, and cumulative step counts are immediately discarded and never saved to disk or sent anywhere.

See the **[Privacy Policy](PRIVACY_POLICY.md)** for full details.

---

## Custom / Open-Source Firmware (ESPHome)

### Does `AirSlice` work with open-source firmware on the `CGDN1`?

Yes. Building [`nemethhh/esphome-qingping-cgdn1`](https://github.com/nemethhh/esphome-qingping-cgdn1) (`main` as of commit `26642dcb` / 2026-11-17 or newer) with `-s bthome true` and `-s portable true` is ideal for mobile use with `AirSlice`:

* Disables the Wi-Fi radio in normal portable operation to extend battery life (while keeping a physical-access escape hatch to manually trigger temporary captive-portal mode for updates or reconfiguration).
* Removes stock firmware's unauthenticated BLE firmware-URL command.
* Includes **battery percentage** in its BTHome v2 BLE broadcasts (stock firmware omits battery level over BLE because its legacy 31-byte advertisement packet has no room left).

---

### What is the stock firmware "BLE command to install arbitrary firmware"?

Stock `CGDN1` firmware exposes a BLE provisioning command that gives the monitor an HTTP URL; the monitor fetches that image over Wi-Fi, verifies a CRC32 trailer, and flashes itself. That means if a stock-firmware unit is connected to Wi-Fi, anyone within Bluetooth range who can host an HTTP URL could potentially trigger a firmware update. Switching to ESPHome lets you require password authentication or encryption for OTA updates, and in portable builds (where Wi-Fi is normally off) updates use a manual escape hatch requiring physical access to trigger temporary captive-portal mode rather than accepting remote BLE triggers.

---

### Can I install open-source firmware on my `CGDN1` without ever running the official app?

Yes. Stock firmware exposes BLE commands both to configure 2.4 GHz Wi-Fi credentials and to trigger an HTTP firmware update URL. When **`🛠️ Developer Mode`** is enabled in `AirSlice` (with `adb` `DEV_COMMAND` intents via [`scripts/analyze-hw-trace`](../scripts/analyze-hw-trace)), you can configure Wi-Fi and flash an initial `.crc.bin` ESPHome image over Bluetooth + local HTTP without installing the vendor app. See **[README — BLE & Hardware Diagnostic Testbench](../README.md#3-ble--hardware-diagnostic-testbench--provisioning-cli)** and **[Physical Hardware Observations](hardware_observations.md)**.

---

### If flashing custom firmware bricks my `CGDN1`, will you fix or replace it?

No—custom firmware flashing and Developer Mode provisioning commands are strictly **at your own risk**. Recovering a bricked `CGDN1` over serial requires prying off a glued-in OLED screen to reach the internal pins, and no repair or replacement is offered if a unit is damaged.

---

## Project Background & Community

### Was AI used to build `AirSlice`? Was it "vibe-coded"?

AI was used heavily, though with an engineering-driven workflow: the project author has an embedded-systems and information-security background, writes specifications for nontrivial features and protocol findings before implementing them (see [`docs/requirements_spec.md`](requirements_spec.md) and [`docs/hardware_observations.md`](hardware_observations.md)), and reviews every patch before commit—while the code itself was largely written by Google's Gemini 4-series models (Argon, Neon, and Hydrogen).

---

### Where can I ask questions or join the user group?

Post in the **[`androidaql-users` Google Group](https://groups.google.com/g/androidaql-users)** or open an issue on **[GitHub](https://github.com/charles-dyfis-net/AndroidAQL)**. (If community members prefer requiring group membership before reading posts on `androidaql-users`, that setting can be adjusted based on consensus.)
