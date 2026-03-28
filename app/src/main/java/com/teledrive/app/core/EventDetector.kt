package com.teledrive.app.core

import android.util.Log
import kotlin.math.abs

class EventDetector {

    private var lastEventTime = 0L
    private val cooldownMs = 1500L

    // 🔥 Sustained detection buffer (IMPORTANT)
    private val accelBuffer = ArrayDeque<Float>()
    private val brakeBuffer = ArrayDeque<Float>()
    private val bufferSize = 5   // ~500ms window (assuming ~100ms sampling)

    fun detectEvent(features: FeatureVector, speedKmH: Float): DrivingEvent {

        val now = System.currentTimeMillis()

        // ==============================
        // 1. SPEED GATE (IMPORTANT)
        // ==============================
        if (speedKmH < 5.0f) {
            clearBuffers()
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        // ==============================
        // 2. COOLDOWN
        // ==============================
        if (now - lastEventTime < cooldownMs) {
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        val accel = features.peakForwardAccel
        val brake = features.minForwardAccel
        val std = features.stdAccel
        val gyro = abs(features.meanGyro)

        // ==============================
        // 3. MOTION VALIDATION (CRITICAL)
        // ==============================
        val isStableMotion = std < 3.5f && gyro < 3.0f

        // ==============================
        // 4. BUFFER UPDATE (SUSTAIN CHECK)
        // ==============================
        push(accelBuffer, accel)
        push(brakeBuffer, brake)

        val accelCount = accelBuffer.count { it > 2.5f }
        val brakeCount = brakeBuffer.count { it < -3.0f }

        val accelSustained = accelCount >= 3   // 60% of window
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
        if (!isStableMotion && (std > 2.2f || gyro > 1.5f)) {
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

        lastEventTime = time

        Log.i(
            "EVENT_DETECTOR",
            ">>> $type | sev=$severity | speed=$speed | std=$std | gyro=$gyro"
        )

        return DrivingEvent(type, severity)
    }
}