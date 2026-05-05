package com.teledrive.app

import android.content.Context
import com.teledrive.app.triphistory.TripEvent
import com.teledrive.app.triphistory.TripStorage
import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geographic risk zone where the same type of harsh driving event has been
 * detected repeatedly across multiple separate trips.
 *
 * Only zones appearing in [tripCount] >= 2 distinct trips are surfaced — single-trip
 * anomalies are filtered out to reduce false positives.
 *
 * [dominantType] is the most frequent event type within the cluster radius, used to
 * colour the hotspot marker (red = acceleration, orange = braking, yellow = unstable).
 */
data class RiskHotspot(
    val center:       GeoPoint,
    val dominantType: String,
    val eventCount:   Int,      // total events in this zone across all trips
    val tripCount:    Int       // number of distinct trips that triggered events here
)

/**
 * Scans all locally-stored trips for geographic clusters of harsh driving events.
 *
 * Algorithm: greedy single-pass spatial grouping (O(n²) — fast for typical data sizes).
 * Events within [radiusMeters] of the first unassigned event in a group are merged.
 * Only groups spanning 2+ distinct trips are promoted to hotspots.
 *
 * Safe to call from any thread; performs I/O via [TripStorage.getAll].
 */
fun detectHotspots(context: Context, radiusMeters: Double = 150.0): List<RiskHotspot> {
    val allTrips = TripStorage.getAll(context)

    // Flatten all (tripId, TripEvent) pairs — filter out zero-coordinate entries
    val tagged: List<Pair<String, TripEvent>> = allTrips.flatMap { trip ->
        (trip.events ?: emptyList())
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .map    { trip.id to it }
    }

    if (tagged.isEmpty()) return emptyList()

    val assigned = BooleanArray(tagged.size) { false }
    val hotspots = mutableListOf<RiskHotspot>()

    for (i in tagged.indices) {
        if (assigned[i]) continue

        val (_, seedEvent) = tagged[i]
        val group = mutableListOf(tagged[i])
        assigned[i] = true

        for (j in (i + 1) until tagged.size) {
            if (assigned[j]) continue
            val (_, candidate) = tagged[j]
            if (haversineMeters(
                    seedEvent.latitude,  seedEvent.longitude,
                    candidate.latitude, candidate.longitude
                ) <= radiusMeters
            ) {
                group.add(tagged[j])
                assigned[j] = true
            }
        }

        // Require events from at least 2 separate trips to qualify as a hotspot
        val tripIds = group.map { it.first }.toSet()
        if (tripIds.size < 2) continue

        val centerLat    = group.map { it.second.latitude  }.average()
        val centerLng    = group.map { it.second.longitude }.average()
        val dominantType = group
            .map { it.second.type }
            .groupBy { it }
            .maxByOrNull { it.value.size }!!.key

        hotspots.add(
            RiskHotspot(
                center       = GeoPoint(centerLat, centerLng),
                dominantType = dominantType,
                eventCount   = group.size,
                tripCount    = tripIds.size
            )
        )
    }

    return hotspots
}

/**
 * Haversine great-circle distance between two WGS-84 coordinates, in metres.
 * Accurate to within ~0.3% for distances up to ~1000 km.
 */
private fun haversineMeters(
    lat1: Double, lng1: Double,
    lat2: Double, lng2: Double
): Double {
    val R    = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a    = sin(dLat / 2).pow(2) +
               cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return R * 2 * asin(sqrt(a))
}
