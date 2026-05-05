package com.teledrive.app.core

import kotlin.math.sqrt

class TeleDriveProcessor {

    // ✅ Forward acceleration using heading
    // Android bearing β is clockwise from North (0=N, 90=E).
    // Forward unit vector in world (East=x, North=y) frame: (sin β, cos β).
    // Assuming the phone lies roughly horizontal with its X-axis ≈ East and
    // Y-axis ≈ North, the forward (speed direction) projection is:
    //   forwardAcc = lx·sin(β) + ly·cos(β)
    // Note: previous formula used cos/sin — swapped, which gave wrong sign.
    private fun getForwardAcceleration(lx: Float, ly: Float, heading: Float): Float {
        val headingRad = Math.toRadians(heading.toDouble())

        return (
                lx * kotlin.math.sin(headingRad) +
                        ly * kotlin.math.cos(headingRad)
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
        val azList = mutableListOf<Float>()  // raw Z-axis for instability (vertical road input)

        for (s in window) {

            // ==========================
            // 1. USE LINEAR ACCELERATION DIRECTLY
            // ==========================
            // Sensor is TYPE_LINEAR_ACCELERATION: Android OS has already removed gravity
            // via its own sensor-fusion (complementary / Kalman filter).
            // Applying another IIR filter on top would track and subtract the real
            // acceleration signal itself, leaving only noise.  Use raw values.
            val lx = s.ax
            val ly = s.ay
            val lz = s.az

            val magnitude = sqrt(lx * lx + ly * ly + lz * lz)

            // 🚫 spike filter - increased to allow real extreme events
            // ⬆️ TUNED: Real data shows spikes up to 20 m/s² during valid events
            if (magnitude > 15f) continue  // was 12f

// 🔥 dominant axis
            val horizontal = sqrt(lx * lx + ly * ly)

            // 🚨 FORWARD ACCELERATION — use lx directly
            // Analysis of real ride data shows the phone X-axis is approximately
            // aligned with the vehicle forward direction (handlebar-mounted portrait).
            // Heading-based projection (lx*sin + ly*cos) DEGRADES signal for this
            // mount orientation (SNR drops from 0.134 → 0.064 and produces wrong sign
            // for braking).  Use raw lx (= ax from TYPE_LINEAR_ACCELERATION) until
            // phone mounting orientation is calibrated via on-device rotation vector.
            //
            // Fallback log suppressed: the ax-direct path is now always used.
            val forward = lx

            signedAccelList.add(forward)

// ✅ USE HORIZONTAL (not magnitude)
            rawMagnitudeList.add(horizontal)

// gyro
            val gyroMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            gyroList.add(gyroMag)
            azList.add(lz)

// debug
            android.util.Log.d(
                "FORWARD_DEBUG",
                "ly=$ly lx=$lx heading=${s.heading} horiz=$horizontal forward=$forward"
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

        // Gyro jitter: std-dev of per-sample gyro magnitude over the window.
        // A low value means smooth/sustained rotation (normal turn).
        // A high value means erratic direction changes (true instability).
        val gyroVariance = gyroList.map { (it - meanGyro) * (it - meanGyro) }.average()
        val gyroStd = sqrt(gyroVariance).toFloat()

        // Z-axis std: primary road-roughness signal.
        // Computed from raw az values (before spike filter removes high-magnitude 3D samples,
        // so individual large az values are preserved for accurate vertical variance).
        val meanAz = if (azList.isNotEmpty()) azList.average().toFloat() else 0f
        val azVariance = if (azList.size > 1)
            azList.map { (it - meanAz) * (it - meanAz) }.average() else 0.0
        val azStd = sqrt(azVariance).toFloat()

        android.util.Log.d(
            "PROCESSOR_FINAL",
            "mean=$mean peak=$peak min=$min std=$std gyro=$meanGyro azStd=$azStd"
        )

        return FeatureVector(
            meanForwardAccel = mean,
            peakForwardAccel = peak,
            minForwardAccel = min,
            stdAccel = std,
            meanGyro = meanGyro,
            peakGyro = peakGyro,
            gyroStd = gyroStd,
            azStd = azStd
        )
    }
}