package io.github.androidaql.engine

import kotlin.math.max

/**
 * Sliding-window step-cadence estimator used to preserve walking speed telemetry when GPS is weak
 * (>50m accuracy indoors or in urban canyons) or before cumulative GPS displacement breaks out of
 * the uncertainty radius.
 *
 * Requires [minStepsToConfirm] steps within [windowMs] and decays to `null` once [staleTimeoutMs]
 * elapses without another step, preventing single jolts or stationary phone handling from producing
 * phantom movement.
 */
class StepMovementEstimator(
    val windowMs: Long = 10_000L,
    val staleTimeoutMs: Long = 4_000L,
    val minStepsToConfirm: Int = 3,
    val maxSpeedMps: Float = 4.5f
) {
    enum class SpeedSource {
        NONE,
        GPS,
        STEP_CADENCE
    }

    data class FusedSpeed(
        val speedMps: Float?,
        val source: SpeedSource
    )

    private val stepTimestampsMs = ArrayDeque<Long>()
    private var lastCounterTotal: Long? = null
    private var lastCounterTimestampMs: Long = 0L

    @Synchronized
    fun reset() {
        stepTimestampsMs.clear()
        lastCounterTotal = null
        lastCounterTimestampMs = 0L
    }

    @Synchronized
    fun onStepDetected(timestampMs: Long, count: Int = 1) {
        val safeCount = count.coerceIn(1, 30)
        repeat(safeCount) {
            stepTimestampsMs.addLast(timestampMs)
        }
        pruneOld(timestampMs)
    }

    /**
     * Fallback adapter for [android.hardware.Sensor.TYPE_STEP_COUNTER], which reports cumulative
     * steps since reboot rather than individual step events.
     */
    @Synchronized
    fun onStepCounterTotal(totalStepsSinceBoot: Long, timestampMs: Long) {
        val prevTotal = lastCounterTotal
        val prevTs = lastCounterTimestampMs
        lastCounterTotal = totalStepsSinceBoot
        lastCounterTimestampMs = timestampMs
        if (prevTotal == null || totalStepsSinceBoot <= prevTotal) {
            pruneOld(timestampMs)
            return
        }
        val delta = (totalStepsSinceBoot - prevTotal).coerceAtMost(30L).toInt()
        if (delta == 1) {
            stepTimestampsMs.addLast(timestampMs)
        } else {
            val startMs = maxOf(prevTs, timestampMs - windowMs)
            val spanMs = max(0L, timestampMs - startMs)
            for (i in 1..delta) {
                val ts = startMs + (spanMs * i) / delta
                stepTimestampsMs.addLast(ts)
            }
        }
        pruneOld(timestampMs)
    }

    @Synchronized
    fun estimateSpeedMps(nowMs: Long): Float? {
        pruneOld(nowMs)
        if (stepTimestampsMs.size < minStepsToConfirm) return null
        val lastMs = stepTimestampsMs.last()
        if (nowMs - lastMs > staleTimeoutMs) return null

        val firstMs = stepTimestampsMs.first()
        val spanSec = max(1.2f, (lastMs - firstMs) / 1000.0f)
        val cadenceHz = (stepTimestampsMs.size - 1) / spanSec
        val strideMeters = (0.60f + 0.10f * cadenceHz).coerceIn(0.65f, 1.05f)
        return (cadenceHz * strideMeters).coerceIn(0.4f, maxSpeedMps)
    }

    private fun pruneOld(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (stepTimestampsMs.isNotEmpty() && stepTimestampsMs.first() < cutoff) {
            stepTimestampsMs.removeFirst()
        }
    }

    companion object {
        private const val WEAK_GPS_THRESHOLD_M = 50.0f

        /**
         * Combines GPS filtered speed with step-cadence walking speed using accuracy-gated max():
         * - When GPS accuracy is healthy (`<= 50m`), takes `max(gpsSpeedMps, stepSpeedMps)` so
         *   vehicles keep full speed while meandering/zig-zagging walks don't dip below step pace.
         * - When GPS is weak (`> 50m`) or unavailable, confirmed step cadence overrides coarse GPS
         *   so indoor Wi-Fi/cell jumps cannot drown out walking transitions.
         * - When the step sensor is actively running during a trip with no GPS fix yet, returns `0f`
         *   while stationary so pauses at crosswalks stay continuous instead of flickering "GPS Off".
         */
        fun fuseMovementSpeed(
            gpsSpeedMps: Float?,
            gpsAccuracyM: Float?,
            stepSpeedMps: Float?,
            isStepSensorActive: Boolean = false
        ): FusedSpeed {
            val isGpsHealthy = gpsAccuracyM != null && gpsAccuracyM <= WEAK_GPS_THRESHOLD_M
            if (isGpsHealthy) {
                return when {
                    gpsSpeedMps != null && stepSpeedMps != null ->
                        if (stepSpeedMps > gpsSpeedMps) {
                            FusedSpeed(stepSpeedMps, SpeedSource.STEP_CADENCE)
                        } else {
                            FusedSpeed(gpsSpeedMps, SpeedSource.GPS)
                        }
                    stepSpeedMps != null -> FusedSpeed(stepSpeedMps, SpeedSource.STEP_CADENCE)
                    gpsSpeedMps != null -> FusedSpeed(gpsSpeedMps, SpeedSource.GPS)
                    isStepSensorActive -> FusedSpeed(0f, SpeedSource.STEP_CADENCE)
                    else -> FusedSpeed(null, SpeedSource.NONE)
                }
            }
            return when {
                stepSpeedMps != null -> FusedSpeed(stepSpeedMps, SpeedSource.STEP_CADENCE)
                gpsSpeedMps != null -> FusedSpeed(gpsSpeedMps, SpeedSource.GPS)
                isStepSensorActive -> FusedSpeed(0f, SpeedSource.STEP_CADENCE)
                else -> FusedSpeed(null, SpeedSource.NONE)
            }
        }
    }
}
