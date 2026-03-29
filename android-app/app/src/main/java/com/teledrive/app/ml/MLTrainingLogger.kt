package com.teledrive.app.ml

import android.content.Context
import android.util.Log
import com.teledrive.app.core.DrivingEventType
import com.teledrive.app.core.SensorSample
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unified ML Training Data Logger
 * 
 * Logs RAW sensor sequences with synchronized labels for 1D CNN training.
 * 
 * Output CSV Format:
 * timestamp, ax, ay, az, gx, gy, gz, speed, label
 * 
 * Features:
 * - Window-based labeling (assigns same label to all samples in detection window)
 * - Event cooldown to prevent noisy rapid labeling
 * - Label smoothing buffer for stable label assignment
 * - Proper synchronization between sensor data and detected events
 */
class MLTrainingLogger(context: Context) {

    companion object {
        private const val TAG = "MLTrainingLogger"
        private const val WINDOW_SIZE = 50          // Number of samples per window
        private const val LABEL_COOLDOWN_MS = 2000L // Cooldown after non-NORMAL event
        private const val LABEL_HOLD_SAMPLES = 50   // Hold event label for this many samples
    }

    // File setup
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val fileName = "training_${dateFormat.format(Date())}.csv"
    val file = File(context.getExternalFilesDir(null), fileName)

    // Sample buffer for window-based logging
    private val sampleBuffer = ArrayDeque<LabeledSample>()

    // Label state management
    private var currentLabel: DrivingEventType = DrivingEventType.NORMAL
    private var lastEventLabel: DrivingEventType = DrivingEventType.NORMAL
    private var lastEventTime: Long = 0L
    private var samplesWithCurrentLabel: Int = 0

    // For tracking logged data stats
    private var totalSamplesLogged: Long = 0
    private val labelCounts = mutableMapOf<DrivingEventType, Long>()

    init {
        if (!file.exists()) {
            file.writeText("timestamp,ax,ay,az,gx,gy,gz,speed,label\n")
        }
        Log.d(TAG, "MLTrainingLogger initialized: ${file.absolutePath}")
    }

    /**
     * Data class for a labeled sensor sample
     */
    data class LabeledSample(
        val timestamp: Long,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val gx: Float,
        val gy: Float,
        val gz: Float,
        val speed: Float,
        val label: DrivingEventType
    )

    /**
     * Update the current event label from RuleBasedAnalyzer
     * Call this when an event is detected (before logging samples)
     */
    fun updateEventLabel(eventType: DrivingEventType) {
        val now = System.currentTimeMillis()

        when {
            // Non-NORMAL event detected
            eventType != DrivingEventType.NORMAL -> {
                // Apply cooldown: don't switch if we recently had an event
                if (now - lastEventTime > LABEL_COOLDOWN_MS || eventType == lastEventLabel) {
                    currentLabel = eventType
                    lastEventLabel = eventType
                    lastEventTime = now
                    samplesWithCurrentLabel = 0
                    Log.d(TAG, "Event label updated: $eventType")
                }
            }

            // NORMAL detected, but we're still in label hold period
            samplesWithCurrentLabel < LABEL_HOLD_SAMPLES && currentLabel != DrivingEventType.NORMAL -> {
                // Keep the current event label (window-based labeling)
            }

            // NORMAL and hold period expired
            else -> {
                currentLabel = DrivingEventType.NORMAL
            }
        }
    }

    /**
     * Log a single sensor sample with the current synchronized label
     * Call this for each sensor reading in real-time
     */
    fun logSample(
        timestamp: Long,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        speed: Float
    ) {
        val sample = LabeledSample(
            timestamp = timestamp,
            ax = ax, ay = ay, az = az,
            gx = gx, gy = gy, gz = gz,
            speed = speed,
            label = currentLabel
        )

        sampleBuffer.addLast(sample)
        samplesWithCurrentLabel++

        // Batch write when buffer is full
        if (sampleBuffer.size >= WINDOW_SIZE) {
            flushBuffer()
        }
    }

    /**
     * Log an entire window of samples with a specific label
     * Use this for window-based labeling where the whole window gets the detected event label
     */
    fun logWindow(
        window: List<SensorSample>,
        speed: Float,
        eventType: DrivingEventType
    ) {
        if (window.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                val labelInt = eventTypeToInt(eventType)

                for (sample in window) {
                    writer.append(
                        "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "$speed," +
                        "$labelInt\n"
                    )
                    totalSamplesLogged++
                    labelCounts[eventType] = (labelCounts[eventType] ?: 0) + 1
                }
            }

            Log.d(TAG, "Logged window: ${window.size} samples, label=$eventType, speed=$speed")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log window", e)
        }
    }

    /**
     * Log a labeled window (window already contains labels per sample)
     */
    fun logLabeledWindow(
        window: List<SensorSample>,
        speeds: List<Float>,
        label: DrivingEventType
    ) {
        if (window.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                val labelInt = eventTypeToInt(label)

                for (i in window.indices) {
                    val sample = window[i]
                    val speed = speeds.getOrElse(i) { speeds.lastOrNull() ?: 0f }

                    writer.append(
                        "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "$speed," +
                        "$labelInt\n"
                    )
                    totalSamplesLogged++
                    labelCounts[label] = (labelCounts[label] ?: 0) + 1
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log labeled window", e)
        }
    }

    /**
     * Flush buffered samples to file
     */
    private fun flushBuffer() {
        if (sampleBuffer.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                while (sampleBuffer.isNotEmpty()) {
                    val sample = sampleBuffer.removeFirst()
                    val labelInt = eventTypeToInt(sample.label)

                    writer.append(
                        "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "${sample.speed}," +
                        "$labelInt\n"
                    )

                    totalSamplesLogged++
                    labelCounts[sample.label] = (labelCounts[sample.label] ?: 0) + 1
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush buffer", e)
        }
    }

    /**
     * Force flush remaining samples (call on service destroy)
     */
    fun close() {
        flushBuffer()
        Log.d(TAG, "MLTrainingLogger closed. Stats: $totalSamplesLogged samples, labels=$labelCounts")
    }

    /**
     * Convert DrivingEventType to integer label for ML
     * 0 = NORMAL
     * 1 = HARSH_ACCELERATION
     * 2 = HARSH_BRAKING
     * 3 = UNSTABLE_RIDE
     */
    private fun eventTypeToInt(eventType: DrivingEventType): Int {
        return when (eventType) {
            DrivingEventType.NORMAL -> 0
            DrivingEventType.HARSH_ACCELERATION -> 1
            DrivingEventType.HARSH_BRAKING -> 2
            DrivingEventType.UNSTABLE_RIDE -> 3
        }
    }

    /**
     * Get logging statistics
     */
    fun getStats(): String {
        return "Total: $totalSamplesLogged samples | " +
               "NORMAL: ${labelCounts[DrivingEventType.NORMAL] ?: 0} | " +
               "ACCEL: ${labelCounts[DrivingEventType.HARSH_ACCELERATION] ?: 0} | " +
               "BRAKE: ${labelCounts[DrivingEventType.HARSH_BRAKING] ?: 0} | " +
               "UNSTABLE: ${labelCounts[DrivingEventType.UNSTABLE_RIDE] ?: 0}"
    }
}

