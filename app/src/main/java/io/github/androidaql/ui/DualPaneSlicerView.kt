package io.github.androidaql.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.SliceCategory
import io.github.androidaql.util.TemperatureUnitFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Synchronized 3-pane timeline chart & interactive session slicer:
 * - Pane 1 (Top, 42%): CO₂ (ppm, blue) & PM2.5 (µg/m³, purple) with 1000 ppm reference threshold.
 * - Pane 2 (Middle, 29%): Temperature (°C, orange) & Relative Humidity (%, cyan) to highlight
 *   indoor/outdoor HVAC transitions when GPS is degraded indoors.
 * - Pane 3 (Bottom, 29%): Movement Speed (m/s, green) with shaded "Weak GPS (>50m Urban Canyon)" bands.
 * - Supports thumb-dragging shared dividers between adjacent segments and tapping/scrubbing a cursor timestamp.
 */
class DualPaneSlicerView(context: Context) : View(context) {

    var slots: List<AqlRingBufferStore.RingSlotRecord> = emptyList()
        set(value) {
            field = value
            if (value.isNotEmpty() && cursorEpochSec < value.first().bucketEpochSec) {
                cursorEpochSec = value[value.size / 2].bucketEpochSec
            }
            invalidate()
        }

    var slices: List<SessionSliceRecord> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var cursorEpochSec: Long = 0L
        private set

    var selectedDividerIndex: Int = -1
        private set

    var isEditMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedDividerIndex = -1
                draggingDividerIdx = -1
            }
            invalidate()
        }

    var desiredHeightDp: Int = 370
        set(value) {
            field = value
            requestLayout()
        }

    var emptyStateMessage: String = "No samples in selected window."
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Configurable grace period (in seconds) before a gap between adjacent samples is rendered
     * as a telemetry dropout with dashed interpolation lines and vertical dropout shading.
     * Default: 45s (3 Archive 0 slots).
     */
    var dropoutGracePeriodSec: Long = 45L
        set(value) {
            field = max(15L, value)
            invalidate()
        }

    /**
     * Optional explicit time-axis bounds (in epoch seconds). When set (e.g. on the Live "Last 15 Minutes"
     * chart), the horizontal axis spans [fixedWindowStartEpochSec, fixedWindowEndEpochSec] even if
     * samples only cover a portion of that window.
     */
    var fixedWindowStartEpochSec: Long? = null
    var fixedWindowEndEpochSec: Long? = null

    var onCursorChanged: ((Long) -> Unit)? = null
    var onSharedDividerMoved: ((leftSliceId: Long, rightSliceId: Long, newEpochSec: Long) -> Unit)? = null
    var onDividerSelected: ((dividerIndex: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val padLeft = 46f * density
    private val padRight = 16f * density
    private val padTop = 24f * density
    private val padBottom = 26f * density
    private val paneGap = 14f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val co2LinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2.4f * density
        style = Paint.Style.STROKE
    }
    private val pm25LinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA")
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }
    private val tempLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FB923C")
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val humidityLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2DD4BF")
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }
    private val speedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val interpDashEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    private val co2InterpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8838BDF8")
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
        pathEffect = interpDashEffect
    }
    private val pm25InterpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88A78BFA")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = interpDashEffect
    }
    private val tempInterpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FB923C")
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        pathEffect = interpDashEffect
    }
    private val humidityInterpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#882DD4BF")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = interpDashEffect
    }
    private val speedInterpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8834D399")
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        pathEffect = interpDashEffect
    }
    private val dropoutBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E94A3B8")
        style = Paint.Style.FILL
    }
    private val dropoutBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5594A3B8")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val weakGpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44EF4444")
        style = Paint.Style.FILL
    }
    private val gpsNotCollectedBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#381E293B")
        style = Paint.Style.FILL
    }
    private val gpsNotCollectedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6664748B")
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val gpsNotCollectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 10.5f * density
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 10f * density
        isFakeBoldText = true
    }
    private val segmentStripFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val segmentStripBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val segmentStripTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val co2Path = Path()
    private val pm25Path = Path()
    private val tempPath = Path()
    private val humidityPath = Path()
    private val speedPath = Path()
    private val co2InterpPath = Path()
    private val pm25InterpPath = Path()
    private val tempInterpPath = Path()
    private val humidityInterpPath = Path()
    private val speedInterpPath = Path()
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneId.systemDefault())
    private val exactTimeFmt = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())
    private val timeBubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        style = Paint.Style.FILL
    }
    private var draggingDividerIdx: Int = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (desiredHeightDp * density).toInt()
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(desiredH, hSize)
            else -> desiredH
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0F172A"))

        if (slots.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                emptyStateMessage,
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        val minTs = fixedWindowStartEpochSec ?: slots.first().bucketEpochSec
        val maxTs = max(minTs + 15L, fixedWindowEndEpochSec ?: slots.last().bucketEpochSec)
        val plotW = max(1f, width - padLeft - padRight)
        val hasSegmentStrip = slices.isNotEmpty()
        val effectivePadBottom = if (hasSegmentStrip) 48f * density else padBottom
        val totalPlotH = max(1f, height - padTop - effectivePadBottom - 2f * paneGap)

        val topPaneH = totalPlotH * 0.42f
        val midPaneH = totalPlotH * 0.29f
        val botPaneH = totalPlotH * 0.29f

        val topPaneTop = padTop
        val topPaneBot = topPaneTop + topPaneH
        val midPaneTop = topPaneBot + paneGap
        val midPaneBot = midPaneTop + midPaneH
        val botPaneTop = midPaneBot + paneGap
        val botPaneBot = botPaneTop + botPaneH

        fun tsToX(ts: Long): Float {
            val ratio = (ts - minTs).toFloat() / (maxTs - minTs).toFloat()
            return padLeft + ratio.coerceIn(0f, 1f) * plotW
        }

        // 1. Draw Slice Background Zones across all 3 panes
        for ((idx, slice) in slices.withIndex()) {
            val x1 = tsToX(slice.startEpochSec)
            val x2 = tsToX(slice.endEpochSec)
            bgPaint.color = when (slice.category) {
                SliceCategory.TRANSIT -> Color.parseColor("#1E3A8A33")
                SliceCategory.DESTINATION -> Color.parseColor("#065F4633")
                SliceCategory.OUTDOOR_WALK -> Color.parseColor("#713F1233")
                SliceCategory.OTHER -> if (idx % 2 == 0) Color.parseColor("#1E293B66") else Color.parseColor("#33415555")
            }
            canvas.drawRect(x1, topPaneTop, x2, topPaneBot, bgPaint)
            canvas.drawRect(x1, midPaneTop, x2, midPaneBot, bgPaint)
            canvas.drawRect(x1, botPaneTop, x2, botPaneBot, bgPaint)
        }

        // 1b. Draw Telemetry Dropout Shading Bands across all 3 panes when gap > dropoutGracePeriodSec
        for (i in 1 until slots.size) {
            val prevTs = slots[i - 1].bucketEpochSec
            val curTs = slots[i].bucketEpochSec
            if (curTs - prevTs > dropoutGracePeriodSec) {
                val x1 = tsToX(prevTs)
                val x2 = tsToX(curTs)
                canvas.drawRect(x1, topPaneTop, x2, topPaneBot, dropoutBandPaint)
                canvas.drawRect(x1, midPaneTop, x2, midPaneBot, dropoutBandPaint)
                canvas.drawRect(x1, botPaneTop, x2, botPaneBot, dropoutBandPaint)
                canvas.drawLine(x1, topPaneTop, x1, botPaneBot, dropoutBorderPaint)
                canvas.drawLine(x2, topPaneTop, x2, botPaneBot, dropoutBorderPaint)
            }
        }

        // 2. Draw pane frames
        canvas.drawRect(padLeft, topPaneTop, width - padRight, topPaneBot, gridPaint)
        canvas.drawRect(padLeft, midPaneTop, width - padRight, midPaneBot, gridPaint)
        canvas.drawRect(padLeft, botPaneTop, width - padRight, botPaneBot, gridPaint)

        // Reserve 14dp at the top of each pane for the pane header text so curves don't collide with labels
        val headerBandPx = 14f * density

        // 3. Pane 1 (Top): CO2 (ppm) & PM2.5
        val maxCo2 = max(1600, slots.maxOf { it.co2PpmMax } + 150)
        val minCo2 = min(400, slots.minOf { it.co2PpmMin })
        fun co2ToY(co2: Int): Float {
            val r = (co2 - minCo2).toFloat() / max(1, maxCo2 - minCo2).toFloat()
            return topPaneBot - r.coerceIn(0f, 1f) * max(1f, topPaneH - headerBandPx)
        }

        if (1000 in minCo2..maxCo2) {
            val y1000 = co2ToY(1000)
            canvas.drawLine(padLeft, y1000, width - padRight, y1000, thresholdPaint)
        }

        co2Path.reset()
        pm25Path.reset()
        co2InterpPath.reset()
        pm25InterpPath.reset()
        val maxPm25 = max(35f, slots.maxOf { it.pm25Max }.toFloat() + 5f)
        var prevX = 0f
        var prevYCo2 = 0f
        var prevYPm = 0f
        for ((i, s) in slots.withIndex()) {
            val x = tsToX(s.bucketEpochSec)
            val yCo2 = co2ToY(s.co2PpmMean)
            val yPm = topPaneBot - (s.pm25Mean / maxPm25).coerceIn(0f, 1f) * max(1f, topPaneH - headerBandPx)
            if (i == 0) {
                co2Path.moveTo(x, yCo2)
                pm25Path.moveTo(x, yPm)
            } else {
                val gapSec = s.bucketEpochSec - slots[i - 1].bucketEpochSec
                if (gapSec > dropoutGracePeriodSec) {
                    co2InterpPath.moveTo(prevX, prevYCo2)
                    co2InterpPath.lineTo(x, yCo2)
                    pm25InterpPath.moveTo(prevX, prevYPm)
                    pm25InterpPath.lineTo(x, yPm)
                    co2Path.moveTo(x, yCo2)
                    pm25Path.moveTo(x, yPm)
                } else {
                    co2Path.lineTo(x, yCo2)
                    pm25Path.lineTo(x, yPm)
                }
            }
            prevX = x
            prevYCo2 = yCo2
            prevYPm = yPm
        }
        canvas.drawPath(pm25InterpPath, pm25InterpPaint)
        canvas.drawPath(co2InterpPath, co2InterpPaint)
        canvas.drawPath(pm25Path, pm25LinePaint)
        canvas.drawPath(co2Path, co2LinePaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("$maxCo2", padLeft - 4f * density, topPaneTop + 10f * density, textPaint)
        canvas.drawText("$minCo2", padLeft - 4f * density, topPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("CO₂ (ppm, blue) & PM2.5 (purple) • Dashed = Dropout (> ${dropoutGracePeriodSec}s)", padLeft + 4f * density, topPaneTop + 11f * density, textPaint)

        // 4. Pane 2 (Middle): Temperature (°F or °C per device/user setting, orange) & Relative Humidity (%, cyan)
        val useFahrenheit = TemperatureUnitFormatter.useFahrenheit(context)
        val unitSym = TemperatureUnitFormatter.unitSymbol(useFahrenheit)
        val minTemp = min(0f, slots.minOf { it.tempCMean } - 2f)
        val maxTemp = max(28f, slots.maxOf { it.tempCMean } + 2f)
        fun tempToY(tC: Float): Float {
            val r = (tC - minTemp) / max(1f, maxTemp - minTemp)
            return midPaneBot - r.coerceIn(0f, 1f) * max(1f, midPaneH - headerBandPx)
        }
        fun humToY(rh: Float): Float {
            val r = (rh / 100f).coerceIn(0f, 1f)
            return midPaneBot - r * max(1f, midPaneH - headerBandPx)
        }

        tempPath.reset()
        humidityPath.reset()
        tempInterpPath.reset()
        humidityInterpPath.reset()
        var prevYT = 0f
        var prevYH = 0f
        for ((i, s) in slots.withIndex()) {
            val x = tsToX(s.bucketEpochSec)
            val yT = tempToY(s.tempCMean)
            val yH = humToY(s.humidityPctMean)
            if (i == 0) {
                tempPath.moveTo(x, yT)
                humidityPath.moveTo(x, yH)
            } else {
                val gapSec = s.bucketEpochSec - slots[i - 1].bucketEpochSec
                if (gapSec > dropoutGracePeriodSec) {
                    tempInterpPath.moveTo(prevX, prevYT)
                    tempInterpPath.lineTo(x, yT)
                    humidityInterpPath.moveTo(prevX, prevYH)
                    humidityInterpPath.lineTo(x, yH)
                    tempPath.moveTo(x, yT)
                    humidityPath.moveTo(x, yH)
                } else {
                    tempPath.lineTo(x, yT)
                    humidityPath.lineTo(x, yH)
                }
            }
            prevX = x
            prevYT = yT
            prevYH = yH
        }
        canvas.drawPath(humidityInterpPath, humidityInterpPaint)
        canvas.drawPath(tempInterpPath, tempInterpPaint)
        canvas.drawPath(humidityPath, humidityLinePaint)
        canvas.drawPath(tempPath, tempLinePaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(TemperatureUnitFormatter.format(maxTemp, useFahrenheit, decimals = 0), padLeft - 4f * density, midPaneTop + 10f * density, textPaint)
        canvas.drawText(TemperatureUnitFormatter.format(minTemp, useFahrenheit, decimals = 0), padLeft - 4f * density, midPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Temp ($unitSym, orange) & RH (%, cyan)", padLeft + 4f * density, midPaneTop + 11f * density, textPaint)

        // 5. Pane 3 (Bottom): Speed (m/s) + Weak GPS (>50m accuracy) + Location Not Collected (Saved Place / GPS Off)
        val maxSpeed = max(12f, (slots.mapNotNull { if (it.knownPlaceId == 0) it.speedMps else null }.maxOrNull() ?: 5f) + 2f)
        fun speedToY(spd: Float): Float {
            val r = (spd / maxSpeed).coerceIn(0f, 1f)
            return botPaneBot - r * max(1f, botPaneH - headerBandPx)
        }
        fun isLocationCollected(s: AqlRingBufferStore.RingSlotRecord): Boolean =
            s.knownPlaceId == 0 && s.speedMps != null

        val stepWidthPx = max(2f, plotW / max(1, slots.size))

        // 5a. Render "Location Not Collected (GPS Off)" spans across Pane 3
        var spanStartIdx = -1
        var spanHasSavedPlace = false
        val midBotY = botPaneTop + headerBandPx + max(1f, botPaneH - headerBandPx) * 0.5f
        fun flushGpsNotCollectedSpan(endIdx: Int) {
            if (spanStartIdx < 0) return
            val x1 = if (spanStartIdx == 0) padLeft else max(padLeft, tsToX(slots[spanStartIdx].bucketEpochSec) - stepWidthPx / 2f)
            val x2 = if (endIdx == slots.lastIndex) (width - padRight) else min(width - padRight, tsToX(slots[endIdx].bucketEpochSec) + stepWidthPx / 2f)
            if (x2 > x1) {
                canvas.drawRect(x1, botPaneTop + headerBandPx, x2, botPaneBot, gpsNotCollectedBandPaint)
                canvas.drawLine(x1, midBotY, x2, midBotY, gpsNotCollectedLinePaint)
                val spanW = x2 - x1
                if (spanW >= 56f * density) {
                    val label = if (spanHasSavedPlace) "🏠 GPS Off (Saved Place)" else "GPS Off"
                    canvas.drawText(label, (x1 + x2) / 2f, midBotY - 4f * density, gpsNotCollectedTextPaint)
                }
            }
            spanStartIdx = -1
            spanHasSavedPlace = false
        }
        for ((i, s) in slots.withIndex()) {
            if (!isLocationCollected(s)) {
                if (spanStartIdx < 0) spanStartIdx = i
                if (s.knownPlaceId > 0) spanHasSavedPlace = true
            } else {
                flushGpsNotCollectedSpan(i - 1)
            }
        }
        flushGpsNotCollectedSpan(slots.lastIndex)

        // 5b. Render active Speed curve and Weak GPS (>50m) bands only where location is collected
        speedPath.reset()
        speedInterpPath.reset()
        var hasPrevCollectedSpeed = false
        var prevCollectedTs = 0L
        var prevYSpd = 0f
        for (s in slots) {
            val x = tsToX(s.bucketEpochSec)
            if (!isLocationCollected(s)) {
                hasPrevCollectedSpeed = false
                continue
            }
            val acc = s.gpsAccuracyM
            if (acc != null && acc > 50f) {
                canvas.drawRect(
                    x - stepWidthPx / 2f,
                    botPaneTop,
                    x + stepWidthPx / 2f,
                    botPaneBot,
                    weakGpsPaint
                )
            }
            val spd = s.speedMps ?: 0f
            val ySpd = speedToY(spd)
            if (!hasPrevCollectedSpeed) {
                speedPath.moveTo(x, ySpd)
                hasPrevCollectedSpeed = true
            } else {
                val gapSec = s.bucketEpochSec - prevCollectedTs
                if (gapSec > dropoutGracePeriodSec) {
                    speedInterpPath.moveTo(prevX, prevYSpd)
                    speedInterpPath.lineTo(x, ySpd)
                    speedPath.moveTo(x, ySpd)
                } else {
                    speedPath.lineTo(x, ySpd)
                }
            }
            prevX = x
            prevCollectedTs = s.bucketEpochSec
            prevYSpd = ySpd
        }
        canvas.drawPath(speedInterpPath, speedInterpPaint)
        canvas.drawPath(speedPath, speedLinePaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.0fm/s", maxSpeed), padLeft - 4f * density, botPaneTop + 10f * density, textPaint)
        canvas.drawText("0", padLeft - 4f * density, botPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Speed (green) • Slate = GPS Off • Red = Weak GPS", padLeft + 4f * density, botPaneTop + 11f * density, textPaint)

        // 5c. Segment Strip beneath Pane 3 (shows numbered intervals #1, #2, ... and their boundaries)
        val segTop = botPaneBot + 4f * density
        val segBot = segTop + 20f * density
        if (hasSegmentStrip) {
            for ((idx, slice) in slices.withIndex()) {
                val x1 = tsToX(slice.startEpochSec) + 1f * density
                val x2 = tsToX(slice.endEpochSec) - 1f * density
                val barW = x2 - x1
                if (barW <= 2f * density) continue
                val isCursorInside = cursorEpochSec in slice.startEpochSec..slice.endEpochSec
                segmentStripFillPaint.color = when (slice.category) {
                    SliceCategory.TRANSIT -> Color.parseColor("#1E3A8A")
                    SliceCategory.DESTINATION -> Color.parseColor("#065F46")
                    SliceCategory.OUTDOOR_WALK -> Color.parseColor("#78350F")
                    SliceCategory.OTHER -> if (idx % 2 == 0) Color.parseColor("#1E293B") else Color.parseColor("#334155")
                }
                segmentStripBorderPaint.color = if (isCursorInside) {
                    Color.parseColor("#38BDF8")
                } else {
                    Color.parseColor("#475569")
                }
                segmentStripBorderPaint.strokeWidth = if (isCursorInside) 1.5f * density else 1f * density
                val segRect = RectF(x1, segTop, x2, segBot)
                canvas.drawRoundRect(segRect, 4f * density, 4f * density, segmentStripFillPaint)
                canvas.drawRoundRect(segRect, 4f * density, 4f * density, segmentStripBorderPaint)

                if (barW >= 14f * density) {
                    val availW = barW - 8f * density
                    val fullTag = "#${idx + 1} ${slice.label}"
                    val shortTag = "#${idx + 1}"
                    val fitted = fitLabelToWidth(fullTag, availW, segmentStripTextPaint)
                    val labelToDraw = if (fitted.isNotEmpty()) {
                        fitted
                    } else if (segmentStripTextPaint.measureText(shortTag) <= availW) {
                        shortTag
                    } else {
                        ""
                    }
                    if (labelToDraw.isNotEmpty()) {
                        canvas.drawText(labelToDraw, (x1 + x2) / 2f, segTop + 14f * density, segmentStripTextPaint)
                    }
                }
            }
        }

        // 6. Shared Dividers & Thumb Handles across all 3 panes (+ Segment Strip)
        val dividerBottomY = if (hasSegmentStrip) segBot else botPaneBot
        for (i in 0 until slices.size - 1) {
            val boundaryTs = slices[i].endEpochSec
            val x = tsToX(boundaryTs)
            dividerPaint.color = if (isEditMode && i == selectedDividerIndex) Color.parseColor("#F97316") else Color.parseColor("#FBBF24")
            canvas.drawLine(x, topPaneTop, x, dividerBottomY, dividerPaint)

            if (isEditMode) {
                val divText = "⇔ ${i + 1}|${i + 2}"
                val halfW = max(24f * density, badgeTextPaint.measureText(divText) * 0.5f + 7f * density)
                val cxPill = x.coerceIn(halfW + 2f * density, width - halfW - 2f * density)
                val pillRect = RectF(
                    cxPill - halfW,
                    topPaneBot - 1f * density,
                    cxPill + halfW,
                    midPaneTop + 1f * density
                )
                handlePaint.color = if (i == selectedDividerIndex) Color.parseColor("#F97316") else Color.parseColor("#FBBF24")
                canvas.drawRoundRect(pillRect, 7f * density, 7f * density, handlePaint)
                badgeTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(divText, cxPill, topPaneBot + 10f * density, badgeTextPaint)
            }
        }

        // 7. Interactive Scrubber Cursor, Top Floating Time Bubble & Inline Intersection Value Badges
        if (cursorEpochSec in minTs..maxTs) {
            val cx = tsToX(cursorEpochSec)
            canvas.drawLine(cx, topPaneTop, cx, botPaneBot, cursorPaint)
            val nearest = slots.minByOrNull { abs(it.bucketEpochSec - cursorEpochSec) }
            if (nearest != null) {
                var inDropoutGapSec = 0L
                for (i in 1 until slots.size) {
                    val t0 = slots[i - 1].bucketEpochSec
                    val t1 = slots[i].bucketEpochSec
                    if (t1 - t0 > dropoutGracePeriodSec && cursorEpochSec > (t0 + 7L) && cursorEpochSec < (t1 - 7L)) {
                        inDropoutGapSec = t1 - t0
                        break
                    }
                }
                val baseTimeLabel = exactTimeFmt.format(Instant.ofEpochSecond(
                    if (inDropoutGapSec > 0L) cursorEpochSec else nearest.bucketEpochSec
                ))
                val timeLabel = if (inDropoutGapSec > 0L) {
                    "$baseTimeLabel • DROPOUT (${inDropoutGapSec}s gap)"
                } else {
                    baseTimeLabel
                }
                timeBubbleBgPaint.color = if (inDropoutGapSec > 0L) Color.parseColor("#FDE68A") else Color.parseColor("#F8FAFC")
                val tbW = badgeTextPaint.measureText(timeLabel) + 12f * density
                val tbH = 17f * density
                val tbX = (cx - tbW / 2f).coerceIn(padLeft, width - padRight - tbW)
                val tbRect = RectF(tbX, 3f * density, tbX + tbW, 3f * density + tbH)
                canvas.drawRoundRect(tbRect, 5f * density, 5f * density, timeBubbleBgPaint)
                badgeTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(timeLabel, tbX + tbW / 2f, 15f * density, badgeTextPaint)

                // Compute intersection points for each curve
                val yCo2 = co2ToY(nearest.co2PpmMean)
                val yPm25 = topPaneBot - (nearest.pm25Mean / maxPm25).coerceIn(0f, 1f) * max(1f, topPaneH - headerBandPx)
                val yTemp = tempToY(nearest.tempCMean)
                val yHum = humToY(nearest.humidityPctMean)
                val spd = nearest.speedMps ?: 0f
                val ySpd = speedToY(spd)

                // Avoid overlap between CO2 and PM2.5 badges in Pane 1 (even when both are clamped near top/bottom)
                val badgeMarginPx = 9f * density
                val minBadgeDistPx = 19.5f * density
                val (labelYCo2, labelYPm25) = resolveNonOverlappingBadgePair(
                    yA = yCo2,
                    yB = yPm25,
                    minY = topPaneTop + badgeMarginPx,
                    maxY = topPaneBot - badgeMarginPx,
                    minDist = minBadgeDistPx
                )

                // Avoid overlap between Temp and Humidity badges in Pane 2 (even when both are clamped near top/bottom)
                val (labelYTemp, labelYHum) = resolveNonOverlappingBadgePair(
                    yA = yTemp,
                    yB = yHum,
                    minY = midPaneTop + badgeMarginPx,
                    maxY = midPaneBot - badgeMarginPx,
                    minDist = minBadgeDistPx
                )

                val placeLeft = cx > (width - padRight - 96f * density)
                drawIntersectionCallout(
                    canvas, cx, yCo2, labelYCo2,
                    "${nearest.co2PpmMean} ppm", Color.parseColor("#38BDF8"), placeLeft
                )
                drawIntersectionCallout(
                    canvas, cx, yPm25, labelYPm25,
                    String.format(Locale.US, "%.0f µg/m³", nearest.pm25Mean), Color.parseColor("#A78BFA"), placeLeft
                )
                drawIntersectionCallout(
                    canvas, cx, yTemp, labelYTemp,
                    TemperatureUnitFormatter.format(nearest.tempCMean, useFahrenheit, decimals = 1), Color.parseColor("#FB923C"), placeLeft
                )
                drawIntersectionCallout(
                    canvas, cx, yHum, labelYHum,
                    String.format(Locale.US, "%.0f%%", nearest.humidityPctMean), Color.parseColor("#2DD4BF"), placeLeft
                )
                if (isLocationCollected(nearest)) {
                    val acc = nearest.gpsAccuracyM
                    val spdBadge = if (acc != null && acc > 50f) {
                        String.format(Locale.US, "%.1fm/s (±%.0fm)", spd, acc)
                    } else {
                        String.format(Locale.US, "%.1fm/s", spd)
                    }
                    drawIntersectionCallout(
                        canvas, cx, ySpd, ySpd.coerceIn(botPaneTop + badgeMarginPx, botPaneBot - badgeMarginPx),
                        spdBadge, Color.parseColor("#34D399"), placeLeft
                    )
                } else {
                    val gpsOffBadge = if (nearest.knownPlaceId > 0) "🏠 GPS Off (Saved Place)" else "GPS Off"
                    drawIntersectionCallout(
                        canvas, cx, midBotY, midBotY.coerceIn(botPaneTop + badgeMarginPx, botPaneBot - badgeMarginPx),
                        gpsOffBadge, Color.parseColor("#94A3B8"), placeLeft
                    )
                }
            }
        }

        // 8. Time Axis Labels
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(timeFmt.format(Instant.ofEpochSecond(minTs)), padLeft, height - 6f * density, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeFmt.format(Instant.ofEpochSecond(maxTs)), width - padRight, height - 6f * density, textPaint)
    }

    private val calloutBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60F172A")
        style = Paint.Style.FILL
    }
    private val calloutBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    private val calloutDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private fun drawIntersectionCallout(
        canvas: Canvas,
        cx: Float,
        curveY: Float,
        badgeCenterY: Float,
        label: String,
        seriesColor: Int,
        placeLeft: Boolean
    ) {
        // Draw intersection circle on the curve
        calloutDotPaint.color = seriesColor
        canvas.drawCircle(cx, curveY, 3.8f * density, calloutDotPaint)

        // Draw pill label next to the intersection (with leader line when displaced vertically)
        val textW = calloutTextPaint.measureText(label)
        val boxW = textW + 10f * density
        val boxH = 16f * density
        val horizGap = 8f * density
        val boxX = if (placeLeft) (cx - boxW - horizGap) else (cx + horizGap)
        val boxY = badgeCenterY - boxH / 2f
        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)

        calloutBorderPaint.color = seriesColor
        calloutTextPaint.color = seriesColor
        if (abs(badgeCenterY - curveY) > 1.5f * density) {
            val anchorX = if (placeLeft) (boxX + boxW) else boxX
            canvas.drawLine(cx, curveY, anchorX, badgeCenterY, calloutBorderPaint)
        }
        canvas.drawRoundRect(rect, 4f * density, 4f * density, calloutBoxPaint)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, calloutBorderPaint)
        canvas.drawText(label, boxX + 5f * density, boxY + 11.5f * density, calloutTextPaint)
    }

    fun nudgeCursor(deltaSec: Long) {
        if (slots.isEmpty()) return
        val minTs = slots.first().bucketEpochSec
        val maxTs = slots.last().bucketEpochSec
        cursorEpochSec = (cursorEpochSec + deltaSec).coerceIn(minTs, maxTs)
        onCursorChanged?.invoke(cursorEpochSec)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        val minTs = fixedWindowStartEpochSec ?: slots.first().bucketEpochSec
        val maxTs = max(minTs + 15L, fixedWindowEndEpochSec ?: slots.last().bucketEpochSec)
        val plotW = max(1f, width - padLeft - padRight)

        fun xToTs(x: Float): Long {
            val ratio = ((x - padLeft) / plotW).coerceIn(0f, 1f)
            val rawTs = minTs + (ratio * (maxTs - minTs)).toLong()
            return (rawTs / 15L) * 15L
        }

        fun tsToX(ts: Long): Float {
            val ratio = (ts - minTs).toFloat() / (maxTs - minTs).toFloat()
            return padLeft + ratio.coerceIn(0f, 1f) * plotW
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var hitDivider = -1
                if (isEditMode) {
                    var bestDist = 28f * density
                    for (i in 0 until slices.size - 1) {
                        val dx = abs(event.x - tsToX(slices[i].endEpochSec))
                        if (dx <= bestDist) {
                            bestDist = dx
                            hitDivider = i
                        }
                    }
                }
                if (isEditMode && hitDivider >= 0) {
                    draggingDividerIdx = hitDivider
                    selectedDividerIndex = hitDivider
                    onDividerSelected?.invoke(hitDivider)
                } else {
                    draggingDividerIdx = -1
                    selectedDividerIndex = -1
                    cursorEpochSec = xToTs(event.x)
                    onCursorChanged?.invoke(cursorEpochSec)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val targetTs = xToTs(event.x)
                if (isEditMode && draggingDividerIdx in 0 until (slices.size - 1)) {
                    val left = slices[draggingDividerIdx]
                    val right = slices[draggingDividerIdx + 1]
                    if (targetTs > left.startEpochSec && targetTs < right.endEpochSec) {
                        cursorEpochSec = targetTs
                        onSharedDividerMoved?.invoke(left.id, right.id, targetTs)
                    }
                } else {
                    cursorEpochSec = targetTs
                    onCursorChanged?.invoke(cursorEpochSec)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingDividerIdx = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fitLabelToWidth(label: String, maxWidthPx: Float, paint: Paint): String {
        if (maxWidthPx <= 0f) return ""
        if (paint.measureText(label) <= maxWidthPx) return label
        val ellipsis = "…"
        val avail = maxWidthPx - paint.measureText(ellipsis)
        if (avail <= 0f) return ""
        val count = paint.breakText(label, true, avail, null)
        return if (count > 0) label.substring(0, count).trimEnd() + ellipsis else ""
    }

    companion object {
        /**
         * Given two raw curve Y coordinates [yA] and [yB] within a pane bounded by [minY]..[maxY],
         * returns adjusted badge center Y coordinates `(labelYA, labelYB)` guaranteed to stay
         * within `[minY, maxY]` and separated by at least [minDist] (as long as `maxY - minY >= minDist`),
         * even when both curves are pinned near the top or bottom border of the pane.
         */
        fun resolveNonOverlappingBadgePair(
            yA: Float,
            yB: Float,
            minY: Float,
            maxY: Float,
            minDist: Float
        ): Pair<Float, Float> {
            val safeMaxY = max(minY, maxY)
            val clampedA = yA.coerceIn(minY, safeMaxY)
            val clampedB = yB.coerceIn(minY, safeMaxY)
            if (abs(clampedA - clampedB) >= minDist) {
                return clampedA to clampedB
            }

            // Preserve whichever curve is actually higher on screen (smaller Y); tie-break consistently
            val aIsUpper = if (abs(yA - yB) > 0.1f) (yA < yB) else true
            val mid = (clampedA + clampedB) * 0.5f
            var upper = mid - minDist * 0.5f
            var lower = mid + minDist * 0.5f

            if (upper < minY) {
                upper = minY
                lower = min(safeMaxY, upper + minDist)
            }
            if (lower > safeMaxY) {
                lower = safeMaxY
                upper = max(minY, lower - minDist)
            }

            return if (aIsUpper) (upper to lower) else (lower to upper)
        }
    }
}
