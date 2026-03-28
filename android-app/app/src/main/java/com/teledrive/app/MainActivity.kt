package com.teledrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.teledrive.app.services.SensorService
import com.teledrive.app.ui.theme.TeleDriveTheme
import com.teledrive.app.analysis.AnalyzerProvider
import android.util.Log
import com.teledrive.app.services.DetectionMode
import android.content.Intent
import androidx.compose.ui.platform.LocalContext


class MainActivity : ComponentActivity() {

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val NOTIFICATION_PERMISSION_CODE = 1002
        private const val CAMERA_PERMISSION_CODE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        setContent {
            TeleDriveTheme {
                val isAutoMode = remember { mutableStateOf(false) }


                PremiumStartScreen(
                    isAutoMode = isAutoMode.value,
                    onModeChange = { isChecked ->

                        isAutoMode.value = isChecked

                        // ✅ CONNECT SERVICE HERE
                        SensorService.currentMode = if (isChecked) {
                            DetectionMode.AI_ASSIST
                        } else {
                            DetectionMode.RULE_BASED
                        }

                        AnalyzerProvider.useML = isChecked // (optional, keep if needed)

                        Log.d("MODE", "UI Mode = ${SensorService.currentMode}")
                    },
                    onStartClick = {
                        startTeleDriveFlow(isAutoMode.value)
                        startActivity(Intent(this, LiveTripActivity::class.java))
                    }
                )
            }
        }
    }

    private fun startTeleDriveFlow(isAuto: Boolean) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.CAMERA)

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
            return
        }

        val intent = Intent(this, SensorService::class.java).apply {
            putExtra("mode", if (isAuto) "AUTO" else "MANUAL")
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ride_channel", "Ride Notifications", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

@Composable
fun PremiumStartScreen(
    isAutoMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onStartClick: () -> Unit
)
{
    val context = LocalContext.current
    // --- ANIMATION STATES ---
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible.value = true // Triggers the entrance animation
    }

    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val glowOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )

    // Entrance animation for Logo (Scale + Fade)
    val logoScale by animateFloatAsState(
        targetValue = if (visible.value) 1f else 0.8f,
        animationSpec = tween(1200, easing = EaseOutBack), label = "logo_scale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible.value) 1f else 0f,
        animationSpec = tween(1000), label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {

        // --- Ambient Background ---
        Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF00FFA3).copy(0.12f), Color.Transparent)),
                radius = 1200f,
                center = center.copy(x = center.x + (glowOffset % 300) - 150)
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // --- Top Toggle (Fades in) ---
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .graphicsLayer(alpha = contentAlpha) // Fade in
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(50))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAutoMode) "AI ASSIST ACTIVE" else "MANUAL MODE",
                        color = if (isAutoMode) Color(0xFF00FFA3) else Color.White.copy(0.6f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isAutoMode,
                        onCheckedChange = onModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00FFA3),
                            checkedTrackColor = Color(0xFF00FFA3).copy(0.2f)
                        )
                    )
                }
            }

            // --- Branding Section (Scales in) ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer(scaleX = logoScale, scaleY = logoScale, alpha = contentAlpha)
            ) {
                Text(
                    text = "TELEDRIVE",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 4.sp,
                    maxLines = 1,
                    softWrap = false
                )

                Box(modifier = Modifier.width(42.dp).height(3.dp).background(Color(0xFF00FFA3)))

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "PRECISION TELEMATICS",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }

            // --- Ignition Button (Slides up) ---
            val buttonSlide by animateDpAsState(
                targetValue = if (visible.value) 0.dp else 50.dp,
                animationSpec = tween(1000, easing = EaseOutCubic), label = "slide"
            )

            Column(modifier = Modifier.fillMaxWidth().offset(y = buttonSlide).graphicsLayer(alpha = contentAlpha)) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(75.dp)
                        .shadow(40.dp, RoundedCornerShape(25.dp), ambientColor = Color(0xFF00FFA3)),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text(
                        "START TRIP",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 3.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Ensure device is securely mounted",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = Color.White.copy(0.3f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}