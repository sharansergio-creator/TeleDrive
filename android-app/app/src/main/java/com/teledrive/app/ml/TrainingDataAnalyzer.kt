package com.teledrive.app.ml

import android.content.Context
import android.util.Log
import com.teledrive.app.core.DrivingEventType
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Utility class to analyze ML training data collected by MLTrainingLogger
 * 
 * Use this to verify the quality of collected training data before
 * using it to train your 1D CNN model.
 */
object TrainingDataAnalyzer {

    private const val TAG = "TrainingDataAnalyzer"

    data class DatasetStats(
        val totalSamples: Long,
        val labelDistribution: Map<Int, Long>,
        val windowCount: Int,
        val avgSamplesPerWindow: Double,
        val speedRange: Pair<Float, Float>,
        val hasValidLabels: Boolean,
        val isBalanced: Boolean
    )

    /**
     * Analyze a training CSV file and return statistics
     */
    fun analyzeFile(context: Context, fileName: String): DatasetStats? {
        val file = File(context.getExternalFilesDir(null), fileName)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $fileName")
            return null
        }

        return analyzeFile(file)
    }

    fun analyzeFile(file: File): DatasetStats? {
        if (!file.exists()) {
            Log.e(TAG, "File not found: ${file.absolutePath}")
            return null
        }

        var totalSamples = 0L
        val labelCounts = mutableMapOf<Int, Long>()
        var minSpeed = Float.MAX_VALUE
        var maxSpeed = Float.MIN_VALUE
        var windowCount = 0
        var lastLabel: Int? = null

        try {
            BufferedReader(FileReader(file)).use { reader ->
                // Skip header
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.size < 9) continue // timestamp,ax,ay,az,gx,gy,gz,speed,label

                    totalSamples++

                    // Parse speed
                    val speed = parts[7].toFloatOrNull() ?: 0f
                    if (speed < minSpeed) minSpeed = speed
                    if (speed > maxSpeed) maxSpeed = speed

                    // Parse label
                    val label = parts[8].trim().toIntOrNull() ?: 0
                    labelCounts[label] = (labelCounts[label] ?: 0) + 1

                    // Count window transitions
                    if (lastLabel != label) {
                        windowCount++
                        lastLabel = label
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file", e)
            return null
        }

        if (totalSamples == 0L) {
            return DatasetStats(
                totalSamples = 0,
                labelDistribution = emptyMap(),
                windowCount = 0,
                avgSamplesPerWindow = 0.0,
                speedRange = 0f to 0f,
                hasValidLabels = false,
                isBalanced = false
            )
        }

        // Check for valid labels (not all NORMAL)
        val hasValidLabels = labelCounts.keys.any { it != 0 }

        // Check balance - no class should be less than 10% or more than 50%
        val minPercent = 0.10
        val maxPercent = 0.50
        val isBalanced = labelCounts.values.all { count ->
            val percent = count.toDouble() / totalSamples
            percent in minPercent..maxPercent
        }

        return DatasetStats(
            totalSamples = totalSamples,
            labelDistribution = labelCounts,
            windowCount = windowCount,
            avgSamplesPerWindow = if (windowCount > 0) totalSamples.toDouble() / windowCount else 0.0,
            speedRange = minSpeed to maxSpeed,
            hasValidLabels = hasValidLabels,
            isBalanced = isBalanced
        )
    }

    /**
     * Get all training CSV files in the app's external storage
     */
    fun listTrainingFiles(context: Context): List<File> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("training_") && it.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Get a summary report for all training files
     */
    fun generateReport(context: Context): String {
        val files = listTrainingFiles(context)
        if (files.isEmpty()) {
            return "No training files found."
        }

        val sb = StringBuilder()
        sb.appendLine("=== ML Training Data Report ===")
        sb.appendLine("Files found: ${files.size}")
        sb.appendLine()

        var totalSamples = 0L
        val aggregatedLabels = mutableMapOf<Int, Long>()

        for (file in files) {
            val stats = analyzeFile(file)
            if (stats != null) {
                sb.appendLine("📁 ${file.name}")
                sb.appendLine("   Samples: ${stats.totalSamples}")
                sb.appendLine("   Labels: ${formatLabelDistribution(stats.labelDistribution)}")
                sb.appendLine("   Speed range: ${stats.speedRange.first.format(1)} - ${stats.speedRange.second.format(1)} km/h")
                sb.appendLine("   Valid: ${if (stats.hasValidLabels) "✅" else "❌ (all NORMAL)"}")
                sb.appendLine()

                totalSamples += stats.totalSamples
                for ((label, count) in stats.labelDistribution) {
                    aggregatedLabels[label] = (aggregatedLabels[label] ?: 0) + count
                }
            }
        }

        sb.appendLine("=== TOTAL ===")
        sb.appendLine("Total samples: $totalSamples")
        sb.appendLine("Label distribution:")
        for ((label, count) in aggregatedLabels.toSortedMap()) {
            val percent = (count.toDouble() / totalSamples * 100).format(1)
            val labelName = when (label) {
                0 -> "NORMAL"
                1 -> "HARSH_ACCELERATION"
                2 -> "HARSH_BRAKING"
                3 -> "UNSTABLE_RIDE"
                else -> "UNKNOWN"
            }
            sb.appendLine("   $labelName: $count ($percent%)")
        }

        return sb.toString()
    }

    private fun formatLabelDistribution(distribution: Map<Int, Long>): String {
        return distribution.entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key}:${it.value}" }
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}

