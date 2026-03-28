package com.teledrive.app.triphistory

import java.util.UUID   // ✅ REQUIRED

// Trend logic
enum class TripTrend(val message: String, val icon: String) {
    IMPROVING("Your driving is improving", "⬆️"),
    DECLINING("Try smoother braking", "⚠️"),
    STABLE("Consistent driving style", "✅")
}

// Data model
data class TripSummary(
    val id: String = UUID.randomUUID().toString(),
    val score: Int,
    val distanceKm: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val tip: String?
)