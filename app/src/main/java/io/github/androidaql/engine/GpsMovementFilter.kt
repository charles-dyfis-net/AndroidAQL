package io.github.androidaql.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Uncertainty-gated cumulative anchor filter for GPS movement telemetry.
 *
 * Why naive sample-to-sample suppression (`dist(fix_t, fix_{t-1}) < acc_t`) fails:
 * - At a normal walking pace (1.3 m/s) with a 5-second GPS interval, each step is ~6.5 meters.
 * - Under typical urban GPS accuracy (12–18 meters), every single consecutive step is smaller than
 *   the inaccuracy radius, so naive sample-to-sample filtering suppresses 100% of real walking.
 *
 * How the Cumulative Anchor Filter solves both stationary jitter and slow cumulative movement:
 * 1. Maintains an in-memory reference Anchor `(anchorLat, anchorLon, anchorTimeMs, anchorAccM)`.
 *    (Raw coordinates exist strictly in volatile memory and are wiped when GPS stops).
 * 2. Measures cumulative displacement `d_anchor` from the Anchor to the current fix and compares it
 *    against the combined horizontal uncertainty radius `R_noise = max(minDeadbandM, hypot(anchorAccM, accM))`.
 * 3. While `d_anchor <= R_noise`, movement is suppressed (`speedMps = 0f`, `deltaDistanceM = 0f`).
 *    If a stationary fix arrives with tighter accuracy (`accM < anchorAccM`), the anchor tightens.
 * 4. Once cumulative movement breaks out of `R_noise` (`d_anchor > R_noise`), the anchor advances to
 *    the new fix and reports the net displacement and speed over `t - anchorTimeMs` (discounting the
 *    uncertainty radius when GPS accuracy is degraded indoors/urban canyons).
 */
class GpsMovementFilter(
    val minDeadbandMeters: Float = 6.0f,
    val weakGpsThresholdMeters: Float = 50.0f
) {
    data class FilteredMovement(
        val speedMps: Float,
        val deltaDistanceM: Float,
        val horizontalAccuracyM: Float,
        val suppressedWithinNoiseRadius: Boolean
    )

    private var hasAnchor: Boolean = false
    private var anchorLat: Double = 0.0
    private var anchorLon: Double = 0.0
    private var anchorTimeMs: Long = 0L
    private var anchorAccM: Float = 0f

    @Synchronized
    fun reset() {
        hasAnchor = false
        anchorLat = 0.0
        anchorLon = 0.0
        anchorTimeMs = 0L
        anchorAccM = 0f
    }

    @Synchronized
    fun onLocationFix(
        latDeg: Double,
        lonDeg: Double,
        timestampMs: Long,
        horizontalAccuracyM: Float,
        dopplerSpeedMps: Float? = null
    ): FilteredMovement {
        val acc = max(1.0f, horizontalAccuracyM)
        if (!hasAnchor || timestampMs <= anchorTimeMs) {
            hasAnchor = true
            anchorLat = latDeg
            anchorLon = lonDeg
            anchorTimeMs = timestampMs
            anchorAccM = acc
            return FilteredMovement(
                speedMps = 0f,
                deltaDistanceM = 0f,
                horizontalAccuracyM = acc,
                suppressedWithinNoiseRadius = true
            )
        }

        val distFromAnchorM = haversineMeters(anchorLat, anchorLon, latDeg, lonDeg)
        val combinedSigmaM = sqrt(anchorAccM * anchorAccM + acc * acc)
        // Require a slightly higher breakout multiple when inside a weak-GPS (>50m) indoor/urban-canyon zone
        val noiseMultiplier = if (acc > weakGpsThresholdMeters || anchorAccM > weakGpsThresholdMeters) 1.35f else 1.0f
        val noiseRadiusM = max(minDeadbandMeters, combinedSigmaM * noiseMultiplier)

        if (distFromAnchorM <= noiseRadiusM) {
            // Still inside the uncertainty circle around the anchor: suppress jitter!
            // If the new fix has tighter accuracy, refine the anchor position & accuracy without resetting anchorTimeMs.
            if (acc < anchorAccM) {
                anchorLat = latDeg
                anchorLon = lonDeg
                anchorAccM = acc
            }
            return FilteredMovement(
                speedMps = 0f,
                deltaDistanceM = 0f,
                horizontalAccuracyM = acc,
                suppressedWithinNoiseRadius = true
            )
        }

        // Cumulative displacement has broken out of the inaccuracy radius!
        val dtSec = max(1.0f, (timestampMs - anchorTimeMs) / 1000.0f)

        // When GPS accuracy is degraded (> 25m), subtract the excess uncertainty so a single multipath jump
        // doesn't inflate distance/speed; when GPS lock is tight (<= 25m), credit the full anchor-to-anchor distance.
        val creditedDistanceM = if (acc > 25.0f || anchorAccM > 25.0f) {
            max(0f, distFromAnchorM - combinedSigmaM)
        } else {
            distFromAnchorM
        }

        val anchorSpeedMps = creditedDistanceM / dtSec
        val effectiveSpeedMps = if (dopplerSpeedMps != null && dopplerSpeedMps >= 0f && acc <= 25.0f) {
            // Blend Doppler speed with cumulative anchor speed when GPS lock is healthy
            0.5f * dopplerSpeedMps + 0.5f * anchorSpeedMps
        } else {
            anchorSpeedMps
        }

        // Advance anchor to the new confirmed position
        anchorLat = latDeg
        anchorLon = lonDeg
        anchorTimeMs = timestampMs
        anchorAccM = acc

        return FilteredMovement(
            speedMps = effectiveSpeedMps,
            deltaDistanceM = creditedDistanceM,
            horizontalAccuracyM = acc,
            suppressedWithinNoiseRadius = false
        )
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
            val c = 2.0 * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
            return (EARTH_RADIUS_M * c).toFloat()
        }
    }
}
