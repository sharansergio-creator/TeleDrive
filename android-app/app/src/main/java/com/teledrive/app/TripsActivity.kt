package com.teledrive.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary
import com.teledrive.app.intelligence.InsightEngine

/**
 * TRIPS SCREEN - Trip History + Drill Down
 * Shows list of recorded trips (REAL DATA ONLY)
 * On click: Navigate to TripDetailActivity
 */
class TripsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = TripStorage.getAll(context = this)
        setContent {
            TripsScreen(history, onBack = { finish() }, onTripClick = { trip ->
                val intent = Intent(this, TripDetailActivity::class.java)
                intent.putExtra("trip_id", trip.id)
                startActivity(intent)
            })
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

@Composable
fun TripsScreen(
    history: List<TripSummary>,
    onBack: () -> Unit,
    onTripClick: (TripSummary) -> Unit
) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animatedProgress.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {
        // Background gradient
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF00E5FF).copy(0.05f), Color.Transparent)
                ),
                radius = 800f,
                center = center.copy(y = center.y * 1.2f)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)
        ) {
            // Header
            item {
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
                        "TRIP HISTORY",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Trip count summary
            item {
                Surface(
                    color = Color.White.copy(0.03f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.08f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "TOTAL TRIPS",
                                color = Color.White.copy(0.4f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${history.size}",
                                color = Color(0xFF00FFA3),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        if (history.isNotEmpty()) {
                            val avgScore = history.map { it.score }.average().toInt()
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "AVG SCORE",
                                    color = Color.White.copy(0.4f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "$avgScore",
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Trip list header
            item {
                Text(
                    "ALL TRIPS",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Trip list or empty state
            if (history.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No trips recorded yet",
                            color = Color.White.copy(0.3f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Start your first trip from the dashboard",
                            color = Color.White.copy(0.2f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                // FIX: TripStorage.getAll() already returns DESC (newest first).
                // Calling .reversed() was flipping it to ASC — oldest trips at top.
                items(history, key = { it.id }) { trip ->
                    TripHistoryCard(trip, onClick = { onTripClick(trip) })
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun TripHistoryCard(trip: TripSummary, onClick: () -> Unit) {
    // ── Timestamps ───────────────────────────────────────────────────────────
    // trip.timestamp = moment the trip was saved (≈ end of ride).
    // startTime = timestamp − durationMs  (the ride start moment).
    val endTimeMs   = trip.timestamp
    val startTimeMs = trip.timestamp - trip.durationMs.coerceAtLeast(0L)
    val timeFmt     = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    val startStr    = timeFmt.format(java.util.Date(startTimeMs))
    val endStr      = timeFmt.format(java.util.Date(endTimeMs))

    // ── Date label: TODAY / YESTERDAY / "MON, APR 7" ─────────────────────────
    val cal         = java.util.Calendar.getInstance()
    val todayMid    = cal.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0);      set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayMid = todayMid - 86_400_000L
    val dateLabel = when {
        startTimeMs >= todayMid     -> "TODAY"
        startTimeMs >= yesterdayMid -> "YESTERDAY"
        else -> java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
                    .format(java.util.Date(startTimeMs))
                    .uppercase(java.util.Locale.getDefault())
    }

    // ── Duration ──────────────────────────────────────────────────────────────
    val durationMin  = (trip.durationMs / 1000 / 60).toInt()
    val durationText = when {
        durationMin < 1  -> "< 1 min"
        durationMin < 60 -> "$durationMin min"
        else             -> "${durationMin / 60}h ${durationMin % 60}m"
    }

    // ── Trip title ────────────────────────────────────────────────────────────
    val startName = trip.startLocationName
    val endName   = trip.endLocationName
    val tripTitle = when {
        startName != null && endName != null && startName != endName -> "$startName \u2192 $endName"
        startName != null && endName != null                          -> "Local Ride \u00b7 $startName"
        startName != null                                             -> "Ride from $startName"
        endName != null                                               -> "Trip to $endName"
        trip.startLat != 0.0 || trip.startLng != 0.0                -> "Recorded Ride"
        else                                                          -> "Trip"
    }

    // ── Score color ───────────────────────────────────────────────────────────
    val scoreColor = when {
        trip.score >= 90 -> Color(0xFF00FFA3)
        trip.score >= 70 -> Color(0xFFFFD60A)
        else             -> Color(0xFFFF2D55)
    }
    val quickInsight = InsightEngine.generateQuickInsight(trip)

    Surface(
        color  = Color.White.copy(0.05f),
        shape  = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.1f)),
        modifier = Modifier
            .clickable(onClick = onClick)
            .shadow(
                elevation    = 3.dp,
                shape        = RoundedCornerShape(18.dp),
                spotColor    = Color.Black.copy(0.15f),
                ambientColor = Color.Black.copy(0.1f)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {

            // ── Row 1: Score badge + Content column + Chevron ────────────────
            // Align Top so the score circle anchors to the title, not the middle of
            // a potentially two-line title block.
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Score badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(scoreColor.copy(0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${trip.score}",
                        color      = scoreColor,
                        fontWeight = FontWeight.Black,
                        fontSize   = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // Content column — occupies all remaining width.
                // Date chip is above the title so the title never has to share its row.
                Column(modifier = Modifier.weight(1f)) {

                    // Date chip — standalone, never competes with title text
                    Surface(
                        color = when (dateLabel) {
                            "TODAY"     -> Color(0xFF00FFA3).copy(0.15f)
                            "YESTERDAY" -> Color(0xFF0A84FF).copy(0.15f)
                            else        -> Color.White.copy(0.07f)
                        },
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            dateLabel,
                            color = when (dateLabel) {
                                "TODAY"     -> Color(0xFF00FFA3)
                                "YESTERDAY" -> Color(0xFF4FC3F7)
                                else        -> Color.White.copy(0.5f)
                            },
                            fontSize      = 9.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier      = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))

                    // Title — full column width, wraps to 2 lines for long place names.
                    // e.g. "Mangaluru → Kankanady" fits on one line; very long names wrap
                    // rather than getting cut with "...".
                    Text(
                        tripTitle,
                        color      = Color.White,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        lineHeight = 21.sp
                    )
                    Spacer(modifier = Modifier.height(5.dp))

                    // Time range row: "11:09 AM – 11:11 AM · 2 min"
                    val timeRow = if (trip.durationMs > 0L)
                        "$startStr \u2013 $endStr \u00b7 $durationText"
                    else
                        "$startStr \u00b7 $durationText"
                    Text(
                        timeRow,
                        color      = Color.White.copy(0.55f),
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    // Distance on its own line for clear hierarchy
                    Text(
                        "${String.format("%.2f", trip.distanceKm)} km",
                        color    = Color.White.copy(0.38f),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                // Chevron — align to top of content block
                Text(
                    "\u203a",
                    color      = Color.White.copy(0.25f),
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Row 2: Event pills ────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EventPill("\u26a1", trip.accelCount, Color(0xFFFFD60A))
                EventPill("\uD83D\uDED1", trip.brakeCount, Color(0xFFFF2D55))
                EventPill("\u26a0\uFE0F", trip.unstableCount, Color(0xFFFF9800))
            }

            // ── Row 3: Insight ────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text      = quickInsight,
                color     = scoreColor.copy(0.75f),
                fontSize  = 11.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                maxLines  = 2,
                overflow  = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EventPill(icon: String, count: Int, color: Color) {
    val bgAlpha = if (count > 0) 0.12f else 0.05f
    val textAlpha = if (count > 0) 1f else 0.3f
    Surface(
        color = color.copy(bgAlpha),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, color.copy(if (count > 0) 0.35f else 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, fontSize = 11.sp)
            Text(
                "$count",
                color = color.copy(textAlpha),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

