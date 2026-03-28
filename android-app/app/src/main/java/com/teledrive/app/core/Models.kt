package com.teledrive.app.core

data class SensorSample(
    val timestamp: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val heading:Float
)

data class FeatureVector(
    val meanForwardAccel: Float,
    val peakForwardAccel: Float,
    val minForwardAccel: Float,
    val stdAccel: Float,
    val meanGyro: Float,
    val peakGyro: Float
)

enum class DrivingEventType {
    HARSH_ACCELERATION,
    HARSH_BRAKING,
    UNSTABLE_RIDE,
    NORMAL
}

data class DrivingEvent(
    val type: DrivingEventType,
    val severity: Float
)