package com.teledrive.app.analysis

import com.teledrive.app.core.FeatureVector
import com.teledrive.app.core.DrivingEvent

interface DrivingAnalyzer {
    fun analyze(features: FeatureVector, speed: Float): DrivingEvent
}