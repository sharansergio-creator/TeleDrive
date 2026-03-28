package com.teledrive.app.core

import kotlin.math.abs

class EcoScoreEngine {

    // 🏍️ BIKE CONSTANTS
    private val BASE_LITERS_PER_KM = 0.025f // ~40 km/L
    private val HARSH_ACCEL_WASTAGE = 0.004f
    private val HARSH_BRAKE_WASTAGE = 0.0025f

    private var totalFuelWasted = 0f
    private var score = 100

    // Balanced weights (reduced impact)
    private val accelWeight = 0.6f
    private val brakingWeight = 1.0f
    private val instabilityWeight = 0.7f

    fun processEvent(event: DrivingEvent): Int {

        val severity = abs(event.severity).coerceIn(0f, 10f)

        // ✅ NORMALIZED PENALTY (IMPORTANT FIX)
        val normalized = severity / 5f  // scale to 0–2 range

        val penalty = when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> (normalized * accelWeight * 10).toInt()
            DrivingEventType.HARSH_BRAKING -> (normalized * brakingWeight * 10).toInt()
            DrivingEventType.UNSTABLE_RIDE -> (normalized * instabilityWeight * 10).toInt()
            DrivingEventType.NORMAL -> 0
        }

        // ✅ FUEL WASTAGE (scaled with severity)
        val wastage = when (event.type) {
            DrivingEventType.HARSH_ACCELERATION -> normalized * HARSH_ACCEL_WASTAGE
            DrivingEventType.HARSH_BRAKING -> normalized * HARSH_BRAKE_WASTAGE
            else -> 0f
        }

        totalFuelWasted += wastage

        // ✅ UPDATE SCORE (controlled drop)
        score -= penalty
        if (score < 0) score = 0
        if (score > 100) score = 100

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