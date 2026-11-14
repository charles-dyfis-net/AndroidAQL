package io.github.androidaql.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
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
import io.github.androidaql.engine.ExcursionStateMachine
import io.github.androidaql.export.CsvExporter
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.storage.ExcursionRecord
import io.github.androidaql.storage.MonitorDevice
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.SliceCategory
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
    private var isTripEditMode: Boolean = false

    // Optional custom 14-day ring buffer time window (when inspecting raw ring buffer before carving a trip)
    private var customWindowStartEpochSec: Long? = null
    private var customWindowEndEpochSec: Long? = null

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
    private lateinit var demoStreamBtn: Button

    // Tab 2: Trips & Slicer Views
    private lateinit var tripSelectorLabel: TextView
    private lateinit var modeToggleBtn: Button
    private lateinit var dualPaneSlicerView: DualPaneSlicerView
    private lateinit var dividerNudgeBar: LinearLayout
    private lateinit var editHelperView: TextView
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
    }

    private val serviceListener: (AqlCollectorService.LiveTelemetrySnapshot) -> Unit = { snap ->
        runOnUiThread {
            renderLiveSnapshot(snap)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AqlCollectorService.LocalBinder ?: return
            val svc = b.getService()
            collectorService = svc
            isBound = true
            svc.addListener(serviceListener)
            refreshLiveRecentGraph()
            refreshTripsTab()
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
            val savedExc = savedInstanceState.getLong("selectedExcursionId", -1L)
            if (savedExc > 0L) selectedExcursionId = savedExc
            isTripEditMode = savedInstanceState.getBoolean("isTripEditMode", false)
            val cwStart = savedInstanceState.getLong("customWindowStartEpochSec", -1L)
            val cwEnd = savedInstanceState.getLong("customWindowEndEpochSec", -1L)
            if (cwStart > 0L && cwEnd > cwStart) {
                customWindowStartEpochSec = cwStart
                customWindowEndEpochSec = cwEnd
            }
        }

        setContentView(buildRootLayout())
        requestRuntimePermissionsIfNeeded()

        ContextCompat.startForegroundService(this, buildServiceStartIntent(intent))
        bindService(Intent(this, AqlCollectorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == AqlCollectorService.ACTION_DEV_COMMAND &&
            AqlCollectorService.isDevCommandAuthorized(this)
        ) {
            ContextCompat.startForegroundService(this, buildServiceStartIntent(intent))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("activeTabIndex", activeTabIndex)
        outState.putLong("selectedExcursionId", selectedExcursionId ?: -1L)
        outState.putBoolean("isTripEditMode", isTripEditMode)
        outState.putLong("customWindowStartEpochSec", customWindowStartEpochSec ?: -1L)
        outState.putLong("customWindowEndEpochSec", customWindowEndEpochSec ?: -1L)
    }

    override fun onDestroy() {
        if (isBound) {
            collectorService?.removeListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun isDeveloperModeEnabled(): Boolean =
        AqlCollectorService.isDeveloperModeEnabled(this)

    private fun setDeveloperModeEnabled(enabled: Boolean) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("developer_mode", enabled)
            .apply()
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
        if (::demoStreamBtn.isInitialized) {
            demoStreamBtn.visibility = if (dev) View.VISIBLE else View.GONE
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

    private fun hasAcceptedLocationDisclosure(): Boolean =
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE).getBoolean("location_disclosure_accepted", false)

    private fun setLocationDisclosureAccepted(accepted: Boolean) {
        getSharedPreferences("aql_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("location_disclosure_accepted", accepted)
            .apply()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val allPerms = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val missing = allPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        val missingLocation = missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
            missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (missingLocation && !hasAcceptedLocationDisclosure()) {
            showProminentLocationDisclosureDialog(
                onAccept = {
                    setLocationDisclosureAccepted(true)
                    permissionLauncher.launch(missing.toTypedArray())
                },
                onDeclineLocation = {
                    val nonLocationPerms = missing.filter {
                        it != Manifest.permission.ACCESS_FINE_LOCATION &&
                            it != Manifest.permission.ACCESS_COARSE_LOCATION
                    }
                    if (nonLocationPerms.isNotEmpty()) {
                        permissionLauncher.launch(nonLocationPerms.toTypedArray())
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
            append("AndroidAQL collects location data and connected Wi-Fi network information to enable automatic Trip Mode and Saved Place detection, even when the app is in the background or your screen is off:\n\n")
            append("• Saved Wi-Fi Places (GPS Off): Location permission is required by Android to read your connected Wi-Fi SSID (e.g., \"Home\") so AndroidAQL can automatically keep GPS off while you are stationary indoors.\n\n")
            append("• Field Excursion Movement Speed: When your Qingping air monitor is actively broadcasting outside of a Saved Wi-Fi Place, AndroidAQL records movement speed (mph / m/s) alongside CO₂ and PM2.5 readings so you can distinguish transit segments from indoor destinations.\n\n")
            append("• 100% Local & Private: Raw GPS latitude/longitude coordinates are never stored to disk, and no telemetry or location data is ever transmitted to any cloud server or third party. Data leaves your phone only when you explicitly share a CSV or diagnostic log.")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("📍 Location & Privacy Disclosure")
            .setMessage(message)
            .setCancelable(onAccept == null)

        if (onAccept != null) {
            builder.setPositiveButton("Continue") { _, _ -> onAccept() }
            builder.setNegativeButton("Not Now (BLE Only)") { _, _ -> onDeclineLocation?.invoke() }
        } else {
            builder.setPositiveButton("Close", null)
        }
        builder.show()
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
            text = "AndroidAQL"
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
        demoStreamBtn = Button(this).apply {
            text = "🎭 Toggle Live 3s Demo BLE Stream (Emulator)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            visibility = if (isDeveloperModeEnabled()) View.VISIBLE else View.GONE
            setOnClickListener {
                val running = collectorService?.toggleDemoStream() ?: false
                val msg = if (running) "Started Live Demo 0xFDCD Stream (every 3s)" else "Stopped Live Demo Stream"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        wifiCard.addView(wifiStatusView)
        wifiCard.addView(saveCurrentWifiBtn)
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
        }

        modeToggleBtn = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                isTripEditMode = !isTripEditMode
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
                collectorService?.metadataDb?.moveSharedDivider(leftId, rightId, newTs)
                refreshTripsTab()
            }
            onDividerSelected = { _ ->
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
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(2), dp(2), dp(2), dp(4))
            visibility = if (isTripEditMode) View.VISIBLE else View.GONE
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

        dividerNudgeBar.addView(makeEditBarButton("✂️ Split", 1.35f) { splitAtCurrentCursor() })
        dividerNudgeBar.addView(makeEditBarButton("-1m", 1f) { nudgeSelectedDivider(-60L) })
        dividerNudgeBar.addView(makeEditBarButton("-15s", 1f) { nudgeSelectedDivider(-15L) })
        dividerNudgeBar.addView(makeEditBarButton("+15s", 1f) { nudgeSelectedDivider(+15L) })
        dividerNudgeBar.addView(makeEditBarButton("+1m", 1f) { nudgeSelectedDivider(+60L) })

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
            val exportCsvBtn = Button(this).apply {
                text = "📤 CSV"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { exportCurrentExcursionToCsv() }
            }
            actionRow.addView(chooseTripBtn)
            actionRow.addView(carveWindowBtn)
            actionRow.addView(modeToggleBtn)
            actionRow.addView(exportCsvBtn)
            col.addView(actionRow)

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            titleRow.addView(tripSelectorLabel)
            col.addView(titleRow)

            col.addView(dualPaneSlicerView)
            col.addView(dividerNudgeBar)
            col.addView(editHelperView)

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
        if (::editHelperView.isInitialized) {
            editHelperView.visibility = if (isTripEditMode) View.VISIBLE else View.GONE
            updateEditHelperText()
        }
        modeToggleBtn.text = if (isTripEditMode) "👁️ Done Editing" else "✏️ Edit Trip"
        modeToggleBtn.setBackgroundColor(
            if (isTripEditMode) Color.parseColor("#D97706") else Color.parseColor("#334155")
        )
        modeToggleBtn.setTextColor(Color.WHITE)
    }

    private fun updateEditHelperText() {
        if (!::editHelperView.isInitialized || !isTripEditMode) return
        val slices = dualPaneSlicerView.slices
        val divIdx = dualPaneSlicerView.selectedDividerIndex
        editHelperView.text = when {
            slices.size <= 1 ->
                "Edit Mode: Tap chart or ±15s/±1m to place the cursor, then tap ✂️ Split to divide #1."
            divIdx in 0 until slices.size - 1 -> {
                val boundaryTime = shortTimeFmt.format(Instant.ofEpochSecond(slices[divIdx].endEpochSec))
                "Divider ⇔ ${divIdx + 1}|${divIdx + 2} selected ($boundaryTime) — separates #${divIdx + 1} (left) and #${divIdx + 2} (right). Drag or nudge."
            }
            else ->
                "Edit Mode: Tap inside a segment + ✂️ Split to add a boundary, or tap/drag a ⇔ divider to adjust."
        }
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
                "🏠 At ${snap.matchedKnownPlace?.label ?: "Saved Place"} • GPS Off • BLE: $bleShortLabel"
            ExcursionStateMachine.CollectorState.FIELD_EXCURSION_ACTIVE ->
                "🚶 On a Trip • GPS Active • BLE: $bleShortLabel"
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
            val spdStr = snap.currentSpeedMps?.let {
                String.format(Locale.US, "%.1f m/s (±%.0fm)", it, snap.currentGpsAccuracyM ?: 0f)
            } ?: "Off"
            val tempStr = TemperatureUnitFormatter.format(snap.mergedTempC, useF, decimals = 1)
            secondaryMetricsView.text = String.format(
                Locale.US,
                "PM2.5: %d µg/m³  •  PM10: %d µg/m³\nTemp: %s  •  Humidity: %.1f%%  •  Battery: %s\nMovement GPS: %s",
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
            ?: if (snap.connectedWifiSsid != null) "Unrecognized Wi-Fi (Trip Mode if monitor active)" else "No Wi-Fi Place Matched"
        wifiStatusView.text = "Wi-Fi: $ssidStr  •  $placeMatchStr"
        saveCurrentWifiBtn.visibility = if (!snap.connectedWifiSsid.isNullOrBlank() && snap.matchedKnownPlace == null) {
            View.VISIBLE
        } else {
            View.GONE
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
            tripSelectorLabel.text = "No Trips Recorded Yet"
            dualPaneSlicerView.slots = emptyList()
            dualPaneSlicerView.slices = emptyList()
            slicesContainer?.removeAllViews()
            return
        }

        val currentExc = excursions.firstOrNull { it.id == selectedExcursionId } ?: excursions.first().also {
            selectedExcursionId = it.id
        }
        val endSec = currentExc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
        val slices = svc.metadataDb.getSlicesForExcursion(currentExc.id)
        val slots = svc.ringStore.queryRange(0, currentExc.startEpochSec, endSec)

        val pinBadge = if (currentExc.isPinned) "📌" else "14d"
        tripSelectorLabel.text = "${currentExc.title} [$pinBadge]"
        dualPaneSlicerView.slices = slices
        dualPaneSlicerView.slots = slots
        updateEditHelperText()

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
        val svc = collectorService ?: return
        val container = slicesContainer ?: return
        container.removeAllViews()
        val cardsList = mutableListOf<Pair<SessionSliceRecord, LinearLayout>>()
        val cursorTs = dualPaneSlicerView.cursorEpochSec

        for ((idx, slice) in slices.withIndex()) {
            val sliceSlots = svc.metadataDb.getPinnedSlotsForSlice(slice.id)
                ?: allSlots.filter { it.bucketEpochSec in slice.startEpochSec..slice.endEpochSec }
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
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spanDuration = formatElapsedAge((slice.endEpochSec - slice.startEpochSec).coerceAtLeast(0L))
            val timeSpan = "${shortTimeFmt.format(Instant.ofEpochSecond(slice.startEpochSec))}–${shortTimeFmt.format(Instant.ofEpochSecond(slice.endEpochSec))} ($spanDuration)"
            val titleTv = TextView(this).apply {
                text = "#${idx + 1} ${slice.label} [${slice.category.name}]\n$timeSpan"
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            headerRow.addView(titleTv)
            headerRow.addView(editSegmentBtn)
            card.addView(headerRow)

            val notesSuffix = if (slice.notes.isNotBlank()) " • \"${slice.notes}\"" else ""
            val statsRow = TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "CO₂: %.0f ppm (Max %d, P95 %d) • PM2.5: %.1f µg/m³ • %.0fm%s",
                    stats.co2Mean,
                    stats.co2Max,
                    stats.co2P95,
                    stats.pm25Mean,
                    stats.totalDistanceMetersEstimate,
                    notesSuffix
                )
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(4))
            }
            card.addView(statsRow)

            val controlsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pinCheck = CheckBox(this).apply {
                text = "Pin Forever"
                isChecked = slice.isPinned
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnCheckedChangeListener { _, checked ->
                    svc.metadataDb.updateSliceMetadata(
                        slice.id, slice.label, slice.category, slice.notes,
                        isPinned = checked, includeInExport = slice.includeInExport, ringStore = svc.ringStore
                    )
                    refreshTripsTab()
                }
            }
            val exportCheck = CheckBox(this).apply {
                text = "Include in CSV"
                isChecked = slice.includeInExport
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnCheckedChangeListener { _, checked ->
                    svc.metadataDb.updateSliceMetadata(
                        slice.id, slice.label, slice.category, slice.notes,
                        isPinned = slice.isPinned, includeInExport = checked, ringStore = svc.ringStore
                    )
                    refreshTripsTab()
                }
            }
            controlsRow.addView(pinCheck)
            controlsRow.addView(exportCheck)
            card.addView(controlsRow)

            cardsList.add(slice to card)
            container.addView(card)
        }
        renderedSliceCards = cardsList
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
            hint = "e.g., CTA Bus #22, Pediatrician Waiting Room, Coffee Shop"
            setText(slice.label)
        }
        layout.addView(labelTitle)
        layout.addView(labelInput)

        val categoryTitle = TextView(this).apply {
            text = "Category:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, dp(2))
        }
        val categories = SliceCategory.entries
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                categories.map { it.name }
            )
            setSelection(categories.indexOf(slice.category).coerceAtLeast(0))
        }
        layout.addView(categoryTitle)
        layout.addView(categorySpinner)

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
                    categorySpinner.setSelection(categories.indexOf(preset.defaultCategory).coerceAtLeast(0))
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
            hint = "e.g., Crowded waiting area, portable HEPA running"
            setText(slice.notes)
        }
        layout.addView(notesTitle)
        layout.addView(notesInput)

        val spanTitle = "${shortTimeFmt.format(Instant.ofEpochSecond(slice.startEpochSec))}–${shortTimeFmt.format(Instant.ofEpochSecond(slice.endEpochSec))}"
        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("Edit Segment #${sliceIdx + 1} ($spanTitle)")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString().trim().ifEmpty { "Segment" }
                val newCategory = categories[categorySpinner.selectedItemPosition]
                val newNotes = notesInput.text.toString().trim()
                svc.metadataDb.updateSliceMetadata(
                    sliceId = slice.id,
                    label = newLabel,
                    category = newCategory,
                    notes = newNotes,
                    isPinned = slice.isPinned,
                    includeInExport = slice.includeInExport,
                    ringStore = svc.ringStore
                )
                refreshTripsTab()
            }
            .setNegativeButton("Cancel", null)

        if (nextSlice != null) {
            dialogBuilder.setNeutralButton("Merge #${sliceIdx + 1} + #${sliceIdx + 2} ↓") { _, _ ->
                svc.metadataDb.mergeAdjacentSlices(slice.id, nextSlice.id)
                refreshTripsTab()
            }
        }
        dialogBuilder.show()
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
        svc.metadataDb.updateOngoingExcursionEnd(exc.id, endSec, closeExcursion = true)
        customWindowStartEpochSec = null
        customWindowEndEpochSec = null
        selectedExcursionId = exc.id
        refreshTripsTab()
        Toast.makeText(this, "Created Trip: $title", Toast.LENGTH_SHORT).show()
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
            for (p in places) {
                val ssids = p.networks.joinToString(", ") { it.ssid }
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
                if (isDeveloperModeEnabled()) {
                    val simBtn = Button(this).apply {
                        text = "Simulate"
                        isAllCaps = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setOnClickListener {
                            svc.setManualKnownPlaceOverride(p.id)
                            Toast.makeText(this@MainActivity, "Simulating presence at ${p.label} (GPS Off)", Toast.LENGTH_SHORT).show()
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
            text = "🔒 View Location & Privacy Disclosure"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { showProminentLocationDisclosureDialog() }
        }
        col.addView(privacyBtn)

        val devModeCheck = CheckBox(this).apply {
            text = "🛠️ Developer Mode (Show HW Test Tab & Demo Controls)"
            isChecked = isDeveloperModeEnabled()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        col.addView(devModeCheck)

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
                setDeveloperModeEnabled(devModeCheck.isChecked)
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
            Toast.makeText(this, "Tap inside a segment (between dividers) on the chart to place the split cursor.", Toast.LENGTH_SHORT).show()
            return
        }
        if (svc.metadataDb.splitSliceAt(targetSlice.id, cursorTs)) {
            refreshTripsTab()
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
        if (svc.metadataDb.moveSharedDivider(left.id, right.id, newBoundary)) {
            refreshTripsTab()
        }
    }

    private fun showTripPickerDialog() {
        val svc = collectorService ?: return
        val excursions = svc.metadataDb.getExcursions()
        val dev = isDeveloperModeEnabled()
        val options = excursions.map { "${it.title} (${if (it.isPinned) "Pinned" else "14d Ring"})" }.toMutableList()
        if (dev) {
            options.add("🔄 Reload 75-min Demo Chicago Trip")
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
                if (which < excursions.size) {
                    selectedExcursionId = excursions[which].id
                } else if (dev) {
                    selectedExcursionId = svc.seedDemoChicagoExcursion()
                }
                refreshTripsTab()
            }
            .show()
    }

    private fun exportCurrentExcursionToCsv() {
        val svc = collectorService ?: return
        val excId = selectedExcursionId ?: return
        val exc = svc.metadataDb.getExcursions().firstOrNull { it.id == excId } ?: return
        val slices = svc.metadataDb.getSlicesForExcursion(excId)
        val endSec = exc.endEpochSec ?: (System.currentTimeMillis() / 1000L)
        val slots = svc.ringStore.queryRange(0, exc.startEpochSec, endSec)
        val device = svc.metadataDb.getPrimaryDevice()
            ?: MonitorDevice(1L, "58:2D:34:12:AB:CD", "CGDN1", "Qingping Air Monitor Lite", true)
        val placesMap = svc.metadataDb.getKnownPlaces().associateBy { it.id }

        val csvText = CsvExporter.exportExcursionCsv(
            device = device,
            excursion = exc,
            slices = slices,
            slots = slots,
            knownPlacesById = placesMap,
            onlySelectedForExport = true
        )

        val exportDir = File(filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "trip_${exc.id}_export.csv")
        outFile.writeText(csvText)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Air Quality Export: ${exc.title}")
            putExtra(Intent.EXTRA_TEXT, csvText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Advocacy CSV (${outFile.name})"))
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
            putExtra(Intent.EXTRA_SUBJECT, "AndroidAQL Hardware Bring-Up Trace (JSONL)")
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
            hint = "Emoji Icon (e.g. 🏥, 🚇, ☕)"
            setText("🏷️")
        }
        val labelInput = EditText(this).apply {
            hint = "Segment Preset Label (e.g. Dental Clinic, Metra)"
        }
        layout.addView(emojiInput)
        layout.addView(labelInput)

        AlertDialog.Builder(this)
            .setTitle("Add Reusable Segment Preset")
            .setView(layout)
            .setPositiveButton("Add Destination Preset") { _, _ ->
                val lbl = labelInput.text.toString().trim()
                if (lbl.isNotEmpty()) {
                    svc.metadataDb.addSegmentPreset(lbl, emojiInput.text.toString().trim(), SliceCategory.DESTINATION)
                }
            }
            .setNeutralButton("Add Transit Preset") { _, _ ->
                val lbl = labelInput.text.toString().trim()
                if (lbl.isNotEmpty()) {
                    svc.metadataDb.addSegmentPreset(lbl, emojiInput.text.toString().trim(), SliceCategory.TRANSIT)
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
