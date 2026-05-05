package com.teledrive.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.teledrive.app.evidence.EvidenceItem
import com.teledrive.app.evidence.EvidenceManager
import com.teledrive.app.triphistory.TripEvent
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.teledrive.app.location.LocationCacheManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen Event Navigator.
 *
 * Launched from TripDetailActivity via "EXPLORE EVENT MAP" button.
 * Receives "trip_id" Intent extra; loads trip from TripStorage.
 *
 * Features:
 *  - Full-screen osmdroid MapView with free pan/zoom (no scroll conflict)
 *  - Premium circle+letter markers: A=Acceleration, B=Braking, U=Unstable
 *  - Tap marker → sliding bottom panel with coaching insight + evidence thumbnail
 *  - Evidence matched to event by type + timestamp proximity (±8 seconds)
 *  - Graceful handling: no GPS data, no events, no evidence
 */
class EventMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: map renders behind the status bar for full immersion.
        // Force white (light) status icons — map tiles are always dark-ish.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val tripId = intent.getStringExtra("trip_id")
        val trip   = TripStorage.getAll(this).find { it.id == tripId }

        if (trip == null) { finish(); return }

        setContent { EventMapScreen(trip = trip, onBack = { finish() }) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventMapScreen(trip: TripSummary, onBack: () -> Unit) {
    val context = LocalContext.current
    val events  = trip.events ?: emptyList()

    // ── State ─────────────────────────────────────────────────────────────────
    val evidenceItems: List<EvidenceItem> = remember { EvidenceManager.loadAll(context) }
    val selectedEvent   = remember { mutableStateOf<TripEvent?>(null) }
    var lastSelectedEvent: TripEvent? by remember { mutableStateOf(null) }
    selectedEvent.value?.let { lastSelectedEvent = it }

    // Increments on every map zoom event → triggers cluster rebuild
    var zoomVersion by remember { mutableIntStateOf(0) }

    // Controls whether risk hotspot markers are shown on the map
    var showHotspots by remember { mutableStateOf(true) }

    // ── Pre-computed data (stable for the lifetime of this trip view) ─────────
    val analytics: RouteAnalytics      = remember(trip.id) { computeRouteAnalytics(trip) }
    val hotspots:  List<RiskHotspot>   = remember        { detectHotspots(context) }

    // Zone names for hotspot markers — resolved async once per screen open.
    // mutableStateMapOf is snapshot-aware: each new entry triggers incremental recomposition.
    val hotspotNames = remember(hotspots) { mutableStateMapOf<Int, String>() }
    LaunchedEffect(hotspots) {
        hotspots.forEachIndexed { i, hs ->
            hotspotNames[i] = LocationCacheManager.resolveLocation(
                context, hs.center.latitude, hs.center.longitude
            )
        }
    }

    val allPoints: List<GeoPoint> = remember(trip.id) {
        buildList {
            if (trip.startLat != 0.0 || trip.startLng != 0.0)
                add(GeoPoint(trip.startLat, trip.startLng))
            if (trip.endLat != 0.0 || trip.endLng != 0.0)
                add(GeoPoint(trip.endLat, trip.endLng))
            events.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                  .forEach { add(GeoPoint(it.latitude, it.longitude)) }
        }
    }

    // Route polyline: event trail start → sorted waypoints → end
    // Option B: dual-layer premium path — wide soft glow + sharp inner line.
    // Two Polyline objects stacked in the overlay list: glow first (z=1), inner line on top (z=2).
    // Both are added to overlays in the MapView remember block below.
    val routePolylineGlow: Polyline? = remember(trip.id) {
        buildRoutePolyline(trip)?.apply {
            val d = context.resources.displayMetrics.density
            outlinePaint.color       = android.graphics.Color.parseColor("#0A84FF")
            outlinePaint.strokeWidth = 14f * d     // wide outer glow
            outlinePaint.alpha       = 55           // very transparent — soft halo only
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
            outlinePaint.pathEffect  = null
        }
    }
    val routePolyline: Polyline? = remember(trip.id) {
        buildRoutePolyline(trip)?.apply {
            val d = context.resources.displayMetrics.density
            outlinePaint.color       = android.graphics.Color.parseColor("#4FC3F7")  // sky-blue
            outlinePaint.strokeWidth = 5f * d       // crisp inner line
            outlinePaint.alpha       = 215           // nearly opaque — clear traveled path
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
            outlinePaint.pathEffect  = null
        }
    }

    // Mutable marker lists — manipulated directly via osmdroid overlay API
    val eventMarkers   = remember(trip.id) { mutableListOf<Marker>() }
    val hotspotMarkers = remember(trip.id) { mutableListOf<Marker>() }

    // ── MapView created once per trip ─────────────────────────────────────────
    val mapView: MapView = remember(trip.id) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true

            // Route polylines at layers 0 & 1 — glow beneath inner line, both beneath markers
            routePolylineGlow?.let { overlays.add(0, it) }
            routePolyline?.let    { overlays.add(if (routePolylineGlow != null) 1 else 0, it) }

            // ── Start marker ──────────────────────────────────────────────
            if (trip.startLat != 0.0 || trip.startLng != 0.0) {
                overlays.add(Marker(this).apply {
                    position = GeoPoint(trip.startLat, trip.startLng)
                    icon     = createMapPinIcon(
                        context, android.graphics.Color.parseColor("#00C853"), "S", 32
                    )
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }

            // ── End marker ────────────────────────────────────────────────
            if (trip.endLat != 0.0 || trip.endLng != 0.0) {
                overlays.add(Marker(this).apply {
                    position = GeoPoint(trip.endLat, trip.endLng)
                    icon     = createMapPinIcon(
                        context, android.graphics.Color.parseColor("#FF3B30"), "E", 32
                    )
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }

            // ── Zoom listener: increment zoomVersion → triggers cluster rebuild ──
            addMapListener(object : MapListener {
                override fun onZoom(event: ZoomEvent): Boolean {
                    zoomVersion++
                    return false
                }
                override fun onScroll(event: ScrollEvent): Boolean = false
            })

            if (allPoints.isNotEmpty()) {
                controller.setCenter(allPoints[0])
                controller.setZoom(15.0)
            }
        }
    }

    // ── Cluster rebuild ───────────────────────────────────────────────────────
    // Local function captures: mapView, eventMarkers, events, selectedEvent, context.
    // If the MapView is not yet laid out (width == 0 on initial frame), the work is
    // posted to the View's message queue and retried after measure/layout completes.
    fun rebuildEventClusters() {
        if (mapView.width == 0 || mapView.height == 0) {
            mapView.post { rebuildEventClusters() }
            return
        }
        eventMarkers.forEach { mapView.overlays.remove(it) }
        eventMarkers.clear()

        val clusters = clusterEvents(mapView, events)
        for (cluster in clusters) {
            val marker = Marker(mapView).apply {
                position = cluster.center
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                if (cluster.events.size == 1) {
                    // Single event: colored letter marker + detail panel on tap
                    val ev = cluster.events[0]
                    val (argb, letter) = when (ev.type) {
                        "HARSH_ACCELERATION" -> android.graphics.Color.parseColor("#FF2D55") to "A"
                        "HARSH_BRAKING"      -> android.graphics.Color.parseColor("#FF9500") to "B"
                        else                 -> android.graphics.Color.parseColor("#FFD60A") to "U"
                    }
                    icon = createNavigatorMarkerIcon(context, argb, letter, 36)
                    setOnMarkerClickListener { _, _ ->
                        selectedEvent.value = ev
                        true
                    }
                } else {
                    // Cluster bubble: zoom in on tap to dissolve into individual markers
                    icon = createClusterIcon(context, cluster.events.size, cluster.dominantType)
                    setOnMarkerClickListener { _, _ ->
                        mapView.controller.animateTo(
                            cluster.center,
                            (mapView.zoomLevelDouble + 2.0).coerceAtMost(19.0),
                            300L
                        )
                        true
                    }
                }
            }
            eventMarkers.add(marker)
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(zoomVersion, trip.id) { rebuildEventClusters() }

    // ── Hotspot overlay rebuild ───────────────────────────────────────────────
    fun rebuildHotspotOverlay() {
        hotspotMarkers.forEach { mapView.overlays.remove(it) }
        hotspotMarkers.clear()
        if (showHotspots) {
            for (hs in hotspots) {
                val argb = when (hs.dominantType) {
                    "HARSH_ACCELERATION" -> android.graphics.Color.parseColor("#FF2D55")
                    "HARSH_BRAKING"      -> android.graphics.Color.parseColor("#FF9500")
                    else                 -> android.graphics.Color.parseColor("#FFD60A")
                }
                val m = Marker(mapView).apply {
                    position = hs.center
                    icon     = createHotspotIcon(context, argb, hs.eventCount)
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        // Zoom in so the user can explore the risk zone
                        mapView.controller.animateTo(
                            hs.center,
                            mapView.zoomLevelDouble.coerceAtLeast(16.0),
                            300L
                        )
                        true
                    }
                }
                hotspotMarkers.add(m)
                mapView.overlays.add(m)
            }
        }
        mapView.invalidate()
    }

    LaunchedEffect(showHotspots, trip.id) { rebuildHotspotOverlay() }

    // ── Initial bounding-box fit ──────────────────────────────────────────────
    LaunchedEffect(trip.id) {
        if (allPoints.size >= 2) {
            val bbox = BoundingBox.fromGeoPoints(allPoints)
            mapView.post { mapView.zoomToBoundingBox(bbox, false, 130) }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onDetach() }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {

        // Full-screen map
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

        // Tap-to-dismiss overlay — only active while detail panel is showing
        if (selectedEvent.value != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication        = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { selectedEvent.value = null }
            )
        }

        // ── Top bar: back | title | HUD ───────────────────────────────────────
        // Single unified Row. statusBarsPadding() reads the real inset height from
        // the OS so it works correctly across every device, notch, and API level.
        // weight(1f) on the center section guarantees the title chip never overlaps
        // the HUD on any screen width — they are structurally separated.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ← Back button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF070B14).copy(alpha = 0.82f), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint               = Color.White,
                    modifier           = Modifier.size(20.dp)
                )
            }

            // Title chip — occupies all space between back button and HUD, centered.
            // Hidden when the event detail panel is open to reduce visual noise.
            Box(
                modifier         = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedEvent.value == null) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color(0xFF070B14).copy(alpha = 0.78f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 18.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "EVENT NAVIGATOR",
                            color         = Color.White.copy(alpha = 0.9f),
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            // Analytics HUD — right side, natural width
            AnalyticsHud(analytics = analytics)
        }

        // ── Hotspot toggle FAB + zone insight chip (bottom-right, above legend) ──
        if (hotspots.isNotEmpty()) {
            val bottomPad = if (selectedEvent.value == null && events.isNotEmpty()) 90.dp else 24.dp
            Column(
                modifier            = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = bottomPad, end = 12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Zone insight chip — shows most active hotspot name when the layer is on
                if (showHotspots && hotspotNames.isNotEmpty()) {
                    val topIdx     = hotspots.indices.maxByOrNull { hotspots[it].eventCount } ?: 0
                    val topHotspot = hotspots[topIdx]
                    val zoneName   = hotspotNames[topIdx]
                    if (zoneName != null) {
                        val zoneColor = when (topHotspot.dominantType) {
                            "HARSH_ACCELERATION" -> Color(0xFFFF2D55)
                            "HARSH_BRAKING"      -> Color(0xFFFF9500)
                            else                 -> Color(0xFFFFD60A)
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF0E1420).copy(alpha = 0.90f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "MOST ACTIVE ZONE",
                                    color         = Color.White.copy(alpha = 0.40f),
                                    fontSize      = 8.sp,
                                    fontWeight    = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    "$zoneName  ·  ${topHotspot.eventCount}×",
                                    color      = zoneColor,
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                // FAB button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (showHotspots) Color(0xFFFF2D55).copy(alpha = 0.85f)
                            else Color(0xFF0E1420).copy(alpha = 0.9f),
                            CircleShape
                        )
                        .clickable { showHotspots = !showHotspots },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔥", fontSize = 18.sp)
                }
            }
        }

        // ── Legend (shown at bottom when no panel is open) ────────────────
        AnimatedVisibility(
            visible  = selectedEvent.value == null && events.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = slideInVertically(initialOffsetY = { it }),
            exit     = slideOutVertically(targetOffsetY = { it })
        ) {
            NavigatorLegend(events = events)
        }

        // ── Event detail panel (slides up on marker tap) ──────────────────
        AnimatedVisibility(
            visible  = selectedEvent.value != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = slideInVertically(initialOffsetY = { it }),
            exit     = slideOutVertically(targetOffsetY = { it })
        ) {
            // lastSelectedEvent persists content during the exit animation
            lastSelectedEvent?.let { ev ->
                EventDetailPanel(
                    event         = ev,
                    evidenceItems = evidenceItems,
                    onDismiss     = { selectedEvent.value = null }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Analytics HUD
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compact floating stats panel shown at the top-right corner of the map.
 * Displays: distance, event count, density per km, and a "Smooth Trip" badge.
 */
@Composable
private fun AnalyticsHud(analytics: RouteAnalytics, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier
            .background(Color(0xFF070B14).copy(alpha = 0.82f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (analytics.isSmooth) {
            Text(
                "✅ Smooth Trip",
                color      = Color(0xFF00FFA3),
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "📏 ${String.format("%.1f", analytics.distanceKm)} km",
            color    = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp
        )
        if (analytics.totalEvents > 0) {
            Text(
                "⚡ ${analytics.totalEvents} event${if (analytics.totalEvents > 1) "s" else ""}",
                color    = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp
            )
            Text(
                "📊 ${String.format("%.1f", analytics.densityPerKm)}/km",
                color    = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Event Detail Panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventDetailPanel(
    event:         TripEvent,
    evidenceItems: List<EvidenceItem>,
    onDismiss:     () -> Unit
) {
    val context = LocalContext.current

    // Match by same event type + timestamp within ±8 seconds
    val matchedEvidence: EvidenceItem? = remember(event.timestamp) {
        evidenceItems.firstOrNull { item ->
            item.eventType == event.type &&
            kotlin.math.abs(item.timestamp - event.timestamp) < 8000L
        }
    }

    // Thumbnail loaded asynchronously; reset when event changes
    var thumbnail: ImageBitmap? by remember(event.timestamp) { mutableStateOf(null) }
    LaunchedEffect(event.timestamp) {
        thumbnail = null
        matchedEvidence?.let { ev ->
            thumbnail = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(ev.file.absolutePath)?.asImageBitmap() }
                    .getOrNull()
            }
        }
    }

    val eventColor = when (event.type) {
        "HARSH_ACCELERATION" -> Color(0xFFFF2D55)
        "HARSH_BRAKING"      -> Color(0xFFFF9500)
        else                 -> Color(0xFFFFD60A)
    }
    val eventLabel = when (event.type) {
        "HARSH_ACCELERATION" -> "Harsh Acceleration"
        "HARSH_BRAKING"      -> "Harsh Braking"
        else                 -> "Unstable Ride"
    }
    val eventIcon = when (event.type) {
        "HARSH_ACCELERATION" -> "⚡"
        "HARSH_BRAKING"      -> "🛑"
        else                 -> "⚠️"
    }
    val timeStr = remember(event.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.timestamp))
    }
    val (insightBody, insightCoach) = remember(event.type, event.speedKmh) {
        getCoachingInsight(event)
    }

    // Location name resolved once per unique event coordinate.
    // null = not yet loaded. Stays null only for zero-coordinate edge cases.
    // Cache hit returns on the first composition after the LaunchedEffect fires.
    var eventLocationLabel: String? by remember(event.latitude, event.longitude) {
        mutableStateOf(null)
    }
    LaunchedEffect(event.latitude, event.longitude) {
        eventLocationLabel = if (event.latitude != 0.0 || event.longitude != 0.0)
            LocationCacheManager.resolveLocation(context, event.latitude, event.longitude)
        else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = Color(0xFF0E1420),
        shape    = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border   = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {

            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ── Header: icon + label + metadata + dismiss ─────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier         = Modifier
                            .size(38.dp)
                            .background(eventColor.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text(eventIcon, fontSize = 17.sp) }

                    Column {
                        Text(
                            eventLabel.uppercase(),
                            color         = eventColor,
                            fontSize      = 13.sp,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "$timeStr  ·  ${event.speedKmh.toInt()} km/h  ·  Severity ${
                                String.format("%.1f", event.severity)
                            }",
                            color    = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp
                        )
                        // Location label — appears once the geocoder resolves (instant on cache hit)
                        if (eventLocationLabel != null) {
                            Text(
                                "📍 $eventLocationLabel",
                                color    = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint               = Color.White.copy(alpha = 0.45f),
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Color.White.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(14.dp))

            // ── Coaching insight ──────────────────────────────────────────
            Text(
                insightBody,
                color      = Color.White.copy(alpha = 0.85f),
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                insightCoach,
                color      = eventColor.copy(alpha = 0.8f),
                fontSize   = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Color.White.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(12.dp))

            // ── Evidence section ──────────────────────────────────────────
            if (matchedEvidence != null) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                context.startActivity(
                                    Intent(context, com.teledrive.app.evidence.EventEvidenceActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnail != null) {
                            Image(
                                bitmap             = thumbnail!!,
                                contentDescription = "Event photo",
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("📷", fontSize = 22.sp)
                        }
                    }
                    Column {
                        Text(
                            "Photo Evidence",
                            color      = Color.White,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            "Tap thumbnail to view gallery",
                            color    = Color.White.copy(alpha = 0.38f),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📷", fontSize = 14.sp)
                    Text(
                        "No photo evidence for this event",
                        color    = Color.White.copy(alpha = 0.3f),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Legend
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NavigatorLegend(events: List<TripEvent>) {
    val types = events.map { it.type }.toSet()
    Row(
        modifier = Modifier
            .padding(bottom = 24.dp)
            .background(Color(0xFF070B14).copy(alpha = 0.82f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        if ("HARSH_ACCELERATION" in types) LegendPill(Color(0xFFFF2D55), "A  Acceleration")
        if ("HARSH_BRAKING"      in types) LegendPill(Color(0xFFFF9500), "B  Braking")
        if ("UNSTABLE_RIDE"      in types) LegendPill(Color(0xFFFFD60A), "U  Unstable")
    }
}

@Composable
private fun LegendPill(color: Color, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(16.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label.take(1),
                color      = Color.Black.copy(alpha = 0.8f),
                fontSize   = 8.sp,
                fontWeight = FontWeight.Black
            )
        }
        Text(
            label.drop(3),   // drop "X  " prefix — just show the type name
            color      = Color.White.copy(alpha = 0.7f),
            fontSize   = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Coaching insights
// ─────────────────────────────────────────────────────────────────────────────

private fun getCoachingInsight(event: TripEvent): Pair<String, String> {
    val kmh = event.speedKmh.toInt()
    return when (event.type) {
        "HARSH_ACCELERATION" -> when {
            kmh < 20 -> Pair(
                "Sudden acceleration from low speed detected at $kmh km/h.",
                "Build speed gradually — progressive throttle reduces fuel consumption and tyre stress."
            )
            kmh < 40 -> Pair(
                "Harsh acceleration at $kmh km/h in moderate traffic zone.",
                "Smooth, controlled throttle improves fuel efficiency by up to 15% and reduces drivetrain wear."
            )
            else -> Pair(
                "Aggressive acceleration at high speed ($kmh km/h) detected.",
                "Hard throttle at speed significantly increases fuel consumption and shortens reaction time margin."
            )
        }
        "HARSH_BRAKING" -> when {
            kmh < 20 -> Pair(
                "Sharp braking at low speed ($kmh km/h).",
                "Likely late obstacle recognition. Scan further ahead and maintain more stopping distance."
            )
            kmh < 40 -> Pair(
                "Hard braking detected at $kmh km/h.",
                "Anticipate hazards earlier — progressive braking reduces brake wear and improves ride stability."
            )
            else -> Pair(
                "Emergency-level braking at $kmh km/h — elevated risk event.",
                "At this speed, hard braking is a leading accident factor. Increase following distance substantially."
            )
        }
        else -> when { // UNSTABLE_RIDE
            kmh < 20 -> Pair(
                "Ride instability detected at $kmh km/h.",
                "Could be road surface or sudden weight shift. Check tyre pressure and avoid abrupt steering inputs."
            )
            kmh < 40 -> Pair(
                "Unstable riding pattern at $kmh km/h.",
                "Relax your grip, keep elbows slightly bent, and distribute weight evenly across the footpegs."
            )
            else -> Pair(
                "High-speed instability detected at $kmh km/h — control risk.",
                "Instability at this speed reduces control significantly. Reduce speed on unfamiliar or uneven surfaces."
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Marker icon — filled circle with white letter (premium style)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws a filled circle with:
 *  - solid [argbColor] fill
 *  - white 2.5dp border stroke
 *  - white bold [letter] centred inside
 *
 * Uses fully-qualified [android.graphics.Canvas] to avoid ambiguity with
 * Compose's Canvas import. Called inside remember{} so not hot-path.
 */
private fun createNavigatorMarkerIcon(
    context:   Context,
    argbColor: Int,
    letter:    String,
    sizeDp:    Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx  = (sizeDp * density + 0.5f).toInt()
    val bmp     = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas  = android.graphics.Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val r  = cx - density

    // Fill
    canvas.drawCircle(cx, cy, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor })

    // White border
    canvas.drawCircle(cx, cy, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        })

    // White bold letter centred inside
    canvas.drawText(
        letter, cx, cy + (r * 0.38f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = android.graphics.Color.WHITE
            textAlign      = android.graphics.Paint.Align.CENTER
            textSize       = r * 0.82f
            isFakeBoldText = true
        }
    )
    return BitmapDrawable(context.resources, bmp)
}

// ─────────────────────────────────────────────────────────────────────────────
// Hotspot icon — ring + solid inner circle with event count
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws a hotspot marker distinguishable from regular event markers:
 *  - large semi-transparent outer ring (signals "zone of risk")
 *  - coloured ring stroke on the outer circle
 *  - solid inner circle with white bold event count
 *
 * Tap zooms to the hotspot zone; the outer ring visually implies a geographic radius.
 */
private fun createHotspotIcon(
    context:   Context,
    argbColor: Int,
    count:     Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx  = (50 * density + 0.5f).toInt()
    val bmp     = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas  = android.graphics.Canvas(bmp)
    val cx      = sizePx / 2f
    val cy      = sizePx / 2f
    val outerR  = cx - density
    val innerR  = outerR * 0.60f

    // Semi-transparent outer fill — conveys "risk zone" area
    canvas.drawCircle(cx, cy, outerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor; alpha = 45 })

    // Coloured outer ring stroke
    canvas.drawCircle(cx, cy, outerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = argbColor
            style       = Paint.Style.STROKE
            strokeWidth = 2f * density
            alpha       = 160
        })

    // Solid inner circle
    canvas.drawCircle(cx, cy, innerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor })

    // White border on inner circle
    canvas.drawCircle(cx, cy, innerR,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = 2f * density
        })

    return BitmapDrawable(context.resources, bmp)
}

// ───────────────────────────────────────────────────────────────────────────────
// Premium map-pin icon for Start and End markers
// ───────────────────────────────────────────────────────────────────────────────

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

    // Drop shadow: 1.5dp right, 2dp down
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
 * then two straight stems back down to the tip.
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
