package com.teledrive.app.triphistory

import java.util.UUID

// Trend logic
enum class TripTrend(val message: String, val icon: String) {
    IMPROVING("Your driving is improving", "⬆️"),
    DECLINING("Try smoother braking", "⚠️"),
    STABLE("Consistent driving style", "✅")
}

/**
 * A single confirmed driving event with its GPS location at the time of detection.
 * Stored as part of TripSummary for map rendering in TripDetailActivity.
 *
 * Null-safety note: this list is declared nullable in TripSummary so that Gson
 * deserializing old trips (which have no "events" key in their JSON) produces null
 * rather than triggering a NullPointerException. All read sites must use ?: emptyList().
 */
data class TripEvent(
    val type: String,        // "HARSH_ACCELERATION" | "HARSH_BRAKING" | "UNSTABLE_RIDE"
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val severity: Float,
    val speedKmh: Float
)

// Enhanced data model with location support + event counts
data class TripSummary(
    val id: String = UUID.randomUUID().toString(),
    val score: Int,
    val distanceKm: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val tip: String?,
    
    // Location support (edge-based)
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val startLocationName: String? = null,
    val endLocationName: String? = null,
    
    // Trip duration
    val durationMs: Long = 0L,
    
    // Event counts (for intelligent insights)
    val accelCount: Int = 0,
    val brakeCount: Int = 0,
    val unstableCount: Int = 0,

    // Spatial event log for Event Map. Nullable intentionally — Gson sets this to null
    // when deserializing trips saved before this field existed, avoiding NPEs on old data.
    val events: List<TripEvent>? = null
)