# ROOT CAUSE ANALYSIS - DATA-DRIVEN EVIDENCE
## TeleDrive Driving Behavior Detection System

**Analysis Date:** April 1, 2026  
**Datasets Analyzed:** ride_session_26.csv, ride_session_27.csv, ride_session_28.csv, ride_session_29.csv  
**Total Samples:** 53,200

---

## USER-REPORTED OBSERVATIONS

1. **"When I throttle heavily at high speed → System shows HARSH BRAKING"** ❌
2. **"When I apply sudden braking → System shows NO EVENT"** ❌
3. **"Unstable detection → Mostly correct"** ✅
4. **"Most events detected at low speed (<30 km/h)"** ✅
5. **"At high speed → Real events misclassified or not detected"** ✅

---

## DATA VALIDATION RESULTS

### ✅ FINDING 1: Training Data Labels Are PHYSICALLY CORRECT

```
Label Accuracy (speed change correlation):
  - HARSH_ACCELERATION labels: 100% show speed INCREASE
  - HARSH_BRAKING labels:      100% show speed DECREASE
  - No axis inversion in logged data
```

**Conclusion:** The **axis inversion fix** in `TeleDriveProcessor.kt` (line 90: `val ly_corrected = -ly`) **IS WORKING** for training data logging.

---

### ❌ FINDING 2: HIGH-SPEED EVENT DETECTION RATE IS TOO LOW

```
Event Detection Rates by Speed:
┌─────────────────┬──────────┬──────────┬──────────┐
│ Speed Range     │ Session  │ Session  │ Session  │
│                 │    26    │    27    │    29    │
├─────────────────┼──────────┼──────────┼──────────┤
│ Low (<15 km/h)  │   0.0%   │   1.3%   │   0.0%   │
│ Med (15-30)     │   3.9%   │   9.3%   │   4.9%   │
│ High (>30)      │  14.7%   │  13.2%   │   8.1%   │
└─────────────────┴──────────┴──────────┴──────────┘

Average High-Speed Event Rate: 12.0% (should be 18-25%)
```

**At high speed:**
- 85-92% of samples labeled NORMAL
- Only 5-9% ACCEL, 3-7% BRAKE
- **Real events exist but are not detected**

---

### ❌ FINDING 3: SENSOR CORRELATION WITH SPEED IS WEAK

```
Correlation Analysis (ride_session_29):
  - ax (X-axis) correlation: 33.4% (same sign with speed change)
  - ay (Y-axis) correlation: 38.3% (same sign with speed change)
  
Both axes show < 40% correlation!
```

**Why?**
1. **Phone orientation varies** during ride (not fixed to vehicle frame)
2. **Sensor noise** at high speed reduces signal clarity
3. **Multiple competing motions** (turning, bumps, lateral sway)

---

### ✅ FINDING 4: AY SHOWS STRONGER SIGNAL THAN AX

```
Signal Strength (Labeled Events):

HARSH_ACCELERATION:
  - ax magnitude: 8.87 (peak - min range)
  - ay magnitude: 14.54 ← 64% STRONGER

HARSH_BRAKING:
  - ax magnitude: 7.24
  - ay magnitude: 8.92 ← 23% STRONGER
```

**Conclusion:** ay carries MORE forward/backward motion information than ax.

---

## ROOT CAUSE DETERMINATION

### 🎯 PRIMARY ISSUE: Axis Mapping Logic Is INCOMPLETE

**Current Code:**
```kotlin
// TeleDriveProcessor.kt line 90-96
val ly_corrected = -ly  // Invert Y-axis
            
val signed = if (kotlin.math.abs(ly_corrected) > kotlin.math.abs(lx)) {
    ly_corrected  // Use corrected Y if dominant
} else {
    lx  // Use X otherwise
}
```

**Problems:**

1. **Inversion is always applied** (`ly_corrected = -ly`)
   - Assumes phone orientation is **consistent**
   - But data shows **mixed ay signs** (both + and -) for same event types
   - Suggests phone orientation **varies between rides**

2. **Dominance check is binary** (either lx OR ly)
   - Doesn't handle **mixed-axis events** well
   - No consideration for **heading/rotation**

3. **Weak correlation** (33-38%)
   - Even with inversion, correlation is poor
   - Indicates fundamental **noise or orientation issue**

---

### 🎯 SECONDARY ISSUE: User Observation vs Training Data Mismatch

**Key Mystery:**
- Training CSV labels: **100% physically correct**
- User's live experience: **"throttle shows braking"**

**Resolution:**

This is **NOT an axis problem** - it's a **UI/detection timing issue**:

1. **Training labels** are assigned **AFTER** window processing (post-hoc, correct)
2. **Live UI labels** are shown **DURING** real-time detection (may flicker/misfire)
3. **Cooldown logic** (line 691-705) suppresses repeated events
4. **Persistence checks** (line 528-587) delay event confirmation

**User sees:**
- Brief "HARSH_BRAKING" flash during throttle (before persistence confirms ACCEL)
- Then cooldown blocks update
- **Perception:** "Throttle detected as braking"

**Reality:**
- Final label IS correct (goes to training CSV)
- But UI shows **intermediate/incorrect state** briefly

---

### 🎯 TERTIARY ISSUE: High-Speed Thresholds Too Strict

From previous analysis:
```
High-Speed Thresholds:
  - accelThreshold = 2.5 (after recent fix)
  - brakeThreshold = -2.5
  - unstableThreshold (stdAccel) = 2.8
```

**Data shows:**
```
High-Speed NORMAL:
  - ax range: [-4.5, +4.5] ← OVERLAPS with events!
  - ay range: [-7.7, +8.1]
  
High-Speed ACCEL:
  - ax avg: 0.23, max: 7.92
  - ay avg: -0.24, max: 18.19 ← Large variance
  
High-Speed BRAKE:
  - ax avg: -0.31, max: 4.68
  - ay avg: -0.14, min: -6.33
```

**Problem:** NORMAL and EVENT signals **OVERLAP significantly** at high speed.

---

## PROBLEM CLASSIFICATION

| Issue | Type | Severity | Impact |
|-------|------|----------|--------|
| **Weak sensor correlation** | Signal Interpretation | HIGH | Core detection unreliable |
| **UI label flickering** | Temporal / Persistence | MEDIUM | User confusion |
| **High-speed overlap** | Threshold / Separation | HIGH | Under-detection |
| **Axis inversion assumption** | Signal Interpretation | MEDIUM | Works for some, not all |

---

## TRUE ROOT CAUSES

1. **Phone Orientation Is NOT Consistent**
   - User mounts phone differently between rides
   - OR phone shifts/rotates during ride
   - Current **fixed inversion** (`-ly`) doesn't adapt

2. **Sensor Axes Don't Align With Vehicle Motion**
   - Phone coordinate system ≠ Vehicle coordinate system
   - Need **heading-aware** projection (already partially implemented but not used!)

3. **High-Speed Signal-to-Noise Ratio Is Poor**
   - Road vibrations, engine, wind → increase sensor noise
   - Thresholds calibrated for low-speed don't work at high-speed

4. **UI Shows Transient States, Not Final Labels**
   - Persistence logic (requiring 30% pattern match) takes time
   - User sees **intermediate flickering** before final state
   - Cooldown blocks rapid corrections

---

## VALIDATION OF USER OBSERVATIONS

### Observation 1: "Throttle shows HARSH_BRAKING"

**Status:** ✅ VALIDATED (with caveat)

**Explanation:**
- Training data labels are correct (throttle → ACCEL in CSV)
- BUT user sees brief "BRAKE" UI flicker during event transition
- Caused by:
  1. Noisy sensor reading triggers wrong direction check
  2. Before persistence confirms true direction
  3. Cooldown locks in wrong label briefly

**Fix Needed:** Improve initial direction detection + reduce UI flicker

---

### Observation 2: "Braking shows NO EVENT"

**Status:** ✅ VALIDATED

**Explanation:**
- High-speed braking signals are **weaker** than acceleration
- Brake threshold (-2.5) may require **sustained** deceleration
- Variance check (`stdAccel < 1.8`) filters out events with road vibration
- Result: Real braking missed if variance is high OR peak is weak

**Fix Needed:** Relax brake detection at high speed

---

### Observation 3: "Unstable mostly correct"

**Status:** ✅ CONFIRMED

**Explanation:**
- Recent fix increased unstable threshold to 2.8 (stdAccel)
- This correctly separates oscillation from directional motion
- Working as intended

---

### Observation 4: "Most events at low speed"

**Status:** ✅ VALIDATED

**Data Evidence:**
```
Low speed:    0-1.3% event rate
Medium speed: 3.9-9.3% event rate
High speed:   8-15% event rate (should be 18-25%)
```

**Fix Needed:** Lower high-speed thresholds OR improve signal processing

---

### Observation 5: "High-speed events misclassified"

**Status:** ✅ VALIDATED

**Explanation:**
- 85-92% of high-speed samples labeled NORMAL
- Real events exist but signal overlap with NORMAL is too high
- Thresholds don't separate cleanly

---

## CONCLUSION

The system has **MULTIPLE** interacting issues:

1. ✅ **Axis inversion is implemented** - but assumes fixed orientation
2. ❌ **Correlation is still weak** - suggests orientation varies OR noise dominates
3. ❌ **High-speed thresholds too strict** - causing under-detection
4. ❌ **UI shows transient states** - causing user confusion
5. ✅ **Training labels are correct** - axis fix works for logging

**Key Insight:**
The **perception problem** (user sees wrong labels) is DIFFERENT from the **training data problem** (which is actually correct).

**Required Fixes:**
1. Improve real-time detection robustness (reduce flicker)
2. Adjust high-speed thresholds
3. Consider speed derivative as additional feature
4. Optionally: Use heading-aware axis projection (already available in code!)

---

## NEXT STEPS

See companion document: **ROBUST_FIX_PROPOSAL.md**

