package com.teledrive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import com.teledrive.app.triphistory.TripEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.sqrt

/**
 * A cluster of one or more TripEvents that are spatially close at the current zoom level.
 *
 * Single-event clusters (size == 1) render as the standard colored letter marker.
 * Multi-event clusters render as a larger bubble showing the event count.
 */
data class EventCluster(
    val center: GeoPoint,
    val events: List<TripEvent>,
    val dominantType: String   // most common event type — drives the cluster bubble color
)

/**
 * Groups [events] into clusters based on screen-pixel proximity at the current map zoom.
 *
 * Algorithm: single-pass greedy scan. O(n²) — perfectly acceptable for typical trip
 * sizes (<100 events). Events within [thresholdPx] of the first unclustered event in
 * a group are merged into that group.
 *
 * Requires the MapView to be laid out (non-zero width/height). Callers should check
 * mapView.width > 0 before calling this function.
 */
fun clusterEvents(
    mapView:     MapView,
    events:      List<TripEvent>,
    thresholdPx: Float = 56f
): List<EventCluster> {
    val validEvents = events.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    if (validEvents.isEmpty()) return emptyList()

    val projection  = mapView.projection
    val pixelPoints = validEvents.map { ev ->
        val pt = Point()
        projection.toPixels(GeoPoint(ev.latitude, ev.longitude), pt)
        pt to ev
    }

    val assigned = BooleanArray(pixelPoints.size) { false }
    val clusters = mutableListOf<EventCluster>()

    for (i in pixelPoints.indices) {
        if (assigned[i]) continue
        val group = mutableListOf(pixelPoints[i].second)
        assigned[i] = true

        for (j in (i + 1) until pixelPoints.size) {
            if (assigned[j]) continue
            val dx = (pixelPoints[i].first.x - pixelPoints[j].first.x).toFloat()
            val dy = (pixelPoints[i].first.y - pixelPoints[j].first.y).toFloat()
            if (sqrt(dx * dx + dy * dy) <= thresholdPx) {
                group.add(pixelPoints[j].second)
                assigned[j] = true
            }
        }

        val centerLat = group.map { it.latitude }.average()
        val centerLng = group.map { it.longitude }.average()
        val dominant  = group.groupBy { it.type }.maxByOrNull { it.value.size }!!.key
        clusters.add(EventCluster(GeoPoint(centerLat, centerLng), group, dominant))
    }

    return clusters
}

/**
 * Draws a cluster bubble: a semi-transparent outer ring + solid inner circle with
 * a white bold event count centred inside. The color is driven by [dominantType].
 */
fun createClusterIcon(
    context:      Context,
    count:        Int,
    dominantType: String
): BitmapDrawable {
    val baseColor = when (dominantType) {
        "HARSH_ACCELERATION" -> android.graphics.Color.parseColor("#FF2D55")
        "HARSH_BRAKING"      -> android.graphics.Color.parseColor("#FF9500")
        else                 -> android.graphics.Color.parseColor("#FFD60A")
    }
    val density = context.resources.displayMetrics.density
    val sizePx  = (52 * density + 0.5f).toInt()
    val bmp     = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas  = android.graphics.Canvas(bmp)
    val cx      = sizePx / 2f
    val cy      = sizePx / 2f
    val outerR  = cx - density
    val innerR  = outerR * 0.72f

    // Semi-transparent outer ring gives "bubble" depth
    canvas.drawCircle(cx, cy, outerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor; alpha = 60 })

    // Solid inner circle
    canvas.drawCircle(cx, cy, innerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor })

    // White border on inner circle
    canvas.drawCircle(cx, cy, innerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = 2f * density
        })

    // Bold event count centred inside
    canvas.drawText(
        count.toString(), cx, cy + innerR * 0.38f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = android.graphics.Color.WHITE
            textAlign      = android.graphics.Paint.Align.CENTER
            textSize       = innerR * 0.82f
            isFakeBoldText = true
        }
    )

    return BitmapDrawable(context.resources, bmp)
}
