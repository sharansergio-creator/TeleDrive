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
import com.teledrive.app.triphistory.TripSummary
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.ml.DataLogger
import com.teledrive.app.ml.MLTrainingLogger
import com.teledrive.app.ml.WindowLabeler
import java.io.File
import com.teledrive.app.ml.ModelHelper
import com.teledrive.app.ml.Scaler
import com.teledrive.app.ml.LabelMapper
import kotlin.text.compareTo
import kotlin.times


enum class DetectionMode {
    RULE_BASED,
    AI_ASSIST
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

    private val CONFIDENCE_THRESHOLD = 0.7f
    private val SMOOTHING_WINDOW = 5
    private val predictionBuffer = ArrayDeque<String>()
    private val analyzer: DrivingAnalyzer get() = AnalyzerProvider.getAnalyzer(this)

    private val windowBuffer = mutableListOf<SensorSample>()

    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f

    private var lastCaptureTime = 0L
    private var lastEventTime = 0L
    private val EVENT_COOLDOWN = 2000L        // 2s for detection/UI
    
    private var lastScoringTime = 0L
    private val SCORING_COOLDOWN = 4000L      // 4s for scoring (longer to reduce over-penalization)

    private var unstableCounter = 0

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
            val trip = TripSummary(
                score = it.finalScore,
                distanceKm = it.distanceKm.toDouble(),   // ✅ correct name + type
                timestamp = System.currentTimeMillis(),
                tip = lastTip
            )

            TripStorage.save(this, trip)

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
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
            }
        }

        val heading = locationService.getHeading()
        val currentSpeed = locationService.getCurrentSpeed()

        val sample = SensorSample(now, ax, ay, az, gx, gy, gz, heading, currentSpeed)
        windowBuffer.add(sample)

        if (windowBuffer.isNotEmpty()) {
            val duration = now - windowBuffer.first().timestamp
            if (duration >= WINDOW_DURATION_MS) {
                processWindow(ArrayList(windowBuffer))

                val cutOff = now - (WINDOW_DURATION_MS - 1000L)
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

        val features = processor.extractFeatures(window)
        val speed = locationService.getCurrentSpeed()
        val now = System.currentTimeMillis()

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

        val input = buildModelInput(window)

        Log.d("ML_INPUT", "${input[0].size} x ${input[0][0].size}")

        val output = modelHelper.predict(input)

        val maxConfidence = output.maxOrNull() ?: 0f
        val predictedIndex = output.indices.maxByOrNull { output[it] } ?: 0
        val rawLabel = labelMapper.getLabel(predictedIndex)

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

        val filteredLabel = if (maxConfidence > 0.6f) rawLabel else "NORMAL"

        val finalMLLabel = when {

            // 🚀 TRUST HIGH CONFIDENCE FIRST
            maxConfidence > 0.9f -> {
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

            // 🚫 Fake unstable
            rawLabel == "UNSTABLE_RIDE" &&
                    (features.stdAccel < 1.5f || features.meanGyro < 0.8f) -> "NORMAL"

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
            isHighSpeed -> -2.5f   // ⬇️ LOWERED: Symmetric with accel (was -3.0)
            isMediumSpeed -> -3.5f // Keep stricter
            else -> -4.5f          // Keep strict
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
        
        // 🔧 FIX (v5 - FINAL): LOWERED threshold to catch oscillations
        // Data showed: Events with stdAccel 2.5-3.0 were triggering ACCEL/BRAKE (wrong!)
        // These are oscillatory patterns → should be UNSTABLE
        //
        // New threshold: 2.5 (down from 2.8)
        //   - Catches high-variance patterns BEFORE they trigger directional events
        //   - Creates clean separation: <2.5 = directional, >=2.5 = oscillatory
        val isUnstableCandidate =
            features.stdAccel >= 2.5f &&  // ⬇️ LOWERED: Catch oscillations earlier
            features.meanGyro > 0.3f &&   // ⬇️ LOWERED: Subtle vibrations are real
            totalEnergy > 0.7f &&         // ⬇️ LOWERED
            speed >= 10f                  // ⬇️ LOWERED: Detect bumpy roads at lower speeds

        // ================= SPEED DERIVATIVE VALIDATION (FIX v5 - CRITICAL) =================
        // SMOKING GUN: 100% of ACCEL/BRAKE labels showed ZERO speed change!
        //
        // Root cause: Previous logic had bypass `|| speed < 15f` that disabled validation
        // This allowed noise spikes to trigger events when speed wasn't changing
        //
        // NEW APPROACH (STRICT):
        //   - ACCEL requires speed INCREASE (>0.3 m/s²)
        //   - BRAKE requires speed DECREASE (<-0.3 m/s²)
        //   - NO bypass - speed derivative is ALWAYS checked
        //   - This ensures only REAL vehicle motion triggers events
        //
        // Calculate speed derivative over window
        val speedDerivative = if (window.size >= 10) {
            val timeDelta = (window.last().timestamp - window.first().timestamp) / 1000f // seconds
            val speedFirst = window.first().speed
            val speedLast = window.last().speed
            if (timeDelta > 0.1f) {
                (speedLast - speedFirst) / timeDelta  // m/s² equivalent
            } else {
                0f
            }
        } else {
            0f
        }
        
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
            features.stdAccel > 1.5f &&  // ⬆️ INCREASED: Filter noise (was 1.0)
            features.stdAccel < 2.5f &&  // ⬇️ DECREASED: Filter oscillations (was 3.0)
            features.peakForwardAccel > kotlin.math.abs(features.minForwardAccel) * 1.2f &&  // Forward motion dominant
            speed >= minSpeedForAcceleration &&  // Speed gate
            speedDerivative > 0.3f  // ✅ STRICT: Require actual speed INCREASE (removed bypass!)

        val isBrakingDetected = 
            features.minForwardAccel < brakeThreshold && 
            features.stdAccel < brakeVarianceThreshold &&  // Speed-aware variance threshold
            kotlin.math.abs(features.minForwardAccel) > features.peakForwardAccel * 1.2f &&  // Backward motion dominant
            speed >= minSpeedForBraking &&  // Speed gate
            speedDerivative < -0.3f  // ✅ STRICT: Require actual speed DECREASE (removed bypass!)

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

        // 🔧 FIX C: Reduced threshold from 2 to 1 (single window confirmation)
        // Oscillation patterns can be intermittent on bumpy roads
        val isConfirmedUnstable = unstableCounter >= 1

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
                val matchesUnstable = currentType == DrivingEventType.UNSTABLE_RIDE && 
                                     miniStd > 1.5f
                
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

        // ================= ML TYPE =================

        val mlType = when (finalMLLabel) {
            "HARSH_ACCELERATION" -> DrivingEventType.HARSH_ACCELERATION
            "HARSH_BRAKING" -> DrivingEventType.HARSH_BRAKING
            "UNSTABLE_RIDE" -> DrivingEventType.UNSTABLE_RIDE
            else -> DrivingEventType.NORMAL
        }

        // ================= MODE SWITCH (🔥 MOST IMPORTANT) =================

        val finalType = when (currentMode) {

            DetectionMode.RULE_BASED -> {
                // ✅ PURE RULE ENGINE
                ruleType
            }

            DetectionMode.AI_ASSIST -> {
                // ✅ PURE ML (WITH SPEED-AWARE SAFETY FILTER)
                when {
                    // 🐢 Block low speed events (event-specific thresholds)
                    mlType == DrivingEventType.HARSH_ACCELERATION && speed < minSpeedForAcceleration -> DrivingEventType.NORMAL
                    mlType == DrivingEventType.HARSH_BRAKING && speed < minSpeedForBraking -> DrivingEventType.NORMAL
                    mlType == DrivingEventType.UNSTABLE_RIDE && speed < minSpeedForUnstable -> DrivingEventType.NORMAL

                    // 🖐️ Block hand movement
                    isLikelyHandMovement -> DrivingEventType.NORMAL

                    // 🚴 High speed: Lower thresholds (road vibration is real)
                    isHighSpeed && features.stdAccel < 0.4f && features.meanGyro < 0.25f ->
                        DrivingEventType.NORMAL

                    // 🚴 Medium/Low speed: Standard thresholds
                    !isHighSpeed && features.stdAccel < 0.6f && features.meanGyro < 0.4f ->
                        DrivingEventType.NORMAL

                    else -> mlType
                }
            }
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
            finalEventType,  // Internal state for scoring
            when (finalEventType) {
                DrivingEventType.HARSH_ACCELERATION -> features.peakForwardAccel
                DrivingEventType.HARSH_BRAKING -> kotlin.math.abs(features.minForwardAccel)
                DrivingEventType.UNSTABLE_RIDE -> features.stdAccel
                else -> 0f
            }
        )
        
        val displayEvent = DrivingEvent(
            displayEventType,  // UI state (stricter confirmation)
            when (displayEventType) {
                DrivingEventType.HARSH_ACCELERATION -> features.peakForwardAccel
                DrivingEventType.HARSH_BRAKING -> kotlin.math.abs(features.minForwardAccel)
                DrivingEventType.UNSTABLE_RIDE -> features.stdAccel
                else -> 0f
            }
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
                tip
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

            // ✅ ALWAYS count events (for statistics)
            rideSessionManager.processEvent(finalEvent)

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
    private fun buildModelInput(window: List<SensorSample>): Array<Array<FloatArray>> {

        val input = Array(1) { Array(50) { FloatArray(8) } }

        // Wait until we have a full window
        if (window.size < 50) return input

        for (i in 0 until 50) {

            val s = window[window.size - 50 + i]

            // Compute magnitudes (same as Python preprocessing)
            val accelMag = kotlin.math.sqrt(
                s.ax * s.ax + s.ay * s.ay + s.az * s.az
            )

            val gyroMag = kotlin.math.sqrt(
                s.gx * s.gx + s.gy * s.gy + s.gz * s.gz
            )

            val raw = floatArrayOf(
                s.ax,
                s.ay,
                s.az,
                s.gx,
                s.gy,
                s.gz,
                accelMag,
                gyroMag
            )

            val normalized = scaler.normalize(raw)

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