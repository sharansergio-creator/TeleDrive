package com.teledrive.app.ml

import android.content.Context
import android.util.Log
import com.teledrive.app.core.DrivingEventType
import com.teledrive.app.core.SensorSample
import java.io.File
import java.io.FileWriter

/**
 * Legacy DataLogger for backward compatibility
 * 
 * NOTE: Consider using MLTrainingLogger instead for new training data collection.
 * MLTrainingLogger provides better window-based labeling and includes speed data.
 * 
 * Output CSV Format: timestamp, ax, ay, az, gx, gy, gz, speed, label
 */
class DataLogger(context: Context) {

    companion object {
        private const val TAG = "DataLogger"
    }

    private val rideFileName = "ride_${System.currentTimeMillis()}.csv"
    val file = File(context.getExternalFilesDir(null), rideFileName)

    // Track current speed (updated by SensorService)
    private var currentSpeed: Float = 0f

    init {
        if (!file.exists()) {
            // Updated header to include speed
            file.writeText("timestamp,ax,ay,az,gx,gy,gz,speed,label\n")
        }
        Log.d(TAG, "DataLogger initialized: ${file.absolutePath}")
    }

    /**
     * Update the current speed value
     */
    fun updateSpeed(speed: Float) {
        currentSpeed = speed
    }

    /**
     * Log a window of samples with a label
     * @param window List of sensor samples
     * @param label Integer label (0=NORMAL, 1=ACCEL, 2=BRAKE, 3=UNSTABLE)
     */
    fun logWindow(window: List<SensorSample>, label: Int) {
        logWindow(window, label, currentSpeed)
    }

    /**
     * Log a window of samples with a label and specific speed
     */
    fun logWindow(window: List<SensorSample>, label: Int, speed: Float) {
        try {
            FileWriter(file, true).use { writer ->
                for (sample in window) {
                    writer.append(
                        "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "$speed," +
                        "$label\n"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log window", e)
        }
    }

    /**
     * Log a window with DrivingEventType
     */
    fun logWindow(window: List<SensorSample>, eventType: DrivingEventType, speed: Float) {
        val label = when (eventType) {
            DrivingEventType.NORMAL -> 0
            DrivingEventType.HARSH_ACCELERATION -> 1
            DrivingEventType.HARSH_BRAKING -> 2
            DrivingEventType.UNSTABLE_RIDE -> 3
        }
        logWindow(window, label, speed)
    }
}