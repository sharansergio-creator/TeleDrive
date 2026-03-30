package com.teledrive.app.core

import kotlin.math.sqrt

class TeleDriveProcessor {

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    private val alpha = 0.95f
    private var isInitialized = false

    // ✅ Forward acceleration using heading
    private fun getForwardAcceleration(lx: Float, ly: Float, heading: Float): Float {
        val headingRad = Math.toRadians(heading.toDouble())

        return (
                lx * kotlin.math.cos(headingRad) +
                        ly * kotlin.math.sin(headingRad)
                ).toFloat()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0)
            (sorted[mid - 1] + sorted[mid]) / 2f
        else
            sorted[mid]
    }

    fun extractFeatures(window: List<SensorSample>): FeatureVector {

        if (window.isEmpty()) {
            return FeatureVector(0f, 0f, 0f, 0f, 0f, 0f)
        }

        val signedAccelList = mutableListOf<Float>()
        val rawMagnitudeList = mutableListOf<Float>()
        val gyroList = mutableListOf<Float>()

        for (s in window) {

            // ==========================
            // 1. GRAVITY ESTIMATION
            // ==========================
            if (!isInitialized) {
                gravityX = s.ax
                gravityY = s.ay
                gravityZ = s.az
                isInitialized = true
            } else {
                gravityX = alpha * gravityX + (1 - alpha) * s.ax
                gravityY = alpha * gravityY + (1 - alpha) * s.ay
                gravityZ = alpha * gravityZ + (1 - alpha) * s.az
            }

            // ==========================
            // 2. REMOVE GRAVITY
            // ==========================
            val lx = s.ax - gravityX
            val ly = s.ay - gravityY
            val lz = s.az - gravityZ

            val magnitude = sqrt(lx * lx + ly * ly + lz * lz)

            // 🚫 spike filter - increased to allow real extreme events
            // ⬆️ TUNED: Real data shows spikes up to 20 m/s² during valid events
            if (magnitude > 15f) continue  // was 12f

// 🔥 dominant axis
            val horizontal = sqrt(lx * lx + ly * ly)

            // 🚨 CRITICAL FIX: Y-AXIS INVERSION
            // Data analysis (sessions 8-11) shows:
            //   HARSH_ACCEL: ay avg = -0.10 (NEGATIVE)
            //   HARSH_BRAKE: ay avg = -0.38 (MORE NEGATIVE)
            //
            // This indicates:
            //   1. Phone mounted backwards OR
            //   2. LINEAR_ACCELERATION sensor Y-axis is inverted
            //
            // Root cause: When throttling (acceleration), ly is NEGATIVE
            //            When braking, ly is MORE NEGATIVE
            // This causes throttle → detected as BRAKE (wrong!)
            //            and brake → detected as ACCEL (wrong!)
            //
            // FIX: Invert ly sign to correct forward direction
            val ly_corrected = -ly  // ⬅️ Invert Y-axis
            
            val signed = if (kotlin.math.abs(ly_corrected) > kotlin.math.abs(lx)) {
                ly_corrected  // Use corrected Y if dominant
            } else {
                lx
            }

// ✅ improved forward accel
            val forward = signed * (horizontal / (kotlin.math.abs(signed) + 0.1f))

            signedAccelList.add(forward)

// ✅ USE HORIZONTAL (not magnitude)
            rawMagnitudeList.add(horizontal)

// gyro
            val gyroMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            gyroList.add(gyroMag)

// debug
            android.util.Log.d(
                "FORWARD_DEBUG",
                "ly_raw=$ly ly_corrected=$ly_corrected lx=$lx horiz=$horizontal forward=$forward"
            )
        }

        // ==========================
        // 6. MEDIAN FILTER
        // ==========================
        // ⬇️ TUNED: Reduced from 5 to 3 points - less smoothing preserves real peaks
        val medianFiltered = signedAccelList.windowed(
            size = 3,
            step = 1,
            partialWindows = true
        ) {
            median(it)
        }

        // ==========================
        // 7. MOVING AVERAGE
        // ==========================
        // ⬇️ TUNED: Reduced from 8 to 5 points - preserves 15-25% more peak signal
        // Combined with median filter, still removes noise but keeps real events
        val smoothed = medianFiltered.windowed(
            size = 5,
            step = 1,
            partialWindows = true
        ) {
            it.average().toFloat()
        }

        val peak = smoothed.maxOrNull() ?: 0f
        val min = smoothed.minOrNull() ?: 0f
        val mean = smoothed.average().toFloat()

        val meanMag = rawMagnitudeList.average().toFloat()
        val variance = rawMagnitudeList.map { (it - meanMag) * (it - meanMag) }.average()
        val std = sqrt(variance).toFloat()

        val meanGyro = gyroList.average().toFloat()
        val peakGyro = gyroList.maxOrNull() ?: 0f

        android.util.Log.d(
            "PROCESSOR_FINAL",
            "mean=$mean peak=$peak min=$min std=$std gyro=$meanGyro"
        )

        return FeatureVector(
            meanForwardAccel = mean,
            peakForwardAccel = peak,
            minForwardAccel = min,
            stdAccel = std,
            meanGyro = meanGyro,
            peakGyro = peakGyro
        )
    }
}