package io.github.androidaql.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.github.androidaql.R
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.service.AqlCollectorService.KeepAlivePayloadStrategy
import io.github.androidaql.storage.BleCollectionMode
import io.github.androidaql.util.TempUnitPreference
import io.github.androidaql.util.TemperatureUnitFormatter
import java.io.File
import java.io.FileOutputStream

class SettingsScreenDialog(
    private val context: Context,
    private val service: AqlCollectorService,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun isDeveloperModeEnabled(): Boolean
        fun setDeveloperModeEnabled(enabled: Boolean)
        fun isDemoDataEnabled(): Boolean
        fun setDemoDataEnabled(enabled: Boolean)
        fun getDropoutGracePeriodSec(): Long
        fun setDropoutGracePeriodSec(sec: Long)
        fun getCompanionAssociationStatus(): Pair<Boolean, String?>
        fun onLinkCompanionDeviceRequested()
        fun onUnlinkCompanionDeviceRequested()
        fun onAddWifiPlaceRequested(onSaved: () -> Unit)
        fun onAddSegmentPresetRequested(onSaved: () -> Unit)
        fun onEditAlertThresholdsRequested(onSaved: () -> Unit)
        fun onShowHelpRequested()
        fun onShareDiagnosticLogRequested()
        fun onShowPrivacyDisclosureRequested()
        fun onEnablePermissionsRequested()
        fun onStopServiceAndExitRequested()
        fun onSettingsDismissed()
    }

    private var dialog: Dialog? = null
    private var topBarView: View? = null
    private var topSeparatorView: View? = null
    private var contentView: LinearLayout? = null
    private var placesListContainer: LinearLayout? = null
    private var presetsSummaryView: TextView? = null
    private var alertsSummaryView: TextView? = null
    private var advancedSummaryView: TextView? = null
    private var advancedChevronView: TextView? = null
    private var advancedBodyContainer: LinearLayout? = null
    private var keepAliveDevRow: LinearLayout? = null
    private var fakeWifiContainer: LinearLayout? = null
    private var fakeWifiSsidInput: EditText? = null
    private var demoSwitch: SwitchCompat? = null
    private var devSwitch: SwitchCompat? = null
    private var enablePermissionsDivider: View? = null
    private var enablePermissionsRow: LinearLayout? = null
    private var backgroundSectionHeader: TextView? = null
    private var backgroundCard: LinearLayout? = null
    private var companionSectionContainer: LinearLayout? = null
    private var companionStatusSubtitle: TextView? = null
    private var companionActionBtn: TextView? = null
    private var updatingDemoSwitch = false
    private var updatingDevSwitch = false
    private var isAdvancedExpanded = callbacks.isDeveloperModeEnabled() || callbacks.isDemoDataEnabled()

    fun show() {
        dialog?.dismiss()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BAR_BG))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnApplyWindowInsetsListener { v, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }

        val topBar = buildTopBar()
        val topSeparator = buildThinSeparator(COLOR_CARD_BORDER)
        topBarView = topBar
        topSeparatorView = topSeparator
        root.addView(topBar)
        root.addView(topSeparator)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_SCREEN_BG))
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        contentView = content

        buildDisplaySection(content)
        buildPlacesAndTripsSection(content)
        buildBackgroundLoggingSection(content)
        buildAlertsSection(content)
        buildAdvancedSection(content)
        buildHelpAndPrivacySection(content)

        scroll.addView(content)
        root.addView(scroll)

        val d = Dialog(context, R.style.Theme_MyApplication)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.parseColor(COLOR_BAR_BG)))
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        d.setOnDismissListener {
            commitFakeWifiOverrideIfChanged()
            dialog = null
            topBarView = null
            topSeparatorView = null
            contentView = null
            callbacks.onSettingsDismissed()
        }
        dialog = d
        d.show()
    }

    fun captureFullHeightPng(outputFile: File): Boolean = runCatching {
        val topBar = topBarView ?: return false
        val sep = topSeparatorView ?: return false
        val content = contentView ?: return false

        val width = content.width.takeIf { it > 0 }
            ?: topBar.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        if (topBar.width != width || topBar.height <= 0 || sep.height <= 0 || content.height <= 0) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val unspecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            topBar.measure(widthSpec, unspecHeight)
            sep.measure(widthSpec, unspecHeight)
            content.measure(widthSpec, unspecHeight)
            topBar.layout(0, 0, width, topBar.measuredHeight.coerceAtLeast(1))
            sep.layout(0, 0, width, sep.measuredHeight.coerceAtLeast(1))
            content.layout(0, 0, width, content.measuredHeight.coerceAtLeast(1))
        }

        val topBarH = topBar.height.coerceAtLeast(1)
        val sepH = sep.height.coerceAtLeast(1)
        val contentH = content.height.coerceAtLeast(1)
        val totalHeight = topBarH + sepH + contentH
        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val bgPaint = Paint()
            bgPaint.color = Color.parseColor(COLOR_BAR_BG)
            canvas.drawRect(0f, 0f, width.toFloat(), topBarH.toFloat(), bgPaint)

            bgPaint.color = Color.parseColor(COLOR_SCREEN_BG)
            canvas.drawRect(0f, topBarH.toFloat(), width.toFloat(), totalHeight.toFloat(), bgPaint)

            topBar.draw(canvas)

            canvas.save()
            canvas.translate(0f, topBarH.toFloat())
            sep.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.translate(0f, (topBarH + sepH).toFloat())
            content.draw(canvas)
            canvas.restore()

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
        true
    }.getOrElse { false }

    fun isBoundTo(targetService: AqlCollectorService): Boolean = service === targetService

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        topBarView = null
        topSeparatorView = null
        contentView = null
    }

    fun refreshPlacesSection() {
        val container = placesListContainer ?: return
        container.removeAllViews()

        val places = service.metadataDb.getKnownPlaces()
        if (places.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = context.getString(R.string.settings_saved_places_empty)
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, dp(6))
            }
            container.addView(emptyTv)
            return
        }

        val fakeSsid = service.fakeWifiSsidOverride
        val demoEnabled = callbacks.isDemoDataEnabled()
        for ((idx, place) in places.withIndex()) {
            val ssids = place.networks.joinToString(", ") { fakeSsid ?: it.ssid }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedRect(COLOR_NESTED_BG, dp(8), COLOR_CARD_BORDER, dp(1))
                setPadding(dp(12), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = dp(6)
                }
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (demoEnabled) marginEnd = dp(8)
                }
            }
            val titleTv = TextView(context).apply {
                text = context.getString(R.string.settings_saved_place_row_title, place.label)
                setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, Typeface.BOLD)
            }
            val ssidTv = TextView(context).apply {
                text = ssids.ifBlank { context.getString(R.string.settings_saved_place_no_ssid) }
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(titleTv)
            textCol.addView(ssidTv)
            row.addView(textCol)

            if (demoEnabled) {
                val simBtn = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    minHeight = dp(32)
                    fun syncButtonState() {
                        val activeHere = service.isManualKnownPlaceOverrideActive() &&
                            service.buildSnapshot().matchedKnownPlace?.id == place.id
                        text = if (activeHere) {
                            context.getString(R.string.settings_sim_stop)
                        } else {
                            context.getString(R.string.settings_sim_start)
                        }
                        background = roundedRect(
                            if (activeHere) COLOR_ACCENT else COLOR_IDLE_PILL_BG,
                            dp(6)
                        )
                    }
                    syncButtonState()
                    setOnClickListener {
                        val stopping = service.isManualKnownPlaceOverrideActive() &&
                            service.buildSnapshot().matchedKnownPlace?.id == place.id
                        if (stopping) {
                            service.setManualKnownPlaceOverride(0)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_sim_stopped),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            service.setManualKnownPlaceOverride(place.id)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_sim_started, place.label),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshPlacesSection()
                    }
                }
                row.addView(simBtn)
            }

            container.addView(row)
        }
    }

    fun refreshPresetsSection() {
        val summary = presetsSummaryView ?: return
        val presets = service.metadataDb.getSegmentPresets()
        summary.text = if (presets.isEmpty()) {
            context.getString(R.string.settings_presets_empty)
        } else {
            presets.joinToString("  •  ") { "${it.iconEmoji} ${it.label}" }
        }
    }

    fun refreshAlertsSection() {
        val summary = alertsSummaryView ?: return
        val ev = service.alertEvaluator
        summary.text = context.getString(
            R.string.settings_alerts_summary,
            ev.co2WarningThresholdPpm,
            ev.co2CriticalThresholdPpm,
            ev.pm25WarningThresholdUgm3,
            ev.pm25CriticalThresholdUgm3,
            ev.batteryWarningThresholdPct,
            ev.batteryCriticalThresholdPct
        )
    }

    fun refreshDemoModeState() {
        val enabled = callbacks.isDemoDataEnabled()
        demoSwitch?.let { sw ->
            if (sw.isChecked != enabled) {
                updatingDemoSwitch = true
                sw.isChecked = enabled
                updatingDemoSwitch = false
            }
        }
        refreshPlacesSection()
        refreshAdvancedSummary()
    }

    fun refreshPermissionsState() {
        val missing = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        val vis = if (missing) View.VISIBLE else View.GONE
        enablePermissionsDivider?.visibility = vis
        enablePermissionsRow?.visibility = vis
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BAR_BG))
            setPadding(dp(8), dp(6), dp(14), dp(6))
        }
        val backBtn = TextView(context).apply {
            text = "←"
            contentDescription = context.getString(R.string.settings_back_cd)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dismiss() }
        }
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.settings_title)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(backBtn)
        bar.addView(titleTv)
        return bar
    }

    private fun buildDisplaySection(parent: LinearLayout) {
        parent.addView(buildSectionHeader(context.getString(R.string.settings_section_display), topMarginDp = 2))
        val card = buildCard()

        card.addView(buildSettingTitle(context.getString(R.string.settings_temp_unit_title)))

        val deviceSym = TemperatureUnitFormatter.unitSymbol(TemperatureUnitFormatter.deviceUseFahrenheit(context))
        val currentPref = TemperatureUnitFormatter.getPreference(context)

        val subtitleTv = buildSubtitleText(tempSubtitleFor(currentPref, deviceSym), topDp = 2, bottomDp = 10)
        card.addView(subtitleTv)

        val selector = MultiRowSegmentedSelector(
            context = context,
            initialSelection = currentPref,
            onSelected = { chosen ->
                TemperatureUnitFormatter.setPreference(context, chosen)
                subtitleTv.text = tempSubtitleFor(chosen, deviceSym)
            }
        )
        selector.addRow(
            listOf(
                TempUnitPreference.AUTO_DEVICE to context.getString(R.string.settings_temp_auto_option, deviceSym),
                TempUnitPreference.FAHRENHEIT to context.getString(R.string.settings_temp_fahrenheit_option),
                TempUnitPreference.CELSIUS to context.getString(R.string.settings_temp_celsius_option)
            )
        )
        card.addView(selector.container)

        parent.addView(card)
    }

    private fun buildPlacesAndTripsSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader(context.getString(R.string.settings_section_places_trips)))
        val card = buildCard()

        // Saved Wi-Fi Places
        card.addView(buildSettingTitle(context.getString(R.string.settings_saved_places_title)))
        card.addView(buildSubtitleText(context.getString(R.string.settings_saved_places_subtitle), topDp = 2, bottomDp = 8))

        val placesBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        placesListContainer = placesBox
        card.addView(placesBox)
        refreshPlacesSection()

        card.addView(buildActionButton(context.getString(R.string.settings_add_wifi_place_btn)) {
            callbacks.onAddWifiPlaceRequested { refreshPlacesSection() }
        })

        card.addView(buildCardDivider())

        // Auto-Trip Delay
        val debounceHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val debounceTitle = buildSettingTitle(context.getString(R.string.settings_auto_trip_delay_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val debounceInfoBtn = buildInfoIconButton(context.getString(R.string.settings_auto_trip_delay_info_cd)) {
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_auto_trip_delay_title)
                .setMessage(R.string.settings_auto_trip_delay_info_msg)
                .setPositiveButton(R.string.dialog_got_it, null)
                .show()
        }
        debounceHeaderRow.addView(debounceTitle)
        debounceHeaderRow.addView(debounceInfoBtn)
        card.addView(debounceHeaderRow)

        val currentDebounceSec = normalizeDebounceSelection(service.getWifiLossDebounceSec())
        val debounceSubtitle = buildSubtitleText(debounceSubtitleFor(currentDebounceSec), topDp = 8, bottomDp = 0)

        val debounceSelector = MultiRowSegmentedSelector(
            context = context,
            initialSelection = currentDebounceSec,
            onSelected = { sec ->
                service.setWifiLossDebounceSec(sec)
                debounceSubtitle.text = debounceSubtitleFor(sec)
            }
        )
        debounceSelector.addRow(
            MainActivity.WIFI_LOSS_DEBOUNCE_OPTIONS_SEC.map { sec ->
                sec to context.getString(R.string.settings_option_seconds, sec)
            }
        )
        card.addView(debounceSelector.container)
        card.addView(debounceSubtitle)

        card.addView(buildCardDivider())

        // Reusable Segment Presets
        card.addView(buildSettingTitle(context.getString(R.string.settings_presets_title)))
        val presetsSummary = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(0, dp(4), 0, dp(10))
        }
        presetsSummaryView = presetsSummary
        card.addView(presetsSummary)
        refreshPresetsSection()

        card.addView(buildActionButton(context.getString(R.string.settings_add_preset_btn)) {
            callbacks.onAddSegmentPresetRequested { refreshPresetsSection() }
        })

        parent.addView(card)
    }

    private fun buildBackgroundLoggingSection(parent: LinearLayout) {
        val header = buildSectionHeader(context.getString(R.string.settings_section_background))
        backgroundSectionHeader = header
        parent.addView(header)

        val card = buildCard()
        backgroundCard = card

        val companionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        companionContainer.addView(buildSettingTitle(context.getString(R.string.settings_auto_resume_title)))

        val subtitle = buildSubtitleText("", topDp = 2, bottomDp = 10)
        companionStatusSubtitle = subtitle
        companionContainer.addView(subtitle)

        val actionBtn = buildActionButton(context.getString(R.string.settings_companion_link_btn)) {
            val (_, linkedSummary) = callbacks.getCompanionAssociationStatus()
            if (linkedSummary != null) {
                callbacks.onUnlinkCompanionDeviceRequested()
            } else {
                callbacks.onLinkCompanionDeviceRequested()
            }
        }
        companionActionBtn = actionBtn
        companionContainer.addView(actionBtn)
        companionContainer.addView(buildCardDivider())
        companionSectionContainer = companionContainer
        card.addView(companionContainer)

        // Scanning on Battery
        card.addView(buildSettingTitle(context.getString(R.string.settings_battery_scan_title)))
        val currentPreferMax = service.getPreferMaxSamplesOnBattery()
        val batteryScanSubtitle = buildSubtitleText(
            batteryScanSubtitleFor(currentPreferMax),
            topDp = 2,
            bottomDp = 10
        )
        card.addView(batteryScanSubtitle)

        val batteryScanSelector = MultiRowSegmentedSelector(
            context = context,
            initialSelection = currentPreferMax,
            onSelected = { preferMax ->
                service.setPreferMaxSamplesOnBattery(preferMax)
                batteryScanSubtitle.text = batteryScanSubtitleFor(preferMax)
            }
        )
        batteryScanSelector.addRow(
            listOf(
                false to context.getString(R.string.settings_battery_scan_efficient_option),
                true to context.getString(R.string.settings_battery_scan_max_option)
            )
        )
        card.addView(batteryScanSelector.container)
        card.addView(buildCardDivider())

        card.addView(buildSettingTitle(context.getString(R.string.settings_stop_service_title)))
        card.addView(buildSubtitleText(context.getString(R.string.settings_stop_service_subtitle), topDp = 2, bottomDp = 10))
        val stopBtn = buildActionButton(context.getString(R.string.settings_stop_service_btn)) {
            if (service.buildSnapshot().activeExcursionId != null) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.dialog_stop_service_active_trip_title)
                    .setMessage(R.string.dialog_stop_service_active_trip_msg)
                    .setPositiveButton(R.string.dialog_stop_service_confirm_btn) { _, _ ->
                        callbacks.onStopServiceAndExitRequested()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                callbacks.onStopServiceAndExitRequested()
            }
        }.apply {
            setTextColor(Color.parseColor(COLOR_DANGER_TEXT))
        }
        card.addView(stopBtn)

        parent.addView(card)
        refreshCompanionAssociationState()
    }

    fun refreshCompanionAssociationState() {
        val header = backgroundSectionHeader ?: return
        val card = backgroundCard ?: return
        val companionContainer = companionSectionContainer ?: return
        val subtitle = companionStatusSubtitle ?: return
        val btn = companionActionBtn ?: return
        header.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
        val (isSupported, linkedSummary) = callbacks.getCompanionAssociationStatus()
        if (!isSupported) {
            companionContainer.visibility = View.GONE
            return
        }
        companionContainer.visibility = View.VISIBLE
        if (linkedSummary != null) {
            subtitle.text = context.getString(R.string.settings_companion_linked_subtitle, linkedSummary)
            btn.text = context.getString(R.string.settings_companion_unlink_btn)
        } else {
            val activeMonitor = service.getRecentActiveLinkableMonitor()
            if (activeMonitor != null) {
                subtitle.text = context.getString(R.string.settings_companion_active_subtitle, activeMonitor.bleAddress)
                btn.text = context.getString(R.string.settings_companion_link_active_btn, activeMonitor.bleAddress)
            } else {
                subtitle.text = context.getString(R.string.settings_companion_unlinked_subtitle)
                btn.text = context.getString(R.string.settings_companion_link_btn)
            }
        }
    }

    private fun buildAlertsSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader(context.getString(R.string.settings_section_alerts)))
        val card = buildCard()

        card.addView(buildSettingTitle(context.getString(R.string.settings_threshold_alerts_title)))
        val alertsSummary = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(6), 0, dp(10))
        }
        alertsSummaryView = alertsSummary
        card.addView(alertsSummary)
        refreshAlertsSection()

        card.addView(buildActionButton(context.getString(R.string.settings_edit_thresholds_btn)) {
            callbacks.onEditAlertThresholdsRequested { refreshAlertsSection() }
        })

        parent.addView(card)
    }

    private fun buildAdvancedSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader(context.getString(R.string.settings_section_advanced)))
        val card = buildCard()

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(36)
        }
        val headerTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        headerTextCol.addView(buildSettingTitle(context.getString(R.string.settings_advanced_title)))
        val advSummary = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        advancedSummaryView = advSummary
        headerTextCol.addView(advSummary)

        val chevron = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(2), dp(4))
        }
        advancedChevronView = chevron

        headerRow.addView(headerTextCol)
        headerRow.addView(chevron)
        card.addView(headerRow)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        advancedBodyContainer = body

        body.addView(buildCardDivider(topMarginDp = 12, bottomMarginDp = 14))

        // Monitor Keep-Alive
        var selectedStrategy = service.devConfig.strategy
        val keepAliveHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val keepAliveTitle = buildSettingTitle(
            context.getString(R.string.settings_keep_alive_title),
            textSizeSp = 14.5f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val keepAliveInfoBtn = buildInfoIconButton(context.getString(R.string.settings_keep_alive_info_cd)) {
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_keep_alive_title)
                .setMessage(selectedStrategy.getSettingsDescription(context))
                .setPositiveButton(R.string.dialog_got_it, null)
                .show()
        }
        keepAliveHeaderRow.addView(keepAliveTitle)
        keepAliveHeaderRow.addView(keepAliveInfoBtn)
        body.addView(keepAliveHeaderRow)

        val keepAliveSubtitle = buildSubtitleText(keepAliveSubtitleFor(selectedStrategy), topDp = 8, bottomDp = 0)
        val keepAliveSelector = MultiRowSegmentedSelector(
            context = context,
            initialSelection = selectedStrategy,
            onSelected = { chosen ->
                selectedStrategy = chosen
                keepAliveSubtitle.text = keepAliveSubtitleFor(chosen)
                if (chosen != service.devConfig.strategy) {
                    service.updateDevKeepAliveConfig(
                        strategy = chosen,
                        reason = "settings_screen"
                    )
                    if (chosen == KeepAlivePayloadStrategy.NONE_PASSIVE) {
                        service.setExcursionBleMode(BleCollectionMode.PASSIVE_ONLY)
                    } else if (service.buildSnapshot().userExcursionBleMode == BleCollectionMode.PASSIVE_ONLY) {
                        service.setExcursionBleMode(BleCollectionMode.PERIODIC_GATT_KEEPALIVE)
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_keep_alive_mode, chosen.getStatusBadge(context)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshAdvancedSummary()
            }
        )
        keepAliveSelector.addRow(
            listOf(
                KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP to context.getString(R.string.settings_keep_alive_auto_option),
                KeepAlivePayloadStrategy.NONE_PASSIVE to context.getString(R.string.settings_keep_alive_passive_option)
            )
        )
        val devRow = keepAliveSelector.addRow(
            listOf(
                KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 to context.getString(R.string.dev_settings_keep_alive_24m_reset_option),
                KeepAlivePayloadStrategy.TIME_SYNC_0509 to context.getString(R.string.dev_settings_keep_alive_0509_only_option)
            ),
            topMarginDp = 6
        )
        keepAliveDevRow = devRow
        body.addView(keepAliveSelector.container)
        body.addView(keepAliveSubtitle)

        body.addView(buildCardDivider())

        // Chart Gap Grace
        body.addView(buildSettingTitle(context.getString(R.string.settings_chart_gap_grace_title), textSizeSp = 14.5f).apply {
            setPadding(0, 0, 0, dp(8))
        })
        val currentGraceSec = normalizeDropoutGraceSelection(callbacks.getDropoutGracePeriodSec())
        val graceSubtitle = buildSubtitleText(dropoutGraceSubtitleFor(currentGraceSec), topDp = 8, bottomDp = 0)
        val graceSelector = MultiRowSegmentedSelector(
            context = context,
            initialSelection = currentGraceSec,
            onSelected = { sec ->
                callbacks.setDropoutGracePeriodSec(sec)
                graceSubtitle.text = dropoutGraceSubtitleFor(sec)
                refreshAdvancedSummary()
            }
        )
        graceSelector.addRow(
            listOf(
                30L to context.getString(R.string.settings_option_seconds, 30L),
                45L to context.getString(R.string.settings_option_seconds, 45L),
                90L to context.getString(R.string.settings_option_seconds, 90L),
                180L to context.getString(R.string.settings_option_3m)
            )
        )
        body.addView(graceSelector.container)
        body.addView(graceSubtitle)

        body.addView(buildCardDivider())

        // Demo Data Mode switch
        val (demoRow, demoSw) = buildSwitchRow(
            title = context.getString(R.string.settings_demo_mode_title),
            subtitle = context.getString(R.string.settings_demo_mode_subtitle),
            initialChecked = callbacks.isDemoDataEnabled()
        ) { checked ->
            if (updatingDemoSwitch) return@buildSwitchRow
            callbacks.setDemoDataEnabled(checked)
            refreshPlacesSection()
            refreshAdvancedSummary()
        }
        demoSwitch = demoSw
        body.addView(demoRow)

        // Developer Mode switch
        val (devRowContainer, devSw) = buildSwitchRow(
            title = context.getString(R.string.settings_dev_mode_title),
            subtitle = context.getString(R.string.settings_dev_mode_subtitle),
            initialChecked = callbacks.isDeveloperModeEnabled()
        ) { checked ->
            if (updatingDevSwitch) return@buildSwitchRow
            callbacks.setDeveloperModeEnabled(checked)
            syncDeveloperControlsVisibility(selectedStrategy)
            refreshAdvancedSummary()
        }
        devRowContainer.setPadding(0, dp(12), 0, 0)
        devSwitch = devSw
        body.addView(devRowContainer)

        // Anonymized Wi-Fi SSID Override
        val fakeBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val fakeLabel = TextView(context).apply {
            text = context.getString(R.string.dev_settings_fake_wifi_label)
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val fakeInput = EditText(context).apply {
            hint = context.getString(R.string.dev_settings_fake_wifi_hint)
            setText(service.fakeWifiSsidOverride ?: "")
            setSingleLine(true)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setHintTextColor(Color.parseColor(COLOR_TEXT_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = roundedRect(COLOR_NESTED_BG, dp(8), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    commitFakeWifiOverrideIfChanged()
                }
            }
        }
        fakeWifiSsidInput = fakeInput
        fakeBox.addView(fakeLabel)
        fakeBox.addView(fakeInput)
        fakeWifiContainer = fakeBox
        body.addView(fakeBox)

        card.addView(body)
        parent.addView(card)

        headerRow.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            syncAdvancedExpansionUi()
        }
        syncDeveloperControlsVisibility(selectedStrategy)
        syncAdvancedExpansionUi()
        refreshAdvancedSummary()
    }

    private fun buildHelpAndPrivacySection(parent: LinearLayout) {
        parent.addView(buildSectionHeader(context.getString(R.string.settings_section_help_privacy)))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(COLOR_CARD_BG, dp(12), COLOR_CARD_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(buildNavigationRow(context.getString(R.string.settings_help_faq_row)) {
            callbacks.onShowHelpRequested()
        })
        card.addView(buildThinSeparator(COLOR_CARD_BORDER))
        card.addView(buildNavigationRow(context.getString(R.string.settings_share_diag_log_row)) {
            callbacks.onShareDiagnosticLogRequested()
        })
        card.addView(buildThinSeparator(COLOR_CARD_BORDER))
        card.addView(buildNavigationRow(context.getString(R.string.settings_privacy_disclosure_row)) {
            callbacks.onShowPrivacyDisclosureRequested()
        })

        val permDivider = buildThinSeparator(COLOR_CARD_BORDER)
        val permRow = buildNavigationRow(context.getString(R.string.settings_enable_permissions_row)) {
            callbacks.onEnablePermissionsRequested()
        }
        enablePermissionsDivider = permDivider
        enablePermissionsRow = permRow
        card.addView(permDivider)
        card.addView(permRow)
        refreshPermissionsState()

        parent.addView(card)
    }

    private fun syncAdvancedExpansionUi() {
        advancedBodyContainer?.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
        advancedChevronView?.text = if (isAdvancedExpanded) "▾" else "▸"
    }

    private fun syncDeveloperControlsVisibility(currentStrategy: KeepAlivePayloadStrategy) {
        val devEnabled = callbacks.isDeveloperModeEnabled()
        val isDevStrategy = currentStrategy == KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 ||
            currentStrategy == KeepAlivePayloadStrategy.TIME_SYNC_0509
        keepAliveDevRow?.visibility = if (devEnabled || isDevStrategy) View.VISIBLE else View.GONE

        val hasOverride = !service.fakeWifiSsidOverride.isNullOrBlank() ||
            !fakeWifiSsidInput?.text?.toString().isNullOrBlank()
        fakeWifiContainer?.visibility = if (devEnabled || hasOverride) View.VISIBLE else View.GONE
    }

    private fun refreshAdvancedSummary() {
        val summary = advancedSummaryView ?: return
        val keepAliveLabel = when (service.devConfig.strategy) {
            KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP -> context.getString(R.string.settings_summary_auto)
            KeepAlivePayloadStrategy.NONE_PASSIVE -> context.getString(R.string.settings_summary_passive)
            KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 -> context.getString(R.string.dev_settings_keep_alive_24m_reset_option)
            KeepAlivePayloadStrategy.TIME_SYNC_0509 -> context.getString(R.string.dev_settings_summary_0509)
        }
        val graceSec = normalizeDropoutGraceSelection(callbacks.getDropoutGracePeriodSec())
        val graceLabel = if (graceSec == 180L) {
            context.getString(R.string.settings_option_3m)
        } else {
            context.getString(R.string.settings_option_seconds, graceSec)
        }
        val parts = mutableListOf(
            context.getString(R.string.settings_summary_keep_alive, keepAliveLabel),
            context.getString(R.string.settings_summary_chart_gap, graceLabel)
        )
        if (callbacks.isDemoDataEnabled()) parts.add(context.getString(R.string.settings_summary_demo))
        if (callbacks.isDeveloperModeEnabled()) parts.add(context.getString(R.string.dev_settings_summary_badge))
        summary.text = parts.joinToString(" • ")
    }

    private fun commitFakeWifiOverrideIfChanged() {
        val input = fakeWifiSsidInput ?: return
        val entered = input.text.toString().trim().ifEmpty { null }
        val current = service.fakeWifiSsidOverride?.trim()?.ifEmpty { null }
        if (entered != current) {
            service.setFakeWifiSsidOverride(entered)
            refreshPlacesSection()
        }
    }

    private fun buildSectionHeader(title: String, topMarginDp: Int = 18): TextView =
        TextView(context).apply {
            text = title
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(6)
                marginStart = dp(4)
            }
        }

    private fun buildCard(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(COLOR_CARD_BG, dp(12), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun buildSettingTitle(title: String, textSizeSp: Float = 15f): TextView =
        TextView(context).apply {
            text = title
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun buildSubtitleText(text: String, topDp: Int, bottomDp: Int): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(topDp), 0, dp(bottomDp))
        }

    private fun buildCardDivider(topMarginDp: Int = 14, bottomMarginDp: Int = 14): View =
        View(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_CARD_BORDER))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(bottomMarginDp)
            }
        }

    private fun buildThinSeparator(hexColor: String): View =
        View(context).apply {
            setBackgroundColor(Color.parseColor(hexColor))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }

    private fun buildActionButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_ACCENT_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedRect(COLOR_NESTED_BG, dp(8), COLOR_CARD_BORDER, dp(1))
            minHeight = dp(40)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildInfoIconButton(contentDesc: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = "ⓘ"
            contentDescription = contentDesc
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minWidth = dp(36)
            minHeight = dp(32)
            setPadding(dp(6), dp(2), dp(2), dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildSwitchRow(
        title: String,
        subtitle: String,
        initialChecked: Boolean,
        onCheckedChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, SwitchCompat> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(buildSettingTitle(title, textSizeSp = 14.5f))
        textCol.addView(buildSubtitleText(subtitle, topDp = 2, bottomDp = 0))

        val switch = SwitchCompat(context).apply {
            isChecked = initialChecked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor(COLOR_ACCENT_TEXT), Color.parseColor(COLOR_TEXT_SECONDARY))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor(COLOR_ACCENT), Color.parseColor(COLOR_CARD_BORDER))
            )
            setOnCheckedChangeListener { _, checked -> onCheckedChanged(checked) }
        }
        row.addView(textCol)
        row.addView(switch)
        row.setOnClickListener { switch.toggle() }
        return row to switch
    }

    private fun buildNavigationRow(title: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            val titleTv = TextView(context).apply {
                text = title
                setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            }
            val chevronTv = TextView(context).apply {
                text = "›"
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(titleTv)
            addView(chevronTv)
            setOnClickListener { onClick() }
        }

    private inner class MultiRowSegmentedSelector<T>(
        private val context: Context,
        initialSelection: T,
        private val onSelected: (T) -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        private val pills = mutableListOf<Pair<T, TextView>>()
        private var currentValue: T = initialSelection

        fun addRow(options: List<Pair<T, String>>, topMarginDp: Int = 0): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundedRect(COLOR_NESTED_BG, dp(8), COLOR_CARD_BORDER, dp(1))
                setPadding(dp(3), dp(3), dp(3), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (topMarginDp > 0) topMargin = dp(topMarginDp)
                }
            }

            for ((value, label) in options) {
                val pill = TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    minHeight = dp(36)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        if (currentValue != value) {
                            currentValue = value
                            syncVisuals()
                            onSelected(value)
                        }
                    }
                }
                pills.add(value to pill)
                row.addView(pill)
            }

            container.addView(row)
            syncVisuals()
            return row
        }

        private fun syncVisuals() {
            for ((value, pill) in pills) {
                val active = value == currentValue
                pill.isSelected = active
                if (active) {
                    pill.background = roundedRect(COLOR_ACCENT, dp(6))
                    pill.setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                    pill.setTypeface(null, Typeface.BOLD)
                } else {
                    pill.background = null
                    pill.setTextColor(Color.parseColor(COLOR_TEXT_BODY))
                    pill.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }

    private fun tempSubtitleFor(pref: TempUnitPreference, deviceSym: String): String = when (pref) {
        TempUnitPreference.AUTO_DEVICE -> context.getString(R.string.settings_temp_subtitle_auto, deviceSym)
        TempUnitPreference.FAHRENHEIT -> context.getString(R.string.settings_temp_subtitle_f)
        TempUnitPreference.CELSIUS -> context.getString(R.string.settings_temp_subtitle_c)
    }

    private fun batteryScanSubtitleFor(preferMaxSamples: Boolean): String = if (preferMaxSamples) {
        context.getString(R.string.settings_battery_scan_subtitle_max)
    } else {
        context.getString(R.string.settings_battery_scan_subtitle_efficient)
    }

    private fun normalizeDebounceSelection(sec: Long): Long =
        if (sec in MainActivity.WIFI_LOSS_DEBOUNCE_OPTIONS_SEC) sec else 30L

    private fun debounceSubtitleFor(sec: Long): String = when (sec) {
        0L -> context.getString(R.string.settings_debounce_0s)
        15L -> context.getString(R.string.settings_debounce_15s)
        30L -> context.getString(R.string.settings_debounce_30s)
        60L -> context.getString(R.string.settings_debounce_60s)
        else -> context.getString(R.string.settings_debounce_custom, sec)
    }

    private fun keepAliveSubtitleFor(strategy: KeepAlivePayloadStrategy): String = when (strategy) {
        KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP ->
            context.getString(R.string.settings_keep_alive_sub_auto)
        KeepAlivePayloadStrategy.NONE_PASSIVE ->
            context.getString(R.string.settings_keep_alive_sub_passive)
        KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 ->
            context.getString(R.string.dev_settings_keep_alive_sub_24m_reset)
        KeepAlivePayloadStrategy.TIME_SYNC_0509 ->
            context.getString(R.string.dev_settings_keep_alive_sub_0509)
    }

    private fun normalizeDropoutGraceSelection(sec: Long): Long =
        if (sec in listOf(30L, 45L, 90L, 180L)) sec else 45L

    private fun dropoutGraceSubtitleFor(sec: Long): String = when (sec) {
        30L -> context.getString(R.string.settings_grace_30s)
        45L -> context.getString(R.string.settings_grace_45s)
        90L -> context.getString(R.string.settings_grace_90s)
        180L -> context.getString(R.string.settings_grace_180s)
        else -> context.getString(R.string.settings_grace_custom, sec)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

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
        private const val COLOR_BAR_BG = "#0F172A"
        private const val COLOR_SCREEN_BG = "#0B1120"
        private const val COLOR_CARD_BG = "#1E293B"
        private const val COLOR_NESTED_BG = "#0F172A"
        private const val COLOR_CARD_BORDER = "#334155"
        private const val COLOR_IDLE_PILL_BG = "#334155"
        private const val COLOR_ACCENT = "#0284C7"
        private const val COLOR_ACCENT_TEXT = "#38BDF8"
        private const val COLOR_DANGER_TEXT = "#F87171"
        private const val COLOR_TEXT_PRIMARY = "#F8FAFC"
        private const val COLOR_TEXT_BODY = "#CBD5E1"
        private const val COLOR_TEXT_SECONDARY = "#94A3B8"
        private const val COLOR_TEXT_MUTED = "#64748B"
    }
}
