package com.teledrive.app.analysis

import com.teledrive.app.core.*

class MLAnalyzer : DrivingAnalyzer {

    override fun analyze(features: FeatureVector, speed: Float): DrivingEvent {

        // TODO: Replace with ML model later
        return DrivingEvent(DrivingEventType.NORMAL, 0f)
    }
}