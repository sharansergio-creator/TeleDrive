package com.teledrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke

class TripDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = TripStorage.getAll(context = this)
        setContent {
            TripDetailsScreen(history) { finish() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

@Composable
fun TripDetailsScreen(history: List<TripSummary>, onBack: () -> Unit) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animatedProgress.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF00E5FF).copy(0.05f), Color.Transparent)),
                radius = 800f,
                center = center.copy(y = center.y * 1.2f)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack, modifier = Modifier.background(Color.White.copy(0.05f), CircleShape)) {
                        Text("←", color = Color.White, fontSize = 20.sp)
                    }
                    Text("ANALYTICS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Spacer(modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // SCORE HERO
            item {
                val avgScore = if (history.isEmpty()) 0 else history.map { it.score }.average().toInt()
                Surface(color = Color.White.copy(0.03f), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, Color.White.copy(0.08f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("OVERALL SAFETY RATING", color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "$avgScore", color = Color(0xFF00FFA3), fontSize = 72.sp, fontWeight = FontWeight.Black)
                        Text(text = "OPTIMAL", color = Color(0xFF00FFA3).copy(0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // PERFORMANCE BREAKDOWN
            item {
                Text("PERFORMANCE BREAKDOWN", color = Color.White.copy(0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    color = Color.White.copy(0.03f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.08f))
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (history.isNotEmpty()) {
                            val avgScore = history.map { it.score }.average().toInt()
                            val totalDistance = history.sumOf { it.distanceKm }
                            val avgBraking = history.map { it.brakeCount }.average()
                            val avgAccel = history.map { it.accelCount }.average()
                            
                            PerformanceMetric(
                                label = "Total Distance",
                                value = String.format(java.util.Locale.US, "%.1f", totalDistance),
                                unit = "km",
                                status = "TRACKED"
                            )
                            PerformanceMetric(
                                label = "Braking Events",
                                value = avgBraking.toInt().toString(),
                                unit = "avg/trip",
                                status = when {
                                    avgBraking < 3 -> "EXCELLENT"
                                    avgBraking < 6 -> "GOOD"
                                    else -> "NEEDS IMPROVEMENT"
                                }
                            )
                            PerformanceMetric(
                                label = "Acceleration Events",
                                value = avgAccel.toInt().toString(),
                                unit = "avg/trip",
                                status = when {
                                    avgAccel < 3 -> "SMOOTH"
                                    avgAccel < 6 -> "MODERATE"
                                    else -> "AGGRESSIVE"
                                }
                            )
                            PerformanceMetric(
                                label = "Overall Safety",
                                value = avgScore.toString(),
                                unit = "score",
                                status = when {
                                    avgScore >= 90 -> "OPTIMAL"
                                    avgScore >= 70 -> "GOOD"
                                    else -> "FAIR"
                                }
                            )
                        } else {
                            Text("No data available", color = Color.White.copy(0.3f), fontSize = 14.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // GRAPH SECTION
            item {
                Text("PERFORMANCE TREND", color = Color.White.copy(0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))
                PremiumGraph(data = history.takeLast(7).map { it.score }, progress = animatedProgress.value)
                Spacer(modifier = Modifier.height(40.dp))
            }

            item {
                Text("RIDE HISTORY", color = Color.White.copy(0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (history.isEmpty()) {
                item {
                    Text("No trips recorded yet", color = Color.White.copy(0.3f), fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                }
            } else {
                items(history.reversed()) { trip ->
                    TripHistoryItemPremium(trip)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun TripHistoryItemPremium(trip: TripSummary) {
    // Format timestamp for fallback
    val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
    val tripDate = dateFormat.format(java.util.Date(trip.timestamp))
    
    // Use real location data or fallback
    val tripTitle = when {
        trip.startLocationName != null && trip.endLocationName != null -> 
            "${trip.startLocationName} → ${trip.endLocationName}"
        trip.endLocationName != null -> 
            "Trip to ${trip.endLocationName}"
        else -> 
            "Trip on $tripDate"
    }
    
    // Calculate duration from durationMs (convert to minutes)
    val durationMin = (trip.durationMs / 1000 / 60).toInt()
    val durationText = when {
        durationMin < 1 -> "< 1 min"
        durationMin < 60 -> "$durationMin min"
        else -> "${durationMin / 60}h ${durationMin % 60}m"
    }
    
    Surface(
        color = Color.White.copy(0.04f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.08f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // Score badge
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        when {
                            trip.score >= 90 -> Color(0xFF00FFA3).copy(0.12f)
                            trip.score >= 70 -> Color(0xFFFFD60A).copy(0.12f)
                            else -> Color(0xFFFF2D55).copy(0.12f)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${trip.score}",
                        color = when {
                            trip.score >= 90 -> Color(0xFF00FFA3)
                            trip.score >= 70 -> Color(0xFFFFD60A)
                            else -> Color(0xFFFF2D55)
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tripTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${String.format("%.2f", trip.distanceKm)} km • $durationText",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(">", color = Color.White.copy(0.2f), fontSize = 20.sp)
        }
    }
}

@Composable
fun PerformanceMetric(label: String, value: String, unit: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = status,
                color = when {
                    status.contains("EXCELLENT") || status.contains("SAFE") || status.contains("OPTIMAL") || status.contains("SMOOTH") -> Color(0xFF00FFA3)
                    status.contains("GOOD") || status.contains("MODERATE") -> Color(0xFFFFD60A)
                    else -> Color(0xFFFF2D55)
                }.copy(0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                color = Color.White.copy(0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
fun PremiumGraph(data: List<Int>, progress: Float) {
    if (data.isEmpty()) return
    val maxValue = 100f
    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val width = size.width
        val height = size.height
        val stepX = width / (data.size.coerceAtLeast(2) - 1)
        val path = Path()
        val fillPath = Path()

        data.forEachIndexed { index, value ->
            val x = stepX * index * progress
            val y = height - (value / maxValue) * height
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(width * progress, height)
        fillPath.close()

        drawPath(path = fillPath, brush = Brush.verticalGradient(listOf(Color(0xFF00E5FF).copy(0.2f), Color.Transparent)))
        drawPath(path = path, color = Color(0xFF00E5FF), style = Stroke(width = 6f, cap = StrokeCap.Round))
    }
}
