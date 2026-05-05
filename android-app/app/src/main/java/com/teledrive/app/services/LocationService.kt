package com.teledrive.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*

class LocationService(context: Context) {

    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var lastLocation: Location? = null
    private var currentSpeed = 0f
    private var smoothedSpeed = 0f

    var totalDistanceKm = 0f

    // Lower alpha = more responsive to real-time changes
    // Higher alpha = smoother but laggier
    private val alpha = 0.4f

    private var locationCallback: LocationCallback? = null

    // Returns the GPS bearing in degrees [0, 360) when available.
    // Returns -1f when GPS has no fix yet.  -1f is the "no-GPS" sentinel.
    // NOTE: 0f is due-North (a valid heading) and is NOT treated as unavailable.
    fun getHeading(): Float = lastLocation?.bearing ?: -1f

    @SuppressLint("MissingPermission")
    fun startTracking(
        onDistanceUpdate: (Float) -> Unit,
        onSpeedUpdate: (Float) -> Unit
    ) {

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1 second is standard for GPS apps
        ).apply {
            setMinUpdateIntervalMillis(500L)
            setMinUpdateDistanceMeters(0.5f) // Detect smaller movements
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                // 1. Calculate Raw Speed (km/h)
                var rawSpeed = if (location.hasSpeed()) {
                    location.speed * 3.6f
                } else {
                    0f
                }

                // 2. Fallback for manual speed calculation
                if (rawSpeed < 1.0f) { // Increased threshold
                    lastLocation?.let { prev ->
                        val timeDiff = (location.time - prev.time) / 1000f
                        // Only calculate if the GPS accuracy is decent (< 15 meters)
                        if (timeDiff > 0 && location.accuracy < 15f) {
                            val distance = prev.distanceTo(location)
                            // If distance is small (< 2m), it's likely just a hand shake/drift
                            if (distance > 2.0f) {
                                val calcSpeed = (distance / timeDiff) * 3.6f
                                if (calcSpeed > 2.0f) rawSpeed = calcSpeed
                            }
                        }
                    }
                }

// 3. Validate & Smooth
                val validatedSpeed = when {
                    rawSpeed < 0.5f -> 0f   // allow slow movement
                    rawSpeed > 150f -> currentSpeed
                    else -> rawSpeed
                }
                Log.d(
                    "SPEED_FINAL",
                    "raw=$rawSpeed validated=$validatedSpeed final=$currentSpeed acc=${location.accuracy}"
                )

                // Simple Low-Pass Filter
                smoothedSpeed = (alpha * validatedSpeed) + ((1 - alpha) * smoothedSpeed)
                currentSpeed = smoothedSpeed

                onSpeedUpdate(currentSpeed)

                // 4. Distance Calculation
                lastLocation?.let { prev ->
                    val distanceMeters = prev.distanceTo(location)

                    // Logic: If we moved more than 1 meter AND speed indicates we aren't just drifting
                    // Reduced thresholds to catch "events" better
                    if (distanceMeters > 2.0 && currentSpeed > 1.0f) {
                        val distanceKm = distanceMeters / 1000f
                        totalDistanceKm += distanceKm
                        onDistanceUpdate(totalDistanceKm)

                        Log.d("SPEED_DEBUG", "Moving: Speed=$currentSpeed km/h, Added=${distanceMeters}m")
                    }
                }

                lastLocation = location
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        lastLocation = null // Reset for next session
    }

    fun getCurrentSpeed(): Float = currentSpeed
    
    fun getLatitude(): Double = lastLocation?.latitude ?: 0.0
    
    fun getLongitude(): Double = lastLocation?.longitude ?: 0.0
}
