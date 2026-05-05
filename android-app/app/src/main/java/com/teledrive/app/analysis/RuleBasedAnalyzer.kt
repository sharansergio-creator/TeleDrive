package com.teledrive.app.analysis

import com.teledrive.app.core.*
import android.util.Log

class RuleBasedAnalyzer : DrivingAnalyzer {

    override fun analyze(features: FeatureVector, speed: Float): DrivingEvent {

        val peak = features.peakForwardAccel
        val min = features.minForwardAccel
        val std = features.stdAccel
        val gyro = kotlin.math.abs(features.meanGyro)

        // ==============================
        // 🚫 SPEED FILTER (STRONGER)
        // ==============================
        if (speed < 15f) {
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        // ==============================
        // 🧠 RELAXED CLEAN CHECK
        // ==============================
        val isClean = std < 2.0f && gyro < 1.2f

        // ==============================
        // 🚀 HARSH ACCELERATION
        // ==============================
        // FIX: Three compounding conditions reduced detection to 9.4%:
        //   1. peak>3.5f too high after double smoothing (only 34% of accel windows pass)
        //   2. peak>|min| fails when noise creates deep negative spikes alongside real +peaks
        //   3. isClean (std<2.0) wrong for acceleration — accel events inherently have higher
        //      horizontal variance than braking (only 55% of accel windows pass)
        // New logic: lower threshold + net-positive mean (sustained, not spike) + gyro-only gate
        val isCleanForAccel = gyro < 1.5f   // relax std requirement — accel windows are noisier by physics
        val isHarshAccel =
            peak > 2.5f &&
                    features.meanForwardAccel > 0.0f  // net positive = real fwd accel, not noise symmetry

        // ==============================
        // 🛑 HARSH BRAKING
        // ==============================
        val isHarshBrake =
            min < -3.5f &&
                    kotlin.math.abs(min) > peak

        // ==============================
        // ⚠️ INSTABILITY (LESS SENSITIVE)
        // ==============================
        // Primary signal: azStd (Z-axis std dev) captures vertical road energy that
        // horizontal std misses entirely on bumpy roads.  Threshold 8.0 is above the
        // normal-riding p75 (≈5.5 at 33 km/h) and catches confirmed unstable windows
        // (azStd ≈ 9.2) while the legacy horizontal-std check is kept as a fallback.
        val isUnstable =
            features.azStd > 8.0f || std > 3.0f || gyro > 2.0f

        Log.d(
            "ANALYZER_FINAL",
            "spd=$speed peak=$peak min=$min std=$std gyro=$gyro"
        )

        return when {
            isHarshBrake && isClean ->
                DrivingEvent(DrivingEventType.HARSH_BRAKING, kotlin.math.abs(min))

            isHarshAccel && isCleanForAccel ->
                DrivingEvent(DrivingEventType.HARSH_ACCELERATION, peak)

            isUnstable ->
                DrivingEvent(DrivingEventType.UNSTABLE_RIDE, std)

            else ->
                DrivingEvent(DrivingEventType.NORMAL, 0f)
        }
    }
}