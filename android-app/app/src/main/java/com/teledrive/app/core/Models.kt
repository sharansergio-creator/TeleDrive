package com.teledrive.app.core

data class SensorSample(
    val timestamp: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val heading:Float,
    val speed: Float  // GPS speed at sample time
)

data class FeatureVector(
    val meanForwardAccel: Float,
    val peakForwardAccel: Float,
    val minForwardAccel: Float,
    val stdAccel: Float,
    val meanGyro: Float,
    val peakGyro: Float,
    // Standard deviation of the per-sample gyro magnitude over the window.
    // LOW value (< 0.15) = sustained turning (NORMAL); HIGH value (> 0.22) = erratic jitter (UNSTABLE candidate).
    val gyroStd: Float = 0f,
    // Standard deviation of the raw Z-axis (vertical) acceleration over the window.
    // Road roughness (potholes, cobblestones) shows up primarily here.
    // Data analysis: NORMAL riding azStd at city speeds (8-25 km/h) ≈ 1.5-4.5.
    // Genuine instability would push this significantly higher.
    val azStd: Float = 0f
)

enum class DrivingEventType {
    HARSH_ACCELERATION,
    HARSH_BRAKING,
    UNSTABLE_RIDE,
    NORMAL
}

/** Origin of a detected event — used for display and trip-log analytics. */
enum class DetectionSource {
    RULE,    // Pure rule-based engine
    ML,      // Pure ML model
    HYBRID   // Both engines consulted
}

data class DrivingEvent(
    val type: DrivingEventType,
    val severity: Float,
    /** 0.0–1.0 classifier confidence (1.0 for rule-based, model softmax prob for ML). */
    val confidence: Float = 1.0f,
    val source: DetectionSource = DetectionSource.RULE
)