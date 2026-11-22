package io.github.androidaql.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.view.WindowInsetsController
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
        fun onAddWifiPlaceRequested(onSaved: () -> Unit)
        fun onAddSegmentPresetRequested(onSaved: () -> Unit)
        fun onEditAlertThresholdsRequested(onSaved: () -> Unit)
        fun onShowHelpRequested()
        fun onShareDiagnosticLogRequested()
        fun onShowPrivacyDisclosureRequested()
        fun onEnablePermissionsRequested()
        fun onSettingsDismissed()
    }

    private var dialog: Dialog? = null
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

        root.addView(buildTopBar())
        root.addView(buildThinSeparator(COLOR_CARD_BORDER))

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

        buildDisplaySection(content)
        buildPlacesAndTripsSection(content)
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
            insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        d.setOnDismissListener {
            commitFakeWifiOverrideIfChanged()
            dialog = null
            callbacks.onSettingsDismissed()
        }
        dialog = d
        d.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun refreshPlacesSection() {
        val container = placesListContainer ?: return
        container.removeAllViews()

        val places = service.metadataDb.getKnownPlaces()
        if (places.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "No saved places configured yet."
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
                text = "🏠 ${place.label}"
                setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, Typeface.BOLD)
            }
            val ssidTv = TextView(context).apply {
                text = ssids.ifBlank { "No Wi-Fi SSID linked" }
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
                        text = if (activeHere) "Stop Sim" else "Simulate"
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
                            Toast.makeText(context, "Stopped simulated presence", Toast.LENGTH_SHORT).show()
                        } else {
                            service.setManualKnownPlaceOverride(place.id)
                            Toast.makeText(
                                context,
                                "Simulating presence at ${place.label} (GPS Off)",
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
            "No reusable presets added yet."
        } else {
            presets.joinToString("  •  ") { "${it.iconEmoji} ${it.label}" }
        }
    }

    fun refreshAlertsSection() {
        val summary = alertsSummaryView ?: return
        val ev = service.alertEvaluator
        summary.text = buildString {
            append("CO₂:  Warn ${ev.co2WarningThresholdPpm} / Crit ${ev.co2CriticalThresholdPpm} ppm\n")
            append("PM2.5:  Warn ${ev.pm25WarningThresholdUgm3} / Crit ${ev.pm25CriticalThresholdUgm3} µg/m³\n")
            append("Battery:  Warn ≤${ev.batteryWarningThresholdPct}% / Crit ≤${ev.batteryCriticalThresholdPct}%")
        }
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
            contentDescription = "Back"
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
            text = "Settings"
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
        parent.addView(buildSectionHeader("DISPLAY", topMarginDp = 2))
        val card = buildCard()

        card.addView(buildSettingTitle("Temperature Unit"))

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
                TempUnitPreference.AUTO_DEVICE to "Auto ($deviceSym)",
                TempUnitPreference.FAHRENHEIT to "°F",
                TempUnitPreference.CELSIUS to "°C"
            )
        )
        card.addView(selector.container)

        parent.addView(card)
    }

    private fun buildPlacesAndTripsSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader("PLACES & TRIPS"))
        val card = buildCard()

        // Saved Wi-Fi Places
        card.addView(buildSettingTitle("Saved Wi-Fi Places"))
        card.addView(buildSubtitleText("Pauses GPS automatically while connected.", topDp = 2, bottomDp = 8))

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

        card.addView(buildActionButton("+ Add or Link Wi-Fi Place") {
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
        val debounceTitle = buildSettingTitle("Auto-Trip Delay").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val debounceInfoBtn = buildInfoIconButton("Auto-Trip Delay details") {
            AlertDialog.Builder(context)
                .setTitle("Auto-Trip Delay")
                .setMessage(
                    "Waits for the selected delay before starting an auto-trip and waking GPS when Saved Place Wi-Fi drops.\n\n" +
                        "Untouched auto-recorded trips under 3 minutes are automatically discarded after closing. Manual trips are always kept, and all 15-second readings remain in the 14-day ring buffer."
                )
                .setPositiveButton("Got it", null)
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
            MainActivity.WIFI_LOSS_DEBOUNCE_OPTIONS_SEC.map { sec -> sec to "${sec}s" }
        )
        card.addView(debounceSelector.container)
        card.addView(debounceSubtitle)

        card.addView(buildCardDivider())

        // Reusable Segment Presets
        card.addView(buildSettingTitle("Reusable Segment Presets"))
        val presetsSummary = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(0, dp(4), 0, dp(10))
        }
        presetsSummaryView = presetsSummary
        card.addView(presetsSummary)
        refreshPresetsSection()

        card.addView(buildActionButton("+ Add Preset") {
            callbacks.onAddSegmentPresetRequested { refreshPresetsSection() }
        })

        parent.addView(card)
    }

    private fun buildAlertsSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader("ALERTS"))
        val card = buildCard()

        card.addView(buildSettingTitle("Threshold Alerts"))
        val alertsSummary = TextView(context).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(6), 0, dp(10))
        }
        alertsSummaryView = alertsSummary
        card.addView(alertsSummary)
        refreshAlertsSection()

        card.addView(buildActionButton("Edit Thresholds") {
            callbacks.onEditAlertThresholdsRequested { refreshAlertsSection() }
        })

        parent.addView(card)
    }

    private fun buildAdvancedSection(parent: LinearLayout) {
        parent.addView(buildSectionHeader("ADVANCED"))
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
        headerTextCol.addView(buildSettingTitle("Advanced"))
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
        val keepAliveTitle = buildSettingTitle("Monitor Keep-Alive", textSizeSp = 14.5f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val keepAliveInfoBtn = buildInfoIconButton("Monitor Keep-Alive details") {
            AlertDialog.Builder(context)
                .setTitle("Monitor Keep-Alive")
                .setMessage(selectedStrategy.settingsDescription)
                .setPositiveButton("Got it", null)
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
                        "Keep-Alive mode: ${chosen.statusBadge}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshAdvancedSummary()
            }
        )
        keepAliveSelector.addRow(
            listOf(
                KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP to "Auto Setup",
                KeepAlivePayloadStrategy.NONE_PASSIVE to "Passive Only"
            )
        )
        val devRow = keepAliveSelector.addRow(
            listOf(
                KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 to "24m Reset",
                KeepAlivePayloadStrategy.TIME_SYNC_0509 to "05 09 Only"
            ),
            topMarginDp = 6
        )
        keepAliveDevRow = devRow
        body.addView(keepAliveSelector.container)
        body.addView(keepAliveSubtitle)

        body.addView(buildCardDivider())

        // Chart Gap Grace
        body.addView(buildSettingTitle("Chart Gap Grace", textSizeSp = 14.5f).apply {
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
                30L to "30s",
                45L to "45s",
                90L to "90s",
                180L to "3m"
            )
        )
        body.addView(graceSelector.container)
        body.addView(graceSubtitle)

        body.addView(buildCardDivider())

        // Demo Data Mode switch
        val (demoRow, demoSw) = buildSwitchRow(
            title = "Demo Data Mode",
            subtitle = "Synthetic sensor stream, sample trip & place simulator",
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
            title = "Developer Mode",
            subtitle = "Show HW Test tab & raw BLE logs",
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
            text = "Anonymized Wi-Fi SSID Override (Screenshots/Video)"
            setTextColor(Color.parseColor(COLOR_TEXT_BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val fakeInput = EditText(context).apply {
            hint = "Leave blank for real SSID"
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
        parent.addView(buildSectionHeader("HELP & PRIVACY"))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(COLOR_CARD_BG, dp(12), COLOR_CARD_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(buildNavigationRow("Help, FAQ & User Guides") {
            callbacks.onShowHelpRequested()
        })
        card.addView(buildThinSeparator(COLOR_CARD_BORDER))
        card.addView(buildNavigationRow("Share Diagnostic Log (JSONL)") {
            callbacks.onShareDiagnosticLogRequested()
        })
        card.addView(buildThinSeparator(COLOR_CARD_BORDER))
        card.addView(buildNavigationRow("Location, Activity & Privacy Disclosure") {
            callbacks.onShowPrivacyDisclosureRequested()
        })

        val permDivider = buildThinSeparator(COLOR_CARD_BORDER)
        val permRow = buildNavigationRow("Enable Location & Step Tracking") {
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
            KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP -> "Auto"
            KeepAlivePayloadStrategy.NONE_PASSIVE -> "Passive"
            KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 -> "24m Reset"
            KeepAlivePayloadStrategy.TIME_SYNC_0509 -> "05 09"
        }
        val graceSec = normalizeDropoutGraceSelection(callbacks.getDropoutGracePeriodSec())
        val graceLabel = if (graceSec == 180L) "3m" else "${graceSec}s"
        val parts = mutableListOf("Keep-Alive: $keepAliveLabel", "Chart Gap: $graceLabel")
        if (callbacks.isDemoDataEnabled()) parts.add("Demo")
        if (callbacks.isDeveloperModeEnabled()) parts.add("Dev")
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
        TempUnitPreference.AUTO_DEVICE -> "Following device regional settings (currently $deviceSym)."
        TempUnitPreference.FAHRENHEIT -> "Always display temperatures in Fahrenheit (°F)."
        TempUnitPreference.CELSIUS -> "Always display temperatures in Celsius (°C)."
    }

    private fun normalizeDebounceSelection(sec: Long): Long =
        if (sec in MainActivity.WIFI_LOSS_DEBOUNCE_OPTIONS_SEC) sec else 30L

    private fun debounceSubtitleFor(sec: Long): String = when (sec) {
        0L -> "Start immediately on Wi-Fi drop (captures elevators & hallways)."
        15L -> "Ignore brief 15s Wi-Fi glitches before starting a trip."
        30L -> "Default — wait 30s before starting an auto-trip & GPS."
        60L -> "Only start an auto-trip after 1 minute away from Wi-Fi."
        else -> "Wait ${sec}s before starting an auto-trip."
    }

    private fun keepAliveSubtitleFor(strategy: KeepAlivePayloadStrategy): String = when (strategy) {
        KeepAlivePayloadStrategy.AUTO_OFFLINE_SETUP ->
            "Runs a ~5s offline setup when needed, then listens passively."
        KeepAlivePayloadStrategy.NONE_PASSIVE ->
            "Passive listener only — never initiates GATT connections."
        KeepAlivePayloadStrategy.TIME_SYNC_0509_PLUS_24M_1101 ->
            "Dev: resets 10m & 30m BLE windows every 24m (11 01)."
        KeepAlivePayloadStrategy.TIME_SYNC_0509 ->
            "Dev: sends 05 09 time sync every 3.5m (<30m ceiling)."
    }

    private fun normalizeDropoutGraceSelection(sec: Long): Long =
        if (sec in listOf(30L, 45L, 90L, 180L)) sec else 45L

    private fun dropoutGraceSubtitleFor(sec: Long): String = when (sec) {
        30L -> "Bridges brief dropouts up to 30s (2 missed 15s slots)."
        45L -> "Default — bridges dropouts up to 45s (3 missed slots)."
        90L -> "Bridges dropouts up to 90s (matches Trip Pause threshold)."
        180L -> "Bridges dropouts up to 3 minutes (180s)."
        else -> "Bridges dropouts up to ${sec}s."
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
        private const val COLOR_TEXT_PRIMARY = "#F8FAFC"
        private const val COLOR_TEXT_BODY = "#CBD5E1"
        private const val COLOR_TEXT_SECONDARY = "#94A3B8"
        private const val COLOR_TEXT_MUTED = "#64748B"
    }
}
