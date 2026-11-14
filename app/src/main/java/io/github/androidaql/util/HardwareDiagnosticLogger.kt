package io.github.androidaql.util

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent structured JSON-Lines logger (`files/hw_bringup_trace.jsonl`) + Logcat output (`AndroidAQL_HW`)
 * so that when testing against physical CGDN1 hardware, every BLE advertisement packet (including raw hex,
 * inter-packet delta_ms, RSSI, unknown TLVs), GATT service/characteristic callback, Wi-Fi transition,
 * and state-machine change is preserved on disk for `adb pull` inspection.
 */
class HardwareDiagnosticLogger(
    val logFile: File,
    private val maxFileSizeBytes: Long = 5L * 1024L * 1024L // 5 MB cap with rotation
) {
    @Volatile
    var configHeaderProvider: (() -> Map<String, Any?>)? = null

    @Synchronized
    fun logEvent(eventType: String, fields: Map<String, Any?>) {
        try {
            var rotatedOrNew = !logFile.exists() || logFile.length() == 0L
            if (logFile.exists() && logFile.length() > maxFileSizeBytes) {
                val backup = File(logFile.parentFile, "${logFile.name}.prev")
                if (backup.exists()) backup.delete()
                logFile.renameTo(backup)
                rotatedOrNew = true
            }
            val nowMs = System.currentTimeMillis()
            FileOutputStream(logFile, true).use { fos ->
                if (rotatedOrNew && eventType != "DEV_CONFIG_CHANGED") {
                    configHeaderProvider?.invoke()?.let { cfgFields ->
                        val hdr = JSONObject()
                        hdr.put("ts_ms", nowMs)
                        hdr.put("event", "DEV_CONFIG_STATE")
                        for ((k, v) in cfgFields) {
                            hdr.put(k, v)
                        }
                        val hdrLine = hdr.toString()
                        Log.i(TAG, hdrLine)
                        fos.write((hdrLine + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
                val obj = JSONObject()
                obj.put("ts_ms", nowMs)
                obj.put("event", eventType)
                for ((k, v) in fields) {
                    obj.put(k, v)
                }
                val line = obj.toString()
                Log.i(TAG, line)
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TAG = "AndroidAQL_HW"
    }
}
