package com.teledrive.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

class EcoScoreEngine {

    // 🏍️ BIKE CONSTANTS
    private val BASE_LITERS_PER_KM = 0.025f // ~40 km/L
    private val HARSH_ACCEL_WASTAGE = 0.004f
    private val HARSH_BRAKE_WASTAGE = 0.0025f

    private var totalFuelWasted = 0f
    private var score = 100

    // Event counters for diminishing returns
    private var accelCount = 0
    private var brakeCount = 0
    private var unstableCount = 0

    // Base penalties (stricter — score 95-100 reserved for zero-event rides)
    private val accelBasePenalty = 8f      // was 6  (+33%)
    private val brakeBasePenalty = 10f     // was 8  (+25%)
    private val unstableBasePenalty = 9f   // was 5  (+80%)

    fun processEvent(event: DrivingEvent): Int {

        val severity = abs(event.severity).coerceIn(0f, 10f)

        // Normalize severity to 0-1 range — min 0.7 ensures every event has real impact
        val normalized = (severity / 10f).coerceIn(0.7f, 1.0f)

        // Increment event counters
        when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> accelCount++
            DrivingEventType.HARSH_BRAKING -> brakeCount++
            DrivingEventType.UNSTABLE_RIDE -> unstableCount++
            DrivingEventType.NORMAL -> return score
        }

        // Get base penalty for this event type
        val basePenalty = when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> accelBasePenalty
            DrivingEventType.HARSH_BRAKING -> brakeBasePenalty
            DrivingEventType.UNSTABLE_RIDE -> unstableBasePenalty
            else -> 0f
        }

        // Get event count for diminishing returns
        val count = when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> accelCount
            DrivingEventType.HARSH_BRAKING -> brakeCount
            DrivingEventType.UNSTABLE_RIDE -> unstableCount
            else -> 1
        }

        // ✅ LOGARITHMIC DIMINISHING RETURNS
        // 1st event: factor = 1.0 (full penalty)
        // 5th event: factor = 0.45 (45% penalty)
        // 10th event: factor = 0.32 (32% penalty)
        // 20th event: factor = 0.22 (22% penalty)
        val diminishingFactor = 1.0f / kotlin.math.sqrt(count.toFloat())

        // Final penalty = base × severity × diminishing factor (use rounding for accuracy)
        val penalty = (basePenalty * normalized * diminishingFactor).roundToInt().coerceAtLeast(1)

        // ✅ FUEL WASTAGE (scaled with severity, not affected by diminishing)
        val wastage = when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> (severity / 10f) * HARSH_ACCEL_WASTAGE
            DrivingEventType.HARSH_BRAKING -> (severity / 10f) * HARSH_BRAKE_WASTAGE
            else -> 0f
        }

        totalFuelWasted += wastage

        // ✅ UPDATE SCORE with bounds
        score -= penalty
        score = score.coerceIn(0, 100)

        return score
    }

    fun getFinalFuelConsumption(distanceKm: Float): Float {
        val baseConsumption = distanceKm * BASE_LITERS_PER_KM
        return baseConsumption + totalFuelWasted
    }

    fun getFinancialLoss(fuelPrice: Float = 105.0f): Float {
        return totalFuelWasted * fuelPrice
    }
}