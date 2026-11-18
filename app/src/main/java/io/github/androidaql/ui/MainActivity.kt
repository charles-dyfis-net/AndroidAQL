package io.github.androidaql.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.androidaql.R
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.engine.StepMovementEstimator
import io.github.androidaql.export.CsvExporter
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.util.TempUnitPreference
import io.github.androidaql.util.TemperatureUnitFormatter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var collectorService: AqlCollectorService? = null
    private var isBound = false
    private var activeTabIndex = 0
    private var selectedExcursionId: Long? = null
    private var lastSeenLatestExcursionId: Long? = null
    private var lastObservedActiveExcursionId: Long? = null
    private var isTripEditMode: Boolean = false

    // Optional custom 14-day ring buffer time window (when inspecting raw ring buffer before carving a trip)
    private var customWindowStartEpochSec: Long? = null
    private var customWindowEndEpochSec: Long? = null

    private var pendingScreenshotPreset: String? = null
    private var activeSegmentModalDialog: AlertDialog? = null
    private var activeDisclosureDialog: AlertDialog? = null

    private lateinit var topHeaderBar: LinearLayout
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var tabButtons: List<Button>
    private lateinit var tabContainers: List<View>

    // Tab 1: Live ("Now") Views
    private lateinit var contextPillView: TextView
    private lateinit var heroCardView: LinearLayout
    private lateinit var co2HeaderLabelView: TextView
    private lateinit var co2HeroValueView: TextView
    private lateinit var co2VentilationBadgeView: TextView
    private lateinit var secondaryMetricsView: TextView
    private lateinit var liveRecentHistoryView: DualPaneSlicerView
    private lateinit var wifiStatusView: TextView
    private lateinit var saveCurrentWifiBtn: Button
    private lateinit var manualTripToggleBtn: Button
    private lateinit var demoStreamBtn: Button

    // Tab 2: Trips & Slicer Views
    private lateinit var tripSelectorLabel: TextView
    private var pinTripBtn: TextView? = null
    private var manageTripBtn: TextView? = null
    private lateinit var modeToggleBtn: Button
    private lateinit var dualPaneSlicerView: DualPaneSlicerView
    private lateinit var dividerNudgeBar: LinearLayout
    private lateinit var cursorModeBtn: Button
    private lateinit var editHelperView: TextView
    private var tripRetentionBanner: TextView? = null
    private var slicesContainer: LinearLayout? = null
    private var renderedSliceCards: List<Pair<SessionSliceRecord, LinearLayout>> = emptyList()

    // Tab 3: Hardware Bring-Up Views
    private lateinit var hwPacketMetaView: TextView
    private lateinit var hwBleModeStatusView: TextView
    private lateinit var hwStrictFilterCheck: CheckBox
    private lateinit var hwGattStatusView: TextView
    private lateinit var hwRawHexLogView: TextView

    private val shortTimeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneId.systemDefault())
    private val dateShortTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        collectorService?.startBleScan()
        collectorService?.refreshWifiAndPlaceState()
        collectorService?.refreshStepSensorRegistration()
    }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val svc = collectorService ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            val importedId = contentResolver.openInputStream(uri)?.use { input ->
                svc.importExcursionFromCsv(input)
            }
            if (importedId != null) {
                customWindowStartEpochSec = null
                customWindowEndEpochSec = null
                selectedExcursionId = importedId
                switchTab(1)
                refreshTripsTab()
                Toast.makeText(this, "Imported CSV trip for replay", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No valid AirSlice rows found in CSV", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "CSV import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceListener: (AqlCollectorService.LiveTelemetrySnapshot) -> Unit = { snap ->
        runOnUiThread {
            renderLiveSnapshot(snap)
            val activeChanged = snap.activeExcursionId != lastObservedActiveExcursionId
            lastObservedActiveExcursionId = snap.activeExcursionId
            if (activeChanged ||
                (activeTabIndex == 1 && snap.activeExcursionId != null && snap.activeExcursionId == selectedExcursionId)
            ) {
                refreshTripsTab()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AqlCollectorService.LocalBinder ?: return
            val svc = b.getService()
            collectorService = svc
            isBound = true
            lastObservedActiveExcursionId = svc.buildSnapshot().activeExcursionId
            svc.addListener(serviceListener)
            refreshLiveRecentGraph()
            refreshTripsTab()
            tryApplyPendingScreenshotPreset()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            collectorService?.removeListener(serviceListener)
            collectorService = null
            isBound = false
        }
    }

    private fun buildServiceStartIntent(sourceIntent: Intent?): Intent =
        Intent(this, AqlCollectorService::class.java).apply {
            if (sourceIntent?.action == AqlCollectorService.ACTION_DEV_COMMAND &&
                AqlCollectorService.isDevCommandAuthorized(this@MainActivity)
            ) {
                action = sourceIntent.action
                sourceIntent.extras?.let { putExtras(it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            activeTabIndex = savedInstanceState.getInt("activeTabIndex", 0)
            pendingScreenshotPreset = savedInstanceState.getString("pendingScreenshotPreset")
            val savedExc = savedInstanceState.getLong("selectedExcursionId", -1L)
            if (savedExc > 0L) selectedExcursionId = savedExc
            val savedLatestExc = savedInstanceState.getLong("lastSeenLatestExcursionId", -1L)
            if (savedLatestExc > 0L) lastSeenLatestExcursionId = savedLatestExc
            isTripEditMode = savedInstanceState.getBoolean("isTripEditMode", false)
            val cwStart = savedInstanceState.getLong("customWindowStartEpochSec", -1L)
            val cwEnd = savedInstanceState.getLong("customWindowEndEpochSec", -1L)
            if (cwStart > 0L && cwEnd > cwStart) {
                customWindowStartEpochSec = cwStart
                customWindowEndEpochSec = cwEnd
            }
        } else {
            captureScreenshotPresetFromIntent(intent)
        }

        setContentView(buildRootLayout())
        requestRuntimePermissionsIfNeeded()

        ContextCompat.startForegroundService(this, buildServiceStartIntent(intent))
        bindService(Intent(this, AqlCollectorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            setMovementDisclosureChoice(acceptedLocationAndActivity = true)
        }
        collectorService?.startBleScan()
        collectorService?.refreshWifiAndPlaceState()
        collectorService?.refreshStepSensorRegistration()
        refreshLiveRecentGraph()
        refreshTripsTab()
        tryApplyPendingScreenshotPreset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == AqlCollectorService.ACTION_DEV_COMMAND &&
            AqlCollectorService.isDevCommandAuthorized(this)
        ) {
            ContextCompat.startForegroundService(this, buildServiceStartIntent(intent))
            captureScreenshotPresetFromIntent(intent)
            if (::topHeaderBar.isInitialized) {
                topHeaderBar.post { tryApplyPendingScreenshotPreset() }
            } else {
                tryApplyPendingScreenshotPreset()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("activeTabIndex", activeTabIndex)
        pendingScreenshotPreset?.let { outState.putString("pendingScreenshotPreset", it) }
        outState.putLong("selectedExcursionId", selectedExcursionId ?: -1L)
        outState.putLong("lastSeenLatestExcursionId", lastSeenLatestExcursionId ?: -1L)
        outState.putBoolean("isTripEditMode", isTripEditMode)
        outState.putLong("customWindowStartEpochSec", customWindowStartEpochSec ?: -1L)
        outState.putLong("customWindowEndEpochSec", customWindowEndEpochSec ?: -1L)
    }

    override fun onDestroy() {
        activeSegmentModalDialog?.dismiss()
        activeSegmentModalDialog = null
        activeDisclosureDialog?.dismiss()
        activeDisclosureDialog = null
        if (isBound) {
            collectorService?.removeListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun captureScreenshotPresetFromIntent(sourceIntent: Intent?) {
        if (sourceIntent?.action == AqlCollectorService.ACTION_DEV_COMMAND &&
            AqlCollectorService.isDevCommandAuthorized(this)
        ) {
            sourceIntent.getStringExtra("screenshot_preset")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { preset ->
                    pendingScreenshotPreset = preset
                    setMovementDisclosureChoice(acceptedLocationAndActivity = false)
                    getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("screenshot_automation_active", true)
                        .apply()
                }
        }
    }

    private fun tryApplyPendingScreenshotPreset() {
        val preset = pendingScreenshotPreset ?: return
        val svc = collectorService ?: return
        if (!AqlCollectorService.isDevCommandAuthorized(this)) {
            pendingScreenshotPreset = null
            return
        }
        pendingScreenshotPreset = null
        activeSegmentModalDialog?.dismiss()
        activeSegmentModalDialog = null
        activeDisclosureDialog?.dismiss()
        activeDisclosureDialog = null

        fun prepareDemoExcursion(reseed: Boolean): Long {
            if (svc.metadataDb.getKnownPlaces().isEmpty()) {
                svc.metadataDb.createKnownPlaceWithWifi("Home", "ChicagoHome-5G", "AA:BB:CC:11:22:33")
            }
            val homePlaceId = svc.metadataDb.getKnownPlaces().first().id
            svc.setManualKnownPlaceOverride(homePlaceId)

            if (reseed) {
                for (exc in svc.metadataDb.getExcursions()) {
                    svc.detachActiveExcursionIfMatching(exc.id)
                    svc.metadataDb.deleteExcursion(exc.id)
                }
                return svc.seedDemoChicagoExcursion()
            }

            for (exc in svc.metadataDb.getExcursions()) {
                if (!exc.title.startsWith("Demo:")) {
                    svc.detachActiveExcursionIfMatching(exc.id)
                    svc.metadataDb.deleteExcursion(exc.id)
                }
            }
            return svc.metadataDb.getExcursions()
                .firstOrNull { it.title.startsWith("Demo:") }
                ?.id
                ?: svc.seedDemoChicagoExcursion()
        }

        fun selectDemoMode(reseed: Boolean, editMode: Boolean) {
            setDeveloperModeEnabled(false)
            TemperatureUnitFormatter.setPreference(this, TempUnitPreference.FAHRENHEIT)
            customWindowStartEpochSec = null
            customWindowEndEpochSec = null
            val demoId = prepareDemoExcursion(reseed)
            selectedExcursionId = demoId
            lastSeenLatestExcursionId = svc.metadataDb.getExcursions().firstOrNull()?.id ?: demoId
            isTripEditMode = editMode
            switchTab(1)
        }

        when (preset) {
            "seed_demo_view" -> {
                selectDemoMode(reseed = true, editMode = false)
                dualPaneSlicerView.selectedDividerIndex = -1
                val slots = dualPaneSlicerView.slots
                if (slots.isNotEmpty()) {
                    dualPaneSlicerView.cursorEpochSec = slots[slots.size / 3].bucketEpochSec
                }
                updateEditModeUi()
                updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                dualPaneSlicerView.invalidate()
                (tabContainers.getOrNull(1) as? ScrollView)?.apply { post { scrollTo(0, 0) } }
            }
            "demo_edit_divider" -> {
                selectDemoMode(reseed = false, editMode = true)
                val slices = dualPaneSlicerView.slices
                val divIdx = when {
                    slices.size >= 3 -> 1
                    slices.size >= 2 -> 0
                    else -> -1
                }
                dualPaneSlicerView.selectedDividerIndex = divIdx
                if (divIdx in slices.indices) {
                    dualPaneSlicerView.cursorEpochSec = slices[divIdx].endEpochSec
                }
                updateEditModeUi()
                updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                dualPaneSlicerView.invalidate()
                (tabContainers.getOrNull(1) as? ScrollView)?.apply { post { scrollTo(0, 0) } }
            }
            "demo_segment_modal" -> {
                selectDemoMode(reseed = false, editMode = false)
                dualPaneSlicerView.selectedDividerIndex = -1
                updateEditModeUi()
                val slices = dualPaneSlicerView.slices
                if (slices.isNotEmpty()) {
                    showSegmentEditModal(slices[0], slices.getOrNull(1), sliceIdx = 0)
                }
            }
            "demo_landscape_edit" -> {
                selectDemoMode(reseed = false, editMode = true)
                val slots = dualPaneSlicerView.slots
                dualPaneSlicerView.selectedDividerIndex = -1
                if (slots.isNotEmpty()) {
                    dualPaneSlicerView.cursorEpochSec = slots[slots.size / 2].bucketEpochSec
                }
                updateEditModeUi()
                updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                dualPaneSlicerView.invalidate()
            }
            "real_trip_overview" -> {
                svc.setManualKnownPlaceOverride(0)
                setDeveloperModeEnabled(false)
                customWindowStartEpochSec = null
                customWindowEndEpochSec = null
                val excursions = svc.metadataDb.getExcursions()
                val realExc = excursions.firstOrNull { !it.title.startsWith("Demo:") } ?: excursions.firstOrNull()
                if (realExc != null) {
                    selectedExcursionId = realExc.id
                    lastSeenLatestExcursionId = excursions.firstOrNull()?.id
                }
                isTripEditMode = false
                switchTab(1)
                dualPaneSlicerView.selectedDividerIndex = -1
                updateEditModeUi()
                (tabContainers.getOrNull(1) as? ScrollView)?.apply { post { scrollTo(0, 0) } }
            }
            "demo_now_dashboard" -> {
                setDeveloperModeEnabled(false)
                setDemoDataEnabled(false)
                TemperatureUnitFormatter.setPreference(this, TempUnitPreference.FAHRENHEIT)
                svc.seedDemoNowDashboard()
                switchTab(0)
                renderLiveSnapshot(svc.buildSnapshot())
                refreshLiveRecentGraph()
                val slots = liveRecentHistoryView.slots
                if (slots.isNotEmpty()) {
                    liveRecentHistoryView.cursorEpochSec = slots[slots.size / 2].bucketEpochSec
                    liveRecentHistoryView.invalidate()
                }
                (tabContainers.getOrNull(0) as? ScrollView)?.apply { post { scrollTo(0, 0) } }
            }
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun isDeveloperModeEnabled(): Boolean =
        AqlCollectorService.isDeveloperModeEnabled(this)

    private fun isDemoDataEnabled(): Boolean =
        AqlCollectorService.isDemoDataEnabled(this)

    private fun setDeveloperModeEnabled(enabled: Boolean) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("developer_mode", enabled)
            .apply()
        applyDeveloperModeVisibility()
    }

    private fun setDemoDataEnabled(enabled: Boolean) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("demo_data_enabled", enabled)
            .apply()
        if (!enabled) {
            collectorService?.stopDemoStream()
            collectorService?.setManualKnownPlaceOverride(0)
        }
        applyDeveloperModeVisibility()
    }

    private fun getDropoutGracePeriodSec(): Long =
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE).getLong("dropout_grace_period_sec", 45L)

    private fun setDropoutGracePeriodSec(sec: Long) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("dropout_grace_period_sec", sec)
            .apply()
        if (::liveRecentHistoryView.isInitialized) liveRecentHistoryView.dropoutGracePeriodSec = sec
        if (::dualPaneSlicerView.isInitialized) dualPaneSlicerView.dropoutGracePeriodSec = sec
    }

    private fun applyDeveloperModeVisibility() {
        val dev = isDeveloperModeEnabled()
        val demo = isDemoDataEnabled()
        if (::demoStreamBtn.isInitialized) {
            demoStreamBtn.visibility = if (demo) View.VISIBLE else View.GONE
        }
        if (::tabButtons.isInitialized && tabButtons.size >= 3) {
            tabButtons[0].text = if (dev) "1. Now" else "Now"
            tabButtons[1].text = if (dev) "2. Trips" else "Trips"
            tabButtons[2].text = "3. HW Test"
            tabButtons[2].visibility = if (dev) View.VISIBLE else View.GONE
            if (!dev && activeTabIndex == 2) {
                switchTab(0)
            }
        }
    }

    private fun hasCompletedMovementDisclosure(): Boolean {
        val prefs = getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("movement_disclosure_version", 0) >= 2
    }

    private fun isBleOnlyModeChosen(): Boolean =
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .getBoolean("ble_only_mode_chosen", false)

    private fun setMovementDisclosureChoice(acceptedLocationAndActivity: Boolean) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("location_disclosure_accepted", acceptedLocationAndActivity)
            .putBoolean("ble_only_mode_chosen", !acceptedLocationAndActivity)
            .putInt("movement_disclosure_version", 2)
            .apply()
    }

    private fun requestRuntimePermissionsIfNeeded(forcePrompt: Boolean = false) {
        val screenshotActive = getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .getBoolean("screenshot_automation_active", false)
        if (!forcePrompt && (pendingScreenshotPreset != null || screenshotActive)) return
        val allPerms = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val missing = allPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        val missingLocationOrActivity = missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
            missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            missing.contains(Manifest.permission.ACTIVITY_RECOGNITION)

        if (!forcePrompt && hasCompletedMovementDisclosure() && isBleOnlyModeChosen()) {
            val bleOnlyPerms = missing.filter {
                it != Manifest.permission.ACCESS_FINE_LOCATION &&
                    it != Manifest.permission.ACCESS_COARSE_LOCATION &&
                    it != Manifest.permission.ACTIVITY_RECOGNITION
            }
            if (bleOnlyPerms.isNotEmpty()) {
                permissionLauncher.launch(bleOnlyPerms.toTypedArray())
            }
            return
        }

        if (missingLocationOrActivity && (forcePrompt || !hasCompletedMovementDisclosure())) {
            showProminentLocationDisclosureDialog(
                onAccept = {
                    setMovementDisclosureChoice(acceptedLocationAndActivity = true)
                    permissionLauncher.launch(missing.toTypedArray())
                },
                onDeclineLocation = {
                    setMovementDisclosureChoice(acceptedLocationAndActivity = false)
                    val bleOnlyPerms = missing.filter {
                        it != Manifest.permission.ACCESS_FINE_LOCATION &&
                            it != Manifest.permission.ACCESS_COARSE_LOCATION &&
                            it != Manifest.permission.ACTIVITY_RECOGNITION
                    }
                    if (bleOnlyPerms.isNotEmpty()) {
                        permissionLauncher.launch(bleOnlyPerms.toTypedArray())
                    }
                }
            )
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showProminentLocationDisclosureDialog(
        onAccept: (() -> Unit)? = null,
        onDeclineLocation: (() -> Unit)? = null
    ) {
        val message = buildString {
            append("AirSlice accesses location, Wi-Fi, and physical activity step data on-device for Trip Mode and Saved Places (including in the background or with the screen off), and immediately discards almost all of it—keeping only movement speed (m/s) and GPS accuracy (m):\n\n")
            append("• Saved Wi-Fi Places: Checks whether your connected Wi-Fi matches a Saved Place (Location permission required) so GPS and step sensing stay off at saved locations like your home or office, where movement tracking isn't needed.\n\n")
            append("• Trip Movement (GPS + Steps): Outside Saved Places while your monitor is nearby, converts GPS fixes and hardware step cadence into scalar speed (m/s) and accuracy (m) in memory, immediately discarding raw coordinates and step counts.\n\n")
            append("• 100% Local & Private: Raw GPS coordinates, unconfigured Wi-Fi names, and step counts are never saved to disk or sent to any server; recorded summaries leave your phone only when you explicitly share a CSV or diagnostic log.")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("📍 Location, Activity & Privacy Disclosure")
            .setMessage(message)
            .setCancelable(onAccept == null)

        if (onAccept != null) {
            builder.setPositiveButton("Continue") { _, _ -> onAccept() }
            builder.setNegativeButton("Not Now (BLE Only)") { _, _ -> onDeclineLocation?.invoke() }
        } else {
            builder.setPositiveButton("Close", null)
        }
        activeDisclosureDialog?.dismiss()
        activeDisclosureDialog = builder.show()
    }

    private fun buildRootLayout(): View {
        val landscape = isLandscape()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Respect Android 15+ mandatory edge-to-edge system bars & camera cutout insets
            setOnApplyWindowInsetsListener { v, windowInsets ->
                val sysBars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
                windowInsets
            }
        }
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        // Top App Header (hidden in Landscape when on Trips tab to maximize 3-pane vertical chart area)
        topHeaderBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "⚙️ Settings"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { showSettingsModal() }
        }
        topHeaderBar.addView(titleView)
        topHeaderBar.addView(settingsBtn)
        root.addView(topHeaderBar)

        // Content Frame
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tab1 = buildLiveTab()
        val tab2 = buildTripsSlicerTab(landscape)
        val tab3 = buildHardwareBringUpTab()
        tabContainers = listOf(tab1, tab2, tab3)
        for (v in tabContainers) {
            contentHolder.addView(v)
        }
        root.addView(contentHolder)

        // Bottom Navigation Bar (3 Tabs: Now, Trips, HW Test)
        bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val tabTitles = listOf("1. Now", "2. Trips", "3. HW Test")
        val btns = mutableListOf<Button>()
        for ((idx, label) in tabTitles.withIndex()) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                setOnClickListener { switchTab(idx) }
            }
            btns.add(b)
            bottomNavBar.addView(b)
        }
        tabButtons = btns
        root.addView(bottomNavBar)

        applyDeveloperModeVisibility()
        switchTab(activeTabIndex)
        return root
    }

    private fun switchTab(index: Int) {
        val maxIdx = if (isDeveloperModeEnabled()) tabContainers.size - 1 else (tabContainers.size - 2).coerceAtLeast(0)
        activeTabIndex = index.coerceIn(0, maxIdx)
        for ((i, container) in tabContainers.withIndex()) {
            container.visibility = if (i == activeTabIndex) View.VISIBLE else View.GONE
        }
        for ((i, btn) in tabButtons.withIndex()) {
            if (i == activeTabIndex) {
                btn.setBackgroundColor(Color.parseColor("#0284C7"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundColor(Color.parseColor("#1E293B"))
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        // In Landscape on the Trips tab, hide the top header bar so all 3 panes fit comfortably with zero scrolling
        topHeaderBar.visibility = if (isLandscape() && activeTabIndex == 1) View.GONE else View.VISIBLE

        if (activeTabIndex == 0) refreshLiveRecentGraph()
        if (activeTabIndex == 1) refreshTripsTab()
    }

    // =========================================================================
    // TAB 1: LIVE ("NOW") — CURRENT STATS + RECENT-HISTORY 3-PANE GRAPH STACK
    // =========================================================================
    private fun buildLiveTab(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }

        contextPillView = TextView(this).apply {
            text = "📡 Waiting for Qingping Monitor (GPS Off)"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect("#1E293B", dp(10))
        }
        col.addView(contextPillView)

        // Hero Air Quality Card
        heroCardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect("#1E293B", dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        co2HeaderLabelView = TextView(this).apply {
            text = "CARBON DIOXIDE (CO₂)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
        co2HeroValueView = TextView(this).apply {
            text = "— ppm"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTypeface(typeface, Typeface.BOLD)
        }
        co2VentilationBadgeView = TextView(this).apply {
            text = "Hold down the top touchbar on your Qingping Air Monitor Lite until the Wi-Fi icon displays to begin."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        secondaryMetricsView = TextView(this).apply {
            text = "PM2.5: — µg/m³  •  PM10: — µg/m³\nTemp: —  •  Humidity: — %  •  Battery: — %"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, 0)
        }

        heroCardView.addView(co2HeaderLabelView)
        heroCardView.addView(co2HeroValueView)
        heroCardView.addView(co2VentilationBadgeView)
        heroCardView.addView(secondaryMetricsView)
        col.addView(heroCardView)

        // Recent-History 3-Pane Graph Stack (Last 15 Minutes)
        val recentGraphHeader = TextView(this).apply {
            text = "Recent History (Last 15 Minutes • Touch to Inspect)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(12), dp(2), dp(4))
        }
        col.addView(recentGraphHeader)

        liveRecentHistoryView = DualPaneSlicerView(this).apply {
            isEditMode = false
            desiredHeightDp = 265
            dropoutGracePeriodSec = getDropoutGracePeriodSec()
            emptyStateMessage = "No recent samples in the last 15 minutes."
        }
        col.addView(liveRecentHistoryView)

        // Wi-Fi Context & Demo Stream Card
        val wifiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect("#1E293B", dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        wifiStatusView = TextView(this).apply {
            text = "Connected Wi-Fi: Checking..."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        saveCurrentWifiBtn = Button(this).apply {
            text = "+ Save Current Wi-Fi Place (Keep GPS Off Here)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setOnClickListener { promptAddCurrentWifiToSavedPlaces() }
        }
        manualTripToggleBtn = Button(this).apply {
            text = "🚶 Start Trip Manually"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setOnClickListener {
                val svc = collectorService ?: return@setOnClickListener
                val snap = svc.buildSnapshot()
                val isTripOpen = snap.activeExcursionId != null ||
                    snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ||
                    snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                if (isTripOpen) {
                    svc.endManualExcursion()
                    Toast.makeText(this@MainActivity, "⏹️ Trip ended", Toast.LENGTH_SHORT).show()
                } else {
                    svc.startManualExcursion()
                    Toast.makeText(this@MainActivity, "🚶 Trip started", Toast.LENGTH_SHORT).show()
                }
                refreshLiveRecentGraph()
                refreshTripsTab()
            }
        }
        demoStreamBtn = Button(this).apply {
            text = "🎭 Toggle Live Demo Replay Stream (No Hardware Needed)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            visibility = if (isDemoDataEnabled()) View.VISIBLE else View.GONE
            setOnClickListener {
                val running = collectorService?.toggleDemoStream() ?: false
                val msg = if (running) "Started Live Demo CSV Replay (every 3s)" else "Stopped Live Demo Replay"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        wifiCard.addView(wifiStatusView)
        wifiCard.addView(saveCurrentWifiBtn)
        wifiCard.addView(manualTripToggleBtn)
        wifiCard.addView(demoStreamBtn)
        col.addView(wifiCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: TRIPS & 3-PANE SLICER (PORTRAIT VS. LANDSCAPE ZERO-SCROLL)
    // =========================================================================
    private fun buildTripsSlicerTab(landscape: Boolean): View {
        // Common controls
        tripSelectorLabel = TextView(this).apply {
            text = "Trip"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (landscape) 13.5f else 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showManageTripDialog() }
        }

        modeToggleBtn = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                isTripEditMode = !isTripEditMode
                dualPaneSlicerView.selectedDividerIndex = -1
                updateEditModeUi()
            }
        }

        dualPaneSlicerView = DualPaneSlicerView(this).apply {
            isEditMode = isTripEditMode
            desiredHeightDp = 365
            dropoutGracePeriodSec = getDropoutGracePeriodSec()
            onCursorChanged = { ts ->
                updateSliceCardHighlights(ts)
                updateEditHelperText()
            }
            onSharedDividerMoved = { leftId, rightId, newTs ->
                val svc = collectorService
                svc?.metadataDb?.moveSharedDivider(leftId, rightId, newTs, svc.ringStore)
                refreshTripsTab()
            }
            onDividerSelected = { _ ->
                updateEditModeUi()
                refreshTripsTab()
            }
        }

        // Split & Fine Nudge Toolbar (Shown only when isTripEditMode == true)
        dividerNudgeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (isTripEditMode) View.VISIBLE else View.GONE
        }
        editHelperView = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(2), dp(2), dp(2), dp(4))
            visibility = if (isTripEditMode) View.VISIBLE else View.GONE
            setOnClickListener {
                if (dualPaneSlicerView.selectedDividerIndex >= 0) {
                    switchToSplitCursorMode()
                }
            }
        }
        fun makeEditBarButton(label: String, portraitWeight: Float, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                maxLines = 1
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(
                    if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (landscape) 0f else portraitWeight
                )
                setOnClickListener { onClick() }
            }

        cursorModeBtn = makeEditBarButton("🎯 Cursor", 1.2f) { switchToSplitCursorMode() }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        dividerNudgeBar.addView(makeEditBarButton("✂️ Split", 1.2f) { splitAtCurrentCursor() })
        dividerNudgeBar.addView(cursorModeBtn)
        dividerNudgeBar.addView(makeEditBarButton("-1m", 0.95f) { nudgeSelectedDivider(-60L) })
        dividerNudgeBar.addView(makeEditBarButton("-15s", 1f) { nudgeSelectedDivider(-15L) })
        dividerNudgeBar.addView(makeEditBarButton("+15s", 1f) { nudgeSelectedDivider(+15L) })
        dividerNudgeBar.addView(makeEditBarButton("+1m", 0.95f) { nudgeSelectedDivider(+60L) })

        if (landscape) {
            // LANDSCAPE MODE: Fit all 3 panes on screen at once with ZERO scrolling!
            // Segment list and 14d ring-buffer carving controls are omitted in Landscape.
            val landRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val compactTopBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val switchTripBtn = Button(this).apply {
                text = "Switch Trip"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener { showTripPickerDialog() }
            }
            compactTopBar.addView(tripSelectorLabel)
            compactTopBar.addView(switchTripBtn)
            compactTopBar.addView(modeToggleBtn)
            compactTopBar.addView(dividerNudgeBar)
            landRoot.addView(compactTopBar)

            dualPaneSlicerView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            landRoot.addView(dualPaneSlicerView)
            slicesContainer = null
            pinTripBtn = null
            manageTripBtn = null
            tripRetentionBanner = null
            updateEditModeUi()
            return landRoot
        } else {
            // PORTRAIT MODE: Offer 14d ring-buffer time-window selector, View/Edit mode toggle,
            // 3-Pane Graph Stack, and clean Segment Cards with Edit Modal.
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(24))
            }

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val chooseTripBtn = Button(this).apply {
                text = "Trips"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { showTripPickerDialog() }
            }
            val carveWindowBtn = Button(this).apply {
                text = "🗓️ 14d Window"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { show14dRingWindowSelectorDialog() }
            }
            actionRow.addView(chooseTripBtn)
            actionRow.addView(carveWindowBtn)
            actionRow.addView(modeToggleBtn)
            col.addView(actionRow)

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val pinBtn = TextView(this).apply {
                text = "⏳ 14d • 📌 Pin"
                maxLines = 1
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(Color.parseColor("#FDE68A"))
                background = roundedRect("#1E293B", dp(6), strokeHex = "#D97706", strokeWidthPx = dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                setOnClickListener { toggleSelectedTripPin() }
            }
            val manageBtn = TextView(this).apply {
                text = "✏️"
                maxLines = 1
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedRect("#1E293B", dp(6), strokeHex = "#334155", strokeWidthPx = dp(1))
                setOnClickListener { showManageTripDialog() }
            }
            pinTripBtn = pinBtn
            manageTripBtn = manageBtn
            titleRow.addView(tripSelectorLabel)
            titleRow.addView(pinBtn)
            titleRow.addView(manageBtn)
            col.addView(titleRow)

            col.addView(dualPaneSlicerView)
            col.addView(dividerNudgeBar)
            col.addView(editHelperView)

            val retentionBanner = TextView(this).apply {
                setTextColor(Color.parseColor("#FDE68A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedRect("#1E293B", dp(8), strokeHex = "#F59E0B", strokeWidthPx = dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                visibility = View.GONE
                setOnClickListener { toggleSelectedTripPin(forcePin = true) }
            }
            tripRetentionBanner = retentionBanner
            col.addView(retentionBanner)

            val segList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            slicesContainer = segList
            col.addView(segList)

            scroll.addView(col)
            updateEditModeUi()
            return scroll
        }
    }

    private fun updateEditModeUi() {
        dualPaneSlicerView.isEditMode = isTripEditMode
        dividerNudgeBar.visibility = if (isTripEditMode) View.VISIBLE else View.GONE
        if (::cursorModeBtn.isInitialized) {
            val dividerActive = isTripEditMode &&
                dualPaneSlicerView.selectedDividerIndex in 0 until (dualPaneSlicerView.slices.size - 1)
            cursorModeBtn.visibility = if (dividerActive) View.VISIBLE else View.GONE
        }
        if (::editHelperView.isInitialized) {
            editHelperView.visibility = if (isTripEditMode) View.VISIBLE else View.GONE
            updateEditHelperText()
        }
        modeToggleBtn.text = if (isTripEditMode) "👁️ Done Editing" else "✏️ Edit Trip"
        modeToggleBtn.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isTripEditMode) "#D97706" else "#334155")
        )
        modeToggleBtn.setTextColor(Color.WHITE)

        val svc = collectorService
        val excId = selectedExcursionId
        if (svc != null && excId != null && customWindowStartEpochSec == null) {
            val currentExc = svc.metadataDb.getExcursions().firstOrNull { it.id == excId }
            updateTripRetentionBanner(currentExc, dualPaneSlicerView.slices)
        } else {
            tripRetentionBanner?.visibility = View.GONE
        }
    }

    private fun toggleSelectedTripPin(forcePin: Boolean? = null) {
        val svc = collectorService ?: return
        if (customWindowStartEpochSec != null) return
        val excId = selectedExcursionId ?: return
        val exc = svc.metadataDb.getExcursions().firstOrNull { it.id == excId } ?: return
        val newPinned = forcePin ?: !exc.isPinned
        if (!newPinned && svc.metadataDb.isExcursionOutsideRingWindow(exc, svc.ringStore)) {
            confirmDeleteTrip(exc, initiatedByUnpin = true)
            return
        }
        svc.metadataDb.setExcursionPinned(exc.id, newPinned, svc.ringStore)
        refreshTripsTab()
        Toast.makeText(
            this,
            if (newPinned) "📌 Trip pinned forever" else "Trip set to 14-day ring retention",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateTripRetentionBanner(exc: ExcursionRecord?, slices: List<SessionSliceRecord>) {
        val banner = tripRetentionBanner ?: return
        if (exc == null || exc.isPinned || (!isTripEditMode && slices.size <= 1)) {
            banner.visibility = View.GONE
            return
        }
        banner.text = "⏳ Auto-recorded trip (expires in 14d). Tap here or '⏳ 14d • 📌 Pin' above to retain this trip forever."
        banner.visibility = View.VISIBLE
    }

    private fun updateEditHelperText() {
        if (!::editHelperView.isInitialized || !isTripEditMode) return
        val slices = dualPaneSlicerView.slices
        val divIdx = dualPaneSlicerView.selectedDividerIndex
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val cursorTime = if (cursorTs > 0L) shortTimeFmt.format(Instant.ofEpochSecond(cursorTs)) else "tap chart"
        if (divIdx in 0 until slices.size - 1) {
            val boundaryTime = shortTimeFmt.format(Instant.ofEpochSecond(slices[divIdx].endEpochSec))
            editHelperView.setTextColor(Color.parseColor("#F97316"))
            editHelperView.text =
                "🟠 Divider ⇔ ${divIdx + 1}|${divIdx + 2} ($boundaryTime): Drag ⇔ or tap ±15s/±1m to move boundary. Tap 🎯 Cursor (or tap chart/banner) to place a new split."
        } else {
            editHelperView.setTextColor(Color.parseColor("#38BDF8"))
            editHelperView.text = if (slices.size <= 1) {
                "🎯 Split Cursor ($cursorTime): Tap/drag chart or ±15s/±1m to aim, then tap ✂️ Split."
            } else {
                "🎯 Split Cursor ($cursorTime): Aim cursor & tap ✂️ Split to add a boundary, or tap a ⇔ pill to move an existing boundary."
            }
        }
    }

    private fun switchToSplitCursorMode() {
        val slices = dualPaneSlicerView.slices
        val prevDivIdx = dualPaneSlicerView.selectedDividerIndex
        dualPaneSlicerView.selectedDividerIndex = -1
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val insideSlice = slices.any { cursorTs > it.startEpochSec && cursorTs < it.endEpochSec }
        if (!insideSlice && slices.isNotEmpty()) {
            val left = slices.getOrNull(prevDivIdx)
            val right = slices.getOrNull(prevDivIdx + 1)
            val preferred = when {
                left != null && right != null ->
                    if ((left.endEpochSec - left.startEpochSec) >= (right.endEpochSec - right.startEpochSec)) left else right
                left != null -> left
                right != null -> right
                else -> slices.first()
            }
            val mid = (((preferred.startEpochSec + preferred.endEpochSec) / 2L) / 15L) * 15L
            val candidate = mid.coerceIn(preferred.startEpochSec, preferred.endEpochSec)
            if (candidate > preferred.startEpochSec && candidate < preferred.endEpochSec) {
                dualPaneSlicerView.cursorEpochSec = candidate
            } else if (preferred.startEpochSec + 15L < preferred.endEpochSec) {
                dualPaneSlicerView.cursorEpochSec = preferred.startEpochSec + 15L
            }
        }
        updateEditModeUi()
        updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
        dualPaneSlicerView.invalidate()
    }

    // =========================================================================
    // TAB 3: HARDWARE BRING-UP & PERSISTENT DIAGNOSTIC LOGGING
    // =========================================================================
    private fun buildHardwareBringUpTab(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }

        val header = TextView(this).apply {
            text = "CGDN1 Hardware Bring-Up & Diagnostic Logger"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }
        col.addView(header)

        val traceInfoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect("#1E293B", dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val tracePathTv = TextView(this).apply {
            text = "Persistent JSONL Trace (analyze via ./scripts/analyze-hw-trace --pull):\n" +
                "adb exec-out run-as net.dyfis.androidaql cat files/hw_bringup_trace.jsonl"
            setTextColor(Color.parseColor("#A7F3D0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        val shareTraceBtn = Button(this).apply {
            text = "📤 Share / Export HW Bring-Up JSONL Trace"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { shareHardwareDiagnosticTrace() }
        }
        traceInfoCard.addView(tracePathTv)
        traceInfoCard.addView(shareTraceBtn)
        col.addView(traceInfoCard)

        // Developer Keep-Alive Hypothesis & Trace Experiment Controls
        val expCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect("#1E293B", dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val expTitle = TextView(this).apply {
            text = "🧪 Keep-Alive Hypothesis & Trace Experiment Controls"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        expCard.addView(expTitle)

        val strategies = AqlCollectorService.KeepAlivePayloadStrategy.entries
        val strategySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                strategies.map { "${it.id}: ${it.shortLabel}" }
            )
            val currentStrat = collectorService?.devConfig?.strategy ?: AqlCollectorService.KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP
            setSelection(strategies.indexOf(currentStrat).coerceAtLeast(0))
        }
        expCard.addView(strategySpinner)

        val intervalOptionsSec = listOf(120, 210, 300, 480)
        val intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                intervalOptionsSec.map { "Keep-Alive Interval: ${it}s (${String.format(Locale.US, "%.1fm", it / 60.0)})" }
            )
            val currentInt = collectorService?.devConfig?.intervalSec ?: 210
            setSelection(intervalOptionsSec.indexOf(currentInt).let { if (it >= 0) it else 1 })
        }
        expCard.addView(intervalSpinner)

        val dedupCheck = CheckBox(this).apply {
            text = "Compact Trace: Deduplicate identical 350ms 0xFDCD frames (15s heartbeat)"
            isChecked = collectorService?.devConfig?.deduplicateFdcdTrace ?: true
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        expCard.addView(dedupCheck)

        val applyExpBtn = Button(this).apply {
            text = "✅ Apply Keep-Alive Config (Logs DEV_CONFIG_CHANGED)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                val chosenStrat = strategies[strategySpinner.selectedItemPosition]
                val chosenInt = intervalOptionsSec[intervalSpinner.selectedItemPosition]
                collectorService?.updateDevKeepAliveConfig(
                    strategy = chosenStrat,
                    intervalSec = chosenInt,
                    deduplicateFdcdTrace = dedupCheck.isChecked,
                    reason = "hw_tab_apply"
                )
                Toast.makeText(
                    this@MainActivity,
                    "Applied ${chosenStrat.id} (${chosenInt}s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        expCard.addView(applyExpBtn)

        val pokeNowBtn = Button(this).apply {
            text = "⚡ Poke Now"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                collectorService?.triggerImmediateKeepAlivePoke("ui_poke_now")
            }
        }
        expCard.addView(pokeNowBtn)
        col.addView(expCard)

        hwPacketMetaView = TextView(this).apply {
            text = "Waiting for BLE advertisement frame..."
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect("#1E293B", dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        col.addView(hwPacketMetaView)

        hwStrictFilterCheck = CheckBox(this).apply {
            text = "Strict 0xFDCD Filter (Uncheck to log all nearby BLE advertisements)"
            isChecked = true
            setTextColor(Color.parseColor("#E2E8F0"))
            setOnCheckedChangeListener { _, checked ->
                collectorService?.setStrictFdcdScanFilter(checked)
            }
        }
        col.addView(hwStrictFilterCheck)

        hwBleModeStatusView = TextView(this).apply {
            text = "Excursion BLE Mode: PASSIVE_ONLY"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(hwBleModeStatusView)

        for (mode in BleCollectionMode.entries) {
            val btn = Button(this).apply {
                text = "Mode: ${mode.displayName}"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener {
                    collectorService?.setExcursionBleMode(mode)
                    Toast.makeText(this@MainActivity, "Excursion BLE mode: ${mode.name}", Toast.LENGTH_SHORT).show()
                }
            }
            col.addView(btn)
        }

        val gattInspectBtn = Button(this).apply {
            text = "🔍 Connect GATT & Enumerate 22210000-... Services"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { collectorService?.triggerManualGattInspection() }
        }
        val gattDisconnectBtn = Button(this).apply {
            text = "Disconnect Active GATT Session"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { collectorService?.disconnectGatt() }
        }
        col.addView(gattInspectBtn)
        col.addView(gattDisconnectBtn)

        hwGattStatusView = TextView(this).apply {
            text = "GATT Status: Disconnected"
            setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect("#1E293B", dp(8))
        }
        col.addView(hwGattStatusView)

        val rawLogTitle = TextView(this).apply {
            text = "Live 0xFDCD TLV & GATT Frame Log (Newest First):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        }
        col.addView(rawLogTitle)

        hwRawHexLogView = TextView(this).apply {
            text = "(No packets received yet)"
            setTextColor(Color.parseColor("#A7F3D0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedRect("#090D16", dp(8))
        }
        col.addView(hwRawHexLogView)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // RENDER & INTERACTION LOGIC
    // =========================================================================
    private fun renderLiveSnapshot(snap: AqlCollectorService.LiveTelemetrySnapshot) {
        val useF = TemperatureUnitFormatter.useFahrenheit(this)

        val bleShortLabel = when {
            snap.lastFrame?.isBtHomeV2 == true -> "BTHome v2 (Passive)"
            snap.lastFrame?.isProvisionedNormalMode == true -> "Provisioned 0x88 (Passive)"
            else -> when (snap.effectiveBleMode) {
                BleCollectionMode.PERIODIC_GATT_KEEPALIVE -> snap.devConfig.strategy.effectiveStatusBadge(snap.collectorState)
                BleCollectionMode.PASSIVE_ONLY -> "Passive"
            }
        }
        contextPillView.text = when (snap.collectorState) {
            ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP ->
                if (snap.matchedKnownPlace != null) {
                    "🏠 At ${snap.matchedKnownPlace.label} • GPS Off • BLE: $bleShortLabel"
                } else {
                    "📡 Monitoring • GPS Off • BLE: $bleShortLabel"
                }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE -> {
                val movementBadge = when {
                    snap.isGpsActive && snap.isStepSensorActive -> "GPS + Steps Active"
                    snap.isGpsActive -> "GPS Active"
                    snap.isStepSensorActive -> "Steps Active • GPS Off"
                    else -> "GPS Off"
                }
                "🚶 On a Trip • $movementBadge • BLE: $bleShortLabel"
            }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED ->
                "⏸️ Trip Paused (>90s BLE dropout; GPS paused)"
            ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ->
                "📡 Monitor Out of Range • GPS Off"
        }

        if (snap.lastFrame != null) {
            val frameAgeSec = ((System.currentTimeMillis() - snap.lastFrame.timestampMs) / 1000L).coerceAtLeast(0L)
            val isStale = frameAgeSec > 45L ||
                snap.collectorState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ||
                snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED

            val hasValidCo2 = snap.mergedCo2Ppm > 0
            val co2ValueStr = if (hasValidCo2) "${snap.mergedCo2Ppm} ppm" else "— ppm"

            if (isStale) {
                heroCardView.alpha = 0.74f
                heroCardView.background = roundedRect("#172033", dp(14))
                co2HeaderLabelView.text = "⚠️ OUT OF DATE • COLLECTED ${formatElapsedAge(frameAgeSec).uppercase(Locale.US)} AGO"
                co2HeaderLabelView.setTextColor(Color.parseColor("#FBBF24"))
                co2HeroValueView.text = co2ValueStr
                co2HeroValueView.setTextColor(Color.parseColor("#94A3B8"))
                val collectedAt = shortTimeFmt.format(Instant.ofEpochMilli(snap.lastFrame.timestampMs))
                co2VentilationBadgeView.text = "Last reading at $collectedAt (${formatElapsedAge(frameAgeSec)} ago). Hold down the monitor's top touchbar until the Wi-Fi icon flashes if asleep."
                co2VentilationBadgeView.setTextColor(Color.parseColor("#FDE68A"))
                secondaryMetricsView.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                heroCardView.alpha = 1.0f
                heroCardView.background = roundedRect("#1E293B", dp(14))
                val liveAgeStr = if (frameAgeSec <= 3L) "just now" else "${frameAgeSec}s ago"
                co2HeaderLabelView.text = "CARBON DIOXIDE (CO₂) • LIVE ($liveAgeStr)"
                co2HeaderLabelView.setTextColor(Color.parseColor("#94A3B8"))
                co2HeroValueView.text = co2ValueStr
                co2VentilationBadgeView.setTextColor(Color.parseColor("#CBD5E1"))
                secondaryMetricsView.setTextColor(Color.parseColor("#E2E8F0"))
                when {
                    !hasValidCo2 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#94A3B8"))
                        co2VentilationBadgeView.text = "CO₂ sensor warming up…"
                    }
                    snap.mergedCo2Ppm < 800 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#34D399"))
                        co2VentilationBadgeView.text = "Good Ventilation (< 800 ppm)"
                    }
                    snap.mergedCo2Ppm < 1200 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#FBBF24"))
                        co2VentilationBadgeView.text = "Moderate / Stuffy Air (800–1200 ppm)"
                    }
                    else -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#F87171"))
                        co2VentilationBadgeView.text = "Poor Ventilation (≥ 1200 ppm)"
                    }
                }
            }
            val batStr = if (snap.mergedBatteryPct in 0..100) "${snap.mergedBatteryPct}%" else "External Power / —"
            val spdStr = when {
                snap.currentSpeedMps != null && snap.currentSpeedSource == StepMovementEstimator.SpeedSource.STEP_CADENCE -> {
                    val accSuffix = when {
                        snap.currentGpsAccuracyM != null -> String.format(Locale.US, "Steps • GPS ±%.0fm", snap.currentGpsAccuracyM)
                        !snap.isGpsActive -> "Steps • GPS Off"
                        else -> "Steps"
                    }
                    String.format(Locale.US, "%.1f m/s (%s)", snap.currentSpeedMps, accSuffix)
                }
                snap.currentSpeedMps != null ->
                    String.format(Locale.US, "%.1f m/s (±%.0fm)", snap.currentSpeedMps, snap.currentGpsAccuracyM ?: 0f)
                snap.isStepSensorActive && !snap.isGpsActive -> "Steps Standby (GPS Off)"
                else -> "Off"
            }
            val tempStr = TemperatureUnitFormatter.format(snap.mergedTempC, useF, decimals = 1)
            secondaryMetricsView.text = String.format(
                Locale.US,
                "PM2.5: %d µg/m³  •  PM10: %d µg/m³\nTemp: %s  •  Humidity: %.1f%%  •  Battery: %s\nMovement: %s",
                snap.mergedPm25Ugm3,
                snap.mergedPm10Ugm3,
                tempStr,
                snap.mergedHumidityPct,
                batStr,
                spdStr
            )
        }

        val ssidStr = snap.connectedWifiSsid ?: "(Not connected / Location permission needed)"
        val placeMatchStr = snap.matchedKnownPlace?.let { "Saved Place: ${it.label} (GPS Off)" }
            ?: when {
                !snap.hasConfiguredKnownPlaces -> "No Saved Places (Save Wi-Fi for Auto-Trips)"
                snap.connectedWifiSsid != null -> "Unrecognized Wi-Fi (Trip Mode if monitor active)"
                else -> "No Wi-Fi Place Matched"
            }
        wifiStatusView.text = "Wi-Fi: $ssidStr  •  $placeMatchStr"
        saveCurrentWifiBtn.visibility = if (!snap.connectedWifiSsid.isNullOrBlank() && snap.matchedKnownPlace == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val isTripOpen = snap.activeExcursionId != null ||
            snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ||
            snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
        manualTripToggleBtn.text = if (isTripOpen) {
            "⏹️ End Current Trip"
        } else {
            "🚶 Start Trip Manually"
        }

        refreshLiveRecentGraph()

        val f = snap.lastFrame
        if (f != null) {
            val dtStr = snap.interPacketDeltaMs?.let { String.format(Locale.US, "%.2fs", it / 1000.0) } ?: "first"
            hwPacketMetaView.text =
                "Device: ${f.productName} [0x${Integer.toHexString(f.productId)}]\n" +
                    "MAC: ${f.macAddress}  |  RSSI: ${f.rssiDbm} dBm  |  Δt: $dtStr\n" +
                    "FrameCtrl: 0x${Integer.toHexString(f.frameControl)} (binding=${f.isEventOrBindingFrame})"
        }
        val dc = snap.devConfig
        hwBleModeStatusView.text =
            "Configured Excursion Mode: ${snap.userExcursionBleMode.displayName} (Effective: ${snap.effectiveBleMode.name})\n" +
                "Active Keep-Alive [epoch=${dc.configEpoch}]: ${dc.strategy.id} • every ${dc.intervalSec}s • dedup=${dc.deduplicateFdcdTrace}"
        val gattLines = buildString {
            append(snap.gattConnectionStatus)
            if (snap.gattDiscoveredServicesLog.isNotEmpty()) {
                append("\n")
                append(snap.gattDiscoveredServicesLog.joinToString("\n"))
            }
        }
        hwGattStatusView.text = gattLines
        hwRawHexLogView.text = if (snap.recentRawFramesLog.isEmpty()) {
            "(Waiting for 0xFDCD packets...)"
        } else {
            snap.recentRawFramesLog.joinToString("\n")
        }
    }

    private fun refreshLiveRecentGraph() {
        val svc = collectorService ?: return
        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = nowSec - 15L * 60L
        // Query last 15 minutes of 15s high-resolution samples to match the "Last 15 Minutes" header
        val recentSlots = svc.ringStore.queryRange(0, startSec, nowSec)
        liveRecentHistoryView.fixedWindowStartEpochSec = startSec
        liveRecentHistoryView.fixedWindowEndEpochSec = nowSec
        liveRecentHistoryView.slices = emptyList()
        liveRecentHistoryView.slots = recentSlots
    }

    private fun refreshTripsTab() {
        val svc = collectorService ?: return

        // Check if user selected a custom 14-day ring buffer window to inspect
        val cwStart = customWindowStartEpochSec
        val cwEnd = customWindowEndEpochSec
        if (cwStart != null && cwEnd != null && cwEnd > cwStart) {
            val slots = svc.ringStore.queryRange(0, cwStart, cwEnd)
            val spanLabel = "${dateShortTimeFmt.format(Instant.ofEpochSecond(cwStart))} – ${shortTimeFmt.format(Instant.ofEpochSecond(cwEnd))}"
            tripSelectorLabel.text = "14d Window: $spanLabel"
            pinTripBtn?.visibility = View.GONE
            manageTripBtn?.visibility = View.GONE
            tripRetentionBanner?.visibility = View.GONE
            dualPaneSlicerView.slices = emptyList()
            dualPaneSlicerView.slots = slots
            slicesContainer?.removeAllViews()
            slicesContainer?.let { container ->
                val carveBtn = Button(this).apply {
                    text = "✂️ Carve This Window as a New Slicable Trip"
                    isAllCaps = false
                    setOnClickListener {
                        carveCustomWindowAsTrip(cwStart, cwEnd)
                    }
                }
                container.addView(carveBtn)
            }
            return
        }

        val excursions = svc.metadataDb.getExcursions()
        if (excursions.isEmpty()) {
            selectedExcursionId = null
            lastSeenLatestExcursionId = null
            tripSelectorLabel.text = "No Trips Recorded Yet"
            pinTripBtn?.visibility = View.GONE
            manageTripBtn?.visibility = View.GONE
            tripRetentionBanner?.visibility = View.GONE
            dualPaneSlicerView.slots = emptyList()
            dualPaneSlicerView.slices = emptyList()
            slicesContainer?.removeAllViews()
            if (isDemoDataEnabled()) {
                slicesContainer?.addView(Button(this).apply {
                    text = "🎭 Load Canned Demo Trip"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnClickListener {
                        customWindowStartEpochSec = null
                        customWindowEndEpochSec = null
                        selectedExcursionId = svc.seedDemoChicagoExcursion()
                        refreshTripsTab()
                    }
                })
                slicesContainer?.addView(Button(this).apply {
                    text = "📂 Import / Replay CSV File..."
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnClickListener {
                        importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                    }
                })
            }
            return
        }

        val (resolvedId, newestId) = MetadataDatabase.resolveSelectedExcursionId(
            excursions = excursions,
            currentSelectedId = selectedExcursionId,
            lastSeenLatestId = lastSeenLatestExcursionId,
            isTripEditMode = isTripEditMode
        )
        selectedExcursionId = resolvedId
        lastSeenLatestExcursionId = newestId
        val currentExc = excursions.firstOrNull { it.id == resolvedId } ?: excursions.first()
        val slices = svc.metadataDb.getSlicesForExcursion(currentExc.id)
        val slots = svc.metadataDb.resolveSlotsForExcursion(currentExc.id, svc.ringStore)

        val pinBadge = if (currentExc.isPinned) "📌" else "14d"
        tripSelectorLabel.text = if (isLandscape()) "${currentExc.title} [$pinBadge]" else currentExc.title
        manageTripBtn?.visibility = View.VISIBLE
        pinTripBtn?.apply {
            visibility = View.VISIBLE
            text = if (currentExc.isPinned) "📌 Pinned" else "⏳ 14d • 📌 Pin"
            if (currentExc.isPinned) {
                background = roundedRect("#1E293B", dp(6), strokeHex = "#334155", strokeWidthPx = dp(1))
                setTextColor(Color.parseColor("#94A3B8"))
            } else {
                background = roundedRect("#1E293B", dp(6), strokeHex = "#D97706", strokeWidthPx = dp(1))
                setTextColor(Color.parseColor("#FDE68A"))
            }
        }
        dualPaneSlicerView.slices = slices
        dualPaneSlicerView.slots = slots
        updateEditHelperText()
        updateTripRetentionBanner(currentExc, slices)

        if (!isLandscape()) {
            renderSliceCards(slices, slots)
        }
    }

    private fun updateSliceCardHighlights(cursorTs: Long) {
        if (renderedSliceCards.size <= 1) return
        for ((slice, card) in renderedSliceCards) {
            val active = cursorTs in slice.startEpochSec..slice.endEpochSec
            card.background = if (active) {
                roundedRect("#1E293B", dp(10), strokeHex = "#38BDF8", strokeWidthPx = dp(2))
            } else {
                roundedRect("#1E293B", dp(10))
            }
        }
    }

    private fun renderSliceCards(
        slices: List<SessionSliceRecord>,
        allSlots: List<AqlRingBufferStore.RingSlotRecord>
    ) {
        val container = slicesContainer ?: return
        container.removeAllViews()
        val cardsList = mutableListOf<Pair<SessionSliceRecord, LinearLayout>>()
        val cursorTs = dualPaneSlicerView.cursorEpochSec

        for ((idx, slice) in slices.withIndex()) {
            val sliceSlots = allSlots.filter { it.bucketEpochSec in slice.startEpochSec..slice.endEpochSec }
            val stats = AqlRingBufferStore.computeSliceStats(sliceSlots, stepSec = 15)
            val isCursorInside = slices.size > 1 && cursorTs in slice.startEpochSec..slice.endEpochSec

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (isCursorInside) {
                    roundedRect("#1E293B", dp(10), strokeHex = "#38BDF8", strokeWidthPx = dp(2))
                } else {
                    roundedRect("#1E293B", dp(10))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    dualPaneSlicerView.selectedDividerIndex = -1
                    val mid = (((slice.startEpochSec + slice.endEpochSec) / 2L) / 15L) * 15L
                    dualPaneSlicerView.cursorEpochSec = mid.coerceIn(slice.startEpochSec, slice.endEpochSec)
                    updateEditModeUi()
                    updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                    dualPaneSlicerView.invalidate()
                }
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spanDuration = formatElapsedAge((slice.endEpochSec - slice.startEpochSec).coerceAtLeast(0L))
            val timeSpan = "${shortTimeFmt.format(Instant.ofEpochSecond(slice.startEpochSec))}–${shortTimeFmt.format(Instant.ofEpochSecond(slice.endEpochSec))} ($spanDuration)"
            val titleTv = TextView(this).apply {
                text = "#${idx + 1} ${slice.label}\n$timeSpan"
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerRow.addView(titleTv)
            if (slices.size >= 2 && (idx == 0 || idx == slices.size - 1)) {
                val isStart = (idx == 0)
                val dropBtn = Button(this).apply {
                    text = if (isStart) "🗑️ Drop #1" else "🗑️ Drop #${idx + 1}"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setOnClickListener { confirmAndTrimSlice(slice, isStart = isStart, idx = idx) }
                }
                headerRow.addView(dropBtn)
            }
            val editSegmentBtn = Button(this).apply {
                text = "✏️ Edit"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    val nextSlice = if (idx < slices.size - 1) slices[idx + 1] else null
                    showSegmentEditModal(slice, nextSlice, idx)
                }
            }
            headerRow.addView(editSegmentBtn)
            card.addView(headerRow)

            val metaRow = TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "Est. distance: %.0fm • CO₂ ≥1000 ppm: %.1fm • %d samples",
                    stats.totalDistanceMetersEstimate,
                    stats.minutesCo2Above1000,
                    stats.totalSamples
                )
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(4), 0, dp(4))
            }
            card.addView(metaRow)

            card.addView(buildSliceStatsTable(stats))

            if (slice.notes.isNotBlank()) {
                val notesView = TextView(this).apply {
                    text = "📝 \"${slice.notes}\""
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(0, dp(4), 0, dp(2))
                }
                card.addView(notesView)
            }

            val controlsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val exportSliceBtn = Button(this).apply {
                text = "📤 Export CSV"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    exportSliceToCsv(slice, idx + 1, sliceSlots)
                }
            }
            controlsRow.addView(exportSliceBtn)
            card.addView(controlsRow)

            cardsList.add(slice to card)
            container.addView(card)
        }
        renderedSliceCards = cardsList
    }

    private data class SliceStatRowSpec(
        val label: String,
        val colorHex: String,
        val p50: String,
        val p95: String,
        val max: String,
        val avg: String,
        val highlightMedian: Boolean = false
    )

    private fun buildSliceStatsTable(stats: AqlRingBufferStore.SliceStatistics): View {
        if (stats.slotCount <= 0) {
            return TextView(this).apply {
                text = "No air-quality samples recorded in this segment."
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(2), 0, dp(4))
            }
        }

        val useF = TemperatureUnitFormatter.useFahrenheit(this)
        val tempUnit = TemperatureUnitFormatter.unitSymbol(useF)
        fun fmtTemp(c: Float): String = String.format(
            Locale.US,
            "%.1f",
            TemperatureUnitFormatter.convertFromCelsius(c, useF)
        )

        val rows = listOf(
            SliceStatRowSpec(
                label = "CO₂ (ppm)",
                colorHex = "#38BDF8",
                p50 = stats.co2Median.toString(),
                p95 = stats.co2P95.toString(),
                max = stats.co2Max.toString(),
                avg = String.format(Locale.US, "%.0f", stats.co2Mean)
            ),
            SliceStatRowSpec(
                label = "PM2.5 (µg/m³)",
                colorHex = "#A78BFA",
                p50 = String.format(Locale.US, "%.1f", stats.pm25P50),
                p95 = String.format(Locale.US, "%.1f", stats.pm25P95),
                max = stats.pm25Max.toString(),
                avg = String.format(Locale.US, "%.1f", stats.pm25Mean)
            ),
            SliceStatRowSpec(
                label = "PM10 (µg/m³)",
                colorHex = "#C084FC",
                p50 = String.format(Locale.US, "%.1f", stats.pm10P50),
                p95 = String.format(Locale.US, "%.1f", stats.pm10P95),
                max = stats.pm10Max.toString(),
                avg = String.format(Locale.US, "%.1f", stats.pm10Mean)
            ),
            SliceStatRowSpec(
                label = "Temp ($tempUnit)",
                colorHex = "#FB923C",
                p50 = fmtTemp(stats.tempCP50),
                p95 = fmtTemp(stats.tempCP95),
                max = fmtTemp(stats.tempCMax),
                avg = fmtTemp(stats.tempCMean),
                highlightMedian = true
            ),
            SliceStatRowSpec(
                label = "RH (%)",
                colorHex = "#2DD4BF",
                p50 = String.format(Locale.US, "%.1f", stats.humidityP50),
                p95 = String.format(Locale.US, "%.1f", stats.humidityP95),
                max = String.format(Locale.US, "%.1f", stats.humidityMax),
                avg = String.format(Locale.US, "%.1f", stats.humidityMean),
                highlightMedian = true
            )
        )

        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#0F172A", dp(8))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
        }

        fun makeCell(text: String, weight: Float, alignEnd: Boolean, colorHex: String, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor(colorHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                gravity = if (alignEnd) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(3))
        }
        header.addView(makeCell("Metric", 1.55f, alignEnd = false, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell("P50", 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell("P95", 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell("Max", 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell("Avg", 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        table.addView(header)

        for (spec in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(makeCell(spec.label, 1.55f, alignEnd = false, colorHex = spec.colorHex, bold = true))
            row.addView(
                makeCell(
                    spec.p50,
                    1.0f,
                    alignEnd = true,
                    colorHex = if (spec.highlightMedian) "#F8FAFC" else "#E2E8F0",
                    bold = spec.highlightMedian
                )
            )
            row.addView(makeCell(spec.p95, 1.0f, alignEnd = true, colorHex = "#E2E8F0"))
            row.addView(
                makeCell(
                    spec.max,
                    1.0f,
                    alignEnd = true,
                    colorHex = if (!spec.highlightMedian) "#F8FAFC" else "#E2E8F0",
                    bold = !spec.highlightMedian
                )
            )
            row.addView(makeCell(spec.avg, 1.0f, alignEnd = true, colorHex = "#CBD5E1"))
            table.addView(row)
        }
        return table
    }

    // =========================================================================
    // SEGMENT EDIT MODAL (FREEFORM LABEL, CATEGORY, NOTES, OPTIONAL PRESETS)
    // =========================================================================
    private fun showSegmentEditModal(slice: SessionSliceRecord, nextSlice: SessionSliceRecord?, sliceIdx: Int) {
        val svc = collectorService ?: return
        val presets = svc.metadataDb.getSegmentPresets()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        val labelTitle = TextView(this).apply {
            text = "Segment Label (Freeform text or tap a preset below):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val labelInput = EditText(this).apply {
            hint = "e.g., CTA Bus #22, Coffee Shop, Bookshop"
            setText(slice.label)
        }
        layout.addView(labelTitle)
        layout.addView(labelInput)

        // Optional quick-fill presets inside the modal (populates the freeform fields without forcing preset creation)
        val presetsHint = TextView(this).apply {
            text = "Quick-Fill from Saved Presets (optional):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(4))
        }
        layout.addView(presetsHint)

        val chipScroll = HorizontalScrollView(this)
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in presets) {
            val chipBtn = Button(this).apply {
                text = "${preset.iconEmoji} ${preset.label}"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    labelInput.setText(preset.label)
                }
            }
            chipRow.addView(chipBtn)
        }
        chipScroll.addView(chipRow)
        layout.addView(chipScroll)

        val notesTitle = TextView(this).apply {
            text = "Notes (optional):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, dp(2))
        }
        val notesInput = EditText(this).apply {
            hint = "e.g., Busy espresso bar, windows open"
            setText(slice.notes)
        }
        layout.addView(notesTitle)
        layout.addView(notesInput)

        val allSlices = dualPaneSlicerView.slices
        var dropBoundaryBtn: Button? = null
        if (allSlices.size >= 2 && (sliceIdx == 0 || sliceIdx == allSlices.size - 1)) {
            val isStart = (sliceIdx == 0)
            dropBoundaryBtn = Button(this).apply {
                text = "🗑️ Drop #${sliceIdx + 1} (Trim Trip ${if (isStart) "Start" else "End"})"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            layout.addView(dropBoundaryBtn)
        }

        val spanTitle = "${shortTimeFmt.format(Instant.ofEpochSecond(slice.startEpochSec))}–${shortTimeFmt.format(Instant.ofEpochSecond(slice.endEpochSec))}"
        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("Edit Segment #${sliceIdx + 1} ($spanTitle)")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString().trim().ifEmpty { "Segment" }
                val newNotes = notesInput.text.toString().trim()
                svc.metadataDb.updateSliceMetadata(
                    sliceId = slice.id,
                    label = newLabel,
                    notes = newNotes,
                    includeInExport = slice.includeInExport
                )
                refreshTripsTab()
            }
            .setNegativeButton("Cancel", null)

        if (nextSlice != null) {
            dialogBuilder.setNeutralButton("Merge #${sliceIdx + 1} + #${sliceIdx + 2} ↓") { _, _ ->
                svc.metadataDb.mergeAdjacentSlices(slice.id, nextSlice.id, svc.ringStore)
                refreshTripsTab()
            }
        }
        val dialog = dialogBuilder.create()
        dropBoundaryBtn?.setOnClickListener {
            dialog.dismiss()
            confirmAndTrimSlice(slice, isStart = (sliceIdx == 0), idx = sliceIdx)
        }
        activeSegmentModalDialog?.dismiss()
        activeSegmentModalDialog = dialog
        dialog.setOnDismissListener {
            if (activeSegmentModalDialog === dialog) {
                activeSegmentModalDialog = null
            }
        }
        dialog.show()
    }

    // =========================================================================
    // 14-DAY RING BUFFER TIME-WINDOW SELECTOR (PORTRAIT MODE)
    // =========================================================================
    private fun show14dRingWindowSelectorDialog() {
        val svc = collectorService ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        val infoTv = TextView(this).apply {
            text = "Focus on a specific subset of the 14-day high-resolution ring buffer without compressing 14 days onto one screen:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(infoTv)

        val dayLabels = (0..13).map { daysAgo ->
            val date = LocalDate.now().minusDays(daysAgo.toLong())
            when (daysAgo) {
                0 -> "Today ($date)"
                1 -> "Yesterday ($date)"
                else -> "$daysAgo days ago ($date)"
            }
        }
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dayLabels)
        }

        val hourLabels = (0..47).map { halfHourIdx ->
            val h = halfHourIdx / 2
            val m = if (halfHourIdx % 2 == 0) 0 else 30
            String.format(Locale.US, "Start at %02d:%02d", h, m)
        }
        val nowZdt = ZonedDateTime.now()
        val defaultHalfHourIdx = ((nowZdt.hour * 2 + (if (nowZdt.minute >= 30) 1 else 0)) - 3).coerceIn(0, 47)
        val startHourSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, hourLabels)
            setSelection(defaultHalfHourIdx)
        }

        val durationMinutesOptions = listOf(30, 60, 90, 120, 180, 240, 360)
        val durationLabels = durationMinutesOptions.map { mins ->
            if (mins < 60) "Duration: ${mins} mins" else "Duration: ${mins / 60.0} hours"
        }
        val durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, durationLabels)
            setSelection(2) // Default 90 mins
        }

        layout.addView(daySpinner)
        layout.addView(startHourSpinner)
        layout.addView(durationSpinner)

        AlertDialog.Builder(this)
            .setTitle("Select Window from 14-Day Ring Buffer")
            .setView(layout)
            .setPositiveButton("Carve as New Trip") { _, _ ->
                val (startSec, endSec) = computeWindowEpochRange(
                    daySpinner.selectedItemPosition,
                    startHourSpinner.selectedItemPosition,
                    durationMinutesOptions[durationSpinner.selectedItemPosition]
                )
                carveCustomWindowAsTrip(startSec, endSec)
            }
            .setNeutralButton("Preview Window Only") { _, _ ->
                val (startSec, endSec) = computeWindowEpochRange(
                    daySpinner.selectedItemPosition,
                    startHourSpinner.selectedItemPosition,
                    durationMinutesOptions[durationSpinner.selectedItemPosition]
                )
                customWindowStartEpochSec = startSec
                customWindowEndEpochSec = endSec
                refreshTripsTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun computeWindowEpochRange(daysAgo: Int, halfHourIdx: Int, durationMins: Int): Pair<Long, Long> {
        val date = LocalDate.now().minusDays(daysAgo.toLong())
        val time = LocalTime.of(halfHourIdx / 2, if (halfHourIdx % 2 == 0) 0 else 30)
        val startZdt = ZonedDateTime.of(date, time, ZoneId.systemDefault())
        val startSec = (startZdt.toEpochSecond() / 15L) * 15L
        val endSec = startSec + durationMins * 60L
        return startSec to endSec
    }

    private fun carveCustomWindowAsTrip(startSec: Long, endSec: Long) {
        val svc = collectorService ?: return
        val title = "Carved Trip (${dateShortTimeFmt.format(Instant.ofEpochSecond(startSec))})"
        val exc = svc.metadataDb.startExcursion(deviceId = 1L, startEpochSec = startSec, title = title)
        svc.metadataDb.updateOngoingExcursionEnd(exc.id, endSec, closeExcursion = true, ringStore = svc.ringStore)
        svc.metadataDb.setExcursionPinned(exc.id, true, svc.ringStore)
        customWindowStartEpochSec = null
        customWindowEndEpochSec = null
        selectedExcursionId = exc.id
        refreshTripsTab()
        Toast.makeText(this, "📌 Carved & Pinned Trip: $title", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // SETTINGS MENU MODAL (PLACES, TEMPERATURE UNITS, ALERTS, PRESETS)
    // =========================================================================
    private fun showSettingsModal() {
        val svc = collectorService ?: return
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        // 1. Temperature Unit Preference
        val tempHeader = TextView(this).apply {
            text = "Temperature Display Unit"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        col.addView(tempHeader)

        val currentPref = TemperatureUnitFormatter.getPreference(this)
        val effectiveSym = TemperatureUnitFormatter.unitSymbol(TemperatureUnitFormatter.useFahrenheit(this))
        val tempStatus = TextView(this).apply {
            text = "Current: ${currentPref.displayName} → Displaying $effectiveSym"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(6))
        }
        col.addView(tempStatus)

        val tempPrefs = TempUnitPreference.entries
        val tempSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                tempPrefs.map { it.displayName }
            )
            setSelection(tempPrefs.indexOf(currentPref).coerceAtLeast(0))
        }
        col.addView(tempSpinner)

        // 2. Monitor Keep-Alive Mode (>30m Unplugged)
        val keepAliveHeader = TextView(this).apply {
            text = "Monitor Keep-Alive Mode (>30m Unplugged)"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        col.addView(keepAliveHeader)

        val currentKeepAliveStrategy = svc.devConfig.strategy
        val keepAliveChoices = buildList {
            addAll(AqlCollectorService.KeepAlivePayloadStrategy.userAccessibleStrategies)
            if (currentKeepAliveStrategy !in this) {
                add(currentKeepAliveStrategy)
            }
        }
        val keepAliveDescView = TextView(this).apply {
            text = currentKeepAliveStrategy.settingsDescription
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(4))
        }
        val keepAliveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                keepAliveChoices.map { it.settingsTitle }
            )
            setSelection(keepAliveChoices.indexOf(currentKeepAliveStrategy).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    keepAliveDescView.text = keepAliveChoices[position].settingsDescription
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        col.addView(keepAliveSpinner)
        col.addView(keepAliveDescView)

        // 3. Saved Wi-Fi Places (GPS Off)
        val placesHeader = TextView(this).apply {
            text = "Saved Wi-Fi Places (GPS Disabled Automatically)"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        col.addView(placesHeader)

        val places = svc.metadataDb.getKnownPlaces()
        if (places.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No Saved Places configured yet."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            }
            col.addView(emptyTv)
        } else {
            val fakeSsid = svc.fakeWifiSsidOverride
            for (p in places) {
                val ssids = p.networks.joinToString(", ") { fakeSsid ?: it.ssid }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                val tv = TextView(this).apply {
                    text = "🏠 ${p.label} ($ssids)"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tv)
                if (isDemoDataEnabled()) {
                    val simActiveHere = svc.isManualKnownPlaceOverrideActive() &&
                        svc.buildSnapshot().matchedKnownPlace?.id == p.id
                    val simBtn = Button(this).apply {
                        text = if (simActiveHere) "Stop Sim" else "Simulate"
                        isAllCaps = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setOnClickListener {
                            val stopping = svc.isManualKnownPlaceOverrideActive() &&
                                svc.buildSnapshot().matchedKnownPlace?.id == p.id
                            if (stopping) {
                                svc.setManualKnownPlaceOverride(0)
                                text = "Simulate"
                                Toast.makeText(this@MainActivity, "Stopped simulated presence", Toast.LENGTH_SHORT).show()
                            } else {
                                svc.setManualKnownPlaceOverride(p.id)
                                text = "Stop Sim"
                                Toast.makeText(this@MainActivity, "Simulating presence at ${p.label} (GPS Off)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    row.addView(simBtn)
                }
                col.addView(row)
            }
        }
        val addPlaceBtn = Button(this).apply {
            text = "+ Add or Link Wi-Fi Network to Saved Place"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { promptAddCurrentWifiToSavedPlaces() }
        }
        col.addView(addPlaceBtn)

        // 3. In-Place Alert Thresholds
        val ev = svc.alertEvaluator
        val alertsHeader = TextView(this).apply {
            text = "In-Place Threshold Alerts"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        val alertsSummary = TextView(this).apply {
            text = "CO₂: Warn ${ev.co2WarningThresholdPpm} / Crit ${ev.co2CriticalThresholdPpm} ppm\n" +
                "PM2.5: Warn ${ev.pm25WarningThresholdUgm3} / Crit ${ev.pm25CriticalThresholdUgm3} µg/m³\n" +
                "Qingping Low Battery: Warn ≤${ev.batteryWarningThresholdPct}% / Crit ≤${ev.batteryCriticalThresholdPct}%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val editAlertsBtn = Button(this).apply {
            text = "Configure Alert Thresholds..."
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { promptEditAlertThresholds() }
        }
        col.addView(alertsHeader)
        col.addView(alertsSummary)
        col.addView(editAlertsBtn)

        // 4. Reusable Segment Presets
        val presetsHeader = TextView(this).apply {
            text = "Reusable Segment Presets"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        val presets = svc.metadataDb.getSegmentPresets()
        val presetsSummary = TextView(this).apply {
            text = presets.joinToString(" • ") { "${it.iconEmoji} ${it.label}" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val addPresetBtn = Button(this).apply {
            text = "+ Add Reusable Segment Preset..."
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { promptAddSegmentPreset() }
        }
        col.addView(presetsHeader)
        col.addView(presetsSummary)
        col.addView(addPresetBtn)

        // 5. Chart Dropout Grace Period
        val dropoutHeader = TextView(this).apply {
            text = "Chart Dropout Grace Period"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        col.addView(dropoutHeader)
        val dropoutValuesSec = listOf(30L, 45L, 90L, 180L)
        val dropoutLabels = listOf(
            "30s (2 missed 15s slots)",
            "45s (Default • 3 missed slots)",
            "90s (Matches Trip Pause threshold)",
            "3m (180s grace)"
        )
        val currentGraceSec = getDropoutGracePeriodSec()
        val dropoutSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                dropoutLabels
            )
            setSelection(dropoutValuesSec.indexOf(currentGraceSec).takeIf { it >= 0 } ?: 1)
        }
        col.addView(dropoutSpinner)

        // 6. Diagnostics & Developer Mode
        val diagHeader = TextView(this).apply {
            text = "Diagnostics & Support"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        }
        col.addView(diagHeader)

        val shareLogBtn = Button(this).apply {
            text = "📤 Share Diagnostic Log (JSONL)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { shareHardwareDiagnosticTrace() }
        }
        col.addView(shareLogBtn)

        val privacyBtn = Button(this).apply {
            text = "🔒 View Location, Activity & Privacy Disclosure"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { showProminentLocationDisclosureDialog() }
        }
        col.addView(privacyBtn)

        val missingLocationOrActivity = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        if (missingLocationOrActivity) {
            val enableLocationBtn = Button(this).apply {
                text = "📍 Enable Location & Step Tracking"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener {
                    requestRuntimePermissionsIfNeeded(forcePrompt = true)
                }
            }
            col.addView(enableLocationBtn)
        }

        val demoDataCheck = CheckBox(this).apply {
            text = "🎭 Demo Data Mode (Synthetic BLE Stream, Sample Trip & Place Sim)"
            isChecked = isDemoDataEnabled()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        col.addView(demoDataCheck)

        val devModeCheck = CheckBox(this).apply {
            text = "🛠️ Developer Mode (Show HW Test Tab & Raw BLE Logs)"
            isChecked = isDeveloperModeEnabled()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        col.addView(devModeCheck)

        val fakeWifiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), 0, dp(4))
            visibility = if (devModeCheck.isChecked || !svc.fakeWifiSsidOverride.isNullOrBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        val fakeWifiLabel = TextView(this).apply {
            text = "Anonymized Wi-Fi SSID Override (Video/Screenshots):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val fakeWifiSsidInput = EditText(this).apply {
            hint = "Leave blank for real SSID (e.g. ChicagoHome-5G)"
            setText(svc.fakeWifiSsidOverride ?: "")
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        fakeWifiContainer.addView(fakeWifiLabel)
        fakeWifiContainer.addView(fakeWifiSsidInput)
        col.addView(fakeWifiContainer)

        devModeCheck.setOnCheckedChangeListener { _, checked ->
            fakeWifiContainer.visibility = if (checked || !svc.fakeWifiSsidOverride.isNullOrBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        scroll.addView(col)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings & Places")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ ->
                val chosenTempPref = tempPrefs[tempSpinner.selectedItemPosition]
                TemperatureUnitFormatter.setPreference(this, chosenTempPref)
                val chosenKeepAlive = keepAliveChoices[keepAliveSpinner.selectedItemPosition]
                if (chosenKeepAlive != currentKeepAliveStrategy) {
                    svc.updateDevKeepAliveConfig(
                        strategy = chosenKeepAlive,
                        reason = "settings_modal"
                    )
                    if (chosenKeepAlive == AqlCollectorService.KeepAlivePayloadStrategy.NONE_PASSIVE) {
                        svc.setExcursionBleMode(BleCollectionMode.PASSIVE_ONLY)
                    } else if (svc.buildSnapshot().userExcursionBleMode == BleCollectionMode.PASSIVE_ONLY) {
                        svc.setExcursionBleMode(BleCollectionMode.PERIODIC_GATT_KEEPALIVE)
                    }
                    Toast.makeText(
                        this,
                        "Keep-Alive mode: ${chosenKeepAlive.statusBadge}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val chosenGraceSec = dropoutValuesSec[dropoutSpinner.selectedItemPosition]
                setDropoutGracePeriodSec(chosenGraceSec)
                setDemoDataEnabled(demoDataCheck.isChecked)
                setDeveloperModeEnabled(devModeCheck.isChecked)
                svc.setFakeWifiSsidOverride(fakeWifiSsidInput.text.toString())
                refreshTripsTab()
                svc.let { renderLiveSnapshot(it.buildSnapshot()) }
                dualPaneSlicerView.invalidate()
                liveRecentHistoryView.invalidate()
            }
            .show()
    }

    private fun splitAtCurrentCursor() {
        val svc = collectorService ?: return
        val excId = selectedExcursionId ?: return
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val slices = svc.metadataDb.getSlicesForExcursion(excId)
        val targetSlice = slices.firstOrNull { cursorTs > it.startEpochSec && cursorTs < it.endEpochSec }
        if (targetSlice == null) {
            if (dualPaneSlicerView.selectedDividerIndex >= 0) {
                switchToSplitCursorMode()
                Toast.makeText(
                    this,
                    "Switched to 🎯 Split Cursor — aim inside the segment and tap ✂️ Split.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "Tap inside a segment (between dividers) on the chart to place the split cursor.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val alignedSplit = (cursorTs / 15L) * 15L
        if (svc.metadataDb.splitSliceAt(targetSlice.id, cursorTs, svc.ringStore)) {
            dualPaneSlicerView.selectedDividerIndex = -1
            refreshTripsTab()
            val newRight = dualPaneSlicerView.slices.firstOrNull { it.startEpochSec == alignedSplit }
            if (newRight != null && newRight.endEpochSec - newRight.startEpochSec >= 30L) {
                val mid = (((newRight.startEpochSec + newRight.endEpochSec) / 2L) / 15L) * 15L
                dualPaneSlicerView.cursorEpochSec = mid.coerceIn(newRight.startEpochSec, newRight.endEpochSec)
            }
            updateEditModeUi()
            updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
            dualPaneSlicerView.invalidate()
        }
    }

    private fun nudgeSelectedDivider(deltaSec: Long) {
        val svc = collectorService ?: return
        val excId = selectedExcursionId ?: return
        val slices = svc.metadataDb.getSlicesForExcursion(excId)
        val divIdx = dualPaneSlicerView.selectedDividerIndex
        if (slices.size < 2 || divIdx < 0 || divIdx >= slices.size - 1) {
            dualPaneSlicerView.nudgeCursor(deltaSec)
            return
        }
        val left = slices[divIdx]
        val right = slices[divIdx + 1]
        val newBoundary = left.endEpochSec + deltaSec
        if (svc.metadataDb.moveSharedDivider(left.id, right.id, newBoundary, svc.ringStore)) {
            dualPaneSlicerView.cursorEpochSec = (newBoundary / 15L) * 15L
            refreshTripsTab()
        }
    }

    private fun applyExcursionTrim(excursionId: Long, boundaryEpochSec: Long, trimStart: Boolean) {
        val svc = collectorService ?: return
        val ok = if (trimStart) {
            svc.metadataDb.trimExcursionStart(excursionId, boundaryEpochSec, svc.ringStore)
        } else {
            val trimmed = svc.metadataDb.trimExcursionEnd(excursionId, boundaryEpochSec, svc.ringStore)
            if (trimmed) svc.detachActiveExcursionIfMatching(excursionId)
            trimmed
        }
        if (!ok) {
            Toast.makeText(this, "Cannot trim outside the current trip bounds.", Toast.LENGTH_SHORT).show()
            return
        }
        dualPaneSlicerView.selectedDividerIndex = -1
        refreshTripsTab()
        val remaining = dualPaneSlicerView.slices
        if (remaining.isNotEmpty()) {
            val focusSlice = if (trimStart) remaining.first() else remaining.last()
            val mid = (((focusSlice.startEpochSec + focusSlice.endEpochSec) / 2L) / 15L) * 15L
            dualPaneSlicerView.cursorEpochSec = mid.coerceIn(focusSlice.startEpochSec, focusSlice.endEpochSec)
        }
        updateEditModeUi()
        updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
        dualPaneSlicerView.invalidate()
    }

    private fun confirmAndTrimSlice(slice: SessionSliceRecord, isStart: Boolean, idx: Int) {
        val boundaryTs = if (isStart) slice.endEpochSec else slice.startEpochSec
        val boundaryTime = shortTimeFmt.format(Instant.ofEpochSecond(boundaryTs))
        AlertDialog.Builder(this)
            .setTitle("Drop Segment #${idx + 1} (${slice.label})?")
            .setMessage("This trims the ${if (isStart) "beginning" else "end"} of the trip to $boundaryTime.")
            .setPositiveButton("Drop & Trim") { _, _ ->
                applyExcursionTrim(slice.excursionId, boundaryTs, trimStart = isStart)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageTripDialog() {
        val svc = collectorService ?: return
        if (customWindowStartEpochSec != null) return
        val excId = selectedExcursionId ?: return
        val exc = svc.metadataDb.getExcursions().firstOrNull { it.id == excId } ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val titleLabel = TextView(this).apply {
            text = "Trip Title:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val titleInput = EditText(this).apply {
            hint = "e.g., Morning Walk & Coffee, Loop Errand"
            setText(exc.title)
        }

        val pinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            }
        }
        val pinTripCheck = CheckBox(this).apply {
            text = "📌 Retain / Pin Entire Trip Forever"
            isChecked = exc.isPinned
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val pinStatusHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val outsideWindow = svc.metadataDb.isExcursionOutsideRingWindow(exc, svc.ringStore)
        fun refreshPinCardStyle(pinned: Boolean) {
            val strokeColor = when {
                pinned -> "#059669"
                outsideWindow -> "#EF4444"
                else -> "#F59E0B"
            }
            val textColor = when {
                pinned -> "#34D399"
                outsideWindow -> "#FCA5A5"
                else -> "#FBBF24"
            }
            pinCard.background = roundedRect("#1E293B", dp(8), strokeHex = strokeColor, strokeWidthPx = dp(2))
            pinStatusHint.setTextColor(Color.parseColor(textColor))
            pinStatusHint.text = when {
                pinned -> "✅ Retained Forever: Full 15-second telemetry is saved in permanent storage."
                outsideWindow -> "⚠️ Outside 14-Day Window: Unpinning this trip will permanently delete it and its saved readings."
                else -> "⚠️ Temporary (14-Day Ring): Auto-recorded trips are not retained beyond 14 days unless pinned."
            }
        }
        refreshPinCardStyle(exc.isPinned)
        pinTripCheck.setOnCheckedChangeListener { _, checked -> refreshPinCardStyle(checked) }
        pinCard.addView(pinTripCheck)
        pinCard.addView(pinStatusHint)

        var autoPinnedFromRename = false
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val edited = s?.toString()?.trim().orEmpty()
                if (!exc.isPinned && !autoPinnedFromRename && edited.isNotEmpty() && edited != exc.title) {
                    autoPinnedFromRename = true
                    if (!pinTripCheck.isChecked) {
                        pinTripCheck.isChecked = true
                    }
                }
            }
        })

        layout.addView(titleLabel)
        layout.addView(titleInput)
        layout.addView(pinCard)

        AlertDialog.Builder(this)
            .setTitle("Manage Trip")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = titleInput.text.toString().trim().ifEmpty { "Trip" }
                svc.metadataDb.renameExcursion(exc.id, newTitle)
                val updatedExc = exc.copy(title = newTitle)
                if (pinTripCheck.isChecked != exc.isPinned) {
                    if (!pinTripCheck.isChecked && outsideWindow) {
                        refreshTripsTab()
                        confirmDeleteTrip(updatedExc, initiatedByUnpin = true)
                        return@setPositiveButton
                    }
                    svc.metadataDb.setExcursionPinned(exc.id, pinTripCheck.isChecked, svc.ringStore)
                }
                refreshTripsTab()
            }
            .setNeutralButton("🗑️ Delete Trip") { _, _ ->
                confirmDeleteTrip(exc)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTrip(exc: ExcursionRecord, initiatedByUnpin: Boolean = false) {
        val svc = collectorService ?: return
        val outsideWindow = svc.metadataDb.isExcursionOutsideRingWindow(exc, svc.ringStore)
        val dialogTitle = if (initiatedByUnpin) {
            "Unpin & Delete Trip (${exc.title})?"
        } else {
            "Delete Trip (${exc.title})?"
        }
        val dialogMessage = if (outsideWindow) {
            "⚠️ This trip is outside the 14-day ring buffer window. ${if (initiatedByUnpin) "Unpinning" else "Deleting"} it will permanently delete the trip, its segment labels, and its saved air-quality readings (they are no longer in the 14-day ring buffer)."
        } else {
            "This removes the trip and its segment labels. (Underlying 14-day ring-buffer readings remain intact and can still be carved via 🗓️ 14d Window.)"
        }
        val positiveLabel = when {
            initiatedByUnpin -> "Unpin & Delete"
            outsideWindow -> "Delete Permanently"
            else -> "Delete Trip"
        }
        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setMessage(dialogMessage)
            .setPositiveButton(positiveLabel) { _, _ ->
                svc.detachActiveExcursionIfMatching(exc.id)
                svc.metadataDb.deleteExcursion(exc.id)
                selectedExcursionId = svc.metadataDb.getExcursions().firstOrNull()?.id
                refreshTripsTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTripPickerDialog() {
        val svc = collectorService ?: return
        val excursions = svc.metadataDb.getExcursions()
        val demo = isDemoDataEnabled()
        val options = excursions.map { "${it.title} (${if (it.isPinned) "Pinned" else "14d Ring"})" }.toMutableList()
        if (demo) {
            options.add("🔄 Reload Canned Demo Trip")
            options.add("📂 Import / Replay CSV File...")
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "No trips recorded yet.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Select Trip")
            .setItems(options.toTypedArray()) { _, which ->
                customWindowStartEpochSec = null
                customWindowEndEpochSec = null
                when {
                    which < excursions.size -> {
                        selectedExcursionId = excursions[which].id
                        refreshTripsTab()
                    }
                    demo && which == excursions.size -> {
                        selectedExcursionId = svc.seedDemoChicagoExcursion()
                        refreshTripsTab()
                    }
                    demo && which == excursions.size + 1 -> {
                        importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                    }
                }
            }
            .show()
    }

    private fun exportSliceToCsv(
        slice: SessionSliceRecord,
        sliceNumber: Int,
        preloadedSlots: List<AqlRingBufferStore.RingSlotRecord>? = null
    ) {
        val svc = collectorService ?: return
        val exc = svc.metadataDb.getExcursions().firstOrNull { it.id == slice.excursionId } ?: return
        val slots = preloadedSlots
            ?: svc.metadataDb.resolveSlotsForExcursion(exc.id, svc.ringStore)
                .filter { it.bucketEpochSec in slice.startEpochSec..slice.endEpochSec }
        if (slots.isEmpty()) {
            Toast.makeText(this, "No air-quality samples recorded in this segment.", Toast.LENGTH_SHORT).show()
            return
        }
        val device = svc.metadataDb.getPrimaryDevice()
            ?: MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Qingping Air Monitor Lite", true)
        val placesMap = svc.metadataDb.getKnownPlaces().associateBy { it.id }

        val csvText = CsvExporter.exportSliceCsv(
            device = device,
            excursion = exc,
            slice = slice,
            slots = slots,
            knownPlacesById = placesMap
        )

        val slug = slice.label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "segment" }
            .take(32)
        val exportDir = File(filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "trip_${exc.id}_seg_${sliceNumber}_${slug}.csv")
        outFile.writeText(csvText)

        val authority = "${applicationContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(this, authority, outFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Air Quality Segment CSV: #${sliceNumber} ${slice.label}")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri(outFile.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Segment CSV (${outFile.name})"))
    }

    private fun shareHardwareDiagnosticTrace() {
        val svc = collectorService ?: return
        val logFile = svc.hwLogger.logFile
        if (!logFile.exists()) {
            Toast.makeText(this, "No hardware trace logged yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val tailContent = logFile.readLines().takeLast(200).joinToString("\n")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AirSlice Hardware Bring-Up Trace (JSONL)")
            putExtra(Intent.EXTRA_TEXT, tailContent)
        }
        startActivity(Intent.createChooser(shareIntent, "Share HW Trace JSONL"))
    }

    private fun promptAddCurrentWifiToSavedPlaces() {
        val svc = collectorService ?: return
        val snap = svc.buildSnapshot()
        val existingPlaces = svc.metadataDb.getKnownPlaces()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val ssidInput = EditText(this).apply {
            hint = "Wi-Fi SSID (e.g. MyHomeWiFi)"
            setText(snap.connectedWifiSsid ?: "")
        }
        val placeNameInput = EditText(this).apply {
            hint = "Place Name (e.g. Home, Second Home, Office)"
            setText(if (existingPlaces.isEmpty()) "Home" else "")
        }
        layout.addView(ssidInput)
        layout.addView(placeNameInput)

        val builder = AlertDialog.Builder(this)
            .setTitle("Save Wi-Fi Place (Keep GPS Off)")
            .setView(layout)
            .setPositiveButton("Create New Place") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val label = placeNameInput.text.toString().trim().ifEmpty { "Home" }
                if (ssid.isNotEmpty()) {
                    val created = svc.metadataDb.createKnownPlaceWithWifi(label, ssid, snap.connectedWifiBssid)
                    svc.backfillNewlySavedKnownPlace(created.id)
                }
            }
            .setNegativeButton("Cancel", null)

        if (existingPlaces.isNotEmpty()) {
            builder.setNeutralButton("Link to Existing Place...") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                if (ssid.isNotEmpty()) {
                    val names = existingPlaces.map { it.label }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Link '$ssid' to Existing Place")
                        .setItems(names) { _, which ->
                            val placeId = existingPlaces[which].id
                            svc.metadataDb.linkWifiToKnownPlace(placeId, ssid, snap.connectedWifiBssid)
                            svc.backfillNewlySavedKnownPlace(placeId)
                        }
                        .show()
                }
            }
        }
        builder.show()
    }

    private fun promptAddSegmentPreset() {
        val svc = collectorService ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val emojiInput = EditText(this).apply {
            hint = "Emoji Icon (e.g. ☕, 🚇, 🏢)"
            setText("🏷️")
        }
        val labelInput = EditText(this).apply {
            hint = "Segment Preset Label (e.g. Coffee Shop, Metra)"
        }
        layout.addView(emojiInput)
        layout.addView(labelInput)

        AlertDialog.Builder(this)
            .setTitle("Add Reusable Segment Preset")
            .setView(layout)
            .setPositiveButton("Add Preset") { _, _ ->
                val lbl = labelInput.text.toString().trim()
                if (lbl.isNotEmpty()) {
                    svc.metadataDb.addSegmentPreset(lbl, emojiInput.text.toString().trim())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptEditAlertThresholds() {
        val svc = collectorService ?: return
        val ev = svc.alertEvaluator
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val co2WarnInput = EditText(this).apply {
            hint = "CO₂ Warning ppm (default 1000)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ev.co2WarningThresholdPpm.toString())
        }
        val co2CritInput = EditText(this).apply {
            hint = "CO₂ Critical ppm (default 2000)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ev.co2CriticalThresholdPpm.toString())
        }
        val pm25WarnInput = EditText(this).apply {
            hint = "PM2.5 Warning µg/m³ (default 25)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ev.pm25WarningThresholdUgm3.toString())
        }
        val batWarnInput = EditText(this).apply {
            hint = "Qingping Low Battery Warning % (default 20)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ev.batteryWarningThresholdPct.toString())
        }
        layout.addView(co2WarnInput)
        layout.addView(co2CritInput)
        layout.addView(pm25WarnInput)
        layout.addView(batWarnInput)

        AlertDialog.Builder(this)
            .setTitle("Configure Alert Thresholds")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                co2WarnInput.text.toString().toIntOrNull()?.let { ev.co2WarningThresholdPpm = it }
                co2CritInput.text.toString().toIntOrNull()?.let { ev.co2CriticalThresholdPpm = it }
                pm25WarnInput.text.toString().toIntOrNull()?.let { ev.pm25WarningThresholdUgm3 = it }
                batWarnInput.text.toString().toIntOrNull()?.let { ev.batteryWarningThresholdPct = it.coerceIn(5, 80) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatElapsedAge(ageSec: Long): String = when {
        ageSec < 60L -> "${ageSec}s"
        ageSec < 3600L -> {
            val m = ageSec / 60L
            val s = ageSec % 60L
            if (s == 0L) "${m}m" else "${m}m ${s}s"
        }
        else -> {
            val h = ageSec / 3600L
            val m = (ageSec % 3600L) / 60L
            "${h}h ${m}m"
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(
        hexColor: String,
        cornerRadiusPx: Int,
        strokeHex: String? = null,
        strokeWidthPx: Int = 0
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = cornerRadiusPx.toFloat()
            if (strokeHex != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, Color.parseColor(strokeHex))
            }
        }
}
