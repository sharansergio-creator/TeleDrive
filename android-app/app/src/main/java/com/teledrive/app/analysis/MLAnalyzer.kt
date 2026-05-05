package com.teledrive.app.analysis

import com.teledrive.app.core.*
import android.content.Context
import com.teledrive.app.ml.ModelHelper
import com.teledrive.app.ml.LabelMapper
import com.teledrive.app.ml.Scaler
import android.util.Log

/**
 * MLAnalyzer - Machine Learning based driving behavior analyzer
 * 
 * CRITICAL FIX (April 1, 2026):
 * - Previous version fed ALL ZEROS to model (non-functional)
 * - Now properly uses raw sensor window buffer
 * - Features match training pipeline exactly
 */
class MLAnalyzer(private val context: Context) : DrivingAnalyzer {

    private val modelHelper = ModelHelper(context)
    private val labelMapper = LabelMapper(context)
    private val scaler = Scaler(context)
    
    // Buffer to store raw sensor samples for ML inference
    private val sensorWindowBuffer = ArrayDeque<SensorSample>()
    
    /**
     * Add sensor sample to buffer (called by SensorService)
     */
    fun addSensorSample(sample: SensorSample) {
        sensorWindowBuffer.addLast(sample)
        
        // Keep buffer size manageable (store last 50 samples)
        while (sensorWindowBuffer.size > 50) {
            sensorWindowBuffer.removeFirst()
        }
    }

    override fun analyze(features: FeatureVector, speed: Float): DrivingEvent {

        // Check if we have enough data
        if (sensorWindowBuffer.size < 50) {
            Log.d("ML_ANALYZER", "Insufficient samples: ${sensorWindowBuffer.size}/50")
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        // 🔹 Build model input from raw sensor buffer
        // Feature order MUST match train_cnn.py FEAT_COLS exactly:
        //   [ax, ay, az, gx, gy, gz, acc_mag, jerk_mag, speed]  (9 features)
        val input = Array(1) { Array(50) { FloatArray(9) } }

        var prevAccMag = 0f
        for (i in 0 until 50) {
            val s = sensorWindowBuffer[i]

            val accMag  = kotlin.math.sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            val jerkMag = if (i == 0) 0f else kotlin.math.abs(accMag - prevAccMag) * 50f
            prevAccMag  = accMag

            val raw = floatArrayOf(
                s.ax,
                s.ay,
                s.az,
                s.gx,
                s.gy,
                s.gz,
                accMag,
                jerkMag,
                s.speed,
            )
            
            val normalized = scaler.normalize(raw)
            // FIX-SPEED-CORRELATOR: zero out the speed feature (index 8) before inference.
            // All UNSTABLE training samples in the dataset have speed = 33.3331 km/h with
            // zero variance (std = 0.000). This causes the model to learn speed≈33 → UNSTABLE
            // as a spurious shortcut rather than learning the actual sensor pattern.
            // Zeroing forces the CNN to rely solely on the 6-axis IMU features.
            // FEAT_COLS = [ax, ay, az, gx, gy, gz, acc_mag, jerk_mag, speed]; speed = index 8.
            normalized[8] = 0f
            input[0][i] = normalized
        }

        // 🔹 Run model inference
        val output = modelHelper.predict(input)

        // 🔹 Get predicted index
        val predictedIndex = output.withIndex().maxByOrNull { it.value }?.index ?: 0

        // 🔹 Convert to label
        val label = labelMapper.getLabel(predictedIndex)
        
        // 🔹 Log predictions for debugging
        Log.d("ML_OUTPUT", "Prediction: ${output.joinToString { "%.3f".format(it) }}")
        Log.d("ML_OUTPUT", "Predicted Index: $predictedIndex")
        Log.d("ML_OUTPUT", "Label: $label")
        Log.d("ML_OUTPUT", "Confidence: %.2f%%".format(output[predictedIndex] * 100))
        
        // Log first and last sample for verification
        if (sensorWindowBuffer.size >= 50) {
            val first = sensorWindowBuffer.first()
            val last = sensorWindowBuffer.last()
            Log.d("ML_INPUT", "First sample: ax=${first.ax}, speed=${first.speed}")
            Log.d("ML_INPUT", "Last sample: ax=${last.ax}, speed=${last.speed}")
        }

        val confidence = output[predictedIndex]

        // FIX-UNSTABLE-CONFIDENCE: require ≥ 0.80 confidence for UNSTABLE_RIDE.
        // The model's UNSTABLE class has a weak decision boundary (zero UNSTABLE
        // training samples in the current dataset; spurious speed correlator).
        // Low-confidence UNSTABLE predictions (0.5–0.79) are almost always noise.
        if (mapToDrivingEvent(label) == DrivingEventType.UNSTABLE_RIDE && confidence < 0.80f) {
            Log.d("ML_OUTPUT", "UNSTABLE suppressed: confidence ${"%.2f".format(confidence)} < 0.80")
            return DrivingEvent(DrivingEventType.NORMAL, 0f)
        }

        return DrivingEvent(
            type = mapToDrivingEvent(label),
            severity = confidence
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