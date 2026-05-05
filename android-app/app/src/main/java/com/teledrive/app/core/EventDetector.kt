package com.teledrive.app.core

import android.util.Log
import kotlin.math.abs

class EventDetector {

    private var lastEventTime = 0L
    private val cooldownMs = 1500L

    private var lastType: DrivingEventType = DrivingEventType.NORMAL

    // 🔥 Sustained detection buffer (IMPORTANT)
    private val accelBuffer = ArrayDeque<Float>()
    private val brakeBuffer = ArrayDeque<Float>()
    // Speed history buffer: tracks speedKmH for each window to validate genuine acceleration
    private val speedBuffer = ArrayDeque<Float>()
    private val bufferSize = 5   // 5-window rolling buffer (~5 seconds at 1s/window)

    fun detectEvent(features: FeatureVector, speedKmH: Float): DrivingEvent {

        val now = System.currentTimeMillis()

        // ==============================
        // 1. SPEED GATE (IMPORTANT)
        // ==============================
        if (speedKmH < 15f) {
            clearBuffers()
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        // ==============================
        // 2. COOLDOWN
        // ==============================
        if (now - lastEventTime < cooldownMs && lastType != DrivingEventType.NORMAL) {
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        val accel = features.peakForwardAccel
        val brake = features.minForwardAccel
        val std = features.stdAccel
        val gyro = abs(features.meanGyro)

        // 🚫 IGNORE MICRO NOISE
        if (std < 0.5f && gyro < 0.4f) {
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        // ==============================
        // 3. MOTION VALIDATION (CRITICAL)
        // ==============================
        val isStableMotion = std < 2.0f && gyro < 1.2f

        // ==============================
        // 4. BUFFER UPDATE (SUSTAIN CHECK)
        // ==============================
        push(accelBuffer, accel)
        push(brakeBuffer, brake)
        push(speedBuffer, speedKmH)

        // ⬇️ FIX: Threshold lowered from 4.8 → 2.5 m/s²
        // Root cause: data analysis across 7 ride sessions proves that after
        // median(3)+MA(5) smoothing the peak forward acceleration during real
        // harsh acceleration events reaches only 1.5–4.0 m/s².
        // At 4.8 m/s² only 1–2.9% of acceleration windows were detected (≈0).
        // At 2.5 m/s² detection rises to 12–51% of acceleration windows.
        // Count relaxed from 4 → 3 (60%) to match a 3-second harsh accel event.
        val accelCount = accelBuffer.count { it > 2.5f }

        // ⬇️ FIX: Lowered from -5.0 to -3.2 to match real braking signals
        // Data analysis (ride_session_32) shows real braking produces -3.0 to -4.5 m/s² after smoothing
        // Previous threshold (-5.0) was too strict, causing missed detections at high speed
        val brakeCount = brakeBuffer.count { it < -3.2f }

        // Speed trend guard: confirm the vehicle is genuinely accelerating.
        // GPS speed is ~1 Hz; speedBuffer covers ~5 seconds of readings.
        // A real harsh-acceleration event raises GPS speed by at least 1 km/h
        // over that window. Road vibration / bumps produce no net speed gain,
        // so this gate eliminates vibration-induced false positives.
        val speedTrend = if (speedBuffer.size >= 3)
            speedBuffer.last() - speedBuffer.first() else 0f

        // 3/5 windows (60%) with peak > 2.5 m/s² AND speed is rising
        val accelSustained = accelCount >= 3 && speedTrend > 1.0f

        // ⬇️ FIX: Relaxed from 4/5 (80%) to 3/5 (60%) windows
        // Braking is transient: initial spike (200ms) + sustained decel + suspension oscillation
        // Real data shows even strong braking (-7.6 m/s² peak) only sustains in 7-20% of samples
        // 60% balances detection sensitivity vs false positive filtering
        val brakeSustained = brakeCount >= 3

        // ==============================
        // 🚀 HARSH ACCELERATION
        // ==============================
        if (accelSustained && isStableMotion) {
            return trigger(now, DrivingEventType.HARSH_ACCELERATION, accel, speedKmH, std, gyro)
        }

        // ==============================
        // 🛑 HARSH BRAKING
        // ==============================
        if (brakeSustained && isStableMotion) {
            return trigger(now, DrivingEventType.HARSH_BRAKING, abs(brake), speedKmH, std, gyro)
        }

        // ==============================
        // ⚠️ UNSTABLE RIDE
        // ==============================
        if (!isStableMotion && (std > 1.6f || gyro > 1.0f)) {
            return trigger(now, DrivingEventType.UNSTABLE_RIDE, std, speedKmH, std, gyro)
        }

        return DrivingEvent(DrivingEventType.NORMAL, 0f)
    }


    // ==============================
    // BUFFER HELPERS
    // ==============================
    private fun push(buffer: ArrayDeque<Float>, value: Float) {
        if (buffer.size >= bufferSize) {
            buffer.removeFirst()
        }
        buffer.addLast(value)
    }

    private fun clearBuffers() {
        accelBuffer.clear()
        brakeBuffer.clear()
        speedBuffer.clear()
    }

    // ==============================
    // EVENT TRIGGER
    // ==============================
    private fun trigger(
        time: Long,
        type: DrivingEventType,
        severity: Float,
        speed: Float,
        std: Float,
        gyro: Float
    ): DrivingEvent {

        // 🚫 PREVENT SAME EVENT SPAM
        if (type == lastType && (time - lastEventTime < 2000)) {
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        lastEventTime = time
        lastType = type

        accelBuffer.clear()
        brakeBuffer.clear()
        speedBuffer.clear()

        Log.i(
            "EVENT_DETECTOR",
            ">>> $type | sev=$severity | speed=$speed | std=$std | gyro=$gyro"
        )

        return DrivingEvent(type, severity)
    }
}