package com.teledrive.app.intelligence

import com.teledrive.app.triphistory.TripSummary
import kotlin.math.abs

/**
 * Intelligent Insight Engine
 * Converts raw trip data into actionable, contextual feedback
 * 
 * Design Philosophy:
 * - Contextual (compares with history)
 * - Specific (not generic advice)
 * - Actionable (tells HOW to improve)
 */
data class TripInsight(
    val category: InsightCategory,
    val title: String,
    val description: String,
    val improvement: String,
    val severity: InsightSeverity,
    val icon: String
)

enum class InsightCategory {
    ACCELERATION,
    BRAKING,
    STABILITY,
    FUEL_EFFICIENCY,
    OVERALL_IMPROVEMENT,
    STREAK,
    MILESTONE
}

enum class InsightSeverity {
    POSITIVE,    // Good performance
    NEUTRAL,     // Average
    WARNING,     // Needs attention
    CRITICAL     // Serious issue
}

object InsightEngine {
    
    /**
     * Generate intelligent insights from trip data
     * Uses rule-based logic with contextual awareness
     */
    fun generateInsights(
        currentTrip: TripSummary,
        previousTrip: TripSummary?,
        recentTrips: List<TripSummary>, // Last 7 days
        profileMileage: Float? // User's vehicle mileage (km/l)
    ): List<TripInsight> {
        
        val insights = mutableListOf<TripInsight>()
        
        // 1. Comparative Analysis (vs last trip)
        if (previousTrip != null) {
            insights.addAll(generateComparativeInsights(currentTrip, previousTrip))
        }
        
        // 2. Absolute Performance (this trip alone)
        insights.addAll(generatePerformanceInsights(currentTrip, profileMileage))
        
        // 3. Trend Analysis (weekly behavior)
        if (recentTrips.size >= 3) {
            insights.addAll(generateTrendInsights(currentTrip, recentTrips))
        }
        
        // 4. Streak & Milestone Detection
        insights.addAll(generateStreakInsights(currentTrip, recentTrips))
        
        // Return top 3 most relevant insights
        return insights
            .sortedByDescending { it.severity.ordinal }
            .take(3)
    }
    
    // ==================== COMPARATIVE INSIGHTS ====================
    
    private fun generateComparativeInsights(
        current: TripSummary,
        previous: TripSummary
    ): List<TripInsight> {
        
        val insights = mutableListOf<TripInsight>()
        
        // Extract event counts (assumes they're stored as extras or in a data class)
        // Note: You'll need to extend TripSummary to store event counts
        // For now, using dummy extraction - adapt to your data model
        
        val currentAccel = extractEventCount(current, "accel")
        val previousAccel = extractEventCount(previous, "accel")
        
        val currentBrake = extractEventCount(current, "brake")
        val previousBrake = extractEventCount(previous, "brake")
        
        val currentUnstable = extractEventCount(current, "unstable")
        val previousUnstable = extractEventCount(previous, "unstable")
        
        // Braking Comparison
        if (previousBrake > 0) {
            val brakeChange = ((currentBrake - previousBrake).toFloat() / previousBrake * 100).toInt()
            
            if (abs(brakeChange) >= 30) {
                insights.add(
                    TripInsight(
                        category = InsightCategory.BRAKING,
                        title = if (brakeChange > 0) "Braking Increased" else "Braking Improved",
                        description = "You braked ${abs(brakeChange)}% ${if (brakeChange > 0) "more" else "less"} than your last trip. " +
                                "This ${if (brakeChange > 0) "increases" else "reduces"} fuel consumption and brake wear.",
                        improvement = if (brakeChange > 0) {
                            "Try maintaining safer distance and anticipating stops earlier."
                        } else {
                            "Great! Keep maintaining smooth deceleration patterns."
                        },
                        severity = if (brakeChange > 0) InsightSeverity.WARNING else InsightSeverity.POSITIVE,
                        icon = "🛑"
                    )
                )
            }
        }
        
        // Acceleration Comparison
        if (previousAccel > 0) {
            val accelChange = ((currentAccel - previousAccel).toFloat() / previousAccel * 100).toInt()
            
            if (abs(accelChange) >= 30) {
                insights.add(
                    TripInsight(
                        category = InsightCategory.ACCELERATION,
                        title = if (accelChange > 0) "Aggressive Acceleration" else "Smoother Start",
                        description = "Your acceleration events ${if (accelChange > 0) "increased" else "decreased"} by ${abs(accelChange)}%.",
                        improvement = if (accelChange > 0) {
                            "Gradual throttle input can improve fuel efficiency by 15-20%."
                        } else {
                            "Excellent! Smooth acceleration saves fuel and reduces engine stress."
                        },
                        severity = if (accelChange > 0) InsightSeverity.WARNING else InsightSeverity.POSITIVE,
                        icon = "⚡"
                    )
                )
            }
        }
        
        // Score Comparison
        val scoreDiff = current.score - previous.score
        if (abs(scoreDiff) >= 10) {
            insights.add(
                TripInsight(
                    category = InsightCategory.OVERALL_IMPROVEMENT,
                    title = if (scoreDiff > 0) "Score Improved" else "Score Declined",
                    description = "Your driving score ${if (scoreDiff > 0) "increased" else "decreased"} by ${abs(scoreDiff)} points.",
                    improvement = if (scoreDiff > 0) {
                        "Keep it up! You're building better driving habits."
                    } else {
                        "Focus on smoother inputs and anticipating road conditions."
                    },
                    severity = if (scoreDiff > 0) InsightSeverity.POSITIVE else InsightSeverity.NEUTRAL,
                    icon = if (scoreDiff > 0) "📈" else "📉"
                )
            )
        }
        
        return insights
    }
    
    // ==================== PERFORMANCE INSIGHTS ====================
    
    private fun generatePerformanceInsights(
        current: TripSummary,
        profileMileage: Float?
    ): List<TripInsight> {
        
        val insights = mutableListOf<TripInsight>()
        
        // Score-based feedback
        when {
            current.score >= 90 -> {
                insights.add(
                    TripInsight(
                        category = InsightCategory.OVERALL_IMPROVEMENT,
                        title = "Excellent Ride",
                        description = "Your score of ${current.score} indicates professional-level driving discipline.",
                        improvement = "Maintain this standard for optimal vehicle longevity.",
                        severity = InsightSeverity.POSITIVE,
                        icon = "⭐"
                    )
                )
            }
            current.score in 70..89 -> {
                insights.add(
                    TripInsight(
                        category = InsightCategory.OVERALL_IMPROVEMENT,
                        title = "Good Performance",
                        description = "Score ${current.score} shows solid driving skills with room for refinement.",
                        improvement = "Focus on reducing sudden inputs for premium performance.",
                        severity = InsightSeverity.NEUTRAL,
                        icon = "👍"
                    )
                )
            }
            current.score < 50 -> {
                insights.add(
                    TripInsight(
                        category = InsightCategory.OVERALL_IMPROVEMENT,
                        title = "High-Risk Behavior Detected",
                        description = "Score ${current.score} indicates aggressive riding patterns that increase accident risk.",
                        improvement = "Prioritize safety: slow down, maintain distance, and avoid sudden maneuvers.",
                        severity = InsightSeverity.CRITICAL,
                        icon = "⚠️"
                    )
                )
            }
        }
        
        // Fuel efficiency insight (if profile mileage available)
        profileMileage?.let { expectedMileage ->
            val fuelLoss = estimateFuelLoss(current, expectedMileage)
            if (fuelLoss > 0.5f) {
                insights.add(
                    TripInsight(
                        category = InsightCategory.FUEL_EFFICIENCY,
                        title = "Fuel Impact",
                        description = "This trip's behavior pattern likely cost you ₹${(fuelLoss * 105).toInt()} in extra fuel.",
                        improvement = "Smooth driving can save ₹200-500 monthly on fuel costs.",
                        severity = InsightSeverity.WARNING,
                        icon = "⛽"
                    )
                )
            }
        }
        
        return insights
    }
    
    // ==================== TREND INSIGHTS ====================
    
    private fun generateTrendInsights(
        current: TripSummary,
        recentTrips: List<TripSummary>
    ): List<TripInsight> {
        
        val insights = mutableListOf<TripInsight>()
        
        // Calculate weekly average score
        val avgScore = recentTrips.map { it.score }.average()
        val scoreImprovement = current.score - avgScore
        
        if (scoreImprovement >= 10) {
            insights.add(
                TripInsight(
                    category = InsightCategory.OVERALL_IMPROVEMENT,
                    title = "Weekly Improvement",
                    description = "You're ${scoreImprovement.toInt()} points above your weekly average!",
                    improvement = "Consistency is key. Keep this momentum going.",
                    severity = InsightSeverity.POSITIVE,
                    icon = "📊"
                )
            )
        }
        
        // Detect consistency
        val scoreVariance = recentTrips.map { it.score }.let { scores ->
            val mean = scores.average()
            scores.map { (it - mean) * (it - mean) }.average()
        }
        
        if (scoreVariance < 50) { // Low variance = consistent
            insights.add(
                TripInsight(
                    category = InsightCategory.OVERALL_IMPROVEMENT,
                    title = "Consistent Performance",
                    description = "Your scores are stable across recent trips, showing habit formation.",
                    improvement = "Consistency builds muscle memory for safe driving.",
                    severity = InsightSeverity.POSITIVE,
                    icon = "✅"
                )
            )
        }
        
        return insights
    }
    
    // ==================== STREAK INSIGHTS ====================
    
    private fun generateStreakInsights(
        current: TripSummary,
        recentTrips: List<TripSummary>
    ): List<TripInsight> {
        
        val insights = mutableListOf<TripInsight>()
        
        // Detect smooth ride streak (score >= 85)
        val smoothStreak = recentTrips
            .takeWhile { it.score >= 85 }
            .size + (if (current.score >= 85) 1 else 0)
        
        when {
            smoothStreak >= 5 -> {
                insights.add(
                    TripInsight(
                        category = InsightCategory.STREAK,
                        title = "🔥 $smoothStreak Smooth Rides",
                        description = "You've maintained excellent driving for $smoothStreak trips straight!",
                        improvement = "Elite performance! Your vehicle thanks you.",
                        severity = InsightSeverity.POSITIVE,
                        icon = "🔥"
                    )
                )
            }
            smoothStreak >= 3 -> {
                insights.add(
                    TripInsight(
                        category = InsightCategory.STREAK,
                        title = "$smoothStreak Smooth Trips",
                        description = "You're building a great streak. Keep it going!",
                        improvement = "2 more trips to unlock 🔥 streak badge!",
                        severity = InsightSeverity.POSITIVE,
                        icon = "✨"
                    )
                )
            }
        }
        
        // Milestone detection
        val totalTrips = recentTrips.size + 1
        if (totalTrips in listOf(10, 25, 50, 100)) {
            insights.add(
                TripInsight(
                    category = InsightCategory.MILESTONE,
                    title = "Milestone: $totalTrips Trips",
                    description = "You've completed $totalTrips tracked rides with TeleDrive!",
                    improvement = "Your driving data helps improve your skills over time.",
                    severity = InsightSeverity.POSITIVE,
                    icon = "🎯"
                )
            )
        }
        
        return insights
    }
    
    // ==================== HELPER FUNCTIONS ====================
    
    private fun extractEventCount(trip: TripSummary, eventType: String): Int {
        return when (eventType) {
            "accel" -> trip.accelCount
            "brake" -> trip.brakeCount
            "unstable" -> trip.unstableCount
            else -> 0
        }
    }

    /**
     * Single-line insight for trip history cards.
     * Called from TripHistoryCard in TripsActivity — must be short (≤ 70 chars).
     * Delegates to [generateTripInsight] so all insight surfaces stay consistent.
     */
    fun generateQuickInsight(trip: TripSummary): String {
        // One-liner variant — truncate to the first sentence of the full insight.
        val full = generateTripInsight(
            score        = trip.score,
            accelCount   = trip.accelCount,
            brakeCount   = trip.brakeCount,
            unstableCount = trip.unstableCount,
            distanceKm   = trip.distanceKm,
            durationMs   = trip.durationMs
        )
        // Take everything up to the first sentence terminator, or the whole string if short.
        val sentence = full.substringBefore(".").substringBefore(",").trim()
        return if (sentence.length in 10..72) sentence else full.take(72)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST-TRIP INSIGHT ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a contextual, human-tone post-trip insight.
     *
     * Design rules:
     *  - Never fake positivity on a bad trip.
     *  - Dominant problem type drives the headline.
     *  - Severity (score + event density + raw counts) selects the tone.
     *  - Short trips (< 1 km) get lighter treatment.
     *  - Mixed multi-category problems get a specific cross-category message.
     *
     * Called from:
     *  - [SensorService] at trip-save time (produces TripSummary.tip)
     *  - [RideSummaryActivity] for the post-ride summary card
     *  - [generateQuickInsight] for trip list one-liners
     */
    fun generateTripInsight(
        score:         Int,
        accelCount:    Int,
        brakeCount:    Int,
        unstableCount: Int,
        distanceKm:    Double,
        durationMs:    Long
    ): String {
        val total       = accelCount + brakeCount + unstableCount
        val durationMin = (durationMs / 60_000L).toInt()
        val isShortTrip = distanceKm < 1.0 && durationMin < 5

        // ── Zero harsh events ──────────────────────────────────────────────
        if (total == 0) {
            return when {
                score >= 95 -> "Perfect ride — zero harsh events. Excellent control throughout."
                score >= 85 -> "Smooth and efficient ride. No harsh events detected."
                score >= 75 -> "Clean ride. Throttle and brake inputs were well-controlled."
                score >= 60 -> "No harsh events logged, but check speed habits for a better score."
                else        -> "No events detected on this short or low-speed trip."
            }
        }

        // ── Event density (events per km — guards against short-trip inflation) ──
        val density = if (distanceKm >= 0.5) total / distanceKm else total.toDouble()

        // ── Dominant category ─────────────────────────────────────────────
        val maxCount       = maxOf(accelCount, brakeCount, unstableCount)
        // "clearly dominant" = at least 2 events AND more than both other types combined
        val accelDominant   = accelCount   == maxCount && accelCount   >= 2
                              && accelCount   > brakeCount + unstableCount
        val brakeDominant   = brakeCount   == maxCount && brakeCount   >= 2
                              && brakeCount   > accelCount + unstableCount
        val unstableDominant = unstableCount == maxCount && unstableCount >= 2
                              && unstableCount > accelCount + brakeCount

        // ── Severity bands ────────────────────────────────────────────────
        val isCritical  = score < 50  || total >= 8  || density > 4.0
        val isSevere    = score < 65  || total >= 5  || density > 2.5
        val isModerate  = score < 80  || total >= 3  || density > 1.0
        // Minor = 1-2 events, decent score, low density
        val isMinor     = total <= 2  && score >= 75 && density <= 1.5

        // ── SHORT TRIP early return ───────────────────────────────────────
        if (isShortTrip && !isCritical) {
            return when {
                unstableDominant -> "Instability noted on a short trip. Rough surface or sudden weight shift?"
                brakeDominant    -> "Hard stop on a short trip. Allow more stopping distance."
                accelDominant    -> "Quick throttle on a short trip. Ease in gradually."
                else             -> "Short trip with $total event${if (total > 1) "s" else ""} — likely to improve on longer rides."
            }
        }

        // ── MINOR (1-2 events, good score) ───────────────────────────────
        if (isMinor) {
            return when {
                accelDominant    -> "Light throttle spike logged. Minimal score impact."
                brakeDominant    -> "One or two hard brakes. Anticipate stops a moment earlier."
                unstableDominant -> "Slight instability noted — could be road surface or tyre pressure."
                else             -> "Minor events logged. Overall ride quality was good."
            }
        }

        // ── CRITICAL (score < 50 OR many events OR very high density) ────
        if (isCritical) {
            return when {
                unstableDominant ->
                    "High instability this trip. Check tyre pressure and reduce speed on bends."
                brakeDominant ->
                    "Frequent hard braking significantly lowered your score. Increase following distance."
                accelDominant ->
                    "Aggressive acceleration throughout. Ease on the throttle for safer, cleaner riding."
                unstableCount >= 3 && brakeCount >= 3 ->
                    "Repeated instability and hard braking detected. Slow down and ride more predictably."
                accelCount >= 3 && brakeCount >= 3 ->
                    "Heavy throttle and braking detected together — avoid rush-and-brake patterns."
                else ->
                    "Multiple high-risk events this ride. Calmer, smoother inputs are needed."
            }
        }

        // ── SEVERE ────────────────────────────────────────────────────────
        if (isSevere) {
            return when {
                unstableDominant ->
                    "Frequent instability detected. Reduce speed on bends and rough roads."
                brakeDominant ->
                    "Repeated hard braking observed. Maintain a safer following distance."
                accelDominant ->
                    "Aggressive throttle inputs detected. Gradual acceleration saves fuel and control."
                unstableCount >= 2 && brakeCount >= 2 ->
                    "Instability and braking events combined — slow down on challenging road sections."
                accelCount >= 2 && unstableCount >= 2 ->
                    "Hard throttle and instability both detected — smooth out your riding style."
                else ->
                    "Multiple events across categories. Smoother, more predictable inputs will help."
            }
        }

        // ── MODERATE ──────────────────────────────────────────────────────
        if (isModerate) {
            return when {
                unstableDominant ->
                    "Some instability this ride. Watch cornering speed and tyre condition."
                brakeDominant ->
                    "A few hard brakes logged. Build the habit of earlier, gentler stops."
                accelDominant ->
                    "Some harsh acceleration. Progressive throttle improves efficiency by up to 15%."
                brakeCount > 0 && accelCount > 0 ->
                    "Moderate ride quality. Smoother braking and throttle can improve your score."
                else ->
                    "Moderate ride. Small refinements in your inputs will push the score higher."
            }
        }

        // ── GOOD (few events, score >= 80, but some noise) ────────────────
        return when {
            score >= 88 -> "Good ride with minor events. Small refinements away from a great score."
            score >= 78 -> "Solid ride quality. Reducing event count by one or two will lift your score."
            else        -> "Decent ride. Focus on anticipating stops and keeping throttle inputs smooth."
        }
    }
    
    private fun estimateFuelLoss(trip: TripSummary, expectedMileage: Float): Float {
        // Rough estimation: poor driving reduces mileage by 20-30%
        val baseFuel = trip.distanceKm / expectedMileage
        val penaltyFactor = (100 - trip.score) / 100f * 0.3f
        return (baseFuel * penaltyFactor).toFloat()
    }
}

