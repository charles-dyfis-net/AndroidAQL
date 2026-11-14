package io.github.androidaql.engine

import io.github.androidaql.storage.BleCollectionMode
import kotlin.math.max

/**
 * Pure, deterministic state machine governing:
 * - BLE-gated GPS activation (`Idle_OutOfRange` vs `Stationary_KnownAP` vs `Field_Excursion_Active` vs `Field_Excursion_SignalPaused`)
 * - Decoupled BLE timeouts (`gpsPauseTimeoutSec = 90s` vs `excursionCloseTimeoutSec = 2700s / 45m`)
 * - Context-dependent BLE Collection Mode (forcing `PASSIVE_ONLY` at Saved Wi-Fi Places while
 *   honoring the user's configured Excursion mode during portable trips).
 */
class ExcursionStateMachine(
    val gpsPauseTimeoutSec: Long = 90L,
    val excursionCloseTimeoutSec: Long = 45L * 60L
) {
    enum class CollectorState {
        IDLE_OUT_OF_RANGE,
        STATIONARY_KNOWN_AP,
        FIELD_EXCURSION_ACTIVE,
        FIELD_EXCURSION_SIGNAL_PAUSED
    }

    data class TransitionOutput(
        val previousState: CollectorState,
        val currentState: CollectorState,
        val shouldRunGps: Boolean,
        val effectiveBleMode: BleCollectionMode,
        val activeKnownPlaceId: Int,
        val startedNewExcursion: Boolean,
        val closedExcursion: Boolean
    )

    var currentState: CollectorState = CollectorState.IDLE_OUT_OF_RANGE
        private set

    var lastBlePacketEpochSec: Long = -1L
        private set

    var currentKnownPlaceId: Int = 0
        private set

    var hasConfiguredKnownPlaces: Boolean = true
        private set

    var isManualExcursionOverride: Boolean = false
        private set

    private var manualStartSuppressedPlaceId: Int = 0
    private var manualEndHoldUntilKnownPlaceOrBleTimeout: Boolean = false

    var userExcursionBleMode: BleCollectionMode = BleCollectionMode.PERIODIC_GATT_KEEPALIVE

    @Synchronized
    fun onBleAdvertisement(
        timestampEpochSec: Long,
        connectedKnownPlaceId: Int,
        hasConfiguredKnownPlaces: Boolean = this.hasConfiguredKnownPlaces
    ): TransitionOutput {
        lastBlePacketEpochSec = max(lastBlePacketEpochSec, timestampEpochSec)
        if (connectedKnownPlaceId != manualStartSuppressedPlaceId) {
            manualStartSuppressedPlaceId = 0
        }
        currentKnownPlaceId = connectedKnownPlaceId
        this.hasConfiguredKnownPlaces = hasConfiguredKnownPlaces
        return evaluate(timestampEpochSec)
    }

    @Synchronized
    fun onWifiContextChanged(
        timestampEpochSec: Long,
        connectedKnownPlaceId: Int,
        hasConfiguredKnownPlaces: Boolean = this.hasConfiguredKnownPlaces
    ): TransitionOutput {
        if (connectedKnownPlaceId != manualStartSuppressedPlaceId) {
            manualStartSuppressedPlaceId = 0
        }
        currentKnownPlaceId = connectedKnownPlaceId
        this.hasConfiguredKnownPlaces = hasConfiguredKnownPlaces
        return evaluate(timestampEpochSec)
    }

    @Synchronized
    fun onManualTripStart(timestampEpochSec: Long): TransitionOutput {
        isManualExcursionOverride = true
        manualEndHoldUntilKnownPlaceOrBleTimeout = false
        manualStartSuppressedPlaceId = currentKnownPlaceId
        if (lastBlePacketEpochSec < 0L || (timestampEpochSec - lastBlePacketEpochSec) > excursionCloseTimeoutSec) {
            lastBlePacketEpochSec = timestampEpochSec - gpsPauseTimeoutSec - 1L
        }
        return evaluate(timestampEpochSec)
    }

    @Synchronized
    fun onManualTripEnd(timestampEpochSec: Long): TransitionOutput {
        isManualExcursionOverride = false
        manualStartSuppressedPlaceId = 0
        if (currentKnownPlaceId == 0) {
            manualEndHoldUntilKnownPlaceOrBleTimeout = true
        }
        return evaluate(timestampEpochSec, forceClose = true)
    }

    @Synchronized
    fun onTimerTick(timestampEpochSec: Long): TransitionOutput {
        return evaluate(timestampEpochSec)
    }

    private fun evaluate(nowEpochSec: Long, forceClose: Boolean = false): TransitionOutput {
        val prev = currentState
        val bleAgeSec = if (lastBlePacketEpochSec < 0L) Long.MAX_VALUE else (nowEpochSec - lastBlePacketEpochSec)
        val isBleFresh = bleAgeSec <= gpsPauseTimeoutSec
        if (!forceClose && (currentKnownPlaceId > 0 || !isBleFresh)) {
            manualEndHoldUntilKnownPlaceOrBleTimeout = false
        }
        val effectiveKnownPlaceId = if (currentKnownPlaceId > 0 && currentKnownPlaceId != manualStartSuppressedPlaceId) {
            currentKnownPlaceId
        } else {
            0
        }
        val isStationaryOrUnconfigured = forceClose ||
            (effectiveKnownPlaceId > 0) ||
            (!hasConfiguredKnownPlaces && !isManualExcursionOverride) ||
            manualEndHoldUntilKnownPlaceOrBleTimeout

        var startedExcursion = false
        var closedExcursion = false

        val next = when (prev) {
            CollectorState.IDLE_OUT_OF_RANGE -> {
                when {
                    !isBleFresh -> {
                        if (isManualExcursionOverride && bleAgeSec <= excursionCloseTimeoutSec && !isStationaryOrUnconfigured) {
                            startedExcursion = true
                            CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                        } else {
                            CollectorState.IDLE_OUT_OF_RANGE
                        }
                    }
                    isStationaryOrUnconfigured -> CollectorState.STATIONARY_KNOWN_AP
                    else -> {
                        startedExcursion = true
                        CollectorState.FIELD_EXCURSION_ACTIVE
                    }
                }
            }
            CollectorState.STATIONARY_KNOWN_AP -> {
                when {
                    !isBleFresh -> {
                        if (isManualExcursionOverride && bleAgeSec <= excursionCloseTimeoutSec && !isStationaryOrUnconfigured) {
                            startedExcursion = true
                            CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                        } else {
                            CollectorState.IDLE_OUT_OF_RANGE
                        }
                    }
                    isStationaryOrUnconfigured -> CollectorState.STATIONARY_KNOWN_AP
                    else -> {
                        startedExcursion = true
                        CollectorState.FIELD_EXCURSION_ACTIVE
                    }
                }
            }
            CollectorState.FIELD_EXCURSION_ACTIVE -> {
                when {
                    isStationaryOrUnconfigured -> {
                        closedExcursion = true
                        if (isBleFresh) CollectorState.STATIONARY_KNOWN_AP else CollectorState.IDLE_OUT_OF_RANGE
                    }
                    isBleFresh -> CollectorState.FIELD_EXCURSION_ACTIVE
                    bleAgeSec <= excursionCloseTimeoutSec -> CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                    else -> {
                        closedExcursion = true
                        CollectorState.IDLE_OUT_OF_RANGE
                    }
                }
            }
            CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED -> {
                when {
                    isStationaryOrUnconfigured -> {
                        closedExcursion = true
                        if (isBleFresh) CollectorState.STATIONARY_KNOWN_AP else CollectorState.IDLE_OUT_OF_RANGE
                    }
                    isBleFresh -> CollectorState.FIELD_EXCURSION_ACTIVE
                    bleAgeSec <= excursionCloseTimeoutSec -> CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED
                    else -> {
                        closedExcursion = true
                        CollectorState.IDLE_OUT_OF_RANGE
                    }
                }
            }
        }

        if (closedExcursion) {
            isManualExcursionOverride = false
            manualStartSuppressedPlaceId = 0
        }

        currentState = next
        val shouldRunGps = (next == CollectorState.FIELD_EXCURSION_ACTIVE)
        val effectiveBleMode = when (next) {
            CollectorState.FIELD_EXCURSION_ACTIVE,
            CollectorState.FIELD_EXCURSION_SIGNAL_PAUSED -> userExcursionBleMode
            CollectorState.STATIONARY_KNOWN_AP -> {
                if (userExcursionBleMode == BleCollectionMode.PASSIVE_ONLY) {
                    BleCollectionMode.PASSIVE_ONLY
                } else {
                    BleCollectionMode.PERIODIC_GATT_KEEPALIVE
                }
            }
            CollectorState.IDLE_OUT_OF_RANGE -> BleCollectionMode.PASSIVE_ONLY
        }

        return TransitionOutput(
            previousState = prev,
            currentState = next,
            shouldRunGps = shouldRunGps,
            effectiveBleMode = effectiveBleMode,
            activeKnownPlaceId = if (next == CollectorState.STATIONARY_KNOWN_AP && effectiveKnownPlaceId > 0) effectiveKnownPlaceId else 0,
            startedNewExcursion = startedExcursion,
            closedExcursion = closedExcursion
        )
    }
}
