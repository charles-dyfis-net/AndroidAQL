package io.github.androidaql.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Decoder for Qingping BLE Service Data advertisements (16-bit Service UUID 0xFDCD),
 * ported from Ernst79/bleparser `package/bleparser/qingping.py`.
 *
 * Specifically targets the Qingping Air Monitor Lite (Model CGDN1, product IDs 0x0E and 0x24),
 * while decoding all standard Qingping TLV tags so hardware bring-up can inspect any frame.
 */
object QingpingBleParser {

    /** 16-bit Bluetooth SIG Service Data UUID assigned to Qingping Technology (Beijing) Co., Ltd. */
    const val QINGPING_SERVICE_UUID_16 = 0xFDCD
    const val QINGPING_SERVICE_UUID_STRING = "0000fdcd-0000-1000-8000-00805f9b34fb"

    /**
     * 16-bit Bluetooth SIG Service Data UUID for BTHome v2 (https://bthome.io/format/),
     * emitted by ESPHome (`bthome` / `esp32_ble_beacon`) and open-source air quality firmwares.
     */
    const val BTHOME_V2_SERVICE_UUID_16 = 0xFCD2
    const val BTHOME_V2_SERVICE_UUID_STRING = "0000fcd2-0000-1000-8000-00805f9b34fb"
    const val PRODUCT_ID_BTHOME_V2 = 0xB2

    /** Qingping proprietary 128-bit GATT service and characteristics for active keep-alive / RF-privacy modes. */
    const val QINGPING_GATT_SERVICE_UUID = "22210000-554a-4546-5542-46534450464d"
    const val QINGPING_GATT_WRITE_CHAR_UUID = "22210001-554a-4546-5542-46534450464d"
    const val QINGPING_GATT_NOTIFY_CHAR_UUID = "22210002-554a-4546-5542-46534450464d"
    const val QINGPING_GATT_PAIR_B_WRITE_CHAR_UUID = "0000000d-0000-1000-8000-00805f9b34fb"
    const val QINGPING_GATT_PAIR_B_NOTIFY_CHAR_UUID = "0000000e-0000-1000-8000-00805f9b34fb"
    const val QINGPING_GATT_PAIR_C_WRITE_CHAR_UUID = "00000017-0000-1000-8000-00805f9b34fb"
    const val QINGPING_GATT_PAIR_C_NOTIFY_CHAR_UUID = "00000018-0000-1000-8000-00805f9b34fb"

    /** Pair B (`000D`) `cmd 0x06` (`setStandardHistoryType`) fixed 20-byte frame. */
    const val QINGPING_PAIR_B_STANDARD_HISTORY_TYPE_HEX = "130616CDFD00420000424016F9FF004200004240"

    /** Offline dummy local MQTT broker spec that transitions an unprovisioned CGDN1 into `0x88` mode. */
    const val QINGPING_OFFLINE_PROVISION_MQTT_SPEC = "127.0.0.1,1883,u,p"

    /** Maximum UTF-8 payload byte count for Pair B `[len, cmd, ...utf8]` frames (`len = 1 + utf8 <= 255`). */
    const val MAX_PAIR_B_STRING_UTF8_BYTES = 254

    /** Parsed status notification from Pair B (`000E`) during a `cmd 0x03` firmware upgrade. */
    data class PairBOtaNotifyStatus(
        val rawValue: Int,
        val state: String,
        val progressPct: Int?
    )

    /** Product IDs for Qingping Air Monitor Lite (CGDN1) and related sensors from qingping.py. */
    val PRODUCT_NAMES: Map<Int, String> = mapOf(
        0x01 to "CGG1",
        0x04 to "CGH1",
        0x07 to "CGG1",
        0x09 to "CGP1W",
        0x0C to "CGD1",
        0x0E to "CGDN1 (Air Monitor Lite)",
        0x10 to "CGDK2",
        0x12 to "CGPR1",
        0x16 to "CGG1-M",
        0x18 to "CGP23W",
        0x1E to "CGP22C",
        0x24 to "CGDN1 (Air Monitor Lite v2)",
        PRODUCT_ID_BTHOME_V2 to "BTHome v2 (ESPHome / Open Firmware)"
    )

    data class DecodedQingpingFrame(
        val timestampMs: Long,
        val frameControl: Int,
        val isEventOrBindingFrame: Boolean,
        val productId: Int,
        val productName: String,
        val macAddress: String,
        val temperatureC: Float? = null,
        val humidityPct: Float? = null,
        val batteryPct: Int? = null,
        val pm25Ugm3: Int? = null,
        val pm10Ugm3: Int? = null,
        val co2Ppm: Int? = null,
        val pressureHpa: Float? = null,
        val illuminanceLux: Int? = null,
        val rssiDbm: Int = -128,
        val rawHex: String = "",
        val unknownTlvs: List<Pair<Int, String>> = emptyList(),
        val hasZeroCo2Tlv: Boolean = false,
        val isBtHomeV2: Boolean = false
    ) {
        val isAirMonitorLite: Boolean
            get() = productId == 0x0E || productId == 0x24 || isBtHomeV2 || pm25Ugm3 != null || co2Ppm != null

        /**
         * True when the CGDN1 emits its final all-zero unbound-exit / radio-shutdown packet
         * (`0824...01040000000012040000000013020000`), where CO2 = 0 ppm, Temp = 0.0°C, and RH = 0.0%.
         */
        val isShutdownZeroFrame: Boolean
            get() = (co2Ppm == null || co2Ppm == 0) && temperatureC == 0.0f && humidityPct == 0.0f &&
                (pm25Ugm3 ?: 0) == 0 && (pm10Ugm3 ?: 0) == 0

        /**
         * True when the CGDN1 is broadcasting in provisioned normal mode (`frameControl == 0x88`,
         * i.e. bit 7 `0x80` set and pairing/unprovisioned bit 1 `0x02` cleared). In this state,
         * the monitor broadcasts unified `0xFDCD` frames indefinitely without BLE GATT keep-alives
         * (even when Wi-Fi is unreachable) while keeping its normal anti-burn-in OLED display.
         */
        val isProvisionedNormalMode: Boolean
            get() = !isBtHomeV2 && !isShutdownZeroFrame && (frameControl and 0x82) == 0x80

        /**
         * True when the NDIR sensor emits `13 02 00 00` (0 ppm) during ~5s post-reset warmup
         * while temperature/humidity are non-zero.
         */
        val isWarmupZeroCo2: Boolean
            get() = hasZeroCo2Tlv && !isShutdownZeroFrame

        val hasEnvironmentalMetrics: Boolean
            get() = !isShutdownZeroFrame &&
                (temperatureC != null || humidityPct != null || pm25Ugm3 != null || pm10Ugm3 != null || co2Ppm != null)
    }

    data class Fe95MiBeaconHeader(
        val frameCtrlHex: String,
        val productIdHex: String,
        val frameCounter: Int
    )

    fun parseFe95Header(fe95ServiceData: ByteArray?): Fe95MiBeaconHeader? {
        if (fe95ServiceData == null || fe95ServiceData.size < 5) return null
        val ctrl = ((fe95ServiceData[1].toInt() and 0xFF) shl 8) or (fe95ServiceData[0].toInt() and 0xFF)
        val pid = ((fe95ServiceData[3].toInt() and 0xFF) shl 8) or (fe95ServiceData[2].toInt() and 0xFF)
        val cnt = fe95ServiceData[4].toInt() and 0xFF
        return Fe95MiBeaconHeader(
            frameCtrlHex = String.format(Locale.US, "0x%04X", ctrl),
            productIdHex = String.format(Locale.US, "0x%04X", pid),
            frameCounter = cnt
        )
    }

    /**
     * Parses the raw Service Data payload associated with UUID 0xFDCD.
     *
     * Note: Android's `ScanRecord.getServiceData(ParcelUuid)` strips the 2-byte 0xFDCD UUID prefix
     * and returns the payload starting at byte 0 (`frameControl`). If a caller passes a full AD structure
     * including length/type/UUID (`0x16 0xCD 0xFD ...`), this method automatically strips that prefix.
     */
    fun parseServiceData(
        rawBytes: ByteArray,
        timestampMs: Long = System.currentTimeMillis(),
        rssiDbm: Int = -128
    ): DecodedQingpingFrame? {
        val payload = stripOptionalAdHeader(rawBytes)
        // Minimum header is 8 bytes: [0]=frameCtrl, [1]=productId, [2..7]=reversed MAC
        if (payload.size < 8) return null

        val frameControl = payload[0].toInt() and 0xFF
        val isEventFrame = (frameControl and 0x40) != 0
        val productId = payload[1].toInt() and 0xFF
        val productName = PRODUCT_NAMES[productId] ?: String.format(Locale.US, "Unknown (0x%02X)", productId)

        // Bytes 2..7 contain the 6-byte MAC address in reverse order (little-endian)
        val macBuilder = StringBuilder(17)
        for (i in 7 downTo 2) {
            if (macBuilder.isNotEmpty()) macBuilder.append(':')
            macBuilder.append(String.format(Locale.US, "%02X", payload[i].toInt() and 0xFF))
        }
        val macAddress = macBuilder.toString()

        var tempC: Float? = null
        var humidityPct: Float? = null
        var batteryPct: Int? = null
        var pm25: Int? = null
        var pm10: Int? = null
        var co2: Int? = null
        var hasZeroCo2 = false
        var pressureHpa: Float? = null
        var illuminanceLux: Int? = null
        val unknownTlvs = mutableListOf<Pair<Int, String>>()

        var offset = 8
        while (offset + 2 <= payload.size) {
            val xdataId = payload[offset].toInt() and 0xFF
            val xdataSize = payload[offset + 1].toInt() and 0xFF
            val valueStart = offset + 2
            val valueEnd = valueStart + xdataSize
            if (valueEnd > payload.size || xdataSize <= 0) {
                break
            }

            val buf = ByteBuffer.wrap(payload, valueStart, xdataSize).order(ByteOrder.LITTLE_ENDIAN)
            when (xdataId) {
                0x01 -> if (xdataSize == 4) {
                    tempC = buf.short.toFloat() / 10.0f
                    humidityPct = (buf.short.toInt() and 0xFFFF).toFloat() / 10.0f
                }
                0x02 -> if (xdataSize == 1) {
                    batteryPct = (buf.get().toInt() and 0xFF).coerceIn(0, 100)
                }
                0x07 -> if (xdataSize == 2) {
                    pressureHpa = (buf.short.toInt() and 0xFFFF).toFloat() / 100.0f
                }
                0x09 -> if (xdataSize == 4) {
                    illuminanceLux = buf.int
                }
                0x12 -> if (xdataSize == 4) {
                    pm25 = buf.short.toInt() and 0xFFFF
                    pm10 = buf.short.toInt() and 0xFFFF
                }
                0x13 -> if (xdataSize == 2) {
                    val rawCo2 = buf.short.toInt() and 0xFFFF
                    // NDIR CO2 sensor warmup after monitor reset emits 0 ppm (`13 02 00 00`) for ~5s while
                    // temperature/humidity are already valid; ignore 0 ppm so it never skews 15s slot averages.
                    if (rawCo2 > 0) {
                        co2 = rawCo2
                    } else {
                        hasZeroCo2 = true
                    }
                }
                else -> {
                    val slice = payload.copyOfRange(valueStart, valueEnd)
                    unknownTlvs.add(xdataId to toHexString(slice))
                }
            }
            offset = valueEnd
        }

        return DecodedQingpingFrame(
            timestampMs = timestampMs,
            frameControl = frameControl,
            isEventOrBindingFrame = isEventFrame,
            productId = productId,
            productName = productName,
            macAddress = macAddress,
            temperatureC = tempC,
            humidityPct = humidityPct,
            batteryPct = batteryPct,
            pm25Ugm3 = pm25,
            pm10Ugm3 = pm10,
            co2Ppm = co2,
            pressureHpa = pressureHpa,
            illuminanceLux = illuminanceLux,
            rssiDbm = rssiDbm,
            rawHex = toHexString(payload),
            unknownTlvs = unknownTlvs,
            hasZeroCo2Tlv = hasZeroCo2
        )
    }

    /**
     * Parses unencrypted BTHome v2 Service Data (`UUID = 0xFCD2`, https://bthome.io/format/),
     * enabling drop-in support for ESPHome and open-source custom firmwares on the `CGDN1`
     * (or any BTHome v2 environmental monitor) without requiring local app modifications.
     */
    fun parseBtHomeV2ServiceData(
        rawBytes: ByteArray,
        deviceAddress: String = "00:00:00:00:00:00",
        deviceName: String = "",
        timestampMs: Long = System.currentTimeMillis(),
        rssiDbm: Int = -128
    ): DecodedQingpingFrame? {
        val payload = stripOptionalBtHomeAdHeader(rawBytes)
        if (payload.size < 2) return null

        val deviceInfo = payload[0].toInt() and 0xFF
        val version = (deviceInfo ushr 5) and 0x07
        val isEncrypted = (deviceInfo and 0x01) != 0
        val hasEmbeddedMac = (deviceInfo and 0x02) != 0
        val isTriggerBased = (deviceInfo and 0x04) != 0

        // Only BTHome v2 (version == 2) unencrypted frames are decoded here
        if (version != 2 || isEncrypted) return null

        var offset = 1
        val macAddress = if (hasEmbeddedMac) {
            if (payload.size < 7) return null
            val sb = StringBuilder(17)
            for (i in 6 downTo 1) {
                if (sb.isNotEmpty()) sb.append(':')
                sb.append(String.format(Locale.US, "%02X", payload[i].toInt() and 0xFF))
            }
            offset = 7
            sb.toString()
        } else {
            deviceAddress
        }

        var tempC: Float? = null
        var humidityPct: Float? = null
        var batteryPct: Int? = null
        var pm25: Int? = null
        var pm10: Int? = null
        var co2: Int? = null
        var hasZeroCo2 = false
        var pressureHpa: Float? = null
        var illuminanceLux: Int? = null
        val unknownObjects = mutableListOf<Pair<Int, String>>()

        while (offset < payload.size) {
            val objId = payload[offset].toInt() and 0xFF
            offset += 1

            val valLen = if (objId == 0x53 || objId == 0x54) {
                // Length-prefixed text (0x53) or raw (0x54) object
                if (offset >= payload.size) break
                val l = payload[offset].toInt() and 0xFF
                offset += 1
                l
            } else {
                bthomeV2ObjectValueSize(objId)
            }

            if (valLen <= 0 || offset + valLen > payload.size) {
                break
            }

            val buf = ByteBuffer.wrap(payload, offset, valLen).order(ByteOrder.LITTLE_ENDIAN)
            when (objId) {
                0x00 -> Unit // Packet ID (uint8)
                0x01 -> batteryPct = (buf.get().toInt() and 0xFF).coerceIn(0, 100)
                0x02 -> tempC = buf.short.toFloat() * 0.01f
                0x03 -> humidityPct = (buf.short.toInt() and 0xFFFF).toFloat() * 0.01f
                0x04 -> {
                    val u24 = (payload[offset].toInt() and 0xFF) or
                        ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                        ((payload[offset + 2].toInt() and 0xFF) shl 16)
                    pressureHpa = u24.toFloat() * 0.01f
                }
                0x05 -> {
                    val u24 = (payload[offset].toInt() and 0xFF) or
                        ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                        ((payload[offset + 2].toInt() and 0xFF) shl 16)
                    illuminanceLux = (u24 * 0.01f).toInt()
                }
                0x0D -> pm25 = buf.short.toInt() and 0xFFFF
                0x0E -> pm10 = buf.short.toInt() and 0xFFFF
                0x12 -> {
                    val rawCo2 = buf.short.toInt() and 0xFFFF
                    if (rawCo2 > 0) {
                        co2 = rawCo2
                    } else {
                        hasZeroCo2 = true
                    }
                }
                0x2E -> humidityPct = (buf.get().toInt() and 0xFF).toFloat()
                0x45 -> tempC = buf.short.toFloat() * 0.1f
                0x57 -> tempC = buf.get().toFloat()
                0x58 -> tempC = buf.get().toFloat() * 0.35f
                else -> {
                    val slice = payload.copyOfRange(offset, offset + valLen)
                    unknownObjects.add(objId to toHexString(slice))
                }
            }
            offset += valLen
        }

        val resolvedName = if (deviceName.isNotBlank()) {
            "$deviceName (BTHome v2)"
        } else {
            PRODUCT_NAMES[PRODUCT_ID_BTHOME_V2] ?: "BTHome v2"
        }

        return DecodedQingpingFrame(
            timestampMs = timestampMs,
            frameControl = deviceInfo,
            isEventOrBindingFrame = isTriggerBased,
            productId = PRODUCT_ID_BTHOME_V2,
            productName = resolvedName,
            macAddress = macAddress,
            temperatureC = tempC,
            humidityPct = humidityPct,
            batteryPct = batteryPct,
            pm25Ugm3 = pm25,
            pm10Ugm3 = pm10,
            co2Ppm = co2,
            pressureHpa = pressureHpa,
            illuminanceLux = illuminanceLux,
            rssiDbm = rssiDbm,
            rawHex = toHexString(payload),
            unknownTlvs = unknownObjects,
            hasZeroCo2Tlv = hasZeroCo2,
            isBtHomeV2 = true
        )
    }

    private fun bthomeV2ObjectValueSize(objId: Int): Int = when (objId) {
        0x00, 0x01, 0x09, in 0x0F..0x11, in 0x15..0x2F, 0x3A, 0x46, 0x57, 0x58, 0x59 -> 1
        0x02, 0x03, 0x06, 0x07, 0x08, 0x0C, 0x0D, 0x0E, 0x12, 0x13, 0x14, 0x3C, 0x3D,
        0x3F, 0x40, 0x41, 0x43, 0x44, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x51, 0x52, 0x56,
        0x5A, 0x5D, 0xF0 -> 2
        0x04, 0x05, 0x0A, 0x0B, 0x42, 0x4B, 0x4F, 0xF2 -> 3
        0x3E, 0x4C, 0x4D, 0x4E, 0x50, 0x55, 0x5B, 0x5C, 0xF1 -> 4
        else -> -1
    }

    private fun stripOptionalBtHomeAdHeader(bytes: ByteArray): ByteArray {
        // Case 1: Starts directly with 0xD2 0xFC (little-endian 0xFCD2 UUID prefix)
        if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xD2 && (bytes[1].toInt() and 0xFF) == 0xFC) {
            return bytes.copyOfRange(2, bytes.size)
        }
        // Case 2: Full BLE AD structure: [len] [0x16 (Service Data 16-bit)] [0xD2] [0xFC] ...
        if (bytes.size >= 6 &&
            (bytes[1].toInt() and 0xFF) == 0x16 &&
            (bytes[2].toInt() and 0xFF) == 0xD2 &&
            (bytes[3].toInt() and 0xFF) == 0xFC
        ) {
            return bytes.copyOfRange(4, bytes.size)
        }
        return bytes
    }

    private fun stripOptionalAdHeader(bytes: ByteArray): ByteArray {
        // Case 1: Starts directly with 0xCD 0xFD (little-endian 0xFDCD UUID prefix)
        if (bytes.size >= 10 && (bytes[0].toInt() and 0xFF) == 0xCD && (bytes[1].toInt() and 0xFF) == 0xFD) {
            return bytes.copyOfRange(2, bytes.size)
        }
        // Case 2: Full BLE AD structure: [len] [0x16 (Service Data 16-bit)] [0xCD] [0xFD] ...
        if (bytes.size >= 12 &&
            (bytes[1].toInt() and 0xFF) == 0x16 &&
            (bytes[2].toInt() and 0xFF) == 0xCD &&
            (bytes[3].toInt() and 0xFF) == 0xFD
        ) {
            return bytes.copyOfRange(4, bytes.size)
        }
        return bytes
    }

    fun toHexString(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun fromHexString(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "").replace(":", "").replace("-", "")
        require(cleaned.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Frames a Qingping custom-service command as `[total_len, type, ...payload]` and splits it
     * into BLE ATT write chunks (default 20 bytes for standard MTU=23).
     */
    fun encodeGattLtvChunks(type: Int, payload: ByteArray, chunkSize: Int = 20): List<ByteArray> {
        val totalLen = 1 + payload.size
        require(totalLen in 1..255) { "GATT LTV total length must fit in 1 byte (1..255)" }
        val frame = ByteArray(1 + totalLen)
        frame[0] = totalLen.toByte()
        frame[1] = (type and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 2, payload.size)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + chunkSize, frame.size)
            chunks.add(frame.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    /** Returns true if [ssid] is non-blank and `"\"<ssid>\",\"<password>\""` fits within 254 UTF-8 bytes. */
    fun isValidPairBWifiCredentials(ssid: String, password: String): Boolean {
        if (ssid.isBlank()) return false
        val utf8Len = "\"$ssid\",\"$password\"".toByteArray(Charsets.UTF_8).size
        return utf8Len in 1..MAX_PAIR_B_STRING_UTF8_BYTES
    }

    /**
     * Encodes Pair B (`000D`) `cmd 0x01` Wi-Fi provisioning payload as `"\"<ssid>\",\"<password>\""`
     * split into 20-byte GATT write chunks.
     */
    fun encodePairBWifiCredentialsChunks(ssid: String, password: String, chunkSize: Int = 20): List<ByteArray> {
        val ascii = "\"$ssid\",\"$password\"".toByteArray(Charsets.UTF_8)
        return encodeGattLtvChunks(type = 0x01, payload = ascii, chunkSize = chunkSize)
    }

    /**
     * Encodes Pair B (`000D`) `cmd 0x02` (`setMqttServer`) payload `"<domain>,<port>,<account>,<password>"`
     * into 20-byte GATT write chunks (`[total_len, 0x02, ...utf8]`, raw continuation chunks).
     */
    fun encodePairBMqttServerChunks(mqttSpec: String, chunkSize: Int = 20): List<ByteArray> {
        val ascii = mqttSpec.toByteArray(Charsets.UTF_8)
        return encodeGattLtvChunks(type = 0x02, payload = ascii, chunkSize = chunkSize)
    }

    /** Returns true if [url] is non-empty and fits within Pair B's 1-byte length ceiling (<= 254 UTF-8 bytes). */
    fun isValidPairBOtaUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val utf8Len = trimmed.toByteArray(Charsets.UTF_8).size
        return utf8Len in 1..MAX_PAIR_B_STRING_UTF8_BYTES
    }

    /**
     * Encodes Pair B (`000D`) `cmd 0x03` (`startFirmwareUpgrade`) URL string payload
     * into 20-byte GATT write chunks (`[total_len, 0x03, ...utf8(url)]`, raw continuation chunks).
     */
    fun encodePairBOtaUpgradeChunks(url: String, chunkSize: Int = 20): List<ByteArray> {
        val ascii = url.trim().toByteArray(Charsets.UTF_8)
        require(ascii.isNotEmpty()) { "OTA firmware upgrade URL must not be empty" }
        require(ascii.size <= MAX_PAIR_B_STRING_UTF8_BYTES) {
            "OTA firmware upgrade URL exceeds Pair B 254-byte UTF-8 ceiling (${ascii.size} bytes)"
        }
        return encodeGattLtvChunks(type = 0x03, payload = ascii, chunkSize = chunkSize)
    }

    /**
     * Decodes Pair B (`000E`) notifications for `cmd 0x03` (`startFirmwareUpgrade`):
     * - Generic 5-byte status frame `[0x04, 0xFF, 0x03, status_lo, status_hi]`:
     *   `status_lo == 0` -> `progress` (accepted ACK, `0%`), any non-zero (`0x01` unauthorized, `0x02` invalid, `0xFF` rejected) -> `rejected`.
     * - 3-byte OTA status frame `[0x02, 0x03, val]` (or non-`0x04` `[len, 0x03, val]`):
     *   `0..99` (`0x00..0x63`) -> `progress`, `100` (`0x64`) -> `complete`, `101..255` (`0x65..0xFF`, e.g. `0xFA..0xFF` signed negative errors) -> `rejected`.
     */
    fun parsePairBOtaNotify(payload: ByteArray): PairBOtaNotifyStatus? {
        if (payload.size >= 4 &&
            (payload[0].toInt() and 0xFF) == 0x04 &&
            (payload[1].toInt() and 0xFF) == 0xFF &&
            (payload[2].toInt() and 0xFF) == 0x03
        ) {
            val statusLo = payload[3].toInt() and 0xFF
            return if (statusLo == 0) {
                PairBOtaNotifyStatus(rawValue = 0, state = "progress", progressPct = 0)
            } else {
                PairBOtaNotifyStatus(rawValue = statusLo, state = "rejected", progressPct = null)
            }
        }
        if (payload.size >= 3 &&
            (payload[0].toInt() and 0xFF) != 0x04 &&
            (payload[1].toInt() and 0xFF) == 0x03
        ) {
            val rawVal = payload[2].toInt() and 0xFF
            return when (rawVal) {
                in 0..99 -> PairBOtaNotifyStatus(rawValue = rawVal, state = "progress", progressPct = rawVal)
                100 -> PairBOtaNotifyStatus(rawValue = 100, state = "complete", progressPct = 100)
                else -> PairBOtaNotifyStatus(rawValue = rawVal, state = "rejected", progressPct = null)
            }
        }
        return null
    }

    /** Returns a human-readable diagnostic string for a Pair B `cmd 0x03` rejection/error status byte. */
    fun describePairBOtaRejectCode(rawVal: Int): String = when (rawVal and 0xFF) {
        0x01 -> "0x01 (Pair A auth required)"
        0xFA -> "0xFA (-6: flash write / CRC32 check failed; check for HTTP 301 redirect or missing CRC trailer)"
        0xFB -> "0xFB (-5: missing or zero Content-Length)"
        0xFC -> "0xFC (-4: HTTP/TLS connect failed)"
        0xFE -> "0xFE (-2: URL parse failed)"
        0xFF -> "0xFF (-1: network gate not online)"
        else -> String.format(Locale.US, "0x%02X", rawVal and 0xFF)
    }
}

/**
 * Assembles split BLE advertisement frames from the same MAC address within a short carry-forward
 * window (default 30 seconds) so that if the CGDN1 alternates packets containing (Temp/RH/PM2.5/PM10)
 * and packets containing (CO2/Battery), each emitted sample has a complete metric set.
 */
class QingpingFrameAssembler(
    private val carryForwardWindowMs: Long = 30_000L
) {
    private data class CachedMetric<T>(val value: T, val timestampMs: Long)

    private class DeviceState {
        var lastPacketTsMs: Long = 0L
        var tempC: CachedMetric<Float>? = null
        var humidityPct: CachedMetric<Float>? = null
        var batteryPct: CachedMetric<Int>? = null
        var pm25Ugm3: CachedMetric<Int>? = null
        var pm10Ugm3: CachedMetric<Int>? = null
        var co2Ppm: CachedMetric<Int>? = null
    }

    private val stateByMac = mutableMapOf<String, DeviceState>()

    data class AssembledSample(
        val frame: QingpingBleParser.DecodedQingpingFrame,
        val interPacketDeltaMs: Long?,
        val mergedTemperatureC: Float,
        val mergedHumidityPct: Float,
        val mergedBatteryPct: Int,
        val mergedPm25Ugm3: Int,
        val mergedPm10Ugm3: Int,
        val mergedCo2Ppm: Int
    )

    @Synchronized
    fun onFrame(frame: QingpingBleParser.DecodedQingpingFrame): AssembledSample {
        val now = frame.timestampMs
        val state = stateByMac.getOrPut(frame.macAddress) { DeviceState() }
        val deltaMs = if (state.lastPacketTsMs > 0L && now >= state.lastPacketTsMs) {
            now - state.lastPacketTsMs
        } else {
            null
        }
        state.lastPacketTsMs = now

        if (!frame.isShutdownZeroFrame) {
            frame.temperatureC?.let { state.tempC = CachedMetric(it, now) }
            frame.humidityPct?.let { state.humidityPct = CachedMetric(it, now) }
            frame.batteryPct?.let { state.batteryPct = CachedMetric(it, now) }
            frame.pm25Ugm3?.let { state.pm25Ugm3 = CachedMetric(it, now) }
            frame.pm10Ugm3?.let { state.pm10Ugm3 = CachedMetric(it, now) }
            frame.co2Ppm?.let { state.co2Ppm = CachedMetric(it, now) }
        }

        fun <T> freshOrDefault(cached: CachedMetric<T>?, defaultVal: T): T {
            if (cached != null && (now - cached.timestampMs) <= carryForwardWindowMs) {
                return cached.value
            }
            return defaultVal
        }

        return AssembledSample(
            frame = frame,
            interPacketDeltaMs = deltaMs,
            mergedTemperatureC = freshOrDefault(state.tempC, 0.0f),
            mergedHumidityPct = freshOrDefault(state.humidityPct, 0.0f),
            mergedBatteryPct = freshOrDefault(state.batteryPct, 255),
            mergedPm25Ugm3 = freshOrDefault(state.pm25Ugm3, 0),
            mergedPm10Ugm3 = freshOrDefault(state.pm10Ugm3, 0),
            mergedCo2Ppm = freshOrDefault(state.co2Ppm, 0)
        )
    }
}
