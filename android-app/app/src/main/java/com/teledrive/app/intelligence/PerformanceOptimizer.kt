package com.teledrive.app.intelligence

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Performance Optimizer
 * Manages battery, thermal, and ML inference optimization
 * 
 * Strategy:
 * - Adaptive sensor sampling based on battery level
 * - Thermal throttling detection
 * - Smart ML inference (skip when battery low)
 * - Camera trigger rate limiting
 */
object PerformanceOptimizer {
    
    private const val TAG = "PerformanceOptimizer"
    
    // Performance profiles
    enum class PerformanceProfile {
        HIGH_PERFORMANCE,   // Full features, battery > 50%
        BALANCED,           // Moderate features, battery 20-50%
        POWER_SAVER         // Minimal features, battery < 20%
    }
    
    // Thermal states
    enum class ThermalState {
        NORMAL,
        WARM,
        HOT,
        CRITICAL
    }
    
    private var currentProfile = PerformanceProfile.HIGH_PERFORMANCE
    private var thermalState = ThermalState.NORMAL
    
    // Rate limiting
    private var lastCameraTrigger = 0L
    private var lastMLInference = 0L
    
    // Counters for monitoring
    private var mlInferenceCount = 0
    private var cameraTriggerCount = 0
    
    /**
     * Get current performance profile based on battery
     */
    fun getCurrentProfile(context: Context): PerformanceProfile {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryLevel = getBatteryLevel(context)
        
        currentProfile = when {
            batteryLevel > 50 && !powerManager.isPowerSaveMode -> PerformanceProfile.HIGH_PERFORMANCE
            batteryLevel > 20 -> PerformanceProfile.BALANCED
            else -> PerformanceProfile.POWER_SAVER
        }
        
        return currentProfile
    }
    
    /**
     * Get battery level percentage
     */
    private fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    /**
     * Check if thermal throttling is active
     */
    fun checkThermalState(context: Context): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            thermalState = when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalState.NORMAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.WARM
                PowerManager.THERMAL_STATUS_MODERATE,
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NORMAL
            }
        }
        
        return thermalState
    }
    
    /**
     * Should run ML inference?
     * Performance optimization: skip in power saver or hot state
     */
    fun shouldRunMLInference(context: Context): Boolean {
        val profile = getCurrentProfile(context)
        val thermal = checkThermalState(context)
        val now = System.currentTimeMillis()
        
        // Rate limiting: max 1 inference per 500ms in balanced mode
        val minInterval = when (profile) {
            PerformanceProfile.HIGH_PERFORMANCE -> 100L
            PerformanceProfile.BALANCED -> 500L
            PerformanceProfile.POWER_SAVER -> 2000L
        }
        
        if (now - lastMLInference < minInterval) {
            return false
        }
        
        // Skip ML in power saver or critical thermal
        if (profile == PerformanceProfile.POWER_SAVER || thermal == ThermalState.CRITICAL) {
            return false
        }
        
        lastMLInference = now
        mlInferenceCount++
        return true
    }
    
    /**
     * Should trigger camera?
     * Aggressive rate limiting to save battery + storage
     */
    fun shouldTriggerCamera(context: Context): Boolean {
        val profile = getCurrentProfile(context)
        val thermal = checkThermalState(context)
        val now = System.currentTimeMillis()
        
        // Minimum 3 seconds between camera triggers
        val minInterval = when (profile) {
            PerformanceProfile.HIGH_PERFORMANCE -> 3000L
            PerformanceProfile.BALANCED -> 5000L
            PerformanceProfile.POWER_SAVER -> 10000L
        }
        
        if (now - lastCameraTrigger < minInterval) {
            Log.d(TAG, "Camera rate limited")
            return false
        }
        
        // Don't trigger camera if thermal is critical
        if (thermal == ThermalState.CRITICAL) {
            Log.d(TAG, "Camera blocked: thermal critical")
            return false
        }
        
        lastCameraTrigger = now
        cameraTriggerCount++
        return true
    }
    
    /**
     * Get recommended sensor sampling rate (Hz)
     */
    fun getSensorSamplingRate(context: Context): Int {
        val profile = getCurrentProfile(context)
        val thermal = checkThermalState(context)
        
        return when {
            thermal == ThermalState.CRITICAL -> 10 // Minimal
            thermal == ThermalState.HOT -> 25
            profile == PerformanceProfile.POWER_SAVER -> 25
            profile == PerformanceProfile.BALANCED -> 40
            else -> 50 // Full rate
        }
    }
    
    /**
     * Should use NNAPI for ML acceleration?
     */
    fun shouldUseNNAPI(context: Context): Boolean {
        // NNAPI available from Android 8.1+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return false
        }
        
        val profile = getCurrentProfile(context)
        
        // Only use NNAPI in high performance mode
        // (NNAPI can actually be slower on some devices)
        return profile == PerformanceProfile.HIGH_PERFORMANCE
    }
    
    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): String {
        return buildString {
            appendLine("Performance Stats:")
            appendLine("Profile: $currentProfile")
            appendLine("Thermal: $thermalState")
            appendLine("ML Inferences: $mlInferenceCount")
            appendLine("Camera Triggers: $cameraTriggerCount")
        }
    }
    
    /**
     * Reset counters (call at trip end)
     */
    fun resetCounters() {
        mlInferenceCount = 0
        cameraTriggerCount = 0
        lastCameraTrigger = 0L
        lastMLInference = 0L
    }
    
    /**
     * Optimization recommendations
     */
    fun getOptimizationRecommendations(context: Context): List<String> {
        val recommendations = mutableListOf<String>()
        val battery = getBatteryLevel(context)
        val thermal = checkThermalState(context)
        
        if (battery < 20) {
            recommendations.add("⚠️ Low battery: some features disabled to conserve power")
        }
        
        if (thermal == ThermalState.HOT || thermal == ThermalState.CRITICAL) {
            recommendations.add("🌡️ Device is hot: performance reduced to prevent overheating")
        }
        
        if (cameraTriggerCount > 10) {
            recommendations.add("📸 Many events captured: consider smoother riding to reduce storage usage")
        }
        
        return recommendations
    }
}

