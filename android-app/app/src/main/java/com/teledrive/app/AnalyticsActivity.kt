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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary

/**
 * ANALYTICS SCREEN - Overall Intelligence & Aggregated Performance
 * Shows overall safety score, total stats, performance breakdown
 * NO individual trip cards (those are in TripsActivity)
 */
class AnalyticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = TripStorage.getAll(context = this)
        setContent {
            AnalyticsScreen(history) { finish() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

@Composable
fun AnalyticsScreen(history: List<TripSummary>, onBack: () -> Unit) {
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
                .padding(horizontal = 20.dp), // Global safe area
            contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp) // Safe margins
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
                        "ANALYTICS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Overall Safety Score
            item {
                val avgScore = if (history.isEmpty()) 0 else history.map { it.score }.average().toInt()
                Surface(
                    color = Color.White.copy(0.03f),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.08f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "OVERALL SAFETY RATING",
                            color = Color.White.copy(0.4f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "$avgScore",
                            color = Color(0xFF00FFA3),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = when {
                                avgScore >= 90 -> "OPTIMAL"
                                avgScore >= 70 -> "GOOD"
                                else -> "FAIR"
                            },
                            color = Color(0xFF00FFA3).copy(0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Performance Breakdown
            item {
                Text(
                    "PERFORMANCE BREAKDOWN",
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
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (history.isNotEmpty()) {
                            val totalDistance = history.sumOf { it.distanceKm }
                            val avgBraking = history.map { it.brakeCount }.average()
                            val avgAccel = history.map { it.accelCount }.average()
                            val avgInstability = history.map { it.unstableCount }.average()

                            PerformanceMetric(
                                label = "Total Distance",
                                value = String.format(java.util.Locale.US, "%.1f", totalDistance),
                                unit = "km",
                                status = "TRACKED"
                            )
                            PerformanceMetric(
                                label = "Avg Braking Events",
                                value = avgBraking.toInt().toString(),
                                unit = "per trip",
                                status = when {
                                    avgBraking < 3 -> "EXCELLENT"
                                    avgBraking < 6 -> "GOOD"
                                    else -> "NEEDS IMPROVEMENT"
                                }
                            )
                            PerformanceMetric(
                                label = "Avg Acceleration Events",
                                value = avgAccel.toInt().toString(),
                                unit = "per trip",
                                status = when {
                                    avgAccel < 3 -> "SMOOTH"
                                    avgAccel < 6 -> "MODERATE"
                                    else -> "AGGRESSIVE"
                                }
                            )
                            PerformanceMetric(
                                label = "Instability Events",
                                value = avgInstability.toInt().toString(),
                                unit = "per trip",
                                status = when {
                                    avgInstability < 2 -> "STABLE"
                                    avgInstability < 5 -> "MODERATE"
                                    else -> "UNSTABLE"
                                }
                            )
                        } else {
                            Text(
                                "No data available",
                                color = Color.White.copy(0.3f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Performance Trend Graph
            item {
                Text(
                    "PERFORMANCE TREND",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                PremiumGraph(
                    data = history.takeLast(7).map { it.score },
                    progress = animatedProgress.value
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Insights section
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "DRIVING INSIGHTS",
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
                            val avgScore = history.map { it.score }.average().toInt()
                            val trend = if (history.size >= 2) {
                                val recent = history.takeLast(3).map { it.score }.average()
                                val older = history.dropLast(3).takeLast(3).map { it.score }.average()
                                if (recent > older) "improving" else if (recent < older) "declining" else "stable"
                            } else {
                                "stable"
                            }

                            InsightRow(
                                icon = "📊",
                                text = "Your driving score is $trend over recent trips"
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            if (avgScore >= 85) {
                                InsightRow(
                                    icon = "✨",
                                    text = "Excellent performance! Keep maintaining this standard"
                                )
                            } else {
                                InsightRow(
                                    icon = "💡",
                                    text = "Focus on smoother acceleration and braking for better scores"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InsightRow(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = icon,
            fontSize = 20.sp
        )
        Text(
            text = text,
            color = Color.White.copy(0.8f),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

