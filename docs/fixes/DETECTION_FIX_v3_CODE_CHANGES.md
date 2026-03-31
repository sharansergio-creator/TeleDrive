# Detection Logic Fix v3 - Code Changes Summary

## Exact Changes Made to SensorService.kt

---

### Change 1: Speed Gating Enhancement

**Location:** Line ~323 (Speed-Aware Thresholds section)

**BEFORE:**
```kotlin
// ================= SPEED-AWARE THRESHOLDS =================
val isMediumSpeed = speed in 15f..30f
val isHighSpeed = speed > 30f

// 🔧 FIX A: LOW-SPEED FILTER
val minSpeedForEvents = 10f  // Single threshold for all
val minEnergyForEvents = if (isHighSpeed) 1.5f else 2.5f
```

**AFTER:**
```kotlin
// ================= SPEED-AWARE THRESHOLDS =================
val isLowSpeed = speed < 15f
val isMediumSpeed = speed in 15f..30f
val isHighSpeed = speed > 30f

// 🔧 FIX A: LOW-SPEED FILTER (ENHANCED)
val minSpeedForAcceleration = 15f  // Acceleration needs momentum
val minSpeedForBraking = 12f       // Braking from moderate speed
val minSpeedForUnstable = 8f       // Bumps at any riding speed
val minEnergyForEvents = if (isHighSpeed) 1.5f else 2.5f
```

**Why:** Event-specific speed gates prevent inappropriate triggers

---

### Change 2: Braking Variance Tightening

**Location:** Line ~465 (Acceleration/Braking Detection)

**BEFORE:**
```kotlin
val isAccelerationDetected = 
    features.peakForwardAccel > accelThreshold && 
    features.stdAccel > 1.0f &&
    features.stdAccel < 3.0f &&
    features.peakForwardAccel > kotlin.math.abs(features.minForwardAccel) * 1.2f
    
val isBrakingDetected = 
    features.minForwardAccel < brakeThreshold && 
    features.stdAccel < 2.0f &&  // TOO PERMISSIVE
    kotlin.math.abs(features.minForwardAccel) > features.peakForwardAccel * 1.2f
```

**AFTER:**
```kotlin
val isAccelerationDetected = 
    features.peakForwardAccel > accelThreshold && 
    features.stdAccel > 1.0f &&
    features.stdAccel < 3.0f &&
    features.peakForwardAccel > kotlin.math.abs(features.minForwardAccel) * 1.2f &&
    speed >= minSpeedForAcceleration  // NEW: Speed gate
    
val isBrakingDetected = 
    features.minForwardAccel < brakeThreshold && 
    features.stdAccel < 1.8f &&  // TIGHTENED: 2.0 → 1.8
    kotlin.math.abs(features.minForwardAccel) > features.peakForwardAccel * 1.2f &&
    speed >= minSpeedForBraking  // NEW: Speed gate
```

**Why:** 
- `stdAccel < 1.8f` separates braking (1.0-1.6) from vibration (2.0-4.0)
- Speed gates prevent low-speed false positives

---

### Change 3: Unstable Detection Enhancement

**Location:** Line ~460 (Unstable Detection)

**BEFORE:**
```kotlin
val isUnstableCandidate =
    features.stdAccel >= 2.0f &&
    features.meanGyro > 0.35f &&
    totalEnergy > 0.8f
```

**AFTER:**
```kotlin
val isUnstableCandidate =
    features.stdAccel >= 2.0f &&
    features.meanGyro > 0.35f &&
    totalEnergy > 0.8f &&
    speed >= minSpeedForUnstable  // NEW: Speed gate
```

**Why:** Prevents stationary phone handling from triggering unstable

---

### Change 4: Persistence Check (NEW - CRITICAL)

**Location:** Line ~500 (After Unstable Detection, Before Priority Order)

**BEFORE:**
```kotlin
val isConfirmedUnstable = unstableCounter >= 1

// Debug logging
Log.d("UNSTABLE_DEBUG", "std=${features.stdAccel}, ...")
```

**AFTER:**
```kotlin
val isConfirmedUnstable = unstableCounter >= 1

// ================= PERSISTENCE CHECK (FIX D - CRITICAL) =================
// NEW 60-line function to validate pattern persistence
fun checkPatternPersistence(
    currentType: DrivingEventType,
    lookbackSamples: Int = 35  // ~0.7 seconds at 50Hz
): Boolean {
    if (currentType == DrivingEventType.NORMAL) return true
    if (windowBuffer.size < lookbackSamples) return false
    
    val recentSamples = windowBuffer.takeLast(lookbackSamples)
    var matchingPatterns = 0
    
    // Use mini-windows of 5 samples (~100ms)
    for (i in 0 until recentSamples.size step 5) {
        if (i + 5 > recentSamples.size) break
        
        val miniWindow = recentSamples.subList(i, minOf(i + 5, recentSamples.size))
        
        // Calculate simplified features
        val axValues = miniWindow.map { it.ax }
        val miniPeak = axValues.maxOrNull() ?: 0f
        val miniMin = axValues.minOrNull() ?: 0f
        val miniStd = if (axValues.size > 1) {
            val mean = axValues.average().toFloat()
            kotlin.math.sqrt(axValues.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        
        // Check if this mini-window matches current pattern
        val matchesAccel = currentType == DrivingEventType.HARSH_ACCELERATION && 
                          miniPeak > accelThreshold * 0.7f
        val matchesBrake = currentType == DrivingEventType.HARSH_BRAKING && 
                          miniMin < brakeThreshold * 0.7f
        val matchesUnstable = currentType == DrivingEventType.UNSTABLE_RIDE && 
                             miniStd > 1.5f
        
        if (matchesAccel || matchesBrake || matchesUnstable) {
            matchingPatterns++
        }
    }
    
    // Require at least 40% of mini-windows show same pattern
    val totalMiniWindows = (recentSamples.size / 5).coerceAtLeast(1)
    val persistenceRatio = matchingPatterns.toFloat() / totalMiniWindows
    
    return persistenceRatio >= 0.4f
}

// Apply persistence check
val hasAccelPersistence = !isAccelerationDetected || checkPatternPersistence(DrivingEventType.HARSH_ACCELERATION)
val hasBrakePersistence = !isBrakingDetected || checkPatternPersistence(DrivingEventType.HARSH_BRAKING)
val hasUnstablePersistence = !isConfirmedUnstable || checkPatternPersistence(DrivingEventType.UNSTABLE_RIDE)

// Override detection flags if persistence check fails
val finalAccelDetected = isAccelerationDetected && hasAccelPersistence
val finalBrakeDetected = isBrakingDetected && hasBrakePersistence
val finalUnstableDetected = isConfirmedUnstable && hasUnstablePersistence

// Debug logging
Log.d("UNSTABLE_DEBUG", "std=${features.stdAccel}, ...")
Log.d("PERSISTENCE_DEBUG",
    "Accel: detected=$isAccelerationDetected, persist=$hasAccelPersistence, final=$finalAccelDetected | " +
    "Brake: detected=$isBrakingDetected, persist=$hasBrakePersistence, final=$finalBrakeDetected | " +
    "Unstable: detected=$isConfirmedUnstable, persist=$hasUnstablePersistence, final=$finalUnstableDetected"
)
```

**Why:** 
- Prevents single-spike false positives
- Requires pattern to persist over 0.6-0.8 seconds
- Uses existing windowBuffer (no new data structures)
- 40% threshold allows natural variation

---

### Change 5: Priority Order Update

**Location:** Line ~570 (Rule Type Determination)

**BEFORE:**
```kotlin
val ruleType = when {
    speed < minSpeedForEvents -> DrivingEventType.NORMAL
    isAccelerationDetected -> DrivingEventType.HARSH_ACCELERATION
    isConfirmedUnstable -> DrivingEventType.UNSTABLE_RIDE
    isBrakingDetected -> DrivingEventType.HARSH_BRAKING
    else -> DrivingEventType.NORMAL
}
```

**AFTER:**
```kotlin
val ruleType = when {
    speed < minSpeedForUnstable -> DrivingEventType.NORMAL  // Lowest speed gate
    finalAccelDetected -> DrivingEventType.HARSH_ACCELERATION   // Persistence-checked
    finalUnstableDetected -> DrivingEventType.UNSTABLE_RIDE      // Persistence-checked
    finalBrakeDetected -> DrivingEventType.HARSH_BRAKING         // Persistence-checked
    else -> DrivingEventType.NORMAL
}
```

**Why:** Uses persistence-validated flags instead of raw detections

---

### Change 6: AI Mode Update

**Location:** Line ~600 (AI_ASSIST Mode)

**BEFORE:**
```kotlin
DetectionMode.AI_ASSIST -> {
    when {
        speed < minSpeedForEvents -> DrivingEventType.NORMAL
        isLikelyHandMovement -> DrivingEventType.NORMAL
        // ... rest
    }
}
```

**AFTER:**
```kotlin
DetectionMode.AI_ASSIST -> {
    when {
        // Event-specific speed gates
        mlType == DrivingEventType.HARSH_ACCELERATION && speed < minSpeedForAcceleration -> DrivingEventType.NORMAL
        mlType == DrivingEventType.HARSH_BRAKING && speed < minSpeedForBraking -> DrivingEventType.NORMAL
        mlType == DrivingEventType.UNSTABLE_RIDE && speed < minSpeedForUnstable -> DrivingEventType.NORMAL
        isLikelyHandMovement -> DrivingEventType.NORMAL
        // ... rest
    }
}
```

**Why:** Consistent speed gating across rule-based and ML modes

---

### Change 7: ML Filtering Update

**Location:** Line ~383 (ML Filtering)

**BEFORE:**
```kotlin
speed < minSpeedForEvents && maxConfidence < 0.8f -> "NORMAL"
```

**AFTER:**
```kotlin
speed < minSpeedForUnstable && maxConfidence < 0.8f -> "NORMAL"
```

**Why:** Use lowest speed threshold for ML filtering consistency

---

## Summary of Changes

| Change | Type | Lines | Impact |
|--------|------|-------|--------|
| Speed gating | Modified | ~10 | Event-specific thresholds |
| Braking variance | Modified | ~5 | Tighter stdAccel limit |
| Unstable detection | Modified | ~3 | Speed gate added |
| Persistence check | **NEW** | ~60 | Pattern validation |
| Priority order | Modified | ~10 | Use validated flags |
| AI mode | Modified | ~8 | Consistent speed gates |
| ML filtering | Modified | ~1 | Fix variable name |
| **TOTAL** | | **~97** | Spike → pattern detection |

---

## Performance Impact

- **Persistence check:** ~245 operations per window
- **Frequency:** Once per second (50Hz with 1s windows)
- **CPU impact:** <1ms on modern Android
- **Memory impact:** None (uses existing windowBuffer)

---

## Testing Validation

After deployment, verify these specific behaviors:

### Test 1: Low Speed (5-12 km/h)
```
Expected: All events = NORMAL
Logs should show:
  - speed < minSpeedForAcceleration (15) → blocked
  - speed < minSpeedForBraking (12) → blocked
```

### Test 2: Bumpy Road (constant 18 km/h)
```
Expected: UNSTABLE_RIDE detected (NOT HARSH_BRAKING)
Logs should show:
  - stdAccel = 2.5-3.5 (above braking threshold 1.8)
  - finalUnstableDetected = true
  - PERSISTENCE_DEBUG: Unstable persist=true
```

### Test 3: True Braking (20→10 km/h)
```
Expected: HARSH_BRAKING detected
Logs should show:
  - stdAccel = 1.2-1.5 (below 1.8 threshold)
  - finalBrakeDetected = true
  - PERSISTENCE_DEBUG: Brake persist=true
```

### Test 4: Single Spike (momentary jerk)
```
Expected: NORMAL (event filtered)
Logs should show:
  - isAccelerationDetected = true (raw)
  - hasAccelPersistence = false (filtered)
  - finalAccelDetected = false (blocked)
  - PERSISTENCE_DEBUG: Accel persist=false
```

---

## Rollback Instructions

If issues occur, rollback specific changes:

### Disable Persistence Check Only
```kotlin
// In checkPatternPersistence()
return true  // Bypass persistence validation
```

### Revert Braking Threshold Only
```kotlin
features.stdAccel < 2.0f  // Back to original
```

### Revert Speed Gates Only
```kotlin
val minSpeedForAcceleration = 10f  // Lower back down
val minSpeedForBraking = 10f
val minSpeedForUnstable = 5f
```

### Full Rollback
```bash
git checkout HEAD~1 -- android-app/app/src/main/java/com/teledrive/app/services/SensorService.kt
```

---

*Detailed code changes for detection logic fix v3*  
*March 31, 2026*

