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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke

class TripDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val history = TripStorage.getAll(context = this)

        setContent {
            TripDetailsScreen(history)
        }

        // 👇 ADD HERE (inside onCreate)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()

                    overridePendingTransition(
                        R.anim.slide_in_left,
                        R.anim.slide_out_right
                    )
                }
            }
        )
    }
}
@Composable
fun TripDetailsScreen(history: List<TripSummary>) {

    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animatedProgress.animateTo(
            1f,
            animationSpec = tween(1200, easing = FastOutSlowInEasing)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 24.dp)
    ) {

        item {
            Text(
                "Analytics",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "Track your driving performance",
                color = Color.White.copy(0.6f),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))
        }

        // GRAPH CARD
        item {
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {

                    Text(
                        "Last 7 Trips",
                        color = Color.White.copy(0.6f),
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    PremiumGraph(
                        data = history.takeLast(7).map { it.score },
                        progress = animatedProgress.value
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }

        item {
            Text(
                "Recent Trips",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))
        }

        items(history.reversed()) { trip ->
            TripHistoryItem(trip)
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable
fun PremiumGraph(data: List<Int>, progress: Float) {

    if (data.isEmpty()) return

    val maxValue = (data.maxOrNull() ?: 100).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {

        val width = size.width
        val height = size.height
        val stepX = width / (data.size - 1)

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

        // 🔥 Gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF00E5FF).copy(alpha = 0.4f),
                    Color.Transparent
                )
            )
        )

        // 🔥 Line
        drawPath(
            path = path,
            color = Color(0xFF00E5FF),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
    }
}