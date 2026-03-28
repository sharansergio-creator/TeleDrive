package com.teledrive.app.core

object AlertManager {

    fun getAlert(event: DrivingEventType): String? {
        return when (event) {
            DrivingEventType.HARSH_ACCELERATION ->
                "Harsh Acceleration Detected"

            DrivingEventType.HARSH_BRAKING ->
                "Sudden Braking Detected"

            DrivingEventType.UNSTABLE_RIDE ->
                "Unstable Ride Detected"

            else -> null
        }
    }

    fun getTip(event: DrivingEventType): String? {
        return when (event) {
            DrivingEventType.HARSH_ACCELERATION ->
                "Accelerate smoothly to save fuel"

            DrivingEventType.HARSH_BRAKING ->
                "Maintain distance and brake gradually"

            DrivingEventType.UNSTABLE_RIDE ->
                "Drive steadily and avoid sudden movements"

            DrivingEventType.NORMAL ->
                "Driving smoothly. Keep it up!"  // ✅ ADD THIS

            else ->
                "Drive safely and maintain control" // ✅ fallback
        }
    }
}