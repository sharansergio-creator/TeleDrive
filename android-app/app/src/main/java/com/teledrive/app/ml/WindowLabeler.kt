package com.teledrive.app.ml

import android.util.Log
import com.teledrive.app.core.DrivingEventType
import com.teledrive.app.core.SensorSample

/**
 * WindowLabeler - Handles window-based labeling for ML training data
 * 
 * This class solves the synchronization problem by:
 * 1. Buffering raw sensor samples with timestamps
 * 2. When RuleBasedAnalyzer detects an event, retroactively labeling the buffered window
 * 3. Maintaining a label hold period to assign event labels to subsequent samples
 * 
 * Key ML Training Concept:
 * For 1D CNN training, we need sequences of raw sensor data labeled with the event
 * that WILL happen (or is happening). This class handles that temporal alignment.
 */
class WindowLabeler {

    companion object {
        private const val TAG = "WindowLabeler"
        private const val DEFAULT_WINDOW_SIZE = 50
        private const val LABEL_HOLD_DURATION_MS = 1000L  // Hold label for 1 second after event
        private const val EVENT_COOLDOWN_MS = 2000L      // Minimum gap between same event labels
    }

    // Configuration
    var windowSize: Int = DEFAULT_WINDOW_SIZE

    // Sample with speed tracking
    data class TimestampedSample(
        val sample: SensorSample,
        val speed: Float,
        val timestamp: Long = sample.timestamp
    )

    // Labeled window ready for logging
    data class LabeledWindow(
        val samples: List<SensorSample>,
        val speeds: List<Float>,
        val label: DrivingEventType,
        val startTime: Long,
        val endTime: Long
    )

    // Buffer for incoming samples
    private val sampleBuffer = ArrayDeque<TimestampedSample>()

    // Label state
    private var activeLabel: DrivingEventType = DrivingEventType.NORMAL
    private var labelStartTime: Long = 0L
    private var lastEventTime: Long = 0L
    private var lastNonNormalEvent: DrivingEventType = DrivingEventType.NORMAL

    // Stats
    private val eventCounts = mutableMapOf<DrivingEventType, Int>()

    /**
     * Add a new sensor sample to the buffer
     */
    fun addSample(sample: SensorSample, speed: Float) {
        sampleBuffer.addLast(TimestampedSample(sample, speed))

        // Trim buffer to prevent memory issues (keep 2x window size)
        while (sampleBuffer.size > windowSize * 2) {
            sampleBuffer.removeFirst()
        }
    }

    /**
     * Called when RuleBasedAnalyzer detects an event
     * Returns a LabeledWindow if we have enough data, null otherwise
     */
    fun onEventDetected(eventType: DrivingEventType): LabeledWindow? {
        val now = System.currentTimeMillis()

        // Update label based on event
        when {
            // New non-NORMAL event
            eventType != DrivingEventType.NORMAL -> {
                // Check cooldown for same event type
                if (eventType != lastNonNormalEvent || (now - lastEventTime) > EVENT_COOLDOWN_MS) {
                    activeLabel = eventType
                    labelStartTime = now
                    lastEventTime = now
                    lastNonNormalEvent = eventType
                    eventCounts[eventType] = (eventCounts[eventType] ?: 0) + 1

                    Log.d(TAG, "Event detected: $eventType (count: ${eventCounts[eventType]})")
                }
            }

            // NORMAL event, but we're in hold period
            (now - labelStartTime) < LABEL_HOLD_DURATION_MS && activeLabel != DrivingEventType.NORMAL -> {
                // Keep the current label (hold period)
            }

            // NORMAL and hold expired
            else -> {
                activeLabel = DrivingEventType.NORMAL
            }
        }

        // Return labeled window if we have enough samples
        return if (sampleBuffer.size >= windowSize) {
            extractLabeledWindow()
        } else {
            null
        }
    }

    /**
     * Extract the current window with its label
     */
    private fun extractLabeledWindow(): LabeledWindow? {
        if (sampleBuffer.size < windowSize) return null

        // Get the last windowSize samples
        val windowSamples = sampleBuffer.toList().takeLast(windowSize)

        return LabeledWindow(
            samples = windowSamples.map { it.sample },
            speeds = windowSamples.map { it.speed },
            label = activeLabel,
            startTime = windowSamples.first().timestamp,
            endTime = windowSamples.last().timestamp
        )
    }

    /**
     * Force extract current window (for periodic logging)
     */
    fun getCurrentWindow(): LabeledWindow? {
        return extractLabeledWindow()
    }

    /**
     * Get current active label
     */
    fun getCurrentLabel(): DrivingEventType = activeLabel

    /**
     * Get statistics
     */
    fun getStats(): Map<DrivingEventType, Int> = eventCounts.toMap()

    /**
     * Reset state
     */
    fun reset() {
        sampleBuffer.clear()
        activeLabel = DrivingEventType.NORMAL
        labelStartTime = 0L
        lastEventTime = 0L
    }
}

