package com.teledrive.app.evidence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EventEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EvidenceScreen(onBack = { finish() }) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Bitmap helpers — run on IO thread, never on main thread
// ──────────────────────────────────────────────────────────────────────────────

private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
    if (!file.exists()) return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    var sample = 1
    var w = opts.outWidth; var h = opts.outHeight
    while (w / 2 >= reqW && h / 2 >= reqH) { sample *= 2; w /= 2; h /= 2 }
    opts.inJustDecodeBounds = false
    opts.inSampleSize = sample
    val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
    // Apply EXIF rotation so images always appear upright
    val rotation = try {
        val exif = ExifInterface(file.absolutePath)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }
    return if (rotation == 0f) raw
    else {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Screens
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EvidenceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var evidenceList by remember { mutableStateOf<List<EvidenceItem>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var selected     by remember { mutableStateOf<EvidenceItem?>(null) }

    // On open: cleanup expired files then load fresh list — both on IO thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EvidenceManager.cleanupOld(context)
            val items = EvidenceManager.loadAll(context)
            withContext(Dispatchers.Main) {
                evidenceList = items
                isLoading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {

        // Subtle ambient glow
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().blur(120.dp)) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFFF2D55).copy(0.04f), Color.Transparent)),
                radius = 900f, center = center.copy(y = center.y * 0.25f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(Color.White.copy(0.06f), CircleShape).size(44.dp)
                ) { Text("←", color = Color.White, fontSize = 18.sp) }
                Text(
                    "EVENT EVIDENCE",
                    color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.size(44.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = if (isLoading) "LOADING…"
                       else "${evidenceList.size} CAPTURE${if (evidenceList.size != 1) "S" else ""} ON DEVICE",
                color = Color.White.copy(0.4f), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF00FFA3), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            "Loading evidence\u2026",
                            color = Color.White.copy(0.45f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 8 })
                ) {
                    if (evidenceList.isEmpty()) {
                        EvidenceEmptyState()
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(evidenceList, key = { it.file.absolutePath }) { item ->
                                EvidenceCard(item) { selected = item }
                            }
                        }
                    }
                }
            }
        }

        // Full-screen detail dialog
        selected?.let { item ->
            EvidenceDetailDialog(
                item = item,
                onDismiss = { selected = null },
                onSave = {
                    scope.launch(Dispatchers.IO) {
                        val ok = EvidenceManager.saveToGallery(context, item)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) "Saved to gallery" else "Save failed — check permissions",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        EvidenceManager.delete(item)
                        val refreshed = EvidenceManager.loadAll(context)
                        withContext(Dispatchers.Main) {
                            evidenceList = refreshed
                            selected = null
                        }
                    }
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Grid Card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EvidenceCard(item: EvidenceItem, onClick: () -> Unit) {
    val eventColor = eventColor(item.eventType)
    val label      = eventLabel(item.eventType)
    val timeStr    = remember(item.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
    }

    // Async thumbnail — 300×300 is plenty for a grid card
    val thumb by produceState<ImageBitmap?>(null, item.file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampled(item.file, 300, 300)?.asImageBitmap() }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(0.03f),
        border = BorderStroke(1.dp, Color.White.copy(0.09f)),
        shadowElevation = 4.dp
    ) {
        Box {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF111827)),
                    contentAlignment = Alignment.Center) {
                    Text("📷", fontSize = 28.sp)
                }
            }

            // Bottom gradient overlay
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(0.82f)),
                        startY = 0.3f
                    )
                )
            )

            // Color accent tab on top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(eventColor.copy(0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(label, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }

            // Time + type at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = eventFullLabel(item.eventType),
                    color = eventColor,
                    fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp
                )
                Text(
                    text = timeStr,
                    color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Detail Dialog
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EvidenceDetailDialog(
    item: EvidenceItem,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val eventColor = eventColor(item.eventType)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // Full-res decode for detail view
    val fullBitmap by produceState<ImageBitmap?>(null, item.file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampled(item.file, 1080, 1920)?.asImageBitmap() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070B14))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.White.copy(0.06f), CircleShape).size(44.dp)
                    ) { Text("✕", color = Color.White, fontSize = 16.sp) }

                    Text(
                        "EVENT PROOF",
                        color = Color.White, fontSize = 12.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 3.sp
                    )

                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.background(Color(0xFFFF2D55).copy(0.12f), CircleShape).size(44.dp)
                    ) { Text("🗑️", fontSize = 16.sp) }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Event type tag
                Text("DETECTED EVENT", color = eventColor.copy(0.6f), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    eventFullLabel(item.eventType),
                    color = eventColor, fontSize = 26.sp, fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap!!,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF111827)),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF00FFA3), strokeWidth = 2.dp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Metadata strip
                Surface(
                    color = Color.White.copy(0.03f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.08f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetaColumn("DATE",
                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(item.timestamp)))
                        MetaColumn("TIME",
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp)))
                        MetaColumn("FILE", item.file.length().let {
                            if (it > 1024 * 1024) "${"%.1f".format(it / 1048576f)} MB"
                            else "${it / 1024} KB"
                        })
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "TD-CNN ANALYSIS: CONFIRMED",
                    color = Color.White.copy(0.3f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Save to gallery button
                Button(
                    onClick = {
                        if (!isSaving) {
                            isSaving = true
                            onSave()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFA3))
                ) {
                    Text(
                        if (isSaving) "SAVING…" else "📥  SAVE TO GALLERY",
                        color = Color.Black, fontWeight = FontWeight.Black, fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.07f))
                ) {
                    Text("CLOSE REVIEW", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            // Delete confirmation overlay
            if (showDeleteConfirm) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color(0xFF111827),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(0.1f)),
                        modifier = Modifier.padding(32.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🗑️", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("DELETE EVIDENCE?", color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.Black)
                            Text("This cannot be undone.", color = Color.White.copy(0.5f),
                                fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { showDeleteConfirm = false },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(0.2f))
                                ) { Text("CANCEL", color = Color.White, fontWeight = FontWeight.Bold) }
                                Button(
                                    onClick = onDelete,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D55))
                                ) { Text("DELETE", color = Color.White, fontWeight = FontWeight.Black) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Empty state
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EvidenceEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🛡️", fontSize = 64.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("NO EVENT EVIDENCE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(6.dp))
            Text("No event evidence available", color = Color.White.copy(0.4f), fontSize = 13.sp)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MetaColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(0.4f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

private fun eventLabel(eventType: String): String = when (eventType) {
    "HARSH_ACCELERATION" -> "ACCEL"
    "HARSH_BRAKING"      -> "BRAKE"
    "UNSTABLE_RIDE"      -> "UNSTABLE"
    else                 -> "EVENT"
}

private fun eventFullLabel(eventType: String): String = when (eventType) {
    "HARSH_ACCELERATION" -> "HARSH ACCELERATION"
    "HARSH_BRAKING"      -> "HARSH BRAKING"
    "UNSTABLE_RIDE"      -> "UNSTABLE RIDE"
    else                 -> eventType
}

private fun eventColor(eventType: String): Color = when (eventType) {
    "HARSH_ACCELERATION" -> Color(0xFF00FFA3)  // Green
    "HARSH_BRAKING"      -> Color(0xFFFF2D55)  // Red
    "UNSTABLE_RIDE"      -> Color(0xFFFF9500)  // Orange
    else                 -> Color(0xFF00E5FF)
}

