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
        val isHarshAccel =
            peak > 3.5f &&
                    peak > kotlin.math.abs(min)

        // ==============================
        // 🛑 HARSH BRAKING
        // ==============================
        val isHarshBrake =
            min < -3.5f &&
                    kotlin.math.abs(min) > peak

        // ==============================
        // ⚠️ INSTABILITY (LESS SENSITIVE)
        // ==============================
        val isUnstable =
            std > 3.0f || gyro > 2.0f

        Log.d(
            "ANALYZER_FINAL",
            "spd=$speed peak=$peak min=$min std=$std gyro=$gyro"
        )

        return when {
            isHarshBrake && isClean ->
                DrivingEvent(DrivingEventType.HARSH_BRAKING, kotlin.math.abs(min))

            isHarshAccel && isClean ->
                DrivingEvent(DrivingEventType.HARSH_ACCELERATION, peak)

            isUnstable ->
                DrivingEvent(DrivingEventType.UNSTABLE_RIDE, std)

            else ->
                DrivingEvent(DrivingEventType.NORMAL, 0f)
        }
    }
}