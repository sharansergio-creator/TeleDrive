package com.teledrive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.teledrive.app.services.SensorService
import com.teledrive.app.services.DetectionMode
import com.teledrive.app.ui.theme.TeleDriveTheme
import com.teledrive.app.analysis.AnalyzerProvider
import com.teledrive.app.triphistory.TripStorage
import com.teledrive.app.triphistory.TripSummary
import com.teledrive.app.profile.UserProfileManager
import com.teledrive.app.onboarding.OnboardingActivity
import com.teledrive.app.evidence.EvidenceManager
import com.teledrive.app.evidence.EventEvidenceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Increments on every onResume so the home screen re-fetches updated trip data
    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted, user can now start trip
        // No need to do anything, they can try again
    }
    
    fun requestPermissionsForTrip() {
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
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if first launch - route to onboarding
        if (UserProfileManager.isFirstTime(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        
        createNotificationChannel()

        setContent {
            TeleDriveTheme {
                val context = LocalContext.current
                // Persist detection mode across app launches
                val prefs = context.getSharedPreferences("teledrive_prefs", android.content.Context.MODE_PRIVATE)
                val savedMode = DetectionMode.valueOf(
                    prefs.getString("detection_mode", DetectionMode.HYBRID_MODE.name)
                        ?: DetectionMode.HYBRID_MODE.name
                )
                val detectionMode = remember { mutableStateOf(savedMode) }
                val lastTrip = remember { mutableStateOf<TripSummary?>(null) }
                val overallScore = remember { mutableStateOf(0) }

                // Reload on every resume so score refreshes after a trip ends
                LaunchedEffect(resumeTick.value) {
                    SensorService.currentMode = detectionMode.value
                    AnalyzerProvider.setMode(detectionMode.value)
                    val trips = TripStorage.getAll(context)
                    if (trips.isNotEmpty()) lastTrip.value = trips.first()
                    overallScore.value = TripStorage.getOverallScore(context)
                }

                PremiumStartScreen(
                    detectionMode = detectionMode.value,
                    lastTrip = lastTrip.value,
                    overallScore = overallScore.value,
                    onModeChange = { mode ->
                        detectionMode.value = mode
                        SensorService.currentMode = mode
                        SensorService.pendingModeReset = true  // clears per-mode state on next window
                        AnalyzerProvider.setMode(mode)
                        prefs.edit().putString("detection_mode", mode.name).apply()
                    },
                    onStartClick = {
                        val permissions = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permissions.add(Manifest.permission.CAMERA)

                        val allGranted = permissions.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }

                        if (allGranted) {
                            startTeleDriveFlow(detectionMode.value)
                            startActivity(Intent(this, LiveTripActivity::class.java))
                        }
                    }
                )
            }
        }
    }

    private fun startTeleDriveFlow(mode: DetectionMode) {
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
            putExtra("mode", mode.name)
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
    detectionMode: DetectionMode,
    lastTrip: TripSummary?,
    overallScore: Int,
    onModeChange: (DetectionMode) -> Unit,
    onStartClick: () -> Unit
) {
    val context = LocalContext.current
    val isAutoMode = detectionMode != DetectionMode.RULE_BASED
    
    // --- SUBTLE AMBIENT GLOW (Automotive Feel) ---
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isAutoMode) 0.02f else 0.01f,
        targetValue = if (isAutoMode) 0.04f else 0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isAutoMode) 3000 else 6000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        // --- REACTIVE AMBIENT GLOW ---
        Canvas(modifier = Modifier.fillMaxSize().blur(120.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00FFA3).copy(alpha = glowAlpha),
                        Color(0xFF00E5FF).copy(alpha = glowAlpha * 0.5f),
                        Color.Transparent
                    ),
                    center = center.copy(y = center.y * 0.4f),
                    radius = size.width * 1.5f
                )
            )
        }
        
        // --- SUBTLE EDGE FADES (Depth Enhancement) ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Top edge fade
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(0.4f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.08f
                )
            )
            // Bottom edge fade
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(0.3f)
                    ),
                    startY = size.height * 0.85f,
                    endY = size.height
                )
            )
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding() // System status bar safe area
                    .padding(horizontal = 20.dp) // Global safe area
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(20.dp)) // Additional breathing space
                
                // --- PREMIUM BRAND HEADER ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "TELEDRIVE",
                        color = Color.White,
                        fontSize = 30.sp, // Slightly larger
                        fontWeight = FontWeight.Black,
                        letterSpacing = 14.sp,
                        style = MaterialTheme.typography.displaySmall.copy(
                            shadow = Shadow(
                                color = Color(0xFF00FFA3).copy(0.5f), // STRONGER GLOW
                                offset = Offset(0f, 3f),
                                blurRadius = 14f // MORE BLUR
                            )
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "PRECISION TELEMATICS",
                        color = Color.White.copy(0.65f), // MORE VISIBLE - was 0.55f
                        fontSize = 11.sp, // Slightly larger
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 5.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp)) // TIGHTER - visual connection to gauge

                // --- AUTOMOTIVE CONTROL PANEL (Grouped) ---
                AutomotiveControlPanel(
                    score = overallScore,
                    lastTrip = lastTrip,
                    detectionMode = detectionMode,
                    onModeChange = onModeChange,
                    onStartClick = onStartClick
                )
                
                Spacer(modifier = Modifier.weight(1f)) // Push to bottom
            }

            // --- BOTTOM NAVIGATION BAR ---
            BottomNavigationBar(selectedTab = 0, context = context)
        }
    }
}

// --- AUTOMOTIVE CONTROL PANEL ---
@Composable
fun AutomotiveControlPanel(
    score: Int,
    lastTrip: TripSummary?,
    detectionMode: DetectionMode,
    onModeChange: (DetectionMode) -> Unit,
    onStartClick: () -> Unit
) {
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    // Check permissions
    val hasPermissions = remember(context) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.CAMERA)
        
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    // Permission dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = Color(0xFF0D1219),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "PERMISSIONS REQUIRED",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "TeleDrive needs the following permissions to track your trips:",
                        color = Color.White.copy(0.7f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionRequirement(
                        icon = Icons.Default.LocationOn,
                        text = "Location - Track route and distance"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionRequirement(
                        icon = Icons.Default.Create,
                        text = "Camera - Capture event evidence"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionRequirement(
                            icon = Icons.Default.Notifications,
                            text = "Notifications - Trip status updates"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        // Trigger permission request through activity
                        if (context is MainActivity) {
                            context.requestPermissionsForTrip()
                        }
                    }
                ) {
                    Text(
                        text = "GRANT PERMISSIONS",
                        color = Color(0xFF00FFA3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(
                        text = "CANCEL",
                        color = Color.White.copy(0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
    
    // Neumorphic container with subtle elevation
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(28.dp),
                spotColor = Color.Black.copy(0.4f),
                ambientColor = Color.Black.copy(0.2f)
            ),
        color = Color(0xFF0D1219), // Dark blue-grey base
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(0.08f), // Top highlight
                    Color.Transparent
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ECO-SCORE GAUGE
            EcoScoreGauge(score = score)
            
            Spacer(modifier = Modifier.height(12.dp)) // Tight spacing
            
            // SYSTEM STATUS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            Color(0xFF00FFA3),
                            CircleShape
                        )
                        .shadow(4.dp, CircleShape, spotColor = Color(0xFF00FFA3))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SYSTEM READY",
                    color = Color(0xFF00FFA3),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.5.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp)) // Tight spacing
            
            // AUTOMOTIVE CTA BUTTON
            AutomotiveCTAButton(
                onClick = {
                    if (hasPermissions) {
                        onStartClick()
                    } else {
                        showPermissionDialog = true
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp)) // INCREASED - proper separation from AI card
            
            // AI ASSIST CONTROL (3-way mode selector)
            DetectionModeSelector(detectionMode = detectionMode, onModeChange = onModeChange)

            Spacer(modifier = Modifier.height(16.dp))

            // EVENT EVIDENCE QUICK ACCESS
            val evidenceCount by produceState(initialValue = 0) {
                value = withContext(Dispatchers.IO) {
                    EvidenceManager.loadAll(context).size
                }
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, EventEvidenceActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF00E5FF)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (evidenceCount > 0) "EVENT EVIDENCE ($evidenceCount)" else "EVENT EVIDENCE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}

// --- CONTEXTUAL WIDGETS ---
@Composable
fun ContextualWidgets(lastTrip: TripSummary?) {
    if (lastTrip != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Last Trip Widget
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = Color.Black.copy(0.15f),
                        ambientColor = Color.Black.copy(0.1f)
                    ),
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "LAST TRIP",
                        color = Color.White.copy(0.5f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${lastTrip.score}",
                            color = Color(0xFF00FFA3),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "/100",
                            color = Color.White.copy(0.3f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${String.format("%.1f", lastTrip.distanceKm)} km",
                        color = Color.White.copy(0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            // Weekly Performance Widget
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = Color.Black.copy(0.15f),
                        ambientColor = Color.Black.copy(0.1f)
                    ),
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "THIS WEEK",
                        color = Color.White.copy(0.5f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "📈",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Improving",
                        color = Color(0xFF00FFA3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// --- PREMIUM BOTTOM NAVIGATION ---
@Composable
fun BottomNavigationBar(selectedTab: Int, context: android.content.Context) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    
    Box(modifier = Modifier.padding(bottom = bottomPadding)) {
        Surface(
            color = Color(0xFF0A0E14).copy(0.98f), // Slightly more solid
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp) // INCREASED HEIGHT
                .shadow(
                    elevation = 18.dp, // STRONGER elevation
                    spotColor = Color.Black.copy(0.8f),
                    ambientColor = Color.Black.copy(0.5f)
                ),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            border = BorderStroke(
                width = 1.5.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(0.12f), // BRIGHTER top
                        Color.Transparent
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp, start = 18.dp, end = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, // BETTER DISTRIBUTION
                verticalAlignment = Alignment.CenterVertically
            ) {
                PremiumNavItem(
                    icon = Icons.Default.Home,
                    label = "Home",
                    isSelected = selectedTab == 0,
                    onClick = { }
                )
                PremiumNavItem(
                    icon = Icons.Default.LocationOn,
                    label = "Trips",
                    isSelected = selectedTab == 1,
                    onClick = {
                        context.startActivity(Intent(context, TripsActivity::class.java))
                    }
                )
                PremiumNavItem(
                    icon = Icons.Default.Info,
                    label = "Analytics",
                    isSelected = selectedTab == 2,
                    onClick = {
                        context.startActivity(Intent(context, AnalyticsActivity::class.java))
                    }
                )
                PremiumNavItem(
                    icon = Icons.Default.Person,
                    label = "Profile",
                    isSelected = selectedTab == 3,
                    onClick = {
                        context.startActivity(Intent(context, com.teledrive.app.profile.ProfileActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
fun PremiumNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 27.dp, // LARGER
        label = "iconSize"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .scale(scale)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .width(74.dp) // Slightly wider
    ) {
        // Icon with STRONG glow for active state
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(46.dp) // Larger touch target
        ) {
            // ENHANCED active glow - MUCH MORE VISIBLE
            if (isSelected) {
                Canvas(modifier = Modifier.size(70.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF00FFA3).copy(0.5f), // VERY STRONG
                                Color(0xFF00FFA3).copy(0.3f),
                                Color(0xFF00FFA3).copy(0.15f),
                                Color(0xFF00FFA3).copy(0.05f),
                                Color.Transparent
                            ),
                            radius = size.width / 2
                        )
                    )
                }
            }
            
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.6f), // Better inactive
                modifier = Modifier.size(iconSize)
            )
        }
        
        Spacer(modifier = Modifier.height(5.dp))
        
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.6f), // Better inactive
            fontSize = 11.sp, // Slightly larger
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
            letterSpacing = 1.3.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun AutomotiveNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        label = "scale"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(8.dp)
    ) {
        // Icon container with neumorphic effect
        Box(
            modifier = Modifier
                .size(if (isSelected) 44.dp else 40.dp)
                .background(
                    if (isSelected) {
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF00FFA3).copy(0.15f),
                                Color(0xFF00FFA3).copy(0.05f)
                            )
                        )
                    } else {
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(0.05f),
                                Color.Transparent
                            )
                        )
                    },
                    CircleShape
                )
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) Color(0xFF00FFA3).copy(0.4f) else Color.White.copy(0.08f),
                    shape = CircleShape
                )
                .shadow(
                    elevation = if (isSelected) 8.dp else 2.dp,
                    shape = CircleShape,
                    spotColor = if (isSelected) Color(0xFF00FFA3).copy(0.4f) else Color.Black.copy(0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.5f),
                modifier = Modifier.size(if (isSelected) 24.dp else 20.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.4f),
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(vertical = 8.dp)
    ) {
        // Icon with premium style
        Box(
            modifier = Modifier
                .size(if (isSelected) 32.dp else 28.dp)
                .background(
                    color = if (isSelected) Color(0xFF00FFA3).copy(0.15f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.4f),
                modifier = Modifier.size(if (isSelected) 22.dp else 20.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00FFA3) else Color.White.copy(0.3f),
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.5.sp
        )
        // Active indicator with subtle glow
        if (isSelected) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(2.dp)
                    .shadow(4.dp, RoundedCornerShape(1.dp), spotColor = Color(0xFF00FFA3))
                    .background(Color(0xFF00FFA3), RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
fun EcoScoreGauge(score: Int) {
    val animatedScore by animateIntAsState(
        targetValue = score,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "score"
    )
    
    // Breathing glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingGlow"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = size.minDimension / 2 - 22.dp.toPx()
            
            // STRONG inner radial glow (center focus)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00FFA3).copy(0.18f * breathingGlow), // Stronger, animated
                        Color(0xFF00E5FF).copy(0.12f * breathingGlow),
                        Color(0xFF00FFA3).copy(0.06f * breathingGlow),
                        Color.Transparent
                    ),
                    center = Offset(centerX, centerY),
                    radius = radius * 0.75f
                ),
                radius = radius * 0.75f
            )
            
            // SEGMENTED BACKGROUND ARC (Green → Yellow → Orange gradient)
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to Color(0xFF00FFA3).copy(0.15f),  // Green start
                    0.3f to Color(0xFF00FFA3).copy(0.12f),  // Green mid
                    0.6f to Color(0xFFFFD700).copy(0.10f),  // Yellow transition
                    0.85f to Color(0xFFFF8C00).copy(0.08f), // Orange warning
                    1.0f to Color(0xFFFF8C00).copy(0.06f),  // Orange end
                    center = Offset(centerX, centerY)
                ),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round) // THICKER
            )
            
            // VISIBLE TICK MARKS (every 30 degrees) - MUCH MORE VISIBLE
            for (i in 0..8) {
                val angle = 135f + (i * 33.75f)
                val angleRad = Math.toRadians(angle.toDouble())
                val startRadius = radius - 6.dp.toPx()
                val endRadius = radius + 6.dp.toPx()
                
                val startX = centerX + (startRadius * kotlin.math.cos(angleRad)).toFloat()
                val startY = centerY + (startRadius * kotlin.math.sin(angleRad)).toFloat()
                val endX = centerX + (endRadius * kotlin.math.cos(angleRad)).toFloat()
                val endY = centerY + (endRadius * kotlin.math.sin(angleRad)).toFloat()
                
                drawLine(
                    color = Color.White.copy(0.35f), // MUCH MORE VISIBLE
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2.5.dp.toPx(), // THICKER
                    cap = StrokeCap.Round
                )
            }
            
            // ACTIVE PROGRESS ARC with dynamic coloring
            val progressSweep = (animatedScore / 100f) * 270f
            val progressColors = when {
                animatedScore >= 90 -> listOf(
                    Color(0xFF00FFA3),
                    Color(0xFF00E5FF),
                    Color(0xFF00FFA3)
                )
                animatedScore >= 75 -> listOf(
                    Color(0xFFFFD60A),
                    Color(0xFFFFD700),
                    Color(0xFFFFD60A)
                )
                else -> listOf(
                    Color(0xFFFF8C00),
                    Color(0xFFFF2D55),
                    Color(0xFFFF8C00)
                )
            }
            
            drawArc(
                brush = Brush.sweepGradient(
                    0f to progressColors[0],
                    0.5f to progressColors[1],
                    1f to progressColors[2],
                    center = Offset(centerX, centerY)
                ),
                startAngle = 135f,
                sweepAngle = progressSweep,
                useCenter = false,
                style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round) // THICKER
            )
            
            // Add shimmer effect on active arc
            if (animatedScore > 0) {
                drawArc(
                    color = Color.White.copy(0.15f * breathingGlow),
                    startAngle = 135f + progressSweep - 15f,
                    sweepAngle = 15f,
                    useCenter = false,
                    style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Eco-Score",
                color = Color.White.copy(0.6f), // More visible
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = if (score == 0) "--" else "$animatedScore",
                color = Color.White,
                fontSize = 84.sp,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.displayLarge.copy(
                    shadow = Shadow(
                        color = when {
                            score >= 90 -> Color(0xFF00FFA3).copy(0.4f)
                            score >= 75 -> Color(0xFFFFD60A).copy(0.4f)
                            else -> Color(0xFFFF2D55).copy(0.4f)
                        },
                        blurRadius = 12f
                    )
                )
            )
        }
    }
}


// --- PREMIUM AUTOMOTIVE CTA BUTTON ---
@Composable
fun AutomotiveCTAButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    // Strong glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .scale(scale)
    ) {
        // REFINED GREEN OUTER GLOW (tighter, better edge definition)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00FFA3).copy(alpha = glowAlpha * 0.5f),
                        Color(0xFF00FFA3).copy(alpha = glowAlpha * 0.3f),
                        Color(0xFF00FFA3).copy(alpha = glowAlpha * 0.15f),
                        Color.Transparent
                    ),
                    radius = size.width * 0.6f, // TIGHTER - was 0.8f
                    center = Offset(size.width / 2, size.height / 2)
                ),
                cornerRadius = CornerRadius(26.dp.toPx()),
                topLeft = Offset(-12.dp.toPx(), -12.dp.toPx()), // REDUCED spread
                size = Size(
                    size.width + 24.dp.toPx(), // REDUCED - was 32dp
                    size.height + 24.dp.toPx()
                )
            )
        }
        
        // Main button with DUAL-LAYER gradient
        Button(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (isPressed) 3.dp else 12.dp,
                    shape = RoundedCornerShape(26.dp),
                    spotColor = Color.Black.copy(0.7f),
                    ambientColor = Color(0xFF00FFA3).copy(0.2f) // Green ambient
                ),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // OUTER GRADIENT LAYER
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1A2332), // Outer lighter
                                    Color(0xFF0F1820)  // Outer darker
                                )
                            ),
                            shape = RoundedCornerShape(26.dp)
                        )
                )
                
                // INNER GRADIENT LAYER (creates depth)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1F2937), // Inner lighter
                                    Color(0xFF141F2E), // Inner mid
                                    Color(0xFF111827)  // Inner darker
                                )
                            ),
                            shape = RoundedCornerShape(23.dp)
                        )
                        .border(
                            width = 2.dp, // THICKER BORDER
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(0.25f), // BRIGHTER top highlight line
                                    Color(0xFF00FFA3).copy(0.15f), // Green mid
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(23.dp)
                        )
                )
                
                // Content with icon
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "START NEW TRIP",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 2.5.sp,
                        style = MaterialTheme.typography.labelLarge.copy(
                            shadow = Shadow(
                                color = Color(0xFF00FFA3).copy(0.6f), // STRONGER glow
                                offset = Offset(0f, 2f),
                                blurRadius = 10f
                            )
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = Color(0xFF00FFA3),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// --- PREMIUM AI CONTROL ---
@Composable
fun DetectionModeSelector(
    detectionMode: DetectionMode,
    onModeChange: (DetectionMode) -> Unit
) {
    val modes = listOf(
        Triple(DetectionMode.RULE_BASED,  "RULE",   "Fast threshold detection"),
        Triple(DetectionMode.ML_MODE,     "AI",     "Pattern-based detection"),
        Triple(DetectionMode.HYBRID_MODE, "HYBRID", "Best accuracy (Recommended)"),
    )

    Surface(
        color = Color(0xFF12171F).copy(0.95f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                colors = when (detectionMode) {
                    DetectionMode.HYBRID_MODE -> listOf(
                        Color(0xFF00FFA3).copy(0.4f), Color(0xFF00E5FF).copy(0.3f), Color(0xFF00FFA3).copy(0.4f)
                    )
                    DetectionMode.ML_MODE -> listOf(
                        Color(0xFFFFD60A).copy(0.4f), Color(0xFFFF9F0A).copy(0.3f), Color(0xFFFFD60A).copy(0.4f)
                    )
                    else -> listOf(Color.White.copy(0.15f), Color.White.copy(0.08f), Color.White.copy(0.15f))
                }
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when (detectionMode) {
                                DetectionMode.HYBRID_MODE -> Color(0xFF00FFA3)
                                DetectionMode.ML_MODE     -> Color(0xFFFFD60A)
                                else                      -> Color.White.copy(0.5f)
                            },
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "DETECTION MODE",
                    color = Color.White.copy(0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.8.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 3 mode buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modes.forEach { (mode, label, desc) ->
                    val isSelected = detectionMode == mode
                    val accentColor = when (mode) {
                        DetectionMode.HYBRID_MODE -> Color(0xFF00FFA3)
                        DetectionMode.ML_MODE     -> Color(0xFFFFD60A)
                        else                      -> Color(0xFF00E5FF)
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onModeChange(mode) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) accentColor.copy(0.15f) else Color.White.copy(0.04f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) accentColor.copy(0.6f) else Color.White.copy(0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) accentColor else Color.White.copy(0.55f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            if (mode == DetectionMode.HYBRID_MODE) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "REC",
                                    color = Color(0xFF00FFA3).copy(0.8f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            // Mode description
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = modes.first { it.first == detectionMode }.third,
                color = Color.White.copy(0.5f),
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun AutomotiveAIControl(isAutoMode: Boolean, onModeChange: (Boolean) -> Unit) {
    // Legacy stub kept for any preview references; not called from production UI.
    DetectionModeSelector(
        detectionMode = if (isAutoMode) DetectionMode.ML_MODE else DetectionMode.RULE_BASED,
        onModeChange  = { mode -> onModeChange(mode != DetectionMode.RULE_BASED) }
    )
}
@Composable
fun PremiumStartButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "scale")
    val alpha by animateFloatAsState(if (isPressed) 0.85f else 1f, label = "alpha")

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(scale)
            .alpha(alpha)
            .shadow(
                elevation = if (isPressed) 2.dp else 8.dp,
                shape = RoundedCornerShape(32.dp), // Pill shape
                spotColor = Color.Black.copy(0.25f),
                ambientColor = Color.Black.copy(0.15f)
            ),
        shape = RoundedCornerShape(32.dp), // Full pill shape
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFF5F5F5), // Off-white
            contentColor = Color(0xFF1A1A1A) // Dark grey, almost black
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        )
    ) {
        Text(
            "START NEW TRIP",
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
fun AiAssistCard(isAutoMode: Boolean, onModeChange: (Boolean) -> Unit) {
    Surface(
        color = Color.White.copy(0.05f), // Slightly more visible
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = Color.Black.copy(0.15f),
                ambientColor = Color.Black.copy(0.1f)
            )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "AI Assist",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Auto trip detection",
                    color = Color.White.copy(0.5f),
                    fontSize = 12.sp
                )
            }
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
}

@Composable
fun PermissionRequirement(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF00FFA3),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = Color.White.copy(0.7f),
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}
