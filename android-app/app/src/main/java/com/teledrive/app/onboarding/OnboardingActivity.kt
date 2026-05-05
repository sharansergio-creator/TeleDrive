package com.teledrive.app.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teledrive.app.MainActivity
import com.teledrive.app.profile.UserProfile
import com.teledrive.app.profile.UserProfileManager
import com.teledrive.app.ui.theme.TeleDriveTheme

class OnboardingActivity : ComponentActivity() {
    
    private var currentStep by mutableStateOf(OnboardingStep.WELCOME)
    private var permissionsGranted by mutableStateOf(false)
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.all { it.value }
        if (permissionsGranted) {
            completeOnboarding()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            TeleDriveTheme {
                OnboardingFlow(
                    currentStep = currentStep,
                    onStepChange = { step -> currentStep = step },
                    onComplete = { profile ->
                        if (profile != null) {
                            UserProfileManager.saveProfile(this, profile)
                        } else {
                            UserProfileManager.markOnboardingComplete(this)
                        }
                        requestPermissions()
                    }
                )
            }
        }
    }
    
    private fun requestPermissions() {
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
            currentStep = OnboardingStep.PERMISSIONS
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            permissionsGranted = true
            completeOnboarding()
        }
    }
    
    private fun completeOnboarding() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

enum class OnboardingStep {
    WELCOME,
    PROFILE,
    PERMISSIONS
}

@Composable
fun OnboardingFlow(
    currentStep: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    onComplete: (UserProfile?) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        when (currentStep) {
            OnboardingStep.WELCOME -> WelcomeScreen(
                onNext = { onStepChange(OnboardingStep.PROFILE) }
            )
            OnboardingStep.PROFILE -> ProfileSetupScreen(
                onSkip = { onComplete(null) },
                onComplete = { profile -> onComplete(profile) }
            )
            OnboardingStep.PERMISSIONS -> PermissionScreen()
        }
    }
}

@Composable
fun WelcomeScreen(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Brand
        Text(
            text = "TELEDRIVE",
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Visible, // Allow text to stay visible
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp), // Small padding to prevent edge clipping
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "PRECISION TELEMATICS",
            color = Color.White.copy(0.65f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(60.dp))
        
        // Feature highlights
        OnboardingFeature(
            icon = Icons.Default.Star,
            title = "Smart Detection",
            description = "AI-powered driving behavior analysis"
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OnboardingFeature(
            icon = Icons.Default.Build,
            title = "Real-time Monitoring",
            description = "Track your driving in real-time"
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OnboardingFeature(
            icon = Icons.Default.Call,
            title = "Privacy First",
            description = "All data stays on your device"
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Next button
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FFA3)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "GET STARTED",
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun OnboardingFeature(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = Color(0xFF00FFA3).copy(0.15f),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF00FFA3),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color.White.copy(0.6f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    onSkip: () -> Unit,
    onComplete: (UserProfile) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var vehicleName by remember { mutableStateOf("") }
    var engineCC by remember { mutableStateOf("") }
    var mileage by remember { mutableStateOf("") }
    
    val isValid = name.isNotBlank() && 
                  vehicleName.isNotBlank() && 
                  engineCC.isNotBlank() && 
                  mileage.isNotBlank()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding() // Safe area for bottom navigation
            .imePadding() // Push content up when keyboard opens
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp)) // Top padding - CRITICAL FIX
        
        // Header
        Text(
            text = "SETUP PROFILE",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Help us personalize your experience",
            color = Color.White.copy(0.6f),
            fontSize = 13.sp
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Form fields
        OnboardingTextField(
            value = name,
            onValueChange = { name = it },
            label = "Your Name",
            placeholder = "John Doe"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OnboardingTextField(
            value = vehicleName,
            onValueChange = { vehicleName = it },
            label = "Vehicle Name",
            placeholder = "Honda CB350"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OnboardingTextField(
            value = engineCC,
            onValueChange = { engineCC = it },
            label = "Engine CC",
            placeholder = "350",
            keyboardType = KeyboardType.Number
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OnboardingTextField(
            value = mileage,
            onValueChange = { mileage = it },
            label = "Mileage (km/l)",
            placeholder = "30",
            keyboardType = KeyboardType.Decimal
        )
        
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))
        
        // Continue Button
        Button(
            onClick = {
                val profile = UserProfile(
                    name = name,
                    vehicleName = vehicleName,
                    engineCC = engineCC.toIntOrNull() ?: 0,
                    mileage = mileage.toFloatOrNull() ?: 0f
                )
                onComplete(profile)
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FFA3),
                disabledContainerColor = Color(0xFF00FFA3).copy(0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "CONTINUE",
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Skip Button - More Prominent
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, Color.White.copy(0.3f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White
            )
        ) {
            Text(
                text = "SKIP FOR NOW",
                color = Color.White.copy(0.8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp)) // Bottom padding
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column {
        Text(
            text = label.uppercase(),
            color = Color.White.copy(0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = Color.White.copy(0.3f)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(0.05f),
                unfocusedContainerColor = Color.White.copy(0.05f),
                focusedIndicatorColor = Color(0xFF00FFA3),
                unfocusedIndicatorColor = Color.White.copy(0.1f),
                cursorColor = Color(0xFF00FFA3),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true
        )
    }
}

@Composable
fun PermissionScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GRANTING PERMISSIONS",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Please allow permissions to enable trip tracking",
            color = Color.White.copy(0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        CircularProgressIndicator(
            color = Color(0xFF00FFA3),
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        PermissionItem(
            icon = Icons.Default.LocationOn,
            title = "Location",
            description = "For trip tracking and route mapping"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PermissionItem(
            icon = Icons.Default.Create,
            title = "Camera",
            description = "For event evidence capture"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PermissionItem(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            description = "For trip status updates"
        )
    }
}

@Composable
fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF00FFA3),
                modifier = Modifier.size(28.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = Color.White.copy(0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}



