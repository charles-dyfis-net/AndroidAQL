package io.github.androidaql.ui

import android.Manifest
import android.app.AlertDialog
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
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
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import io.github.androidaql.R
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.engine.StepMovementEstimator
import io.github.androidaql.export.XlsxExporter
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.service.CompanionDeviceHelper
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MetadataDatabase
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var collectorService: AqlCollectorService? = null
    private var isBound = false
    private var isBindRequested = false
    private var isExitingApp = false
    private var activeTabIndex = 0
    private var selectedExcursionId: Long? = null
    private var lastSeenLatestExcursionId: Long? = null
    private var lastObservedActiveExcursionId: Long? = null
    private var isTripEditMode: Boolean = false

    // Optional custom 14-day ring buffer time window (when inspecting raw ring buffer before carving a trip)
    private var customWindowStartEpochSec: Long? = null
    private var customWindowEndEpochSec: Long? = null

    // Persisted zoom viewport across orientation / lifecycle recreation
    private var savedViewStartEpochSec: Long? = null
    private var savedViewEndEpochSec: Long? = null
    private var lastViewportExcursionId: Long? = null
    private var lastViewportCustomWindow: Pair<Long, Long>? = null

    private var pendingScreenshotPreset: String? = null
    private var pendingServiceStartIntent: Intent? = null
    private var pendingImportXlsxRequest: Pair<Uri, Boolean>? = null
    private var pendingCompanionLinkRequested: Boolean = false
    private var pendingCompanionLinkBleAddress: String? = null
    private var activeSegmentModalDialog: AlertDialog? = null
    private var activeDisclosureDialog: AlertDialog? = null
    private var activeHelpDialog: AlertDialog? = null
    private var activeSettingsDialog: SettingsScreenDialog? = null

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
    private lateinit var recentGraphHeaderView: TextView
    private lateinit var liveFollowResetBtn: TextView
    private lateinit var wifiStatusView: TextView
    private lateinit var saveCurrentWifiBtn: Button
    private lateinit var manualTripToggleBtn: Button
    private lateinit var addNowNoteBtn: Button
    private lateinit var demoStreamBtn: Button

    // Tab 2: Trips & Slicer Views
    private lateinit var tripSelectorLabel: TextView
    private var pinTripBtn: TextView? = null
    private var manageTripBtn: TextView? = null
    private lateinit var modeToggleBtn: Button
    private var tripsAddNoteBtn: TextView? = null
    private var landAddNoteBtn: Button? = null
    private lateinit var dualPaneSlicerView: DualPaneSlicerView
    private var zoomControlBar: LinearLayout? = null
    private var zoomStatusView: TextView? = null
    private var zoomFullBtn: Button? = null
    private var zoom30mBtn: Button? = null
    private var zoom10mBtn: Button? = null
    private var zoom3mBtn: Button? = null
    private var zoomSegBtn: Button? = null
    private var landZoomToggleBtn: Button? = null
    private var landFocusSegBtn: Button? = null
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
    private val exactTimeFmt = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())
    private val dateShortTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        collectorService?.startBleScan()
        collectorService?.refreshWifiAndPlaceState()
        collectorService?.refreshStepSensorRegistration()
        activeSettingsDialog?.refreshPermissionsState()
    }

    private val companionAssociationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val assoc = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
            onCompanionAssociated(assoc)
        }
    }

    private fun launchXlsxImportPicker(timeShiftForDemo: Boolean) {
        val launcher = if (timeShiftForDemo) importDemoReplayXlsxLauncher else importXlsxLauncher
        launcher.launch(arrayOf(XlsxExporter.MIME_TYPE, "application/octet-stream", "*/*"))
    }

    private fun handleImportedXlsxUri(uri: android.net.Uri?, timeShiftForDemo: Boolean) {
        if (uri == null) return
        val svc = collectorService ?: run {
            pendingImportXlsxRequest = uri to timeShiftForDemo
            return
        }
        try {
            val result = contentResolver.openInputStream(uri)?.use { input ->
                svc.importExcursionFromXlsx(input, timeShiftToNow = timeShiftForDemo)
            }
            if (result?.excursionId != null) {
                customWindowStartEpochSec = null
                customWindowEndEpochSec = null
                selectedExcursionId = result.excursionId
                switchTab(1)
                refreshTripsTab()
                val base = if (timeShiftForDemo) {
                    getString(R.string.toast_xlsx_replayed, result.importedSegmentCount, result.importedNoteCount)
                } else {
                    getString(R.string.toast_xlsx_imported, result.importedSegmentCount, result.importedNoteCount)
                }
                Toast.makeText(this, base, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_xlsx_no_samples), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_xlsx_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private val importXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleImportedXlsxUri(uri, timeShiftForDemo = false)
    }

    private val importDemoReplayXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleImportedXlsxUri(uri, timeShiftForDemo = true)
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

    private val serviceUserStopListener: () -> Unit = {
        runOnUiThread { completeAppExit() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AqlCollectorService.LocalBinder ?: return
            if (!isBindRequested) {
                runCatching { unbindService(this) }
                return
            }
            val svc = b.getService()
            collectorService = svc
            isBound = true
            svc.refreshCompanionAssociationCache()
            lastObservedActiveExcursionId = svc.buildSnapshot().activeExcursionId
            svc.addListener(serviceListener)
            svc.addUserStopListener(serviceUserStopListener)
            refreshLiveRecentGraph()
            refreshTripsTab()
            if (activeSettingsDialog?.let { !it.isBoundTo(svc) } == true) {
                showSettingsModal()
            } else {
                activeSettingsDialog?.refreshCompanionAssociationState()
            }
            pendingImportXlsxRequest?.let { (uri, timeShift) ->
                pendingImportXlsxRequest = null
                handleImportedXlsxUri(uri, timeShift)
            }
            tryApplyPendingScreenshotPreset()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            collectorService?.removeListener(serviceListener)
            collectorService?.removeUserStopListener(serviceUserStopListener)
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
                lastViewportCustomWindow = cwStart to cwEnd
            }
            val savedLastVpExc = savedInstanceState.getLong("lastViewportExcursionId", -1L)
            if (savedLastVpExc > 0L) lastViewportExcursionId = savedLastVpExc
            val savedVs = savedInstanceState.getLong("viewStartEpochSec", -1L)
            val savedVe = savedInstanceState.getLong("viewEndEpochSec", -1L)
            if (savedVs > 0L && savedVe > savedVs) {
                savedViewStartEpochSec = savedVs
                savedViewEndEpochSec = savedVe
            }
        } else {
            captureScreenshotPresetFromIntent(intent)
            captureCompanionLinkRequestFromIntent(intent)
        }

        val rootView = buildRootLayout()
        setContentView(rootView)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        requestRuntimePermissionsIfNeeded()

        AqlCollectorService.cancelResumeRequiredNotification(this)
        pendingServiceStartIntent = buildServiceStartIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (isExitingApp) return
        AqlCollectorService.setUserStoppedService(this, false)
        val startIntent = pendingServiceStartIntent ?: Intent(this, AqlCollectorService::class.java)
        pendingServiceStartIntent = null
        ContextCompat.startForegroundService(this, startIntent)
        if (!isBindRequested) {
            isBindRequested = bindService(
                Intent(this, AqlCollectorService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AqlCollectorService.cancelResumeRequiredNotification(this)
        CompanionDeviceHelper.ensureObservingAll(this)
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
        activeSettingsDialog?.refreshPermissionsState()
        activeSettingsDialog?.refreshCompanionAssociationState()
        tryApplyPendingScreenshotPreset()
        if (pendingCompanionLinkRequested) {
            val targetAddr = pendingCompanionLinkBleAddress
            pendingCompanionLinkRequested = false
            pendingCompanionLinkBleAddress = null
            requestCompanionDeviceAssociation(preferredBleAddress = targetAddr)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureCompanionLinkRequestFromIntent(intent)
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

    private fun unbindCollectorService() {
        if (isBindRequested || isBound) {
            collectorService?.removeListener(serviceListener)
            collectorService?.removeUserStopListener(serviceUserStopListener)
            runCatching { unbindService(serviceConnection) }
            isBindRequested = false
            isBound = false
            collectorService = null
        }
    }

    override fun onStop() {
        unbindCollectorService()
        super.onStop()
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
        outState.putLong("lastViewportExcursionId", lastViewportExcursionId ?: -1L)
        val saveVs = if (::dualPaneSlicerView.isInitialized && dualPaneSlicerView.isViewportZoomed()) {
            dualPaneSlicerView.viewStartEpochSec ?: -1L
        } else {
            savedViewStartEpochSec ?: -1L
        }
        val saveVe = if (::dualPaneSlicerView.isInitialized && dualPaneSlicerView.isViewportZoomed()) {
            dualPaneSlicerView.viewEndEpochSec ?: -1L
        } else {
            savedViewEndEpochSec ?: -1L
        }
        outState.putLong("viewStartEpochSec", saveVs)
        outState.putLong("viewEndEpochSec", saveVe)
    }

    override fun onDestroy() {
        activeSettingsDialog?.dismiss()
        activeSettingsDialog = null
        activeSegmentModalDialog?.dismiss()
        activeSegmentModalDialog = null
        activeDisclosureDialog?.dismiss()
        activeDisclosureDialog = null
        activeHelpDialog?.dismiss()
        activeHelpDialog = null
        unbindCollectorService()
        super.onDestroy()
    }

    private fun completeAppExit() {
        isExitingApp = true
        activeSettingsDialog?.dismiss()
        activeSettingsDialog = null
        activeSegmentModalDialog?.dismiss()
        activeSegmentModalDialog = null
        activeDisclosureDialog?.dismiss()
        activeDisclosureDialog = null
        activeHelpDialog?.dismiss()
        activeHelpDialog = null
        unbindCollectorService()
        finishAndRemoveTask()
    }

    private fun captureCompanionLinkRequestFromIntent(sourceIntent: Intent?) {
        if (sourceIntent?.action != AqlCollectorService.ACTION_LINK_COMPANION_MONITOR) return
        val bleAddr = CompanionDeviceHelper.normalizeBluetoothMac(
            sourceIntent.getStringExtra(AqlCollectorService.EXTRA_LINK_COMPANION_BLE_ADDRESS)
        )
        sourceIntent.action = null
        AqlCollectorService.cancelCompanionLinkSuggestionNotification(this)
        CompanionDeviceHelper.markLinkSuggestionDismissed(this)
        if (CompanionDeviceHelper.hasAssociations(this)) {
            Toast.makeText(this, getString(R.string.toast_monitor_already_linked), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCompanionLinkRequested = true
        pendingCompanionLinkBleAddress = bleAddr
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
        activeHelpDialog?.dismiss()
        activeHelpDialog = null

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
            savedViewStartEpochSec = null
            savedViewEndEpochSec = null
            lastViewportCustomWindow = null
            if (::dualPaneSlicerView.isInitialized) {
                dualPaneSlicerView.resetZoom(notify = false)
            }
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
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setCancelable(onAccept == null)

        if (onAccept != null) {
            builder.setPositiveButton(R.string.disclosure_continue_btn) { _, _ -> onAccept() }
            builder.setNegativeButton(R.string.disclosure_decline_btn) { _, _ -> onDeclineLocation?.invoke() }
        } else {
            builder.setPositiveButton(R.string.dialog_close, null)
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
        val helpBtn = Button(this).apply {
            text = getString(R.string.header_help_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showHelpDialog() }
        }
        val settingsBtn = Button(this).apply {
            text = getString(R.string.header_settings_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { showSettingsModal() }
        }
        topHeaderBar.addView(titleView)
        topHeaderBar.addView(helpBtn)
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
        val tabTitles = listOf(
            getString(R.string.nav_tab_now),
            getString(R.string.nav_tab_trips),
            getString(R.string.dev_nav_tab_hw_test)
        )
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
            text = getString(R.string.now_context_waiting_initial)
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
            text = getString(R.string.now_co2_header_initial)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
        co2HeroValueView = TextView(this).apply {
            text = getString(R.string.now_co2_value_placeholder)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTypeface(typeface, Typeface.BOLD)
        }
        co2VentilationBadgeView = TextView(this).apply {
            text = getString(R.string.now_empty_onboarding_badge)
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        secondaryMetricsView = TextView(this).apply {
            text = getString(R.string.now_secondary_metrics_placeholder)
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
        recentGraphHeaderView = TextView(this).apply {
            text = getString(R.string.now_recent_history_header)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        liveFollowResetBtn = TextView(this).apply {
            text = getString(R.string.now_live_reset_btn)
            maxLines = 1
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#7DD3FC"))
            background = roundedRect("#0F172A", dp(6), strokeHex = "#38BDF8", strokeWidthPx = dp(1))
            visibility = View.GONE
            setOnClickListener { liveRecentHistoryView.snapToLiveEdge() }
        }
        val recentGraphHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(4))
            addView(recentGraphHeaderView)
            addView(liveFollowResetBtn)
        }
        col.addView(recentGraphHeaderRow)

        liveRecentHistoryView = DualPaneSlicerView(this).apply {
            isEditMode = false
            followLatestEdge = true
            desiredHeightDp = 265
            dropoutGracePeriodSec = getDropoutGracePeriodSec()
            emptyStateMessage = getString(R.string.now_recent_empty)
            onCursorChanged = { updateLiveGraphHeaderUi() }
            onLiveFollowStateChanged = { updateLiveGraphHeaderUi() }
            onTimelineNoteTapped = { note ->
                showEditOrPromoteNoteDialog(note, slice = null, sliceIdx = -1)
            }
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
            text = getString(R.string.now_wifi_checking)
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        saveCurrentWifiBtn = Button(this).apply {
            text = getString(R.string.now_save_wifi_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setOnClickListener { promptAddCurrentWifiToSavedPlaces() }
        }
        val tripActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        manualTripToggleBtn = Button(this).apply {
            text = getString(R.string.now_manual_trip_start_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.45f)
            setOnClickListener {
                val svc = collectorService ?: return@setOnClickListener
                val snap = svc.buildSnapshot()
                val isTripOpen = snap.activeExcursionId != null ||
                    snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ||
                    snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                if (isTripOpen) {
                    svc.endManualExcursion()
                    Toast.makeText(this@MainActivity, getString(R.string.toast_trip_ended), Toast.LENGTH_SHORT).show()
                } else {
                    svc.startManualExcursion()
                    Toast.makeText(this@MainActivity, getString(R.string.toast_trip_started), Toast.LENGTH_SHORT).show()
                }
                refreshLiveRecentGraph()
                refreshTripsTab()
            }
        }
        addNowNoteBtn = Button(this).apply {
            text = getString(R.string.now_add_note_btn)
            isAllCaps = false
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                val nowSec = System.currentTimeMillis() / 1000L
                val hasDetachedSelection = liveRecentHistoryView.slots.isNotEmpty() &&
                    !liveRecentHistoryView.isFollowingLiveEdge &&
                    liveRecentHistoryView.cursorEpochSec > 0L
                if (hasDetachedSelection) {
                    showAddNoteDialog(
                        capturedEpochSec = liveRecentHistoryView.cursorEpochSec,
                        isFromTripsTab = false,
                        alternateNowEpochSec = nowSec
                    )
                } else {
                    showAddNoteDialog(
                        capturedEpochSec = nowSec,
                        isFromTripsTab = false,
                        alternateNowEpochSec = null
                    )
                }
            }
        }
        tripActionRow.addView(manualTripToggleBtn)
        tripActionRow.addView(addNowNoteBtn)
        demoStreamBtn = Button(this).apply {
            text = getString(R.string.now_demo_stream_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            visibility = if (isDemoDataEnabled()) View.VISIBLE else View.GONE
            setOnClickListener {
                val running = collectorService?.toggleDemoStream() ?: false
                val msg = if (running) {
                    getString(R.string.toast_demo_replay_started)
                } else {
                    getString(R.string.toast_demo_replay_stopped)
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        wifiCard.addView(wifiStatusView)
        wifiCard.addView(saveCurrentWifiBtn)
        wifiCard.addView(tripActionRow)
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
            text = getString(R.string.trips_title_default)
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
            enableZoomAndMinimap = true
            desiredHeightDp = computePortraitSlicerHeightDp(resources.configuration.screenHeightDp)
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
            onViewportChanged = { _, _, _ ->
                updateZoomControlsUi()
            }
            onTimelineNoteTapped = { note ->
                val curSlices = slices
                val containingIdx = curSlices.indices.firstOrNull { idx ->
                    val s = curSlices[idx]
                    if (idx < curSlices.lastIndex) {
                        note.timestampEpochSec >= s.startEpochSec && note.timestampEpochSec < s.endEpochSec
                    } else {
                        note.timestampEpochSec >= s.startEpochSec && note.timestampEpochSec <= s.endEpochSec
                    }
                } ?: -1
                showEditOrPromoteNoteDialog(note, curSlices.getOrNull(containingIdx), containingIdx)
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

        cursorModeBtn = makeEditBarButton(getString(R.string.trips_cursor_btn), 1.2f) { switchToSplitCursorMode() }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        dividerNudgeBar.addView(makeEditBarButton(getString(R.string.trips_split_btn), 1.2f) { splitAtCurrentCursor() })
        dividerNudgeBar.addView(cursorModeBtn)
        dividerNudgeBar.addView(makeEditBarButton(getString(R.string.trips_nudge_minus_1m), 0.95f) { nudgeSelectedDivider(-60L) })
        dividerNudgeBar.addView(makeEditBarButton(getString(R.string.trips_nudge_minus_15s), 1f) { nudgeSelectedDivider(-15L) })
        dividerNudgeBar.addView(makeEditBarButton(getString(R.string.trips_nudge_plus_15s), 1f) { nudgeSelectedDivider(+15L) })
        dividerNudgeBar.addView(makeEditBarButton(getString(R.string.trips_nudge_plus_1m), 0.95f) { nudgeSelectedDivider(+60L) })

        if (landscape) {
            // LANDSCAPE MODE: Fit all 3 panes on screen at once with ZERO scrolling!
            // Segment list and 14d ring-buffer carving controls are omitted in Landscape.
            tripSelectorLabel.maxLines = 1
            tripSelectorLabel.ellipsize = TextUtils.TruncateAt.END
            zoomControlBar = null
            zoomStatusView = null
            zoomFullBtn = null
            zoom30mBtn = null
            zoom10mBtn = null
            zoom3mBtn = null
            zoomSegBtn = null

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
                text = getString(R.string.trips_switch_trip_btn)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener { showTripPickerDialog() }
            }
            val landZoomBtn = Button(this).apply {
                text = getString(R.string.trips_land_zoom_10m)
                isAllCaps = false
                maxLines = 1
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    if (dualPaneSlicerView.isViewportZoomed()) {
                        dualPaneSlicerView.resetZoom()
                    } else {
                        val (tStart, tEnd) = dualPaneSlicerView.getTripBoundsEpochSec()
                        val span = maxOf(DualPaneSlicerView.STEP_SEC, tEnd - tStart)
                        val target = if (span <= DualPaneSlicerView.ZOOM_PRESET_10M_SEC &&
                            span > DualPaneSlicerView.ZOOM_PRESET_3M_SEC
                        ) {
                            DualPaneSlicerView.ZOOM_PRESET_3M_SEC
                        } else {
                            DualPaneSlicerView.ZOOM_PRESET_10M_SEC
                        }
                        dualPaneSlicerView.zoomToWindowAroundCursor(target)
                    }
                }
            }
            val landSegBtn = Button(this).apply {
                text = getString(R.string.trips_focus_seg_btn)
                isAllCaps = false
                maxLines = 1
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener { focusCurrentSegmentOrDivider() }
            }
            val landNoteBtn = Button(this).apply {
                text = getString(R.string.trips_land_add_note_btn)
                isAllCaps = false
                maxLines = 1
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    val ts = if (dualPaneSlicerView.cursorEpochSec > 0L) {
                        dualPaneSlicerView.cursorEpochSec
                    } else {
                        System.currentTimeMillis() / 1000L
                    }
                    showAddNoteDialog(capturedEpochSec = ts, isFromTripsTab = true)
                }
            }
            landZoomToggleBtn = landZoomBtn
            landFocusSegBtn = landSegBtn
            landAddNoteBtn = landNoteBtn
            tripsAddNoteBtn = null

            compactTopBar.addView(tripSelectorLabel)
            compactTopBar.addView(switchTripBtn)
            compactTopBar.addView(landZoomBtn)
            compactTopBar.addView(landSegBtn)
            compactTopBar.addView(landNoteBtn)
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
            updateZoomControlsUi()
            return landRoot
        } else {
            // PORTRAIT MODE: Offer 14d ring-buffer time-window selector, View/Edit mode toggle,
            // 3-Pane Graph Stack with Overview Minimap + Quick Zoom bar, and clean Segment Cards.
            landZoomToggleBtn = null
            landFocusSegBtn = null
            landAddNoteBtn = null

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
                text = getString(R.string.trips_choose_trip_btn)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { showTripPickerDialog() }
            }
            val carveWindowBtn = Button(this).apply {
                text = getString(R.string.trips_14d_window_btn)
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
                text = getString(R.string.trips_pin_initial)
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

            val zStatus = TextView(this).apply {
                text = getString(R.string.trips_zoom_view_full)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            zoomStatusView = zStatus

            val addTripsNoteBtn = TextView(this).apply {
                text = getString(R.string.trips_portrait_add_note_btn)
                maxLines = 1
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#5EEAD4"))
                background = roundedRect("#0F172A", dp(6), strokeHex = "#14B8A6", strokeWidthPx = dp(1))
                setOnClickListener {
                    val ts = if (dualPaneSlicerView.cursorEpochSec > 0L) {
                        dualPaneSlicerView.cursorEpochSec
                    } else {
                        System.currentTimeMillis() / 1000L
                    }
                    showAddNoteDialog(capturedEpochSec = ts, isFromTripsTab = true)
                }
            }
            tripsAddNoteBtn = addTripsNoteBtn

            val zoomHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                addView(zStatus)
                addView(addTripsNoteBtn)
            }

            fun makeZoomBtn(label: String, weight: Float, onClick: () -> Unit): Button =
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    maxLines = 1
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    }
                    setOnClickListener { onClick() }
                }

            val fullBtn = makeZoomBtn(getString(R.string.trips_zoom_preset_full), 1f) { dualPaneSlicerView.resetZoom() }
            val z30Btn = makeZoomBtn(getString(R.string.trips_zoom_preset_30m), 1f) {
                dualPaneSlicerView.zoomToWindowAroundCursor(DualPaneSlicerView.ZOOM_PRESET_30M_SEC)
            }
            val z10Btn = makeZoomBtn(getString(R.string.trips_zoom_preset_10m), 1f) {
                dualPaneSlicerView.zoomToWindowAroundCursor(DualPaneSlicerView.ZOOM_PRESET_10M_SEC)
            }
            val z3Btn = makeZoomBtn(getString(R.string.trips_zoom_preset_3m), 1f) {
                dualPaneSlicerView.zoomToWindowAroundCursor(DualPaneSlicerView.ZOOM_PRESET_3M_SEC)
            }
            val segBtn = makeZoomBtn(getString(R.string.trips_focus_seg_btn), 1.15f) { focusCurrentSegmentOrDivider() }
            zoomFullBtn = fullBtn
            zoom30mBtn = z30Btn
            zoom10mBtn = z10Btn
            zoom3mBtn = z3Btn
            zoomSegBtn = segBtn

            val zoomBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(fullBtn)
                addView(z30Btn)
                addView(z10Btn)
                addView(z3Btn)
                addView(segBtn)
            }
            val zBar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                addView(zoomHeaderRow)
                addView(zoomBtnRow)
            }
            zoomControlBar = zBar
            col.addView(zBar)

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
            updateZoomControlsUi()
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
        modeToggleBtn.text = if (isTripEditMode) {
            getString(R.string.trips_edit_done_btn)
        } else {
            getString(R.string.trips_edit_trip_btn)
        }
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
        updateZoomControlsUi()
    }

    private fun focusCurrentSegmentOrDivider() {
        val slices = dualPaneSlicerView.slices
        if (slices.isEmpty() || dualPaneSlicerView.slots.isEmpty()) return
        val divIdx = dualPaneSlicerView.selectedDividerIndex
        val (tripStart, tripEnd) = dualPaneSlicerView.getTripBoundsEpochSec()
        val tripSpan = maxOf(DualPaneSlicerView.STEP_SEC, tripEnd - tripStart)
        if (isTripEditMode && divIdx in 0 until (slices.size - 1)) {
            val boundaryTs = slices[divIdx].endEpochSec
            val halfWindow = maxOf(
                DualPaneSlicerView.DIVIDER_FOCUS_MIN_HALF_SPAN_SEC,
                ((tripSpan * DualPaneSlicerView.DIVIDER_FOCUS_SPAN_FRACTION).toLong() / DualPaneSlicerView.STEP_SEC) * DualPaneSlicerView.STEP_SEC
            ).coerceAtMost(DualPaneSlicerView.DIVIDER_FOCUS_MAX_HALF_SPAN_SEC)
            dualPaneSlicerView.setViewportEpochSec(boundaryTs - halfWindow, boundaryTs + halfWindow)
            return
        }
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val targetSlice = slices.firstOrNull { cursorTs in it.startEpochSec..it.endEpochSec }
            ?: slices.first()
        dualPaneSlicerView.focusOnSegment(targetSlice.startEpochSec, targetSlice.endEpochSec)
    }

    private fun applyPendingSavedViewportIfNeeded() {
        val vs = savedViewStartEpochSec
        val ve = savedViewEndEpochSec
        savedViewStartEpochSec = null
        savedViewEndEpochSec = null
        if (vs != null && ve != null && ve > vs && dualPaneSlicerView.slots.isNotEmpty()) {
            dualPaneSlicerView.setViewportEpochSec(vs, ve, notify = false)
        }
    }

    private fun updateZoomControlsUi() {
        if (!::dualPaneSlicerView.isInitialized) return
        val hasSlots = dualPaneSlicerView.slots.isNotEmpty()
        val (tripStart, tripEnd) = dualPaneSlicerView.getTripBoundsEpochSec()
        val (viewStart, viewEnd) = dualPaneSlicerView.getEffectiveViewportEpochSec()
        val tripSpanSec = maxOf(0L, tripEnd - tripStart)
        val viewSpanSec = maxOf(0L, viewEnd - viewStart)
        val isZoomed = dualPaneSlicerView.isViewportZoomed()

        zoomControlBar?.visibility = if (hasSlots) View.VISIBLE else View.GONE
        zoomStatusView?.apply {
            if (isZoomed) {
                setTextColor(Color.parseColor(COLOR_ZOOM_STATUS_ACTIVE_HEX))
                text = getString(
                    R.string.trips_zoom_active,
                    formatElapsedAge(this@MainActivity, viewSpanSec),
                    formatElapsedAge(this@MainActivity, tripSpanSec)
                )
            } else {
                setTextColor(Color.parseColor(COLOR_ZOOM_STATUS_IDLE_HEX))
                text = getString(
                    R.string.trips_zoom_view_full_span,
                    formatElapsedAge(this@MainActivity, tripSpanSec)
                )
            }
        }

        fun stylePresetButton(btn: Button?, active: Boolean, enabledHint: Boolean) {
            btn ?: return
            btn.alpha = if (enabledHint) ENABLED_BUTTON_ALPHA else DISABLED_BUTTON_ALPHA
            btn.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (active) COLOR_ZOOM_BTN_ACTIVE_HEX else COLOR_ZOOM_BTN_IDLE_PORTRAIT_HEX)
            )
            btn.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#CBD5E1"))
        }

        stylePresetButton(zoomFullBtn, active = !isZoomed, enabledHint = hasSlots)
        stylePresetButton(
            zoom30mBtn,
            active = isZoomed && abs(viewSpanSec - DualPaneSlicerView.ZOOM_PRESET_30M_SEC) <= DualPaneSlicerView.STEP_SEC,
            enabledHint = hasSlots && tripSpanSec > DualPaneSlicerView.ZOOM_PRESET_30M_SEC
        )
        stylePresetButton(
            zoom10mBtn,
            active = isZoomed && abs(viewSpanSec - DualPaneSlicerView.ZOOM_PRESET_10M_SEC) <= DualPaneSlicerView.STEP_SEC,
            enabledHint = hasSlots && tripSpanSec > DualPaneSlicerView.ZOOM_PRESET_10M_SEC
        )
        stylePresetButton(
            zoom3mBtn,
            active = isZoomed && abs(viewSpanSec - DualPaneSlicerView.ZOOM_PRESET_3M_SEC) <= DualPaneSlicerView.STEP_SEC,
            enabledHint = hasSlots && tripSpanSec > DualPaneSlicerView.ZOOM_PRESET_3M_SEC
        )
        val canFocusSeg = hasSlots && (dualPaneSlicerView.slices.size > 1 ||
            (isTripEditMode && dualPaneSlicerView.selectedDividerIndex >= 0))
        stylePresetButton(zoomSegBtn, active = false, enabledHint = canFocusSeg)

        landZoomToggleBtn?.apply {
            visibility = if (hasSlots) View.VISIBLE else View.GONE
            text = when {
                isZoomed -> getString(R.string.trips_land_zoom_full)
                tripSpanSec in (DualPaneSlicerView.ZOOM_PRESET_3M_SEC + 1L)..DualPaneSlicerView.ZOOM_PRESET_10M_SEC ->
                    getString(R.string.trips_land_zoom_3m)
                else -> getString(R.string.trips_land_zoom_10m)
            }
            alpha = if (hasSlots && (isZoomed || tripSpanSec > DualPaneSlicerView.MIN_VIEWPORT_SPAN_SEC)) {
                ENABLED_BUTTON_ALPHA
            } else {
                DISABLED_BUTTON_ALPHA
            }
            backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (isZoomed) COLOR_ZOOM_BTN_ACTIVE_HEX else COLOR_ZOOM_BTN_IDLE_LANDSCAPE_HEX)
            )
            setTextColor(Color.WHITE)
        }
        landFocusSegBtn?.apply {
            visibility = if (hasSlots && dualPaneSlicerView.slices.isNotEmpty()) View.VISIBLE else View.GONE
            alpha = if (canFocusSeg) ENABLED_BUTTON_ALPHA else DISABLED_BUTTON_ALPHA
        }
        landAddNoteBtn?.visibility = if (hasSlots && !isTripEditMode) View.VISIBLE else View.GONE
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
            if (newPinned) {
                getString(R.string.toast_trip_pinned)
            } else {
                getString(R.string.toast_trip_unpinned_retention)
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateTripRetentionBanner(exc: ExcursionRecord?, slices: List<SessionSliceRecord>) {
        val banner = tripRetentionBanner ?: return
        if (exc == null || exc.isPinned || (!isTripEditMode && slices.size <= 1)) {
            banner.visibility = View.GONE
            return
        }
        val retentionSec = collectorService?.ringStore?.getArchiveWindowSec(0) ?: DEFAULT_RETENTION_WINDOW_SEC
        val remLabel = formatRemainingRetentionCompact(this, exc, System.currentTimeMillis() / 1000L, retentionSec)
        banner.text = getString(R.string.trips_retention_banner, remLabel)
        banner.visibility = View.VISIBLE
    }

    private fun updateEditHelperText() {
        if (!::editHelperView.isInitialized || !isTripEditMode) return
        val slices = dualPaneSlicerView.slices
        val divIdx = dualPaneSlicerView.selectedDividerIndex
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val cursorTime = if (cursorTs > 0L) {
            shortTimeFmt.format(Instant.ofEpochSecond(cursorTs))
        } else {
            getString(R.string.trips_edit_helper_tap_chart)
        }
        if (divIdx in 0 until slices.size - 1) {
            val boundaryTime = shortTimeFmt.format(Instant.ofEpochSecond(slices[divIdx].endEpochSec))
            editHelperView.setTextColor(Color.parseColor("#F97316"))
            editHelperView.text = getString(
                R.string.trips_edit_helper_divider,
                divIdx + 1,
                divIdx + 2,
                boundaryTime
            )
        } else {
            editHelperView.setTextColor(Color.parseColor("#38BDF8"))
            editHelperView.text = if (slices.size <= 1) {
                getString(R.string.trips_edit_helper_cursor_single, cursorTime)
            } else {
                getString(R.string.trips_edit_helper_cursor_multi, cursorTime)
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
            text = getString(R.string.dev_hw_header)
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
            text = getString(R.string.dev_hw_trace_path_info)
            setTextColor(Color.parseColor("#A7F3D0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        val shareTraceBtn = Button(this).apply {
            text = getString(R.string.dev_hw_share_trace_btn)
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
            text = getString(R.string.dev_hw_exp_title)
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
                strategies.map {
                    getString(R.string.dev_hw_strategy_spinner_item, it.id, it.getShortLabel(this@MainActivity))
                }
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
                intervalOptionsSec.map { getString(R.string.dev_hw_interval_spinner_item, it, it / 60.0) }
            )
            val currentInt = collectorService?.devConfig?.intervalSec ?: 210
            setSelection(intervalOptionsSec.indexOf(currentInt).let { if (it >= 0) it else 1 })
        }
        expCard.addView(intervalSpinner)

        val dedupCheck = CheckBox(this).apply {
            text = getString(R.string.dev_hw_dedup_check)
            isChecked = collectorService?.devConfig?.deduplicateFdcdTrace ?: true
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        expCard.addView(dedupCheck)

        val applyExpBtn = Button(this).apply {
            text = getString(R.string.dev_hw_apply_config_btn)
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
                    getString(R.string.dev_toast_hw_applied_config, chosenStrat.id, chosenInt),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        expCard.addView(applyExpBtn)

        val pokeNowBtn = Button(this).apply {
            text = getString(R.string.dev_hw_poke_now_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                collectorService?.triggerImmediateKeepAlivePoke("ui_poke_now")
            }
        }
        expCard.addView(pokeNowBtn)
        col.addView(expCard)

        hwPacketMetaView = TextView(this).apply {
            text = getString(R.string.dev_hw_packet_meta_waiting)
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
            text = getString(R.string.dev_hw_strict_filter_check)
            isChecked = true
            setTextColor(Color.parseColor("#E2E8F0"))
            setOnCheckedChangeListener { _, checked ->
                collectorService?.setStrictFdcdScanFilter(checked)
            }
        }
        col.addView(hwStrictFilterCheck)

        hwBleModeStatusView = TextView(this).apply {
            text = getString(R.string.dev_hw_ble_mode_initial)
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(hwBleModeStatusView)

        for (mode in BleCollectionMode.entries) {
            val btn = Button(this).apply {
                text = getString(R.string.dev_hw_mode_btn, mode.displayName)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener {
                    collectorService?.setExcursionBleMode(mode)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.dev_toast_hw_ble_mode, mode.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            col.addView(btn)
        }

        val gattInspectBtn = Button(this).apply {
            text = getString(R.string.dev_hw_gatt_inspect_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { collectorService?.triggerManualGattInspection() }
        }
        val gattDisconnectBtn = Button(this).apply {
            text = getString(R.string.dev_hw_gatt_disconnect_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { collectorService?.disconnectGatt() }
        }
        col.addView(gattInspectBtn)
        col.addView(gattDisconnectBtn)

        hwGattStatusView = TextView(this).apply {
            text = getString(R.string.dev_hw_gatt_status_initial)
            setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect("#1E293B", dp(8))
        }
        col.addView(hwGattStatusView)

        val rawLogTitle = TextView(this).apply {
            text = getString(R.string.dev_hw_raw_log_title)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        }
        col.addView(rawLogTitle)

        hwRawHexLogView = TextView(this).apply {
            text = getString(R.string.dev_hw_raw_log_empty)
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

        val sourceLabel: String? = when {
            snap.lastFrame == null -> null
            snap.lastFrame.isBtHomeV2 -> getString(R.string.now_source_bthome)
            else -> getString(R.string.now_source_qingping)
        }
        contextPillView.text = when (snap.collectorState) {
            ExcursionStateMachine.CollectorState.STATIONARY_KNOWN_AP ->
                if (snap.matchedKnownPlace != null) {
                    if (sourceLabel != null) {
                        getString(R.string.now_context_at_place_with_source, snap.matchedKnownPlace.label, sourceLabel)
                    } else {
                        getString(R.string.now_context_at_place, snap.matchedKnownPlace.label)
                    }
                } else {
                    if (sourceLabel != null) {
                        getString(R.string.now_context_monitoring_with_source, sourceLabel)
                    } else {
                        getString(R.string.now_context_monitoring)
                    }
                }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE -> {
                val movementBadge = when {
                    snap.isGpsActive && snap.isStepSensorActive -> getString(R.string.now_movement_gps_steps)
                    snap.isGpsActive -> getString(R.string.now_movement_gps)
                    snap.isStepSensorActive -> getString(R.string.now_movement_steps)
                    else -> getString(R.string.now_movement_gps_off)
                }
                if (sourceLabel != null) {
                    getString(R.string.now_context_on_trip_with_source, movementBadge, sourceLabel)
                } else {
                    getString(R.string.now_context_on_trip, movementBadge)
                }
            }
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED ->
                getString(R.string.now_context_trip_paused)
            ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ->
                getString(R.string.now_context_out_of_range)
        }

        if (snap.lastFrame != null) {
            val frameAgeSec = ((System.currentTimeMillis() - snap.lastFrame.timestampMs) / 1000L).coerceAtLeast(0L)
            val isStale = frameAgeSec > 45L ||
                snap.collectorState == ExcursionStateMachine.CollectorState.IDLE_OUT_OF_RANGE ||
                snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED

            val hasValidCo2 = snap.mergedCo2Ppm > 0
            val co2ValueStr = if (hasValidCo2) {
                getString(R.string.notif_status_co2_ppm, snap.mergedCo2Ppm)
            } else {
                getString(R.string.now_co2_value_placeholder)
            }

            if (isStale) {
                heroCardView.alpha = 0.74f
                heroCardView.background = roundedRect("#172033", dp(14))
                val formattedAge = formatElapsedAge(this, frameAgeSec)
                co2HeaderLabelView.text = getString(
                    R.string.now_co2_header_out_of_date,
                    formattedAge.uppercase(Locale.US)
                )
                co2HeaderLabelView.setTextColor(Color.parseColor("#FBBF24"))
                co2HeroValueView.text = co2ValueStr
                co2HeroValueView.setTextColor(Color.parseColor("#94A3B8"))
                val collectedAt = shortTimeFmt.format(Instant.ofEpochMilli(snap.lastFrame.timestampMs))
                co2VentilationBadgeView.text = getString(
                    R.string.now_co2_stale_badge,
                    collectedAt,
                    formattedAge
                )
                co2VentilationBadgeView.setTextColor(Color.parseColor("#FDE68A"))
                secondaryMetricsView.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                heroCardView.alpha = 1.0f
                heroCardView.background = roundedRect("#1E293B", dp(14))
                val liveAgeStr = if (frameAgeSec <= 3L) {
                    getString(R.string.now_live_age_just_now)
                } else {
                    getString(R.string.now_live_age_seconds, frameAgeSec)
                }
                co2HeaderLabelView.text = getString(R.string.now_co2_header_live, liveAgeStr)
                co2HeaderLabelView.setTextColor(Color.parseColor("#94A3B8"))
                co2HeroValueView.text = co2ValueStr
                co2VentilationBadgeView.setTextColor(Color.parseColor("#CBD5E1"))
                secondaryMetricsView.setTextColor(Color.parseColor("#E2E8F0"))
                when {
                    !hasValidCo2 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#94A3B8"))
                        co2VentilationBadgeView.text = getString(R.string.now_co2_warming_up)
                    }
                    snap.mergedCo2Ppm < 800 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#34D399"))
                        co2VentilationBadgeView.text = getString(R.string.now_co2_good)
                    }
                    snap.mergedCo2Ppm < 1200 -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#FBBF24"))
                        co2VentilationBadgeView.text = getString(R.string.now_co2_moderate)
                    }
                    else -> {
                        co2HeroValueView.setTextColor(Color.parseColor("#F87171"))
                        co2VentilationBadgeView.text = getString(R.string.now_co2_poor)
                    }
                }
            }
            val batStr = if (snap.mergedBatteryPct in 0..100) {
                getString(R.string.now_battery_pct, snap.mergedBatteryPct)
            } else {
                getString(R.string.now_battery_external)
            }
            val spdStr = when {
                snap.currentSpeedMps != null && snap.currentSpeedSource == StepMovementEstimator.SpeedSource.STEP_CADENCE -> {
                    val accSuffix = when {
                        snap.currentGpsAccuracyM != null ->
                            getString(R.string.now_speed_steps_gps, snap.currentGpsAccuracyM)
                        !snap.isGpsActive -> getString(R.string.now_speed_steps_gps_off)
                        else -> getString(R.string.now_speed_steps)
                    }
                    getString(R.string.now_speed_source_format, snap.currentSpeedMps, accSuffix)
                }
                snap.currentSpeedMps != null ->
                    getString(R.string.now_speed_gps_format, snap.currentSpeedMps, snap.currentGpsAccuracyM ?: 0f)
                snap.isStepSensorActive && !snap.isGpsActive -> getString(R.string.now_speed_steps_standby)
                else -> getString(R.string.now_speed_off)
            }
            val tempStr = TemperatureUnitFormatter.format(snap.mergedTempC, useF, decimals = 1)
            secondaryMetricsView.text = getString(
                R.string.now_secondary_metrics_format,
                snap.mergedPm25Ugm3,
                snap.mergedPm10Ugm3,
                tempStr,
                snap.mergedHumidityPct,
                batStr,
                spdStr
            )
        }

        val ssidStr = snap.connectedWifiSsid ?: getString(R.string.now_wifi_not_connected)
        val placeMatchStr = snap.matchedKnownPlace?.let {
            getString(R.string.now_wifi_place_saved, it.label)
        } ?: when {
            !snap.hasConfiguredKnownPlaces -> getString(R.string.now_wifi_place_none_configured)
            snap.connectedWifiSsid != null -> getString(R.string.now_wifi_place_unrecognized)
            else -> getString(R.string.now_wifi_place_none_matched)
        }
        wifiStatusView.text = getString(R.string.now_wifi_status_format, ssidStr, placeMatchStr)
        saveCurrentWifiBtn.visibility = if (!snap.connectedWifiSsid.isNullOrBlank() && snap.matchedKnownPlace == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val isTripOpen = snap.activeExcursionId != null ||
            snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ||
            snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
        manualTripToggleBtn.text = if (isTripOpen) {
            getString(R.string.now_manual_trip_end_btn)
        } else {
            getString(R.string.now_manual_trip_start_btn)
        }

        refreshLiveRecentGraph()

        val f = snap.lastFrame
        if (f != null) {
            val dtStr = snap.interPacketDeltaMs?.let { String.format(Locale.US, "%.2fs", it / 1000.0) }
                ?: getString(R.string.dev_hw_dt_first)
            hwPacketMetaView.text = getString(
                R.string.dev_hw_packet_meta_format,
                f.productName,
                Integer.toHexString(f.productId),
                f.macAddress,
                f.rssiDbm,
                dtStr,
                Integer.toHexString(f.frameControl),
                f.isEventOrBindingFrame.toString()
            )
        }
        val dc = snap.devConfig
        hwBleModeStatusView.text = getString(
            R.string.dev_hw_ble_mode_status_format,
            snap.userExcursionBleMode.displayName,
            snap.effectiveBleMode.name,
            dc.configEpoch,
            dc.strategy.id,
            dc.intervalSec,
            dc.deduplicateFdcdTrace.toString()
        )
        val gattLines = buildString {
            append(snap.gattConnectionStatus)
            if (snap.gattDiscoveredServicesLog.isNotEmpty()) {
                append("\n")
                append(snap.gattDiscoveredServicesLog.joinToString("\n"))
            }
        }
        hwGattStatusView.text = gattLines
        hwRawHexLogView.text = if (snap.recentRawFramesLog.isEmpty()) {
            getString(R.string.dev_hw_raw_log_waiting)
        } else {
            snap.recentRawFramesLog.joinToString("\n")
        }
    }

    private fun updateLiveGraphHeaderUi() {
        if (!::liveRecentHistoryView.isInitialized ||
            !::recentGraphHeaderView.isInitialized ||
            !::liveFollowResetBtn.isInitialized
        ) {
            return
        }
        val detached = liveRecentHistoryView.slots.isNotEmpty() &&
            !liveRecentHistoryView.isFollowingLiveEdge &&
            liveRecentHistoryView.cursorEpochSec > 0L
        if (detached) {
            recentGraphHeaderView.text = getString(
                R.string.now_recent_history_selected,
                exactTimeFmt.format(Instant.ofEpochSecond(liveRecentHistoryView.cursorEpochSec))
            )
            liveFollowResetBtn.visibility = View.VISIBLE
        } else {
            recentGraphHeaderView.text = getString(R.string.now_recent_history_header)
            liveFollowResetBtn.visibility = View.GONE
        }
    }

    private fun refreshLiveRecentGraph() {
        val svc = collectorService ?: return
        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = nowSec - 15L * 60L
        // Query last 15 minutes of 15s high-resolution samples to match the "Last 15 Minutes" header
        val recentSlots = svc.ringStore.queryRange(0, startSec, nowSec)
        val recentNotes = svc.metadataDb.getTimelineNotesInRange(startSec, nowSec)
        liveRecentHistoryView.fixedWindowStartEpochSec = startSec
        liveRecentHistoryView.fixedWindowEndEpochSec = nowSec
        liveRecentHistoryView.slices = emptyList()
        liveRecentHistoryView.timelineNotes = recentNotes
        liveRecentHistoryView.slots = recentSlots
        updateLiveGraphHeaderUi()
    }

    private fun refreshTripsTab() {
        val svc = collectorService ?: return

        // Check if user selected a custom 14-day ring buffer window to inspect
        val cwStart = customWindowStartEpochSec
        val cwEnd = customWindowEndEpochSec
        if (cwStart != null && cwEnd != null && cwEnd > cwStart) {
            val windowPair = cwStart to cwEnd
            if (lastViewportCustomWindow != windowPair) {
                lastViewportCustomWindow = windowPair
                lastViewportExcursionId = null
                dualPaneSlicerView.resetZoom(notify = false)
            }
            val slots = svc.ringStore.queryRange(0, cwStart, cwEnd)
            val windowNotes = svc.metadataDb.getTimelineNotesInRange(cwStart, cwEnd)
            val spanLabel = "${dateShortTimeFmt.format(Instant.ofEpochSecond(cwStart))} – ${shortTimeFmt.format(Instant.ofEpochSecond(cwEnd))}"
            tripSelectorLabel.text = getString(R.string.trips_window_title, spanLabel)
            pinTripBtn?.visibility = View.GONE
            manageTripBtn?.visibility = View.GONE
            tripRetentionBanner?.visibility = View.GONE
            dualPaneSlicerView.slices = emptyList()
            dualPaneSlicerView.timelineNotes = windowNotes
            dualPaneSlicerView.slots = slots
            applyPendingSavedViewportIfNeeded()
            updateZoomControlsUi()
            slicesContainer?.removeAllViews()
            slicesContainer?.let { container ->
                val carveBtn = Button(this).apply {
                    text = getString(R.string.trips_carve_window_btn)
                    isAllCaps = false
                    setOnClickListener {
                        carveCustomWindowAsTrip(cwStart, cwEnd)
                    }
                }
                container.addView(carveBtn)
                if (windowNotes.isNotEmpty()) {
                    val notesHdr = TextView(this).apply {
                        text = getString(R.string.trips_window_notes_header, windowNotes.size)
                        setTextColor(Color.parseColor("#5EEAD4"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(2), dp(8), dp(2), dp(4))
                    }
                    container.addView(notesHdr)
                    for (note in windowNotes) {
                        container.addView(buildTimelineNoteRowView(note, slice = null, sliceIdx = -1))
                    }
                }
            }
            return
        }

        val excursions = svc.metadataDb.getExcursions()
        if (excursions.isEmpty()) {
            selectedExcursionId = null
            lastSeenLatestExcursionId = null
            lastViewportExcursionId = null
            lastViewportCustomWindow = null
            tripSelectorLabel.text = getString(R.string.trips_none_recorded)
            pinTripBtn?.visibility = View.GONE
            manageTripBtn?.visibility = View.GONE
            tripRetentionBanner?.visibility = View.GONE
            dualPaneSlicerView.resetZoom(notify = false)
            dualPaneSlicerView.slots = emptyList()
            dualPaneSlicerView.slices = emptyList()
            dualPaneSlicerView.timelineNotes = emptyList()
            updateZoomControlsUi()
            slicesContainer?.removeAllViews()
            slicesContainer?.addView(Button(this).apply {
                text = getString(R.string.trips_import_xlsx_btn)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener {
                    launchXlsxImportPicker(timeShiftForDemo = false)
                }
            })
            if (isDemoDataEnabled()) {
                slicesContainer?.addView(Button(this).apply {
                    text = getString(R.string.trips_load_demo_btn)
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
                    text = getString(R.string.trips_demo_replay_xlsx_btn)
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnClickListener {
                        launchXlsxImportPicker(timeShiftForDemo = true)
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
        lastViewportCustomWindow = null
        if (lastViewportExcursionId != currentExc.id) {
            lastViewportExcursionId = currentExc.id
            dualPaneSlicerView.resetZoom(notify = false)
        }
        val slices = svc.metadataDb.getSlicesForExcursion(currentExc.id)
        val slots = svc.metadataDb.resolveSlotsForExcursion(currentExc.id, svc.ringStore)
        val excEndSec = currentExc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
        val tripNotes = svc.metadataDb.getTimelineNotesInRange(currentExc.startEpochSec, excEndSec)

        val remLabel = formatRemainingRetentionCompact(
            this,
            currentExc,
            System.currentTimeMillis() / 1000L,
            svc.ringStore.getArchiveWindowSec(0)
        )
        val pinBadge = if (currentExc.isPinned) "📌" else "⏳ $remLabel"
        tripSelectorLabel.text = if (isLandscape()) "${currentExc.title} [$pinBadge]" else currentExc.title
        manageTripBtn?.visibility = View.VISIBLE
        pinTripBtn?.apply {
            visibility = View.VISIBLE
            text = if (currentExc.isPinned) {
                getString(R.string.trips_pin_pinned)
            } else {
                getString(R.string.trips_pin_unpinned, remLabel)
            }
            if (currentExc.isPinned) {
                background = roundedRect("#1E293B", dp(6), strokeHex = "#334155", strokeWidthPx = dp(1))
                setTextColor(Color.parseColor("#94A3B8"))
            } else {
                background = roundedRect("#1E293B", dp(6), strokeHex = "#D97706", strokeWidthPx = dp(1))
                setTextColor(Color.parseColor("#FDE68A"))
            }
        }
        dualPaneSlicerView.slices = slices
        dualPaneSlicerView.timelineNotes = tripNotes
        dualPaneSlicerView.slots = slots
        applyPendingSavedViewportIfNeeded()
        updateEditHelperText()
        updateZoomControlsUi()
        updateTripRetentionBanner(currentExc, slices)

        if (!isLandscape()) {
            renderSliceCards(slices, slots, tripNotes)
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

    private fun buildTimelineNoteRowView(
        note: TimelineNoteRecord,
        slice: SessionSliceRecord?,
        sliceIdx: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            background = roundedRect("#0F172A", dp(6), strokeHex = "#14B8A6", strokeWidthPx = dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val labelTv = TextView(this).apply {
            text = "📝 ${exactTimeFmt.format(Instant.ofEpochSecond(note.timestampEpochSec))} — ${note.text}"
            setTextColor(Color.parseColor("#CCFBF1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dualPaneSlicerView.selectedDividerIndex = -1
                val (tripStart, tripEnd) = dualPaneSlicerView.getTripBoundsEpochSec()
                val snapped = ((note.timestampEpochSec + DualPaneSlicerView.STEP_SEC / 2L) / DualPaneSlicerView.STEP_SEC) * DualPaneSlicerView.STEP_SEC
                val clamped = snapped.coerceIn(tripStart, tripEnd)
                dualPaneSlicerView.cursorEpochSec = clamped
                dualPaneSlicerView.ensureTimestampVisibleInViewport(clamped, centerIfOutside = true)
                updateEditModeUi()
                updateSliceCardHighlights(clamped)
                dualPaneSlicerView.invalidate()
                (tabContainers.getOrNull(1) as? ScrollView)?.smoothScrollTo(0, 0)
            }
        }
        row.addView(labelTv)

        val canPromote = slice != null &&
            MetadataDatabase.computeAlignedSplitForNote(
                note.timestampEpochSec,
                slice.startEpochSec,
                slice.endEpochSec
            ) != null
        if (canPromote && slice != null) {
            val splitBtn = Button(this).apply {
                text = getString(R.string.trips_split_btn)
                isAllCaps = false
                maxLines = 1
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(6), dp(3), dp(6), dp(3))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
                setOnClickListener { showPromoteNoteToSplitDialog(note, slice, sliceIdx) }
            }
            row.addView(splitBtn)
        }

        val editNoteBtn = Button(this).apply {
            text = "✏️"
            isAllCaps = false
            maxLines = 1
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            setOnClickListener { showEditOrPromoteNoteDialog(note, slice, sliceIdx) }
        }
        row.addView(editNoteBtn)
        return row
    }

    private fun renderSliceCards(
        slices: List<SessionSliceRecord>,
        allSlots: List<AqlRingBufferStore.RingSlotRecord>,
        timelineNotes: List<TimelineNoteRecord> = emptyList()
    ) {
        val container = slicesContainer ?: return
        container.removeAllViews()
        val cardsList = mutableListOf<Pair<SessionSliceRecord, LinearLayout>>()
        val cursorTs = dualPaneSlicerView.cursorEpochSec
        val notesBySliceId = MetadataDatabase.assignNotesToSlices(slices, timelineNotes)

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
                    dualPaneSlicerView.ensureTimestampVisibleInViewport(
                        dualPaneSlicerView.cursorEpochSec,
                        centerIfOutside = true
                    )
                    updateEditModeUi()
                    updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                    dualPaneSlicerView.invalidate()
                }
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spanDuration = formatElapsedAge(this, (slice.endEpochSec - slice.startEpochSec).coerceAtLeast(0L))
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
                    text = getString(R.string.trips_drop_slice_btn, if (isStart) 1 else idx + 1)
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setOnClickListener { confirmAndTrimSlice(slice, isStart = isStart, idx = idx) }
                }
                headerRow.addView(dropBtn)
            }
            if (slices.size > 1) {
                val focusBtn = Button(this).apply {
                    text = "🔍"
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setOnClickListener {
                        dualPaneSlicerView.selectedDividerIndex = -1
                        val mid = (((slice.startEpochSec + slice.endEpochSec) / 2L) / 15L) * 15L
                        dualPaneSlicerView.cursorEpochSec = mid.coerceIn(slice.startEpochSec, slice.endEpochSec)
                        dualPaneSlicerView.focusOnSegment(slice.startEpochSec, slice.endEpochSec)
                        updateEditModeUi()
                        updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
                        (tabContainers.getOrNull(1) as? ScrollView)?.smoothScrollTo(0, 0)
                    }
                }
                headerRow.addView(focusBtn)
            }
            val editSegmentBtn = Button(this).apply {
                text = getString(R.string.trips_edit_slice_btn)
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
                text = getString(
                    R.string.trips_slice_meta,
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

            val sliceTimelineNotes = notesBySliceId[slice.id].orEmpty()
            for (note in sliceTimelineNotes) {
                card.addView(buildTimelineNoteRowView(note, slice, idx))
            }

            val controlsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val exportSliceBtn = Button(this).apply {
                text = getString(R.string.trips_export_slice_btn)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnClickListener {
                    exportSliceToXlsx(slice, idx + 1, sliceSlots)
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
                text = getString(R.string.trips_slice_empty_samples)
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
                label = getString(R.string.trips_stats_row_co2),
                colorHex = "#38BDF8",
                p50 = stats.co2Median.toString(),
                p95 = stats.co2P95.toString(),
                max = stats.co2Max.toString(),
                avg = String.format(Locale.US, "%.0f", stats.co2Mean)
            ),
            SliceStatRowSpec(
                label = getString(R.string.trips_stats_row_pm25),
                colorHex = "#A78BFA",
                p50 = String.format(Locale.US, "%.1f", stats.pm25P50),
                p95 = String.format(Locale.US, "%.1f", stats.pm25P95),
                max = stats.pm25Max.toString(),
                avg = String.format(Locale.US, "%.1f", stats.pm25Mean)
            ),
            SliceStatRowSpec(
                label = getString(R.string.trips_stats_row_pm10),
                colorHex = "#C084FC",
                p50 = String.format(Locale.US, "%.1f", stats.pm10P50),
                p95 = String.format(Locale.US, "%.1f", stats.pm10P95),
                max = stats.pm10Max.toString(),
                avg = String.format(Locale.US, "%.1f", stats.pm10Mean)
            ),
            SliceStatRowSpec(
                label = getString(R.string.trips_stats_row_temp, tempUnit),
                colorHex = "#FB923C",
                p50 = fmtTemp(stats.tempCP50),
                p95 = fmtTemp(stats.tempCP95),
                max = fmtTemp(stats.tempCMax),
                avg = fmtTemp(stats.tempCMean),
                highlightMedian = true
            ),
            SliceStatRowSpec(
                label = getString(R.string.trips_stats_row_rh),
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
        header.addView(makeCell(getString(R.string.trips_stats_col_metric), 1.55f, alignEnd = false, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell(getString(R.string.trips_stats_col_p50), 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell(getString(R.string.trips_stats_col_p95), 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell(getString(R.string.trips_stats_col_max), 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
        header.addView(makeCell(getString(R.string.trips_stats_col_avg), 1.0f, alignEnd = true, colorHex = "#94A3B8", bold = true))
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
            text = getString(R.string.dialog_segment_label_prompt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.dialog_segment_label_hint)
            setText(slice.label)
        }
        layout.addView(labelTitle)
        layout.addView(labelInput)

        // Optional quick-fill presets inside the modal (populates the freeform fields without forcing preset creation)
        val presetsHint = TextView(this).apply {
            text = getString(R.string.dialog_segment_presets_hint)
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
            text = getString(R.string.dialog_segment_notes_prompt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, dp(2))
        }
        val notesInput = EditText(this).apply {
            hint = getString(R.string.dialog_segment_notes_hint)
            setText(slice.notes)
        }
        layout.addView(notesTitle)
        layout.addView(notesInput)

        val allSlices = dualPaneSlicerView.slices
        var dropBoundaryBtn: Button? = null
        if (allSlices.size >= 2 && (sliceIdx == 0 || sliceIdx == allSlices.size - 1)) {
            val isStart = (sliceIdx == 0)
            dropBoundaryBtn = Button(this).apply {
                text = if (isStart) {
                    getString(R.string.dialog_segment_drop_start, sliceIdx + 1)
                } else {
                    getString(R.string.dialog_segment_drop_end, sliceIdx + 1)
                }
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            layout.addView(dropBoundaryBtn)
        }

        val spanTitle = "${shortTimeFmt.format(Instant.ofEpochSecond(slice.startEpochSec))}–${shortTimeFmt.format(Instant.ofEpochSecond(slice.endEpochSec))}"
        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_segment_edit_title, sliceIdx + 1, spanTitle))
            .setView(layout)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val newLabel = labelInput.text.toString().trim()
                    .ifEmpty { getString(R.string.dialog_segment_default_label) }
                val newNotes = notesInput.text.toString().trim()
                svc.metadataDb.updateSliceMetadata(
                    sliceId = slice.id,
                    label = newLabel,
                    notes = newNotes,
                    includeInExport = slice.includeInExport
                )
                refreshTripsTab()
            }
            .setNegativeButton(R.string.dialog_cancel, null)

        if (nextSlice != null) {
            dialogBuilder.setNeutralButton(
                getString(R.string.dialog_segment_merge_btn, sliceIdx + 1, sliceIdx + 2)
            ) { _, _ ->
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
            text = getString(R.string.dialog_14d_window_info)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(infoTv)

        val dayLabels = (0..13).map { daysAgo ->
            val date = LocalDate.now().minusDays(daysAgo.toLong())
            when (daysAgo) {
                0 -> getString(R.string.dialog_14d_day_today, date.toString())
                1 -> getString(R.string.dialog_14d_day_yesterday, date.toString())
                else -> getString(R.string.dialog_14d_day_days_ago, daysAgo, date.toString())
            }
        }
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dayLabels)
        }

        val hourLabels = (0..47).map { halfHourIdx ->
            val h = halfHourIdx / 2
            val m = if (halfHourIdx % 2 == 0) 0 else 30
            getString(R.string.dialog_14d_start_at, h, m)
        }
        val nowZdt = ZonedDateTime.now()
        val defaultHalfHourIdx = ((nowZdt.hour * 2 + (if (nowZdt.minute >= 30) 1 else 0)) - 3).coerceIn(0, 47)
        val startHourSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, hourLabels)
            setSelection(defaultHalfHourIdx)
        }

        val durationMinutesOptions = listOf(30, 60, 90, 120, 180, 240, 360)
        val durationLabels = durationMinutesOptions.map { mins ->
            if (mins < 60) {
                getString(R.string.dialog_14d_duration_mins, mins)
            } else {
                getString(R.string.dialog_14d_duration_hours, mins / 60.0)
            }
        }
        val durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, durationLabels)
            setSelection(2) // Default 90 mins
        }

        layout.addView(daySpinner)
        layout.addView(startHourSpinner)
        layout.addView(durationSpinner)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_14d_window_title)
            .setView(layout)
            .setPositiveButton(R.string.dialog_14d_carve_btn) { _, _ ->
                val (startSec, endSec) = computeWindowEpochRange(
                    daySpinner.selectedItemPosition,
                    startHourSpinner.selectedItemPosition,
                    durationMinutesOptions[durationSpinner.selectedItemPosition]
                )
                carveCustomWindowAsTrip(startSec, endSec)
            }
            .setNeutralButton(R.string.dialog_14d_preview_btn) { _, _ ->
                val (startSec, endSec) = computeWindowEpochRange(
                    daySpinner.selectedItemPosition,
                    startHourSpinner.selectedItemPosition,
                    durationMinutesOptions[durationSpinner.selectedItemPosition]
                )
                customWindowStartEpochSec = startSec
                customWindowEndEpochSec = endSec
                refreshTripsTab()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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
        val title = getString(R.string.trips_carved_title, dateShortTimeFmt.format(Instant.ofEpochSecond(startSec)))
        val exc = svc.metadataDb.startExcursion(deviceId = 1L, startEpochSec = startSec, title = title, isManual = true)
        svc.metadataDb.updateOngoingExcursionEnd(exc.id, endSec, closeExcursion = true, ringStore = svc.ringStore)
        svc.metadataDb.setExcursionPinned(exc.id, true, svc.ringStore)
        customWindowStartEpochSec = null
        customWindowEndEpochSec = null
        selectedExcursionId = exc.id
        refreshTripsTab()
        Toast.makeText(this, getString(R.string.toast_carved_pinned, title), Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // SETTINGS SCREEN (DISPLAY, PLACES, ALERTS, ADVANCED, HELP & PRIVACY)
    // =========================================================================
    private fun showSettingsModal() {
        val svc = collectorService ?: return
        activeSettingsDialog?.dismiss()
        val settingsDialog = SettingsScreenDialog(
            context = this,
            service = svc,
            callbacks = object : SettingsScreenDialog.Callbacks {
                override fun isDeveloperModeEnabled(): Boolean = this@MainActivity.isDeveloperModeEnabled()

                override fun setDeveloperModeEnabled(enabled: Boolean) {
                    this@MainActivity.setDeveloperModeEnabled(enabled)
                }

                override fun isDemoDataEnabled(): Boolean = this@MainActivity.isDemoDataEnabled()

                override fun setDemoDataEnabled(enabled: Boolean) {
                    this@MainActivity.setDemoDataEnabled(enabled)
                }

                override fun getDropoutGracePeriodSec(): Long = this@MainActivity.getDropoutGracePeriodSec()

                override fun setDropoutGracePeriodSec(sec: Long) {
                    this@MainActivity.setDropoutGracePeriodSec(sec)
                }

                override fun getCompanionAssociationStatus(): Pair<Boolean, String?> {
                    val supported = CompanionDeviceHelper.isSupported(this@MainActivity)
                    val summary = if (supported) {
                        CompanionDeviceHelper.formatAssociationSummary(
                            CompanionDeviceHelper.getAssociations(this@MainActivity)
                        )
                    } else {
                        null
                    }
                    return supported to summary
                }

                override fun onLinkCompanionDeviceRequested() {
                    requestCompanionDeviceAssociation()
                }

                override fun onUnlinkCompanionDeviceRequested() {
                    unlinkCompanionDevices()
                }

                override fun onAddWifiPlaceRequested(onSaved: () -> Unit) {
                    promptAddCurrentWifiToSavedPlaces(onSaved)
                }

                override fun onAddSegmentPresetRequested(onSaved: () -> Unit) {
                    promptAddSegmentPreset(onSaved)
                }

                override fun onEditAlertThresholdsRequested(onSaved: () -> Unit) {
                    promptEditAlertThresholds(onSaved)
                }

                override fun onShowHelpRequested() {
                    showHelpDialog()
                }

                override fun onShareDiagnosticLogRequested() {
                    shareHardwareDiagnosticTrace()
                }

                override fun onShowPrivacyDisclosureRequested() {
                    showProminentLocationDisclosureDialog()
                }

                override fun onEnablePermissionsRequested() {
                    requestRuntimePermissionsIfNeeded(forcePrompt = true)
                }

                override fun onStopServiceAndExitRequested() {
                    val currentSvc = collectorService ?: svc
                    isExitingApp = true
                    activeSettingsDialog?.dismiss()
                    activeSettingsDialog = null
                    currentSvc.removeUserStopListener(serviceUserStopListener)
                    currentSvc.requestUserStop(
                        reason = "settings_exit",
                        allowDuringActiveExcursion = true
                    )
                    completeAppExit()
                }

                override fun onSettingsDismissed() {
                    activeSettingsDialog = null
                    refreshTripsTab()
                    renderLiveSnapshot(svc.buildSnapshot())
                    dualPaneSlicerView.invalidate()
                    liveRecentHistoryView.invalidate()
                }
            }
        )
        activeSettingsDialog = settingsDialog
        settingsDialog.show()
    }

    private fun requestCompanionDeviceAssociation(preferredBleAddress: String? = null) {
        if (!CompanionDeviceHelper.isSupported(this)) {
            Toast.makeText(this, getString(R.string.toast_companion_not_supported), Toast.LENGTH_LONG).show()
            return
        }
        val cdm = CompanionDeviceHelper.getManager(this) ?: return
        if (!AqlCollectorService.hasBluetoothScanPermission(this)) {
            requestRuntimePermissionsIfNeeded(forcePrompt = true)
            Toast.makeText(this, getString(R.string.toast_grant_bt_first), Toast.LENGTH_LONG).show()
            return
        }
        val targetMac = CompanionDeviceHelper.normalizeBluetoothMac(preferredBleAddress)
            ?: collectorService?.getRecentActiveLinkableMonitor()?.bleAddress
        val usedSingleDevice = CompanionDeviceHelper.shouldUseSingleDeviceRequest(targetMac)
        val request = CompanionDeviceHelper.buildAssociationRequest(targetMac)
        try {
            cdm.associate(
                request,
                mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: android.content.IntentSender) {
                        runOnUiThread {
                            runCatching {
                                companionAssociationLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }.onFailure { err ->
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.toast_companion_picker_failed, err.message ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        runOnUiThread {
                            onCompanionAssociated(associationInfo)
                        }
                    }

                    override fun onFailure(error: CharSequence?) {
                        runOnUiThread {
                            val msg = if (usedSingleDevice && targetMac != null) {
                                getString(R.string.toast_companion_unreachable, targetMac)
                            } else {
                                error?.toString()?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.toast_companion_cancelled)
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_companion_start_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onCompanionAssociated(associationInfo: AssociationInfo?) {
        val assoc = associationInfo ?: CompanionDeviceHelper.getAssociations(this).lastOrNull()
        val observing = assoc != null && CompanionDeviceHelper.startObservingPresence(this, assoc)
        AqlCollectorService.cancelCompanionLinkSuggestionNotification(this)
        CompanionDeviceHelper.markLinkSuggestionDismissed(this)
        collectorService?.refreshCompanionAssociationCache()
        activeSettingsDialog?.refreshCompanionAssociationState()
        val label = assoc?.let {
            CompanionDeviceHelper.formatAssociationLabel(it.displayName, it.deviceMacAddress?.toString())
        } ?: getString(R.string.companion_default_monitor_label)
        val msg = if (observing) {
            getString(R.string.toast_companion_linked_observing, label)
        } else {
            getString(R.string.toast_companion_linked_no_presence, label)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun unlinkCompanionDevices() {
        CompanionDeviceHelper.disassociateAll(this)
        collectorService?.refreshCompanionAssociationCache()
        activeSettingsDialog?.refreshCompanionAssociationState()
        Toast.makeText(this, getString(R.string.toast_companion_unlinked), Toast.LENGTH_SHORT).show()
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, getString(R.string.toast_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun showHelpDialog() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }

        val introView = TextView(this).apply {
            text = getString(R.string.help_intro)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        col.addView(introView)

        val demoActionBtn = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            fun syncLabel() {
                text = if (isDemoDataEnabled()) {
                    getString(R.string.help_demo_enabled_btn)
                } else {
                    getString(R.string.help_demo_disabled_btn)
                }
            }
            syncLabel()
            setOnClickListener {
                val nextEnabled = !isDemoDataEnabled()
                setDemoDataEnabled(nextEnabled)
                refreshTripsTab()
                activeSettingsDialog?.refreshDemoModeState()
                syncLabel()
                val msg = if (nextEnabled) {
                    getString(R.string.toast_demo_enabled)
                } else {
                    getString(R.string.toast_demo_disabled)
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
        col.addView(demoActionBtn)

        val linkHeader = TextView(this).apply {
            text = getString(R.string.help_links_header)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(14), 0, dp(4))
        }
        col.addView(linkHeader)

        val links = listOf(
            getString(R.string.help_link_faq) to FAQ_DOC_URL,
            getString(R.string.help_link_slicing) to SLICING_GUIDE_URL,
            getString(R.string.help_link_hardware) to HARDWARE_GUIDE_URL,
            getString(R.string.help_link_group) to USERS_GROUP_URL
        )
        for ((label, url) in links) {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setOnClickListener { openExternalUrl(url) }
            }
            col.addView(btn)
        }

        scroll.addView(col)
        activeHelpDialog?.dismiss()
        activeHelpDialog = AlertDialog.Builder(this)
            .setTitle(R.string.help_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_close, null)
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
                    getString(R.string.toast_split_switched_cursor),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_split_tap_inside), Toast.LENGTH_SHORT).show()
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
            dualPaneSlicerView.ensureTimestampVisibleInViewport(
                dualPaneSlicerView.cursorEpochSec,
                centerIfOutside = false
            )
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
            dualPaneSlicerView.ensureTimestampVisibleInViewport(
                dualPaneSlicerView.cursorEpochSec,
                centerIfOutside = false
            )
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
            Toast.makeText(this, getString(R.string.toast_trim_outside_bounds), Toast.LENGTH_SHORT).show()
            return
        }
        dualPaneSlicerView.selectedDividerIndex = -1
        refreshTripsTab()
        val remaining = dualPaneSlicerView.slices
        if (remaining.isNotEmpty()) {
            val focusSlice = if (trimStart) remaining.first() else remaining.last()
            val mid = (((focusSlice.startEpochSec + focusSlice.endEpochSec) / 2L) / 15L) * 15L
            dualPaneSlicerView.cursorEpochSec = mid.coerceIn(focusSlice.startEpochSec, focusSlice.endEpochSec)
            dualPaneSlicerView.ensureTimestampVisibleInViewport(
                dualPaneSlicerView.cursorEpochSec,
                centerIfOutside = false
            )
        }
        updateEditModeUi()
        updateSliceCardHighlights(dualPaneSlicerView.cursorEpochSec)
        dualPaneSlicerView.invalidate()
    }

    private fun confirmAndTrimSlice(slice: SessionSliceRecord, isStart: Boolean, idx: Int) {
        val boundaryTs = if (isStart) slice.endEpochSec else slice.startEpochSec
        val boundaryTime = shortTimeFmt.format(Instant.ofEpochSecond(boundaryTs))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_trim_title, idx + 1, slice.label))
            .setMessage(
                getString(
                    if (isStart) R.string.dialog_trim_msg_start else R.string.dialog_trim_msg_end,
                    boundaryTime
                )
            )
            .setPositiveButton(R.string.dialog_trim_confirm_btn) { _, _ ->
                applyExcursionTrim(slice.excursionId, boundaryTs, trimStart = isStart)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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
            text = getString(R.string.dialog_manage_trip_title_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.dialog_manage_trip_title_hint)
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
            text = getString(R.string.dialog_manage_trip_pin_check)
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
                pinned -> getString(R.string.dialog_manage_trip_pin_forever)
                outsideWindow -> getString(R.string.dialog_manage_trip_pin_outside)
                else -> getString(R.string.dialog_manage_trip_pin_temporary)
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

        var dialogRef: AlertDialog? = null
        val exportTripBtn = Button(this).apply {
            text = getString(R.string.dialog_manage_trip_export_btn)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                val editedTitle = titleInput.text.toString().trim()
                    .ifEmpty { getString(R.string.dialog_manage_trip_default_title) }
                if (editedTitle != exc.title) {
                    svc.metadataDb.renameExcursion(exc.id, editedTitle)
                    refreshTripsTab()
                }
                val updatedExc = svc.metadataDb.getExcursions().firstOrNull { it.id == exc.id }
                    ?: exc.copy(title = editedTitle)
                dialogRef?.dismiss()
                exportExcursionToXlsx(updatedExc)
            }
        }
        layout.addView(exportTripBtn)

        dialogRef = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_manage_trip_title)
            .setView(layout)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val newTitle = titleInput.text.toString().trim()
                    .ifEmpty { getString(R.string.dialog_manage_trip_default_title) }
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
            .setNeutralButton(R.string.dialog_manage_trip_delete_btn) { _, _ ->
                confirmDeleteTrip(exc)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteTrip(exc: ExcursionRecord, initiatedByUnpin: Boolean = false) {
        val svc = collectorService ?: return
        val outsideWindow = svc.metadataDb.isExcursionOutsideRingWindow(exc, svc.ringStore)
        val dialogTitle = if (initiatedByUnpin) {
            getString(R.string.dialog_delete_trip_title_unpin, exc.title)
        } else {
            getString(R.string.dialog_delete_trip_title, exc.title)
        }
        val dialogMessage = when {
            outsideWindow && initiatedByUnpin -> getString(R.string.dialog_delete_trip_msg_outside_unpin)
            outsideWindow -> getString(R.string.dialog_delete_trip_msg_outside_delete)
            else -> getString(R.string.dialog_delete_trip_msg_inside)
        }
        val positiveLabel = when {
            initiatedByUnpin -> getString(R.string.dialog_delete_trip_btn_unpin)
            outsideWindow -> getString(R.string.dialog_delete_trip_btn_permanent)
            else -> getString(R.string.dialog_delete_trip_btn)
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
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAddNoteDialog(
        capturedEpochSec: Long,
        isFromTripsTab: Boolean,
        alternateNowEpochSec: Long? = null
    ) {
        val svc = collectorService ?: return
        val snap = svc.buildSnapshot()
        val excursions = svc.metadataDb.getExcursions()

        var targetEpochSec = capturedEpochSec
        var dialogRef: AlertDialog? = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }

        val warnView: TextView? = if (!isFromTripsTab) {
            TextView(this).apply {
                setTextColor(Color.parseColor("#FDE68A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(8))
            }
        } else {
            null
        }

        fun updateRetentionWarning(tsSec: Long) {
            val wv = warnView ?: return
            val nowRefSec = alternateNowEpochSec ?: (System.currentTimeMillis() / 1000L)
            val coveredByRecordedTrip = excursions.any { exc ->
                tsSec in exc.startEpochSec..(exc.endEpochSec ?: nowRefSec)
            }
            val isLiveNowTarget = (alternateNowEpochSec == null || tsSec == alternateNowEpochSec)
            val isTripActiveNow = snap.activeExcursionId != null ||
                snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ||
                snap.collectorState == ExcursionStateMachine.CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
            when {
                coveredByRecordedTrip || (isLiveNowTarget && isTripActiveNow) -> {
                    wv.visibility = View.GONE
                }
                isLiveNowTarget && snap.isPendingWifiLossDebounce -> {
                    wv.text = getString(R.string.dialog_note_warn_debounce)
                    wv.visibility = View.VISIBLE
                }
                else -> {
                    wv.text = if (isLiveNowTarget) {
                        getString(R.string.dialog_note_warn_no_live_trip)
                    } else {
                        getString(R.string.dialog_note_warn_outside_trip)
                    }
                    wv.visibility = View.VISIBLE
                }
            }
        }

        if (!isFromTripsTab && alternateNowEpochSec != null && alternateNowEpochSec != capturedEpochSec) {
            val selectedLabel = getString(
                R.string.dialog_note_target_selected,
                exactTimeFmt.format(Instant.ofEpochSecond(capturedEpochSec))
            )
            val nowLabel = getString(
                R.string.dialog_note_target_now,
                exactTimeFmt.format(Instant.ofEpochSecond(alternateNowEpochSec))
            )
            lateinit var selectedPill: TextView
            lateinit var nowPill: TextView

            fun styleTargetPill(pill: TextView, active: Boolean) {
                if (active) {
                    pill.setTextColor(Color.parseColor("#5EEAD4"))
                    pill.setTypeface(Typeface.DEFAULT_BOLD)
                    pill.background = roundedRect("#0F172A", dp(6), strokeHex = "#14B8A6", strokeWidthPx = dp(1))
                } else {
                    pill.setTextColor(Color.parseColor("#CBD5E1"))
                    pill.setTypeface(Typeface.DEFAULT)
                    pill.background = roundedRect("#1E293B", dp(6), strokeHex = "#475569", strokeWidthPx = dp(1))
                }
            }

            fun selectTargetTimestamp(newTs: Long) {
                targetEpochSec = newTs
                val selectedActive = (newTs == capturedEpochSec)
                styleTargetPill(selectedPill, selectedActive)
                styleTargetPill(nowPill, !selectedActive)
                dialogRef?.setTitle(
                    getString(R.string.dialog_note_add_title, exactTimeFmt.format(Instant.ofEpochSecond(newTs)))
                )
                updateRetentionWarning(newTs)
            }

            selectedPill = TextView(this).apply {
                text = selectedLabel
                maxLines = 2
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener { selectTargetTimestamp(capturedEpochSec) }
            }
            nowPill = TextView(this).apply {
                text = nowLabel
                maxLines = 2
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                }
                setOnClickListener { selectTargetTimestamp(alternateNowEpochSec) }
            }
            styleTargetPill(selectedPill, true)
            styleTargetPill(nowPill, false)
            val toggleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                addView(selectedPill)
                addView(nowPill)
            }
            container.addView(toggleRow)
        }

        if (warnView != null) {
            updateRetentionWarning(targetEpochSec)
            container.addView(warnView)
        }

        val input = EditText(this).apply {
            hint = getString(R.string.dialog_note_input_hint, MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT)
            setSingleLine(false)
            maxLines = 3
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        container.addView(input)

        val presets = svc.metadataDb.getSegmentPresets()
        if (presets.isNotEmpty()) {
            val chipScroll = HorizontalScrollView(this).apply {
                setPadding(0, dp(6), 0, 0)
            }
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (preset in presets) {
                val chip = Button(this).apply {
                    text = "${preset.iconEmoji} ${preset.label}"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    minWidth = 0
                    minimumWidth = 0
                    setOnClickListener {
                        input.setText("${preset.iconEmoji} ${preset.label}")
                        input.setSelection(input.text.length)
                    }
                }
                chipRow.addView(chip)
            }
            chipScroll.addView(chipRow)
            container.addView(chipScroll)
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_note_add_title, exactTimeFmt.format(Instant.ofEpochSecond(targetEpochSec))))
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val text = input.text.toString().trim().ifEmpty { MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT }
                svc.metadataDb.addTimelineNote(targetEpochSec, text)
                refreshLiveRecentGraph()
                refreshTripsTab()
                Toast.makeText(
                    this,
                    getString(R.string.toast_note_saved, exactTimeFmt.format(Instant.ofEpochSecond(targetEpochSec))),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showEditOrPromoteNoteDialog(
        note: TimelineNoteRecord,
        slice: SessionSliceRecord?,
        sliceIdx: Int
    ) {
        val svc = collectorService ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        val input = EditText(this).apply {
            setText(note.text)
            setSelection(text.length)
            setSingleLine(false)
            maxLines = 3
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        container.addView(input)

        val alignedSplit = if (slice != null) {
            MetadataDatabase.computeAlignedSplitForNote(
                note.timestampEpochSec,
                slice.startEpochSec,
                slice.endEpochSec
            )
        } else {
            null
        }

        var dialogRef: AlertDialog? = null
        if (alignedSplit != null && slice != null) {
            val promoteBtn = Button(this).apply {
                text = getString(R.string.dialog_note_promote_btn)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    val edited = input.text.toString().trim().ifEmpty { MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT }
                    val noteForSplit = if (edited != note.text) {
                        svc.metadataDb.updateTimelineNote(note.id, edited)
                        note.copy(text = edited)
                    } else {
                        note
                    }
                    dialogRef?.dismiss()
                    refreshLiveRecentGraph()
                    refreshTripsTab()
                    showPromoteNoteToSplitDialog(noteForSplit, slice, sliceIdx)
                }
            }
            container.addView(promoteBtn)
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_note_edit_title, exactTimeFmt.format(Instant.ofEpochSecond(note.timestampEpochSec))))
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                svc.metadataDb.updateTimelineNote(note.id, input.text.toString())
                refreshLiveRecentGraph()
                refreshTripsTab()
            }
            .setNeutralButton(R.string.dialog_delete) { _, _ ->
                svc.metadataDb.deleteTimelineNote(note.id)
                refreshLiveRecentGraph()
                refreshTripsTab()
                Toast.makeText(this, getString(R.string.toast_note_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showPromoteNoteToSplitDialog(
        note: TimelineNoteRecord,
        slice: SessionSliceRecord,
        sliceIdx: Int
    ) {
        val svc = collectorService ?: return
        val alignedSplit = MetadataDatabase.computeAlignedSplitForNote(
            note.timestampEpochSec,
            slice.startEpochSec,
            slice.endEpochSec
        ) ?: return
        val newRightNum = sliceIdx + 2

        fun finishSplit(newRightLabel: String?, deleteNote: Boolean) {
            if (svc.metadataDb.splitSliceAt(slice.id, alignedSplit, svc.ringStore, newRightSliceLabel = newRightLabel)) {
                if (deleteNote) {
                    svc.metadataDb.deleteTimelineNote(note.id)
                }
                isTripEditMode = true
                refreshTripsTab()
                refreshLiveRecentGraph()
                dualPaneSlicerView.selectedDividerIndex = sliceIdx
                dualPaneSlicerView.cursorEpochSec = alignedSplit
                dualPaneSlicerView.ensureTimestampVisibleInViewport(alignedSplit, centerIfOutside = true)
                updateEditModeUi()
                updateZoomControlsUi()
                updateSliceCardHighlights(alignedSplit)
                dualPaneSlicerView.invalidate()
                (tabContainers.getOrNull(1) as? ScrollView)?.smoothScrollTo(0, 0)
                Toast.makeText(
                    this,
                    getString(R.string.toast_promote_split_done, sliceIdx + 1, newRightNum),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val isQuickMarker = (note.text == MetadataDatabase.DEFAULT_QUICK_NOTE_TEXT)
        val positiveText = if (isQuickMarker) {
            getString(R.string.dialog_promote_split_remove_marker)
        } else {
            getString(R.string.dialog_promote_split_label, newRightNum)
        }
        val neutralText = if (isQuickMarker) {
            getString(R.string.dialog_promote_split_keep_marker)
        } else {
            getString(R.string.dialog_promote_split_keep_note)
        }
        val rightLabel = if (isQuickMarker) null else note.text

        val splitTimeStr = exactTimeFmt.format(Instant.ofEpochSecond(alignedSplit))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_promote_split_title, splitTimeStr))
            .setMessage(
                getString(
                    R.string.dialog_promote_split_msg,
                    sliceIdx + 1,
                    slice.label,
                    splitTimeStr,
                    note.text
                )
            )
            .setPositiveButton(positiveText) { _, _ -> finishSplit(newRightLabel = rightLabel, deleteNote = true) }
            .setNeutralButton(neutralText) { _, _ -> finishSplit(newRightLabel = null, deleteNote = false) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showTripPickerDialog() {
        val svc = collectorService ?: return
        svc.metadataDb.coalesceMicroExcursionsAndRestoreRingPlaceIds(svc.ringStore)
        val excursions = svc.metadataDb.getExcursions()
        val demo = isDemoDataEnabled()
        val nowSec = System.currentTimeMillis() / 1000L
        val retentionWindowSec = svc.ringStore.getArchiveWindowSec(0)
        val options = excursions.map { formatTripPickerLabel(this, it, nowSec, retentionWindowSec) }.toMutableList()
        val importIdx = options.size
        options.add(getString(R.string.trips_import_xlsx_btn))
        if (demo) {
            options.add(getString(R.string.trips_picker_reload_demo))
            options.add(getString(R.string.trips_demo_replay_xlsx_btn))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_trip_picker_title)
            .setItems(options.toTypedArray()) { _, which ->
                customWindowStartEpochSec = null
                customWindowEndEpochSec = null
                when {
                    which < excursions.size -> {
                        selectedExcursionId = excursions[which].id
                        refreshTripsTab()
                    }
                    which == importIdx -> {
                        launchXlsxImportPicker(timeShiftForDemo = false)
                    }
                    demo && which == importIdx + 1 -> {
                        selectedExcursionId = svc.seedDemoChicagoExcursion()
                        refreshTripsTab()
                    }
                    demo && which == importIdx + 2 -> {
                        launchXlsxImportPicker(timeShiftForDemo = true)
                    }
                }
            }
            .show()
    }

    private fun slugifyFilenamePart(raw: String, fallback: String): String =
        raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { fallback }
            .take(32)

    private fun resolveDeviceForExport(svc: AqlCollectorService, deviceId: Long): MonitorDevice =
        svc.metadataDb.getDeviceById(deviceId)
            ?: svc.metadataDb.getPrimaryDevice()
            ?: MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Qingping Air Monitor Lite", true)

    private fun shareXlsxBytes(
        bytes: ByteArray,
        fileName: String,
        subject: String,
        chooserTitle: String
    ) {
        val exportDir = File(filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, fileName)
        outFile.writeBytes(bytes)

        val authority = "${applicationContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(this, authority, outFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = XlsxExporter.MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri(outFile.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(R.string.share_chooser_with_file, chooserTitle, outFile.name)
            )
        )
    }

    private fun exportExcursionToXlsx(exc: ExcursionRecord) {
        val svc = collectorService ?: return
        val slots = svc.metadataDb.resolveSlotsForExcursion(exc.id, svc.ringStore)
        if (slots.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_export_no_trip_samples), Toast.LENGTH_SHORT).show()
            return
        }
        val slices = svc.metadataDb.getSlicesForExcursion(exc.id)
        val excEndSec = exc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
        val notes = svc.metadataDb.getTimelineNotesInRange(exc.startEpochSec, excEndSec)
        val device = resolveDeviceForExport(svc, exc.deviceId)
        val placesMap = svc.metadataDb.getKnownPlaces().associateBy { it.id }

        val xlsxBytes = XlsxExporter.exportExcursionXlsx(
            device = device,
            excursion = exc,
            slices = slices,
            slots = slots,
            notes = notes,
            knownPlacesById = placesMap,
            onlySelectedForExport = false
        )
        val slug = slugifyFilenamePart(exc.title, "trip")
        shareXlsxBytes(
            bytes = xlsxBytes,
            fileName = "trip_${exc.id}_${slug}.xlsx",
            subject = getString(R.string.share_trip_xlsx_subject, exc.title),
            chooserTitle = getString(R.string.share_trip_xlsx_chooser)
        )
    }

    private fun exportSliceToXlsx(
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
            Toast.makeText(this, getString(R.string.toast_export_no_segment_samples), Toast.LENGTH_SHORT).show()
            return
        }
        val notes = svc.metadataDb.getTimelineNotesInRange(slice.startEpochSec, slice.endEpochSec)
        val device = resolveDeviceForExport(svc, exc.deviceId)
        val placesMap = svc.metadataDb.getKnownPlaces().associateBy { it.id }

        val xlsxBytes = XlsxExporter.exportSliceXlsx(
            device = device,
            excursion = exc,
            slice = slice,
            slots = slots,
            notes = notes,
            knownPlacesById = placesMap
        )
        val slug = slugifyFilenamePart(slice.label, "segment")
        shareXlsxBytes(
            bytes = xlsxBytes,
            fileName = "trip_${exc.id}_seg_${sliceNumber}_${slug}.xlsx",
            subject = getString(R.string.share_segment_xlsx_subject, sliceNumber, slice.label),
            chooserTitle = getString(R.string.share_segment_xlsx_chooser)
        )
    }

    private fun shareHardwareDiagnosticTrace() {
        val svc = collectorService ?: return
        val logFile = svc.hwLogger.logFile
        if (!logFile.exists()) {
            Toast.makeText(this, getString(R.string.toast_no_hw_trace), Toast.LENGTH_SHORT).show()
            return
        }
        val tailContent = logFile.readLines().takeLast(200).joinToString("\n")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_hw_trace_subject))
            putExtra(Intent.EXTRA_TEXT, tailContent)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_hw_trace_chooser)))
    }

    private fun promptAddCurrentWifiToSavedPlaces(onSaved: (() -> Unit)? = null) {
        val svc = collectorService ?: return
        val snap = svc.buildSnapshot()
        val existingPlaces = svc.metadataDb.getKnownPlaces()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val ssidLabel = TextView(this).apply {
            text = getString(R.string.dialog_wifi_ssid_label)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val ssidInput = EditText(this).apply {
            hint = getString(R.string.dialog_wifi_ssid_hint)
            setText(snap.connectedWifiSsid ?: "")
        }
        val placeLabel = TextView(this).apply {
            text = getString(R.string.dialog_wifi_place_label)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(10), 0, 0)
        }
        val defaultPlaceName = getString(R.string.dialog_wifi_place_default)
        val placeNameInput = EditText(this).apply {
            hint = getString(R.string.dialog_wifi_place_hint)
            setText(if (existingPlaces.isEmpty()) defaultPlaceName else "")
        }
        layout.addView(ssidLabel)
        layout.addView(ssidInput)
        layout.addView(placeLabel)
        layout.addView(placeNameInput)
        scroll.addView(layout)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_wifi_place_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_wifi_create_btn) { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val label = placeNameInput.text.toString().trim().ifEmpty { defaultPlaceName }
                if (ssid.isNotEmpty()) {
                    val created = svc.metadataDb.createKnownPlaceWithWifi(label, ssid, snap.connectedWifiBssid)
                    svc.backfillNewlySavedKnownPlace(created.id)
                    onSaved?.invoke()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)

        if (existingPlaces.isNotEmpty()) {
            builder.setNeutralButton(R.string.dialog_wifi_link_existing_btn) { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                if (ssid.isNotEmpty()) {
                    val names = existingPlaces.map { it.label }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_wifi_link_existing_title, ssid))
                        .setItems(names) { _, which ->
                            val placeId = existingPlaces[which].id
                            svc.metadataDb.linkWifiToKnownPlace(placeId, ssid, snap.connectedWifiBssid)
                            svc.backfillNewlySavedKnownPlace(placeId)
                            onSaved?.invoke()
                        }
                        .show()
                }
            }
        }
        builder.show()
    }

    private fun promptAddSegmentPreset(onSaved: (() -> Unit)? = null) {
        val svc = collectorService ?: return
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val emojiLabel = TextView(this).apply {
            text = getString(R.string.dialog_preset_emoji_label)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        val emojiInput = EditText(this).apply {
            hint = getString(R.string.dialog_preset_emoji_hint)
            setText("🏷️")
        }
        val labelTitle = TextView(this).apply {
            text = getString(R.string.dialog_preset_label_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(10), 0, 0)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.dialog_preset_label_hint)
        }
        layout.addView(emojiLabel)
        layout.addView(emojiInput)
        layout.addView(labelTitle)
        layout.addView(labelInput)
        scroll.addView(layout)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_preset_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_preset_add_btn) { _, _ ->
                val lbl = labelInput.text.toString().trim()
                if (lbl.isNotEmpty()) {
                    svc.metadataDb.addSegmentPreset(lbl, emojiInput.text.toString().trim())
                    onSaved?.invoke()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun promptEditAlertThresholds(onSaved: (() -> Unit)? = null) {
        val svc = collectorService ?: return
        val ev = svc.alertEvaluator
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(6))
        }

        fun addThresholdPairRow(
            groupTitle: String,
            warnLabelText: String,
            warnInitial: Int,
            critLabelText: String,
            critInitial: Int,
            topPaddingDp: Int = 12
        ): Pair<EditText, EditText> {
            val groupHeader = TextView(this).apply {
                text = groupTitle
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(topPaddingDp), 0, dp(4))
            }
            layout.addView(groupHeader)

            val pairRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            fun buildColumn(labelText: String, initialValue: Int, addEndMargin: Boolean): Pair<LinearLayout, EditText> {
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (addEndMargin) marginEnd = dp(10)
                    }
                }
                val label = TextView(this).apply {
                    text = labelText
                    gravity = Gravity.BOTTOM
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(initialValue.toString())
                }
                col.addView(label)
                col.addView(input)
                return col to input
            }

            val (warnCol, warnInput) = buildColumn(warnLabelText, warnInitial, addEndMargin = true)
            val (critCol, critInput) = buildColumn(critLabelText, critInitial, addEndMargin = false)
            pairRow.addView(warnCol)
            pairRow.addView(critCol)
            layout.addView(pairRow)
            return warnInput to critInput
        }

        val (co2WarnInput, co2CritInput) = addThresholdPairRow(
            groupTitle = getString(R.string.dialog_thresholds_co2_group),
            warnLabelText = getString(R.string.dialog_thresholds_co2_warn),
            warnInitial = ev.co2WarningThresholdPpm,
            critLabelText = getString(R.string.dialog_thresholds_co2_crit),
            critInitial = ev.co2CriticalThresholdPpm,
            topPaddingDp = 0
        )
        val (pm25WarnInput, pm25CritInput) = addThresholdPairRow(
            groupTitle = getString(R.string.dialog_thresholds_pm25_group),
            warnLabelText = getString(R.string.dialog_thresholds_pm25_warn),
            warnInitial = ev.pm25WarningThresholdUgm3,
            critLabelText = getString(R.string.dialog_thresholds_pm25_crit),
            critInitial = ev.pm25CriticalThresholdUgm3
        )
        val (batWarnInput, batCritInput) = addThresholdPairRow(
            groupTitle = getString(R.string.dialog_thresholds_bat_group),
            warnLabelText = getString(R.string.dialog_thresholds_bat_warn),
            warnInitial = ev.batteryWarningThresholdPct,
            critLabelText = getString(R.string.dialog_thresholds_bat_crit),
            critInitial = ev.batteryCriticalThresholdPct
        )

        scroll.addView(layout)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_thresholds_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val nextCo2Warn = (co2WarnInput.text.toString().toIntOrNull() ?: ev.co2WarningThresholdPpm)
                    .coerceAtLeast(1)
                val nextCo2Crit = (co2CritInput.text.toString().toIntOrNull() ?: ev.co2CriticalThresholdPpm)
                    .coerceAtLeast(nextCo2Warn)
                val nextPm25Warn = (pm25WarnInput.text.toString().toIntOrNull() ?: ev.pm25WarningThresholdUgm3)
                    .coerceAtLeast(1)
                val nextPm25Crit = (pm25CritInput.text.toString().toIntOrNull() ?: ev.pm25CriticalThresholdUgm3)
                    .coerceAtLeast(nextPm25Warn)
                val nextBatWarn = (batWarnInput.text.toString().toIntOrNull() ?: ev.batteryWarningThresholdPct)
                    .coerceIn(5, 80)
                val rawBatCrit = (batCritInput.text.toString().toIntOrNull() ?: ev.batteryCriticalThresholdPct)
                    .coerceIn(5, 80)
                val nextBatCrit = if (nextBatWarn > 5) rawBatCrit.coerceAtMost(nextBatWarn - 1) else 5

                ev.co2WarningThresholdPpm = nextCo2Warn
                ev.co2CriticalThresholdPpm = nextCo2Crit
                ev.pm25WarningThresholdUgm3 = nextPm25Warn
                ev.pm25CriticalThresholdUgm3 = nextPm25Crit
                ev.batteryWarningThresholdPct = nextBatWarn
                ev.batteryCriticalThresholdPct = nextBatCrit
                onSaved?.invoke()
            }
            .setNeutralButton(R.string.dialog_thresholds_reset_btn) { _, _ ->
                ev.co2WarningThresholdPpm = 1000
                ev.co2CriticalThresholdPpm = 2000
                ev.pm25WarningThresholdUgm3 = 25
                ev.pm25CriticalThresholdUgm3 = 55
                ev.batteryWarningThresholdPct = 20
                ev.batteryCriticalThresholdPct = 10
                onSaved?.invoke()
                Toast.makeText(this, getString(R.string.toast_thresholds_reset), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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

    companion object {
        const val MIN_PORTRAIT_SLICER_HEIGHT_DP = 320
        const val MAX_PORTRAIT_SLICER_HEIGHT_DP = 460
        const val PORTRAIT_SLICER_HEIGHT_FRACTION = 0.45f
        const val ENABLED_BUTTON_ALPHA = 1.0f
        const val DISABLED_BUTTON_ALPHA = 0.45f
        const val COLOR_ZOOM_BTN_ACTIVE_HEX = "#0284C7"
        const val COLOR_ZOOM_BTN_IDLE_PORTRAIT_HEX = "#1E293B"
        const val COLOR_ZOOM_BTN_IDLE_LANDSCAPE_HEX = "#334155"
        const val COLOR_ZOOM_STATUS_ACTIVE_HEX = "#38BDF8"
        const val COLOR_ZOOM_STATUS_IDLE_HEX = "#94A3B8"
        const val DEFAULT_RETENTION_WINDOW_SEC = 14L * 86_400L
        val WIFI_LOSS_DEBOUNCE_OPTIONS_SEC = listOf(0L, 15L, 30L, 60L)
        const val FAQ_DOC_URL = "https://github.com/charles-dyfis-net/AndroidAQL/blob/main/docs/FAQ.md"
        const val SLICING_GUIDE_URL = "https://github.com/charles-dyfis-net/AndroidAQL/blob/main/docs/trip_slicing_guide.md"
        const val HARDWARE_GUIDE_URL = "https://github.com/charles-dyfis-net/AndroidAQL/blob/main/docs/how_cgdn1_works.md"
        const val USERS_GROUP_URL = "https://groups.google.com/g/androidaql-users"

        fun formatElapsedAge(ageSec: Long): String = when {
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

        fun formatElapsedAge(context: Context, ageSec: Long): String = when {
            ageSec < 60L -> context.getString(R.string.trips_age_seconds, ageSec)
            ageSec < 3600L -> {
                val m = ageSec / 60L
                val s = ageSec % 60L
                if (s == 0L) {
                    context.getString(R.string.trips_age_minutes, m)
                } else {
                    context.getString(R.string.trips_age_minutes_seconds, m, s)
                }
            }
            else -> {
                val h = ageSec / 3600L
                val m = (ageSec % 3600L) / 60L
                context.getString(R.string.trips_age_hours_minutes, h, m)
            }
        }

        fun formatRemainingRetentionCompact(
            excursion: ExcursionRecord,
            nowEpochSec: Long = System.currentTimeMillis() / 1000L,
            retentionWindowSec: Long = DEFAULT_RETENTION_WINDOW_SEC
        ): String {
            val ageSec = (nowEpochSec - excursion.startEpochSec).coerceAtLeast(0L)
            val remainingSec = (retentionWindowSec - ageSec).coerceAtLeast(0L)
            if (remainingSec < 86_400L) return "<1d"
            val maxDays = (retentionWindowSec / 86_400L).coerceAtLeast(1L)
            val days = ((remainingSec + 43_200L) / 86_400L).coerceIn(1L, maxDays)
            return "${days}d"
        }

        fun formatRemainingRetentionCompact(
            context: Context,
            excursion: ExcursionRecord,
            nowEpochSec: Long = System.currentTimeMillis() / 1000L,
            retentionWindowSec: Long = DEFAULT_RETENTION_WINDOW_SEC
        ): String {
            val ageSec = (nowEpochSec - excursion.startEpochSec).coerceAtLeast(0L)
            val remainingSec = (retentionWindowSec - ageSec).coerceAtLeast(0L)
            if (remainingSec < 86_400L) return context.getString(R.string.trips_retention_less_than_1d)
            val maxDays = (retentionWindowSec / 86_400L).coerceAtLeast(1L)
            val days = ((remainingSec + 43_200L) / 86_400L).coerceIn(1L, maxDays)
            return context.getString(R.string.trips_retention_days, days)
        }

        fun formatTripPickerLabel(
            excursion: ExcursionRecord,
            nowEpochSec: Long = System.currentTimeMillis() / 1000L,
            retentionWindowSec: Long = DEFAULT_RETENTION_WINDOW_SEC
        ): String {
            val endSec = excursion.endEpochSec ?: nowEpochSec
            val duration = formatElapsedAge((endSec - excursion.startEpochSec).coerceAtLeast(0L))
            return if (excursion.endEpochSec == null) {
                if (excursion.isPinned) {
                    "${excursion.title} • Live ($duration) • 📌 Pinned"
                } else {
                    "${excursion.title} • Live ($duration)"
                }
            } else {
                val badge = if (excursion.isPinned) {
                    "📌 Pinned"
                } else {
                    "⏳ ${formatRemainingRetentionCompact(excursion, nowEpochSec, retentionWindowSec)}"
                }
                "${excursion.title} • $duration • $badge"
            }
        }

        fun formatTripPickerLabel(
            context: Context,
            excursion: ExcursionRecord,
            nowEpochSec: Long = System.currentTimeMillis() / 1000L,
            retentionWindowSec: Long = DEFAULT_RETENTION_WINDOW_SEC
        ): String {
            val endSec = excursion.endEpochSec ?: nowEpochSec
            val duration = formatElapsedAge(context, (endSec - excursion.startEpochSec).coerceAtLeast(0L))
            return if (excursion.endEpochSec == null) {
                if (excursion.isPinned) {
                    context.getString(R.string.trips_picker_live_pinned, excursion.title, duration)
                } else {
                    context.getString(R.string.trips_picker_live, excursion.title, duration)
                }
            } else if (excursion.isPinned) {
                context.getString(R.string.trips_picker_closed_pinned, excursion.title, duration)
            } else {
                val rem = formatRemainingRetentionCompact(context, excursion, nowEpochSec, retentionWindowSec)
                context.getString(R.string.trips_picker_closed_retention, excursion.title, duration, rem)
            }
        }

        /**
         * Computes a responsive portrait chart height in dp based on the current window height,
         * preventing the 3-pane slicer + zoom/nudge controls from crowding small phones while taking
         * better advantage of larger phones and tablets.
         */
        fun computePortraitSlicerHeightDp(screenHeightDp: Int): Int {
            if (screenHeightDp <= 0) return DualPaneSlicerView.DEFAULT_PORTRAIT_HEIGHT_DP
            return (screenHeightDp * PORTRAIT_SLICER_HEIGHT_FRACTION)
                .toInt()
                .coerceIn(MIN_PORTRAIT_SLICER_HEIGHT_DP, MAX_PORTRAIT_SLICER_HEIGHT_DP)
        }
    }
}
