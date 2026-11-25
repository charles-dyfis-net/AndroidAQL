package io.github.androidaql.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.androidaql.R
import io.github.androidaql.ble.QingpingBleParser
import io.github.androidaql.ble.QingpingFrameAssembler
import io.github.androidaql.engine.AlertEvaluator
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.engine.GpsMovementFilter
import io.github.androidaql.engine.StepMovementEstimator
import io.github.androidaql.export.XlsxImporter
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.KnownPlace
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.ui.MainActivity
import io.github.androidaql.util.HardwareDiagnosticLogger
import io.github.androidaql.util.TemperatureUnitFormatter
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Foreground Service managing:
 * 1. Passive 0xFDCD / BTHome v2 BLE scanning + Qingping TLV frame assembly
 * 2. Two-mode BLE / GATT lifecycle (`PASSIVE_ONLY`, `PERIODIC_GATT_KEEPALIVE`)
 * 3. Event-driven Saved Wi-Fi Place detection via `ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`
 * 4. Strictly BLE-gated GPS movement speed/accuracy collection (coordinate-free by default)
 * 5. O(1) writes into `.aqlring` binary ring buffer + in-place stateful alert notifications
 */
class AqlCollectorService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): AqlCollectorService = this@AqlCollectorService
    }

    enum class KeepAlivePayloadStrategy(
        val id: String,
        @param:StringRes val shortLabelRes: Int,
        @param:StringRes val settingsDescriptionRes: Int,
        @param:StringRes val statusBadgeRes: Int
    ) {
        AUTO_OFFLINE_SETUP(
            id = "AUTO_OFFLINE_SETUP",
            shortLabelRes = R.string.dev_keep_alive_short_auto_offline_setup,
            settingsDescriptionRes = R.string.settings_keep_alive_desc_auto_offline_setup,
            statusBadgeRes = R.string.keep_alive_badge_auto_offline_setup
        ),
        TIME_SYNC_0509_PLUS_24M_1101(
            id = "0509_24M_1101",
            shortLabelRes = R.string.dev_keep_alive_short_0509_24m_1101,
            settingsDescriptionRes = R.string.dev_settings_keep_alive_desc_0509_24m_1101,
            statusBadgeRes = R.string.dev_keep_alive_badge_0509_24m_1101
        ),
        TIME_SYNC_0509(
            id = "0509_ONLY",
            shortLabelRes = R.string.dev_keep_alive_short_0509_only,
            settingsDescriptionRes = R.string.dev_settings_keep_alive_desc_0509_only,
            statusBadgeRes = R.string.dev_keep_alive_badge_0509_only
        ),
        NONE_PASSIVE(
            id = "NONE_PASSIVE",
            shortLabelRes = R.string.dev_keep_alive_short_none_passive,
            settingsDescriptionRes = R.string.settings_keep_alive_desc_none_passive,
            statusBadgeRes = R.string.keep_alive_badge_none_passive
        );

        fun getShortLabel(context: Context): String =
            context.getString(shortLabelRes)

        fun getSettingsDescription(context: Context): String =
            context.getString(settingsDescriptionRes)

        fun getStatusBadge(context: Context): String =
            context.getString(statusBadgeRes)

        companion object {
            val userAccessibleStrategies: List<KeepAlivePayloadStrategy> = listOf(
                AUTO_OFFLINE_SETUP,
                NONE_PASSIVE
            )

            fun fromId(id: String?): KeepAlivePayloadStrategy {
                if (id.equals("AUTO_EXCURSION_1101", ignoreCase = true)) {
                    return AUTO_OFFLINE_SETUP
                }
                return entries.firstOrNull {
                    it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true)
                } ?: AUTO_OFFLINE_SETUP
            }
        }
    }

    data class DevKeepAliveConfig(
        val configEpoch: Int = 1,
        val strategy: KeepAlivePayloadStrategy = KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP,
        val intervalSec: Int = 210,
        val retryCooldownSec: Int = 15,
        val triggerOnFe95Adv: Boolean = true,
        val deduplicateFdcdTrace: Boolean = true
    ) {
        fun toLogMap(): Map<String, Any?> = mapOf(
            "cfg_epoch" to configEpoch,
            "strategy" to strategy.id,
            "interval_sec" to intervalSec,
            "retry_sec" to retryCooldownSec,
            "trig_fe95" to triggerOnFe95Adv,
            "dedup_fdcd" to deduplicateFdcdTrace
        )
    }

    data class LiveTelemetrySnapshot(
        val collectorState: ExcursionStateMachine.CollectorState,
        val effectiveBleMode: BleCollectionMode,
        val userExcursionBleMode: BleCollectionMode,
        val strictFdcdFilterEnabled: Boolean,
        val devConfig: DevKeepAliveConfig,
        val connectedWifiSsid: String?,
        val connectedWifiBssid: String?,
        val matchedKnownPlace: KnownPlace?,
        val hasConfiguredKnownPlaces: Boolean = true,
        val activeExcursionId: Long?,
        val lastFrame: QingpingBleParser.DecodedQingpingFrame?,
        val interPacketDeltaMs: Long?,
        val mergedCo2Ppm: Int,
        val mergedPm25Ugm3: Int,
        val mergedPm10Ugm3: Int,
        val mergedTempC: Float,
        val mergedHumidityPct: Float,
        val mergedBatteryPct: Int,
        val isGpsActive: Boolean,
        val isStepSensorActive: Boolean,
        val currentSpeedMps: Float?,
        val currentSpeedSource: StepMovementEstimator.SpeedSource,
        val currentGpsAccuracyM: Float?,
        val gattConnectionStatus: String,
        val gattDiscoveredServicesLog: List<String>,
        val recentRawFramesLog: List<String>,
        val isPendingWifiLossDebounce: Boolean = false
    )

    data class ActiveLinkableMonitor(
        val bleAddress: String,
        val displayName: String?
    )

    data class StatusNotificationKey(
        val title: String,
        val body: String,
        val hasActiveExcursion: Boolean
    )

    data class CompanionIdleSleepInput(
        val hasLinkedCompanionDevice: Boolean,
        val collectorState: ExcursionStateMachine.CollectorState,
        val hasActiveExcursion: Boolean,
        val hasPendingBlipCleanup: Boolean,
        val hasBoundUiClients: Boolean,
        val isDemoStreamRunning: Boolean,
        val isOtaUpgradeInFlight: Boolean,
        val hasActiveGattSession: Boolean,
        val isInUnprovisionedGattKeepAliveWindow: Boolean,
        val isManualExcursionOverride: Boolean,
        val nowMs: Long,
        val idleOutOfRangeSinceMs: Long?,
        val lastUiUnbindMs: Long,
        val serviceStartedAtMs: Long,
        val lastCompanionAppearedMs: Long,
        val companionDisappearedAtMs: Long?,
        val lastLiveBleHeardMs: Long
    )

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var metadataDb: MetadataDatabase
        private set
    lateinit var ringStore: AqlRingBufferStore
        private set
    lateinit var hwLogger: HardwareDiagnosticLogger
        private set

    val stateMachine = ExcursionStateMachine()
    val alertEvaluator = AlertEvaluator()
    val gpsMovementFilter = GpsMovementFilter()
    val stepMovementEstimator = StepMovementEstimator()
    private val frameAssembler = QingpingFrameAssembler()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var activeScanMode: Int = -1
    private var lastScanStartMs: Long = 0L
    @Volatile private var lastAnyScanResultMs: Long = 0L
    var strictFdcdFilterEnabled: Boolean = true
        private set

    private var boundClientCount: Int = 0
    private var lastUiUnbindMs: Long = 0L
    private var serviceStartedAtMs: Long = 0L
    private var idleOutOfRangeSinceMs: Long? = null
    private var lastCompanionAppearedMs: Long = 0L
    private var companionDisappearedAtMs: Long? = null
    private var hasLinkedCompanionDevice: Boolean = false
    @Volatile private var isSleepingForAbsentCompanion: Boolean = false
    @Volatile private var isUserStopping: Boolean = false
    private var isWatchdogScheduled: Boolean = false
    private var lastPostedStatusNotificationKey: StatusNotificationKey? = null
    @Volatile private var lastLiveTelemetryBleAddress: String? = null
    @Volatile private var lastLiveTelemetryDeviceName: String? = null
    @Volatile private var lastLiveTelemetryBleMs: Long = 0L
    private var lastRealBleHeardMs: Long = 0L
    private var isExternalPowerConnected: Boolean = false
    private var isPowerReceiverRegistered: Boolean = false

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val plugged = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            if (isExternalPowerConnected != plugged) {
                isExternalPowerConnected = plugged
                syncBleScanModeIfNeeded()
            }
        }
    }

    private var activeGatt: BluetoothGatt? = null
    private var isGattProbeInFlight: Boolean = false
    @Volatile private var pendingOneShotGattScript: String? = null
    @Volatile private var pendingWifiCredentials: Pair<String, String>? = null
    @Volatile private var pendingOtaUrl: String? = null
    @Volatile private var isOtaUpgradeInFlight: Boolean = false
    private var otaInactivityTimeoutRunnable: Runnable? = null
    private var gattStatusText: String = "Disconnected (Passive Mode)"
    private val gattServicesLog = mutableListOf<String>()
    private var lastPeriodicGattPokeMs: Long = 0L
    private var lastSeenBleDevice: BluetoothDevice? = null
    private var lastFdcdBleDevice: BluetoothDevice? = null
    private var lastConnectableQingpingDevice: BluetoothDevice? = null
    private var hasCompletedInitialGattDiscovery: Boolean = false
    private var lastAutoGattAttemptMs: Long = 0L

    var devConfig: DevKeepAliveConfig = DevKeepAliveConfig()
        private set
    private var gattSessionSeq: Int = 0
    private var lastWindowReset1101Ms: Long = 0L
    private var lastMonitorHeardMs: Long = 0L
    private var lastFe95FrameCntByMac = HashMap<String, Int>()
    private var lastLoggedFdcdRawHex: String = ""
    private var lastLoggedFdcdTsMs: Long = 0L
    private var skippedFdcdRepeatCount: Int = 0
    private var prevDecodedFrameCtrl: Int = -1

    private var connectivityManager: ConnectivityManager? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var rawWifiSsid: String? = null
    private var currentWifiBssid: String? = null
    var fakeWifiSsidOverride: String? = null
        private set
    private var manualKnownPlaceOverrideId: Int = 0

    private var locationManager: LocationManager? = null
    private var isLocationActive = false
    private var lastLocationFix: Location? = null
    private var latestGpsFilteredSpeedMps: Float? = null
    private var latestSpeedMps: Float? = null
    private var latestSpeedSource: StepMovementEstimator.SpeedSource = StepMovementEstimator.SpeedSource.NONE
    private var latestGpsAccuracyM: Float? = null

    private var sensorManager: SensorManager? = null
    private var activeStepSensor: Sensor? = null
    private var isStepSensorActive = false

    private var activeExcursionId: Long? = null
    private var pendingBlipCleanupAfterEpochSec: Long? = null
    private var lastSampledFrame: QingpingBleParser.DecodedQingpingFrame? = null
    private var lastInterPacketDeltaMs: Long? = null
    private var latestCo2Ppm: Int = 0
    private var latestPm25Ugm3: Int = 0
    private var latestPm10Ugm3: Int = 0
    private var latestTempC: Float = 0f
    private var latestHumidityPct: Float = 0f
    private var latestBatteryPct: Int = 255

    private val rawFrameHistory = ArrayDeque<String>(40)
    private val lastNonFdcdLogMsByMac = HashMap<String, Long>()
    private val listeners = mutableSetOf<(LiveTelemetrySnapshot) -> Unit>()
    private val userStopListeners = mutableSetOf<() -> Unit>()
    var isDemoStreamRunning: Boolean = false
        private set
    private var demoTickCounter: Int = 0
    private var demoStreamRows: List<XlsxImporter.ParsedSampleRow> = emptyList()

    private val demoStreamRunnable = object : Runnable {
        override fun run() {
            if (!isDemoStreamRunning) return
            emitNextDemoReplayFrame()
            mainHandler.postDelayed(this, 3_000L)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            val transition = stateMachine.onTimerTick(nowSec)
            applyStateTransition(transition, nowSec)
            val cleanupAt = pendingBlipCleanupAfterEpochSec
            if (cleanupAt != null && nowSec >= cleanupAt) {
                pendingBlipCleanupAfterEpochSec = null
                metadataDb.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowSec)
            }
            refreshEffectiveSpeed()
            if (maybeSleepForAbsentCompanion(nowMs)) {
                return
            }
            syncBleScanModeIfNeeded()
            restartScanIfStalled()
            updateOngoingForegroundNotification()
            notifyListeners()
            isWatchdogScheduled = true
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    private fun ensureWatchdogScheduled() {
        if (!isWatchdogScheduled) {
            isWatchdogScheduled = true
            mainHandler.postDelayed(watchdogRunnable, 5_000L)
        }
    }

    /**
     * Guards against the OS silently demoting or starving our scan (see note in [startBleScan]):
     * - Broad (unfiltered) discovery mode: proactively restart every 4 min.
     * - Idle out-of-range hardware-filtered mode: defer restarts to 15 min so we don't churn BLE every 2 min.
     * - Active monitoring/excursion: if no scan result arrives for 60s, restart (at most every 2 min).
     */
    private fun restartScanIfStalled() {
        val nowMs = System.currentTimeMillis()
        val sinceStart = nowMs - lastScanStartMs
        val sinceResult = nowMs - lastAnyScanResultMs
        val restartReason = shouldRestartBleScan(
            isScanning = isScanning,
            strictFdcdFilterEnabled = strictFdcdFilterEnabled,
            collectorState = stateMachine.currentState,
            msSinceStart = sinceStart,
            msSinceAnyResult = sinceResult
        ) ?: return

        hwLogger.logEvent(
            "BLE_SCAN_RESTART",
            mapOf(
                "reason" to restartReason,
                "scan_mode" to activeScanMode,
                "ms_since_start" to sinceStart,
                "ms_since_any_result" to sinceResult
            )
        )
        stopBleScan()
        startBleScan()
    }

    /** OR'ed hardware scan filters matching CGDN1 (0xFDCD / 0xFE95) and BTHome v2 (0xFCD2) advertisements. */
    private fun buildQingpingScanFilters(): List<ScanFilter> {
        val filters = mutableListOf(
            // :BA telemetry beacon — 16-bit Service Data 0xFDCD (empty data/mask = match any payload)
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(QingpingBleParser.QINGPING_SERVICE_UUID_STRING), ByteArray(0))
                .build(),
            // BTHome v2 telemetry beacon (ESPHome / custom firmware) — 16-bit Service Data 0xFCD2
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(QingpingBleParser.BTHOME_V2_SERVICE_UUID_STRING), ByteArray(0))
                .build(),
            // :BC connectable MiBeacon + GATT server — 16-bit Service Data 0xFE95
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(XIAOMI_MIBEACON_SERVICE_UUID_STRING), ByteArray(0))
                .build(),
            ScanFilter.Builder().setDeviceName(QINGPING_AIR_MONITOR_LITE_NAME).build()
        )
        // Belt-and-braces: exact-address filters for any CGDN1 MACs already seen this session.
        listOfNotNull(lastSeenBleDevice?.address, lastConnectableQingpingDevice?.address)
            .distinct()
            .forEach { addr -> filters.add(ScanFilter.Builder().setDeviceAddress(addr).build()) }
        return filters
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nowMs = System.currentTimeMillis()
        serviceStartedAtMs = nowMs
        idleOutOfRangeSinceMs = nowMs
        metadataDb = MetadataDatabase(applicationContext)
        val ringFile = File(filesDir, "device_1.aqlring")
        ringStore = AqlRingBufferStore(ringFile, deviceId = 1L)
        metadataDb.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore)
        stateMachine.wifiLossDebounceSec = getWifiLossDebounceSec()
        devConfig = loadDevKeepAliveConfig()
        fakeWifiSsidOverride = getSharedPreferences("aql_dev_keepalive", Context.MODE_PRIVATE)
            .getString("fake_wifi_ssid", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        hwLogger = HardwareDiagnosticLogger(File(filesDir, "hw_bringup_trace.jsonl")).apply {
            configHeaderProvider = { devConfig.toLogMap() }
        }
        hwLogger.logEvent(
            "SERVICE_START",
            mapOf("sdk_int" to android.os.Build.VERSION.SDK_INT) + devConfig.toLogMap()
        )

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        hasLinkedCompanionDevice = CompanionDeviceHelper.hasAssociations(this)
        if (hasLinkedCompanionDevice) {
            CompanionDeviceHelper.ensureObservingAll(this)
        }

        createNotificationChannels()
        registerPowerConnectionReceiver()
        registerWifiCallback()
        restoreLastSampleFromRingBuffer()
        // Do not auto-seed synthetic demo data on startup so physical hardware ring buffers remain pure.
        isWatchdogScheduled = true
        mainHandler.post(watchdogRunnable)
    }

    private fun restoreLastSampleFromRingBuffer() {
        val nowSec = System.currentTimeMillis() / 1000L
        val recentSlot = ringStore.queryRange(0, nowSec - 24L * 3600L, nowSec)
            .lastOrNull { it.sampleCount > 0 && it.co2PpmMean > 0 } ?: return
        val dev = metadataDb.getPrimaryDevice()
        latestCo2Ppm = recentSlot.co2PpmMean
        latestPm25Ugm3 = recentSlot.pm25Mean.toInt()
        latestPm10Ugm3 = recentSlot.pm10Mean.toInt()
        latestTempC = recentSlot.tempCMean
        latestHumidityPct = recentSlot.humidityPctMean
        latestBatteryPct = recentSlot.batteryPctLast
        lastSampledFrame = QingpingBleParser.DecodedQingpingFrame(
            timestampMs = recentSlot.bucketEpochSec * 1000L,
            frameControl = 0x0A,
            isEventOrBindingFrame = false,
            productId = 0x24,
            productName = dev?.modelCode ?: "CGDN1",
            macAddress = dev?.macAddress ?: "00:00:00:00:00:00",
            temperatureC = recentSlot.tempCMean,
            humidityPct = recentSlot.humidityPctMean,
            batteryPct = recentSlot.batteryPctLast.takeIf { it in 0..100 },
            pm25Ugm3 = recentSlot.pm25Mean.toInt(),
            pm10Ugm3 = recentSlot.pm10Mean.toInt(),
            co2Ppm = recentSlot.co2PpmMean,
            rssiDbm = recentSlot.rssiDbmLast,
            rawHex = "",
            unknownTlvs = emptyList()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startReason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: if (intent == null) "sticky_restart" else "activity"
        val nowMs = System.currentTimeMillis()
        isUserStopping = false
        if (startReason == "activity" || intent?.action == ACTION_DEV_COMMAND) {
            setUserStoppedService(this, false)
        }
        val wasSleeping = isSleepingForAbsentCompanion
        isSleepingForAbsentCompanion = false
        if (wasSleeping) {
            serviceStartedAtMs = nowMs
            if (stateMachine.currentState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE) {
                idleOutOfRangeSinceMs = nowMs
            }
        }
        if (startReason == "companion_device_appeared" || startReason == "companion_presence_event") {
            lastCompanionAppearedMs = nowMs
            companionDisappearedAtMs = null
        }
        hwLogger.logEvent(
            "SERVICE_START_COMMAND",
            mapOf("reason" to startReason, "start_id" to startId)
        )
        val enteredForeground = startForegroundWithCurrentType(startReason)
        if (!enteredForeground) {
            postResumeRequiredNotification(this, startReason)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        cancelResumeRequiredNotification(this)
        ensureWatchdogScheduled()
        startBleScan()
        if (intent?.action == ACTION_DEV_COMMAND && isDevCommandAuthorized(this)) {
            handleDevCommandIntent(intent)
        }
        return START_STICKY
    }

    private fun loadDevKeepAliveConfig(): DevKeepAliveConfig {
        val prefs = getSharedPreferences("aql_dev_keepalive", Context.MODE_PRIVATE)
        return DevKeepAliveConfig(
            configEpoch = prefs.getInt("cfg_epoch", 1),
            strategy = KeepAlivePayloadStrategy.fromId(prefs.getString("strategy", KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP.id)),
            intervalSec = prefs.getInt("interval_sec", 210).coerceIn(30, 600),
            retryCooldownSec = prefs.getInt("retry_sec", 15).coerceIn(5, 120),
            triggerOnFe95Adv = prefs.getBoolean("trig_fe95", true),
            deduplicateFdcdTrace = prefs.getBoolean("dedup_fdcd", true)
        )
    }

    fun updateDevKeepAliveConfig(
        strategy: KeepAlivePayloadStrategy = devConfig.strategy,
        intervalSec: Int = devConfig.intervalSec,
        retryCooldownSec: Int = devConfig.retryCooldownSec,
        triggerOnFe95Adv: Boolean = devConfig.triggerOnFe95Adv,
        deduplicateFdcdTrace: Boolean = devConfig.deduplicateFdcdTrace,
        reason: String = "ui_update"
    ) {
        val nextEpoch = devConfig.configEpoch + 1
        val updated = DevKeepAliveConfig(
            configEpoch = nextEpoch,
            strategy = strategy,
            intervalSec = intervalSec.coerceIn(30, 600),
            retryCooldownSec = retryCooldownSec.coerceIn(5, 120),
            triggerOnFe95Adv = triggerOnFe95Adv,
            deduplicateFdcdTrace = deduplicateFdcdTrace
        )
        devConfig = updated
        getSharedPreferences("aql_dev_keepalive", Context.MODE_PRIVATE).edit()
            .putInt("cfg_epoch", updated.configEpoch)
            .putString("strategy", updated.strategy.id)
            .putInt("interval_sec", updated.intervalSec)
            .putInt("retry_sec", updated.retryCooldownSec)
            .putBoolean("trig_fe95", updated.triggerOnFe95Adv)
            .putBoolean("dedup_fdcd", updated.deduplicateFdcdTrace)
            .apply()
        hwLogger.logEvent(
            "DEV_CONFIG_CHANGED",
            updated.toLogMap() + mapOf("reason" to reason)
        )
        appendRawFrameLog("DEV CFG [epoch=${updated.configEpoch}]: strategy=${updated.strategy.id} int=${updated.intervalSec}s dedup=${updated.deduplicateFdcdTrace}")
        notifyListeners()
    }

    fun logDeveloperNote(note: String) {
        if (note.isBlank()) return
        hwLogger.logEvent(
            "DEV_USER_NOTE",
            mapOf(
                "cfg_epoch" to devConfig.configEpoch,
                "strategy" to devConfig.strategy.id,
                "note" to note.trim()
            )
        )
        appendRawFrameLog("NOTE [epoch=${devConfig.configEpoch}]: ${note.trim()}")
        notifyListeners()
    }

    @SuppressLint("MissingPermission")
    fun triggerImmediateKeepAlivePoke(reason: String = "manual_poke") {
        if (isOtaUpgradeInFlight && reason != "adb_ota_upgrade") {
            hwLogger.logEvent(
                "DEV_GATT_POKE_SUPPRESSED",
                mapOf("cfg_epoch" to devConfig.configEpoch, "reason" to reason)
            )
            return
        }
        val dev = resolveConnectableQingpingDevice() ?: lastSeenBleDevice
        if (dev == null) {
            if (reason == "adb_ota_upgrade") {
                isOtaUpgradeInFlight = false
                pendingOtaUrl = null
                hwLogger.logEvent(
                    "DEV_OTA_REFUSED",
                    mapOf("cfg_epoch" to devConfig.configEpoch, "reason" to "no_ble_device_seen")
                )
            }
            gattStatusText = "No CGDN1 BLE device seen yet to poke GATT"
            notifyListeners()
            return
        }
        hwLogger.logEvent(
            "DEV_MANUAL_POKE",
            mapOf(
                "cfg_epoch" to devConfig.configEpoch,
                "strategy" to devConfig.strategy.id,
                "mac" to dev.address,
                "reason" to reason
            )
        )
        val shouldDisconnect = reason != "adb_ota_upgrade"
        connectGattInternal(dev, disconnectAfterDiscovery = shouldDisconnect)
    }

    private fun handleDevCommandIntent(intent: Intent) {
        if (intent.hasExtra("fake_wifi_ssid")) {
            setFakeWifiSsidOverride(intent.getStringExtra("fake_wifi_ssid"))
        }
        intent.getStringExtra("note")?.let { logDeveloperNote(it) }
        val hasCfgChange = intent.hasExtra("strategy") ||
            intent.hasExtra("interval_sec") ||
            intent.hasExtra("retry_sec") ||
            intent.hasExtra("trig_fe95") ||
            intent.hasExtra("dedup_fdcd")
        if (hasCfgChange) {
            val strat = intent.getStringExtra("strategy")?.let { KeepAlivePayloadStrategy.fromId(it) } ?: devConfig.strategy
            val intSec = intent.getIntExtra("interval_sec", devConfig.intervalSec)
            val retrySec = intent.getIntExtra("retry_sec", devConfig.retryCooldownSec)
            val trigFe95 = intent.getBooleanExtra("trig_fe95", devConfig.triggerOnFe95Adv)
            val dedup = intent.getBooleanExtra("dedup_fdcd", devConfig.deduplicateFdcdTrace)
            updateDevKeepAliveConfig(
                strategy = strat,
                intervalSec = intSec,
                retryCooldownSec = retrySec,
                triggerOnFe95Adv = trigFe95,
                deduplicateFdcdTrace = dedup,
                reason = "adb_intent"
            )
        }
        val explicitOtaUrl = intent.getStringExtra("ota_url")?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitOtaUrl != null) {
            val utf8Len = explicitOtaUrl.toByteArray(Charsets.UTF_8).size
            if (!QingpingBleParser.isValidPairBOtaUrl(explicitOtaUrl)) {
                hwLogger.logEvent(
                    "DEV_OTA_REFUSED",
                    mapOf(
                        "cfg_epoch" to devConfig.configEpoch,
                        "reason" to "invalid_or_oversized_url",
                        "utf8_len" to utf8Len
                    )
                )
                gattStatusText = "OTA refused: URL UTF-8 length ($utf8Len B) must be 1..${QingpingBleParser.MAX_PAIR_B_STRING_UTF8_BYTES} bytes"
                appendRawFrameLog(gattStatusText)
                notifyListeners()
                return
            }
            val forceOta = intent.getBooleanExtra("force_ota", false)
            val frame = lastSampledFrame
            val nowMs = System.currentTimeMillis()
            val recent0x88Online = frame != null &&
                frame.isProvisionedNormalMode &&
                (nowMs - frame.timestampMs) <= 120_000L
            if (!recent0x88Online && !forceOta) {
                val frameCtrl = frame?.let { String.format(Locale.US, "0x%02X", it.frameControl) } ?: "none"
                val ageSec = frame?.let { (nowMs - it.timestampMs) / 1000L } ?: -1L
                hwLogger.logEvent(
                    "DEV_OTA_REFUSED",
                    mapOf(
                        "cfg_epoch" to devConfig.configEpoch,
                        "reason" to "not_in_0x88_mode",
                        "last_frame_ctrl" to frameCtrl,
                        "last_frame_age_sec" to ageSec
                    )
                )
                gattStatusText = "OTA refused: monitor not online in 0x88 Wi-Fi mode (last=$frameCtrl, age=${ageSec}s; pass --force-ota to override)"
                appendRawFrameLog(gattStatusText)
                notifyListeners()
                return
            }
            pendingOtaUrl = explicitOtaUrl
            isOtaUpgradeInFlight = true
            hwLogger.logEvent(
                "DEV_OTA_QUEUED",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "url" to explicitOtaUrl,
                    "utf8_len" to utf8Len,
                    "force_ota" to forceOta
                )
            )
            appendRawFrameLog("DEV OTA queued (Pair A 1102 auth + Pair B cmd 3): $explicitOtaUrl")
            triggerImmediateKeepAlivePoke("adb_ota_upgrade")
            return
        }

        val wifiSsid = intent.getStringExtra("wifi_ssid")?.takeIf { it.isNotEmpty() }
        if (wifiSsid != null) {
            val wifiPassword = intent.getStringExtra("wifi_password") ?: ""
            if (!QingpingBleParser.isValidPairBWifiCredentials(wifiSsid, wifiPassword)) {
                hwLogger.logEvent(
                    "DEV_WIFI_CONFIG_REFUSED",
                    mapOf(
                        "cfg_epoch" to devConfig.configEpoch,
                        "ssid" to wifiSsid,
                        "reason" to "invalid_or_oversized_credentials"
                    )
                )
                gattStatusText = "Wi-Fi config refused: SSID must be non-blank and quoted credential pair <= ${QingpingBleParser.MAX_PAIR_B_STRING_UTF8_BYTES} UTF-8 bytes"
                appendRawFrameLog(gattStatusText)
                notifyListeners()
                return
            }
            pendingWifiCredentials = wifiSsid to wifiPassword
            hwLogger.logEvent(
                "DEV_WIFI_CONFIG_QUEUED",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "ssid" to wifiSsid,
                    "password_len" to wifiPassword.length
                )
            )
            appendRawFrameLog("DEV WIFI queued for SSID=$wifiSsid (credentials redacted)")
            triggerImmediateKeepAlivePoke("adb_wifi_config")
            return
        }

        val gattScript = intent.getStringExtra("gatt_script")?.trim()?.takeIf { it.isNotEmpty() }
        if (gattScript != null) {
            pendingOneShotGattScript = gattScript
            val sanitizedScript = gattScript.split(';').joinToString(";") { part ->
                val trimmed = part.trim()
                if (trimmed.startsWith("b_wifi:", ignoreCase = true)) {
                    val afterPrefix = trimmed.substring(7)
                    val colonIdx = afterPrefix.indexOf(':')
                    val ssidPart = if (colonIdx >= 0) afterPrefix.substring(0, colonIdx) else afterPrefix
                    "b_wifi:$ssidPart:<redacted>"
                } else {
                    part
                }
            }
            hwLogger.logEvent(
                "DEV_GATT_SCRIPT_QUEUED",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "script" to sanitizedScript
                )
            )
            appendRawFrameLog("DEV SCRIPT queued: $sanitizedScript")
        }
        if (intent.getBooleanExtra("poke_now", false) || gattScript != null) {
            triggerImmediateKeepAlivePoke(if (gattScript != null) "adb_gatt_script" else "adb_intent_poke")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        onClientBound()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        onClientBound()
    }

    private fun onClientBound() {
        boundClientCount++
        isUserStopping = false
        val wasSleeping = isSleepingForAbsentCompanion
        isSleepingForAbsentCompanion = false
        if (wasSleeping) {
            serviceStartedAtMs = System.currentTimeMillis()
            if (stateMachine.currentState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE) {
                idleOutOfRangeSinceMs = serviceStartedAtMs
            }
        }
        ensureWatchdogScheduled()
        if (hasBluetoothScanPermission(this)) {
            startBleScan()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClientCount = (boundClientCount - 1).coerceAtLeast(0)
        if (boundClientCount == 0) {
            lastUiUnbindMs = System.currentTimeMillis()
        }
        return true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        isWatchdogScheduled = false
        lastPostedStatusNotificationKey = null
        unregisterPowerConnectionReceiver()
        stopBleScan()
        disconnectGatt()
        stopGpsCollection()
        stopStepCollection()
        unregisterWifiCallback()
        cancelManualTripStopSuggestionNotification()
        ringStore.close()
        metadataDb.close()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun registerPowerConnectionReceiver() {
        val batteryStatus = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val pluggedFlags = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        isExternalPowerConnected = pluggedFlags != 0

        if (!isPowerReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    powerConnectionReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isPowerReceiverRegistered = true
            }
        }
    }

    private fun unregisterPowerConnectionReceiver() {
        if (!isPowerReceiverRegistered) return
        runCatching {
            unregisterReceiver(powerConnectionReceiver)
        }
        isPowerReceiverRegistered = false
    }

    fun refreshCompanionAssociationCache() {
        val linked = CompanionDeviceHelper.hasAssociations(this)
        hasLinkedCompanionDevice = linked
        if (linked) {
            cancelCompanionLinkSuggestionNotification(this)
            CompanionDeviceHelper.ensureObservingAll(this)
        }
    }

    private fun onCompanionPresenceChanged(appeared: Boolean, reason: String) {
        val nowMs = System.currentTimeMillis()
        hwLogger.logEvent(
            if (appeared) "COMPANION_DEVICE_APPEARED" else "COMPANION_DEVICE_DISAPPEARED",
            mapOf("reason" to reason, "state" to stateMachine.currentState.name)
        )
        if (appeared) {
            lastCompanionAppearedMs = nowMs
            companionDisappearedAtMs = null
            isSleepingForAbsentCompanion = false
            cancelResumeRequiredNotification(this)
            ensureWatchdogScheduled()
            if (hasBluetoothScanPermission(this)) {
                startBleScan()
                syncBleScanModeIfNeeded()
            }
        } else {
            companionDisappearedAtMs = nowMs
        }
    }

    private fun isInUnprovisionedGattKeepAliveWindow(nowMs: Long): Boolean {
        if (devConfig.strategy == KeepAlivePayloadStrategy.NONE_PASSIVE) return false
        val frame = lastSampledFrame ?: return false
        if (frame.isProvisionedNormalMode || frame.isBtHomeV2) return false
        val lastHeardMs = maxOf(frame.timestampMs, lastMonitorHeardMs)
        return lastHeardMs > 0L && (nowMs - lastHeardMs) <= 11 * 60 * 1000L
    }

    private fun maybeSleepForAbsentCompanion(nowMs: Long): Boolean {
        if (isSleepingForAbsentCompanion || !hasLinkedCompanionDevice) return false
        val input = CompanionIdleSleepInput(
            hasLinkedCompanionDevice = hasLinkedCompanionDevice,
            collectorState = stateMachine.currentState,
            hasActiveExcursion = activeExcursionId != null,
            hasPendingBlipCleanup = pendingBlipCleanupAfterEpochSec != null,
            hasBoundUiClients = boundClientCount > 0 || listeners.isNotEmpty(),
            isDemoStreamRunning = isDemoStreamRunning,
            isOtaUpgradeInFlight = isOtaUpgradeInFlight,
            hasActiveGattSession = activeGatt != null || isGattProbeInFlight,
            isInUnprovisionedGattKeepAliveWindow = isInUnprovisionedGattKeepAliveWindow(nowMs),
            isManualExcursionOverride = stateMachine.isManualExcursionOverride,
            nowMs = nowMs,
            idleOutOfRangeSinceMs = idleOutOfRangeSinceMs,
            lastUiUnbindMs = lastUiUnbindMs,
            serviceStartedAtMs = serviceStartedAtMs,
            lastCompanionAppearedMs = lastCompanionAppearedMs,
            companionDisappearedAtMs = companionDisappearedAtMs,
            lastLiveBleHeardMs = lastRealBleHeardMs
        )
        if (!shouldSleepForAbsentCompanion(input)) return false

        val observingCount = CompanionDeviceHelper.ensureObservingAll(this)
        if (observingCount <= 0) {
            hasLinkedCompanionDevice = CompanionDeviceHelper.hasAssociations(this)
            hwLogger.logEvent(
                "COMPANION_IDLE_SLEEP_ABORTED",
                mapOf(
                    "reason" to "no_active_presence_observation",
                    "has_linked_companion" to hasLinkedCompanionDevice
                )
            )
            return false
        }

        isSleepingForAbsentCompanion = true
        isWatchdogScheduled = false
        hwLogger.logEvent(
            "COMPANION_IDLE_SLEEP",
            mapOf(
                "observing_count" to observingCount,
                "idle_ms" to (nowMs - (idleOutOfRangeSinceMs ?: nowMs)),
                "since_unbind_ms" to if (lastUiUnbindMs > 0L) (nowMs - lastUiUnbindMs) else -1L,
                "since_appeared_ms" to if (lastCompanionAppearedMs > 0L) (nowMs - lastCompanionAppearedMs) else -1L,
                "disappeared_at_ms" to (companionDisappearedAtMs ?: -1L),
                "last_real_ble_ms" to lastRealBleHeardMs
            )
        )
        stopBleScan()
        stopForeground(STOP_FOREGROUND_REMOVE)
        lastPostedStatusNotificationKey = null
        stopSelf()
        return true
    }

    fun getRecentActiveLinkableMonitor(
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = ACTIVE_MONITOR_LINK_MAX_AGE_MS
    ): ActiveLinkableMonitor? {
        if (isDemoStreamRunning || isDemoDataEnabled(this)) return null
        val addr = CompanionDeviceHelper.normalizeBluetoothMac(lastLiveTelemetryBleAddress) ?: return null
        if (lastLiveTelemetryBleMs <= 0L || (nowMs - lastLiveTelemetryBleMs) > maxAgeMs) return null
        return ActiveLinkableMonitor(
            bleAddress = addr,
            displayName = lastLiveTelemetryDeviceName
        )
    }

    fun addListener(listener: (LiveTelemetrySnapshot) -> Unit) {
        listeners.add(listener)
        listener(buildSnapshot())
    }

    fun removeListener(listener: (LiveTelemetrySnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun addUserStopListener(listener: () -> Unit) {
        userStopListeners.add(listener)
    }

    fun removeUserStopListener(listener: () -> Unit) {
        userStopListeners.remove(listener)
    }

    fun requestUserStop(reason: String, allowDuringActiveExcursion: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestUserStop(reason, allowDuringActiveExcursion) }
            return true
        }
        if (isUserStopping) return true
        val excId = activeExcursionId
        if (!allowDuringActiveExcursion && excId != null) {
            hwLogger.logEvent(
                "USER_STOP_IGNORED_ACTIVE_EXCURSION",
                mapOf("reason" to reason, "excursion_id" to excId)
            )
            return false
        }

        isUserStopping = true
        isWatchdogScheduled = false
        setUserStoppedService(this, true)
        hwLogger.logEvent(
            "USER_STOP_SERVICE",
            mapOf("reason" to reason, "had_active_excursion" to (excId != null))
        )

        if (excId != null) {
            val nowSec = System.currentTimeMillis() / 1000L
            metadataDb.updateOngoingExcursionEnd(
                excId,
                nowSec,
                closeExcursion = true,
                ringStore = ringStore
            )
            activeExcursionId = null
        }

        if (isDemoStreamRunning) {
            stopDemoStream()
        }
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(demoStreamRunnable)
        stopBleScan()
        disconnectGatt()
        stopGpsCollection()
        stopStepCollection()
        cancelManualTripStopSuggestionNotification()
        cancelResumeRequiredNotification(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        lastPostedStatusNotificationKey = null
        userStopListeners.toList().forEach { it() }
        stopSelf()
        return true
    }

    fun setExcursionBleMode(mode: BleCollectionMode) {
        stateMachine.userExcursionBleMode = mode
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onTimerTick(nowSec)
        applyStateTransition(transition, nowSec)
        notifyListeners()
    }

    fun setStrictFdcdScanFilter(strict: Boolean) {
        if (strictFdcdFilterEnabled != strict) {
            strictFdcdFilterEnabled = strict
            if (isScanning) {
                stopBleScan()
                startBleScan()
            }
            notifyListeners()
        }
    }

    fun setManualKnownPlaceOverride(knownPlaceId: Int) {
        manualKnownPlaceOverrideId = knownPlaceId
        refreshWifiAndPlaceState()
    }

    fun isManualKnownPlaceOverrideActive(): Boolean = manualKnownPlaceOverrideId > 0

    fun detachActiveExcursionIfMatching(excursionId: Long) {
        if (activeExcursionId == excursionId) {
            activeExcursionId = null
        }
    }

    fun getWifiLossDebounceSec(): Long =
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .getLong(PREF_WIFI_LOSS_DEBOUNCE_SEC, ExcursionStateMachine.DEFAULT_WIFI_LOSS_DEBOUNCE_SEC)
            .coerceIn(0L, MAX_WIFI_LOSS_DEBOUNCE_SEC)

    fun setWifiLossDebounceSec(debounceSec: Long) {
        val clamped = debounceSec.coerceIn(0L, MAX_WIFI_LOSS_DEBOUNCE_SEC)
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_WIFI_LOSS_DEBOUNCE_SEC, clamped)
            .apply()
        stateMachine.wifiLossDebounceSec = clamped
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onTimerTick(nowSec)
        applyStateTransition(transition, nowSec)
        updateOngoingForegroundNotification()
        notifyListeners()
    }

    fun startManualExcursion(): Long? {
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onManualTripStart(nowSec)
        applyStateTransition(transition, nowSec)
        activeExcursionId?.let { excId -> metadataDb.markExcursionManual(excId) }
        updateOngoingForegroundNotification()
        notifyListeners()
        return activeExcursionId
    }

    fun endManualExcursion() {
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onManualTripEnd(nowSec)
        applyStateTransition(transition, nowSec)
        updateOngoingForegroundNotification()
        notifyListeners()
    }

    private fun effectiveWifiSsid(): String? =
        if (!rawWifiSsid.isNullOrBlank()) (fakeWifiSsidOverride ?: rawWifiSsid) else null

    private fun resolveMatchedKnownPlace(knownPlaces: List<KnownPlace> = metadataDb.getKnownPlaces()): KnownPlace? {
        if (manualKnownPlaceOverrideId > 0) {
            return knownPlaces.firstOrNull { it.id == manualKnownPlaceOverrideId }
        }
        val effSsid = effectiveWifiSsid()
        return metadataDb.matchKnownPlaceByWifi(effSsid, currentWifiBssid)
            ?: if (effSsid != rawWifiSsid) metadataDb.matchKnownPlaceByWifi(rawWifiSsid, currentWifiBssid) else null
    }

    fun setFakeWifiSsidOverride(ssid: String?) {
        val normalized = ssid?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("clear", ignoreCase = true) && !it.equals("none", ignoreCase = true)
        }
        fakeWifiSsidOverride = normalized
        val editor = getSharedPreferences("aql_dev_keepalive", Context.MODE_PRIVATE).edit()
        if (normalized != null) {
            editor.putString("fake_wifi_ssid", normalized)
        } else {
            editor.remove("fake_wifi_ssid")
        }
        editor.apply()
        refreshWifiAndPlaceState()
    }

    fun refreshWifiAndPlaceState() {
        if (rawWifiSsid.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            unregisterWifiCallback()
            registerWifiCallback()
        }
        readFallbackWifiInfoIfNeeded()
        val knownPlaces = metadataDb.getKnownPlaces()
        val hasPlaces = knownPlaces.isNotEmpty()
        val matchedPlace = resolveMatchedKnownPlace(knownPlaces)
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onWifiContextChanged(
            timestampEpochSec = nowSec,
            connectedKnownPlaceId = matchedPlace?.id ?: 0,
            hasConfiguredKnownPlaces = hasPlaces
        )
        applyStateTransition(transition, nowSec)
        updateOngoingForegroundNotification()
        notifyListeners()
    }

    /**
     * Called ONLY when the user explicitly creates or links a Saved Wi-Fi Place in Settings,
     * backfilling recent stationary indoor slots (up to 30m, never overlapping any excursion).
     */
    fun backfillNewlySavedKnownPlace(placeId: Int) {
        if (placeId <= 0) return
        val nowSec = System.currentTimeMillis() / 1000L
        val latestExcursionEnd = metadataDb.getExcursions()
            .maxOfOrNull { it.endEpochSec ?: nowSec } ?: 0L
        val safeStartSec = maxOf(nowSec - 30L * 60L, latestExcursionEnd + 15L)
        if (nowSec >= safeStartSec) {
            ringStore.markStationaryRangeAsKnownPlace(safeStartSec, nowSec, placeId)
        }
        refreshWifiAndPlaceState()
    }

    fun bookmarkActiveExcursion() {
        val excId = activeExcursionId ?: metadataDb.getExcursions().firstOrNull()?.id ?: return
        metadataDb.setExcursionPinned(excId, true, ringStore)
        updateOngoingForegroundNotification()
        notifyListeners()
    }

    fun onUserDismissedAlert(metric: AlertEvaluator.MetricType) {
        alertEvaluator.onUserDismissedNotification(metric)
    }

    @SuppressLint("MissingPermission")
    fun triggerManualGattInspection() {
        val dev = resolveConnectableQingpingDevice() ?: lastSeenBleDevice
        if (dev == null) {
            gattStatusText = "No CGDN1 BLE device seen yet to connect GATT"
            notifyListeners()
            return
        }
        connectGattInternal(dev, disconnectAfterDiscovery = false)
    }

    /**
     * Resolves the connectable `:BC` BluetoothDevice for GATT operations.
     * Note: While the 6-byte device ID embedded inside the `0xFDCD` payload is the base Wi-Fi MAC (`:BA`),
     * the actual BLE radio address (`result.device.address`) for both `0xFDCD` and `0xFE95` advertisements
     * is already `:BC` (`MAC + 2`). Never add +2 to `result.device.address`, or `:BC` becomes `:BE`.
     */
    private fun resolveConnectableQingpingDevice(): BluetoothDevice? {
        lastConnectableQingpingDevice?.let { return it }
        lastFdcdBleDevice?.let {
            lastConnectableQingpingDevice = it
            return it
        }
        return lastSeenBleDevice
    }

    private fun getOrCreateAuthToken(): ByteArray {
        val prefs = getSharedPreferences("aql_qingping_bond", Context.MODE_PRIVATE)
        val savedHex = prefs.getString("auth_token_hex", null)
        if (!savedHex.isNullOrBlank() && savedHex.length == 32) {
            return ByteArray(16) { i ->
                savedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        // Deterministic 16-byte token (same format as clOwOck / qingping-cgd1)
        val defaultHex = "6e021111c28d192cfedbe04038a7f238"
        prefs.edit().putString("auth_token_hex", defaultHex).apply()
        return ByteArray(16) { i ->
            defaultHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun computeDesiredBleScanMode(nowMs: Long = System.currentTimeMillis()): Int {
        val hasActiveGattOrOta = isOtaUpgradeInFlight || isGattProbeInFlight || activeGatt != null
        val sampled = lastSampledFrame
        val needsActiveUnprovisionedGattDiscovery =
            devConfig.strategy != KeepAlivePayloadStrategy.NONE_PASSIVE &&
                sampled != null &&
                !sampled.isProvisionedNormalMode &&
                !sampled.isBtHomeV2 &&
                !hasCompletedInitialGattDiscovery
        val isRecentCompanionWake =
            lastCompanionAppearedMs > 0L && (nowMs - lastCompanionAppearedMs) < COMPANION_WAKE_BOOST_WINDOW_MS
        return chooseBleScanMode(
            strictFdcdFilterEnabled = strictFdcdFilterEnabled,
            collectorState = stateMachine.currentState,
            hasActiveGattOrOta = hasActiveGattOrOta,
            needsActiveUnprovisionedGattDiscovery = needsActiveUnprovisionedGattDiscovery,
            isRecentCompanionWake = isRecentCompanionWake,
            isExternalPowerConnected = isExternalPowerConnected
        )
    }

    private fun syncBleScanModeIfNeeded() {
        if (!isScanning || isSleepingForAbsentCompanion) return
        val nowMs = System.currentTimeMillis()
        val desiredMode = computeDesiredBleScanMode(nowMs)
        if (desiredMode == activeScanMode) return
        if ((nowMs - lastScanStartMs) < MIN_SCAN_MODE_SWITCH_INTERVAL_MS) return
        hwLogger.logEvent(
            "BLE_SCAN_MODE_CHANGED",
            mapOf(
                "from_mode" to activeScanMode,
                "to_mode" to desiredMode,
                "state" to stateMachine.currentState.name,
                "external_power" to isExternalPowerConnected
            )
        )
        stopBleScan()
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (isSleepingForAbsentCompanion) return
        if (!hasBluetoothScanPermission(this)) {
            hwLogger.logEvent("BLE_SCAN_PERMISSION_DEFERRED", emptyMap())
            return
        }
        val scanner = bleScanner ?: run {
            bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            bluetoothAdapter?.bluetoothLeScanner?.also { bleScanner = it }
        }
        if (scanner == null) {
            hwLogger.logEvent("BLE_SCAN_UNAVAILABLE", mapOf("adapter_enabled" to (bluetoothAdapter?.isEnabled == true)))
            return
        }

        if (isScanning) return
        try {
            // Empirically (Pixel 6a, Android 17): an *unfiltered* scan is silently demoted by the OS
            // ("BtScan.ScanManager: regularScanTimeout ... Moving unfiltered scan to opportunistic scan"),
            // after which the app receives no results unless some other app happens to be scanning.
            // Unfiltered scans also receive nothing while the screen is off. So in normal (strict) mode we
            // always pass OR'ed hardware filters for the CGDN1's advertisement shapes, and adapt the scan
            // duty cycle by collector state & power source (LOW_POWER when idle, BALANCED on battery
            // during active monitoring, LOW_LATENCY on external power / broad discovery / active GATT).
            val desiredMode = computeDesiredBleScanMode()
            val settings = ScanSettings.Builder()
                .setScanMode(desiredMode)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()
            val filters = if (strictFdcdFilterEnabled) buildQingpingScanFilters() else emptyList()
            scanner.startScan(filters, settings, bleScanCallback)
            isScanning = true
            activeScanMode = desiredMode
            lastScanStartMs = System.currentTimeMillis()
            lastAnyScanResultMs = lastScanStartMs
            hwLogger.logEvent(
                "BLE_SCAN_STARTED",
                mapOf(
                    "strict_software_filter" to strictFdcdFilterEnabled,
                    "hardware_filter_count" to filters.size,
                    "scan_mode" to desiredMode,
                    "external_power" to isExternalPowerConnected
                )
            )
        } catch (se: SecurityException) {
            isScanning = false
            activeScanMode = -1
            hwLogger.logEvent("BLE_SCAN_SECURITY_ERROR", mapOf("error" to se.message))
        } catch (e: Exception) {
            isScanning = false
            activeScanMode = -1
            hwLogger.logEvent("BLE_SCAN_ERROR", mapOf("error" to e.message))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan(force: Boolean = false) {
        if (!isScanning && !force) return
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                bleScanner?.stopScan(bleScanCallback)
            }
        } catch (_: Exception) {
        } finally {
            isScanning = false
            activeScanMode = -1
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            activeScanMode = -1
            hwLogger.logEvent("BLE_SCAN_FAILED", mapOf("error_code" to errorCode))
            // SCAN_FAILED_ALREADY_STARTED = 1: unconditionally clear any stale registration before retrying
            mainHandler.postDelayed({
                stopBleScan(force = true)
                if (!isSleepingForAbsentCompanion) {
                    startBleScan()
                }
            }, 1_500L)
        }

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastAnyScanResultMs = System.currentTimeMillis()
            val record = result.scanRecord ?: return
            val fdcdUuid = ParcelUuid.fromString(QingpingBleParser.QINGPING_SERVICE_UUID_STRING)
            val serviceData = record.getServiceData(fdcdUuid)
                ?: record.serviceData?.entries?.firstOrNull {
                    it.key.uuid.toString().lowercase(Locale.US).startsWith("0000fdcd")
                }?.value

            val btHomeUuid = ParcelUuid.fromString(QingpingBleParser.BTHOME_V2_SERVICE_UUID_STRING)
            val btHomeServiceData = if (serviceData == null) {
                record.getServiceData(btHomeUuid)
                    ?: record.serviceData?.entries?.firstOrNull {
                        it.key.uuid.toString().lowercase(Locale.US).startsWith("0000fcd2")
                    }?.value
            } else {
                null
            }

            val devName = record.deviceName ?: result.device?.name ?: ""
            val mac = result.device?.address ?: "unknown"
            if (serviceData == null && btHomeServiceData == null) {
                val nowMs = System.currentTimeMillis()
                val lastLoggedMs = lastNonFdcdLogMsByMac[mac] ?: 0L
                val fe95Entry = record.serviceData?.entries?.firstOrNull {
                    it.key.uuid.toString().lowercase(Locale.US).startsWith("0000fe95")
                }
                val hasFe95ServiceData = fe95Entry != null
                val fe95Header = QingpingBleParser.parseFe95Header(fe95Entry?.value)
                val isQingpingNamed = hasFe95ServiceData ||
                    devName.contains("Qingping", ignoreCase = true) ||
                    devName.contains("ClearGrass", ignoreCase = true) ||
                    devName.contains("Air Monitor", ignoreCase = true) ||
                    devName.contains("CGDN1", ignoreCase = true)

                if (fe95Header != null) {
                    val prevCnt = lastFe95FrameCntByMac[mac]
                    lastFe95FrameCntByMac[mac] = fe95Header.frameCounter
                    // Exclude normal 8-bit uint8 wrap-around (246..255 -> 0..3)
                    if (prevCnt != null && prevCnt in 5..245 && fe95Header.frameCounter <= 3) {
                        hwLogger.logEvent(
                            "BLE_FE95_FRAME_CNT_RESET",
                            mapOf(
                                "cfg_epoch" to devConfig.configEpoch,
                                "mac" to mac,
                                "prev_cnt" to prevCnt,
                                "new_cnt" to fe95Header.frameCounter,
                                "fe95_ctrl" to fe95Header.frameCtrlHex
                            )
                        )
                    }
                }

                if (isQingpingNamed) {
                    lastConnectableQingpingDevice = result.device
                    lastSeenBleDevice = result.device
                    lastMonitorHeardMs = nowMs
                    lastRealBleHeardMs = nowMs
                    val intervalMs = devConfig.intervalSec * 1000L
                    val retryMs = devConfig.retryCooldownSec * 1000L
                    val periodicDue = hasCompletedInitialGattDiscovery &&
                        devConfig.triggerOnFe95Adv &&
                        (nowMs - lastPeriodicGattPokeMs) >= intervalMs
                    if (!isOtaUpgradeInFlight &&
                        devConfig.strategy != KeepAlivePayloadStrategy.NONE_PASSIVE &&
                        lastSampledFrame?.isProvisionedNormalMode != true &&
                        (!hasCompletedInitialGattDiscovery || periodicDue) &&
                        activeGatt == null &&
                        (nowMs - lastAutoGattAttemptMs) >= retryMs
                    ) {
                        lastAutoGattAttemptMs = nowMs
                        mainHandler.post {
                            connectGattInternal(result.device, disconnectAfterDiscovery = true)
                        }
                    }
                }

                // Always log named Qingping/ClearGrass devices once per 15s, or log every unique MAC once per 15s during bring-up
                if (nowMs - lastLoggedMs >= 15_000L) {
                    lastNonFdcdLogMsByMac[mac] = nowMs
                    val rawBytes = record.bytes ?: ByteArray(0)
                    val rawHex = QingpingBleParser.toHexString(rawBytes)
                    val serviceUuids = record.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: ""
                    val serviceDataKeys = record.serviceData?.keys?.joinToString(",") { it.uuid.toString() } ?: ""
                    if (isDeveloperModeEnabled(this@AqlCollectorService)) {
                        val eventMap = LinkedHashMap<String, Any?>()
                        eventMap["mac"] = mac
                        eventMap["device_name"] = devName
                        eventMap["rssi_dbm"] = result.rssi
                        if (fe95Header != null) {
                            eventMap["fe95_cnt"] = fe95Header.frameCounter
                            eventMap["fe95_ctrl"] = fe95Header.frameCtrlHex
                        }
                        eventMap["service_uuids"] = serviceUuids
                        eventMap["service_data_uuids"] = serviceDataKeys
                        eventMap["raw_hex"] = rawHex
                        hwLogger.logEvent("BLE_ADV_NON_FDCD", eventMap)
                    }
                    if (!strictFdcdFilterEnabled || isQingpingNamed) {
                        mainHandler.post {
                            val cntTag = fe95Header?.let { " cnt=${it.frameCounter}" } ?: ""
                            appendRawFrameLog("NON-FDCD [$devName / $mac$cntTag] RSSI=${result.rssi} sd=[$serviceDataKeys]: $rawHex")
                            notifyListeners()
                        }
                    }
                }
                return
            }

            lastFdcdBleDevice = result.device
            lastSeenBleDevice = result.device
            val nowMs = System.currentTimeMillis()
            val decoded = if (serviceData != null) {
                QingpingBleParser.parseServiceData(serviceData, timestampMs = nowMs, rssiDbm = result.rssi)
            } else {
                QingpingBleParser.parseBtHomeV2ServiceData(
                    btHomeServiceData!!,
                    deviceAddress = mac,
                    deviceName = devName,
                    timestampMs = nowMs,
                    rssiDbm = result.rssi
                )
            }
            if (decoded == null) {
                if (isDeveloperModeEnabled(this@AqlCollectorService)) {
                    val rawUnparsed = serviceData ?: btHomeServiceData ?: ByteArray(0)
                    hwLogger.logEvent(
                        "BLE_ADV_FDCD_UNPARSED",
                        mapOf(
                            "mac" to result.device.address,
                            "rssi_dbm" to result.rssi,
                            "raw_hex" to QingpingBleParser.toHexString(rawUnparsed)
                        )
                    )
                }
                return
            }

            if (!decoded.isShutdownZeroFrame) {
                lastMonitorHeardMs = nowMs
                lastRealBleHeardMs = nowMs
                val gattTarget = resolveConnectableQingpingDevice()
                val retryMs = devConfig.retryCooldownSec * 1000L
                if (!isOtaUpgradeInFlight &&
                    !decoded.isBtHomeV2 &&
                    !decoded.isProvisionedNormalMode &&
                    devConfig.strategy != KeepAlivePayloadStrategy.NONE_PASSIVE &&
                    !hasCompletedInitialGattDiscovery &&
                    activeGatt == null &&
                    gattTarget != null &&
                    (nowMs - lastAutoGattAttemptMs) >= retryMs
                ) {
                    lastAutoGattAttemptMs = nowMs
                    mainHandler.post {
                        connectGattInternal(gattTarget, disconnectAfterDiscovery = true)
                    }
                }
            }

            val otaBleAddress = result.device?.address
            val advertisedName = devName.takeIf { it.isNotBlank() }
            mainHandler.post {
                handleDecodedFrame(
                    decoded = decoded,
                    overTheAirBleAddress = otaBleAddress,
                    advertisedDeviceName = advertisedName,
                    isSyntheticDemo = false
                )
            }
        }
    }

    private fun handleDecodedFrame(
        decoded: QingpingBleParser.DecodedQingpingFrame,
        overTheAirBleAddress: String? = null,
        advertisedDeviceName: String? = null,
        isSyntheticDemo: Boolean = false
    ) {
        val frameCtrlHex = String.format(Locale.US, "0x%02X", decoded.frameControl)
        if (decoded.isShutdownZeroFrame) {
            prevDecodedFrameCtrl = decoded.frameControl
            hasCompletedInitialGattDiscovery = false
            lastMonitorHeardMs = 0L
            if (!isSyntheticDemo) {
                lastRealBleHeardMs = 0L
            }
            appendRawFrameLog(
                "[${decoded.macAddress}] (SHUTDOWN-ZERO, frameCtrl=$frameCtrlHex) | ${decoded.rawHex}"
            )
            hwLogger.logEvent(
                "BLE_ADV_FDCD_SHUTDOWN_ZERO",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "mac" to decoded.macAddress,
                    "frame_ctrl_hex" to frameCtrlHex,
                    "rssi_dbm" to decoded.rssiDbm,
                    "raw_hex" to decoded.rawHex
                )
            )
            notifyListeners()
            return
        }

        val assembled = frameAssembler.onFrame(decoded)
        if (!decoded.isWarmupZeroCo2) {
            lastSampledFrame = decoded
        }
        lastMonitorHeardMs = decoded.timestampMs
        lastInterPacketDeltaMs = assembled.interPacketDeltaMs
        val deltaMs = assembled.interPacketDeltaMs ?: -1L
        if (lastWindowReset1101Ms == 0L || deltaMs >= 60_000L) {
            lastWindowReset1101Ms = decoded.timestampMs
        }

        if (deltaMs >= 10_000L) {
            hwLogger.logEvent(
                "BLE_TELEMETRY_GAP",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "mac" to decoded.macAddress,
                    "gap_ms" to deltaMs,
                    "prev_ctrl" to if (prevDecodedFrameCtrl >= 0) String.format(Locale.US, "0x%02X", prevDecodedFrameCtrl) else "none",
                    "new_ctrl" to frameCtrlHex
                )
            )
        }
        prevDecodedFrameCtrl = decoded.frameControl

        if (decoded.isWarmupZeroCo2) {
            hwLogger.logEvent(
                "BLE_WARMUP_ZERO_CO2",
                mapOf(
                    "cfg_epoch" to devConfig.configEpoch,
                    "mac" to decoded.macAddress,
                    "frame_ctrl_hex" to frameCtrlHex,
                    "temp_c" to (decoded.temperatureC ?: 0f),
                    "humidity_pct" to (decoded.humidityPct ?: 0f),
                    "raw_hex" to decoded.rawHex
                )
            )
        }

        latestCo2Ppm = assembled.mergedCo2Ppm
        latestPm25Ugm3 = assembled.mergedPm25Ugm3
        latestPm10Ugm3 = assembled.mergedPm10Ugm3
        latestTempC = assembled.mergedTemperatureC
        latestHumidityPct = assembled.mergedHumidityPct
        latestBatteryPct = assembled.mergedBatteryPct

        val deltaStr = assembled.interPacketDeltaMs?.let { String.format(Locale.US, "+%.1fs", it / 1000.0) } ?: "first"
        appendRawFrameLog(
            "[${decoded.macAddress}] ($deltaStr, ${decoded.rssiDbm}dBm) CO2=${latestCo2Ppm} PM2.5=${latestPm25Ugm3} T=${latestTempC}C RH=${latestHumidityPct}% | ${decoded.rawHex}"
        )

        if (isDeveloperModeEnabled(this)) {
            val shouldSkipDuplicateLog = devConfig.deduplicateFdcdTrace &&
                decoded.rawHex == lastLoggedFdcdRawHex &&
                deltaMs in 0L..2_999L &&
                (decoded.timestampMs - lastLoggedFdcdTsMs) < 15_000L &&
                !decoded.isWarmupZeroCo2
            if (shouldSkipDuplicateLog) {
                skippedFdcdRepeatCount++
            } else {
                val fdcdMap = LinkedHashMap<String, Any?>()
                fdcdMap["mac"] = decoded.macAddress
                fdcdMap["frame_ctrl_hex"] = frameCtrlHex
                fdcdMap["rssi_dbm"] = decoded.rssiDbm
                fdcdMap["delta_ms"] = deltaMs
                if (skippedFdcdRepeatCount > 0) {
                    fdcdMap["rep"] = skippedFdcdRepeatCount
                }
                fdcdMap["co2_ppm"] = latestCo2Ppm
                fdcdMap["pm25_ugm3"] = latestPm25Ugm3
                fdcdMap["pm10_ugm3"] = latestPm10Ugm3
                fdcdMap["temp_c"] = latestTempC
                fdcdMap["humidity_pct"] = latestHumidityPct
                if (decoded.unknownTlvs.isNotEmpty()) {
                    fdcdMap["unknown_tlvs"] = decoded.unknownTlvs.joinToString(",") {
                        String.format(Locale.US, "0x%02X:%s", it.first, it.second)
                    }
                }
                fdcdMap["raw_hex"] = decoded.rawHex
                hwLogger.logEvent("BLE_ADV_FDCD", fdcdMap)
                lastLoggedFdcdRawHex = decoded.rawHex
                lastLoggedFdcdTsMs = decoded.timestampMs
                skippedFdcdRepeatCount = 0
            }
        }

        metadataDb.upsertMonitorDevice(decoded.macAddress, decoded.productName, "Qingping ${decoded.productName}")

        readFallbackWifiInfoIfNeeded()
        val knownPlaces = metadataDb.getKnownPlaces()
        val hasPlaces = knownPlaces.isNotEmpty()
        val matchedPlace = resolveMatchedKnownPlace(knownPlaces)
        val knownPlaceId = matchedPlace?.id ?: 0

        val nowSec = decoded.timestampMs / 1000L
        val transition = stateMachine.onBleAdvertisement(
            timestampEpochSec = nowSec,
            connectedKnownPlaceId = knownPlaceId,
            hasConfiguredKnownPlaces = hasPlaces
        )
        applyStateTransition(transition, nowSec)

        // Write sample to constant-size .aqlring binary buffer once NDIR CO2 warmup is complete
        if (decoded.hasEnvironmentalMetrics && latestCo2Ppm > 0) {
            refreshEffectiveSpeed()
            val recordSpeed = transition.activeKnownPlaceId == 0 &&
                (transition.shouldRunGps || latestSpeedSource == StepMovementEstimator.SpeedSource.STEP_CADENCE)
            val recordedGpsAccuracy = if (
                recordSpeed &&
                transition.shouldRunGps &&
                latestSpeedSource != StepMovementEstimator.SpeedSource.STEP_CADENCE
            ) {
                latestGpsAccuracyM
            } else {
                null
            }
            ringStore.writeSample(
                AqlRingBufferStore.RawSampleInput(
                    timestampEpochSec = nowSec,
                    knownPlaceId = transition.activeKnownPlaceId,
                    co2Ppm = latestCo2Ppm,
                    pm25Ugm3 = latestPm25Ugm3,
                    pm10Ugm3 = latestPm10Ugm3,
                    tempC = latestTempC,
                    humidityPct = latestHumidityPct,
                    batteryPct = latestBatteryPct,
                    rssiDbm = decoded.rssiDbm,
                    speedMps = if (recordSpeed) latestSpeedMps else null,
                    gpsAccuracyM = recordedGpsAccuracy
                )
            )
            activeExcursionId?.let { excId ->
                metadataDb.updateOngoingExcursionEnd(excId, nowSec, closeExcursion = false)
            }
            val alertActions = alertEvaluator.evaluateSample(nowSec, latestCo2Ppm, latestPm25Ugm3, latestBatteryPct)
            dispatchAlertNotifications(alertActions)

            if (!isSyntheticDemo) {
                val normalizedBleMac = CompanionDeviceHelper.normalizeBluetoothMac(overTheAirBleAddress)
                if (normalizedBleMac != null) {
                    val resolvedName = advertisedDeviceName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: decoded.productName
                    lastLiveTelemetryBleAddress = normalizedBleMac
                    lastLiveTelemetryDeviceName = resolvedName
                    lastLiveTelemetryBleMs = decoded.timestampMs
                    maybePostCompanionLinkSuggestionNotification(normalizedBleMac, resolvedName)
                }
            }
        }

        updateOngoingForegroundNotification()
        notifyListeners()
    }

    private fun isCurrentlyAtSavedWifiPlace(): Boolean {
        readFallbackWifiInfoIfNeeded()
        return resolveMatchedKnownPlace() != null
    }

    private fun applyStateTransition(
        transition: ExcursionStateMachine.TransitionOutput,
        nowEpochSec: Long
    ) {
        if (transition.startedNewExcursion && activeExcursionId == null) {
            val startSec = transition.startedExcursionStartEpochSec ?: nowEpochSec
            val fmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())
            val title = getString(R.string.trip_default_title, fmt.format(Instant.ofEpochSecond(startSec)))
            val exc = metadataDb.startExcursion(
                deviceId = 1L,
                startEpochSec = startSec,
                title = title,
                isManual = transition.startedByManualOverride
            )
            if (nowEpochSec > startSec) {
                metadataDb.updateOngoingExcursionEnd(exc.id, nowEpochSec, closeExcursion = false)
            }
            activeExcursionId = exc.id
            pendingBlipCleanupAfterEpochSec = null
            // If the monitor was already awake at Home under 0509_ONLY for >= 10 minutes before stepping out
            // in Always 24m Reset mode, arm a 11 01 window reset so the trip starts with a fresh 30m timer.
            val nowMs = System.currentTimeMillis()
            if (devConfig.strategy == KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 &&
                lastSampledFrame?.isProvisionedNormalMode != true &&
                lastWindowReset1101Ms > 0L &&
                (nowMs - lastWindowReset1101Ms) >= 10L * 60_000L
            ) {
                lastWindowReset1101Ms = nowMs - 24L * 60_000L
                lastPeriodicGattPokeMs = 0L
            }
        } else if (transition.closedExcursion && activeExcursionId != null) {
            val closeEndSec = transition.closedExcursionEndEpochSec ?: nowEpochSec
            metadataDb.updateOngoingExcursionEnd(
                activeExcursionId!!,
                closeEndSec,
                closeExcursion = true,
                ringStore = ringStore
            )
            metadataDb.coalesceMicroExcursionsAndRestoreRingPlaceIds(ringStore, nowEpochSec)
            pendingBlipCleanupAfterEpochSec =
                nowEpochSec + MetadataDatabase.MICRO_EXCURSION_COALESCE_GAP_SEC + 1L
            activeExcursionId = null
        }

        if (transition.clearManualTripEndSuggestion) {
            cancelManualTripStopSuggestionNotification()
        } else if (transition.suggestManualTripEndPlaceId != null) {
            postManualTripStopSuggestionNotification(transition.suggestManualTripEndPlaceId)
        }

        if (transition.shouldRunGps) {
            startGpsCollection()
        } else {
            stopGpsCollection()
        }

        if (transition.currentState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE) {
            startStepCollection()
        } else {
            stopStepCollection()
        }

        if (transition.currentState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE) {
            if (idleOutOfRangeSinceMs == null) {
                idleOutOfRangeSinceMs = System.currentTimeMillis()
            }
            dispatchAlertNotifications(alertEvaluator.onOutOfRange())
        } else {
            idleOutOfRangeSinceMs = null
        }

        // After a real signal gap (>90s since last GATT poke), re-arm initial GATT discovery so that
        // when the CGDN1 is woken again (touchbar → Wi-Fi icon) we connect within its ~60s window.
        if ((transition.currentState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ||
                transition.currentState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED) &&
            activeGatt == null &&
            (System.currentTimeMillis() - lastPeriodicGattPokeMs) > 90_000L
        ) {
            hasCompletedInitialGattDiscovery = false
        }

        manageGattMode(transition.effectiveBleMode)
        syncBleScanModeIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun manageGattMode(effectiveMode: BleCollectionMode) {
        if (isOtaUpgradeInFlight) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val dev = resolveConnectableQingpingDevice()
        val lastHeardMs = maxOf(lastSampledFrame?.timestampMs ?: 0L, lastMonitorHeardMs)
        // Keep trying for 11 minutes after the last advertisement so that if the 30-minute (1,797s)
        // 0x0A broadcast ceiling pauses advertisements while the 10-minute (609.8s) post-GATT timer
        // is still ticking on the ESP32, the 5s watchdog still initiates scheduled GATT pokes.
        val monitorRecentlyHeard = lastHeardMs > 0L && (nowMs - lastHeardMs) <= 11 * 60 * 1000L
        val intervalMs = devConfig.intervalSec * 1000L
        val retryMs = devConfig.retryCooldownSec * 1000L

        val isPassiveBroadcastActive = lastSampledFrame?.isBtHomeV2 == true ||
            lastSampledFrame?.isProvisionedNormalMode == true
        if (devConfig.strategy == KeepAlivePayloadStrategy.NONE_PASSIVE || isPassiveBroadcastActive) {
            if (activeGatt != null && !isGattProbeInFlight) {
                disconnectGatt()
                gattStatusText = when {
                    lastSampledFrame?.isBtHomeV2 == true -> "Passive (BTHome v2 Broadcast Active)"
                    lastSampledFrame?.isProvisionedNormalMode == true -> "Passive (Provisioned 0x88 Broadcast Active)"
                    else -> "Disconnected (Dev Strategy: NONE_PASSIVE)"
                }
            } else if (activeGatt == null && lastSampledFrame?.isProvisionedNormalMode == true &&
                devConfig.strategy != KeepAlivePayloadStrategy.NONE_PASSIVE
            ) {
                gattStatusText = "Passive (Provisioned 0x88 Broadcast Active)"
            }
            return
        }

        // Periodic connect-write-disconnect keep-alive (runs both in STATIONARY_KNOWN_AP and
        // IDLE_OUT_OF_RANGE as long as monitorRecentlyHeard is within the 11-minute post-GATT window).
        if (dev != null &&
            monitorRecentlyHeard &&
            (nowMs - lastPeriodicGattPokeMs) >= intervalMs &&
            (nowMs - lastAutoGattAttemptMs) >= retryMs &&
            activeGatt == null
        ) {
            lastAutoGattAttemptMs = nowMs
            connectGattInternal(dev, disconnectAfterDiscovery = true)
        }
    }

    private fun cancelOtaWatchdog() {
        otaInactivityTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        otaInactivityTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun connectGattInternal(
        device: BluetoothDevice,
        disconnectAfterDiscovery: Boolean,
        connectAttempt: Int = 1,
        maxConnectAttempts: Int = 5,
        explicitOtaUrl: String? = null,
        explicitWifiCreds: Pair<String, String>? = null
    ) {
        val sessionOtaUrl = explicitOtaUrl ?: pendingOtaUrl.also { pendingOtaUrl = null }
        val isOtaSession = sessionOtaUrl != null
        val sessionWifiCreds = explicitWifiCreds ?: if (isOtaSession) {
            null
        } else {
            pendingWifiCredentials.also { pendingWifiCredentials = null }
        }
        val isWifiSession = sessionWifiCreds != null
        disconnectGatt(clearOtaFlag = !isOtaSession)
        stopBleScan()
        if (isOtaSession) {
            isOtaUpgradeInFlight = true
        }
        val shouldAutoDisconnect = disconnectAfterDiscovery && !isOtaSession
        val sessionId = ++gattSessionSeq
        val sessionStartMs = System.currentTimeMillis()
        val sessionCfg = devConfig
        var connectedOk = false
        var disconnectHandled = false
        var servicesCount = 0
        var summaryLogged = false
        var otaFwChar: BluetoothGattCharacteristic? = null
        val writesSent = mutableListOf<String>()
        val notifiesRcvd = mutableListOf<String>()
        val readsRcvd = mutableListOf<String>()

        fun emitSessionSummary(disconnectStatus: Int) {
            if (summaryLogged) return
            summaryLogged = true
            hwLogger.logEvent(
                "GATT_SESSION_SUMMARY",
                mapOf(
                    "session_id" to sessionId,
                    "cfg_epoch" to sessionCfg.configEpoch,
                    "strategy" to sessionCfg.strategy.id,
                    "is_ota" to isOtaSession,
                    "mac" to device.address,
                    "duration_ms" to (System.currentTimeMillis() - sessionStartMs),
                    "connected_ok" to connectedOk,
                    "services_cnt" to servicesCount,
                    "disconnect_status" to disconnectStatus,
                    "writes" to writesSent.joinToString(","),
                    "notifies" to notifiesRcvd.joinToString(","),
                    "reads_cnt" to readsRcvd.size
                )
            )
        }

        fun armOtaWatchdog(gatt: BluetoothGatt, timeoutMs: Long = 60_000L) {
            cancelOtaWatchdog()
            val runnable = Runnable {
                if (activeGatt !== gatt || !isOtaUpgradeInFlight) return@Runnable
                hwLogger.logEvent(
                    "DEV_OTA_STATUS",
                    mapOf(
                        "session_id" to sessionId,
                        "cfg_epoch" to sessionCfg.configEpoch,
                        "state" to "timeout",
                        "timeout_ms" to timeoutMs
                    )
                )
                isOtaUpgradeInFlight = false
                gattStatusText = "OTA #$sessionId timed out after ${timeoutMs / 1000L}s without 000E reply"
                appendRawFrameLog(gattStatusText)
                emitSessionSummary(0)
                disconnectGatt(clearOtaFlag = true)
                notifyListeners()
            }
            otaInactivityTimeoutRunnable = runnable
            mainHandler.postDelayed(runnable, timeoutMs)
        }

        isGattProbeInFlight = true
        lastAutoGattAttemptMs = sessionStartMs
        gattStatusText = if (isOtaSession) {
            "Connecting OTA #$sessionId to ${device.address}..."
        } else {
            "Connecting GATT #$sessionId (${sessionCfg.strategy.id}) to ${device.address}..."
        }
        notifyListeners()

        activeGatt = device.connectGatt(
            this,
            false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    hwLogger.logEvent(
                        "GATT_CONNECTION_STATE",
                        mapOf(
                            "session_id" to sessionId,
                            "cfg_epoch" to sessionCfg.configEpoch,
                            "mac" to device.address,
                            "status" to status,
                            "new_state" to newState
                        )
                    )
                    mainHandler.post {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            connectedOk = true
                            gattStatusText = if (isOtaSession) {
                                "Connected OTA #$sessionId (${device.address}) — Discovering services..."
                            } else {
                                "Connected GATT #$sessionId (${device.address}) — Discovering services..."
                            }
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            if (disconnectHandled) return@post
                            disconnectHandled = true
                            cancelOtaWatchdog()
                            val isExplicitSession = isOtaSession || isWifiSession || pendingOneShotGattScript != null
                            if (!connectedOk && isExplicitSession && connectAttempt < maxConnectAttempts) {
                                emitSessionSummary(status)
                                isGattProbeInFlight = false
                                if (activeGatt === gatt) {
                                    activeGatt?.close()
                                    activeGatt = null
                                } else {
                                    gatt.close()
                                }
                                gattStatusText = "BLE connect status=$status (attempt $connectAttempt/$maxConnectAttempts); retrying in 1.5s..."
                                appendRawFrameLog(gattStatusText)
                                notifyListeners()
                                mainHandler.postDelayed({
                                    connectGattInternal(
                                        device = device,
                                        disconnectAfterDiscovery = disconnectAfterDiscovery,
                                        connectAttempt = connectAttempt + 1,
                                        maxConnectAttempts = maxConnectAttempts,
                                        explicitOtaUrl = sessionOtaUrl,
                                        explicitWifiCreds = sessionWifiCreds
                                    )
                                }, 1_500L)
                                return@post
                            }
                            if (isOtaSession) {
                                if (isOtaUpgradeInFlight) {
                                    hwLogger.logEvent(
                                        "DEV_OTA_STATUS",
                                        mapOf(
                                            "session_id" to sessionId,
                                            "cfg_epoch" to sessionCfg.configEpoch,
                                            "state" to "disconnected",
                                            "status" to status
                                        )
                                    )
                                }
                                isOtaUpgradeInFlight = false
                            }
                            emitSessionSummary(status)
                            gattStatusText = "GATT #$sessionId Disconnected (status=$status, strategy=${sessionCfg.strategy.id})"
                            isGattProbeInFlight = false
                            if (activeGatt === gatt) {
                                activeGatt?.close()
                                activeGatt = null
                            } else {
                                gatt.close()
                            }
                            if (!isSleepingForAbsentCompanion) {
                                startBleScan()
                            }
                        }
                        notifyListeners()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val summary = mutableListOf<String>()
                    val gattOpsQueue = ArrayDeque<() -> Unit>()
                    servicesCount = gatt.services.size

                    for (svc in gatt.services) {
                        val isQingpingCustom = svc.uuid.toString().equals(QingpingBleParser.QINGPING_GATT_SERVICE_UUID, ignoreCase = true)
                        val tag = if (isQingpingCustom) " [QINGPING CUSTOM]" else ""
                        summary.add("Service: ${svc.uuid}$tag")
                        for (ch in svc.characteristics) {
                            val descStr = ch.descriptors.joinToString(",") { it.uuid.toString() }
                            summary.add("  └─ Char: ${ch.uuid} (props=0x${Integer.toHexString(ch.properties)}, descs=[$descStr])")
                        }
                    }

                    fun findFwRevisionChar(): BluetoothGattCharacteristic? =
                        gatt.services
                            .asSequence()
                            .flatMap { it.characteristics.asSequence() }
                            .firstOrNull {
                                it.uuid.toString().startsWith("00002a26", ignoreCase = true) &&
                                    (it.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                            }

                    fun queueCharRead(ch: BluetoothGattCharacteristic?) {
                        if (ch == null) return
                        gattOpsQueue.add {
                            try {
                                gatt.readCharacteristic(ch)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    if (!hasCompletedInitialGattDiscovery &&
                        !isOtaSession &&
                        !isWifiSession
                    ) {
                        for (svc in gatt.services) {
                            val isQingpingCustom = svc.uuid.toString().equals(QingpingBleParser.QINGPING_GATT_SERVICE_UUID, ignoreCase = true)
                            if (!isQingpingCustom) {
                                for (ch in svc.characteristics) {
                                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                                        queueCharRead(ch)
                                    }
                                }
                            }
                        }
                    }

                    val qingpingSvc = gatt.services.firstOrNull {
                        it.uuid.toString().equals(QingpingBleParser.QINGPING_GATT_SERVICE_UUID, ignoreCase = true)
                    }
                    val oneShotScript = pendingOneShotGattScript.also {
                        if (!isOtaSession && !isWifiSession) {
                            pendingOneShotGattScript = null
                        }
                    }

                    fun findCustomChar(vararg prefixes: String): BluetoothGattCharacteristic? {
                        val svc = qingpingSvc ?: return null
                        return svc.characteristics.firstOrNull { ch ->
                            val u = ch.uuid.toString().lowercase(Locale.US)
                            prefixes.any { p -> u.startsWith(p) }
                        }
                    }

                    fun resolveWriteType(ch: BluetoothGattCharacteristic): Int {
                        return if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }
                    }

                    fun queueCccdEnable(ch: BluetoothGattCharacteristic?) {
                        if (ch == null) return
                        gattOpsQueue.add {
                            try {
                                gatt.setCharacteristicNotification(ch, true)
                                val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                if (cccd != null) {
                                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }

                    fun queueCharWrite(
                        ch: BluetoothGattCharacteristic?,
                        payload: ByteArray,
                        stepLabel: String,
                        summaryLabel: String = QingpingBleParser.toHexString(payload),
                        redactPayload: Boolean = false,
                        onAfterWrite: (() -> Unit)? = null
                    ) {
                        if (ch == null) return
                        val writeType = resolveWriteType(ch)
                        gattOpsQueue.add {
                            try {
                                val loggedHex = if (redactPayload) "<redacted>" else QingpingBleParser.toHexString(payload)
                                val rc = gatt.writeCharacteristic(ch, payload, writeType)
                                writesSent.add(summaryLabel)
                                hwLogger.logEvent(
                                    "GATT_CHAR_WRITE",
                                    mapOf(
                                        "session_id" to sessionId,
                                        "mac" to device.address,
                                        "char_uuid" to ch.uuid.toString(),
                                        "step" to stepLabel,
                                        "payload_hex" to loggedHex,
                                        "rc" to rc
                                    )
                                )
                                onAfterWrite?.invoke()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    fun queuePairBChunks(
                        writeCharB: BluetoothGattCharacteristic?,
                        chunks: List<ByteArray>,
                        stepPrefix: String,
                        summaryPrefix: String,
                        redactPayload: Boolean = false,
                        onLastChunkWritten: (() -> Unit)? = null
                    ) {
                        chunks.forEachIndexed { idx, chunk ->
                            val isLast = (idx == chunks.lastIndex)
                            val chunkSummary = if (redactPayload) {
                                "$summaryPrefix[$idx]:<redacted>"
                            } else {
                                "$summaryPrefix[$idx]:${QingpingBleParser.toHexString(chunk)}"
                            }
                            queueCharWrite(
                                writeCharB,
                                chunk,
                                "${stepPrefix}_c$idx",
                                chunkSummary,
                                redactPayload = redactPayload,
                                onAfterWrite = if (isLast) onLastChunkWritten else null
                            )
                        }
                    }

                    fun buildEpochTimeSyncCmd(): ByteArray {
                        val epochSec = (System.currentTimeMillis() / 1000L).toInt()
                        return byteArrayOf(
                            0x05.toByte(),
                            0x09.toByte(),
                            (epochSec and 0xFF).toByte(),
                            ((epochSec ushr 8) and 0xFF).toByte(),
                            ((epochSec ushr 16) and 0xFF).toByte(),
                            ((epochSec ushr 24) and 0xFF).toByte()
                        )
                    }

                    fun buildAuthCmd(subCmd: Int, token: ByteArray): ByteArray = ByteArray(18).apply {
                        this[0] = 0x11.toByte()
                        this[1] = (subCmd and 0xFF).toByte()
                        System.arraycopy(token, 0, this, 2, 16)
                    }

                    var setupError = false
                    try {
                        val writeCharA = findCustomChar("00000001", "22210001")
                        val notifyCharA = findCustomChar("00000002", "22210002")
                        val writeCharB = findCustomChar("0000000d", "0000000f")
                        val notifyCharB = findCustomChar("0000000e", "00000010")
                        val writeCharC = findCustomChar("00000017")
                        val notifyCharC = findCustomChar("00000018")
                        otaFwChar = findFwRevisionChar()

                        if (isOtaSession) {
                            // Pair A 11 02 (without 11 01) unlocks Pair B on a provisioned monitor without knocking 0x88 -> 0x0A.
                            check(qingpingSvc != null && writeCharB != null) {
                                "Missing Pair B write characteristic (000D) for OTA"
                            }
                            val authToken = getOrCreateAuthToken()
                            queueCharRead(otaFwChar)
                            queueCccdEnable(notifyCharA)
                            queueCccdEnable(notifyCharB)
                            if (writeCharA != null) {
                                queueCharWrite(writeCharA, buildAuthCmd(0x02, authToken), "ota_a_1102", "a:1102")
                                repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            }
                            queueCharWrite(writeCharB, byteArrayOf(0x01, 0x08), "ota_b_0108", "b:0108")
                            repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            val otaChunks = QingpingBleParser.encodePairBOtaUpgradeChunks(sessionOtaUrl!!)
                            queuePairBChunks(writeCharB, otaChunks, "ota_b_cmd3", "b_ota") {
                                gattStatusText = "OTA #$sessionId URL sent (${otaChunks.size} chunks); awaiting 000E replies (60s timeout for flash erase)..."
                                appendRawFrameLog(gattStatusText)
                                armOtaWatchdog(gatt, 60_000L)
                                notifyListeners()
                            }
                        } else if (sessionWifiCreds != null) {
                            check(qingpingSvc != null && writeCharA != null && writeCharB != null) {
                                "Missing Pair A/Pair B characteristics for Wi-Fi configuration"
                            }
                            val authToken = getOrCreateAuthToken()
                            queueCccdEnable(notifyCharA)
                            queueCccdEnable(notifyCharB)
                            queueCharWrite(writeCharA, buildAuthCmd(0x01, authToken), "wifi_a_1101", "a:1101") {
                                lastWindowReset1101Ms = System.currentTimeMillis()
                            }
                            repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            queueCharWrite(writeCharA, buildAuthCmd(0x02, authToken), "wifi_a_1102", "a:1102")
                            repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            queueCharWrite(writeCharA, buildEpochTimeSyncCmd(), "wifi_a_0509", "a:0509")
                            repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            queueCharWrite(writeCharB, byteArrayOf(0x01, 0x08), "wifi_b_0108", "b:0108")
                            repeat(2) { gattOpsQueue.add { /* wait tick */ } }
                            queueCharWrite(writeCharB, byteArrayOf(0x01, 0x07), "wifi_b_0107", "b:0107")
                            repeat(25) { gattOpsQueue.add { /* await AP scan (~8s) */ } }
                            val wifiChunks = QingpingBleParser.encodePairBWifiCredentialsChunks(
                                sessionWifiCreds.first,
                                sessionWifiCreds.second
                            )
                            queuePairBChunks(
                                writeCharB,
                                wifiChunks,
                                "wifi_b_creds",
                                "b_wifi",
                                redactPayload = true
                            )
                            repeat(62) { gattOpsQueue.add { /* await Wi-Fi assoc (~20s) */ } }
                            val histStdBytes = QingpingBleParser.fromHexString(
                                QingpingBleParser.QINGPING_PAIR_B_STANDARD_HISTORY_TYPE_HEX
                            )
                            queueCharWrite(
                                writeCharB,
                                histStdBytes,
                                "wifi_b_hist_std",
                                "b_hist_std:${QingpingBleParser.QINGPING_PAIR_B_STANDARD_HISTORY_TYPE_HEX}"
                            )
                            repeat(3) { gattOpsQueue.add { /* wait tick */ } }
                            val mqttChunks = QingpingBleParser.encodePairBMqttServerChunks(
                                QingpingBleParser.QINGPING_OFFLINE_PROVISION_MQTT_SPEC
                            )
                            queuePairBChunks(writeCharB, mqttChunks, "wifi_b_mqtt", "b_mqtt")
                            repeat(12) { gattOpsQueue.add { /* await 0x88 commit (~3.8s) */ } }
                        } else if (qingpingSvc != null && oneShotScript != null) {
                            val steps = oneShotScript.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                            val usesPairB = steps.any {
                                it.startsWith("b:", ignoreCase = true) ||
                                    it.startsWith("b_ltv:", ignoreCase = true) ||
                                    it.startsWith("b_wifi:", ignoreCase = true) ||
                                    it.startsWith("b_mqtt:", ignoreCase = true) ||
                                    it.startsWith("b_ota:", ignoreCase = true) ||
                                    it.equals("b_hist_std", ignoreCase = true)
                            }
                            val usesPairC = steps.any { it.startsWith("c:", ignoreCase = true) }
                            queueCccdEnable(notifyCharA)
                            if (usesPairB) queueCccdEnable(notifyCharB)
                            if (usesPairC) queueCccdEnable(notifyCharC)

                            val authToken = getOrCreateAuthToken()
                            for (rawStep in steps) {
                                val stepLower = rawStep.lowercase(Locale.US)
                                when {
                                    stepLower == "a:1101" -> {
                                        queueCharWrite(writeCharA, buildAuthCmd(0x01, authToken), "script_a_1101", "a:1101") {
                                            lastWindowReset1101Ms = System.currentTimeMillis()
                                        }
                                    }
                                    stepLower == "a:1102" -> {
                                        queueCharWrite(writeCharA, buildAuthCmd(0x02, authToken), "script_a_1102", "a:1102")
                                    }
                                    stepLower == "a:0509" -> {
                                        queueCharWrite(writeCharA, buildEpochTimeSyncCmd(), "script_a_0509", "a:0509")
                                    }
                                    stepLower.startsWith("a:") -> {
                                        val bytes = QingpingBleParser.fromHexString(rawStep.substring(2).trim())
                                        queueCharWrite(writeCharA, bytes, "script_a_hex", "a:${QingpingBleParser.toHexString(bytes)}")
                                    }
                                    stepLower.startsWith("b:") -> {
                                        val bytes = QingpingBleParser.fromHexString(rawStep.substring(2).trim())
                                        queueCharWrite(writeCharB, bytes, "script_b_hex", "b:${QingpingBleParser.toHexString(bytes)}")
                                    }
                                    stepLower.startsWith("c:") -> {
                                        val bytes = QingpingBleParser.fromHexString(rawStep.substring(2).trim())
                                        queueCharWrite(writeCharC, bytes, "script_c_hex", "c:${QingpingBleParser.toHexString(bytes)}")
                                    }
                                    stepLower.startsWith("b_ltv:") -> {
                                        val rest = rawStep.substring(6)
                                        val colonIdx = rest.indexOf(':')
                                        if (colonIdx > 0) {
                                            val typeInt = rest.substring(0, colonIdx).trim().toIntOrNull()
                                            val asciiPayload = rest.substring(colonIdx + 1)
                                            if (typeInt != null) {
                                                val chunks = QingpingBleParser.encodeGattLtvChunks(
                                                    type = typeInt,
                                                    payload = asciiPayload.toByteArray(Charsets.UTF_8)
                                                )
                                                queuePairBChunks(writeCharB, chunks, "script_b_ltv_${typeInt}", "b_ltv:$typeInt")
                                            }
                                        }
                                    }
                                    stepLower.startsWith("b_wifi:") -> {
                                        val rest = rawStep.substring(7)
                                        val colonIdx = rest.indexOf(':')
                                        if (colonIdx >= 0) {
                                            val chunks = QingpingBleParser.encodePairBWifiCredentialsChunks(
                                                rest.substring(0, colonIdx),
                                                rest.substring(colonIdx + 1)
                                            )
                                            queuePairBChunks(writeCharB, chunks, "script_b_wifi", "b_wifi", redactPayload = true)
                                        }
                                    }
                                    stepLower == "b_hist_std" -> {
                                        val bytes = QingpingBleParser.fromHexString(
                                            QingpingBleParser.QINGPING_PAIR_B_STANDARD_HISTORY_TYPE_HEX
                                        )
                                        queueCharWrite(
                                            writeCharB,
                                            bytes,
                                            "script_b_hist_std",
                                            "b_hist_std:${QingpingBleParser.QINGPING_PAIR_B_STANDARD_HISTORY_TYPE_HEX}"
                                        )
                                    }
                                    stepLower.startsWith("b_mqtt:") -> {
                                        val chunks = QingpingBleParser.encodePairBMqttServerChunks(rawStep.substring(7))
                                        queuePairBChunks(writeCharB, chunks, "script_b_mqtt", "b_mqtt")
                                    }
                                    stepLower.startsWith("b_ota:") -> {
                                        val otaUrl = rawStep.substring(6).trim()
                                        if (otaUrl.isNotEmpty()) {
                                            val chunks = QingpingBleParser.encodePairBOtaUpgradeChunks(otaUrl)
                                            queuePairBChunks(writeCharB, chunks, "script_b_ota", "b_ota")
                                        }
                                    }
                                    stepLower.startsWith("wait:") -> {
                                        val waitMs = rawStep.substring(5).trim().toLongOrNull() ?: 320L
                                        val ticks = (waitMs / 320L).coerceAtLeast(1L).toInt()
                                        repeat(ticks) {
                                            gattOpsQueue.add { /* script wait tick */ }
                                        }
                                    }
                                }
                            }
                        } else if (qingpingSvc != null && sessionCfg.strategy == KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP) {
                            if (lastSampledFrame?.isProvisionedNormalMode != true && writeCharA != null) {
                                val authToken = getOrCreateAuthToken()
                                queueCccdEnable(notifyCharA)
                                queueCccdEnable(notifyCharB)
                                queueCharWrite(writeCharA, buildAuthCmd(0x01, authToken), "auto_offline_1101", "1101") {
                                    lastWindowReset1101Ms = System.currentTimeMillis()
                                }
                                queueCharWrite(writeCharA, buildAuthCmd(0x02, authToken), "auto_offline_1102", "1102")
                                queueCharWrite(writeCharA, buildEpochTimeSyncCmd(), "auto_offline_0509")
                                if (writeCharB != null) {
                                    queueCharWrite(writeCharB, byteArrayOf(0x01, 0x08), "auto_offline_b_0108", "b:0108")
                                    val mqttChunks = QingpingBleParser.encodePairBMqttServerChunks(
                                        QingpingBleParser.QINGPING_OFFLINE_PROVISION_MQTT_SPEC
                                    )
                                    queuePairBChunks(writeCharB, mqttChunks, "auto_offline_b_mqtt", "b_mqtt")
                                    // Wait ~3.8s (+800ms drain tail = ~4.6s) for 04 FF 02 00 00 ACK & 0x8A->0x88 transition
                                    repeat(12) {
                                        gattOpsQueue.add { /* await 0x88 transition tick */ }
                                    }
                                }
                            }
                        } else if (qingpingSvc != null) {
                            val allow24m1101 = sessionCfg.strategy == KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101
                            val due24m1101 = allow24m1101 &&
                                lastWindowReset1101Ms > 0L &&
                                (System.currentTimeMillis() - lastWindowReset1101Ms) >= 24L * 60_000L
                            val shouldSend1101 = due24m1101
                            val shouldSend1102 = shouldSend1101
                            val shouldSend0509 = shouldSend1101 ||
                                sessionCfg.strategy == KeepAlivePayloadStrategy.TIME_SYNC_0509 ||
                                sessionCfg.strategy == KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101
                            if ((shouldSend1101 || shouldSend1102 || shouldSend0509) && writeCharA != null) {
                                queueCccdEnable(notifyCharA)
                                val authToken = getOrCreateAuthToken()
                                if (shouldSend1101) {
                                    queueCharWrite(writeCharA, buildAuthCmd(0x01, authToken), "periodic_24m_bond_1101", "1101") {
                                        lastWindowReset1101Ms = System.currentTimeMillis()
                                    }
                                }
                                if (shouldSend1102) {
                                    queueCharWrite(writeCharA, buildAuthCmd(0x02, authToken), "auth_confirm_1102", "1102")
                                }
                                if (shouldSend0509) {
                                    queueCharWrite(writeCharA, buildEpochTimeSyncCmd(), "rtc_time_sync_0509")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        setupError = true
                        val errMsg = e.message ?: e.javaClass.simpleName
                        hwLogger.logEvent(
                            "DEV_GATT_SCRIPT_ERROR",
                            mapOf(
                                "session_id" to sessionId,
                                "cfg_epoch" to sessionCfg.configEpoch,
                                "is_ota" to isOtaSession,
                                "error" to errMsg
                            )
                        )
                        if (isOtaSession) {
                            hwLogger.logEvent(
                                "DEV_OTA_STATUS",
                                mapOf(
                                    "session_id" to sessionId,
                                    "cfg_epoch" to sessionCfg.configEpoch,
                                    "state" to "error",
                                    "error" to errMsg
                                )
                            )
                            isOtaUpgradeInFlight = false
                        }
                        mainHandler.post {
                            gattStatusText = "GATT #$sessionId setup error: $errMsg"
                            appendRawFrameLog(gattStatusText)
                            emitSessionSummary(status)
                            disconnectGatt(clearOtaFlag = true)
                            notifyListeners()
                        }
                    }

                    hwLogger.logEvent(
                        "GATT_SERVICES_DISCOVERED",
                        mapOf(
                            "session_id" to sessionId,
                            "cfg_epoch" to sessionCfg.configEpoch,
                            "mac" to device.address,
                            "status" to status,
                            "services_count" to gatt.services.size
                        )
                    )
                    if (setupError) {
                        return
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS && gatt.services.isNotEmpty()) {
                        hasCompletedInitialGattDiscovery = true
                        lastPeriodicGattPokeMs = System.currentTimeMillis()
                    }

                    val drainRunnable = object : Runnable {
                        override fun run() {
                            if (activeGatt !== gatt) return
                            val nextOp = gattOpsQueue.pollFirst()
                            if (nextOp != null) {
                                nextOp.invoke()
                                mainHandler.postDelayed(this, 320L)
                            } else if (shouldAutoDisconnect) {
                                mainHandler.postDelayed({
                                    if (activeGatt === gatt) {
                                        lastPeriodicGattPokeMs = System.currentTimeMillis()
                                        isGattProbeInFlight = false
                                        emitSessionSummary(0)
                                        gattStatusText = "GATT #$sessionId Keep-Alive Complete (${sessionCfg.strategy.id})"
                                        disconnectGatt()
                                        notifyListeners()
                                    }
                                }, 800L)
                            } else {
                                lastPeriodicGattPokeMs = System.currentTimeMillis()
                                if (!isOtaSession) {
                                    isGattProbeInFlight = false
                                    gattStatusText = "GATT #$sessionId Held Open (${sessionCfg.strategy.id})"
                                }
                                notifyListeners()
                            }
                        }
                    }

                    mainHandler.post {
                        if (!isSleepingForAbsentCompanion) {
                            startBleScan()
                        }
                        gattServicesLog.clear()
                        gattServicesLog.addAll(summary)
                        gattStatusText = if (isOtaSession) {
                            "OTA #$sessionId: queuing ${gattOpsQueue.size} ops..."
                        } else {
                            "GATT #$sessionId (${sessionCfg.strategy.id}): running ${gattOpsQueue.size} ops..."
                        }
                        notifyListeners()
                        mainHandler.postDelayed(drainRunnable, 150L)
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    val hex = QingpingBleParser.toHexString(value)
                    val ascii = value.map { b ->
                        val c = b.toInt() and 0xFF
                        if (c in 32..126) c.toChar() else '.'
                    }.joinToString("")
                    readsRcvd.add("${characteristic.uuid.toString().take(8)}:$ascii")
                    hwLogger.logEvent(
                        "GATT_CHAR_READ",
                        mapOf(
                            "session_id" to sessionId,
                            "mac" to device.address,
                            "char_uuid" to characteristic.uuid.toString(),
                            "status" to status,
                            "raw_hex" to hex,
                            "ascii" to ascii
                        )
                    )
                    mainHandler.post {
                        appendRawFrameLog("GATT READ [${characteristic.uuid.toString().take(8)}]: $hex ($ascii)")
                        notifyListeners()
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    val hex = QingpingBleParser.toHexString(value)
                    val charPrefix = characteristic.uuid.toString().substring(4, 8).lowercase(Locale.US)
                    notifiesRcvd.add("$charPrefix:$hex")
                    hwLogger.logEvent(
                        "GATT_CHAR_NOTIFY",
                        mapOf(
                            "session_id" to sessionId,
                            "mac" to device.address,
                            "char_uuid" to characteristic.uuid.toString(),
                            "raw_hex" to hex
                        )
                    )
                    val wifiAssocConnected = if (charPrefix == "000e" &&
                        value.size >= 3 &&
                        (value[0].toInt() and 0xFF) == 0x02 &&
                        (value[1].toInt() and 0xFF) == 0x01
                    ) {
                        (value[2].toInt() and 0xFF) == 0x01
                    } else {
                        null
                    }
                    val otaStatus = if (isOtaSession && charPrefix == "000e") {
                        QingpingBleParser.parsePairBOtaNotify(value)
                    } else {
                        null
                    }
                    mainHandler.post {
                        appendRawFrameLog("GATT NOTIFY [${characteristic.uuid.toString().take(8)}]: $hex")
                        if (wifiAssocConnected != null && activeGatt === gatt) {
                            hwLogger.logEvent(
                                "DEV_WIFI_CONFIG_STATUS",
                                mapOf(
                                    "session_id" to sessionId,
                                    "cfg_epoch" to sessionCfg.configEpoch,
                                    "connected" to wifiAssocConnected,
                                    "raw_hex" to hex
                                )
                            )
                            gattStatusText = if (wifiAssocConnected) {
                                "Wi-Fi config #$sessionId: associated & DHCP OK ($hex)"
                            } else {
                                "Wi-Fi config #$sessionId: association failed ($hex)"
                            }
                        }
                        if (otaStatus != null && activeGatt === gatt) {
                            val rejectDetail = if (otaStatus.state == "rejected") {
                                QingpingBleParser.describePairBOtaRejectCode(otaStatus.rawValue)
                            } else {
                                null
                            }
                            val statusFields = LinkedHashMap<String, Any?>()
                            statusFields["session_id"] = sessionId
                            statusFields["cfg_epoch"] = sessionCfg.configEpoch
                            statusFields["state"] = otaStatus.state
                            statusFields["raw_val"] = otaStatus.rawValue
                            statusFields["progress_pct"] = otaStatus.progressPct
                            statusFields["raw_hex"] = hex
                            if (rejectDetail != null) {
                                statusFields["error_detail"] = rejectDetail
                            }
                            hwLogger.logEvent("DEV_OTA_STATUS", statusFields)
                            when (otaStatus.state) {
                                "progress" -> {
                                    armOtaWatchdog(gatt, 60_000L)
                                    gattStatusText = "OTA #$sessionId in progress: ${otaStatus.progressPct}%"
                                }
                                "rejected" -> {
                                    cancelOtaWatchdog()
                                    isOtaUpgradeInFlight = false
                                    gattStatusText = "OTA #$sessionId rejected: $rejectDetail"
                                    emitSessionSummary(0)
                                    disconnectGatt(clearOtaFlag = true)
                                }
                                "complete" -> {
                                    cancelOtaWatchdog()
                                    isOtaUpgradeInFlight = false
                                    gattStatusText = "OTA #$sessionId complete (${otaStatus.progressPct}%); reading FW / awaiting reboot"
                                    otaFwChar?.let { ch ->
                                        try {
                                            gatt.readCharacteristic(ch)
                                        } catch (_: Exception) {
                                        }
                                    }
                                    mainHandler.postDelayed({
                                        if (activeGatt === gatt) {
                                            emitSessionSummary(0)
                                            disconnectGatt(clearOtaFlag = true)
                                            notifyListeners()
                                        }
                                    }, 2_000L)
                                }
                            }
                        }
                        notifyListeners()
                    }
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnectGatt(clearOtaFlag: Boolean = true) {
        cancelOtaWatchdog()
        if (clearOtaFlag) {
            isOtaUpgradeInFlight = false
        }
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {
        } finally {
            isGattProbeInFlight = false
            activeGatt = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsCollection() {
        if (isLocationActive) return
        val lm = locationManager ?: return
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationManager.FUSED_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
            lm.requestLocationUpdates(provider, 5_000L, 0f, locationListener, Looper.getMainLooper())
            isLocationActive = true
            startForegroundWithCurrentType()
        } catch (_: SecurityException) {
            isLocationActive = false
        } catch (_: Exception) {
            isLocationActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopGpsCollection() {
        if (!isLocationActive) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        } finally {
            isLocationActive = false
            lastLocationFix = null
            gpsMovementFilter.reset()
            latestGpsFilteredSpeedMps = null
            latestGpsAccuracyM = null
            refreshEffectiveSpeed()
            startForegroundWithCurrentType()
        }
    }

    private val locationListener = LocationListener { location ->
        val acc = if (location.hasAccuracy()) location.accuracy else 999f
        val dopplerSpd = if (location.hasSpeed()) location.speed else null
        val filtered = gpsMovementFilter.onLocationFix(
            latDeg = location.latitude,
            lonDeg = location.longitude,
            timestampMs = location.time,
            horizontalAccuracyM = acc,
            dopplerSpeedMps = dopplerSpd
        )
        latestGpsAccuracyM = filtered.horizontalAccuracyM
        latestGpsFilteredSpeedMps = filtered.speedMps
        lastLocationFix = location
        refreshEffectiveSpeed()
        // Note: Raw latitude & longitude are intentionally not persisted to disk by default.
        notifyListeners()
    }

    fun refreshStepSensorRegistration() {
        val nowSec = System.currentTimeMillis() / 1000L
        val transition = stateMachine.onTimerTick(nowSec)
        if (transition.currentState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE) {
            startStepCollection()
        } else {
            stopStepCollection()
        }
        refreshEffectiveSpeed()
        updateOngoingForegroundNotification()
        notifyListeners()
    }

    private fun startStepCollection() {
        if (isStepSensorActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sm = sensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: return
        try {
            stepMovementEstimator.reset()
            val registered = sm.registerListener(
                stepSensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                0,
                mainHandler
            )
            if (registered) {
                activeStepSensor = sensor
                isStepSensorActive = true
            }
        } catch (_: SecurityException) {
            isStepSensorActive = false
            activeStepSensor = null
        } catch (_: Exception) {
            isStepSensorActive = false
            activeStepSensor = null
        }
    }

    private fun stopStepCollection() {
        if (isStepSensorActive) {
            try {
                sensorManager?.unregisterListener(stepSensorListener)
            } catch (_: Exception) {
            }
        }
        isStepSensorActive = false
        activeStepSensor = null
        stepMovementEstimator.reset()
        refreshEffectiveSpeed()
    }

    private fun refreshEffectiveSpeed() {
        val stepSpd = if (isStepSensorActive) {
            stepMovementEstimator.estimateSpeedMps(SystemClock.elapsedRealtime())
        } else {
            null
        }
        val fused = StepMovementEstimator.fuseMovementSpeed(
            gpsSpeedMps = latestGpsFilteredSpeedMps,
            gpsAccuracyM = latestGpsAccuracyM,
            stepSpeedMps = stepSpd,
            isStepSensorActive = isStepSensorActive
        )
        latestSpeedMps = fused.speedMps
        latestSpeedSource = fused.source
    }

    private val stepSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val rawEventElapsedMs = event.timestamp / 1_000_000L
            val deltaMs = kotlin.math.abs(nowElapsedMs - rawEventElapsedMs)
            val tsMs = if (rawEventElapsedMs > 0L && deltaMs <= 60_000L) rawEventElapsedMs else nowElapsedMs
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    val count = event.values.size.coerceAtLeast(1)
                    stepMovementEstimator.onStepDetected(tsMs, count)
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values.firstOrNull()?.toLong() ?: return
                    stepMovementEstimator.onStepCounterTotal(totalSteps, tsMs)
                }
            }
            refreshEffectiveSpeed()
            notifyListeners()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerWifiCallback() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                    val ssid = wifiInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
                    val bssid = wifiInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
                    mainHandler.post {
                        rawWifiSsid = ssid
                        currentWifiBssid = bssid
                        refreshWifiAndPlaceState()
                    }
                }

                override fun onLost(network: Network) {
                    mainHandler.post {
                        rawWifiSsid = null
                        currentWifiBssid = null
                        refreshWifiAndPlaceState()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun readFallbackWifiInfoIfNeeded() {
        if (!rawWifiSsid.isNullOrBlank()) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val info = wm.connectionInfo ?: return
            val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
            if (ssid != null) {
                rawWifiSsid = ssid
                currentWifiBssid = bssid
            }
        } catch (_: Exception) {
        }
    }

    private fun unregisterWifiCallback() {
        val cb = wifiNetworkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        wifiNetworkCallback = null
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.notif_channel_status_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_status_desc)
            setShowBadge(false)
        }
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alerts_desc)
        }
        val tripSuggestionChannel = NotificationChannel(
            CHANNEL_TRIP_SUGGESTIONS,
            getString(R.string.notif_channel_trip_suggestions_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_trip_suggestions_desc)
        }
        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
        nm.createNotificationChannel(tripSuggestionChannel)
        ensureLifecycleNotificationChannel(this)
    }

    private fun startForegroundWithCurrentType(startReason: String = "activity"): Boolean {
        val key = buildStatusNotificationKey()
        val notification = buildStatusNotification(key)
        // Always include FOREGROUND_SERVICE_TYPE_LOCATION when location permission is granted so
        // Android 14+ allows starting LocationManager updates later when walking out of a Saved Wi-Fi
        // Place with the phone in a pocket (background). Actual GPS hardware polling remains 0 mW
        // until startGpsCollection() is called during FIELD_EXCURSION_ACTIVE.
        val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification, fgsType)
            lastPostedStatusNotificationKey = key
            return true
        } catch (firstErr: Exception) {
            // Fallback if location permission not yet granted
            try {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                lastPostedStatusNotificationKey = key
                return true
            } catch (fallbackErr: Exception) {
                hwLogger.logEvent(
                    "FGS_START_FAILED",
                    mapOf(
                        "reason" to startReason,
                        "first_error" to "${firstErr.javaClass.simpleName}: ${firstErr.message}",
                        "fallback_error" to "${fallbackErr.javaClass.simpleName}: ${fallbackErr.message}"
                    )
                )
                return false
            }
        }
    }

    private fun updateOngoingForegroundNotification(force: Boolean = false) {
        if (isSleepingForAbsentCompanion || isUserStopping) return
        val key = buildStatusNotificationKey()
        if (!force && key == lastPostedStatusNotificationKey) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_FOREGROUND, buildStatusNotification(key))
        lastPostedStatusNotificationKey = key
    }

    private fun buildStatusNotificationKey(): StatusNotificationKey {
        val matchedPlace = metadataDb.getKnownPlaces().firstOrNull { it.id == stateMachine.currentKnownPlaceId }
        val contextTitle = when (stateMachine.currentState) {
            ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP ->
                if (matchedPlace != null) {
                    getString(R.string.notif_status_title_at_place, matchedPlace.label)
                } else {
                    getString(R.string.notif_status_title_monitoring_no_place)
                }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE -> when {
                isLocationActive -> getString(R.string.notif_status_title_trip_gps)
                isStepSensorActive -> getString(R.string.notif_status_title_trip_steps)
                else -> getString(R.string.notif_status_title_trip_gps_off)
            }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED ->
                getString(R.string.notif_status_title_trip_paused)
            ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ->
                getString(R.string.notif_status_title_waiting_monitor)
        }

        val metricsBody = if (lastSampledFrame != null) {
            val useF = TemperatureUnitFormatter.useFahrenheit(this)
            val tempStr = TemperatureUnitFormatter.format(latestTempC, useF, decimals = 1)
            val co2Str = if (latestCo2Ppm > 0) {
                getString(R.string.notif_status_co2_ppm, latestCo2Ppm)
            } else {
                getString(R.string.notif_status_co2_placeholder)
            }
            getString(
                R.string.notif_status_body_metrics,
                co2Str,
                latestPm25Ugm3,
                tempStr,
                latestHumidityPct
            )
        } else {
            getString(R.string.notif_status_body_scanning)
        }

        return StatusNotificationKey(
            title = contextTitle,
            body = metricsBody,
            hasActiveExcursion = activeExcursionId != null
        )
    }

    private fun buildStatusNotification(
        key: StatusNotificationKey = buildStatusNotificationKey()
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_aql)
            .setContentTitle(key.title)
            .setContentText(key.body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (key.hasActiveExcursion) {
            val bookmarkIntent = PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, AlertActionReceiver::class.java).apply {
                    action = AlertActionReceiver.ACTION_BOOKMARK_CURRENT_TRIP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.star_on,
                getString(R.string.notif_action_bookmark_trip),
                bookmarkIntent
            )
        } else if (shouldShowStopNotificationAction(key.hasActiveExcursion)) {
            val stopIntent = PendingIntent.getBroadcast(
                this,
                2,
                Intent(this, AlertActionReceiver::class.java).apply {
                    action = AlertActionReceiver.ACTION_STOP_SERVICE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop_service),
                stopIntent
            )
        }

        return builder.build()
    }

    private fun dispatchAlertNotifications(actions: List<AlertEvaluator.AlertAction>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (act in actions) {
            when (act.directive) {
                AlertEvaluator.NotificationDirective.NO_CHANGE -> Unit
                AlertEvaluator.NotificationDirective.CANCEL_IN_PLACE -> {
                    nm.cancel(act.metric.notificationId)
                }
                AlertEvaluator.NotificationDirective.TRIGGER_NEW_OR_ESCALATE,
                AlertEvaluator.NotificationDirective.UPDATE_IN_PLACE_SILENT -> {
                    val onlyAlertOnce = (act.directive == AlertEvaluator.NotificationDirective.UPDATE_IN_PLACE_SILENT)
                    val dismissIntent = PendingIntent.getBroadcast(
                        this,
                        act.metric.notificationId,
                        Intent(this, AlertActionReceiver::class.java).apply {
                            action = AlertActionReceiver.ACTION_ALERT_DISMISSED
                            putExtra(AlertActionReceiver.EXTRA_METRIC_NAME, act.metric.name)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val openIntent = PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val durMin = act.durationAboveThresholdSec / 60
                    val durSec = act.durationAboveThresholdSec % 60
                    val severityLabel = when (act.severity) {
                        AlertEvaluator.SeverityTier.WARNING -> getString(R.string.notif_alert_severity_warning)
                        AlertEvaluator.SeverityTier.CRITICAL -> getString(R.string.notif_alert_severity_critical)
                        AlertEvaluator.SeverityTier.NONE -> act.severity.name
                    }
                    val metricLabel = when (act.metric) {
                        AlertEvaluator.MetricType.CO2 -> getString(R.string.notif_alert_metric_co2)
                        AlertEvaluator.MetricType.PM25 -> getString(R.string.notif_alert_metric_pm25)
                        AlertEvaluator.MetricType.BATTERY_LOW -> getString(R.string.notif_alert_metric_battery_low)
                    }
                    val title = getString(
                        R.string.notif_alert_title,
                        severityLabel,
                        metricLabel,
                        act.currentValue,
                        act.metric.unit,
                        act.trend.symbol
                    )
                    val body = if (act.metric == AlertEvaluator.MetricType.BATTERY_LOW) {
                        getString(R.string.notif_alert_body_battery, act.peakValueInSpike)
                    } else {
                        getString(
                            R.string.notif_alert_body_threshold,
                            act.peakValueInSpike,
                            act.metric.unit,
                            durMin,
                            durSec
                        )
                    }

                    val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_aql)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setContentIntent(openIntent)
                        .setDeleteIntent(dismissIntent)
                        .setOnlyAlertOnce(onlyAlertOnce)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                    nm.notify(act.metric.notificationId, notif)
                }
            }
        }
    }

    private fun postManualTripStopSuggestionNotification(knownPlaceId: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val placeLabel = metadataDb.getKnownPlaces().firstOrNull { it.id == knownPlaceId }?.label
        val (title, body) = buildManualTripStopSuggestionCopy(this, placeLabel)
        val openIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_MANUAL_TRIP_STOP_SUGGESTION,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val endTripIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID_MANUAL_TRIP_STOP_SUGGESTION + 1,
            Intent(this, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_END_MANUAL_TRIP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_TRIP_SUGGESTIONS)
            .setSmallIcon(R.drawable.ic_stat_aql)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_end_trip), endTripIntent)
            .build()
        try {
            nm.notify(NOTIFICATION_ID_MANUAL_TRIP_STOP_SUGGESTION, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelManualTripStopSuggestionNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID_MANUAL_TRIP_STOP_SUGGESTION)
    }

    private fun maybePostCompanionLinkSuggestionNotification(bleAddress: String, monitorName: String?) {
        if (hasLinkedCompanionDevice || CompanionDeviceHelper.isLinkSuggestionSuppressed(this)) return
        val hasPostNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPostNotif) return
        val screenshotAutomationActive = getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .getBoolean("screenshot_automation_active", false)
        val isDemoOrScreenshot = isDemoStreamRunning || isDemoDataEnabled(this) || screenshotAutomationActive
        if (isDemoOrScreenshot) return

        val isSupported = CompanionDeviceHelper.isSupported(this)
        val hasAssociations = if (isSupported) CompanionDeviceHelper.hasAssociations(this) else false
        if (hasAssociations) {
            hasLinkedCompanionDevice = true
        }
        val shouldPost = CompanionDeviceHelper.shouldPostProactiveLinkSuggestion(
            isCompanionSupported = isSupported,
            hasExistingAssociations = hasAssociations,
            isSuggestionSuppressed = false,
            hasNotificationPermission = hasPostNotif,
            isDemoOrScreenshotMode = isDemoOrScreenshot,
            liveBleAddress = bleAddress
        )
        if (!shouldPost) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureLifecycleNotificationChannel(this)
        val (title, body) = buildCompanionLinkSuggestionCopy(this, monitorName, bleAddress)
        val linkIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_LINK_COMPANION_MONITOR
            putExtra(EXTRA_LINK_COMPANION_BLE_ADDRESS, bleAddress)
            if (!monitorName.isNullOrBlank()) {
                putExtra(EXTRA_LINK_COMPANION_MONITOR_NAME, monitorName)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val linkPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_COMPANION_LINK_SUGGESTION,
            linkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID_COMPANION_LINK_SUGGESTION + 1,
            Intent(this, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_DISMISS_COMPANION_LINK_SUGGESTION
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_LIFECYCLE)
            .setSmallIcon(R.drawable.ic_stat_aql)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(linkPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_add, getString(R.string.notif_action_link_monitor), linkPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_dont_ask_again), dismissPendingIntent)
            .build()
        try {
            nm.notify(NOTIFICATION_ID_COMPANION_LINK_SUGGESTION, notification)
            CompanionDeviceHelper.markLinkSuggestionShown(this)
            hwLogger.logEvent(
                "COMPANION_LINK_SUGGESTION_POSTED",
                mapOf("ble_mac" to bleAddress, "monitor_name" to monitorName)
            )
        } catch (_: SecurityException) {
        }
    }

    /**
     * Generates a realistic 75-minute Downtown Chicago excursion (CTA Bus -> Loop Coffee Shop
     * with urban canyon GPS multipath -> Bookshop -> Return Transit) so the user can test
     * retrospective session slicing, shared dividers, and XLSX export immediately.
     */
    fun toggleDemoStream(): Boolean {
        isDemoStreamRunning = !isDemoStreamRunning
        mainHandler.removeCallbacks(demoStreamRunnable)
        if (isDemoStreamRunning) {
            mainHandler.post(demoStreamRunnable)
        }
        notifyListeners()
        return isDemoStreamRunning
    }

    fun stopDemoStream() {
        if (!isDemoStreamRunning) return
        isDemoStreamRunning = false
        mainHandler.removeCallbacks(demoStreamRunnable)
        notifyListeners()
    }

    private fun buildSyntheticCgdn1Frame(
        co2Ppm: Int,
        pm25Ugm3: Int,
        pm10Ugm3: Int,
        tempX10: Int,
        humX10: Int,
        batteryPct: Int,
        rssiDbm: Int,
        timestampMs: Long = System.currentTimeMillis()
    ): QingpingBleParser.DecodedQingpingFrame? {
        val payload = java.nio.ByteBuffer.allocate(27).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(0x08.toByte())
            put(0x0E.toByte()) // CGDN1 Air Monitor Lite
            put(byteArrayOf(0xCD.toByte(), 0xAB.toByte(), 0x12, 0x34, 0x2D, 0x58))
            put(0x01.toByte()); put(0x04.toByte()); putShort(tempX10.toShort()); putShort(humX10.toShort())
            put(0x12.toByte()); put(0x04.toByte()); putShort(pm25Ugm3.toShort()); putShort(pm10Ugm3.toShort())
            put(0x13.toByte()); put(0x02.toByte()); putShort(co2Ppm.toShort())
            put(0x02.toByte()); put(0x01.toByte()); put(batteryPct.toByte())
        }.array()

        return QingpingBleParser.parseServiceData(
            payload,
            timestampMs = timestampMs,
            rssiDbm = rssiDbm
        )
    }

    private fun ensureDemoStreamRows(): List<XlsxImporter.ParsedSampleRow> {
        if (demoStreamRows.isEmpty()) {
            demoStreamRows = assets.open("demo/sample_trip.xlsx").use { input ->
                XlsxImporter.parseXlsx(input).rows
            }
        }
        return demoStreamRows
    }

    private fun emitNextDemoReplayFrame() {
        val rows = ensureDemoStreamRows()
        if (rows.isEmpty()) return
        val row = rows[demoTickCounter % rows.size]
        demoTickCounter++
        val slot = row.slot
        val decoded = buildSyntheticCgdn1Frame(
            co2Ppm = slot.co2PpmMean,
            pm25Ugm3 = slot.pm25Mean.roundToInt(),
            pm10Ugm3 = slot.pm10Mean.roundToInt(),
            tempX10 = slot.tempCMeanX10,
            humX10 = slot.humidityMeanX10,
            batteryPct = if (slot.batteryPctLast in 0..100) slot.batteryPctLast else 88,
            rssiDbm = if (slot.rssiDbmLast > -128) slot.rssiDbmLast else -58
        ) ?: return
        handleDecodedFrame(decoded, isSyntheticDemo = true)
    }

    /**
     * Populates a deterministic, privacy-safe live "Now" dashboard state at Saved Place "Home"
     * (`ChicagoHome-5G`) by replaying `assets/demo/sample_now_stationary.xlsx` time-shifted to `now`.
     */
    fun seedDemoNowDashboard() {
        for (exc in metadataDb.getExcursions()) {
            detachActiveExcursionIfMatching(exc.id)
            metadataDb.deleteExcursion(exc.id)
        }
        val places = metadataDb.getKnownPlaces()
        val homePlace = places.firstOrNull { it.label == "Home" }
            ?: places.firstOrNull()
            ?: metadataDb.createKnownPlaceWithWifi("Home", "ChicagoHome-5G", "AA:BB:CC:11:22:33")
        if (rawWifiSsid.isNullOrBlank()) {
            rawWifiSsid = "ChicagoHome-5G"
        }
        setFakeWifiSsidOverride("ChicagoHome-5G")
        setManualKnownPlaceOverride(homePlace.id)

        val nowMs = System.currentTimeMillis()
        val nowSec = (nowMs / 1000L / 15L) * 15L
        val parsed = assets.open("demo/sample_now_stationary.xlsx").use { input ->
            XlsxImporter.parseXlsx(input)
        }
        val result = XlsxImporter.importTimeShiftedForDemoReplay(
            parsed = parsed,
            metadataDb = metadataDb,
            ringStore = ringStore,
            targetEndEpochSec = nowSec,
            createExcursion = false,
            importNotes = false,
            defaultHomeSsid = "ChicagoHome-5G"
        )
        val lastSlot = result.lastRow?.slot ?: return
        val decoded = buildSyntheticCgdn1Frame(
            co2Ppm = lastSlot.co2PpmMean,
            pm25Ugm3 = lastSlot.pm25Mean.roundToInt(),
            pm10Ugm3 = lastSlot.pm10Mean.roundToInt(),
            tempX10 = lastSlot.tempCMeanX10,
            humX10 = lastSlot.humidityMeanX10,
            batteryPct = if (lastSlot.batteryPctLast in 0..100) lastSlot.batteryPctLast else 100,
            rssiDbm = if (lastSlot.rssiDbmLast > -128) lastSlot.rssiDbmLast else -54,
            timestampMs = nowMs
        ) ?: return
        handleDecodedFrame(decoded, isSyntheticDemo = true)
    }

    /**
     * Imports a 4-sheet AirSlice XLSX workbook (either preserving original timestamps as a pinned
     * excursion or time-shifted to `now` for Demo Data replay).
     */
    fun importExcursionFromXlsx(
        input: InputStream,
        timeShiftToNow: Boolean = false,
        titleOverride: String? = null
    ): XlsxImporter.ImportResult? {
        val parsed = XlsxImporter.parseXlsx(input)
        if (parsed.rows.isEmpty()) return null
        val result = if (timeShiftToNow) {
            demoStreamRows = parsed.rows
            demoTickCounter = 0
            val nowSec = ((System.currentTimeMillis() / 1000L / 15L) * 15L) - 15L
            XlsxImporter.importTimeShiftedForDemoReplay(
                parsed = parsed,
                metadataDb = metadataDb,
                ringStore = ringStore,
                targetEndEpochSec = nowSec,
                createExcursion = true,
                importNotes = true,
                defaultHomeSsid = "ChicagoHome-5G",
                titleOverride = titleOverride
            )
        } else {
            XlsxImporter.importPreservingTimestamps(
                parsed = parsed,
                metadataDb = metadataDb,
                defaultHomeSsid = "ChicagoHome-5G",
                titleOverride = titleOverride
            )
        }
        notifyListeners()
        return result
    }

    fun seedDemoChicagoExcursion(): Long {
        if (metadataDb.getKnownPlaces().isEmpty()) {
            metadataDb.createKnownPlaceWithWifi("Home", "ChicagoHome-5G", "AA:BB:CC:11:22:33")
        }
        return assets.open("demo/sample_trip.xlsx").use { input ->
            importExcursionFromXlsx(input, timeShiftToNow = true)?.excursionId ?: 0L
        }
    }

    private fun appendRawFrameLog(entry: String) {
        val timePrefix = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        if (rawFrameHistory.size >= 40) {
            rawFrameHistory.removeLast()
        }
        rawFrameHistory.addFirst("$timePrefix  $entry")
    }

    fun buildSnapshot(): LiveTelemetrySnapshot {
        val knownPlaces = metadataDb.getKnownPlaces()
        val matchedPlace = resolveMatchedKnownPlace(knownPlaces)
        val stateTransition = stateMachine.onTimerTick(System.currentTimeMillis() / 1000L)
        return LiveTelemetrySnapshot(
            collectorState = stateTransition.currentState,
            effectiveBleMode = stateTransition.effectiveBleMode,
            userExcursionBleMode = stateMachine.userExcursionBleMode,
            strictFdcdFilterEnabled = strictFdcdFilterEnabled,
            devConfig = devConfig,
            connectedWifiSsid = effectiveWifiSsid(),
            connectedWifiBssid = currentWifiBssid,
            matchedKnownPlace = matchedPlace,
            hasConfiguredKnownPlaces = knownPlaces.isNotEmpty(),
            activeExcursionId = activeExcursionId,
            lastFrame = lastSampledFrame,
            interPacketDeltaMs = lastInterPacketDeltaMs,
            mergedCo2Ppm = latestCo2Ppm,
            mergedPm25Ugm3 = latestPm25Ugm3,
            mergedPm10Ugm3 = latestPm10Ugm3,
            mergedTempC = latestTempC,
            mergedHumidityPct = latestHumidityPct,
            mergedBatteryPct = latestBatteryPct,
            isGpsActive = isLocationActive,
            isStepSensorActive = isStepSensorActive,
            currentSpeedMps = latestSpeedMps,
            currentSpeedSource = latestSpeedSource,
            currentGpsAccuracyM = latestGpsAccuracyM,
            gattConnectionStatus = gattStatusText,
            gattDiscoveredServicesLog = gattServicesLog.toList(),
            recentRawFramesLog = rawFrameHistory.toList(),
            isPendingWifiLossDebounce = stateMachine.isPendingWifiLossDebounce
        )
    }

    private fun notifyListeners() {
        val snap = buildSnapshot()
        for (l in listeners) {
            l(snap)
        }
    }

    companion object {
        const val CHANNEL_STATUS = "aql_live_status"
        const val CHANNEL_ALERTS = "aql_threshold_alerts"
        const val CHANNEL_LIFECYCLE = "aql_lifecycle_status"
        const val CHANNEL_TRIP_SUGGESTIONS = "aql_trip_suggestions"
        const val NOTIFICATION_ID_FOREGROUND = 1000
        const val NOTIFICATION_ID_RESUME_REQUIRED = 1004
        const val NOTIFICATION_ID_MANUAL_TRIP_STOP_SUGGESTION = 1005
        const val NOTIFICATION_ID_COMPANION_LINK_SUGGESTION = 1006
        const val EXTRA_START_REASON = "io.github.androidaql.extra.START_REASON"
        const val ACTION_LINK_COMPANION_MONITOR = "io.github.androidaql.ACTION_LINK_COMPANION_MONITOR"
        const val EXTRA_LINK_COMPANION_BLE_ADDRESS = "io.github.androidaql.extra.LINK_COMPANION_BLE_ADDRESS"
        const val EXTRA_LINK_COMPANION_MONITOR_NAME = "io.github.androidaql.extra.LINK_COMPANION_MONITOR_NAME"
        const val XIAOMI_MIBEACON_SERVICE_UUID_STRING = "0000fe95-0000-1000-8000-00805f9b34fb"
        const val QINGPING_AIR_MONITOR_LITE_NAME = "Qingping Air Monitor Lite"
        const val ACTION_DEV_COMMAND = "net.dyfis.androidaql.DEV_COMMAND"
        const val PERIODIC_GATT_KEEPALIVE_INTERVAL_MS = 210_000L
        const val GATT_RETRY_COOLDOWN_MS = 15_000L
        const val PREF_WIFI_LOSS_DEBOUNCE_SEC = "wifi_loss_debounce_sec"
        const val PREF_USER_STOPPED_SERVICE = "user_stopped_service"
        const val MAX_WIFI_LOSS_DEBOUNCE_SEC = 300L
        const val ACTIVE_MONITOR_LINK_MAX_AGE_MS = 90_000L
        const val COMPANION_IDLE_SLEEP_GRACE_MS = 180_000L
        const val COMPANION_DISAPPEARED_SLEEP_GRACE_MS = 60_000L
        const val COMPANION_COLD_IDLE_SLEEP_GRACE_MS = 60_000L
        const val MIN_SCAN_MODE_SWITCH_INTERVAL_MS = 10_000L
        const val IDLE_HARDWARE_SCAN_REFRESH_MS = 15 * 60_000L
        const val COMPANION_WAKE_BOOST_WINDOW_MS = 30_000L

        @Volatile
        var instance: AqlCollectorService? = null
            private set

        fun hasBluetoothScanPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

        fun buildAutoStartIntent(context: Context, reason: String): Intent =
            Intent(context, AqlCollectorService::class.java).apply {
                putExtra(EXTRA_START_REASON, reason)
            }

        fun tryAutoStartService(context: Context, reason: String): Boolean {
            val current = instance
            if (current != null && !current.isSleepingForAbsentCompanion) return true
            if (!hasBluetoothScanPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, buildAutoStartIntent(context, reason))
                true
            } catch (_: ForegroundServiceStartNotAllowedException) {
                postResumeRequiredNotification(context, reason)
                false
            } catch (_: SecurityException) {
                postResumeRequiredNotification(context, reason)
                false
            } catch (_: IllegalStateException) {
                postResumeRequiredNotification(context, reason)
                false
            }
        }

        fun isUserStoppedService(context: Context): Boolean =
            context.getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_USER_STOPPED_SERVICE, false)

        fun setUserStoppedService(context: Context, stopped: Boolean) {
            context.getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_USER_STOPPED_SERVICE, stopped)
                .apply()
        }

        fun shouldShowStopNotificationAction(hasActiveExcursion: Boolean): Boolean =
            !hasActiveExcursion

        fun shouldAllowCompanionAutoWake(isUserStopped: Boolean): Boolean =
            !isUserStopped

        fun onCompanionDeviceAppeared(context: Context, reason: String) {
            if (!shouldAllowCompanionAutoWake(isUserStoppedService(context))) return
            val active = instance
            if (active != null && !active.isSleepingForAbsentCompanion) {
                active.mainHandler.post {
                    if (active.isSleepingForAbsentCompanion) {
                        tryAutoStartService(context, reason)
                    } else {
                        active.onCompanionPresenceChanged(appeared = true, reason = reason)
                    }
                }
                return
            }
            tryAutoStartService(context, reason)
        }

        fun onCompanionDeviceDisappeared(reason: String) {
            instance?.let { svc ->
                svc.mainHandler.post {
                    svc.onCompanionPresenceChanged(appeared = false, reason = reason)
                }
            }
        }

        fun shouldSleepForAbsentCompanion(input: CompanionIdleSleepInput): Boolean {
            if (!input.hasLinkedCompanionDevice) return false
            if (input.collectorState != ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE) return false
            if (input.hasActiveExcursion ||
                input.hasPendingBlipCleanup ||
                input.hasBoundUiClients ||
                input.isDemoStreamRunning ||
                input.isOtaUpgradeInFlight ||
                input.hasActiveGattSession ||
                input.isInUnprovisionedGattKeepAliveWindow ||
                input.isManualExcursionOverride
            ) {
                return false
            }
            val idleSince = input.idleOutOfRangeSinceMs ?: return false
            val effectiveStartMs = maxOf(
                idleSince,
                input.lastUiUnbindMs,
                input.serviceStartedAtMs,
                input.lastCompanionAppearedMs
            )
            val requiredGraceMs = when {
                input.lastLiveBleHeardMs <= 0L -> COMPANION_COLD_IDLE_SLEEP_GRACE_MS
                input.companionDisappearedAtMs != null &&
                    input.companionDisappearedAtMs >= input.lastLiveBleHeardMs ->
                    COMPANION_DISAPPEARED_SLEEP_GRACE_MS
                else -> COMPANION_IDLE_SLEEP_GRACE_MS
            }
            return (input.nowMs - effectiveStartMs) >= requiredGraceMs
        }

        fun chooseBleScanMode(
            strictFdcdFilterEnabled: Boolean,
            collectorState: ExcursionStateMachine.CollectorState,
            hasActiveGattOrOta: Boolean,
            needsActiveUnprovisionedGattDiscovery: Boolean,
            isRecentCompanionWake: Boolean,
            isExternalPowerConnected: Boolean = false
        ): Int {
            if (!strictFdcdFilterEnabled || hasActiveGattOrOta || needsActiveUnprovisionedGattDiscovery) {
                return ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            if (collectorState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE &&
                !isRecentCompanionWake
            ) {
                return ScanSettings.SCAN_MODE_LOW_POWER
            }
            if (isExternalPowerConnected) {
                return ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            return ScanSettings.SCAN_MODE_BALANCED
        }

        fun shouldRestartBleScan(
            isScanning: Boolean,
            strictFdcdFilterEnabled: Boolean,
            collectorState: ExcursionStateMachine.CollectorState,
            msSinceStart: Long,
            msSinceAnyResult: Long
        ): String? {
            if (!isScanning) return null
            if (!strictFdcdFilterEnabled && msSinceStart >= 4 * 60_000L) {
                return "broad_refresh"
            }
            if (strictFdcdFilterEnabled && collectorState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE) {
                return if (msSinceStart >= IDLE_HARDWARE_SCAN_REFRESH_MS) "idle_refresh" else null
            }
            if (msSinceAnyResult >= 60_000L && msSinceStart >= 120_000L) {
                return "no_results"
            }
            return null
        }

        fun buildResumeNotificationCopy(reason: String): Pair<String, String> = when (reason) {
            AqlAutoStartReceiver.REASON_BOOT_COMPLETED ->
                "AirSlice paused after reboot" to "Tap to resume passive air quality logging."
            AqlAutoStartReceiver.REASON_PACKAGE_REPLACED ->
                "AirSlice paused after app update" to "Tap to resume passive air quality logging."
            else ->
                "AirSlice passive logging paused" to "Tap to resume air quality monitoring."
        }

        fun buildResumeNotificationCopy(context: Context, reason: String): Pair<String, String> = when (reason) {
            AqlAutoStartReceiver.REASON_BOOT_COMPLETED ->
                context.getString(R.string.notif_resume_boot_title) to
                    context.getString(R.string.notif_resume_boot_body)
            AqlAutoStartReceiver.REASON_PACKAGE_REPLACED ->
                context.getString(R.string.notif_resume_update_title) to
                    context.getString(R.string.notif_resume_update_body)
            else ->
                context.getString(R.string.notif_resume_default_title) to
                    context.getString(R.string.notif_resume_default_body)
        }

        fun ensureLifecycleNotificationChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_LIFECYCLE,
                context.getString(R.string.notif_channel_lifecycle_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_lifecycle_desc)
            }
            nm.createNotificationChannel(channel)
        }

        fun postResumeRequiredNotification(context: Context, reason: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureLifecycleNotificationChannel(context)
            val (title, body) = buildResumeNotificationCopy(context, reason)
            val openIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_RESUME_REQUIRED,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_LIFECYCLE)
                .setSmallIcon(R.drawable.ic_stat_aql)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                nm.notify(NOTIFICATION_ID_RESUME_REQUIRED, notification)
            } catch (_: SecurityException) {
            }
        }

        fun cancelResumeRequiredNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(NOTIFICATION_ID_RESUME_REQUIRED)
        }

        fun buildCompanionLinkSuggestionCopy(monitorName: String?, bleAddress: String): Pair<String, String> {
            val trimmedName = monitorName?.trim()?.takeIf { it.isNotEmpty() }
            val hasDistinctName = trimmedName != null && !trimmedName.equals(bleAddress, ignoreCase = true)
            val displayLabel = if (hasDistinctName) "$trimmedName ($bleAddress)" else bleAddress
            val titleTarget = if (hasDistinctName) trimmedName else bleAddress
            val title = "Link $titleTarget for battery savings?"
            val body = "Tap to link $displayLabel. AirSlice will sleep when it's away and auto-resume when nearby."
            return title to body
        }

        fun buildCompanionLinkSuggestionCopy(
            context: Context,
            monitorName: String?,
            bleAddress: String
        ): Pair<String, String> {
            val trimmedName = monitorName?.trim()?.takeIf { it.isNotEmpty() }
            val hasDistinctName = trimmedName != null && !trimmedName.equals(bleAddress, ignoreCase = true)
            val displayLabel = if (hasDistinctName) "$trimmedName ($bleAddress)" else bleAddress
            val titleTarget = if (hasDistinctName) trimmedName else bleAddress
            val title = context.getString(R.string.notif_companion_link_title, titleTarget)
            val body = context.getString(R.string.notif_companion_link_body, displayLabel)
            return title to body
        }

        fun cancelCompanionLinkSuggestionNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(NOTIFICATION_ID_COMPANION_LINK_SUGGESTION)
        }

        fun buildManualTripStopSuggestionCopy(placeLabel: String?): Pair<String, String> {
            val trimmed = placeLabel?.trim()?.takeIf { it.isNotEmpty() }
            return if (trimmed != null) {
                "Back at $trimmed — End manual trip?" to
                    "You reconnected to $trimmed Wi-Fi. Your manual trip is still recording."
            } else {
                "Back on Saved Wi-Fi — End manual trip?" to
                    "You reconnected to a Saved Wi-Fi Place. Your manual trip is still recording."
            }
        }

        fun buildManualTripStopSuggestionCopy(
            context: Context,
            placeLabel: String?
        ): Pair<String, String> {
            val trimmed = placeLabel?.trim()?.takeIf { it.isNotEmpty() }
            return if (trimmed != null) {
                context.getString(R.string.notif_manual_trip_stop_title_named, trimmed) to
                    context.getString(R.string.notif_manual_trip_stop_body_named, trimmed)
            } else {
                context.getString(R.string.notif_manual_trip_stop_title_fallback) to
                    context.getString(R.string.notif_manual_trip_stop_body_fallback)
            }
        }

        fun isDeveloperModeEnabled(context: Context): Boolean =
            context.getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
                .getBoolean("developer_mode", false)

        fun isDemoDataEnabled(context: Context): Boolean =
            context.getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
                .getBoolean("demo_data_enabled", false)

        fun isDevCommandAuthorized(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
                isDeveloperModeEnabled(context)
    }
}
