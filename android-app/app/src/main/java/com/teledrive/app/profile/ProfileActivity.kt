package com.teledrive.app.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.evidence.EvidenceManager
import com.teledrive.app.evidence.EventEvidenceActivity
import com.teledrive.app.triphistory.TripStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val existingProfile = UserProfileManager.getProfile(this)
        val allTrips = TripStorage.getAll(this)
        
        setContent {
            ProfileScreen(
                existingProfile = existingProfile,
                totalTrips = allTrips.size,
                avgScore = if (allTrips.isEmpty()) 0 else allTrips.map { it.score }.average().toInt(),
                onSave = { profile ->
                    UserProfileManager.saveProfile(this, profile)
                    finish()
                },
                onBack = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    existingProfile: UserProfile?,
    totalTrips: Int,
    avgScore: Int,
    onSave: (UserProfile) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(existingProfile?.name ?: "") }
    var vehicleName by remember { mutableStateOf(existingProfile?.vehicleName ?: "") }
    var engineCC by remember { mutableStateOf(existingProfile?.engineCC?.toString() ?: "") }
    var mileage by remember { mutableStateOf(existingProfile?.mileage?.toString() ?: "") }
    var isEditing by remember { mutableStateOf(existingProfile == null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header with back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(0.05f), CircleShape)
                ) {
                    Text("←", color = Color.White, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "PROFILE",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
            }

            // === SECTION 1: USER INFO ===
            ProfileSection(title = "USER INFORMATION") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Profile Avatar
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(Color(0xFF00FFA3).copy(0.12f), CircleShape)
                            .border(2.dp, Color(0xFF00FFA3).copy(0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "U",
                            color = Color(0xFF00FFA3),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                if (isEditing) {
                    // Editable fields
                    PremiumTextField(
                        label = "Your Name",
                        value = name,
                        onValueChange = { name = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PremiumTextField(
                        label = "Vehicle Name",
                        value = vehicleName,
                        onValueChange = { vehicleName = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PremiumTextField(
                        label = "Engine CC",
                        value = engineCC,
                        onValueChange = { engineCC = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PremiumTextField(
                        label = "Mileage (km/l)",
                        value = mileage,
                        onValueChange = { mileage = it }
                    )
                } else {
                    // Display mode
                    InfoRow(label = "Name", value = name.ifEmpty { "Not set" })
                    InfoRow(label = "Vehicle", value = vehicleName.ifEmpty { "Not set" })
                    InfoRow(label = "Engine", value = if (engineCC.isNotEmpty()) "$engineCC CC" else "Not set")
                    InfoRow(label = "Mileage", value = if (mileage.isNotEmpty()) "$mileage km/l" else "Not set")
                }
            }

            // === SECTION 2: DRIVING SUMMARY ===
            ProfileSection(title = "DRIVING SUMMARY") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatCard(
                        label = "Total Trips",
                        value = totalTrips.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    StatCard(
                        label = "Avg Score",
                        value = avgScore.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // === SECTION 2.5: EVENT EVIDENCE ===
            val evidenceContext = LocalContext.current
            var evidenceCount by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                evidenceCount = withContext(Dispatchers.IO) {
                    EvidenceManager.loadAll(evidenceContext).size
                }
            }
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00E5FF).copy(0.15f), RoundedCornerShape(18.dp))
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(18.dp),
                        spotColor = Color.Black.copy(0.15f),
                        ambientColor = Color.Black.copy(0.1f)
                    )
                    .clickable {
                        evidenceContext.startActivity(
                            Intent(evidenceContext, EventEvidenceActivity::class.java)
                        )
                    }
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "EVENT EVIDENCE",
                        color = Color(0xFF00E5FF).copy(0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Color(0xFF00E5FF).copy(0.1f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Evidence",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (evidenceCount > 0)
                                    "Event Evidence ($evidenceCount events)"
                                else
                                    "Event Evidence",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (evidenceCount > 0)
                                    "View all captured events"
                                else
                                    "No events captured yet",
                                color = Color.White.copy(0.4f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Navigate",
                            tint = Color(0xFF00E5FF).copy(0.5f)
                        )
                    }
                }
            }

            // === SECTION 3: SETTINGS ===
            ProfileSection(title = "SETTINGS") {
                Column {
                    SettingRow(
                        icon = Icons.Default.Build,
                        label = "AI Assist",
                        sublabel = "Auto trip detection"
                    )
                    SettingRow(
                        icon = Icons.Default.Notifications,
                        label = "Notifications",
                        sublabel = "Event alerts"
                    )
                    SettingRow(
                        icon = Icons.Default.Lock,
                        label = "Permissions",
                        sublabel = "Location, sensors"
                    )
                }
            }

            // === SECTION 4: APP INFO ===
            ProfileSection(title = "APP INFO") {
                InfoRow(label = "Version", value = "1.0.0")
                InfoRow(label = "Build", value = "Production")
                Text(
                    text = "All data stored locally on device",
                    color = Color.White.copy(0.3f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Save/Edit button
            if (isEditing) {
                Button(
                    onClick = {
                        val profile = UserProfile(
                            name = name,
                            vehicleName = vehicleName,
                            engineCC = engineCC.toIntOrNull() ?: 0,
                            mileage = mileage.toFloatOrNull() ?: 0f
                        )
                        onSave(profile)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FFA3)
                    ),
                    enabled = name.isNotEmpty()
                ) {
                    Text(
                        "SAVE PROFILE",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp
                    )
                }
            } else {
                Button(
                    onClick = { isEditing = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(0.08f)
                    )
                ) {
                    Text(
                        "EDIT PROFILE",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(18.dp))
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(18.dp),
                spotColor = Color.Black.copy(0.15f),
                ambientColor = Color.Black.copy(0.1f)
            )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFF00FFA3).copy(0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF00FFA3).copy(0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = Color(0xFF00FFA3),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = Color.White.copy(0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SettingRow(icon: ImageVector, label: String, sublabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color.White.copy(0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color(0xFF00FFA3).copy(0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = sublabel,
                color = Color.White.copy(0.4f),
                fontSize = 11.sp
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = Color.White.copy(0.2f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column {
        Text(
            text = label.uppercase(),
            color = Color.White.copy(0.4f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(0.03f),
                unfocusedContainerColor = Color.White.copy(0.03f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF00FFA3),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
