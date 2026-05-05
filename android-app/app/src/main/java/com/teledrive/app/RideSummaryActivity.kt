package com.teledrive.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.core.RideSessionManager
import com.teledrive.app.services.SensorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.text.uppercase
import androidx.compose.ui.platform.LocalContext
import com.teledrive.app.triphistory.TripStorage
import androidx.compose.ui.graphics.StrokeJoin
import com.teledrive.app.triphistory.TripSummary
import com.teledrive.app.triphistory.TripTrend
import android.app.Activity
import android.content.Intent
import com.teledrive.app.evidence.EvidenceManager
import com.teledrive.app.evidence.EventEvidenceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Image helpers ─────────────────────────────────────────────────────────────

/**
 * Decode a JPEG from [path] and apply any EXIF rotation so the bitmap is
 * always upright. Returns null if the file cannot be read.
 */
private fun loadCorrectedBitmap(path: String): Bitmap? = try {
    val raw = BitmapFactory.decodeFile(path) ?: return null
    val exif = ExifInterface(path)
    val rotation = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) raw
    else {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
} catch (e: Exception) { null }

class RideSummaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = RideSessionManager.lastSession ?: run {
            finish()
            return
        }
        val finalTip = com.teledrive.app.intelligence.InsightEngine.generateTripInsight(
            score         = session.finalScore,
            accelCount    = session.harshAccelerationCount,
            brakeCount    = session.harshBrakingCount,
            unstableCount = session.unstableRideCount,
            distanceKm    = session.distanceKm.toDouble(),
            durationMs    = session.rideDuration
        )

        // 🛡️ CRITICAL FIX: Extract data from Intent (Sent by SensorService)
        val fuelUsed = intent.getFloatExtra("fuelUsed", 0.0f)
        val moneyLost = intent.getFloatExtra("moneyLost", 0.0f)
        val finalScore = intent.getIntExtra("score", session.finalScore)
        val finalDistance = intent.getFloatExtra("distance", session.distanceKm)
        val imagePath = intent.getStringExtra("imagePath") ?: SensorService.lastCapturedImagePath
        val tip = intent.getStringExtra("tip")


        val durationMillis = session.endTime - session.startTime
        val totalSeconds = durationMillis / 1000
        val durationFormatted = "${totalSeconds / 60}m ${totalSeconds % 60}s"
        val avgSpeed = if (totalSeconds > 0) finalDistance / (totalSeconds / 3600f) else 0f

        val scoreColor = when {
            finalScore >= 90 -> Color(0xFF00FFA3)  // Green
            finalScore >= 75 -> Color(0xFFFFD60A)  // Yellow
            else             -> Color(0xFFFF2D55)  // Red
        }
        setContent {
            SummaryScreen(
                score = finalScore,
                scoreColor = scoreColor,
                stats = listOf(
                    "Distance" to "%.2f km".format(finalDistance),
                    "Avg Speed" to "%.1f km/h".format(avgSpeed),
                    "Fuel used" to "%.3f L".format(fuelUsed),
                    "Duration" to durationFormatted
                ),
                moneyLost = moneyLost,
                events = listOf(
                    "⚡  Harsh Acceleration" to session.harshAccelerationCount,
                    "🛑  Harsh Braking" to session.harshBrakingCount,
                    "⚠️  Instability" to session.unstableRideCount
                ),
                imagePath = imagePath,
                tip = finalTip, // 👈 ADD THIS
                onClose = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    score: Int,
    scoreColor: Color,
    stats: List<Pair<String, String>>,
    moneyLost: Float,
    events: List<Pair<String, Int>>,
    imagePath: String?,
    tip: String?,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val evidenceCount by produceState(initialValue = 0) {
        value = withContext(Dispatchers.IO) { EvidenceManager.getCount(context) }
    }

    val evidenceImage by produceState<Bitmap?>(null, imagePath) {
        value = withContext(Dispatchers.IO) {
            if (imagePath == null) null else loadCorrectedBitmap(imagePath)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        // ── Blurred background glow ────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(scoreColor.copy(0.15f), Color.Transparent)),
                radius = 1200f,
                center = center.copy(y = 0f)
            )
        }

        // ── Scrollable content ─────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TELEDRIVE",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(top = 40.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // --- SCORE RING ---
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    drawArc(
                        color = Color.White.copy(0.05f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(scoreColor.copy(0.2f), scoreColor)),
                        startAngle = -90f, sweepAngle = (score / 100f) * 360f, useCenter = false,
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$score", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text(text = "SCORE", fontSize = 12.sp, color = scoreColor, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- 💰 FINANCIAL LOSS CARD (The "Cost" UI) ---
            if (moneyLost > 0.01f) {
                Surface(
                    color = Color(0xFFFF2D55).copy(0.15f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFFF2D55).copy(0.4f), RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("FINANCIAL LOSS", color = Color(0xFFFF2D55), fontSize = 11.sp, fontWeight = FontWeight.Black)
                            Text("Aggressive driving waste", color = Color.White.copy(0.6f), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "₹${"%.2f".format(moneyLost)}",
                            color = Color(0xFFFF2D55),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // --- STATS GRID ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stats[0].first, stats[0].second, Modifier.weight(1f))
                StatCard(stats[1].first, stats[1].second, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stats[2].first, stats[2].second, Modifier.weight(1f))
                StatCard(stats[3].first, stats[3].second, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(40.dp))

            // --- BEHAVIOR ANALYSIS ---
            Text("BEHAVIOR ANALYSIS", Modifier.align(Alignment.Start), color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = Color.White.copy(0.02f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(24.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    events.forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(event.first, color = Color.White.copy(0.7f), fontSize = 14.sp)
                            Text(
                                text = event.second.toString(),
                                color = if(event.second > 0) Color(0xFFFF2D55) else Color.White,
                                fontWeight = FontWeight.ExtraBold, fontSize = 18.sp
                            )
                        }
                    }
                    if (events.all { it.second == 0 }) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\u2713  Smooth and controlled ride",
                            color = Color(0xFF00FFA3),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            val tipColor = when {
                tip?.contains("Accelerate", ignoreCase = true) == true -> Color(0xFFFFD60A) // Yellow
                tip?.contains("braking", ignoreCase = true) == true -> Color(0xFFFF2D55) // Red
                tip?.contains("vibrations", ignoreCase = true) == true -> Color(0xFF00E5FF) // Cyan
                tip?.contains("steadily", ignoreCase = true) == true -> Color(0xFF00E5FF) // Cyan
                else -> Color(0xFF00FFA3) // Green
            }
            // --- DRIVING INSIGHT ---
            if (!tip.isNullOrBlank()) {

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "DRIVING INSIGHT",
                    modifier = Modifier.align(Alignment.Start),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = Color(0xFF111827),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, tipColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                ) {
                    Text(
                        text = tip,
                        modifier = Modifier.padding(20.dp),
                        color = tipColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // --- EVIDENCE IMAGE ---
            evidenceImage?.let { bmp ->
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    "EVENT EVIDENCE",
                    modifier = Modifier.align(Alignment.Start),
                    color = Color.White.copy(0.4f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Evidence",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (evidenceCount > 0) {
                Button(
                    onClick = { context.startActivity(Intent(context, EventEvidenceActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2333)),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(0.3f))
                ) {
                    Text(
                        text = "\uD83D\uDCF8  Event Evidence ($evidenceCount event${if (evidenceCount != 1) "s" else ""})",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    val intent = Intent(context, TripDetailsActivity::class.java)
                    context.startActivity(intent)
                    if (context is Activity) {
                        context.overridePendingTransition(
                            R.anim.slide_in_right,
                            R.anim.slide_out_left
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
            ) {
                Text(
                    "VIEW TRIP HISTORY",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("BACK TO DASHBOARD", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))
        } // end scrollable Column
    } // end root Box
}
@Composable
fun StatCard(label: String, value: String, modifier: Modifier) {
    Surface(
        color = Color.White.copy(0.03f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.border(1.dp, Color.White.copy(0.04f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistorySheet() {
    val context = LocalContext.current
    val history = TripStorage.getAll(context)
    val trend = remember(history) {
        if (history.size < 2) TripTrend.STABLE
        else if (history.last().score > history[history.size - 2].score) TripTrend.IMPROVING
        else TripTrend.DECLINING
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        // Header & Trend Insight
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Analytics", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    text = "${trend.message} ${trend.icon}",
                    color = if (trend == TripTrend.IMPROVING) Color(0xFF00FFA3) else Color.White.copy(0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Premium Graph Container
        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Last 7 Trips", color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(16.dp))
                PremiumScoreGraph(history)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("Recent Trips", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Performance Note: Using LazyColumn inside a sheet is efficient for long lists
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            items(history.reversed()) { trip ->
                TripHistoryItem(trip)
            }
        }
    }
}


@Composable
fun PremiumScoreGraph(trips: List<TripSummary>, modifier: Modifier = Modifier) {
    val scores = trips.takeLast(7).map { it.score.toFloat() }
    if (scores.isEmpty()) return

    val zipLineColor = Color(0xFF00E5FF) // Cyber Cyan

    Canvas(modifier = modifier
        .fillMaxWidth()
        .height(150.dp)) {
        val width = size.width
        val height = size.height
        val spacing = width / (scores.size - 1).coerceAtLeast(1)
        val maxScore = 100f

        val points = scores.mapIndexed { index, score ->
            Offset(index * spacing, height - (score / maxScore * height))
        }

        // 1. Draw Area Gradient (The "Premium" Look)
        val fillPath = Path().apply {
            moveTo(0f, height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(zipLineColor.copy(alpha = 0.3f), Color.Transparent)
            )
        )

        // 2. Draw Smooth Line
        val strokePath = Path().apply {
            points.forEachIndexed { i, pt ->
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(
                    pt.x,
                    pt.y
                )
            }
        }
        drawPath(
            path = strokePath,
            color = zipLineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 3. Draw Glowing Points
        points.forEach { pt ->
            drawCircle(
                zipLineColor.copy(alpha = 0.2f),
                radius = 8.dp.toPx(),
                center = pt
            ) // Outer Glow
            drawCircle(Color.White, radius = 3.dp.toPx(), center = pt) // Inner Core
        }
    }
}
@Composable
fun TripHistoryItem(trip: TripSummary) {
    val scoreColor = when {
        trip.score >= 90 -> Color(0xFF00FFA3)  // Green
        trip.score >= 75 -> Color(0xFFFFD60A)  // Yellow
        else             -> Color(0xFFFF2D55)  // Red
    }
    
    // Format duration
    val durationMinutes = (trip.durationMs / 1000 / 60).toInt()
    val durationSeconds = ((trip.durationMs / 1000) % 60).toInt()
    val durationText = if (durationMinutes > 0) {
        "${durationMinutes}m ${durationSeconds}s"
    } else {
        "${durationSeconds}s"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = Color.White.copy(0.03f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Score Badge
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(scoreColor.copy(alpha = 0.15f), CircleShape)
                    .border(2.dp, scoreColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${trip.score}",
                        color = scoreColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    Text(
                        "SCORE",
                        color = scoreColor.copy(0.6f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Trip Details
            Column(modifier = Modifier.weight(1f)) {
                // FROM → TO
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        trip.startLocationName ?: "Unknown Location",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        " → ",
                        color = Color(0xFF00FFA3),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        trip.endLocationName ?: "Unknown Location",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Distance & Duration
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%.2f km".format(trip.distanceKm),
                        color = Color.White.copy(0.6f),
                        fontSize = 12.sp
                    )
                    Text(
                        " • ",
                        color = Color.White.copy(0.3f),
                        fontSize = 12.sp
                    )
                    Text(
                        durationText,
                        color = Color.White.copy(0.6f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Driving Insight (tip)
                trip.tip?.let { tip ->
                    Text(
                        text = "\"$tip\"",
                        color = Color(0xFF00FFA3).copy(0.7f),
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 1
                    )
                }
            }
        }
    }
}