package com.teledrive.app

import com.teledrive.app.triphistory.TripSummary
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * Computed analytics for a single trip, derived purely from locally-stored data.
 *
 * [isSmooth] is true when no driving events were detected — presented to the rider
 * as a positive "Smooth Trip" badge.
 */
data class RouteAnalytics(
    val distanceKm:    Double,
    val totalEvents:   Int,
    val densityPerKm:  Double,   // events per km; 0 when distance is zero
    val maxSeverity:   Float,
    val isSmooth:      Boolean   // zero harsh events detected
)

/**
 * Derives [RouteAnalytics] from a stored [TripSummary].
 * Pure computation — no I/O, safe to call on any thread.
 */
fun computeRouteAnalytics(trip: TripSummary): RouteAnalytics {
    val events  = trip.events ?: emptyList()
    val density = if (trip.distanceKm > 0.0) events.size / trip.distanceKm else 0.0
    val maxSev  = events.maxOfOrNull { it.severity } ?: 0f
    return RouteAnalytics(
        distanceKm   = trip.distanceKm,
        totalEvents  = events.size,
        densityPerKm = density,
        maxSeverity  = maxSev,
        isSmooth     = events.isEmpty()
    )
}

/**
 * Builds a smoothed [Polyline] tracing: start → event waypoints (sorted by timestamp) → end.
 *
 * Processing pipeline:
 *  1. Collect anchor points: start → events sorted by timestamp → end.
 *  2. GPS outlier removal: a middle point is dropped when it is > [MAX_JUMP_KM] km
 *     from BOTH its predecessor AND its successor (single-point GPS glitch removal).
 *  3. Catmull-Rom spline: [SPLINE_STEPS] intermediate GeoPoints are interpolated between
 *     every consecutive pair of anchors. This converts sharp angular bends into smooth
 *     curves, making the sparse event-trail look like a real road segment.
 *
 * Returns null when fewer than 2 valid points remain after filtering.
 * Callers apply Paint styling (color, strokeWidth, alpha) after construction.
 */
fun buildRoutePolyline(trip: TripSummary): Polyline? {
    // ── Step 1: collect anchor points ─────────────────────────────────────────
    val anchors: List<GeoPoint> = buildList {
        if (trip.startLat != 0.0 || trip.startLng != 0.0)
            add(GeoPoint(trip.startLat, trip.startLng))
        trip.events
            ?.filter   { it.latitude != 0.0 || it.longitude != 0.0 }
            ?.sortedBy { it.timestamp }
            ?.forEach  { add(GeoPoint(it.latitude, it.longitude)) }
        if (trip.endLat != 0.0 || trip.endLng != 0.0)
            add(GeoPoint(trip.endLat, trip.endLng))
    }
    if (anchors.size < 2) return null

    // ── Step 2: GPS outlier removal ────────────────────────────────────────────
    // A middle point is a glitch only when its distance to BOTH neighbours exceeds
    // MAX_JUMP_KM. Start and end anchors are always preserved.
    val MAX_JUMP_KM = 2.5
    val filtered: List<GeoPoint> = anchors.filterIndexed { i, pt ->
        when (i) {
            0, anchors.lastIndex -> true
            else -> {
                val dPrev = haversineKm(anchors[i - 1], pt)
                val dNext = haversineKm(pt, anchors[i + 1])
                !(dPrev > MAX_JUMP_KM && dNext > MAX_JUMP_KM)
            }
        }
    }
    if (filtered.size < 2) return null

    // ── Step 3: Catmull-Rom spline interpolation ───────────────────────────────
    // Adds SPLINE_STEPS intermediate points between each consecutive anchor pair.
    // Boundary condition: duplicate the first and last anchors as phantom control
    // points so the spline begins exactly at filtered[0] and ends at filtered.last().
    val SPLINE_STEPS = 6
    val splined: List<GeoPoint> = if (filtered.size >= 3) {
        buildList {
            val pts: List<GeoPoint> = buildList {
                add(filtered.first())   // phantom start
                addAll(filtered)
                add(filtered.last())    // phantom end
            }
            // Iterate real segments: pts[1]→pts[2], pts[2]→pts[3], …
            for (i in 1..pts.size - 3) {
                val p0 = pts[i - 1]; val p1 = pts[i]
                val p2 = pts[i + 1]; val p3 = pts[i + 2]
                for (step in 0 until SPLINE_STEPS) {
                    val t  = step / SPLINE_STEPS.toDouble()
                    val t2 = t * t; val t3 = t2 * t
                    val lat = 0.5 * (
                        2.0 * p1.latitude +
                        (-p0.latitude + p2.latitude) * t +
                        (2.0 * p0.latitude - 5.0 * p1.latitude + 4.0 * p2.latitude - p3.latitude) * t2 +
                        (-p0.latitude + 3.0 * p1.latitude - 3.0 * p2.latitude + p3.latitude) * t3
                    )
                    val lng = 0.5 * (
                        2.0 * p1.longitude +
                        (-p0.longitude + p2.longitude) * t +
                        (2.0 * p0.longitude - 5.0 * p1.longitude + 4.0 * p2.longitude - p3.longitude) * t2 +
                        (-p0.longitude + 3.0 * p1.longitude - 3.0 * p2.longitude + p3.longitude) * t3
                    )
                    add(GeoPoint(lat, lng))
                }
            }
            add(filtered.last()) // always close on the final anchor
        }
    } else filtered

    return Polyline().apply {
        setPoints(splined)
        setInfoWindow(null)
    }
}

/** Haversine great-circle distance in kilometres between two [GeoPoint]s. */
private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val r    = 6371.0
    val dLat = Math.toRadians(b.latitude  - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val sinLat = Math.sin(dLat / 2).let { it * it }
    val sinLon = Math.sin(dLon / 2).let { it * it }
    val c = sinLat + Math.cos(Math.toRadians(a.latitude)) *
                     Math.cos(Math.toRadians(b.latitude)) * sinLon
    return r * 2.0 * Math.asin(Math.sqrt(c))
}
