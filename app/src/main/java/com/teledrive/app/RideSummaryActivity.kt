package com.teledrive.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.core.RideSessionManager
import com.teledrive.app.services.SensorService

class RideSummaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = RideSessionManager.lastSession ?: run {
            finish()
            return
        }

        // 🛡️ CRITICAL FIX: Extract data from Intent (Sent by SensorService)
        val fuelUsed = intent.getFloatExtra("fuelUsed", 0.0f)
        val moneyLost = intent.getFloatExtra("moneyLost", 0.0f)
        val finalScore = intent.getIntExtra("score", session.finalScore)
        val finalDistance = intent.getFloatExtra("distance", session.distanceKm)
        val imagePath = intent.getStringExtra("imagePath") ?: SensorService.lastCapturedImagePath

        val durationMillis = session.endTime - session.startTime
        val totalSeconds = durationMillis / 1000
        val durationFormatted = "${totalSeconds / 60}m ${totalSeconds % 60}s"
        val avgSpeed = if (totalSeconds > 0) finalDistance / (totalSeconds / 3600f) else 0f

        val scoreColor = when {
            finalScore >= 90 -> Color(0xFF00FFA3)
            finalScore >= 75 -> Color(0xFF00E5FF)
            finalScore >= 60 -> Color(0xFFFFD60A)
            else -> Color(0xFFFF2D55)
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
                    "Harsh Acceleration" to session.harshAccelerationCount,
                    "Harsh Braking" to session.harshBrakingCount,
                    "Instability" to session.unstableRideCount
                ),
                imagePath = imagePath,
                onClose = { finish() }
            )
        }
    }
}

@Composable
fun SummaryScreen(
    score: Int,
    scoreColor: Color,
    stats: List<Pair<String, String>>,
    moneyLost: Float,
    events: List<Pair<String, Int>>,
    imagePath: String?,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {
        // Blurred Background Glow
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(scoreColor.copy(0.15f), Color.Transparent)),
                radius = 1200f,
                center = center.copy(y = 0f)
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp),
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
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFF2D55).copy(0.4f), RoundedCornerShape(24.dp))
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
                modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(24.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    events.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                }
            }

            // --- EVIDENCE IMAGE ---
            imagePath?.let { path ->
                val bitmap = try {
                    BitmapFactory.decodeFile(path)?.let {
                        val matrix = Matrix().apply { postRotate(90f) }
                        Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
                    }
                } catch (e: Exception) { null }

                bitmap?.let {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text("EVENT EVIDENCE", Modifier.align(Alignment.Start), color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Image(
                        bitmap = it.asImageBitmap(), contentDescription = "Evidence",
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("BACK TO DASHBOARD", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier) {
    Surface(
        color = Color.White.copy(0.03f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.border(1.dp, Color.White.copy(0.04f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label.uppercase(), color = Color.White.copy(0.3f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
    }
}