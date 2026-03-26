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
import com.teledrive.app.TripHistory.TripSummary
import com.teledrive.app.TripHistory.TripStorage
import com.teledrive.app.ml.DataLogger
import java.io.File

class SensorService : Service(), SensorEventListener {

    companion object {
        const val TAG = "TeleDriveSensors"
        const val CHANNEL_ID = "teledrive_foreground"
        const val NOTIFICATION_ID = 1
        private const val WINDOW_DURATION_MS = 1500L
        var lastCapturedImagePath: String? = null
    }

    private lateinit var sensorManager: SensorManager
    private val processor = TeleDriveProcessor()
    private val ecoScoreEngine = EcoScoreEngine()
    private val rideSessionManager = RideSessionManager()
    private lateinit var locationService: LocationService
    private lateinit var dataLogger: DataLogger
    private val analyzer: DrivingAnalyzer get() = AnalyzerProvider.getAnalyzer()

    private val windowBuffer = mutableListOf<SensorSample>()

    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f

    private var lastCaptureTime = 0L
    private var lastEventTime = 0L
    private val EVENT_COOLDOWN = 3000L

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val session = rideSessionManager.endRide()

        session?.let {
            val trip = TripSummary(
                score = it.finalScore,
                distance = it.distanceKm,
                duration = it.rideDuration,
                harshAccel = it.harshAccelerationCount,
                harshBrake = it.harshBrakingCount,
                instability = it.unstableRideCount,
                timestamp = System.currentTimeMillis()
            )

            TripStorage.save(this, trip)

            val fuelUsed = ecoScoreEngine.getFinalFuelConsumption(it.distanceKm)
            val moneyLost = ecoScoreEngine.getFinancialLoss(105.0f)

            val intent = Intent(this, RideSummaryActivity::class.java).apply {
                putExtra("score", it.finalScore)
                putExtra("distance", it.distanceKm)
                putExtra("fuelUsed", fuelUsed)
                putExtra("moneyLost", moneyLost)
                putExtra("harshAccel", it.harshAccelerationCount)
                putExtra("harshBrake", it.harshBrakingCount)
                putExtra("instability", it.unstableRideCount)
                putExtra("imagePath", lastCapturedImagePath ?: RideSessionManager.lastEventImagePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

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

                val cutOff = now - (WINDOW_DURATION_MS - 500L)
                windowBuffer.removeAll { it.timestamp < cutOff }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processWindow(window: List<SensorSample>) {

        val features = processor.extractFeatures(window)
        val speed = locationService.getCurrentSpeed()
        val now = System.currentTimeMillis()

        // ✅ STEP 1: MAIN ANALYZER
        val analyzedEvent = analyzer.analyze(features, speed)

        // ✅ STEP 2: FALLBACK (WITH SPEED FILTER)
        val fallbackType = if (speed > 22f) {
            when {
                features.peakForwardAccel > 3.2f ->
                    DrivingEventType.HARSH_ACCELERATION

                features.minForwardAccel < -6.0f ->
                    DrivingEventType.HARSH_BRAKING

                features.stdAccel > 3.2f ->
                    DrivingEventType.UNSTABLE_RIDE

                else -> DrivingEventType.NORMAL
            }
        } else {
            DrivingEventType.NORMAL
        }
        val finalType = if (analyzedEvent.type != DrivingEventType.NORMAL) {
            analyzedEvent.type
        } else {

            // 🔥 BUFFER ZONE (TIGHTENED - BASED ON LOGS)
            if (features.minForwardAccel in -5.5f..-4.5f) {
                DrivingEventType.NORMAL
            }
            // 🔥 INSTABILITY NOISE FILTER
            if (features.stdAccel in 2.5f..3.2f) {
                DrivingEventType.NORMAL
            }
            else {
                fallbackType
            }
        }
        // ✅ FIX 4: FINAL SAFETY FILTER (ADD HERE)
        val safeFinalType = if (speed < 22f) {
            DrivingEventType.NORMAL
        } else {
            finalType
        }
        val finalEvent = DrivingEvent(
            safeFinalType,
            when (safeFinalType) {
                DrivingEventType.HARSH_ACCELERATION -> features.peakForwardAccel
                DrivingEventType.HARSH_BRAKING -> kotlin.math.abs(features.minForwardAccel)
                DrivingEventType.UNSTABLE_RIDE -> features.stdAccel
                else -> 0f
            }
        )
        val log = "${System.currentTimeMillis()}," +
                "$speed," +
                "${features.peakForwardAccel}," +
                "${features.minForwardAccel}," +
                "${features.stdAccel}," +
                "${features.meanGyro}," +
                "${finalEvent.type}"

        appendLogToFile(log)

        Log.d("CSV_WRITE", log)

        // ✅ STEP 4: COOLDOWN CONTROL
        val allowUpdate = when {
            finalEvent.type == DrivingEventType.NORMAL -> true
            now - lastEventTime > EVENT_COOLDOWN -> true
            else -> false
        }

        if (allowUpdate) {

            if (finalEvent.type != DrivingEventType.NORMAL) {
                lastEventTime = now
            }

            // ✅ ONLY PLACE UI IS UPDATED
            LiveDataBus.listener?.invoke(
                finalEvent.type.name,
                speed,
                features.stdAccel
            )

            updateNotification(finalEvent.type.name, speed)
        }

        // ✅ DEBUG (KEEP THIS)
        Log.d(
            "FINAL_PIPELINE",
            "spd=$speed peak=${features.peakForwardAccel} min=${features.minForwardAccel} std=${features.stdAccel} -> ${finalEvent.type}"
        )

        // ✅ STEP 5: SESSION + SCORE
        if (finalEvent.type != DrivingEventType.NORMAL) {

            val scoreImpact = ecoScoreEngine.processEvent(finalEvent)

            rideSessionManager.processEvent(finalEvent)
            rideSessionManager.updateScore(scoreImpact)

            val isDangerous = when (finalEvent.type) {
                DrivingEventType.HARSH_BRAKING -> true
                DrivingEventType.HARSH_ACCELERATION -> finalEvent.severity > 2.5f
                DrivingEventType.UNSTABLE_RIDE -> finalEvent.severity > 3.0f
                else -> false
            }

            if (isDangerous && (now - lastCaptureTime > 15000L)) {
                lastCaptureTime = now

                val intent = Intent(
                    this,
                    com.teledrive.app.camera.CameraControllerActivity::class.java
                ).apply {
                    putExtra("event_type", finalEvent.type.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }

                startActivity(intent)
            }
        }

        // ✅ STEP 6: ML LOGGING
        val label = when (finalEvent.type) {
            DrivingEventType.HARSH_ACCELERATION -> 1
            DrivingEventType.HARSH_BRAKING -> 2
            DrivingEventType.UNSTABLE_RIDE -> 3
            else -> 0
        }

        dataLogger.logWindow(window, label)
    }
    // ---------------- NOTIFICATIONS ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride Insights",
                NotificationManager.IMPORTANCE_HIGH
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