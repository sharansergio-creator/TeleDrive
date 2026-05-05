package com.teledrive.app.intelligence

import com.teledrive.app.triphistory.TripSummary

/**
 * Trip Intelligence Layer
 * Provides trends, comparisons, and streak detection
 */

data class TripComparison(
    val scoreDelta: Int,
    val fuelDelta: Float,
    val eventsDelta: Int,
    val improvement: Boolean
)

data class WeeklyTrend(
    val averageScore: Double,
    val totalDistance: Double,
    val totalTrips: Int,
    val bestScore: Int,
    val worstScore: Int,
    val scoreImprovement: Double, // Percentage
    val consistency: TrendConsistency
)

enum class TrendConsistency {
    EXCELLENT,  // Variance < 50
    GOOD,       // Variance < 100
    UNSTABLE    // Variance >= 100
}

data class StreakInfo(
    val currentStreak: Int,
    val longestStreak: Int,
    val streakType: StreakType,
    val nextMilestone: Int
)

enum class StreakType {
    SMOOTH_RIDES,  // Score >= 85
    SAFE_RIDES,    // Score >= 70
    COMPLETED      // Any trips
}

object TripIntelligence {
    
    /**
     * Compare current trip with previous trip
     */
    fun compareWithPrevious(
        current: TripSummary,
        previous: TripSummary
    ): TripComparison {
        
        val scoreDelta = current.score - previous.score
        
        // Simplified fuel delta (you can use your FuelEstimator)
        val currentFuel = current.distanceKm / 40.0 // Assume 40 km/l
        val previousFuel = previous.distanceKm / 40.0
        val fuelDelta = (currentFuel - previousFuel).toFloat()
        
        val currentEvents = current.accelCount + current.brakeCount + current.unstableCount
        val previousEvents = previous.accelCount + previous.brakeCount + previous.unstableCount
        val eventsDelta = currentEvents - previousEvents
        
        return TripComparison(
            scoreDelta = scoreDelta,
            fuelDelta = fuelDelta,
            eventsDelta = eventsDelta,
            improvement = scoreDelta > 0
        )
    }
    
    /**
     * Calculate weekly trend (last 7 days)
     */
    fun calculateWeeklyTrend(trips: List<TripSummary>): WeeklyTrend? {
        
        if (trips.isEmpty()) return null
        
        val now = System.currentTimeMillis()
        val weekAgo = now - (7 * 24 * 60 * 60 * 1000)
        
        val weekTrips = trips.filter { it.timestamp >= weekAgo }
        
        if (weekTrips.isEmpty()) return null
        
        val scores = weekTrips.map { it.score }
        val avgScore = scores.average()
        val totalDistance = weekTrips.sumOf { it.distanceKm }
        val bestScore = scores.maxOrNull() ?: 0
        val worstScore = scores.minOrNull() ?: 0
        
        // Calculate variance for consistency
        val variance = scores.map { (it - avgScore) * (it - avgScore) }.average()
        
        val consistency = when {
            variance < 50 -> TrendConsistency.EXCELLENT
            variance < 100 -> TrendConsistency.GOOD
            else -> TrendConsistency.UNSTABLE
        }
        
        // Calculate improvement (compare first half vs second half of week)
        val improvement = if (weekTrips.size >= 4) {
            val mid = weekTrips.size / 2
            val firstHalf = weekTrips.take(mid).map { it.score }.average()
            val secondHalf = weekTrips.drop(mid).map { it.score }.average()
            ((secondHalf - firstHalf) / firstHalf * 100)
        } else {
            0.0
        }
        
        return WeeklyTrend(
            averageScore = avgScore,
            totalDistance = totalDistance,
            totalTrips = weekTrips.size,
            bestScore = bestScore,
            worstScore = worstScore,
            scoreImprovement = improvement,
            consistency = consistency
        )
    }
    
    /**
     * Detect streaks
     */
    fun detectStreak(trips: List<TripSummary>, threshold: Int = 85): StreakInfo {
        
        if (trips.isEmpty()) {
            return StreakInfo(0, 0, StreakType.SMOOTH_RIDES, 3)
        }
        
        // Sort by timestamp (newest first)
        val sortedTrips = trips.sortedByDescending { it.timestamp }
        
        // Calculate current streak
        var currentStreak = 0
        for (trip in sortedTrips) {
            if (trip.score >= threshold) {
                currentStreak++
            } else {
                break
            }
        }
        
        // Calculate longest streak
        var longestStreak = 0
        var tempStreak = 0
        
        for (trip in sortedTrips) {
            if (trip.score >= threshold) {
                tempStreak++
                if (tempStreak > longestStreak) {
                    longestStreak = tempStreak
                }
            } else {
                tempStreak = 0
            }
        }
        
        // Determine next milestone
        val milestones = listOf(3, 5, 10, 15, 20, 25, 50)
        val nextMilestone = milestones.firstOrNull { it > currentStreak } ?: (currentStreak + 10)
        
        val streakType = when {
            threshold >= 85 -> StreakType.SMOOTH_RIDES
            threshold >= 70 -> StreakType.SAFE_RIDES
            else -> StreakType.COMPLETED
        }
        
        return StreakInfo(
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            streakType = streakType,
            nextMilestone = nextMilestone
        )
    }
    
    /**
     * Get motivational message based on streak
     */
    fun getStreakMessage(streak: StreakInfo): String {
        return when {
            streak.currentStreak == 0 -> "Start your smooth riding streak!"
            streak.currentStreak < 3 -> "${streak.currentStreak} smooth ${if (streak.currentStreak == 1) "ride" else "rides"}. Keep going!"
            streak.currentStreak in 3..4 -> "🔥 ${streak.currentStreak} smooth rides! You're building momentum."
            streak.currentStreak in 5..9 -> "🔥 ${streak.currentStreak} ride streak! You're on fire!"
            streak.currentStreak in 10..19 -> "🚀 ${streak.currentStreak} ride streak! Elite performance!"
            streak.currentStreak >= 20 -> "⭐ ${streak.currentStreak} ride streak! You're a driving master!"
            else -> "Keep riding safely!"
        }
    }
    
    /**
     * Get trend message
     */
    fun getTrendMessage(trend: WeeklyTrend): String {
        return when {
            trend.scoreImprovement > 10 -> "📈 Your driving improved ${trend.scoreImprovement.toInt()}% this week!"
            trend.scoreImprovement > 5 -> "📊 Steady improvement this week (+${trend.scoreImprovement.toInt()}%)"
            trend.scoreImprovement < -10 -> "⚠️ Your scores dropped ${(-trend.scoreImprovement).toInt()}% this week"
            trend.consistency == TrendConsistency.EXCELLENT -> "✅ Excellent consistency this week!"
            trend.consistency == TrendConsistency.GOOD -> "👍 Good consistency maintained"
            else -> "Focus on smoother, more consistent riding"
        }
    }
    
    /**
     * Get personalized weekly summary
     */
    fun getWeeklySummary(trips: List<TripSummary>): String {
        val trend = calculateWeeklyTrend(trips) ?: return "Not enough data for weekly summary"
        val streak = detectStreak(trips)
        
        return buildString {
            appendLine("This Week:")
            appendLine("• ${trend.totalTrips} trips, ${String.format("%.1f", trend.totalDistance)} km")
            appendLine("• Average score: ${trend.averageScore.toInt()}/100")
            appendLine("• ${getTrendMessage(trend)}")
            appendLine("• ${getStreakMessage(streak)}")
            
            if (streak.currentStreak > 0) {
                appendLine("• ${streak.nextMilestone - streak.currentStreak} more to next milestone!")
            }
        }
    }
}

