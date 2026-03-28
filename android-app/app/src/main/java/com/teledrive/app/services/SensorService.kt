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
import java.io.File
import com.teledrive.app.ml.ModelHelper
import com.teledrive.app.ml.Scaler
import com.teledrive.app.ml.LabelMapper


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
        var lastCapturedImagePath: String? = null

        var currentMode: DetectionMode = DetectionMode.RULE_BASED
    }

    private lateinit var sensorManager: SensorManager
    private val processor = TeleDriveProcessor()
    private val ecoScoreEngine = EcoScoreEngine()
    private val rideSessionManager = RideSessionManager()
    private lateinit var locationService: LocationService
    private lateinit var dataLogger: DataLogger
    private lateinit var modelHelper: ModelHelper
    private lateinit var scaler: Scaler
    private lateinit var labelMapper: LabelMapper

    private val CONFIDENCE_THRESHOLD = 0.7f
    private val SMOOTHING_WINDOW = 5
    private val predictionBuffer = ArrayDeque<String>()
    private val analyzer: DrivingAnalyzer get() = AnalyzerProvider.getAnalyzer()

    private val windowBuffer = mutableListOf<SensorSample>()

    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f

    private var lastCaptureTime = 0L
    private var lastEventTime = 0L
    private val EVENT_COOLDOWN = 2000L

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

        windowBuffer.add(SensorSample(now, ax, ay, az, gx, gy, gz, heading))

        if (windowBuffer.isNotEmpty()) {
            val duration = now - windowBuffer.first().timestamp
            if (duration >= WINDOW_DURATION_MS) {
                processWindow(ArrayList(windowBuffer))

                val cutOff = now - (WINDOW_DURATION_MS - 1000L)
                windowBuffer.removeAll { it.timestamp < cutOff }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processWindow(window: List<SensorSample>) {

        val features = processor.extractFeatures(window)
        val speed = locationService.getCurrentSpeed()
        val now = System.currentTimeMillis()

        // 🔥 ENERGY (used for ML filtering only)
        val totalEnergy = (features.stdAccel*1.2f) + features.meanGyro

        if(totalEnergy < 1.2f) return

        // ================= SPEED-AWARE THRESHOLDS =================
        // 🚴 Bike detection needs different thresholds at different speeds
        speed < 15f      // Below 15 km/h = slow cycling or stationary
        val isMediumSpeed = speed in 15f..30f
        val isHighSpeed = speed > 30f     // Fast cycling

        // Dynamic thresholds based on speed (higher speed = more tolerance)
        val minSpeedForEvents = 12f       // ⬆️ Raised from 5f - ignore events below 12 km/h
        val minEnergyForEvents = if (isHighSpeed) 1.5f else 2.5f  // High speed needs less energy threshold

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
            isHighSpeed -> 1.8f    // ⬇️ Lower at high speed
            isMediumSpeed -> 2.2f  // Standard
            else -> 3.0f           // ⬆️ Much stricter at low speed to avoid hand shake
        }

        val ruleType = when {
            // 🐢 Block all events at very low speed
            speed < minSpeedForEvents -> DrivingEventType.NORMAL

            features.peakForwardAccel > accelThreshold &&
                    features.stdAccel > 1.5f ->
                DrivingEventType.HARSH_ACCELERATION

            features.minForwardAccel < brakeThreshold &&
                    features.stdAccel > 1.5f ->
                DrivingEventType.HARSH_BRAKING

            features.stdAccel > instabilityThreshold &&
                    totalEnergy > minEnergyForEvents &&
                    !isLikelyHandMovement ->  // 🖐️ Block hand movement
                DrivingEventType.UNSTABLE_RIDE

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

        // ================= COOLDOWN =================

        val isCooldownActive = (now - lastEventTime) < EVENT_COOLDOWN

        val finalEventType = if (isCooldownActive) {
            DrivingEventType.NORMAL
        } else {
            finalType
        }

        if (finalEventType != DrivingEventType.NORMAL) {
            lastEventTime = now
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

        appendLogToFile(log)
        Log.d("CSV_WRITE", log)

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

        Log.d("FINAL_PIPELINE",
            "MODE=$currentMode EVENT=${finalEvent.type} spd=$speed std=${features.stdAccel}"
        )

        // ================= SCORE =================

        if (finalEvent.type != DrivingEventType.NORMAL) {

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
        // ================= ML TRAINING =================

        val label = when (finalEvent.type) {
            DrivingEventType.HARSH_ACCELERATION -> 1
            DrivingEventType.HARSH_BRAKING -> 2
            DrivingEventType.UNSTABLE_RIDE -> 3
            else -> 0
        }

        dataLogger.logWindow(window, label)
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