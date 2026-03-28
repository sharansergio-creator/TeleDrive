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
    private val bufferSize = 5   // ~500ms window (assuming ~100ms sampling)

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

        val accelCount = accelBuffer.count { it > 4.8f }
        val brakeCount = brakeBuffer.count { it < -5.0f }

        val accelSustained = accelCount >= 4   // 60% of window
        val brakeSustained = brakeCount >= 4

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

        Log.i(
            "EVENT_DETECTOR",
            ">>> $type | sev=$severity | speed=$speed | std=$std | gyro=$gyro"
        )

        return DrivingEvent(type, severity)
    }
}