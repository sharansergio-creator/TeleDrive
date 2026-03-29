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
    private val EVENT_COOLDOWN = 2000L

    private var unstableCounter = 0

    // ---- Event Persistence & State Machine ----
    // Prevents single-window false positives and UI flickering between states.
    private val EVENT_CONFIRM_THRESHOLD = 2     // Consecutive event windows required to ENTER event state
    private val NORMAL_CONFIRM_THRESHOLD = 3    // Consecutive NORMAL windows required to EXIT event state
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

        val sample = SensorSample(now, ax, ay, az, gx, gy, gz, heading)
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

        // 🔥 ENERGY (used for ML filtering only)
// 🔥 ENERGY (used for ML filtering only)
        val totalEnergy = (features.stdAccel * 1.2f) + features.meanGyro

        if (totalEnergy < 1.0f) {
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
        speed < 15f      // Below 15 km/h = slow cycling or stationary
        val isMediumSpeed = speed in 15f..30f
        val isHighSpeed = speed > 30f     // Fast cycling

        // Dynamic thresholds based on speed (higher speed = more tolerance)
        val minSpeedForEvents = if (ML_TRAINING_MODE) 0f else 12f       // ⬆️ Raised from 5f - ignore events below 12 km/h
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
            speed < minSpeedForEvents && maxConfidence < 0.8f -> "NORMAL"

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
        val accelThreshold = when {
            isHighSpeed -> 5.5f    // ⬇️ Lower threshold at high speed - easier to detect
            isMediumSpeed -> 6.5f  // Standard
            else -> 8.0f           // ⬆️ Higher threshold at low speed - harder to trigger
        }

        val brakeThreshold = when {
            isHighSpeed -> -5.5f
            isMediumSpeed -> -6.5f
            else -> -8.0f
        }

        val instabilityThreshold = when {
            isHighSpeed -> 1.0f    // ⬇️ Lower at high speed
            isMediumSpeed -> 1.2f  // Standard
            else -> 1.5f           // ⬆️ Much stricter at low speed to avoid hand shake
        }

        // ================= UNSTABLE DETECTION (COUNTER-BASED) =================
        // Detect continuous vibration, not spikes
        // Must NOT override acceleration/braking events
        
        val isUnstableCandidate =
            features.stdAccel in instabilityThreshold..4.0f &&
            totalEnergy > 1.0f &&
            features.meanGyro > 0.5f

        // Check acceleration/braking conditions FIRST (they have priority)
        val isAccelerationDetected = features.peakForwardAccel > accelThreshold && features.stdAccel > 1.5f
        val isBrakingDetected = features.minForwardAccel < brakeThreshold && features.stdAccel > 1.5f

        // Update unstable counter with proper reset logic
        when {
            // Reset counter if acceleration or braking is detected (they take priority)
            isAccelerationDetected || isBrakingDetected -> {
                unstableCounter = 0
            }
            // Increment if unstable candidate
            isUnstableCandidate -> {
                unstableCounter++
            }
            // Reset if not a candidate
            else -> {
                unstableCounter = 0
            }
        }

        // Require 2+ consecutive windows for confirmation (temporal smoothing)
        val isConfirmedUnstable = unstableCounter >= 2

        // Debug logging for unstable detection
        Log.d("UNSTABLE_DEBUG",
            "std=${features.stdAccel}, gyro=${features.meanGyro}, energy=$totalEnergy, " +
            "counter=$unstableCounter, candidate=$isUnstableCandidate, confirmed=$isConfirmedUnstable"
        )

        // ================= RULE TYPE DETERMINATION =================
        // Priority order: speed → acceleration → braking → unstable → normal
        val ruleType = when {
            // 1. Speed check first
            speed < minSpeedForEvents -> DrivingEventType.NORMAL

            // 2. Acceleration (highest priority event)
            isAccelerationDetected -> DrivingEventType.HARSH_ACCELERATION

            // 3. Braking (second priority)
            isBrakingDetected -> DrivingEventType.HARSH_BRAKING

            // 4. Unstable (only if confirmed via counter)
            isConfirmedUnstable -> DrivingEventType.UNSTABLE_RIDE

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
                    // 🐢 Block low speed events
                    speed < minSpeedForEvents -> DrivingEventType.NORMAL

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

        // Advance the state machine
        lastStableState = currentState
        currentState = confirmedEventType

        // finalEventType is driven purely by the state machine — NOT by raw cooldown suppression.
        // lastEventTime is updated inside the allowUpdate block below so the very first
        // confirmed event always satisfies the cooldown check and reaches the UI.
        val finalEventType = confirmedEventType

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
            finalEventType,
            when (finalEventType) {
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
            finalEvent.type == DrivingEventType.NORMAL -> true
            now - lastEventTime > EVENT_COOLDOWN -> true
            else -> false
        }

        if (allowUpdate) {

            val alert = AlertManager.getAlert(finalEvent.type)
            val tip = AlertManager.getTip(finalEvent.type)
            if (!tip.isNullOrBlank()) {
                lastTip = tip
            }

            if (finalEvent.type != DrivingEventType.NORMAL) {
                lastEventTime = now
            }
            Log.d("TIP_DEBUG", "Event=${finalEvent.type} | Tip=$tip | Stored = $lastTip")

            LiveDataBus.listener?.invoke(
                alert ?: finalEvent.type.name,
                speed,
                features.stdAccel,
                tip
            )

            updateNotification(finalEvent.type.name, speed)
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

            val scoreImpact = ecoScoreEngine.processEvent(finalEvent)

            rideSessionManager.processEvent(finalEvent)
            rideSessionManager.updateScore(scoreImpact)
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