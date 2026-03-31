# 🚨 QUICK FIX SUMMARY - Classification Bug

**Issue:** Bumpy roads → HARSH_BRAKING (wrong) | Should be → UNSTABLE_RIDE

---

## ✅ 4 FIXES APPLIED

### **FIX A: LOW-SPEED FILTER**
```kotlin
// Line 367
val minSpeedForEvents = 10f  // Block ALL harsh events below 10 km/h
```

### **FIX B: BRAKING VALIDATION**
```kotlin
// Line 481 - CRITICAL CHANGE
val isBrakingDetected = 
    features.minForwardAccel < brakeThreshold && 
    features.stdAccel < 2.0f &&  // ⬅️ Changed from > 1.0 to < 2.0
    kotlin.math.abs(features.minForwardAccel) > features.peakForwardAccel * 1.2f
```
**Why:** Braking must be directional (low variance), not oscillatory

### **FIX C: UNSTABLE DETECTION**
```kotlin
// Line 456
val isUnstableCandidate =
    features.stdAccel >= 2.0f &&  // ⬅️ Changed from range to absolute threshold
    features.meanGyro > 0.35f &&
    totalEnergy > 0.8f

// Line 504
val isConfirmedUnstable = unstableCounter >= 1  // ⬅️ Changed from 2 to 1
```
**Why:** Catch oscillation patterns (high variance) immediately

### **FIX D: PRIORITY ORDER**
```kotlin
// Line 520-537 - Already correct, enhanced comments
// Order: Speed → Acceleration → UNSTABLE → Braking → Normal
```

---

## 🎯 RESULT

| Scenario | BEFORE | AFTER |
|----------|--------|-------|
| Bumpy road (stdAccel=2.8) | HARSH_BRAKING ❌ | UNSTABLE_RIDE ✅ |
| True braking (stdAccel=1.5) | HARSH_BRAKING ✅ | HARSH_BRAKING ✅ |
| Low speed (5 km/h) | HARSH_BRAKING ❌ | NORMAL ✅ |

---

## 🔑 KEY INSIGHT

**Root Cause:** Braking was checking `stdAccel > 1.0` (allowing high variance)  
**Fix:** Changed to `stdAccel < 2.0` (requiring low variance)  
**Impact:** Oscillations (bumpy roads) now fail braking check → go to unstable

**Mutual Exclusivity:**
- `stdAccel >= 2.0` → UNSTABLE (oscillation)
- `stdAccel < 2.0` → BRAKING (directional)

---

## ✅ STATUS

- **Lines changed:** ~50 / 869
- **Functions modified:** 1 (processWindow)
- **Compilation:** ✅ No errors
- **Risk:** Low (minimal, surgical changes)
- **Ready for:** Field testing

---

**Full documentation:** `CLASSIFICATION_BUG_FIX.md`

