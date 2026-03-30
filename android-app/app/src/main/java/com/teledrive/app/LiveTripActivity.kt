package com.teledrive.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.core.LiveDataBus
import com.teledrive.app.services.SensorService
import android.util.Log

class LiveTripActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var liveEvent by remember { mutableStateOf("NORMAL") }
            var liveSpeed by remember { mutableStateOf(0f) }
            var liveStd by remember { mutableStateOf(0f) }

            var currentTip by remember { mutableStateOf<String?>(null) }

            DisposableEffect(Unit) {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var resetRunnable: Runnable? = null

                LiveDataBus.listener = { event, speed, std, tip->

                    Log.d("TIP_DEBUG_UI", "Received Tip=$tip")

                    liveSpeed = speed.coerceAtLeast(0f)
                    liveStd = std
                    currentTip = tip

                    if (event != "NORMAL") {
                        liveEvent = event

                        // cancel previous reset safely
                        resetRunnable?.let { handler.removeCallbacks(it) }

                        resetRunnable = Runnable {
                            liveEvent = "NORMAL"
                            currentTip = null
                        }

                        handler.postDelayed(resetRunnable!!, 2000)

                    } else {
                        liveEvent = "NORMAL"
                        currentTip = null
                    }
                }
                onDispose { LiveDataBus.listener = null }
            }

            PremiumLiveTripScreen(
                event = liveEvent,
                speed = liveSpeed,
                std = liveStd,
                tip = currentTip,
                onEndTrip = {
                    stopService(Intent(this@LiveTripActivity, SensorService::class.java))
                    finish()
                }
            )
        }
    }
}

@Composable
fun PremiumLiveTripScreen(
    event: String,
    speed: Float,
    std: Float,
    tip:String?,
    onEndTrip: () -> Unit
) {
    Log.d("TIP_DEBUG_SCREEN", "UI Tip=$tip")
    // Background glow animation to fill the "empty" space
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val glowOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse),
        label = "offset"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when (event) {
            "NORMAL" -> Color(0xFF070B14)
            "HARSH_BRAKING" -> Color(0xFF2D0505)
            else -> Color(0xFF0F0A1E)
        }, label = "bg"
    )

    val accentColor = when (event) {
        "NORMAL" -> Color(0xFF00FFA3) // Electric Mint
        "HARSH_BRAKING" -> Color(0xFFFF2D55) // Ferrari Red
        else -> Color(0xFFFFD60A) // Cyber Yellow
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {

        // --- Ambient Background Mesh ---
        Canvas(modifier = Modifier.fillMaxSize().blur(80.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(accentColor.copy(0.15f), Color.Transparent)),
                radius = 800f,
                center = center.copy(x = center.x + (glowOffset % 200) - 100)
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // --- LIVE MONITORING INDICATOR ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pulse by infiniteTransition.animateFloat(
                    initialValue = 0.6f, targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse"
                )
                Box(modifier = Modifier.size(6.dp).scale(pulse).background(accentColor, CircleShape))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "LIVE MONITORING",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )
            }

            // --- MAIN GAUGE ---
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    // Outer decorative glow ring
                    Box(modifier = Modifier.size(260.dp).border(1.dp, Color.White.copy(0.05f), CircleShape))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val animatedSpeed by animateFloatAsState(
                            targetValue = speed,
                            animationSpec = tween(500),
                            label = "speed_anim"
                        )
                        Text(
                            text = animatedSpeed.toInt().toString(),
                            fontSize = 130.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontFamily = FontFamily.SansSerif,
                            modifier = Modifier.offset(y = 10.dp)
                        )
                        Text(
                            text = "KILOMETERS / HR",
                            fontSize = 12.sp,
                            color = accentColor.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 5.sp
                        )
                    }
                }
            }

            // --- STATUS CARD (Sleeker, Non-Boxy) ---
            Surface(
                color = Color.White.copy(0.03f),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(30.dp))
            ) {
                Row(
                    modifier = Modifier.padding(25.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 🚨 FIX: Added weight(1f) to constrain width and prevent overflow
                    // Problem: Long event text + tip pushed STABILITY indicator off-screen
                    // Solution: Left column gets available space but won't exceed it
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "RIDE STATUS",
                            color = Color.White.copy(0.4f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        Text(
                            formatEventPremium(event),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        // 🔥 TIP (NEW)
                        if (tip != null) {
                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = tip,
                                color = accentColor.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))  // ⬅️ Added spacing
                    Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color.White.copy(0.1f)))
                    Spacer(modifier = Modifier.width(12.dp))  // ⬅️ Added spacing
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text("STABILITY", color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("%.2f".format(std), color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            // --- FINISH BUTTON (Premium Floating Action) ---
            Button(
                onClick = onEndTrip,
                modifier = Modifier.fillMaxWidth().height(70.dp).shadow(30.dp, RoundedCornerShape(25.dp), ambientColor = accentColor),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("FINISH SESSION", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 2.sp)
            }
        }
    }
}

fun formatEventPremium(event: String): String {
    return when(event) {
        "NORMAL" -> "Optimal"
        "HARSH_BRAKING" -> "Harsh Brake"
        "HARSH_ACCELERATION" -> "Aggressive"
        "UNSTABLE_RIDE" -> "Unstable"
        else -> event
    }
}