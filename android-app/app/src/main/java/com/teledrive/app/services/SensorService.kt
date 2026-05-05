package com.teledrive.app.services

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.Build    
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teledrive.app.core.*
import com.teledrive.app.RideSummaryActivity
import com.teledrive.app.analysis.AnalyzerProvider
import com.teledrive.app.analysis.DrivingAnalyzer
import com.teledrive.app.triphistory.TripEvent
import com.teledrive.app.triphistory.TripSummary
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.ml.DataLogger
import com.teledrive.app.ml.MLTrainingLogger
import com.teledrive.app.ml.WindowLabeler
import java.io.File
import com.teledrive.app.ml.ModelHelper
import com.teledrive.app.ml.Scaler
import com.teledrive.app.ml.LabelMapper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


enum class DetectionMode {
    RULE_BASED,
    ML_MODE,
    HYBRID_MODE
}


class SensorService : Service(), SensorEventListener {

    companion object {
        const val TAG = "TeleDriveSensors"
        const val CHANNEL_ID = "teledrive_foreground"
        const val NOTIFICATION_ID = 1
        private const val WINDOW_DURATION_MS = 1000L
        private const val ML_WINDOW_SIZE = 50  // Fixed window size for ML
        var lastCapturedImagePath: String? = null

        var currentMode: DetectionMode = DetectionMode.RULE_BASED

        /**
         * Training mode flag - when true:
         * - Logs NORMAL samples that would normally be filtered
         * - Uses true event labels (not cooldown-suppressed)
         * - Ensures consistent window sizes for ML
         */
        var ML_TRAINING_MODE = true  // Set to true for data collection rides

        /**
         * Set this flag from the UI thread (e.g., MainActivity.onModeChange) to trigger
         * a full per-mode state reset at the start of the next processWindow() call.
         *
         * Why a flag instead of a direct call: SensorService is a foreground service that
         * is not bound, so its instance methods are not reachable from activities.
         * @Volatile ensures the sensor thread reads the updated value without a lock.
         */
        @Volatile var pendingModeReset = false
    }
   

    private lateinit var sensorManager: SensorManager
    private val processor = TeleDriveProcessor()
    private val ecoScoreEngine = EcoScoreEngine()
    private val rideSessionManager = RideSessionManager()
    private lateinit var locationService: LocationService
    private lateinit var dataLogger: DataLogger
    private lateinit var mlTrainingLogger: MLTrainingLogger
    private lateinit var windowLabeler: WindowLabeler
    private lateinit var modelHelper: ModelHelper
    private lateinit var scaler: Scaler
    private lateinit var labelMapper: LabelMapper

    // ⬇️ FIX: Track GPS speed across windows for reliable acceleration detection.
    // Root cause: speedDerivative = (window.last.speed – window.first.speed) is ALWAYS 0
    // because GPS updates at ~1 Hz while the window is also 1 s — the GPS transition
    // nearly always falls BETWEEN windows, not within one.
    // Solution: accumulate the last 5 GPS speed readings (one per window) so the
    // trend over ~5 seconds can confirm genuine sustained acceleration.
    private val accelSpeedBuffer = ArrayDeque<Float>()   // stores per-window GPS speed
    private val ACCEL_SPEED_BUF_SIZE = 5

    private val CONFIDENCE_THRESHOLD = 0.7f
    private val SMOOTHING_WINDOW = 5
    private val predictionBuffer = ArrayDeque<String>()
    private val analyzer: DrivingAnalyzer get() = AnalyzerProvider.getAnalyzer(this)

    private val windowBuffer = mutableListOf<SensorSample>()

    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f

    // ---- Sampling duplicate guard ----
    // Ensures one row per real accelerometer event; blocks gyro-event writes and
    // catches the rare case where the OS delivers the same accel values twice.
    private var lastWrittenAx = Float.NaN
    private var lastWrittenAy = Float.NaN
    private var lastWrittenAz = Float.NaN
    private var lastWrittenTimestamp = 0L

    private var lastCaptureTime = 0L
    private var lastEventTime = 0L
    private val EVENT_COOLDOWN = 2000L        // 2s for detection/UI
    
    private var lastScoringTime = 0L
    private val SCORING_COOLDOWN = 4000L      // 4s for scoring (longer to reduce over-penalization)
    private var lastUnstableScoredTime = 0L
    private val UNSTABLE_SCORE_COOLDOWN = 3000L  // min 3s between UNSTABLE score/count increments

    private var unstableCounter = 0
    // FIX (Hybrid over-detection): Consecutive high-confidence ML-only UNSTABLE detections.
    // Hybrid CASE 2 fires every window where conf > 0.93, so an isolated single-window bump
    // counts as an event — 4-5x more events than Rule-Based mode (which needs 2 consecutive).
    // This counter mirrors the Rule-Based unstableCounter >= 2 requirement for ML-only path.
    // Resets on any rejection, CASE 1 (both agree), CASE 3 (rule-only), or CASE 4.
    private var hybridMLUnstableStreak = 0
    // Vibration baseline: slow EMA of stdAccel during confirmed NORMAL windows.
    // Adapts to road surface so the threshold stays above the current vibration floor.
    private var vibrationBaselineEMA = 1.2f
    private val VIBRATION_BASELINE_ALPHA = 0.05f  // ~20-window (~20s) half-life
    // DATA-DRIVEN: excess added on top of EMA baseline to set unstable threshold.
    // Reduced from 2.2 → 1.0: the old value combined with coerceAtLeast(4.0) made the
    // threshold unreachable at city speeds (8-25 km/h where NORMAL p90 horizStd = 1.5-2.6).
    private val VIBRATION_BASELINE_EXCESS = 1.0f

    // ---- Event Persistence & State Machine ----
    // Prevents single-window false positives and UI flickering between states.
    // ⬇️ TUNED: Reduced to capture more real events (data shows single-window spikes are valid)
    private val EVENT_CONFIRM_THRESHOLD = 1     // Consecutive event windows required to ENTER event state (was 2)
    private val NORMAL_CONFIRM_THRESHOLD = 2    // Consecutive NORMAL windows required to EXIT event state (was 3)
    private var consecutiveEventCounter = 0
    private var consecutiveNormalCounter = 0
    private var lastDetectedEvent: DrivingEventType = DrivingEventType.NORMAL
    private var currentState: DrivingEventType = DrivingEventType.NORMAL
    private var lastStableState: DrivingEventType = DrivingEventType.NORMAL

    private var lastTip: String? = null

    /**
     * Reset all per-mode detection state when the detection mode changes.
     *
     * Why this is necessary: several counters and buffers are stateful across windows:
     *   - unstableCounter: accumulated in the rule engine, used for confirmed-unstable gate.
     *     If it's at 1 when mode switches to Hybrid, the first window immediately confirms.
     *   - predictionBuffer: ML label smoothing buffer. If filled during Rule-Based mode
     *     (where ML now correctly does NOT run) this is safe; guard on mlNeeded handles it.
     *     But if switching from ML → Hybrid, the buffer may carry labels from ML-only windows.
     *   - hybridMLUnstableStreak: streak counter for CASE-2 Hybrid UNSTABLE. If > 0 when
     *     switching back to Hybrid from ML mode, the next window completes the streak.
     *   - consecutiveEventCounter/consecutiveNormalCounter: state machine counters. Carrying
     *     these across a mode switch causes the state machine to be in a mid-confirmation
     *     state that wasn't earned in the new mode.
     * Called from MainActivity whenever the user changes detection mode.
     */
    fun resetDetectionState() {
        unstableCounter              = 0
        hybridMLUnstableStreak       = 0
        predictionBuffer.clear()
        consecutiveEventCounter      = 0
        consecutiveNormalCounter     = 0
        lastDetectedEvent            = DrivingEventType.NORMAL
        currentState                 = DrivingEventType.NORMAL
        lastStableState              = DrivingEventType.NORMAL
        // Note: vibrationBaselineEMA is intentionally preserved — it reflects the road
        // surface the rider is currently on and should not jump on a mode switch.
        Log.i(TAG, "resetDetectionState: all per-mode counters cleared for mode=${currentMode}")
    }

    // Accumulates TripEvent entries during the current trip session.
    // Populated inside the cooldown-gated scoring block so each unique confirmed
    // event is recorded exactly once, matching the existing score/count logic.
    // Cleared implicitly when the service is recreated for the next trip.
    private val sessionEvents = mutableListOf<TripEvent>()

    private val debugLogs = ArrayList<String>()

    override fun onCreate() {
        super.onCreate()

        locationService = LocationService(this)

        locationService.startTracking(
            onDistanceUpdate = { distance ->
                rideSessionManager.updateDistance(distance)
            },
            onSpeedUpdate = { speed ->
                // Optional: you can log or ignore
                Log.d("SERVICE_SPEED", "speed=$speed")
            }
        )
        rideSessionManager.startRide()
        
        // Capture start location
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val startLat = locationService.getLatitude()
            val startLng = locationService.getLongitude()
            rideSessionManager.setStartLocation(startLat, startLng)
            Log.d("LOCATION_DEBUG", "Start location captured: $startLat, $startLng")
            
            // Pre-load location cache
            com.teledrive.app.location.LocationCacheManager.loadCache(this)
        }, 2000) // Wait 2s for GPS lock
        
        dataLogger = DataLogger(this)
        mlTrainingLogger = MLTrainingLogger(this)
        windowLabeler = WindowLabeler()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        modelHelper = ModelHelper(this)
        scaler = Scaler(this)
        labelMapper = LabelMapper(this)
        labelMapper.debugLabels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val session = rideSessionManager.endRide()

        session?.let {

            Log.d("TRIP_DEBUG",
                "Accel=${it.harshAccelerationCount}, " +
                        "Brake=${it.harshBrakingCount}, " +
                        "Instability=${it.unstableRideCount}"
            )
            
            // Capture end location
            val endLat = locationService.getLatitude()
            val endLng = locationService.getLongitude()
            
            // Get start location from session manager (captured in onCreate)
            val startLoc = rideSessionManager.getStartLocation()
            
            // Resolve location names asynchronously (will use cache if available)
            GlobalScope.launch {
                val startName = if (startLoc.first != 0.0 && startLoc.second != 0.0) {
                    com.teledrive.app.location.LocationCacheManager.resolveLocation(
                        this@SensorService, 
                        startLoc.first, 
                        startLoc.second
                    )
                } else null
                
                val endName = if (endLat != 0.0 && endLng != 0.0) {
                    com.teledrive.app.location.LocationCacheManager.resolveLocation(
                        this@SensorService, 
                        endLat, 
                        endLng
                    )
                } else null
                
                // Compute a post-trip insight from the final session stats.
                // This replaces lastTip (a live-event tip, often "NORMAL" = fake positivity)
                // with a genuine analysis of the complete ride.
                val postTripInsight = com.teledrive.app.intelligence.InsightEngine.generateTripInsight(
                    score         = it.finalScore,
                    accelCount    = it.harshAccelerationCount,
                    brakeCount    = it.harshBrakingCount,
                    unstableCount = it.unstableRideCount,
                    distanceKm    = it.distanceKm.toDouble(),
                    durationMs    = it.rideDuration
                )
                // Save trip with location data and event counts
                val trip = TripSummary(
                    score = it.finalScore,
                    distanceKm = it.distanceKm.toDouble(),
                    timestamp = System.currentTimeMillis(),
                    tip = postTripInsight,
                    startLat = startLoc.first,
                    startLng = startLoc.second,
                    endLat = endLat,
                    endLng = endLng,
                    startLocationName = startName,
                    endLocationName = endName,
                    durationMs = it.rideDuration,
                    accelCount = it.harshAccelerationCount,
                    brakeCount = it.harshBrakingCount,
                    unstableCount = it.unstableRideCount,
                    events = sessionEvents.toList()
                )

                TripStorage.save(this@SensorService, trip)
                TripStorage.saveOverallScore(this@SensorService)

                Log.d("LOCATION_DEBUG", "Trip saved: $startName → $endName")
            }

            val fuelUsed = ecoScoreEngine.getFinalFuelConsumption(it.distanceKm)
            val moneyLost = ecoScoreEngine.getFinancialLoss(105.0f)

            val intent = Intent(this, RideSummaryActivity::class.java).apply {
                putExtra("score", it.finalScore)
                putExtra("distance", it.distanceKm)
                putExtra("fuelUsed", fuelUsed)
                putExtra("tip", lastTip)
                putExtra("moneyLost", moneyLost)
                putExtra("harshAccel", it.harshAccelerationCount)
                putExtra("harshBrake", it.harshBrakingCount)
                putExtra("instability", it.unstableRideCount)
                putExtra("imagePath", lastCapturedImagePath ?: RideSessionManager.lastEventImagePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
Thread.sleep(500)
            startActivity(intent)
            sendSummaryNotification(it.finalScore, it.distanceKm)
        }

        sensorManager.unregisterListener(this)
        try { locationService.stopTracking() } catch (_: Exception) {}
        
        // Close ML training logger and log stats
// Close ML training logger and log comprehensive stats
        try {
            val stats = mlTrainingLogger.getStats()
            Log.d("ML_TRAINING", "═══════════════════════════════════════")
            Log.d("ML_TRAINING", "       TRAINING DATA SUMMARY           ")
            Log.d("ML_TRAINING", "═══════════════════════════════════════")
            Log.d("ML_TRAINING", stats)
            Log.d("ML_TRAINING", "File: ${mlTrainingLogger.file.absolutePath}")
            Log.d("ML_TRAINING", "Training mode was: $ML_TRAINING_MODE")
            Log.d("ML_TRAINING", "═══════════════════════════════════════")
            mlTrainingLogger.close()
        } catch (e: Exception) {
            Log.e("ML_TRAINING", "Error closing logger", e)
        }
        super.onDestroy()
    }

    private fun appendLogToFile(log: String) {
        try {
            val file = File(getExternalFilesDir(null), "ride_logs.csv")

            if (!file.exists()) {
                file.writeText("timestamp,speed,peak,min,std,gyro,event\n")
            }

            file.appendText(log + "\n")

        } catch (e: Exception) {
            Log.e("CSV_ERROR", "Failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                // FIX: gyro only updates cached values; it does NOT produce a sample row.
                // Root cause of ~50% duplicate rows: both sensors fired onSensorChanged
                // and each call unconditionally appended a SensorSample, producing
                // interleaved rows where one half had stale data.
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
                return
            }
            else -> return
        }

        // Use the sensor event's own nanosecond timestamp converted to milliseconds.
        // This is strictly monotonic within the accelerometer stream and avoids the
        // ~10 ms System.currentTimeMillis() resolution that caused shared timestamps.
        val eventTimeMs = event.timestamp / 1_000_000L

        // Duplicate guard: skip if hardware delivered the same accel values again
        // (can happen on some devices when the sensor FIFO is flushed with stale data).
        if (eventTimeMs == lastWrittenTimestamp &&
            ax == lastWrittenAx && ay == lastWrittenAy && az == lastWrittenAz) {
            return
        }
        lastWrittenTimestamp = eventTimeMs
        lastWrittenAx = ax; lastWrittenAy = ay; lastWrittenAz = az

        Log.d("SAMPLING", "timestamp=$eventTimeMs, ax=$ax, ay=$ay, az=$az")

        val heading = locationService.getHeading()
        val currentSpeed = locationService.getCurrentSpeed()

        val sample = SensorSample(eventTimeMs, ax, ay, az, gx, gy, gz, heading, currentSpeed)
        windowBuffer.add(sample)

        if (windowBuffer.isNotEmpty()) {
            val duration = eventTimeMs - windowBuffer.first().timestamp
            if (duration >= WINDOW_DURATION_MS) {
                processWindow(ArrayList(windowBuffer))

                val cutOff = eventTimeMs - (WINDOW_DURATION_MS - 1000L)
                windowBuffer.removeAll { it.timestamp < cutOff }
            }
        }
    }

    /**
     * Unified training data logger
     * 
     * Logs the EXACT window that was analyzed with the EXACT label determined.
     * This ensures perfect synchronization between sensor data and labels.
     * 
     * Output: timestamp, ax, ay, az, gx, gy, gz, speed, label
     */
    private fun logTrainingWindow(
        window: List<SensorSample>,
        eventType: DrivingEventType,
        speed: Float
    ) {
        if (!ML_TRAINING_MODE) return
        if (window.isEmpty()) return

        val windowToLog = if (window.size < ML_WINDOW_SIZE) {
            Log.d(TAG, "Padding window: ${window.size} → $ML_WINDOW_SIZE")

            val padded = window.toMutableList()
            while (padded.size < ML_WINDOW_SIZE) {
                padded.add(padded.last())
            }
            padded
        } else {
            window.takeLast(ML_WINDOW_SIZE)
        }

        mlTrainingLogger.logWindow(
            window = windowToLog,
            speed = speed,
            eventType = eventType
        )


        if (eventType != DrivingEventType.NORMAL) {
            Log.d(
                "ML_TRAINING",


                "✅ Logged EVENT: ${eventType.name}, samples=${windowToLog.size}"
            )
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processWindow(window: List<SensorSample>) {

        // Consume a mode-switch reset requested from the UI thread.
        // The flag is set by MainActivity.onModeChange via pendingModeReset = true.
        // Checked here (sensor thread) rather than in the setter to avoid cross-thread
        // synchronisation on multiple instance fields.
        if (pendingModeReset) {
            resetDetectionState()
            pendingModeReset = false
        }

        val features = processor.extractFeatures(window)
        val speed = locationService.getCurrentSpeed()
        val now = System.currentTimeMillis()

        // Update multi-window speed buffer for acceleration detection (one entry per window).
        // Computed BEFORE the speed-gate return below so the buffer stays current even at
        // low speed, ensuring a clean trend when the vehicle accelerates past 15 km/h.
        if (accelSpeedBuffer.size >= ACCEL_SPEED_BUF_SIZE) accelSpeedBuffer.removeFirst()
        accelSpeedBuffer.addLast(speed)
        // accelSpeedTrend: km/h gained over the last ~5 seconds (>=3 samples required)
        val accelSpeedTrend = if (accelSpeedBuffer.size >= 3)
            accelSpeedBuffer.last() - accelSpeedBuffer.first() else 0f

        // Feed raw sensor samples to MLAnalyzer when ML is active
        if (AnalyzerProvider.useML) {
            val mlAnalyzer = analyzer as? com.teledrive.app.analysis.MLAnalyzer
            window.forEach { sample ->
                mlAnalyzer?.addSensorSample(sample)
            }
            Log.d("ML_PIPELINE", "Fed ${window.size} samples to MLAnalyzer")
        }

        if (speed < 5f) {
            // Only log NORMAL for ML, skip events
            if (ML_TRAINING_MODE && window.size >= ML_WINDOW_SIZE) {
                logTrainingWindow(window, DrivingEventType.NORMAL, speed)
            }
            return
        }

        // 🔥 ENERGY (used for ML filtering only)
        // ⬇️ TUNED: Reduced from 1.0 to 0.7 based on real data analysis
        // Real events show energy as low as 1.1-1.3, this ensures we don't miss them
        val totalEnergy = (features.stdAccel * 1.2f) + features.meanGyro

        if (totalEnergy < 0.7f) {
            // In training mode: still log these as NORMAL samples
            // These are valuable for teaching the model what "not moving" looks like
            if (ML_TRAINING_MODE && window.size >= ML_WINDOW_SIZE) {
                logTrainingWindow(window, DrivingEventType.NORMAL, speed)
                Log.d("ML_TRAINING", "📝 Logged low-energy NORMAL window")
            }
            return
        }

        // ================= SPEED-AWARE THRESHOLDS =================
        // 🚴 Bike detection needs different thresholds at different speeds
        val isLowSpeed = speed < 15f      // Below 15 km/h = slow cycling or stationary
        val isMediumSpeed = speed in 15f..30f
        val isHighSpeed = speed > 30f     // Fast cycling

        // 🔧 FIX A: LOW-SPEED FILTER (ENHANCED)
        // Separate thresholds for different event types
        val minSpeedForAcceleration = 15f  // Acceleration requires meaningful forward motion
        val minSpeedForBraking = 12f       // Braking can occur at slightly lower speed
        val minSpeedForUnstable = 8f       // Unstable can occur at any riding speed
        val minEnergyForEvents =
            if (isHighSpeed) 1.5f else 2.5f  // High speed needs less energy threshold

        // ================= ML PREDICTION =================
        // Guard: only run TFLite inference when the current mode actually uses ML.
        // Previously this block ran unconditionally, wasting ~10–20 ms per window in
        // Rule-Based mode and — more critically — mutating predictionBuffer and
        // maxConfidence/rawLabel state that persists into Hybrid/ML modes when the
        // user switches mid-ride.
        val mlNeeded = currentMode != DetectionMode.RULE_BASED

        val input = if (mlNeeded) buildModelInput(window)
                    else Array(1) { Array(50) { FloatArray(9) } }  // empty placeholder

        if (mlNeeded) {
            Log.d("ML_INPUT", "${input[0].size} x ${input[0][0].size}")  // 50 x 9
            Log.d("ML_PIPELINE", "🧠 Running ML inference...")
        }

        val output = if (mlNeeded) modelHelper.predict(input)
                     else FloatArray(4) { 0f }  // NORMAL defaults when ML not needed

        val maxConfidence = output.maxOrNull() ?: 0f
        val predictedIndex = output.indices.maxByOrNull { output[it] } ?: 0
        val rawLabel = if (mlNeeded) labelMapper.getLabel(predictedIndex) else "NORMAL"
        
        // ✅ ML_OUTPUT logs for debugging (matching MLAnalyzer format)
        if (mlNeeded) {
            Log.d("ML_OUTPUT", "Prediction: ${output.joinToString { "%.3f".format(it) }}")
            Log.d("ML_OUTPUT", "Predicted Index: $predictedIndex")
            Log.d("ML_OUTPUT", "Label: $rawLabel")
            Log.d("ML_OUTPUT", "Confidence: %.2f%%".format(maxConfidence * 100))
        }

        // ================= ML FILTERING =================

        // 🚴 IMPROVED: Speed-based movement detection
        // Old: speed > 5f triggered too easily
        // New: Require meaningful cycling speed OR significant sustained energy
        val isActuallyMoving = totalEnergy > minEnergyForEvents

        // 🖐️ IMPROVED: Hand movement filter
        // High gyro + low speed = likely phone being handled, not bike event
        val isLikelyHandMovement = features.meanGyro > 2.2f && speed < 8f

        // 🚴 IMPROVED: Speed-scaled movement detection
        // At high speed, smaller sensor values indicate real events
        val isRealMovement = when {
            isHighSpeed -> features.stdAccel > 0.7f || features.meanGyro > 0.4f
            isMediumSpeed -> features.stdAccel > 1.0f || features.meanGyro > 0.6f
            else -> features.stdAccel > 1.5f || features.meanGyro > 0.8f  // ⬆️ Stricter at low speed
        }

        val filteredLabel = if (mlNeeded && maxConfidence > 0.6f) rawLabel else "NORMAL"

        val finalMLLabel = when {

            // 🚀 TRUST HIGH CONFIDENCE FIRST — but NOT for UNSTABLE_RIDE.
            // FIX (Issue 2): The model scores ≥ 0.9 confidence on bumpy roads because it
            // learned from physics-filtered UNSTABLE samples that share high-jerk signatures
            // with normal bumpy riding. Bypassing the physics guard for UNSTABLE at 0.9
            // confidence was the single largest source of false positives.
            // ACCEL/BRAKE are safe to bypass: their physics (speed trend, direction dominance)
            // are complementary and rarely produce high confidence on noise.
            maxConfidence > 0.9f && rawLabel != "UNSTABLE_RIDE" -> {
                predictionBuffer.clear()
                rawLabel
            }

            // 🖐️ Block obvious hand movement
            isLikelyHandMovement -> "NORMAL"

            // 🚫 Not moving
            !isActuallyMoving -> "NORMAL"

            // 🐢 Too slow (but allow strong ML)
            speed < minSpeedForUnstable && maxConfidence < 0.8f -> "NORMAL"

            // 🚫 Weak movement ONLY if confidence is low
            !isRealMovement && maxConfidence < 0.85f -> "NORMAL"

            // 🚫 Fake unstable — physics guard for ML UNSTABLE predictions.
            // DATA-DRIVEN FIX (Issue 2): The old azStd < 1.5 filter was calibrated for smooth
            // roads, but ALL attached real-ride sessions have azStd 5-8 m/s² per 50-window.
            // On those roads azStd < 1.5 NEVER fires (triggers on 0-2% of windows) — dead code.
            //
            // New 4-condition compound filter:
            //   A. stdAccel < 1.5:  horizontal variance too low → not genuinely unstable
            //   B. meanGyro < 0.5 AND gyroStd < 0.18: quiet gyro, no jitter → smooth motion
            //   C. azStd < 5.0: little-to-moderate vertical bounce → normal bumpy road, not instability.
            //      DATA FIX: raised from 3.0 → 5.0.  Simulation on 125 normal windows at 33 km/h
            //      showed 16.8% passing through the old 3.0 threshold (the 3.0–5.0 range was
            //      unguarded).  Normal riding p75 azStd ≈ 5.5; genuine instability azStd ≥ 9.
            //      Threshold 5.0 blocks the false-positive gap without impacting real events.
            //   D. azStd > 4.0 AND stdAccel < 2.5 AND gyroStd < 0.25:
            //      VERTICAL DOMINANT pattern — big bounce (azStd > 4.0) but little lateral
            //      variance and little gyro jitter. This is a bumpy road, NOT rider instability.
            //      Real instability shows azStd high AND horizontal std high AND gyro jitter high.
            //      Evidence: 100% of false-positive windows in attached data match this profile.
            rawLabel == "UNSTABLE_RIDE" &&
                    (features.stdAccel < 1.5f ||
                     (features.meanGyro < 0.5f && features.gyroStd < 0.18f) ||
                     features.azStd < 5.0f ||           // extended from 3.0 → 5.0 (closes 3.0–5.0 false-positive gap)
                     (features.azStd > 4.0f && features.stdAccel < 2.5f && features.gyroStd < 0.35f)) -> { // FIX2: gyroStd 0.25→0.35 (road noise p90=0.344 slipped through)
                predictionBuffer.clear()  // FIX1: CRITICAL — clear buffer on physics suppression.
                // Without this, correctly-blocked UNSTABLE predictions (conf 0.6-0.85) silently
                // accumulate in the buffer. When one window later crosses 0.93, the buffer
                // majority is UNSTABLE and it fires even though each individual window was rejected.
                // This was the buffer-priming mechanism causing Hybrid false-positive chains.
                "NORMAL"
            }

            // 🧠 Reset
            filteredLabel == "NORMAL" -> {
                predictionBuffer.clear()
                "NORMAL"
            }

            // 🧠 Buffer smoothing
            else -> {
                predictionBuffer.add(filteredLabel)

                if (predictionBuffer.size > 5) {
                    predictionBuffer.removeFirst()
                }

                predictionBuffer
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key ?: "NORMAL"
            }
        }

        // ================= DEBUG =================

        if (mlNeeded) {
            Log.d(
                "ML_DEBUG",
                """
Raw = ${output.joinToString()}
Confidence = $maxConfidence
RawLabel = $rawLabel
FinalML = $finalMLLabel
std=${features.stdAccel} gyro=${features.meanGyro} speed=$speed
Buffer = ${predictionBuffer.joinToString()}
""".trimIndent()
            )
        }

        // ================= RULE ENGINE (SPEED-AWARE) =================

        // 🚴 Dynamic thresholds based on speed
        // ⬆️ DATA-DRIVEN FIX (v3): Adjusted high-speed thresholds after analyzing sessions 17,20,24
        // Previous v2 values (3.0-4.5) were calibrated for LOW-speed false positive reduction
        // But data shows at HIGH SPEED, real events have LOWER peak magnitudes:
        //   - High-speed ACCEL: peak avg=5.97, min=3.55 (current 3.0 threshold catches 100%)
        //   - High-speed BRAKE: min avg=-5.08, min=-7.37 (current -3.0 threshold catches 87.5%)
        //   - BUT: Many events are misclassified as UNSTABLE due to variance
        //
        // New strategy: Lower high-speed thresholds to 2.5 (from 3.0)
        // This ensures we catch REAL events before unstable check interferes
        // Keep medium/low thresholds higher to prevent false positives
        val accelThreshold = when {
            isHighSpeed -> 2.5f    // ⬇️ LOWERED: Real high-speed events show peaks 3.5-9.0, avg=5.97
            isMediumSpeed -> 3.5f  // Keep stricter - filters normal riding oscillations  
            else -> 4.5f           // Keep strict at low speed to avoid false triggers
        }

        val brakeThreshold = when {
            isHighSpeed -> -2.0f   // ⬇️ FIX: -2.5→-2.0; data shows GT brake min avg=-2.08 after smoothing
            isMediumSpeed -> -3.0f // ⬇️ FIX: -3.5→-3.0; most braking occurs in medium-speed range
            else -> -4.0f          // ⬇️ FIX: -4.5→-4.0; low-speed braking still requires clear signal
        }

        // ⬇️ DATA-DRIVEN FIX (v5 - FINAL): Complete rebalancing based on 93,600 samples
        // Previous variance thresholds allowed noise + oscillations to trigger accel/brake
        //
        // New approach: TIGHT variance window for directional events
        //   - stdAccel 1.5-2.5 = sustained directional motion (ACCEL/BRAKE)
        //   - stdAccel < 1.5 = noise (too weak)
        //   - stdAccel > 2.5 = oscillation (should be UNSTABLE)
        //
        // This creates CLEAR separation between event types
        val instabilityThreshold = when {
            isHighSpeed -> 1.0f    // Used for reference only
            isMediumSpeed -> 1.0f
            else -> 1.3f
        }

        // ================= UNSTABLE DETECTION (COUNTER-BASED) =================
        // Detect continuous vibration, not spikes
        // Must NOT override acceleration/braking events

        // DATA-DRIVEN CALIBRATION (from analysis of 6,500 NORMAL-labeled samples + attached dataset audit):
        //
        // Speed-stratified NORMAL horizStd percentiles (attached dataset, 621 windows):
        //   slow  (<15 km/h): p90=1.51  → floor 1.8  (unchanged)
        //   medium(15-30):    p90=1.87  → floor 2.0  (LOWERED from 2.5; was missing 5.5%→7.4%)
        //   fast  (>=30):     p90=3.43  → floor 3.0  (LOWERED from 4.5; was catching only 3.8%→16%)
        //
        // FIX (Issue 1): Previous floor of 4.5 at high speed was unreachable:
        //   - Real data std_accel p90=3.4 at high speed, p99=8.9
        //   - Floor 4.5 → only 3.8% of windows qualified → counter NEVER reached 3
        //   - Floor 3.0 → 16% qualify → counter can reach 2 on genuinely bumpy stretches
        val unstableFloor = when {
            speed < 15f -> 1.8f   // NORMAL p90=1.51; just above to allow city pothole detection
            speed < 30f -> 2.0f   // LOWERED from 2.5: real NORMAL p90=1.87, floor 2.0 is appropriate
            else        -> 3.0f   // LOWERED from 4.5: real p90=3.43; 4.5 was beyond reach
        }
        val adaptiveUnstableThreshold = (vibrationBaselineEMA + VIBRATION_BASELINE_EXCESS)
            .coerceAtLeast(unstableFloor)

        // REMOVED: meanGyro > 0.58f gate
        //   Root cause: NORMAL slow-speed meanGyro p75 = 0.58. This gate blocked ~50% of all
        //   valid windows. Genuine instability doesn't necessarily have high mean gyro;
        //   gyroSTD (jitter) is the correct discriminator.
        //
        // FIX (Issue 1): gyroStd threshold lowered 0.22 → 0.15
        //   Attached dataset analysis: gyroStd median=0.16, mean=0.21.
        //   At threshold 0.22 (above mean), 69% of all windows were blocked.
        //   At threshold 0.15 (just below median), ~50% of windows pass — balanced.
        //   Sustained turns still produce gyroStd < 0.10; genuine jitter is > 0.15.
        //
        // CHANGED: totalEnergy > 1.8 → 1.2 (lower barrier for detection at slow speed)
        //
        // CHANGED: speed >= 10f → speed >= minSpeedForUnstable (8f)
        //   Instability can and does occur at 8-10 km/h on rough city roads.
        val isUnstableCandidate =
            features.stdAccel >= adaptiveUnstableThreshold &&
            features.gyroStd > 0.15f &&          // FIX: lowered from 0.22; real median=0.16, was blocking 69% of windows
            totalEnergy > 1.2f &&                // minimum motion (instability → real forces)
            speed >= minSpeedForUnstable         // 8f; was hardcoded 10f

        // ================= SPEED DERIVATIVE VALIDATION =================
        // Unit: km/h per second  (GPS speed is km/h; timeDelta is seconds)
        // Threshold ±2.5 km/h/s ≈ 0.7 m/s² — this is the "harsh" boundary.
        // A hard brake from 50→40 km/h in 4 s = -2.5 km/h/s — meets threshold.
        // Gentle coasting 30→29 km/h in 1 s = -1 km/h/s — correctly filtered out.
        // Previously 0.3 km/h/s (0.08 m/s²) accepted any trivial deceleration.
        val speedDerivative = if (window.size >= 10) {
            val timeDelta = (window.last().timestamp - window.first().timestamp) / 1000f // ms→s
            val speedFirst = window.first().speed   // km/h
            val speedLast  = window.last().speed    // km/h
            if (timeDelta > 0.1f) (speedLast - speedFirst) / timeDelta else 0f
        } else 0f

        // ─── PHYSICS DEBUG LOG (always on — used for post-ride axis validation) ───
        // Tag: PHYSICS  Format: acc=<forwardAcc> speed=<kmh> delta=<km/h/s>
        // forwardAcc: heading-projected linear acceleration (or ax fallback if no GPS)
        // Braking VALID when: forwardAcc < -2.5  AND  delta < -0.3
        // Accel  VALID when:  forwardAcc >  2.5  AND  delta >  0.3
        Log.d("PHYSICS",
            "acc=${"%.3f".format(features.meanForwardAccel)} " +
            "peak=${"%.3f".format(features.peakForwardAccel)} " +
            "min=${"%.3f".format(features.minForwardAccel)} " +
            "speed=${"%.1f".format(speed)} " +
            "delta=${"%.3f".format(speedDerivative)} " +
            "std=${"%.3f".format(features.stdAccel)} " +
            "heading=${window.lastOrNull()?.let { locationService.getHeading() } ?: -1f}"
        )

        Log.d("SPEED_VALIDATION",
            "speedDeriv=$speedDerivative, peakAccel=${features.peakForwardAccel}, " +
            "minAccel=${features.minForwardAccel}")

        // Check acceleration/braking conditions FIRST (they have priority)
        // ⬇️ CRITICAL FIX v2: Added magnitude comparison to distinguish accel from brake
        // Session 4 data showed 51.8% of "accel" samples had negative dominant axis (misclassified braking!)
        // Root cause: peak > threshold doesn't check if forward motion is DOMINANT
        //
        // New logic: Require peak/min magnitude comparison (20% margin)
        //   - Acceleration: peak must be 20% stronger than |min| (forward dominant)
        //   - Braking: |min| must be 20% stronger than peak (backward dominant)
        //
        // 🔧 FIX (v5 - FINAL): STRICT variance window + MANDATORY speed validation
        // Data analysis (93,600 samples) shows:
        //   - 100% of events had NO speed change → detecting noise!
        //   - Variance range 1.5-2.5 = real directional motion
        //   - Variance > 2.5 = oscillation (should be UNSTABLE)
        //
        // Solution:
        //   1. Tighter variance window (1.5-2.5 instead of 1.0-3.0)
        //   2. MANDATORY speed increase/decrease (NO bypass!)
        //   3. Higher thresholds (filter noise)
        val brakeVarianceThreshold = when {
            isHighSpeed -> 2.2f    // Keep relaxed for high-speed
            isMediumSpeed -> 1.8f  // Keep current
            else -> 1.5f           // Keep stricter at low speed
        }
        
        val isAccelerationDetected =
            features.peakForwardAccel > accelThreshold &&
            features.stdAccel > 1.0f &&                                          // ⬇️ FIX: lowered from 1.5 (smooth hard acceleration has lower variance)
            features.peakForwardAccel > kotlin.math.abs(features.minForwardAccel) * 1.2f &&
            speed >= minSpeedForAcceleration &&
            accelSpeedTrend > 1.0f &&  // ⬇️ FIX: replaces speedDerivative > 2.5f
            features.azStd < 6.0f      // FIX-BUMP: bumpy roads (azStd ≥ 6 m/s²) must not trigger
                                       // harsh acceleration. Real forward acceleration on any
                                       // reasonable surface shows azStd < 4.  Threshold 6.0 is
                                       // above normal-riding p75 (5.5) and well below the bumpy
                                       // false-positive zone (6–9 m/s²) confirmed by data analysis.
            // Root cause: speedDerivative uses window-internal GPS delta which is ALWAYS 0
            // because GPS updates between windows (~1 Hz) not within them.
            // accelSpeedTrend uses the last 5 window speeds → reliable 5-second trend.

        val isBrakingDetected =
            features.minForwardAccel < brakeThreshold &&
            features.stdAccel < brakeVarianceThreshold &&
            kotlin.math.abs(features.minForwardAccel) > features.peakForwardAccel * 1.2f &&
            speed >= minSpeedForBraking &&
            accelSpeedTrend < -3.0f  // ⬇️ FIX: replaces speedDerivative < -2.5f
            // Root cause: speedDerivative uses window-internal GPS delta which is ALWAYS 0
            // because GPS (~1 Hz) transitions fall BETWEEN 1-second windows, not within them.
            // accelSpeedTrend = last – first of the 5-window speed buffer (~5 seconds of GPS).
            // Stricter than accel gate (+1.0): -3.0 requires ≥3 km/h genuine speed loss,
            // filtering gentle coasting (p50 non-GT trend=+1.8) while catching hard braking
            // (p50 GT trend=−6.3 km/h over 5 windows).

        // Update unstable counter with SMARTER reset logic
        // 🔧 FIX C: Improved unstable counter logic
        // Only reset for STRONG directional events (> 5.0 m/s²) to allow unstable detection
        // during mild oscillations that briefly cross accel/brake thresholds
        when {
            // Reset ONLY for strong acceleration/braking (not mild vibrations)
            (isAccelerationDetected && features.peakForwardAccel > 5.0f) ||
            (isBrakingDetected && kotlin.math.abs(features.minForwardAccel) > 5.0f) -> {
                unstableCounter = 0
            }
            // Increment if unstable candidate (even during mild accel/brake)
            isUnstableCandidate -> {
                unstableCounter++
            }
            // Reset if not a candidate
            else -> {
                unstableCounter = 0
            }
        }

        // Require 2 consecutive windows (~2 seconds) of instability.
        // FIX (Issue 1): Previous value was 3. With the old floor of 4.5, only 3.8% of
        // high-speed windows qualified, making 3 consecutive windows statistically
        // near-impossible (~0.06% chance). With new floor 3.0 (16% qualification rate),
        // 2 consecutive is correct: P(2 consecutive at 16%) ≈ 2.6% — low false positive
        // rate while allowing genuine bumpy stretches to be detected.
        val isConfirmedUnstable = unstableCounter >= 2

        // ================= PERSISTENCE CHECK (FIX D - CRITICAL) =================
        // Prevent single-spike false positives by checking pattern persistence
        // Uses existing windowBuffer (already stores ~1.5 seconds of data)
        // Requires condition to persist over 600-800ms (30-40 samples at 50Hz)
        
        // Helper function to check if recent history supports the current detection
        fun checkPatternPersistence(
            currentType: DrivingEventType,
            lookbackSamples: Int = 35  // ~0.7 seconds at 50Hz
        ): Boolean {
            if (currentType == DrivingEventType.NORMAL) return true
            if (windowBuffer.size < lookbackSamples) return false  // Not enough history yet
            
            // Scan recent samples (excluding current window) for consistent pattern
            val recentSamples = windowBuffer.takeLast(lookbackSamples)
            var matchingPatterns = 0
            
            // Use mini-windows of 5 samples (~100ms) to check for repeated pattern
            for (i in 0 until recentSamples.size step 5) {
                if (i + 5 > recentSamples.size) break
                
                val miniWindow = recentSamples.subList(i, minOf(i + 5, recentSamples.size))
                
                // Calculate simplified features for mini-window
                val axValues = miniWindow.map { it.ax }
                val miniPeak = axValues.maxOrNull() ?: 0f
                val miniMin = axValues.minOrNull() ?: 0f
                val miniStd = if (axValues.size > 1) {
                    val mean = axValues.average().toFloat()
                    kotlin.math.sqrt(axValues.map { (it - mean) * (it - mean) }.average()).toFloat()
                } else 0f
                
                // Check if this mini-window shows same pattern
                val matchesAccel = currentType == DrivingEventType.HARSH_ACCELERATION && 
                                  miniPeak > accelThreshold * 0.7f  // 70% of threshold
                val matchesBrake = currentType == DrivingEventType.HARSH_BRAKING && 
                                  miniMin < brakeThreshold * 0.7f
                // DATA-DRIVEN FIX: old threshold 1.5 was calibrated for the old 4.0 horizStd
                // floor and was blocking all unstable confirmations at city speeds (ax miniStd
                // at 10-25 km/h is typically 0.3-0.8, far below 1.5).
                // New threshold = 50% of the speed-adaptive adaptiveUnstableThreshold.
                // This ensures persistence check scales consistently with detection threshold.
                val matchesUnstable = currentType == DrivingEventType.UNSTABLE_RIDE &&
                                     miniStd > (adaptiveUnstableThreshold * 0.5f).coerceAtLeast(0.7f)
                
                if (matchesAccel || matchesBrake || matchesUnstable) {
                    matchingPatterns++
                }
            }
            
            // ⬇️ DATA-DRIVEN FIX (v3): Relaxed from 40% to 30%
            // Real high-speed events are more transient - strict persistence misses them
            // 30% allows brief but real events while still filtering single-spike noise
            val totalMiniWindows = (recentSamples.size / 5).coerceAtLeast(1)
            val persistenceRatio = matchingPatterns.toFloat() / totalMiniWindows
            
            return persistenceRatio >= 0.3f
        }
        
        // Apply persistence check to current detections
        val hasAccelPersistence = !isAccelerationDetected || checkPatternPersistence(DrivingEventType.HARSH_ACCELERATION)
        val hasBrakePersistence = !isBrakingDetected || checkPatternPersistence(DrivingEventType.HARSH_BRAKING)
        val hasUnstablePersistence = !isConfirmedUnstable || checkPatternPersistence(DrivingEventType.UNSTABLE_RIDE)
        
        // Override detection flags if persistence check fails
        val finalAccelDetected = isAccelerationDetected && hasAccelPersistence
        val finalBrakeDetected = isBrakingDetected && hasBrakePersistence
        val finalUnstableDetected = isConfirmedUnstable && hasUnstablePersistence

        // Debug logging for unstable detection
        Log.d("UNSTABLE_DEBUG",
            "std=${features.stdAccel}, gyro=${features.meanGyro}, energy=$totalEnergy, " +
            "counter=$unstableCounter, candidate=$isUnstableCandidate, confirmed=$isConfirmedUnstable"
        )
        
        // Debug logging for persistence
        Log.d("PERSISTENCE_DEBUG",
            "Accel: detected=$isAccelerationDetected, persist=$hasAccelPersistence, final=$finalAccelDetected | " +
            "Brake: detected=$isBrakingDetected, persist=$hasBrakePersistence, final=$finalBrakeDetected | " +
            "Unstable: detected=$isConfirmedUnstable, persist=$hasUnstablePersistence, final=$finalUnstableDetected"
        )

        // ================= RULE TYPE DETERMINATION =================
        // 🔧 FIX D: PRIORITY ORDER (with persistence validation)
        // Order: Speed check → Acceleration → UNSTABLE → Braking
        // Rationale:
        //   - Oscillation (high stdAccel) → UNSTABLE_RIDE
        //   - Clean directional deceleration (low stdAccel) → HARSH_BRAKING
        //   - This prevents bumpy roads from being misclassified as braking
        // Now uses persistence-checked flags to prevent single-spike false positives
        val ruleType = when {
            // 1. LOW-SPEED FILTER (FIX A) - below minimum speed for any harsh event
            speed < minSpeedForUnstable -> DrivingEventType.NORMAL

            // 2. Acceleration (highest priority - clear forward motion with persistence)
            finalAccelDetected -> DrivingEventType.HARSH_ACCELERATION

            // 3. UNSTABLE (PRIORITY 2 - detect oscillation BEFORE directional braking)
            finalUnstableDetected -> DrivingEventType.UNSTABLE_RIDE

            // 4. Braking (PRIORITY 3 - only if NOT unstable and has persistence)
            finalBrakeDetected -> DrivingEventType.HARSH_BRAKING

            // 5. Normal
            else -> DrivingEventType.NORMAL
        }

        // NOTE: vibrationBaselineEMA update removed from here.
        // Root cause: conditioning on ruleType means the EMA updates on the rule engine's
        // view of NORMAL even when the final detection (finalType, determined after the
        // mode switch below) says UNSTABLE. In ML/Hybrid mode this causes the baseline to
        // drift downward every time the rule says NORMAL but ML reports UNSTABLE, creating
        // a positive feedback loop that progressively lowers the unstable threshold.
        // FIX: update is now performed AFTER the mode switch using finalType.
        // The one-window delay is irrelevant to EMA convergence (~20-window time constant).

        Log.d("UNSTABLE_CAL",
            "stdAccel=${"%.3f".format(features.stdAccel)} " +
            "baseline=${"%.3f".format(vibrationBaselineEMA)} " +
            "threshold=${"%.3f".format(adaptiveUnstableThreshold)} " +
            "gyroStd=${"%.3f".format(features.gyroStd)} " +
            "azStd=${"%.3f".format(features.azStd)} " +
            "candidate=$isUnstableCandidate counter=$unstableCounter confirmed=$isConfirmedUnstable")

        // ================= ML TYPE =================

        val mlType = when (finalMLLabel) {
            "HARSH_ACCELERATION" -> DrivingEventType.HARSH_ACCELERATION
            "HARSH_BRAKING" -> DrivingEventType.HARSH_BRAKING
            "UNSTABLE_RIDE" -> DrivingEventType.UNSTABLE_RIDE
            else -> DrivingEventType.NORMAL
        }

        // ================= MODE SWITCH (3 MODES) =================

        val finalType: DrivingEventType
        val finalConfidence: Float
        val finalSource: DetectionSource

        when (currentMode) {

            DetectionMode.RULE_BASED -> {
                // Pure rule engine — fast, threshold-based
                finalType       = ruleType
                finalConfidence = 1.0f
                finalSource     = DetectionSource.RULE
            }

            DetectionMode.ML_MODE -> {
                // Pure ML — pattern-based with speed-aware safety filter
                val mlSafe = when {
                    mlType == DrivingEventType.HARSH_ACCELERATION && speed < minSpeedForAcceleration -> DrivingEventType.NORMAL
                    mlType == DrivingEventType.HARSH_BRAKING       && speed < minSpeedForBraking      -> DrivingEventType.NORMAL
                    mlType == DrivingEventType.UNSTABLE_RIDE        && speed < minSpeedForUnstable     -> DrivingEventType.NORMAL
                    isLikelyHandMovement                                                              -> DrivingEventType.NORMAL
                    isHighSpeed  && features.stdAccel < 0.4f && features.meanGyro < 0.25f            -> DrivingEventType.NORMAL
                    !isHighSpeed && features.stdAccel < 0.6f && features.meanGyro < 0.40f            -> DrivingEventType.NORMAL
                    else -> mlType
                }
                finalType       = mlSafe
                finalConfidence = if (mlSafe != DrivingEventType.NORMAL) maxConfidence else 0f
                finalSource     = DetectionSource.ML
            }

            DetectionMode.HYBRID_MODE -> {
                // Hybrid: rule-based for speed, ML for pattern recognition
                // Rule result is already physics-validated; ML adds pattern depth.
                when {
                    // ── CASE 1: Both agree on a non-NORMAL event ── HIGH CONFIDENCE
                    ruleType != DrivingEventType.NORMAL && ruleType == mlType -> {
                        hybridMLUnstableStreak = 0  // reset streak — CASE 1 confirms independently
                        finalType       = ruleType
                        finalConfidence = maxOf(maxConfidence, 0.85f) // floor at 85%
                        finalSource     = DetectionSource.HYBRID
                        Log.d("HYBRID", "AGREE: ${ruleType.name} conf=${finalConfidence}")
                    }

                    // ── CASE 2: ML detects, rule does not ── allow only if confidence high enough
                    // DATA-DRIVEN FIX (Issue 2): UNSTABLE over-detection in Hybrid mode.
                    // When rule says NORMAL (common since rule thresholds are physics-based) and
                    // ML says UNSTABLE, the old threshold 0.88 was too low — ML model can produce
                    // 0.88-0.92 confidence on sustained road vibration (jerk_mag is always high
                    // on bumpy roads; model trained on similar jerk patterns for UNSTABLE class).
                    // Evidence: training UNSTABLE jerk p95=773 m/s³; attached-data normal jerk
                    // p95=473 m/s³ — only 38% lower, well within model's learned distribution.
                    // UNSTABLE-specific threshold raised to 0.93 to require stronger certainty.
                    // ACCEL/BRAKE keep 0.80 (physics is complementary there, fewer false positives).
                    ruleType == DrivingEventType.NORMAL && mlType != DrivingEventType.NORMAL -> {
                        val speedOk = when (mlType) {
                            DrivingEventType.HARSH_ACCELERATION -> speed >= minSpeedForAcceleration
                            DrivingEventType.HARSH_BRAKING      -> speed >= minSpeedForBraking
                            DrivingEventType.UNSTABLE_RIDE      -> speed >= minSpeedForUnstable
                            else                                -> true
                        }
                        // Require higher confidence for ML-only UNSTABLE predictions
                        // FIX-UNSTABLE: also require minimum azStd > 5.5 for UNSTABLE.
                        // The model has a spurious speed correlator (training data for the UNSTABLE
                        // class was captured at a single GPS speed, creating a learned speed→UNSTABLE
                        // shortcut). On flat roads at that speed, the model fires confidently even
                        // though azStd is normal (2–3 m/s²). Requiring azStd > 5.5 ensures the
                        // road surface actually has vertical energy before accepting the ML verdict.
                        val azOkForUnstable = mlType != DrivingEventType.UNSTABLE_RIDE || features.azStd > 5.5f
                        val requiredConf = if (mlType == DrivingEventType.UNSTABLE_RIDE) 0.93f else 0.80f
                        if (maxConfidence > requiredConf && speedOk && !isLikelyHandMovement && azOkForUnstable) {
                            if (mlType == DrivingEventType.UNSTABLE_RIDE) {
                                // FIX3: Require 2 consecutive high-confidence ML-only UNSTABLE windows.
                                // Without this, Hybrid fires on every isolated bump (std_a>3.4, az>12,
                                // gyrStd>0.47) with conf>0.93 — giving 4-6 events where Rule-Based
                                // gives 2, because Rule-Based requires unstableCounter>=2 (2 consecutive).
                                // This mirrors that requirement in the ML-only path.
                                hybridMLUnstableStreak++
                                if (hybridMLUnstableStreak >= 2) {
                                    finalType       = DrivingEventType.UNSTABLE_RIDE
                                    finalConfidence = maxConfidence
                                    finalSource     = DetectionSource.HYBRID
                                    hybridMLUnstableStreak = 0  // reset so next burst also needs 2
                                    Log.d("HYBRID", "ML-ONLY UNSTABLE confirmed (streak=2) conf=$maxConfidence")
                                } else {
                                    // First window: warm up streak, hold at NORMAL
                                    finalType       = DrivingEventType.NORMAL
                                    finalConfidence = 0f
                                    finalSource     = DetectionSource.HYBRID
                                    Log.d("HYBRID", "ML-ONLY UNSTABLE streak=${hybridMLUnstableStreak}/2 — waiting for 2nd consecutive")
                                }
                            } else {
                                hybridMLUnstableStreak = 0
                                finalType       = mlType
                                finalConfidence = maxConfidence
                                finalSource     = DetectionSource.HYBRID
                                Log.d("HYBRID", "ML-ONLY: ${mlType.name} conf=${maxConfidence} (req=$requiredConf)")
                            }
                        } else {
                            // Gate rejected (confidence, speed, hand-movement, or azStd check):
                            // clear streak and buffer priming.
                            hybridMLUnstableStreak = 0
                            finalType       = DrivingEventType.NORMAL
                            finalConfidence = 0f
                            finalSource     = DetectionSource.HYBRID
                            // FIX1b: Clear buffer on confidence-gate reject for UNSTABLE.
                            // A window that passed physics but scored 0.6-0.92 still added
                            // UNSTABLE to the buffer. Clearing prevents cascade priming.
                            if (mlType == DrivingEventType.UNSTABLE_RIDE) predictionBuffer.clear()
                            if (!azOkForUnstable) Log.d("HYBRID", "UNSTABLE blocked: azStd=${features.azStd} < 5.5")
                        }
                    }

                    // ── CASE 3: Rule detects, ML does not ──
                    // For ACCEL/BRAKE: trust rule (physics-validated) at low confidence.
                    // For UNSTABLE: ML=NORMAL is a strong signal that it's vibration noise, NOT instability.
                    //   → prefer NORMAL to avoid the root cause of 20-50 false unstable events per ride.
                    ruleType != DrivingEventType.NORMAL && mlType == DrivingEventType.NORMAL -> {
                        hybridMLUnstableStreak = 0  // ML=NORMAL breaks any ML-only streak
                        if (ruleType == DrivingEventType.UNSTABLE_RIDE) {
                            // ML disagrees on unstable: trust ML, suppress rule noise
                            finalType       = DrivingEventType.NORMAL
                            finalConfidence = 0f
                            finalSource     = DetectionSource.HYBRID
                            Log.d("HYBRID", "UNSTABLE suppressed: ML=NORMAL overrides rule vibration noise")
                        } else {
                            // Accel/Brake: rule is physics-validated, allow at low confidence
                            finalType       = ruleType
                            finalConfidence = 0.60f
                            finalSource     = DetectionSource.HYBRID
                            Log.d("HYBRID", "RULE-ONLY (low-conf): ${ruleType.name}")
                        }
                    }

                    // ── CASE 4: Both detect but disagree ── trust rule
                    ruleType != DrivingEventType.NORMAL && mlType != DrivingEventType.NORMAL -> {
                        hybridMLUnstableStreak = 0  // rule wins; reset ML-only streak
                        finalType       = ruleType
                        finalConfidence = 0.70f
                        finalSource     = DetectionSource.HYBRID
                        Log.d("HYBRID", "DISAGREE: rule=${ruleType.name} ml=${mlType.name} -> rule wins")
                    }

                    // ── NORMAL ──
                    else -> {
                        hybridMLUnstableStreak = 0
                        finalType       = DrivingEventType.NORMAL
                        finalConfidence = 0f
                        finalSource     = DetectionSource.HYBRID
                    }
                }
            }
        }

        // ================= VIBRATION BASELINE UPDATE (uses finalType) =================
        // Must run AFTER the mode switch so it uses the actual surfaced detection result,
        // not the intermediate ruleType which may disagree with finalType in ML/Hybrid mode.
        // Clamped to [0.5, 3.0] to prevent degenerate roads disabling detection entirely.
        if (finalType == DrivingEventType.NORMAL && speed >= minSpeedForUnstable && features.stdAccel > 0f) {
            vibrationBaselineEMA = VIBRATION_BASELINE_ALPHA * features.stdAccel +
                (1f - VIBRATION_BASELINE_ALPHA) * vibrationBaselineEMA
            vibrationBaselineEMA = vibrationBaselineEMA.coerceIn(0.5f, 3.0f)
        }

        // ================= COOLDOWN (SIDE EFFECTS ONLY) =================
        // isCooldownActive is used ONLY to gate camera/scoring below.
        // It does NOT suppress event detection or the displayed UI state anymore.
        val isCooldownActive = (now - lastEventTime) < EVENT_COOLDOWN

        // Training label: TRUE detected event — no suppression, written to ML CSV
        val trainingLabel = finalType

        // ================= EVENT PERSISTENCE + STATE MACHINE =================
        // ENTER event state: require EVENT_CONFIRM_THRESHOLD consecutive windows of the same event.
        // EXIT  event state: require NORMAL_CONFIRM_THRESHOLD consecutive NORMAL windows (hysteresis).
        // This eliminates single-window false positives and flickering back to NORMAL.
        when {
            finalType != DrivingEventType.NORMAL -> {
                if (finalType == lastDetectedEvent) {
                    // Same event type keeps accumulating
                    consecutiveEventCounter++
                } else {
                    // New event type detected — restart confirmation from 1
                    consecutiveEventCounter = 1
                    lastDetectedEvent = finalType
                }
                consecutiveNormalCounter = 0
            }
            else -> {
                consecutiveNormalCounter++
                // Only clear the event counter once enough NORMAL windows are observed
                if (consecutiveNormalCounter >= NORMAL_CONFIRM_THRESHOLD) {
                    consecutiveEventCounter = 0
                    lastDetectedEvent = DrivingEventType.NORMAL
                }
            }
        }

        // Event is confirmed only after N consecutive same-type detections
        val isEventConfirmed = finalType != DrivingEventType.NORMAL &&
                consecutiveEventCounter >= EVENT_CONFIRM_THRESHOLD

        // Determine UI state with hysteresis:
        //   - Enter event state only when confirmed
        //   - Hold event state until enough NORMAL windows pass (prevents flicker back)
        val confirmedEventType = when {
            isEventConfirmed -> finalType
            currentState != DrivingEventType.NORMAL &&
                    consecutiveNormalCounter < NORMAL_CONFIRM_THRESHOLD -> currentState // Hold current
            else -> DrivingEventType.NORMAL
        }
        
        // 🔧 FIX v4: SEPARATE UI STATE (reduce flicker)
        // User reports: "Throttle shows HARSH_BRAKING" (brief flash)
        // Root cause: UI updates immediately with confirmedEventType, but state may flip during confirmation
        //
        // Solution: Use stricter threshold for UI display (internal scoring uses confirmedEventType)
        //   - UI state requires +1 extra confirmation window
        //   - Reduces transient incorrect state display by ~90%
        val uiEventType = when {
            finalType != DrivingEventType.NORMAL && 
                    consecutiveEventCounter >= (EVENT_CONFIRM_THRESHOLD + 1) -> finalType
            currentState != DrivingEventType.NORMAL &&
                    consecutiveNormalCounter < (NORMAL_CONFIRM_THRESHOLD + 1) -> currentState
            else -> DrivingEventType.NORMAL
        }

        // Advance the state machine
        lastStableState = currentState
        currentState = confirmedEventType  // Internal state for scoring

        // finalEventType is driven purely by the state machine — NOT by raw cooldown suppression.
        // lastEventTime is updated inside the allowUpdate block below so the very first
        // confirmed event always satisfies the cooldown check and reaches the UI.
        val finalEventType = confirmedEventType  // For scoring/logging
        val displayEventType = uiEventType       // For UI display (reduced flicker)

        // Structured detection-vs-UI log for easy debugging
        Log.d("STATE_MACHINE",
            "DETECTED=${finalType.name} | " +
            "CONFIRMED=$isEventConfirmed | " +
            "COUNTER=$consecutiveEventCounter/$EVENT_CONFIRM_THRESHOLD | " +
            "NORMAL_CTR=$consecutiveNormalCounter/$NORMAL_CONFIRM_THRESHOLD | " +
            "STATE=${currentState.name}"
        )

        if (ML_TRAINING_MODE) {
            Log.d("ML_TRAINING",
                "training=$trainingLabel, ui=$finalEventType, cooldown=$isCooldownActive")
        }



        // ================= EVENT =================

        val finalEvent = DrivingEvent(
            type       = finalEventType,
            severity   = when (finalEventType) {
                DrivingEventType.HARSH_ACCELERATION -> features.peakForwardAccel
                DrivingEventType.HARSH_BRAKING      -> kotlin.math.abs(features.minForwardAccel)
                DrivingEventType.UNSTABLE_RIDE      -> features.stdAccel
                else                                -> 0f
            },
            confidence = if (finalEventType != DrivingEventType.NORMAL) finalConfidence else 0f,
            source     = finalSource
        )

        val displayEvent = DrivingEvent(
            type       = displayEventType,
            severity   = when (displayEventType) {
                DrivingEventType.HARSH_ACCELERATION -> features.peakForwardAccel
                DrivingEventType.HARSH_BRAKING      -> kotlin.math.abs(features.minForwardAccel)
                DrivingEventType.UNSTABLE_RIDE      -> features.stdAccel
                else                                -> 0f
            },
            confidence = if (displayEventType != DrivingEventType.NORMAL) finalConfidence else 0f,
            source     = finalSource
        )

        // ================= CSV LOG =================

        val log = "${System.currentTimeMillis()}," +
                "$speed," +
                "${features.peakForwardAccel}," +
                "${features.minForwardAccel}," +
                "${features.stdAccel}," +
                "${features.meanGyro}," +
                "${finalEvent.type}"

       // appendLogToFile(log)
       // Log.d("CSV_WRITE", log)

        // ================= UI =================

        val allowUpdate = when {
            displayEvent.type == DrivingEventType.NORMAL -> true
            now - lastEventTime > EVENT_COOLDOWN -> true
            else -> false
        }

        if (allowUpdate) {

            val alert = AlertManager.getAlert(displayEvent.type)
            val tip = AlertManager.getTip(displayEvent.type)
            if (!tip.isNullOrBlank()) {
                lastTip = tip
            }

            if (displayEvent.type != DrivingEventType.NORMAL) {
                lastEventTime = now
            }
            Log.d("TIP_DEBUG", "Event=${displayEvent.type} | Tip=$tip | Stored = $lastTip")

            LiveDataBus.listener?.invoke(
                alert ?: displayEvent.type.name,
                speed,
                features.stdAccel,
                tip,
                displayEvent.confidence,
                displayEvent.source.name
            )

            updateNotification(displayEvent.type.name, speed)
        }

        // ================= DEBUG =================

        Log.d(
            "FINAL_PIPELINE",
            "MODE=$currentMode EVENT=${finalEvent.type} spd=$speed std=${features.stdAccel}"
        )

        // ================= SCORE =================

        // Side effects (camera + score) are gated by cooldown to preserve original frequency.
        // State machine ensures UI is stable; cooldown ensures scoring isn't applied every window.
        if (finalEvent.type != DrivingEventType.NORMAL && !isCooldownActive) {

            // 📸 CAPTURE IMAGE
            if (System.currentTimeMillis() - lastCaptureTime > 3000) {

                lastCaptureTime = System.currentTimeMillis()

                val cameraIntent =
                    Intent(this, com.teledrive.app.camera.CameraControllerActivity::class.java)

                cameraIntent.putExtra("event_type", finalEvent.type.name)
                cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                startActivity(cameraIntent)

                Log.d("IMAGE_DEBUG", "Camera triggered for ${finalEvent.type}")
            }

            // Count event and update score.
            // UNSTABLE is gated by a dedicated 3s cooldown so clustered vibration bursts
            // don't collapse the score — detection and UI are NOT affected by this gate.
            val isUnstableScoreAllowed = finalEvent.type != DrivingEventType.UNSTABLE_RIDE ||
                (now - lastUnstableScoredTime) >= UNSTABLE_SCORE_COOLDOWN
            if (isUnstableScoreAllowed) {
                if (finalEvent.type == DrivingEventType.UNSTABLE_RIDE) {
                    lastUnstableScoredTime = now
                }
                rideSessionManager.processEvent(finalEvent)
            } else {
                // Residual UNSTABLE within cooldown: count for session stats, skip score penalty
                rideSessionManager.incrementUnstableCountOnly()
                Log.d("UNSTABLE_CAL", "UNSTABLE gated by score cooldown — count only")
            }

            // ✅ SCORING: Use longer cooldown to prevent over-penalization
            // Events are counted (9 accel, 8 brake), but score updates are throttled
            val allowScoring = (now - lastScoringTime) > SCORING_COOLDOWN
            if (allowScoring) {
                val scoreImpact = ecoScoreEngine.processEvent(finalEvent)
                rideSessionManager.updateScore(scoreImpact)
                lastScoringTime = now
                
                Log.d("SCORE_UPDATE", "Score updated: $scoreImpact (Event: ${finalEvent.type})")
            } else {
                Log.d("SCORE_UPDATE", "Event counted but score not updated (cooldown active)")
            }

            // 📍 CAPTURE EVENT LOCATION
            // GPS lat/lng is already being polled at 1 Hz by LocationService — no extra
            // battery cost. We skip capture only if GPS has no fix yet (both are exactly 0.0).
            val eventLat = locationService.getLatitude()
            val eventLng = locationService.getLongitude()
            if (eventLat != 0.0 || eventLng != 0.0) {
                sessionEvents.add(
                    TripEvent(
                        type      = finalEvent.type.name,
                        latitude  = eventLat,
                        longitude = eventLng,
                        timestamp = now,
                        severity  = finalEvent.severity,
                        speedKmh  = speed
                    )
                )
                Log.d("EVENT_MAP", "📍 Captured: ${finalEvent.type.name} @ ($eventLat, $eventLng) speed=${speed}km/h")
            } else {
                Log.d("EVENT_MAP", "⚠️ Skipped location capture — no GPS fix yet")
            }
        }

// ================= ML TRAINING DATA LOGGING =================
//
// Single unified logging pipeline:
// - Uses the EXACT window that was just analyzed
// - Uses the TRUE training label (before cooldown suppression)
// - Ensures perfect synchronization between data and labels
//
// Output: timestamp, ax, ay, az, gx, gy, gz, speed, label
// Labels: 0=NORMAL, 1=HARSH_ACCELERATION, 2=HARSH_BRAKING, 3=UNSTABLE_RIDE

// Log using unified function with TRUE training label
        logTrainingWindow(window, trainingLabel, speed)

// Keep WindowLabeler update for other purposes (can be removed if unused)
        windowLabeler.onEventDetected(finalEvent.type)

    }
    // ================= ML INPUT BUILDER =================
    // Feature order MUST match train_cnn.py FEAT_COLS exactly:
    //   [ax, ay, az, gx, gy, gz, acc_mag, jerk_mag, speed]  (9 features)
    private fun buildModelInput(window: List<SensorSample>): Array<Array<FloatArray>> {

        val input = Array(1) { Array(50) { FloatArray(9) } }

        // Wait until we have a full window
        if (window.size < 50) return input

        var prevAccMag = 0f

        for (i in 0 until 50) {

            val s = window[window.size - 50 + i]

            val accMag  = kotlin.math.sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)

            // Spike rejection: mirrors TeleDriveProcessor's `if (magnitude > 15f) continue`.
            // Without this, TeleDriveProcessor sees clean feature statistics (spike excluded)
            // while the CNN tensor contains the raw spike in acc_mag/jerk_mag, causing the
            // rule engine and ML to diverge on spiked windows.
            // Sample-and-hold: copy the previous valid row so the tensor stays 50×9.
            if (accMag > 15f) {
                if (i > 0) {
                    input[0][i] = input[0][i - 1].copyOf()
                }
                // i == 0 spike: row stays all-zeros (safe; scaler maps 0 → ≈mean → NORMAL).
                // prevAccMag intentionally NOT updated — next valid jerkMag delta is from
                // the last clean sample, not the spike.
                continue
            }

            // jerk_mag = |delta(acc_mag)| * 50 (matching Python: diff().abs() * 50 Hz)
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
            // FIX-SPEED-CORRELATOR: zero out the speed feature (index 8).
            // Training data for the UNSTABLE class was collected at a single fixed speed
            // (33.3331 km/h, std=0.000), so the scaler learned speed≈33 → UNSTABLE.
            // Zeroing forces pattern-based inference on IMU axes only.
            normalized[8] = 0f

            input[0][i] = normalized
        }

        return input
    }
    // ---------------- NOTIFICATIONS ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride Insights",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(event: String = "NORMAL", speed: Float = 0f, loss: Float = 0f): Notification {
        val intent = Intent(this, com.teledrive.app.LiveTripActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (event == "NORMAL") {
            "Speed: ${speed.toInt()} km/h"
        } else {
            event
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeleDrive Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(event: String, speed: Float) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(event, speed))
    }

    private fun sendSummaryNotification(score: Int, distance: Float) {
        val intent = Intent(this, RideSummaryActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ride Summary")
            .setContentText("Score: $score | ${"%.1f".format(distance)} km")
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(2, notification)
    }
}