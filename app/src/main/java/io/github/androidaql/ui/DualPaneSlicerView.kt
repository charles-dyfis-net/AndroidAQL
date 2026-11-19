package io.github.androidaql.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.androidaql.storage.AqlRingBufferStore
import io.github.androidaql.storage.SessionSliceRecord
import io.github.androidaql.storage.TimelineNoteRecord
import io.github.androidaql.util.TemperatureUnitFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Synchronized 3-pane timeline chart & interactive session slicer:
 * - Pane 1 (Top, 42%): CO₂ (ppm, blue) & PM2.5 (µg/m³, purple) with 1000 ppm reference threshold.
 * - Pane 2 (Middle, 29%): Temperature (°C, orange) & Relative Humidity (%, cyan) to highlight
 *   indoor/outdoor HVAC transitions when GPS is degraded indoors.
 * - Pane 3 (Bottom, 29%): Movement Speed (m/s, log-scaled green) with 1.5 m/s walking reference and shaded "Weak GPS (>50m Urban Canyon)" bands.
 * - Supports thumb-dragging shared dividers between adjacent segments, tapping/scrubbing a cursor timestamp,
 *   and (when [enableZoomAndMinimap] is true) 2-finger pinch-to-zoom/pan, edge auto-scroll, and an overview minimap bar.
 */
class DualPaneSlicerView(context: Context) : View(context) {

    var slots: List<AqlRingBufferStore.RingSlotRecord> = emptyList()
        set(value) {
            field = value
            if (value.isEmpty()) {
                viewStartEpochSec = null
                viewEndEpochSec = null
                stopEdgeAutoScroll()
                if (followLatestEdge && !isFollowingLiveEdge) {
                    isFollowingLiveEdge = true
                    onLiveFollowStateChanged?.invoke(true)
                }
            } else {
                val (tripStart, tripEnd) = getTripBoundsEpochSec()
                val prevFollowing = isFollowingLiveEdge
                val (resolvedCursor, nextFollowing) = resolveCursorOnSlotsUpdated(
                    followLatestEdge = followLatestEdge,
                    isFollowingLiveEdge = isFollowingLiveEdge,
                    currentCursorEpochSec = cursorEpochSec,
                    tripStartEpochSec = tripStart,
                    tripEndEpochSec = tripEnd,
                    firstSlotEpochSec = value.first().bucketEpochSec,
                    midSlotEpochSec = value[value.size / 2].bucketEpochSec,
                    lastSlotEpochSec = value.last().bucketEpochSec
                )
                cursorEpochSec = resolvedCursor
                isFollowingLiveEdge = nextFollowing
                if (followLatestEdge && nextFollowing != prevFollowing) {
                    onLiveFollowStateChanged?.invoke(nextFollowing)
                }
                val curVs = viewStartEpochSec
                val curVe = viewEndEpochSec
                if (curVs != null && curVe != null) {
                    val (clampedS, clampedE) = clampViewport(tripStart, tripEnd, curVs, curVe)
                    if (clampedS <= tripStart && clampedE >= tripEnd) {
                        viewStartEpochSec = null
                        viewEndEpochSec = null
                    } else {
                        viewStartEpochSec = clampedS
                        viewEndEpochSec = clampedE
                    }
                }
            }
            invalidate()
        }

    var slices: List<SessionSliceRecord> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var timelineNotes: List<TimelineNoteRecord> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onTimelineNoteTapped: ((TimelineNoteRecord) -> Unit)? = null

    var followLatestEdge: Boolean = false
    var isFollowingLiveEdge: Boolean = true
        private set
    var onLiveFollowStateChanged: ((Boolean) -> Unit)? = null

    var cursorEpochSec: Long = 0L

    var selectedDividerIndex: Int = -1

    var isEditMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedDividerIndex = -1
                draggingDividerIdx = -1
            }
            invalidate()
        }

    var enableZoomAndMinimap: Boolean = false
        set(value) {
            field = value
            if (!value) {
                viewStartEpochSec = null
                viewEndEpochSec = null
                stopEdgeAutoScroll()
            }
            requestLayout()
            invalidate()
        }

    var viewStartEpochSec: Long? = null
        private set

    var viewEndEpochSec: Long? = null
        private set

    var desiredHeightDp: Int = DEFAULT_PORTRAIT_HEIGHT_DP
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
    var onViewportChanged: ((viewStartSec: Long, viewEndSec: Long, isZoomed: Boolean) -> Unit)? = null

    fun getTripBoundsEpochSec(): Pair<Long, Long> {
        if (slots.isEmpty()) return 0L to STEP_SEC
        val tripStart = fixedWindowStartEpochSec ?: slots.first().bucketEpochSec
        val maxDataEnd = max(
            slots.last().bucketEpochSec,
            slices.maxOfOrNull { it.endEpochSec } ?: 0L
        )
        val tripEnd = max(tripStart + STEP_SEC, fixedWindowEndEpochSec ?: maxDataEnd)
        return tripStart to tripEnd
    }

    fun getEffectiveViewportEpochSec(): Pair<Long, Long> {
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        if (!enableZoomAndMinimap || slots.isEmpty()) {
            return tripStart to tripEnd
        }
        val reqStart = viewStartEpochSec ?: tripStart
        val reqEnd = viewEndEpochSec ?: tripEnd
        return clampViewport(tripStart, tripEnd, reqStart, reqEnd)
    }

    fun isViewportZoomed(): Boolean {
        if (!enableZoomAndMinimap || slots.isEmpty()) return false
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        val (effStart, effEnd) = getEffectiveViewportEpochSec()
        return effStart > tripStart || effEnd < tripEnd
    }

    fun setViewportEpochSec(startSec: Long, endSec: Long, notify: Boolean = true) {
        if (!enableZoomAndMinimap || slots.isEmpty()) return
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        val (effStart, effEnd) = clampViewport(tripStart, tripEnd, startSec, endSec)
        if (effStart <= tripStart && effEnd >= tripEnd) {
            viewStartEpochSec = null
            viewEndEpochSec = null
        } else {
            viewStartEpochSec = effStart
            viewEndEpochSec = effEnd
        }
        invalidate()
        if (notify) {
            onViewportChanged?.invoke(effStart, effEnd, isViewportZoomed())
        }
    }

    fun resetZoom(notify: Boolean = true) {
        viewStartEpochSec = null
        viewEndEpochSec = null
        stopEdgeAutoScroll()
        invalidate()
        if (notify) {
            val (tripStart, tripEnd) = getTripBoundsEpochSec()
            onViewportChanged?.invoke(tripStart, tripEnd, false)
        }
    }

    fun zoomToWindowAroundCursor(windowDurationSec: Long) {
        if (!enableZoomAndMinimap || slots.isEmpty()) return
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        val center = cursorEpochSec.coerceIn(tripStart, tripEnd)
        val half = max(STEP_SEC, windowDurationSec / 2L)
        setViewportEpochSec(center - half, center + half)
    }

    fun focusOnSegment(sliceStartSec: Long, sliceEndSec: Long) {
        if (!enableZoomAndMinimap || slots.isEmpty()) return
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        val (focusStart, focusEnd) = computeSegmentFocusViewport(tripStart, tripEnd, sliceStartSec, sliceEndSec)
        setViewportEpochSec(focusStart, focusEnd)
    }

    fun ensureTimestampVisibleInViewport(targetTs: Long, centerIfOutside: Boolean = false) {
        if (!isViewportZoomed()) return
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        val (curStart, curEnd) = getEffectiveViewportEpochSec()
        val (newStart, newEnd) = ensureTimestampVisible(
            tripStartSec = tripStart,
            tripEndSec = tripEnd,
            curStartSec = curStart,
            curEndSec = curEnd,
            targetTs = targetTs,
            centerIfOutside = centerIfOutside
        )
        if (newStart != curStart || newEnd != curEnd) {
            setViewportEpochSec(newStart, newEnd)
        }
    }

    private val density = resources.displayMetrics.density
    private val viewConfig = ViewConfiguration.get(context)
    private val tapSlopPx = viewConfig.scaledTouchSlop.toFloat()
    private val doubleTapSlopPx = viewConfig.scaledDoubleTapSlop.toFloat()
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val padLeft = PAD_LEFT_DP * density
    private val padRight = PAD_RIGHT_DP * density
    private val padTop = PAD_TOP_DP * density
    private val paneGap = PANE_GAP_DP * density

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
    private val stepSpeedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val stepSpeedBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFBBF24")
        style = Paint.Style.FILL
    }
    private val speedRefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
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

    private val minimapTrackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_TRACK_HEX)
        style = Paint.Style.FILL
    }
    private val minimapTrackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_BORDER_HEX)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val minimapSparklinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_SPARKLINE_HEX)
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    private val minimapSparklinePath = Path()
    private val minimapDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_DIM_HEX)
        style = Paint.Style.FILL
    }
    private val minimapWindowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }
    private val minimapCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val minimapCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_CURSOR_HEX)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    private val minimapDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_MINIMAP_DIVIDER_HEX)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val zoomStatusCanvasTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_ZOOM_STATUS_HEX)
        textSize = 10f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val noteLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(NOTE_MARKER_COLOR_HEX)
        alpha = 175
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }
    private val notePinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(NOTE_PIN_FILL_HEX)
        style = Paint.Style.FILL
    }
    private val notePinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(NOTE_PIN_STROKE_HEX)
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    private val minimapNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(NOTE_MARKER_COLOR_HEX)
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
    }
    private val notePinPath = Path()

    private val co2Path = Path()
    private val pm25Path = Path()
    private val tempPath = Path()
    private val humidityPath = Path()
    private val speedPath = Path()
    private val stepSpeedPath = Path()
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

    private enum class TouchMode {
        NONE,
        SCRUB_CURSOR,
        DRAG_DIVIDER,
        DRAG_MINIMAP,
        TWO_FINGER_PINCH_PAN
    }

    private var touchMode: TouchMode = TouchMode.NONE
    private var draggingDividerIdx: Int = -1
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downDividerWasAlreadySelected: Boolean = false
    private var minimapDragGrabOffsetSec: Long = 0L
    private var pinchPrevSpanX: Float = 0f
    private var pinchPrevMidX: Float = 0f
    private var lastSegmentStripTapMs: Long = 0L
    private var lastSegmentStripTapX: Float = 0f
    private var lastSegmentStripTappedIdx: Int = -1
    private var edgeScrollDirection: Int = 0
    private var lastTouchX: Float = 0f
    private var edgeScrollActive: Boolean = false
    private var pendingTappedNote: TimelineNoteRecord? = null

    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive ||
                edgeScrollDirection == 0 ||
                slots.isEmpty() ||
                (touchMode != TouchMode.SCRUB_CURSOR && touchMode != TouchMode.DRAG_DIVIDER)
            ) {
                edgeScrollActive = false
                return
            }
            val (tripMinTs, tripMaxTs) = getTripBoundsEpochSec()
            val (curViewMin, curViewMax) = getEffectiveViewportEpochSec()
            val viewSpan = max(STEP_SEC, curViewMax - curViewMin)
            val stepMag = max(STEP_SEC, ((viewSpan * EDGE_AUTOSCROLL_STEP_FRACTION).toLong() / STEP_SEC) * STEP_SEC)
            val (pStart, pEnd) = panViewportByDeltaSec(
                tripStartSec = tripMinTs,
                tripEndSec = tripMaxTs,
                curStartSec = curViewMin,
                curEndSec = curViewMax,
                deltaSec = stepMag * edgeScrollDirection
            )
            if (pStart == curViewMin && pEnd == curViewMax) {
                stopEdgeAutoScroll()
                return
            }
            setViewportEpochSec(pStart, pEnd)
            val plotW = max(1f, width - padLeft - padRight)
            val ratio = ((lastTouchX - padLeft) / plotW).coerceIn(0f, 1f)
            val rawTs = pStart + (ratio * (pEnd - pStart)).roundToLong()
            val offsetAligned = ((rawTs - pStart).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            val targetTs = (pStart + offsetAligned).coerceIn(pStart, pEnd)

            if (touchMode == TouchMode.DRAG_DIVIDER &&
                isEditMode &&
                draggingDividerIdx in 0 until (slices.size - 1)
            ) {
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
            postDelayed(this, EDGE_AUTOSCROLL_INTERVAL_MS)
        }
    }

    private fun startEdgeAutoScroll(direction: Int, touchX: Float) {
        lastTouchX = touchX
        if (edgeScrollActive && edgeScrollDirection == direction) return
        edgeScrollDirection = direction
        if (!edgeScrollActive) {
            edgeScrollActive = true
            removeCallbacks(edgeScrollRunnable)
            postDelayed(edgeScrollRunnable, EDGE_AUTOSCROLL_INTERVAL_MS)
        }
    }

    private fun stopEdgeAutoScroll() {
        edgeScrollDirection = 0
        edgeScrollActive = false
        removeCallbacks(edgeScrollRunnable)
    }

    override fun onDetachedFromWindow() {
        stopEdgeAutoScroll()
        super.onDetachedFromWindow()
    }

    private data class PaneLayout(
        val topPaneTop: Float,
        val topPaneBot: Float,
        val midPaneTop: Float,
        val midPaneBot: Float,
        val botPaneTop: Float,
        val botPaneBot: Float,
        val topPaneH: Float,
        val midPaneH: Float,
        val botPaneH: Float,
        val hasSegmentStrip: Boolean,
        val segTop: Float,
        val segBot: Float,
        val hasMinimap: Boolean,
        val minimapTop: Float,
        val minimapBot: Float,
        val timeAxisBaselineY: Float
    )

    private fun computePaneLayout(): PaneLayout {
        val hasSegmentStrip = slices.isNotEmpty()
        val hasMinimap = enableZoomAndMinimap && slots.isNotEmpty()
        val fm = textPaint.fontMetrics
        val chrome = computeBottomChromeMetrics(
            viewHeightPx = height.toFloat(),
            density = density,
            hasSegmentStrip = hasSegmentStrip,
            hasMinimap = hasMinimap,
            textAscentPx = -fm.ascent,
            textDescentPx = fm.descent
        )
        val totalPlotH = max(1f, height - padTop - chrome.effectivePadBottomPx - 2f * paneGap)
        val topPaneH = totalPlotH * PANE_TOP_HEIGHT_FRACTION
        val midPaneH = totalPlotH * PANE_MID_HEIGHT_FRACTION
        val botPaneH = totalPlotH * PANE_BOT_HEIGHT_FRACTION
        val topPaneTop = padTop
        val topPaneBot = topPaneTop + topPaneH
        val midPaneTop = topPaneBot + paneGap
        val midPaneBot = midPaneTop + midPaneH
        val botPaneTop = midPaneBot + paneGap
        val botPaneBot = botPaneTop + botPaneH
        val segTop = botPaneBot + chrome.segTopGapPx
        val segBot = segTop + chrome.segHeightPx
        val timeAxisTop = segBot + chrome.timeAxisTopGapPx
        val timeAxisBaselineY = timeAxisTop + chrome.timeAxisAscentPx
        val timeAxisBottom = timeAxisTop + chrome.timeAxisTextHeightPx
        val minimapTop = timeAxisBottom + chrome.minimapTopGapPx
        val minimapBot = minimapTop + chrome.minimapHeightPx
        return PaneLayout(
            topPaneTop = topPaneTop,
            topPaneBot = topPaneBot,
            midPaneTop = midPaneTop,
            midPaneBot = midPaneBot,
            botPaneTop = botPaneTop,
            botPaneBot = botPaneBot,
            topPaneH = topPaneH,
            midPaneH = midPaneH,
            botPaneH = botPaneH,
            hasSegmentStrip = hasSegmentStrip,
            segTop = segTop,
            segBot = segBot,
            hasMinimap = hasMinimap,
            minimapTop = minimapTop,
            minimapBot = minimapBot,
            timeAxisBaselineY = timeAxisBaselineY
        )
    }

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

        val (tripMinTs, tripMaxTs) = getTripBoundsEpochSec()
        val (viewMinTs, viewMaxTs) = getEffectiveViewportEpochSec()
        val isZoomed = isViewportZoomed()
        val plotW = max(1f, width - padLeft - padRight)
        val plotRight = width - padRight
        val layout = computePaneLayout()
        val hasSegmentStrip = layout.hasSegmentStrip
        val topPaneH = layout.topPaneH
        val midPaneH = layout.midPaneH
        val botPaneH = layout.botPaneH
        val topPaneTop = layout.topPaneTop
        val topPaneBot = layout.topPaneBot
        val midPaneTop = layout.midPaneTop
        val midPaneBot = layout.midPaneBot
        val botPaneTop = layout.botPaneTop
        val botPaneBot = layout.botPaneBot

        fun tsToViewX(ts: Long): Float {
            val ratio = (ts - viewMinTs).toFloat() / max(STEP_SEC, viewMaxTs - viewMinTs).toFloat()
            return padLeft + ratio * plotW
        }

        fun tsToTripX(ts: Long): Float {
            val ratio = (ts - tripMinTs).toFloat() / max(STEP_SEC, tripMaxTs - tripMinTs).toFloat()
            return padLeft + ratio.coerceIn(0f, 1f) * plotW
        }

        val prevBeforeView = slots.indexOfLast { it.bucketEpochSec < viewMinTs }
        val nextAfterView = slots.indexOfFirst { it.bucketEpochSec > viewMaxTs }
        val startIdx = if (prevBeforeView >= 0) prevBeforeView else 0
        val endIdx = if (nextAfterView >= 0) nextAfterView else slots.lastIndex
        val visibleSlots = slots.filter { it.bucketEpochSec in viewMinTs..viewMaxTs }.ifEmpty { slots }

        // 1 & 1b. Clip background zones and dropout bands to plot width across panes
        canvas.save()
        canvas.clipRect(padLeft, topPaneTop, plotRight, botPaneBot)

        for ((idx, slice) in slices.withIndex()) {
            if (slice.endEpochSec <= viewMinTs || slice.startEpochSec >= viewMaxTs) continue
            val x1 = max(padLeft, tsToViewX(slice.startEpochSec))
            val x2 = min(plotRight, tsToViewX(slice.endEpochSec))
            if (x2 <= x1) continue
            bgPaint.color = when (idx % 4) {
                0 -> Color.parseColor("#1E3A8A33")
                1 -> Color.parseColor("#065F4633")
                2 -> Color.parseColor("#713F1233")
                else -> Color.parseColor("#33415555")
            }
            canvas.drawRect(x1, topPaneTop, x2, topPaneBot, bgPaint)
            canvas.drawRect(x1, midPaneTop, x2, midPaneBot, bgPaint)
            canvas.drawRect(x1, botPaneTop, x2, botPaneBot, bgPaint)
        }

        for (i in max(1, startIdx)..endIdx) {
            val prevTs = slots[i - 1].bucketEpochSec
            val curTs = slots[i].bucketEpochSec
            if (curTs - prevTs > dropoutGracePeriodSec) {
                val x1 = tsToViewX(prevTs)
                val x2 = tsToViewX(curTs)
                if (x2 < padLeft || x1 > plotRight) continue
                canvas.drawRect(x1, topPaneTop, x2, topPaneBot, dropoutBandPaint)
                canvas.drawRect(x1, midPaneTop, x2, midPaneBot, dropoutBandPaint)
                canvas.drawRect(x1, botPaneTop, x2, botPaneBot, dropoutBandPaint)
                canvas.drawLine(x1, topPaneTop, x1, botPaneBot, dropoutBorderPaint)
                canvas.drawLine(x2, topPaneTop, x2, botPaneBot, dropoutBorderPaint)
            }
        }

        canvas.restore()

        // 2. Draw pane frames
        canvas.drawRect(padLeft, topPaneTop, plotRight, topPaneBot, gridPaint)
        canvas.drawRect(padLeft, midPaneTop, plotRight, midPaneBot, gridPaint)
        canvas.drawRect(padLeft, botPaneTop, plotRight, botPaneBot, gridPaint)

        // Reserve 14dp at the top of each pane for the pane header text so curves don't collide with labels
        val headerBandPx = PANE_HEADER_BAND_DP * density

        // 3. Pane 1 (Top): CO2 (ppm) & PM2.5 (viewport-scoped Y-axis)
        val maxCo2 = max(CO2_AXIS_MAX_FLOOR_PPM, visibleSlots.maxOf { it.co2PpmMax } + CO2_AXIS_HEADROOM_PPM)
        val minCo2 = min(CO2_AXIS_MIN_CEILING_PPM, visibleSlots.minOf { it.co2PpmMin })
        fun co2ToY(co2: Int): Float {
            val r = (co2 - minCo2).toFloat() / max(1, maxCo2 - minCo2).toFloat()
            return topPaneBot - r.coerceIn(0f, 1f) * max(1f, topPaneH - headerBandPx)
        }

        val maxPm25 = max(PM25_AXIS_MAX_FLOOR_UGM3, visibleSlots.maxOf { it.pm25Max }.toFloat() + PM25_AXIS_HEADROOM_UGM3)
        co2Path.reset()
        pm25Path.reset()
        co2InterpPath.reset()
        pm25InterpPath.reset()
        var prevX = 0f
        var prevYCo2 = 0f
        var prevYPm = 0f
        for (i in startIdx..endIdx) {
            val s = slots[i]
            val x = tsToViewX(s.bucketEpochSec)
            val yCo2 = co2ToY(s.co2PpmMean)
            val yPm = topPaneBot - (s.pm25Mean / maxPm25).coerceIn(0f, 1f) * max(1f, topPaneH - headerBandPx)
            if (i == startIdx) {
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

        canvas.save()
        canvas.clipRect(padLeft, topPaneTop, plotRight, topPaneBot)
        if (CO2_THRESHOLD_PPM in minCo2..maxCo2) {
            val y1000 = co2ToY(CO2_THRESHOLD_PPM)
            canvas.drawLine(padLeft, y1000, plotRight, y1000, thresholdPaint)
        }
        canvas.drawPath(pm25InterpPath, pm25InterpPaint)
        canvas.drawPath(co2InterpPath, co2InterpPaint)
        canvas.drawPath(pm25Path, pm25LinePaint)
        canvas.drawPath(co2Path, co2LinePaint)
        canvas.restore()

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("$maxCo2", padLeft - 4f * density, topPaneTop + 10f * density, textPaint)
        canvas.drawText("$minCo2", padLeft - 4f * density, topPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("CO₂ (ppm, blue) & PM2.5 (purple) • Dashed = Dropout (> ${dropoutGracePeriodSec}s)", padLeft + 4f * density, topPaneTop + 11f * density, textPaint)

        // 4. Pane 2 (Middle): Temperature (°F or °C per device/user setting, orange) & Relative Humidity (%, cyan)
        val useFahrenheit = TemperatureUnitFormatter.useFahrenheit(context)
        val unitSym = TemperatureUnitFormatter.unitSymbol(useFahrenheit)
        val minTemp = min(TEMP_AXIS_MIN_CEILING_C, visibleSlots.minOf { it.tempCMean } - TEMP_AXIS_HEADROOM_C)
        val maxTemp = max(TEMP_AXIS_MAX_FLOOR_C, visibleSlots.maxOf { it.tempCMean } + TEMP_AXIS_HEADROOM_C)
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
        for (i in startIdx..endIdx) {
            val s = slots[i]
            val x = tsToViewX(s.bucketEpochSec)
            val yT = tempToY(s.tempCMean)
            val yH = humToY(s.humidityPctMean)
            if (i == startIdx) {
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

        canvas.save()
        canvas.clipRect(padLeft, midPaneTop, plotRight, midPaneBot)
        canvas.drawPath(humidityInterpPath, humidityInterpPaint)
        canvas.drawPath(tempInterpPath, tempInterpPaint)
        canvas.drawPath(humidityPath, humidityLinePaint)
        canvas.drawPath(tempPath, tempLinePaint)
        canvas.restore()

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(TemperatureUnitFormatter.format(maxTemp, useFahrenheit, decimals = 0), padLeft - 4f * density, midPaneTop + 10f * density, textPaint)
        canvas.drawText(TemperatureUnitFormatter.format(minTemp, useFahrenheit, decimals = 0), padLeft - 4f * density, midPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Temp ($unitSym, orange) & RH (%, cyan)", padLeft + 4f * density, midPaneTop + 11f * density, textPaint)

        // 5. Pane 3 (Bottom): Speed (m/s, shifted log scale) + Weak GPS (>50m accuracy) + Location Not Collected (Saved Place / GPS Off)
        val maxSpeed = max(
            SPEED_AXIS_MAX_FLOOR_MPS,
            (visibleSlots.mapNotNull { if (it.knownPlaceId == 0) it.speedMps else null }.maxOrNull() ?: 5f) + SPEED_AXIS_HEADROOM_MPS
        )
        fun speedToY(spd: Float): Float {
            val r = normalizeLogSpeed(spd, maxSpeed)
            return botPaneBot - r * max(1f, botPaneH - headerBandPx)
        }
        val yWalkRef = speedToY(WALK_SPEED_REF_MPS)
        fun isLocationCollected(s: AqlRingBufferStore.RingSlotRecord): Boolean =
            s.knownPlaceId == 0 && s.speedMps != null

        val viewportSlotCount = max(1f, (viewMaxTs - viewMinTs).toFloat() / STEP_SEC.toFloat())
        val stepWidthPx = max(2f, plotW / viewportSlotCount)

        canvas.save()
        canvas.clipRect(padLeft, botPaneTop, plotRight, botPaneBot)
        canvas.drawLine(padLeft, yWalkRef, plotRight, yWalkRef, speedRefPaint)

        // 5a. Render "Location Not Collected (GPS Off)" spans across Pane 3
        var spanStartIdx = -1
        var spanHasSavedPlace = false
        val midBotY = botPaneTop + headerBandPx + max(1f, botPaneH - headerBandPx) * 0.5f
        fun flushGpsNotCollectedSpan(spanEndIdx: Int) {
            if (spanStartIdx < 0) return
            val rawX1 = if (spanStartIdx == 0) {
                tsToViewX(tripMinTs)
            } else {
                tsToViewX(slots[spanStartIdx].bucketEpochSec) - stepWidthPx / 2f
            }
            val rawX2 = if (spanEndIdx == slots.lastIndex) {
                tsToViewX(tripMaxTs)
            } else {
                tsToViewX(slots[spanEndIdx].bucketEpochSec) + stepWidthPx / 2f
            }
            val x1 = max(padLeft, rawX1)
            val x2 = min(plotRight, rawX2)
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
        for (i in startIdx..endIdx) {
            val s = slots[i]
            if (!isLocationCollected(s)) {
                if (spanStartIdx < 0) spanStartIdx = i
                if (s.knownPlaceId > 0) spanHasSavedPlace = true
            } else {
                flushGpsNotCollectedSpan(i - 1)
            }
        }
        flushGpsNotCollectedSpan(endIdx)

        // 5b. Render active Speed curve (Green = GPS, Amber = Step-estimated without GPS) and Weak GPS (>50m) bands
        speedPath.reset()
        stepSpeedPath.reset()
        speedInterpPath.reset()
        var hasPrevCollectedSpeed = false
        var prevCollectedTs = 0L
        var prevSpeedX = 0f
        var prevYSpd = 0f
        for (i in startIdx..endIdx) {
            val s = slots[i]
            val x = tsToViewX(s.bucketEpochSec)
            if (!isLocationCollected(s)) {
                hasPrevCollectedSpeed = false
                continue
            }
            val acc = s.gpsAccuracyM
            val isStepEstimated = (acc == null)
            if (isStepEstimated) {
                canvas.drawRect(
                    x - stepWidthPx / 2f,
                    botPaneTop + headerBandPx,
                    x + stepWidthPx / 2f,
                    botPaneBot,
                    stepSpeedBandPaint
                )
            } else if (acc!! > WEAK_GPS_ACCURACY_THRESHOLD_M) {
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
            val targetPath = if (isStepEstimated) stepSpeedPath else speedPath
            if (!hasPrevCollectedSpeed) {
                targetPath.moveTo(x, ySpd)
                hasPrevCollectedSpeed = true
            } else {
                val gapSec = s.bucketEpochSec - prevCollectedTs
                if (gapSec > dropoutGracePeriodSec) {
                    speedInterpPath.moveTo(prevSpeedX, prevYSpd)
                    speedInterpPath.lineTo(x, ySpd)
                    targetPath.moveTo(x, ySpd)
                } else {
                    targetPath.moveTo(prevSpeedX, prevYSpd)
                    targetPath.lineTo(x, ySpd)
                }
            }
            prevSpeedX = x
            prevCollectedTs = s.bucketEpochSec
            prevYSpd = ySpd
        }
        canvas.drawPath(speedInterpPath, speedInterpPaint)
        canvas.drawPath(speedPath, speedLinePaint)
        canvas.drawPath(stepSpeedPath, stepSpeedLinePaint)
        canvas.restore()

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.0fm/s", maxSpeed), padLeft - 4f * density, botPaneTop + 10f * density, textPaint)
        if (yWalkRef > botPaneTop + 20f * density && yWalkRef < botPaneBot - 10f * density) {
            canvas.drawText("1.5", padLeft - 4f * density, yWalkRef + 3f * density, textPaint)
        }
        canvas.drawText("0", padLeft - 4f * density, botPaneBot, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Speed (log) • Green=GPS • Amber=Steps • Red=Weak", padLeft + 4f * density, botPaneTop + 11f * density, textPaint)

        // 5c. Segment Strip beneath Pane 3 (shows numbered intervals #1, #2, ... clamped to viewport)
        val segTop = layout.segTop
        val segBot = layout.segBot
        if (hasSegmentStrip) {
            for ((idx, slice) in slices.withIndex()) {
                if (slice.endEpochSec <= viewMinTs || slice.startEpochSec >= viewMaxTs) continue
                val x1 = max(padLeft, tsToViewX(slice.startEpochSec)) + 1f * density
                val x2 = min(plotRight, tsToViewX(slice.endEpochSec)) - 1f * density
                val barW = x2 - x1
                if (barW <= 2f * density) continue
                val isCursorInside = cursorEpochSec in slice.startEpochSec..slice.endEpochSec
                segmentStripFillPaint.color = when (idx % 4) {
                    0 -> Color.parseColor("#1E3A8A")
                    1 -> Color.parseColor("#065F46")
                    2 -> Color.parseColor("#78350F")
                    else -> Color.parseColor("#334155")
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
                        val baselineY = segTop + (segBot - segTop) * 0.72f
                        canvas.drawText(labelToDraw, (x1 + x2) / 2f, baselineY, segmentStripTextPaint)
                    }
                }
            }
        }

        // 6. Shared Dividers & Thumb Handles across all 3 panes (+ Segment Strip), active divider drawn last
        val dividerBottomY = if (hasSegmentStrip) segBot else botPaneBot
        fun drawSingleDivider(i: Int) {
            val boundaryTs = slices[i].endEpochSec
            if (boundaryTs !in viewMinTs..viewMaxTs) return
            val x = tsToViewX(boundaryTs)
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
        for (i in 0 until slices.size - 1) {
            if (i != selectedDividerIndex) drawSingleDivider(i)
        }
        if (selectedDividerIndex in 0 until (slices.size - 1)) {
            drawSingleDivider(selectedDividerIndex)
        }

        // 6b. Timestamped Timeline Notes (teal dotted marker + top-rail diamond pin)
        if (timelineNotes.isNotEmpty()) {
            val pinCy = topPaneTop - 6.5f * density
            val pinR = NOTE_PIN_RADIUS_DP * density
            for (note in timelineNotes) {
                if (note.timestampEpochSec !in viewMinTs..viewMaxTs) continue
                val nx = tsToViewX(note.timestampEpochSec)
                canvas.drawLine(nx, topPaneTop, nx, botPaneBot, noteLinePaint)
                notePinPath.reset()
                notePinPath.moveTo(nx, pinCy - pinR)
                notePinPath.lineTo(nx + pinR, pinCy)
                notePinPath.lineTo(nx, pinCy + pinR)
                notePinPath.lineTo(nx - pinR, pinCy)
                notePinPath.close()
                canvas.drawPath(notePinPath, notePinFillPaint)
                canvas.drawPath(notePinPath, notePinStrokePaint)
            }
        }

        // 7. Interactive Scrubber Cursor, Top Floating Time Bubble & Inline Intersection Value Badges
        if (cursorEpochSec in viewMinTs..viewMaxTs) {
            val cx = tsToViewX(cursorEpochSec)
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
                val timeLabel = when {
                    inDropoutGapSec > 0L -> "$baseTimeLabel • DROPOUT (${inDropoutGapSec}s gap)"
                    followLatestEdge && isFollowingLiveEdge -> "Live • $baseTimeLabel"
                    else -> baseTimeLabel
                }
                timeBubbleBgPaint.color = if (inDropoutGapSec > 0L) Color.parseColor("#FDE68A") else Color.parseColor("#F8FAFC")
                val tbW = badgeTextPaint.measureText(timeLabel) + 12f * density
                val tbH = 17f * density
                val tbX = (cx - tbW / 2f).coerceIn(padLeft, plotRight - tbW)
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

                val placeLeft = cx > (plotRight - 96f * density)
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
                    val (spdBadge, badgeColor) = when {
                        acc == null -> String.format(Locale.US, "%.1fm/s (Steps)", spd) to Color.parseColor("#FBBF24")
                        acc > WEAK_GPS_ACCURACY_THRESHOLD_M -> String.format(Locale.US, "%.1fm/s (±%.0fm)", spd, acc) to Color.parseColor("#34D399")
                        else -> String.format(Locale.US, "%.1fm/s", spd) to Color.parseColor("#34D399")
                    }
                    drawIntersectionCallout(
                        canvas, cx, ySpd, ySpd.coerceIn(botPaneTop + badgeMarginPx, botPaneBot - badgeMarginPx),
                        spdBadge, badgeColor, placeLeft
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

        // 8. Time Axis Labels (Viewport Bounds + Zoom Span Indicator with narrow-screen overlap guard)
        val leftTimeLabel = timeFmt.format(Instant.ofEpochSecond(viewMinTs))
        val rightTimeLabel = timeFmt.format(Instant.ofEpochSecond(viewMaxTs))
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(leftTimeLabel, padLeft, layout.timeAxisBaselineY, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rightTimeLabel, plotRight, layout.timeAxisBaselineY, textPaint)
        if (isZoomed) {
            val zoomLabel = "🔍 ${formatSpanShort(viewMaxTs - viewMinTs)} of ${formatSpanShort(tripMaxTs - tripMinTs)}"
            val leftW = textPaint.measureText(leftTimeLabel)
            val rightW = textPaint.measureText(rightTimeLabel)
            val centerW = zoomStatusCanvasTextPaint.measureText(zoomLabel)
            if (leftW + centerW + rightW + TIME_AXIS_LABEL_MIN_GAP_DP * density <= plotW) {
                canvas.drawText(zoomLabel, padLeft + plotW * 0.5f, layout.timeAxisBaselineY, zoomStatusCanvasTextPaint)
            }
        }

        // 9. Overview Minimap Navigator Bar (Full Trip Context + Draggable Viewport Window)
        if (layout.hasMinimap && layout.minimapBot > layout.minimapTop + 4f * density) {
            val miniRect = RectF(padLeft, layout.minimapTop, plotRight, layout.minimapBot)
            val miniRadius = MINIMAP_CORNER_RADIUS_DP * density
            canvas.drawRoundRect(miniRect, miniRadius, miniRadius, minimapTrackFillPaint)

            canvas.save()
            canvas.clipRect(miniRect)

            for ((idx, slice) in slices.withIndex()) {
                val sx1 = tsToTripX(slice.startEpochSec)
                val sx2 = tsToTripX(slice.endEpochSec)
                if (sx2 > sx1) {
                    bgPaint.color = when (idx % 4) {
                        0 -> Color.parseColor("#1E3A8A66")
                        1 -> Color.parseColor("#065F4666")
                        2 -> Color.parseColor("#78350F66")
                        else -> Color.parseColor("#33415566")
                    }
                    canvas.drawRect(sx1, layout.minimapTop, sx2, layout.minimapBot, bgPaint)
                }
                if (idx < slices.size - 1) {
                    val dx = tsToTripX(slice.endEpochSec)
                    canvas.drawLine(dx, layout.minimapTop, dx, layout.minimapBot, minimapDividerPaint)
                }
            }

            // Full-trip CO2 sparkline
            val globalMinCo2 = min(CO2_AXIS_MIN_CEILING_PPM, slots.minOf { it.co2PpmMin })
            val globalMaxCo2 = max(CO2_AXIS_MAX_FLOOR_PPM, slots.maxOf { it.co2PpmMax } + CO2_AXIS_HEADROOM_PPM)
            val sparkInset = MINIMAP_SPARKLINE_INSET_DP * density
            val sparkTop = layout.minimapTop + sparkInset
            val sparkBot = layout.minimapBot - sparkInset
            val sparkH = max(1f, sparkBot - sparkTop)
            minimapSparklinePath.reset()
            for ((i, s) in slots.withIndex()) {
                val sx = tsToTripX(s.bucketEpochSec)
                val r = (s.co2PpmMean - globalMinCo2).toFloat() / max(1, globalMaxCo2 - globalMinCo2).toFloat()
                val sy = sparkBot - r.coerceIn(0f, 1f) * sparkH
                if (i == 0) {
                    minimapSparklinePath.moveTo(sx, sy)
                } else {
                    minimapSparklinePath.lineTo(sx, sy)
                }
            }
            canvas.drawPath(minimapSparklinePath, minimapSparklinePaint)

            val vx1 = tsToTripX(viewMinTs)
            val vx2 = tsToTripX(viewMaxTs)
            if (isZoomed) {
                if (vx1 > padLeft) {
                    canvas.drawRect(padLeft, layout.minimapTop, vx1, layout.minimapBot, minimapDimPaint)
                }
                if (vx2 < plotRight) {
                    canvas.drawRect(vx2, layout.minimapTop, plotRight, layout.minimapBot, minimapDimPaint)
                }
            }

            for (note in timelineNotes) {
                if (note.timestampEpochSec in tripMinTs..tripMaxTs) {
                    val nxMini = tsToTripX(note.timestampEpochSec)
                    canvas.drawLine(
                        nxMini,
                        layout.minimapTop + 1f * density,
                        nxMini,
                        layout.minimapBot - 1f * density,
                        minimapNotePaint
                    )
                }
            }

            if (cursorEpochSec in tripMinTs..tripMaxTs) {
                val miniCx = tsToTripX(cursorEpochSec)
                canvas.drawLine(miniCx, layout.minimapTop, miniCx, layout.minimapBot, minimapCursorPaint)
            }
            canvas.restore()

            canvas.drawRoundRect(miniRect, miniRadius, miniRadius, minimapTrackBorderPaint)

            val rawThumbW = vx2 - vx1
            val minThumbW = min(plotW, MINIMAP_MIN_THUMB_WIDTH_DP * density)
            val thumbCenter = (vx1 + vx2) * 0.5f
            val halfThumb = max(rawThumbW, minThumbW) * 0.5f
            val thumbX1 = (thumbCenter - halfThumb).coerceIn(padLeft, plotRight - minThumbW)
            val thumbX2 = (thumbX1 + max(rawThumbW, minThumbW)).coerceAtMost(plotRight)
            val thumbColor = Color.parseColor(
                if (touchMode == TouchMode.DRAG_MINIMAP) COLOR_MINIMAP_THUMB_DRAG_HEX else COLOR_MINIMAP_THUMB_IDLE_HEX
            )
            minimapWindowBorderPaint.color = thumbColor
            minimapCapPaint.color = thumbColor
            val thumbRect = RectF(thumbX1, layout.minimapTop, thumbX2, layout.minimapBot)
            val thumbRadius = MINIMAP_THUMB_CORNER_RADIUS_DP * density
            canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, minimapWindowBorderPaint)
            val capW = MINIMAP_END_CAP_WIDTH_DP * density
            if (isZoomed) {
                canvas.drawRect(thumbX1, layout.minimapTop, min(thumbX2, thumbX1 + capW), layout.minimapBot, minimapCapPaint)
                canvas.drawRect(max(thumbX1, thumbX2 - capW), layout.minimapTop, thumbX2, layout.minimapBot, minimapCapPaint)
            }
        }
    }

    private fun formatSpanShort(spanSec: Long): String {
        val safe = max(0L, spanSec)
        return when {
            safe < 60L -> "${safe}s"
            safe < 3600L -> {
                val m = safe / 60L
                val s = safe % 60L
                if (s == 0L) "${m}m" else "${m}m ${s}s"
            }
            else -> {
                val h = safe / 3600L
                val m = (safe % 3600L) / 60L
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
        }
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
        val (tripStart, tripEnd) = getTripBoundsEpochSec()
        cursorEpochSec = (cursorEpochSec + deltaSec).coerceIn(tripStart, tripEnd)
        ensureTimestampVisibleInViewport(cursorEpochSec, centerIfOutside = false)
        onCursorChanged?.invoke(cursorEpochSec)
        invalidate()
    }

    fun snapToLiveEdge() {
        if (!followLatestEdge) return
        val wasFollowing = isFollowingLiveEdge
        isFollowingLiveEdge = true
        if (slots.isNotEmpty()) {
            cursorEpochSec = slots.last().bucketEpochSec
        }
        if (!wasFollowing) {
            onLiveFollowStateChanged?.invoke(true)
        }
        onCursorChanged?.invoke(cursorEpochSec)
        invalidate()
    }

    private fun applyUserCursorScrub(targetTs: Long, tripMaxTs: Long) {
        cursorEpochSec = targetTs
        if (followLatestEdge) {
            val latestSlotTs = slots.lastOrNull()?.bucketEpochSec ?: tripMaxTs
            val nextFollow = shouldFollowLiveEdgeAfterScrub(targetTs, latestSlotTs, tripMaxTs)
            if (nextFollow != isFollowingLiveEdge) {
                isFollowingLiveEdge = nextFollow
                onLiveFollowStateChanged?.invoke(nextFollow)
            }
        }
        onCursorChanged?.invoke(cursorEpochSec)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        val (tripMinTs, tripMaxTs) = getTripBoundsEpochSec()
        val (viewMinTs, viewMaxTs) = getEffectiveViewportEpochSec()
        val plotW = max(1f, width - padLeft - padRight)

        fun xToViewTs(x: Float): Long {
            val ratio = ((x - padLeft) / plotW).coerceIn(0f, 1f)
            val rawTs = viewMinTs + (ratio * (viewMaxTs - viewMinTs)).roundToLong()
            val offsetAligned = ((rawTs - viewMinTs).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            return (viewMinTs + offsetAligned).coerceIn(viewMinTs, viewMaxTs)
        }

        fun xToTripTs(x: Float): Long {
            val ratio = ((x - padLeft) / plotW).coerceIn(0f, 1f)
            val rawTs = tripMinTs + (ratio * (tripMaxTs - tripMinTs)).roundToLong()
            val offsetAligned = ((rawTs - tripMinTs).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            return (tripMinTs + offsetAligned).coerceIn(tripMinTs, tripMaxTs)
        }

        fun tsToViewX(ts: Long): Float {
            val ratio = (ts - viewMinTs).toFloat() / max(STEP_SEC, viewMaxTs - viewMinTs).toFloat()
            return padLeft + ratio * plotW
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stopEdgeAutoScroll()
                pendingTappedNote = null
                val layout = computePaneLayout()

                // Case A: Overview Minimap Zone
                if (layout.hasMinimap && event.y >= layout.minimapTop - MINIMAP_TOUCH_SLOP_TOP_DP * density) {
                    touchMode = TouchMode.DRAG_MINIMAP
                    draggingDividerIdx = -1
                    downDividerWasAlreadySelected = false
                    val touchTripTs = xToTripTs(event.x)
                    if (isViewportZoomed()) {
                        if (touchTripTs in viewMinTs..viewMaxTs) {
                            minimapDragGrabOffsetSec = touchTripTs - viewMinTs
                        } else {
                            val viewSpan = max(STEP_SEC, viewMaxTs - viewMinTs)
                            minimapDragGrabOffsetSec = viewSpan / 2L
                            setViewportEpochSec(touchTripTs - minimapDragGrabOffsetSec, touchTripTs - minimapDragGrabOffsetSec + viewSpan)
                        }
                    } else {
                        cursorEpochSec = touchTripTs
                        onCursorChanged?.invoke(cursorEpochSec)
                    }
                    invalidate()
                    return true
                }

                // Case B: Segment Strip Double-Tap to Focus / Toggle Zoom
                val stripSlopY = SEGMENT_STRIP_TOUCH_SLOP_Y_DP * density
                val inSegmentStripExact = layout.hasSegmentStrip &&
                    event.y in (layout.segTop - stripSlopY)..(layout.segBot + stripSlopY)
                if (inSegmentStripExact) {
                    val nowMs = SystemClock.uptimeMillis()
                    val tapViewTs = xToViewTs(event.x)
                    val tappedSliceIdx = slices.indexOfFirst { tapViewTs in it.startEpochSec..it.endEpochSec }
                    if (enableZoomAndMinimap &&
                        nowMs - lastSegmentStripTapMs <= doubleTapTimeoutMs &&
                        abs(event.x - lastSegmentStripTapX) <= doubleTapSlopPx &&
                        tappedSliceIdx >= 0 &&
                        tappedSliceIdx == lastSegmentStripTappedIdx
                    ) {
                        lastSegmentStripTapMs = 0L
                        val targetSlice = slices[tappedSliceIdx]
                        val (focusStart, focusEnd) = computeSegmentFocusViewport(
                            tripStartSec = tripMinTs,
                            tripEndSec = tripMaxTs,
                            sliceStartSec = targetSlice.startEpochSec,
                            sliceEndSec = targetSlice.endEpochSec
                        )
                        if (isViewportZoomed() &&
                            abs(viewMinTs - focusStart) <= STEP_SEC &&
                            abs(viewMaxTs - focusEnd) <= STEP_SEC
                        ) {
                            resetZoom()
                        } else {
                            setViewportEpochSec(focusStart, focusEnd)
                        }
                        touchMode = TouchMode.NONE
                        draggingDividerIdx = -1
                        downDividerWasAlreadySelected = false
                        return true
                    }
                    lastSegmentStripTapMs = nowMs
                    lastSegmentStripTapX = event.x
                    lastSegmentStripTappedIdx = tappedSliceIdx
                }

                // Case C: Main 3 Panes & Segment Strip Single-Finger (Divider Drag or Cursor Scrub)
                val pillBandSlop = DIVIDER_PILL_BAND_SLOP_DP * density
                val inPillBand = event.y in (layout.topPaneBot - pillBandSlop)..(layout.midPaneTop + pillBandSlop)
                val inSegmentStrip = layout.hasSegmentStrip &&
                    event.y in (layout.botPaneBot - MINIMAP_TOUCH_SLOP_TOP_DP * density)..(layout.segBot + 8f * density)
                var hitDivider = -1
                if (isEditMode) {
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until slices.size - 1) {
                        val boundaryTs = slices[i].endEpochSec
                        if (boundaryTs !in viewMinTs..viewMaxTs) continue
                        val allowedDist = when {
                            inPillBand -> DIVIDER_PILL_HIT_RADIUS_DP * density
                            inSegmentStrip -> DIVIDER_STRIP_HIT_RADIUS_DP * density
                            i == selectedDividerIndex && cursorEpochSec == boundaryTs ->
                                // Co-located cyan cursor inside Panes 1/2/3: let user drag cursor away freely
                                -1f
                            else -> DIVIDER_LINE_HIT_RADIUS_DP * density
                        }
                        if (allowedDist <= 0f) continue
                        val dx = abs(event.x - tsToViewX(boundaryTs))
                        if (dx <= allowedDist && dx < bestDist) {
                            bestDist = dx
                            hitDivider = i
                        }
                    }
                }
                downX = event.x
                downY = event.y
                downDividerWasAlreadySelected = (hitDivider >= 0 && hitDivider == selectedDividerIndex)
                if (isEditMode && hitDivider >= 0) {
                    touchMode = TouchMode.DRAG_DIVIDER
                    draggingDividerIdx = hitDivider
                    selectedDividerIndex = hitDivider
                    onDividerSelected?.invoke(hitDivider)
                } else {
                    if (timelineNotes.isNotEmpty() &&
                        event.y <= layout.topPaneTop + NOTE_PIN_HIT_RADIUS_DP * density
                    ) {
                        val hitRadiusPx = NOTE_PIN_HIT_RADIUS_DP * density
                        var bestDist = Float.MAX_VALUE
                        var bestNote: TimelineNoteRecord? = null
                        for (note in timelineNotes) {
                            if (note.timestampEpochSec !in viewMinTs..viewMaxTs) continue
                            val dx = abs(event.x - tsToViewX(note.timestampEpochSec))
                            if (dx <= hitRadiusPx && dx < bestDist) {
                                bestDist = dx
                                bestNote = note
                            }
                        }
                        pendingTappedNote = bestNote
                    }
                    touchMode = TouchMode.SCRUB_CURSOR
                    val wasSelected = selectedDividerIndex >= 0
                    draggingDividerIdx = -1
                    selectedDividerIndex = -1
                    if (wasSelected) {
                        onDividerSelected?.invoke(-1)
                    }
                    applyUserCursorScrub(xToViewTs(event.x), tripMaxTs)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pendingTappedNote = null
                if (enableZoomAndMinimap && event.pointerCount >= 2 && touchMode != TouchMode.DRAG_MINIMAP) {
                    draggingDividerIdx = -1
                    downDividerWasAlreadySelected = false
                    stopEdgeAutoScroll()
                    touchMode = TouchMode.TWO_FINGER_PINCH_PAN
                    pinchPrevMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    pinchPrevSpanX = max(MIN_PINCH_SPAN_DP * density, abs(event.getX(1) - event.getX(0)))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > tapSlopPx || abs(event.y - downY) > tapSlopPx) {
                    pendingTappedNote = null
                }
                when (touchMode) {
                    TouchMode.NONE -> return true
                    TouchMode.TWO_FINGER_PINCH_PAN -> {
                        if (event.pointerCount < 2) return true
                        val curMidX = (event.getX(0) + event.getX(1)) * 0.5f
                        val curSpanX = max(MIN_PINCH_SPAN_DP * density, abs(event.getX(1) - event.getX(0)))
                        if (enableZoomAndMinimap) {
                            val scaleFactor = (curSpanX / max(1f, pinchPrevSpanX))
                                .coerceIn(PINCH_STEP_SCALE_MIN, PINCH_STEP_SCALE_MAX)
                            val anchorRatio = ((curMidX - padLeft) / plotW).coerceIn(0f, 1f)
                            val (zStart, zEnd) = zoomViewportAroundAnchor(
                                tripStartSec = tripMinTs,
                                tripEndSec = tripMaxTs,
                                curStartSec = viewMinTs,
                                curEndSec = viewMaxTs,
                                scaleFactor = scaleFactor,
                                anchorRatio = anchorRatio
                            )
                            val dx = curMidX - pinchPrevMidX
                            val panDeltaSec = -((dx / plotW) * (zEnd - zStart).toFloat()).roundToLong()
                            val (pStart, pEnd) = panViewportByDeltaSec(
                                tripStartSec = tripMinTs,
                                tripEndSec = tripMaxTs,
                                curStartSec = zStart,
                                curEndSec = zEnd,
                                deltaSec = panDeltaSec
                            )
                            setViewportEpochSec(pStart, pEnd)
                        }
                        pinchPrevMidX = curMidX
                        pinchPrevSpanX = curSpanX
                        return true
                    }
                    TouchMode.DRAG_MINIMAP -> {
                        val touchTripTs = xToTripTs(event.x)
                        if (isViewportZoomed()) {
                            val span = max(STEP_SEC, viewMaxTs - viewMinTs)
                            val reqStart = touchTripTs - minimapDragGrabOffsetSec
                            val (pStart, pEnd) = clampViewport(tripMinTs, tripMaxTs, reqStart, reqStart + span)
                            setViewportEpochSec(pStart, pEnd)
                        } else {
                            cursorEpochSec = touchTripTs
                            onCursorChanged?.invoke(cursorEpochSec)
                            invalidate()
                        }
                        return true
                    }
                    TouchMode.DRAG_DIVIDER, TouchMode.SCRUB_CURSOR -> {
                        val targetTs = xToViewTs(event.x)
                        if (touchMode == TouchMode.DRAG_DIVIDER &&
                            isEditMode &&
                            draggingDividerIdx in 0 until (slices.size - 1)
                        ) {
                            val left = slices[draggingDividerIdx]
                            val right = slices[draggingDividerIdx + 1]
                            if (targetTs > left.startEpochSec && targetTs < right.endEpochSec) {
                                cursorEpochSec = targetTs
                                onSharedDividerMoved?.invoke(left.id, right.id, targetTs)
                            }
                        } else {
                            applyUserCursorScrub(targetTs, tripMaxTs)
                        }
                        if (isViewportZoomed()) {
                            val edgeZonePx = EDGE_AUTOSCROLL_ZONE_DP * density
                            if (event.x < padLeft + edgeZonePx && viewMinTs > tripMinTs) {
                                startEdgeAutoScroll(-1, event.x)
                            } else if (event.x > (width - padRight - edgeZonePx) && viewMaxTs < tripMaxTs) {
                                startEdgeAutoScroll(+1, event.x)
                            } else {
                                stopEdgeAutoScroll()
                            }
                        } else {
                            stopEdgeAutoScroll()
                        }
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchMode == TouchMode.TWO_FINGER_PINCH_PAN) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopEdgeAutoScroll()
                val tappedNote = pendingTappedNote
                pendingTappedNote = null
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    tappedNote != null &&
                    abs(event.x - downX) <= tapSlopPx &&
                    abs(event.y - downY) <= tapSlopPx
                ) {
                    touchMode = TouchMode.NONE
                    draggingDividerIdx = -1
                    downDividerWasAlreadySelected = false
                    val snapped = ((tappedNote.timestampEpochSec + STEP_SEC / 2L) / STEP_SEC) * STEP_SEC
                    applyUserCursorScrub(snapped.coerceIn(viewMinTs, viewMaxTs), tripMaxTs)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    onTimelineNoteTapped?.invoke(tappedNote)
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    touchMode == TouchMode.DRAG_DIVIDER &&
                    downDividerWasAlreadySelected &&
                    abs(event.x - downX) < tapSlopPx &&
                    abs(event.y - downY) < tapSlopPx
                ) {
                    selectedDividerIndex = -1
                    onDividerSelected?.invoke(-1)
                }
                touchMode = TouchMode.NONE
                draggingDividerIdx = -1
                downDividerWasAlreadySelected = false
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
        // Timeline & Zoom Presets
        const val STEP_SEC = 15L
        const val MIN_VIEWPORT_SPAN_SEC = 180L
        const val ZOOM_PRESET_30M_SEC = 30L * 60L
        const val ZOOM_PRESET_10M_SEC = 10L * 60L
        const val ZOOM_PRESET_3M_SEC = MIN_VIEWPORT_SPAN_SEC
        const val MAX_ZOOM_FACTOR = 64f
        const val DEFAULT_VISIBLE_MARGIN_RATIO = 0.10f
        const val MAX_VISIBLE_MARGIN_RATIO = 0.45f
        const val DEFAULT_SEGMENT_FOCUS_PADDING_RATIO = 0.15f
        const val MIN_SEGMENT_FOCUS_PADDING_SEC = 30L
        const val DIVIDER_FOCUS_SPAN_FRACTION = 0.08f
        const val DIVIDER_FOCUS_MIN_HALF_SPAN_SEC = MIN_VIEWPORT_SPAN_SEC
        const val DIVIDER_FOCUS_MAX_HALF_SPAN_SEC = 5L * 60L

        // Layout & Geometry (dp / fractions)
        const val DEFAULT_PORTRAIT_HEIGHT_DP = 390
        const val COMPACT_HEIGHT_THRESHOLD_DP = 290f
        const val PAD_LEFT_DP = 46f
        const val PAD_RIGHT_DP = 16f
        const val PAD_TOP_DP = 24f
        const val PANE_GAP_DP = 14f
        const val PANE_HEADER_BAND_DP = 14f
        const val PANE_TOP_HEIGHT_FRACTION = 0.42f
        const val PANE_MID_HEIGHT_FRACTION = 0.29f
        const val PANE_BOT_HEIGHT_FRACTION = 0.29f
        const val SEGMENT_STRIP_TOP_GAP_DP = 3f
        const val SEGMENT_STRIP_HEIGHT_DP = 20f
        const val SEGMENT_STRIP_HEIGHT_COMPACT_DP = 18f
        const val TIME_AXIS_TOP_GAP_DP = 4f
        const val TIME_AXIS_BOTTOM_PADDING_DP = 4f
        const val TIME_AXIS_MIN_TEXT_HEIGHT_DP = 10f
        const val TIME_AXIS_LABEL_MIN_GAP_DP = 16f
        const val MINIMAP_TOP_GAP_DP = 4f
        const val MINIMAP_HEIGHT_DP = 20f
        const val MINIMAP_HEIGHT_COMPACT_DP = 16f
        const val MINIMAP_BOTTOM_MARGIN_DP = 4f
        const val MINIMAP_CORNER_RADIUS_DP = 4f
        const val MINIMAP_THUMB_CORNER_RADIUS_DP = 3f
        const val MINIMAP_MIN_THUMB_WIDTH_DP = 16f
        const val MINIMAP_END_CAP_WIDTH_DP = 3f
        const val MINIMAP_SPARKLINE_INSET_DP = 2f

        // Touch & Edge Auto-Scroll
        const val MINIMAP_TOUCH_SLOP_TOP_DP = 6f
        const val SEGMENT_STRIP_TOUCH_SLOP_Y_DP = 4f
        const val DIVIDER_PILL_BAND_SLOP_DP = 20f
        const val DIVIDER_PILL_HIT_RADIUS_DP = 28f
        const val DIVIDER_STRIP_HIT_RADIUS_DP = 20f
        const val DIVIDER_LINE_HIT_RADIUS_DP = 9f
        const val MIN_PINCH_SPAN_DP = 16f
        const val PINCH_STEP_SCALE_MIN = 0.5f
        const val PINCH_STEP_SCALE_MAX = 2.0f
        const val EDGE_AUTOSCROLL_ZONE_DP = 24f
        const val EDGE_AUTOSCROLL_INTERVAL_MS = 60L
        const val EDGE_AUTOSCROLL_STEP_FRACTION = 0.04f

        // Y-Axis Scale Floors & Headroom
        const val CO2_AXIS_MAX_FLOOR_PPM = 1600
        const val CO2_AXIS_MIN_CEILING_PPM = 400
        const val CO2_AXIS_HEADROOM_PPM = 150
        const val CO2_THRESHOLD_PPM = 1000
        const val PM25_AXIS_MAX_FLOOR_UGM3 = 35f
        const val PM25_AXIS_HEADROOM_UGM3 = 5f
        const val TEMP_AXIS_MIN_CEILING_C = 0f
        const val TEMP_AXIS_MAX_FLOOR_C = 28f
        const val TEMP_AXIS_HEADROOM_C = 2f
        const val SPEED_AXIS_MAX_FLOOR_MPS = 12f
        const val SPEED_AXIS_HEADROOM_MPS = 2f
        const val WALK_SPEED_REF_MPS = 1.5f
        const val WEAK_GPS_ACCURACY_THRESHOLD_M = 50f

        // Zoom & Minimap Colors
        const val COLOR_MINIMAP_TRACK_HEX = "#0B1120"
        const val COLOR_MINIMAP_BORDER_HEX = "#334155"
        const val COLOR_MINIMAP_SPARKLINE_HEX = "#8838BDF8"
        const val COLOR_MINIMAP_DIM_HEX = "#99090D16"
        const val COLOR_MINIMAP_THUMB_IDLE_HEX = "#38BDF8"
        const val COLOR_MINIMAP_THUMB_DRAG_HEX = "#F59E0B"
        const val COLOR_MINIMAP_CURSOR_HEX = "#F8FAFC"
        const val COLOR_MINIMAP_DIVIDER_HEX = "#FBBF24"
        const val COLOR_ZOOM_STATUS_HEX = "#38BDF8"

        // Timeline Note Markers
        const val NOTE_PIN_RADIUS_DP = 5.5f
        const val NOTE_PIN_HIT_RADIUS_DP = 18f
        const val NOTE_MARKER_COLOR_HEX = "#2DD4BF"
        const val NOTE_PIN_FILL_HEX = "#14B8A6"
        const val NOTE_PIN_STROKE_HEX = "#0F172A"

        data class BottomChromeMetrics(
            val effectivePadBottomPx: Float,
            val segTopGapPx: Float,
            val segHeightPx: Float,
            val timeAxisTopGapPx: Float,
            val timeAxisAscentPx: Float,
            val timeAxisTextHeightPx: Float,
            val minimapTopGapPx: Float,
            val minimapHeightPx: Float,
            val bottomMarginPx: Float
        )

        /**
         * Derives non-overlapping bottom chrome heights and total bottom padding from named layout
         * constants and actual font metrics so the segment strip, time axis labels, and minimap
         * remain cleanly separated across phone/tablet and portrait/landscape dimensions.
         */
        fun computeBottomChromeMetrics(
            viewHeightPx: Float,
            density: Float,
            hasSegmentStrip: Boolean,
            hasMinimap: Boolean,
            textAscentPx: Float,
            textDescentPx: Float
        ): BottomChromeMetrics {
            val isCompact = viewHeightPx > 0f && viewHeightPx < COMPACT_HEIGHT_THRESHOLD_DP * density
            val segTopGapPx = if (hasSegmentStrip) SEGMENT_STRIP_TOP_GAP_DP * density else 0f
            val segHeightPx = if (hasSegmentStrip) {
                (if (isCompact) SEGMENT_STRIP_HEIGHT_COMPACT_DP else SEGMENT_STRIP_HEIGHT_DP) * density
            } else {
                0f
            }
            val timeAxisTopGapPx = TIME_AXIS_TOP_GAP_DP * density
            val minAscentPx = TIME_AXIS_MIN_TEXT_HEIGHT_DP * density
            val timeAxisAscentPx = max(minAscentPx, textAscentPx)
            val timeAxisTextHeightPx = max(minAscentPx, timeAxisAscentPx + max(0f, textDescentPx))
            val minimapTopGapPx = if (hasMinimap) MINIMAP_TOP_GAP_DP * density else 0f
            val minimapHeightPx = if (hasMinimap) {
                (if (isCompact) MINIMAP_HEIGHT_COMPACT_DP else MINIMAP_HEIGHT_DP) * density
            } else {
                0f
            }
            val bottomMarginPx = if (hasMinimap) {
                MINIMAP_BOTTOM_MARGIN_DP * density
            } else {
                TIME_AXIS_BOTTOM_PADDING_DP * density
            }
            val effectivePadBottomPx = segTopGapPx +
                segHeightPx +
                timeAxisTopGapPx +
                timeAxisTextHeightPx +
                minimapTopGapPx +
                minimapHeightPx +
                bottomMarginPx
            return BottomChromeMetrics(
                effectivePadBottomPx = effectivePadBottomPx,
                segTopGapPx = segTopGapPx,
                segHeightPx = segHeightPx,
                timeAxisTopGapPx = timeAxisTopGapPx,
                timeAxisAscentPx = timeAxisAscentPx,
                timeAxisTextHeightPx = timeAxisTextHeightPx,
                minimapTopGapPx = minimapTopGapPx,
                minimapHeightPx = minimapHeightPx,
                bottomMarginPx = bottomMarginPx
            )
        }

        /**
         * Clamps and snaps a requested viewport `[reqStartSec, reqEndSec]` to the 15-second grid
         * strictly within `[tripStartSec, tripEndSec]`, preserving at least [minSpanSec] (or the full
         * trip duration if shorter).
         */
        fun clampViewport(
            tripStartSec: Long,
            tripEndSec: Long,
            reqStartSec: Long,
            reqEndSec: Long,
            minSpanSec: Long = MIN_VIEWPORT_SPAN_SEC
        ): Pair<Long, Long> {
            val safeTripEnd = max(tripStartSec + STEP_SEC, tripEndSec)
            val tripSpan = safeTripEnd - tripStartSec
            val alignedMinReq = ((minSpanSec.toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC)
            val effectiveMinSpan = min(tripSpan, max(STEP_SEC, alignedMinReq))
            val rawSpan = (reqEndSec - reqStartSec).coerceIn(effectiveMinSpan, tripSpan)
            val alignedSpan = (((rawSpan.toDouble() / STEP_SEC.toDouble()).roundToLong()) * STEP_SEC)
                .coerceIn(effectiveMinSpan, tripSpan)
            if (alignedSpan >= tripSpan) {
                return tripStartSec to safeTripEnd
            }
            val offsetFromTripStart = ((reqStartSec - tripStartSec).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            val maxStart = safeTripEnd - alignedSpan
            val start = (tripStartSec + offsetFromTripStart).coerceIn(tripStartSec, maxStart)
            return start to (start + alignedSpan)
        }

        /**
         * Zooms the current viewport `[curStartSec, curEndSec]` by [scaleFactor] (> 1 zooms in,
         * < 1 zooms out) while keeping the timestamp at [anchorRatio] stationary before edge clamping.
         */
        fun zoomViewportAroundAnchor(
            tripStartSec: Long,
            tripEndSec: Long,
            curStartSec: Long,
            curEndSec: Long,
            scaleFactor: Float,
            anchorRatio: Float,
            minSpanSec: Long = MIN_VIEWPORT_SPAN_SEC
        ): Pair<Long, Long> {
            val curSpan = max(STEP_SEC, curEndSec - curStartSec).toDouble()
            val safeScale = scaleFactor.coerceIn(1f / MAX_ZOOM_FACTOR, MAX_ZOOM_FACTOR).toDouble()
            val safeRatio = anchorRatio.coerceIn(0f, 1f).toDouble()
            val anchorTs = curStartSec.toDouble() + safeRatio * curSpan
            val newSpan = curSpan / safeScale
            val reqStart = (anchorTs - safeRatio * newSpan).roundToLong()
            val reqEnd = reqStart + newSpan.roundToLong()
            return clampViewport(tripStartSec, tripEndSec, reqStart, reqEnd, minSpanSec)
        }

        /**
         * Shifts the current viewport `[curStartSec, curEndSec]` by [deltaSec] snapped to the 15-second
         * grid while preserving the exact viewport span inside `[tripStartSec, tripEndSec]`.
         */
        fun panViewportByDeltaSec(
            tripStartSec: Long,
            tripEndSec: Long,
            curStartSec: Long,
            curEndSec: Long,
            deltaSec: Long
        ): Pair<Long, Long> {
            val safeTripEnd = max(tripStartSec + STEP_SEC, tripEndSec)
            val tripSpan = safeTripEnd - tripStartSec
            val span = max(STEP_SEC, curEndSec - curStartSec).coerceAtMost(tripSpan)
            val alignedDelta = (deltaSec.toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            val maxStart = max(tripStartSec, safeTripEnd - span)
            val newStart = (curStartSec + alignedDelta).coerceIn(tripStartSec, maxStart)
            return newStart to (newStart + span)
        }

        /**
         * Ensures [targetTs] sits comfortably within `[curStartSec, curEndSec]` with [marginRatio]
         * edge headroom. Leaves the viewport untouched if [targetTs] is already inside the safe interior.
         */
        fun ensureTimestampVisible(
            tripStartSec: Long,
            tripEndSec: Long,
            curStartSec: Long,
            curEndSec: Long,
            targetTs: Long,
            marginRatio: Float = DEFAULT_VISIBLE_MARGIN_RATIO,
            centerIfOutside: Boolean = false
        ): Pair<Long, Long> {
            val span = max(STEP_SEC, curEndSec - curStartSec)
            val safeMarginRatio = marginRatio.coerceIn(0f, MAX_VISIBLE_MARGIN_RATIO)
            val marginSec = ((span * safeMarginRatio).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            val innerStart = curStartSec + marginSec
            val innerEnd = curEndSec - marginSec
            if (innerStart <= innerEnd && targetTs in innerStart..innerEnd) {
                return curStartSec to curEndSec
            }
            val reqStart = if (centerIfOutside) {
                targetTs - span / 2L
            } else if (targetTs < innerStart) {
                targetTs - marginSec
            } else {
                targetTs - (span - marginSec)
            }
            return clampViewport(tripStartSec, tripEndSec, reqStart, reqStart + span)
        }

        /**
         * Computes a zoomed viewport framing `[sliceStartSec, sliceEndSec]` with [paddingRatio] margin
         * (at least 30s) on both sides so adjacent dividers remain visible and draggable.
         */
        fun computeSegmentFocusViewport(
            tripStartSec: Long,
            tripEndSec: Long,
            sliceStartSec: Long,
            sliceEndSec: Long,
            paddingRatio: Float = DEFAULT_SEGMENT_FOCUS_PADDING_RATIO,
            minSpanSec: Long = MIN_VIEWPORT_SPAN_SEC
        ): Pair<Long, Long> {
            val sliceSpan = max(STEP_SEC, sliceEndSec - sliceStartSec)
            val padSec = max(
                MIN_SEGMENT_FOCUS_PADDING_SEC,
                ((sliceSpan * paddingRatio.coerceAtLeast(0f)).toDouble() / STEP_SEC.toDouble()).roundToLong() * STEP_SEC
            )
            var reqStart = sliceStartSec - padSec
            var reqEnd = sliceEndSec + padSec
            if (reqEnd - reqStart < minSpanSec) {
                val mid = (sliceStartSec + sliceEndSec) / 2L
                val half = minSpanSec / 2L
                reqStart = mid - half
                reqEnd = reqStart + minSpanSec
            }
            return clampViewport(tripStartSec, tripEndSec, reqStart, reqEnd, minSpanSec)
        }

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

        const val SPEED_LOG_SCALE_KNEE_MPS = 0.5f

        /**
         * Maps movement speed [0, maxSpeedMps] onto [0, 1] using a shifted logarithmic scale
         * `ln(1 + v / k) / ln(1 + max / k)` so walking paces (~1.0–1.8 m/s) remain clearly
         * visible above 0 even when transit/highway speeds (10–30+ m/s) appear in the same trip.
         */
        fun normalizeLogSpeed(speedMps: Float, maxSpeedMps: Float): Float {
            val clampedMax = max(12f, maxSpeedMps)
            val clampedSpd = speedMps.coerceIn(0f, clampedMax)
            if (clampedSpd <= 0f) return 0f
            val denom = ln(1f + clampedMax / SPEED_LOG_SCALE_KNEE_MPS)
            if (denom <= 0f) return 0f
            return (ln(1f + clampedSpd / SPEED_LOG_SCALE_KNEE_MPS) / denom).coerceIn(0f, 1f)
        }

        /**
         * Returns true when a user scrub on a live-following chart lands at or past the live edge
         * (the latest available sample or within one step of the trailing window end).
         */
        fun shouldFollowLiveEdgeAfterScrub(
            targetEpochSec: Long,
            latestSlotEpochSec: Long,
            windowEndEpochSec: Long,
            stepSec: Long = STEP_SEC
        ): Boolean = targetEpochSec >= max(latestSlotEpochSec, windowEndEpochSec - stepSec)

        /**
         * Resolves `(cursorEpochSec, isFollowingLiveEdge)` whenever new ring slots arrive.
         * Live charts track the rightmost reading until the user scrubs left, and re-attach
         * automatically once a detached cursor scrolls past the trailing window.
         */
        fun resolveCursorOnSlotsUpdated(
            followLatestEdge: Boolean,
            isFollowingLiveEdge: Boolean,
            currentCursorEpochSec: Long,
            tripStartEpochSec: Long,
            tripEndEpochSec: Long,
            @Suppress("UNUSED_PARAMETER") firstSlotEpochSec: Long,
            midSlotEpochSec: Long,
            lastSlotEpochSec: Long
        ): Pair<Long, Boolean> {
            return if (followLatestEdge) {
                if (isFollowingLiveEdge ||
                    currentCursorEpochSec < tripStartEpochSec ||
                    currentCursorEpochSec > tripEndEpochSec
                ) {
                    lastSlotEpochSec to true
                } else {
                    currentCursorEpochSec to false
                }
            } else {
                val resolvedCursor = if (
                    currentCursorEpochSec < tripStartEpochSec ||
                    currentCursorEpochSec > tripEndEpochSec
                ) {
                    midSlotEpochSec
                } else {
                    currentCursorEpochSec
                }
                resolvedCursor to false
            }
        }
    }
}
