package com.teledrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.teledrive.app.triphistory.TripEvent
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * TRIP DETAIL SCREEN - Deep Dive into Individual Trip
 * This screen must feel intelligent and insightful
 * Shows: Summary, Behavior Analysis, Fuel Impact, Driving Insight
 */
class TripDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val tripId = intent.getStringExtra("trip_id")
        val history = TripStorage.getAll(context = this)
        val trip = history.find { it.id == tripId }
        
        if (trip == null) {
            finish()
            return
        }
        
        setContent {
            TripDetailScreen(trip) { finish() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

@Composable
fun TripDetailScreen(trip: TripSummary, onBack: () -> Unit) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animatedProgress.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
    }

    // Format data
    val dateFormat = java.text.SimpleDateFormat("EEEE, MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault())
    val tripDate = dateFormat.format(java.util.Date(trip.timestamp))
    
    val durationMin = (trip.durationMs / 1000 / 60).toInt()
    val durationText = when {
        durationMin < 1 -> "< 1 min"
        durationMin < 60 -> "$durationMin minutes"
        else -> "${durationMin / 60}h ${durationMin % 60}m"
    }
    // Start time = trip saved-time minus duration (trip.timestamp ≈ end of ride)
    val startTimeMs  = trip.timestamp - trip.durationMs.coerceAtLeast(0L)
    val timeFmt      = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    val startTimeStr = timeFmt.format(java.util.Date(startTimeMs))

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {
        // Background gradient
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            val scoreColor = when {
                trip.score >= 90 -> Color(0xFF00FFA3)
                trip.score >= 70 -> Color(0xFFFFD60A)
                else -> Color(0xFFFF2D55)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(scoreColor.copy(0.08f), Color.Transparent)
                ),
                radius = 900f,
                center = center.copy(y = 0f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(Color.White.copy(0.05f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    "TRIP DETAILS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            // === SECTION 1: SUMMARY ===
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.1f)),
                modifier = Modifier.shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = Color.Black.copy(0.15f),
                    ambientColor = Color.Black.copy(0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Score Ring
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp)
                    ) {
                        val scoreColor = when {
                            trip.score >= 90 -> Color(0xFF00FFA3)
                            trip.score >= 70 -> Color(0xFFFFD60A)
                            else -> Color(0xFFFF2D55)
                        }
                        
                        Canvas(modifier = Modifier.size(140.dp)) {
                            drawArc(
                                color = Color.White.copy(0.05f),
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = scoreColor,
                                startAngle = 135f,
                                sweepAngle = (trip.score / 100f) * 270f,
                                useCenter = false,
                                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "ECO SCORE",
                                color = Color.White.copy(0.4f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "${trip.score}",
                                color = scoreColor,
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Trip metadata
                    Text(
                        tripDate,
                        color = Color.White.copy(0.5f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    // Route title — shown when geocoded location names are available
                    run {
                        val sName = trip.startLocationName
                        val eName = trip.endLocationName
                        val routeTitle = when {
                            sName != null && eName != null && sName != eName ->
                                "$sName → $eName"
                            sName != null && eName != null ->
                                "Local Ride · $sName"
                            sName != null -> "From $sName"
                            eName != null -> "To $eName"
                            else          -> null
                        }
                        routeTitle?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                it,
                                color      = Color.White.copy(0.80f),
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Stats row — 3 columns: Start | Duration | Distance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TripSummaryStat(
                            label = "Start",
                            value = startTimeStr
                        )
                        Divider(
                            modifier = Modifier
                                .width(1.dp)
                                .height(40.dp),
                            color = Color.White.copy(0.1f)
                        )
                        TripSummaryStat(
                            label = "Duration",
                            value = durationText
                        )
                        Divider(
                            modifier = Modifier
                                .width(1.dp)
                                .height(40.dp),
                            color = Color.White.copy(0.1f)
                        )
                        TripSummaryStat(
                            label = "Distance",
                            value = "${String.format("%.2f", trip.distanceKm)} km"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === SECTION 2: BEHAVIOR ANALYSIS ===
            Text(
                "BEHAVIOR ANALYSIS",
                modifier = Modifier.align(Alignment.Start),
                color = Color.White.copy(0.4f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                color = Color.White.copy(0.03f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    BehaviorEventRow(
                        label = "Harsh Acceleration",
                        count = trip.accelCount,
                        icon = "⚡"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BehaviorEventRow(
                        label = "Harsh Braking",
                        count = trip.brakeCount,
                        icon = "🛑"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BehaviorEventRow(
                        label = "Instability",
                        count = trip.unstableCount,
                        icon = "⚠️"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === SECTION 2.5: EVENT MAP ===
            // Shown only when the trip has at least one GPS coordinate (start, end, or any event).
            // Old trips (no "events" field in JSON) safely produce null → emptyList() → hidden.
            val hasMapData = (trip.events ?: emptyList()).any { it.latitude != 0.0 || it.longitude != 0.0 } ||
                             trip.startLat != 0.0 || trip.startLng != 0.0
            if (hasMapData) {
                Text(
                    "EVENT MAP",
                    modifier = Modifier.align(Alignment.Start),
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                EventMapCard(trip = trip)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === SECTION 3: FUEL IMPACT ===
            Text(
                "FUEL IMPACT",
                modifier = Modifier.align(Alignment.Start),
                color = Color.White.copy(0.4f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = Color.White.copy(0.03f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // ── Fuel model ─────────────────────────────────────────────────────
                    // Base: distance ÷ 45 km/L (typical 125cc bike).
                    // Event penalties add % overhead on top of base.
                    // Total fuel used = base × penalty factor — this is what the display shows.
                    // The "loss due to harsh driving" is the delta above base.
                    // Root-cause fix: previous version showed ONLY the delta (tiny fractions)
                    // formatted with %.2f, causing "0.00 L" on short trips. Now we show total
                    // consumed and use smart decimal precision so any non-zero value is visible.
                    val mileageKmPerLiter = 45f
                    val baseFuelL = (trip.distanceKm / mileageKmPerLiter).toFloat()
                        .coerceAtLeast(0.001f)  // minimum 1 mL even for sub-100m entries
                    val penaltyFactor = 1.0f + (
                        trip.accelCount    * 0.025f +
                        trip.brakeCount    * 0.020f +
                        trip.unstableCount * 0.008f
                    ).coerceAtMost(0.60f) // cap at +60% over base
                    val totalFuelL   = baseFuelL * penaltyFactor
                    val wastedFuelL  = totalFuelL - baseFuelL
                    val fuelCostINR  = totalFuelL * 100f   // ₹100/L approximate

                    // Smart formatter: never show "0.00" — use enough decimal places
                    fun fmtL(v: Float) = when {
                        v < 0.01f -> String.format("%.4f L", v)
                        v < 0.1f  -> String.format("%.3f L", v)
                        v < 1.0f  -> String.format("%.2f L", v)
                        else      -> String.format("%.1f L", v)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Estimated Fuel Used",
                                color = Color.White.copy(0.5f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                fmtL(totalFuelL),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Est. Cost",
                                color = Color.White.copy(0.5f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "₹${String.format("%.0f", fuelCostINR)}",
                                color = Color(0xFFFF2D55),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Waste breakdown row — only shown when events actually occurred
                    if (wastedFuelL > 0.0005f) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Excess (harsh events)",
                                color = Color.White.copy(0.35f),
                                fontSize = 11.sp
                            )
                            Text(
                                "+${fmtL(wastedFuelL)}",
                                color = Color(0xFFFFD60A).copy(0.75f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color.White.copy(0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Contextual insight line
                    val totalEvents = trip.accelCount + trip.brakeCount + trip.unstableCount
                    val insightText = when {
                        totalEvents == 0 ->
                            "✓  Efficient ride — minimal fuel impact"
                        trip.accelCount > trip.brakeCount && trip.accelCount > 2 ->
                            "⚡  Aggressive throttle increased fuel use by ${String.format("%.0f", (penaltyFactor - 1f) * 100f)}%"
                        trip.brakeCount > 2 ->
                            "🛑  Frequent hard braking reduced efficiency"
                        wastedFuelL > baseFuelL * 0.15f ->
                            "⚠️  Harsh events added ${fmtL(wastedFuelL)} above efficient baseline"
                        else ->
                            "💡  Smoother driving could reduce fuel use slightly"
                    }
                    Text(
                        insightText,
                        color = if (totalEvents == 0) Color(0xFF00FFA3).copy(0.8f)
                                else Color(0xFFFFD60A).copy(0.75f),
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === SECTION 4: DRIVING INSIGHT ===
            trip.tip?.let { insight ->
                Text(
                    "DRIVING INSIGHT",
                    modifier = Modifier.align(Alignment.Start),
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = Color(0xFF00FFA3).copy(0.08f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FFA3).copy(0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "💡",
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            insight,
                            color = Color(0xFF00FFA3),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

@Composable
fun TripSummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            color = Color.White.copy(0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BehaviorEventRow(label: String, count: Int, icon: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                fontSize = 20.sp
            )
            Text(
                label,
                color = Color.White.copy(0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        Box(
            modifier = Modifier
                .background(
                    when {
                        count == 0 -> Color(0xFF00FFA3).copy(0.1f)
                        count < 3 -> Color(0xFFFFD60A).copy(0.1f)
                        else -> Color(0xFFFF2D55).copy(0.1f)
                    },
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "$count",
                color = when {
                    count == 0 -> Color(0xFF00FFA3)
                    count < 3 -> Color(0xFFFFD60A)
                    else -> Color(0xFFFF2D55)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EVENT MAP COMPOSABLES
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Renders an osmdroid MapView card showing start (green), end (blue), and
 * event markers (red/orange/yellow by type) for the given [TripSummary].
 *
 * Design rules:
 *  - MapView created once via remember(trip.id); never recreated on recomposition.
 *  - allPoints computed first so the mapView remember block can use it for centering.
 *  - zoomToBoundingBox deferred via post() — requires the view to be measured first.
 *  - onDetach() called on disposal to close tile providers and prevent memory leaks.
 */
@Composable
private fun EventMapCard(trip: TripSummary) {
    val context = LocalContext.current
    val events  = trip.events ?: emptyList()

    // Build complete point list once per trip (used for overlay setup + bounding box zoom)
    val allPoints: List<GeoPoint> = remember(trip.id) {
        val pts = mutableListOf<GeoPoint>()
        if (trip.startLat != 0.0 || trip.startLng != 0.0)
            pts.add(GeoPoint(trip.startLat, trip.startLng))
        if (trip.endLat != 0.0 || trip.endLng != 0.0)
            pts.add(GeoPoint(trip.endLat, trip.endLng))
        events.filter { it.latitude != 0.0 || it.longitude != 0.0 }
              .forEach { pts.add(GeoPoint(it.latitude, it.longitude)) }
        pts.toList()
    }

    // Create MapView once; rebuilds only if a different trip is shown (trip.id change)
    val mapView: MapView = remember(trip.id) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false) // preview card — full interaction via EventMapActivity

            // ── Start marker — green navigation pin ──────────────────────
            if (trip.startLat != 0.0 || trip.startLng != 0.0) {
                overlays.add(Marker(this).apply {
                    position = GeoPoint(trip.startLat, trip.startLng)
                    icon     = createMapPinIcon(context, android.graphics.Color.parseColor("#00C853"), "S", 28)
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }

            // ── End marker — red navigation pin ──────────────────────────
            if (trip.endLat != 0.0 || trip.endLng != 0.0) {
                overlays.add(Marker(this).apply {
                    position = GeoPoint(trip.endLat, trip.endLng)
                    icon     = createMapPinIcon(context, android.graphics.Color.parseColor("#FF3B30"), "E", 28)
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }

            // ── Event markers (color-coded by type) ───────────────────────
            events.filter { it.latitude != 0.0 || it.longitude != 0.0 }.forEach { ev ->
                val argb = when (ev.type) {
                    "HARSH_ACCELERATION" -> android.graphics.Color.parseColor("#FF2D55")
                    "HARSH_BRAKING"      -> android.graphics.Color.parseColor("#FF9500")
                    else                 -> android.graphics.Color.parseColor("#FFD60A") // UNSTABLE_RIDE
                }
                overlays.add(Marker(this).apply {
                    position = GeoPoint(ev.latitude, ev.longitude)
                    icon     = createMarkerIcon(context, argb, 28)
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }

            // Initial rough center — setCenter/setZoom work before view is measured
            if (allPoints.isNotEmpty()) {
                controller.setCenter(allPoints[0])
                controller.setZoom(15.0)
            }
        }
    }

    // Precisely fit all points after the MapView is measured and laid out.
    // post() queues the call after the next layout pass — safe because AndroidView
    // has already been added to the Compose hierarchy by this point.
    LaunchedEffect(trip.id) {
        if (allPoints.size >= 2) {
            val bbox = BoundingBox.fromGeoPoints(allPoints)
            mapView.post { mapView.zoomToBoundingBox(bbox, false, 100) }
        }
    }

    // Start tile-loading thread on enter; stop and release tile cache on leave.
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onDetach() }
    }

    Surface(
        color  = Color.White.copy(0.03f),
        shape  = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.08f))
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp)),
                factory  = { mapView }
            )
            if (events.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                MapLegendRow(events)
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, EventMapActivity::class.java)
                            .putExtra("trip_id", trip.id)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, Color(0xFF00FFA3).copy(alpha = 0.4f)),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFA3))
            ) {
                Text(
                    "EXPLORE EVENT MAP",
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

/** Shows only the legend dots for event types that actually occurred in this trip. */
@Composable
private fun MapLegendRow(events: List<TripEvent>) {
    val types = events.map { it.type }.toSet()
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        if ("HARSH_ACCELERATION" in types) MapLegendDot(Color(0xFFFF2D55), "\u26a1 Accel")
        if ("HARSH_BRAKING"      in types) MapLegendDot(Color(0xFFFF9500), "\ud83d\uded1 Brake")
        if ("UNSTABLE_RIDE"      in types) MapLegendDot(Color(0xFFFFD60A), "\u26a0\ufe0f Unstable")
    }
}

@Composable
private fun MapLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
        )
        Text(
            label,
            color      = Color.White.copy(0.5f),
            fontSize   = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Creates a filled coloured circle [BitmapDrawable] for use as an osmdroid [Marker] icon.
 * Uses fully-qualified [android.graphics.Canvas] to avoid shadowing Compose's [Canvas] import.
 */
private fun createMarkerIcon(context: Context, argbColor: Int, sizeDp: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx  = (sizeDp * density + 0.5f).toInt()
    val bmp     = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas  = android.graphics.Canvas(bmp)
    val r       = sizePx / 2f - density
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor })
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        })
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Teardrop navigation-pin icon with a semi-transparent drop shadow.
 * Bitmap dimensions: [sizeDp] wide × [sizeDp × 1.6] tall.
 * Use anchor ANCHOR_CENTER (X) + ANCHOR_BOTTOM (Y) so the pin tip
 * touches the geographic coordinate on the map.
 */
private fun createMapPinIcon(
    context:   Context,
    argbColor: Int,
    letter:    String,
    sizeDp:    Int
): BitmapDrawable {
    val d    = context.resources.displayMetrics.density
    val w    = (sizeDp * d + 0.5f).toInt()
    val h    = (sizeDp * 1.6f * d + 0.5f).toInt()
    val bmp  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv   = android.graphics.Canvas(bmp)
    val cx   = w / 2f
    val r    = cx - 2f * d       // circle radius (inset for border)
    val cy   = r + 2f * d        // circle centre Y
    val tipY = h.toFloat() - d   // pin tip at bottom of bitmap

    // Drop shadow: 1.5dp right, 2dp down, semi-transparent
    cv.drawPath(makePinPath(cx + 1.5f * d, cy + 2f * d, r, tipY + 2f * d),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(60, 0, 0, 0) })

    val pinPath = makePinPath(cx, cy, r, tipY)
    cv.drawPath(pinPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor })
    cv.drawPath(pinPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = android.graphics.Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 2.2f * d
    })
    cv.drawText(letter, cx, cy + r * 0.36f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = android.graphics.Color.WHITE
            textAlign      = android.graphics.Paint.Align.CENTER
            textSize       = r * 0.78f
            isFakeBoldText = true
        })
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Teardrop [Path]: clockwise arc from the left tangent (125°) sweeping 290°
 * to the right tangent (55°) — covering the top of the circle —
 * then two straight stems down to the tip.
 */
private fun makePinPath(cx: Float, cy: Float, r: Float, tipY: Float): android.graphics.Path {
    val angle = Math.toRadians(35.0)
    val tx    = (r * Math.sin(angle)).toFloat()
    val ty    = (r * Math.cos(angle)).toFloat()
    return android.graphics.Path().apply {
        moveTo(cx, tipY)
        lineTo(cx - tx, cy + ty)
        arcTo(android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 125f, 290f, false)
        lineTo(cx, tipY)
        close()
    }
}
