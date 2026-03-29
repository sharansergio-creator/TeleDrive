package com.teledrive.app.analysis

import com.teledrive.app.core.*
import android.content.Context
import com.teledrive.app.ml.ModelHelper
import com.teledrive.app.ml.LabelMapper
import android.util.Log

class MLAnalyzer(private val context: Context) : DrivingAnalyzer{

    private val modelHelper = ModelHelper(context)
    private val labelMapper = LabelMapper(context)

    override fun analyze(features: FeatureVector, speed: Float): DrivingEvent {

        // 🔹 Convert FeatureVector → model input
        val input = Array(1) {
            Array(50) {
                FloatArray(8) { 0f }
            }
        }

        // 🔹 Run model
        val output = modelHelper.predict(input)

        // 🔹 Get predicted index
        val predictedIndex = output.withIndex().maxByOrNull { it.value }?.index ?: 0

        // 🔹 Convert to label
        val label = labelMapper.getLabel(predictedIndex)

        android.util.Log.d("ML_OUTPUT", "Raw: ${output.joinToString()}")
        android.util.Log.d("ML_OUTPUT", "Predicted Index: $predictedIndex")
        android.util.Log.d("ML_OUTPUT", "Label: $label")

        return DrivingEvent(
            type = mapToDrivingEvent(label),
            severity = output[predictedIndex]
        )
    }

    private fun mapToDrivingEvent(label: String): DrivingEventType {
        return when (label) {
            "HARSH_ACCELERATION" -> DrivingEventType.HARSH_ACCELERATION
            "HARSH_BRAKING" -> DrivingEventType.HARSH_BRAKING
            "UNSTABLE_RIDE" -> DrivingEventType.UNSTABLE_RIDE
            else -> DrivingEventType.NORMAL
        }
    }
}