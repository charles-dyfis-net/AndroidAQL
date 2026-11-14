package io.github.androidaql.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Stateful in-place threshold alert evaluator supporting:
 * - High-threshold alerts for CO₂ and PM2.5, plus Low-Battery alerts (`BATTERY_LOW`) for the Qingping monitor
 * - Persistence window (default 30s sustained beyond threshold before initial trigger)
 * - In-place updates (`UPDATE_IN_PLACE_SILENT`) with peak/extremum tracking, duration, and trend direction
 * - Automatic in-place clearing (`CANCEL_IN_PLACE`) when metric recovers past `threshold ± hysteresisMargin`
 * - Implicit snooze when user swipes away an active alert (re-arms only after recovery or escalation into a higher severity tier).
 */
class AlertEvaluator(
    var co2WarningThresholdPpm: Int = 1000,
    var co2CriticalThresholdPpm: Int = 2000,
    var co2HysteresisPpm: Int = 150,
    var pm25WarningThresholdUgm3: Int = 25,
    var pm25CriticalThresholdUgm3: Int = 55,
    var pm25HysteresisUgm3: Int = 5,
    var batteryWarningThresholdPct: Int = 20,
    var batteryCriticalThresholdPct: Int = 10,
    var batteryHysteresisPct: Int = 5,
    val persistenceWindowSec: Long = 30L
) {
    enum class MetricType(val notificationId: Int, val label: String, val unit: String) {
        CO2(1001, "CO₂", "ppm"),
        PM25(1002, "PM2.5", "µg/m³"),
        BATTERY_LOW(1003, "Qingping Battery Low", "%")
    }

    enum class SeverityTier {
        NONE,
        WARNING,
        CRITICAL
    }

    enum class NotificationDirective {
        NO_CHANGE,
        TRIGGER_NEW_OR_ESCALATE,
        UPDATE_IN_PLACE_SILENT,
        CANCEL_IN_PLACE
    }

    enum class TrendDirection(val symbol: String) {
        RISING("↑"),
        FALLING("↓"),
        STEADY("→")
    }

    data class AlertAction(
        val metric: MetricType,
        val directive: NotificationDirective,
        val severity: SeverityTier,
        val currentValue: Int,
        val peakValueInSpike: Int,
        val durationAboveThresholdSec: Long,
        val trend: TrendDirection
    )

    private class MetricAlertState {
        var firstBeyondEpochSec: Long = -1L
        var activeSeverity: SeverityTier = SeverityTier.NONE
        var snoozedSeverity: SeverityTier = SeverityTier.NONE
        var extremumValue: Int = 0
        var lastValue: Int = -1
        var isNotificationVisible: Boolean = false
    }

    private val states = mapOf(
        MetricType.CO2 to MetricAlertState(),
        MetricType.PM25 to MetricAlertState(),
        MetricType.BATTERY_LOW to MetricAlertState()
    )

    @Synchronized
    fun evaluateSample(
        timestampEpochSec: Long,
        co2Ppm: Int,
        pm25Ugm3: Int,
        batteryPct: Int = 255
    ): List<AlertAction> {
        val actions = mutableListOf(
            evaluateHighMetric(
                metric = MetricType.CO2,
                timestampEpochSec = timestampEpochSec,
                value = co2Ppm,
                warnThreshold = co2WarningThresholdPpm,
                critThreshold = co2CriticalThresholdPpm,
                hysteresis = co2HysteresisPpm,
                trendDeadband = 10
            ),
            evaluateHighMetric(
                metric = MetricType.PM25,
                timestampEpochSec = timestampEpochSec,
                value = pm25Ugm3,
                warnThreshold = pm25WarningThresholdUgm3,
                critThreshold = pm25CriticalThresholdUgm3,
                hysteresis = pm25HysteresisUgm3,
                trendDeadband = 2
            )
        )
        if (batteryPct in 0..100) {
            actions.add(
                evaluateLowMetric(
                    metric = MetricType.BATTERY_LOW,
                    timestampEpochSec = timestampEpochSec,
                    value = batteryPct,
                    warnThreshold = batteryWarningThresholdPct,
                    critThreshold = batteryCriticalThresholdPct,
                    hysteresis = batteryHysteresisPct
                )
            )
        }
        return actions
    }

    @Synchronized
    fun onUserDismissedNotification(metric: MetricType) {
        val st = states.getValue(metric)
        st.snoozedSeverity = st.activeSeverity
        st.isNotificationVisible = false
    }

    @Synchronized
    fun onOutOfRange(): List<AlertAction> {
        val actions = mutableListOf<AlertAction>()
        for ((metric, st) in states) {
            if (st.isNotificationVisible || st.activeSeverity != SeverityTier.NONE) {
                st.firstBeyondEpochSec = -1L
                st.activeSeverity = SeverityTier.NONE
                st.snoozedSeverity = SeverityTier.NONE
                st.extremumValue = 0
                st.isNotificationVisible = false
                actions.add(
                    AlertAction(
                        metric = metric,
                        directive = NotificationDirective.CANCEL_IN_PLACE,
                        severity = SeverityTier.NONE,
                        currentValue = 0,
                        peakValueInSpike = 0,
                        durationAboveThresholdSec = 0L,
                        trend = TrendDirection.STEADY
                    )
                )
            }
        }
        return actions
    }

    private fun evaluateHighMetric(
        metric: MetricType,
        timestampEpochSec: Long,
        value: Int,
        warnThreshold: Int,
        critThreshold: Int,
        hysteresis: Int,
        trendDeadband: Int
    ): AlertAction {
        val st = states.getValue(metric)
        val trend = when {
            st.lastValue < 0 -> TrendDirection.STEADY
            value > st.lastValue + trendDeadband -> TrendDirection.RISING
            value < st.lastValue - trendDeadband -> TrendDirection.FALLING
            else -> TrendDirection.STEADY
        }
        st.lastValue = value

        val rawTier = when {
            value >= critThreshold -> SeverityTier.CRITICAL
            value >= warnThreshold -> SeverityTier.WARNING
            else -> SeverityTier.NONE
        }

        if (st.activeSeverity != SeverityTier.NONE) {
            val recoveryThreshold = warnThreshold - hysteresis
            if (value <= recoveryThreshold) {
                val shouldCancel = st.isNotificationVisible
                st.firstBeyondEpochSec = -1L
                st.activeSeverity = SeverityTier.NONE
                st.snoozedSeverity = SeverityTier.NONE
                st.extremumValue = 0
                st.isNotificationVisible = false
                return AlertAction(
                    metric = metric,
                    directive = if (shouldCancel) NotificationDirective.CANCEL_IN_PLACE else NotificationDirective.NO_CHANGE,
                    severity = SeverityTier.NONE,
                    currentValue = value,
                    peakValueInSpike = 0,
                    durationAboveThresholdSec = 0L,
                    trend = trend
                )
            }

            st.extremumValue = max(st.extremumValue, value)
            val durationSec = max(0L, timestampEpochSec - st.firstBeyondEpochSec)

            if (rawTier.ordinal > st.activeSeverity.ordinal) {
                st.activeSeverity = rawTier
                st.isNotificationVisible = true
                return AlertAction(
                    metric = metric,
                    directive = NotificationDirective.TRIGGER_NEW_OR_ESCALATE,
                    severity = st.activeSeverity,
                    currentValue = value,
                    peakValueInSpike = st.extremumValue,
                    durationAboveThresholdSec = durationSec,
                    trend = trend
                )
            }

            if (st.snoozedSeverity.ordinal >= st.activeSeverity.ordinal) {
                return AlertAction(
                    metric = metric,
                    directive = NotificationDirective.NO_CHANGE,
                    severity = st.activeSeverity,
                    currentValue = value,
                    peakValueInSpike = st.extremumValue,
                    durationAboveThresholdSec = durationSec,
                    trend = trend
                )
            }

            return AlertAction(
                metric = metric,
                directive = NotificationDirective.UPDATE_IN_PLACE_SILENT,
                severity = st.activeSeverity,
                currentValue = value,
                peakValueInSpike = st.extremumValue,
                durationAboveThresholdSec = durationSec,
                trend = trend
            )
        }

        if (rawTier == SeverityTier.NONE) {
            st.firstBeyondEpochSec = -1L
            st.extremumValue = 0
            return AlertAction(
                metric = metric,
                directive = NotificationDirective.NO_CHANGE,
                severity = SeverityTier.NONE,
                currentValue = value,
                peakValueInSpike = 0,
                durationAboveThresholdSec = 0L,
                trend = trend
            )
        }

        if (st.firstBeyondEpochSec < 0L) {
            st.firstBeyondEpochSec = timestampEpochSec
            st.extremumValue = value
        } else {
            st.extremumValue = max(st.extremumValue, value)
        }

        val sustainedSec = timestampEpochSec - st.firstBeyondEpochSec
        if (sustainedSec >= persistenceWindowSec) {
            st.activeSeverity = rawTier
            st.isNotificationVisible = true
            return AlertAction(
                metric = metric,
                directive = NotificationDirective.TRIGGER_NEW_OR_ESCALATE,
                severity = rawTier,
                currentValue = value,
                peakValueInSpike = st.extremumValue,
                durationAboveThresholdSec = sustainedSec,
                trend = trend
            )
        }

        return AlertAction(
            metric = metric,
            directive = NotificationDirective.NO_CHANGE,
            severity = SeverityTier.NONE,
            currentValue = value,
            peakValueInSpike = st.extremumValue,
            durationAboveThresholdSec = sustainedSec,
            trend = trend
        )
    }

    private fun evaluateLowMetric(
        metric: MetricType,
        timestampEpochSec: Long,
        value: Int,
        warnThreshold: Int,
        critThreshold: Int,
        hysteresis: Int
    ): AlertAction {
        val st = states.getValue(metric)
        val trend = when {
            st.lastValue < 0 -> TrendDirection.STEADY
            value > st.lastValue + 1 -> TrendDirection.RISING
            value < st.lastValue - 1 -> TrendDirection.FALLING
            else -> TrendDirection.STEADY
        }
        st.lastValue = value

        val rawTier = when {
            value <= critThreshold -> SeverityTier.CRITICAL
            value <= warnThreshold -> SeverityTier.WARNING
            else -> SeverityTier.NONE
        }

        if (st.activeSeverity != SeverityTier.NONE) {
            val recoveryThreshold = warnThreshold + hysteresis
            if (value >= recoveryThreshold) {
                val shouldCancel = st.isNotificationVisible
                st.firstBeyondEpochSec = -1L
                st.activeSeverity = SeverityTier.NONE
                st.snoozedSeverity = SeverityTier.NONE
                st.extremumValue = 100
                st.isNotificationVisible = false
                return AlertAction(
                    metric = metric,
                    directive = if (shouldCancel) NotificationDirective.CANCEL_IN_PLACE else NotificationDirective.NO_CHANGE,
                    severity = SeverityTier.NONE,
                    currentValue = value,
                    peakValueInSpike = value,
                    durationAboveThresholdSec = 0L,
                    trend = trend
                )
            }

            st.extremumValue = min(st.extremumValue, value)
            val durationSec = max(0L, timestampEpochSec - st.firstBeyondEpochSec)

            if (rawTier.ordinal > st.activeSeverity.ordinal) {
                st.activeSeverity = rawTier
                st.isNotificationVisible = true
                return AlertAction(
                    metric = metric,
                    directive = NotificationDirective.TRIGGER_NEW_OR_ESCALATE,
                    severity = st.activeSeverity,
                    currentValue = value,
                    peakValueInSpike = st.extremumValue,
                    durationAboveThresholdSec = durationSec,
                    trend = trend
                )
            }

            if (st.snoozedSeverity.ordinal >= st.activeSeverity.ordinal) {
                return AlertAction(
                    metric = metric,
                    directive = NotificationDirective.NO_CHANGE,
                    severity = st.activeSeverity,
                    currentValue = value,
                    peakValueInSpike = st.extremumValue,
                    durationAboveThresholdSec = durationSec,
                    trend = trend
                )
            }

            return AlertAction(
                metric = metric,
                directive = NotificationDirective.UPDATE_IN_PLACE_SILENT,
                severity = st.activeSeverity,
                currentValue = value,
                peakValueInSpike = st.extremumValue,
                durationAboveThresholdSec = durationSec,
                trend = trend
            )
        }

        if (rawTier == SeverityTier.NONE) {
            st.firstBeyondEpochSec = -1L
            st.extremumValue = value
            return AlertAction(
                metric = metric,
                directive = NotificationDirective.NO_CHANGE,
                severity = SeverityTier.NONE,
                currentValue = value,
                peakValueInSpike = value,
                durationAboveThresholdSec = 0L,
                trend = trend
            )
        }

        if (st.firstBeyondEpochSec < 0L) {
            st.firstBeyondEpochSec = timestampEpochSec
            st.extremumValue = value
        } else {
            st.extremumValue = min(st.extremumValue, value)
        }

        val sustainedSec = timestampEpochSec - st.firstBeyondEpochSec
        if (sustainedSec >= persistenceWindowSec) {
            st.activeSeverity = rawTier
            st.isNotificationVisible = true
            return AlertAction(
                metric = metric,
                directive = NotificationDirective.TRIGGER_NEW_OR_ESCALATE,
                severity = rawTier,
                currentValue = value,
                peakValueInSpike = st.extremumValue,
                durationAboveThresholdSec = sustainedSec,
                trend = trend
            )
        }

        return AlertAction(
            metric = metric,
            directive = NotificationDirective.NO_CHANGE,
            severity = SeverityTier.NONE,
            currentValue = value,
            peakValueInSpike = st.extremumValue,
            durationAboveThresholdSec = sustainedSec,
            trend = trend
        )
    }
}
