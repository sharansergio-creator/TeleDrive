# 🧪 TESTING & VALIDATION GUIDE

## ✅ QUICK VALIDATION CHECKLIST

Before field testing, verify these in code:

- [ ] Line 329: `minSpeedForEvents = 10f` (not conditional)
- [ ] Line 461: `stdAccel >= 2.0f` (absolute threshold)
- [ ] Line 485: `stdAccel < 2.0f` (LOW variance for braking)
- [ ] Line 510: `unstableCounter >= 1` (single window)
- [ ] Line 494-495: `> 5.0f` (reset threshold)

---

## 🧪 FIELD TEST PROTOCOL

### **Test 1: Bumpy Road Detection**

**Objective:** Verify oscillation patterns trigger UNSTABLE_RIDE

**Setup:**
- Location: Unpaved/rough road OR road with potholes
- Speed: 15-20 km/h (above minimum threshold)
- Duration: 2-3 minutes continuous riding

**Execution:**
1. Start ride tracking
2. Ride on bumpy surface at steady 15-20 km/h
3. Note when events are detected
4. Monitor real-time display

**Expected Results:**
```
✅ UNSTABLE_RIDE events triggered
✅ stdAccel > 2.0 (check logs)
✅ meanGyro > 0.35 (check logs)
❌ HARSH_BRAKING should NOT appear (unless you actually brake)
```

**Log Verification:**
```bash
adb logcat | grep "UNSTABLE_DEBUG"
```
Look for:
```
std=2.5-3.5, gyro=0.4-0.6, counter=1-3, confirmed=true
```

**Pass Criteria:**
- 80%+ of bumpy sections → UNSTABLE_RIDE
- Braking false positives < 20%

---

### **Test 2: True Braking Detection**

**Objective:** Verify clean deceleration triggers HARSH_BRAKING

**Setup:**
- Location: Smooth road
- Initial speed: 25-30 km/h
- Action: Smooth, controlled braking

**Execution:**
1. Accelerate to 25-30 km/h
2. Apply brakes smoothly (not emergency stop)
3. Decelerate to ~10 km/h over 2-3 seconds
4. Note event detection

**Expected Results:**
```
✅ HARSH_BRAKING event triggered
✅ stdAccel < 2.0 (directional, not oscillatory)
✅ minForwardAccel < -3.0 (deceleration)
❌ UNSTABLE_RIDE should NOT appear
```

**Log Verification:**
```bash
adb logcat | grep "FINAL_PIPELINE"
```
Look for:
```
EVENT=HARSH_BRAKING spd=25-15 std=1.2-1.8
```

**Pass Criteria:**
- Clean braking → HARSH_BRAKING (100%)
- stdAccel consistently < 2.0
- No unstable false positives

---

### **Test 3: Low-Speed Filter**

**Objective:** Verify all harsh events blocked below 10 km/h

**Setup:**
- Location: Any surface (smooth or bumpy)
- Speed: 5-9 km/h
- Duration: 1-2 minutes

**Execution:**
1. Ride slowly (5-9 km/h)
2. Try to trigger events (bumpy surface, brake, accelerate)
3. Monitor event detection

**Expected Results:**
```
✅ ALL events → NORMAL
✅ Speed filter blocks everything below 10 km/h
✅ No HARSH_BRAKING false positives
✅ No UNSTABLE_RIDE at low speed
```

**Log Verification:**
```bash
adb logcat | grep "Speed check"
```
Look for:
```
speed < minSpeedForEvents -> NORMAL
```

**Pass Criteria:**
- 0% harsh events at speeds < 10 km/h
- System shows NORMAL state only

---

### **Test 4: Mixed Scenario**

**Objective:** Verify all event types detected correctly in sequence

**Setup:**
- Location: Mixed terrain (smooth + bumpy sections)
- Route: ~5 km with varied conditions
- Actions: Normal riding with acceleration and braking

**Execution:**
1. Start ride
2. Accelerate smoothly (0 → 25 km/h)
3. Ride on bumpy section (15-20 km/h)
4. Return to smooth road
5. Brake smoothly (25 → 10 km/h)
6. End ride

**Expected Results:**
```
Phase 1 (Accel):  HARSH_ACCELERATION ✅
Phase 2 (Bumpy):  UNSTABLE_RIDE ✅
Phase 3 (Smooth): NORMAL ✅
Phase 4 (Brake):  HARSH_BRAKING ✅
```

**Pass Criteria:**
- All 4 states detected in sequence
- No misclassification (bumpy ≠ braking)
- Clean transitions between states

---

## 📊 LOG ANALYSIS

### **Key Log Tags:**

```bash
# Overall detection pipeline
adb logcat | grep "FINAL_PIPELINE"

# Unstable detection details
adb logcat | grep "UNSTABLE_DEBUG"

# State machine transitions
adb logcat | grep "STATE_MACHINE"

# Feature values
adb logcat | grep "FORWARD_DEBUG"
```

### **Expected Log Patterns:**

#### **Bumpy Road:**
```
UNSTABLE_DEBUG: std=2.8, gyro=0.45, counter=1, confirmed=true
FINAL_PIPELINE: EVENT=UNSTABLE_RIDE spd=18 std=2.8
```

#### **True Braking:**
```
UNSTABLE_DEBUG: std=1.5, gyro=0.30, counter=0, confirmed=false
FINAL_PIPELINE: EVENT=HARSH_BRAKING spd=22 std=1.5
```

#### **Low Speed:**
```
FINAL_PIPELINE: EVENT=NORMAL spd=7 std=2.2
(Speed filter blocked harsh events)
```

---

## 📈 METRICS TO TRACK

### **Event Statistics (End of Ride):**

```kotlin
harshAccelerationCount: X
harshBrakingCount: Y
unstableRideCount: Z
```

**Expected Ratios (typical ride):**

| Scenario | Accel | Brake | Unstable | Comment |
|----------|-------|-------|----------|---------|
| Smooth road | 2-4 | 2-4 | 0-1 | Normal |
| Mixed terrain | 3-5 | 3-5 | 5-10 | Bumpy sections |
| City riding | 5-8 | 5-8 | 2-4 | Stop/start |

**Red Flags (indicates potential issue):**

❌ `harshBrakingCount > 15` on bumpy road → Fix may not be working  
❌ `unstableRideCount = 0` on rough terrain → Unstable not detecting  
❌ Events at speeds < 10 km/h → Speed filter not working

---

## 🔍 DATA VALIDATION (CSV Analysis)

If you have existing ride session CSVs, validate the fix logic:

### **Analysis Script (Python):**

```python
import pandas as pd

# Load session data
df = pd.read_csv('ride_session_X.csv')

# Filter conditions
high_variance = df['stdAccel'] >= 2.0
low_variance = df['stdAccel'] < 2.0
low_speed = df['speed'] < 10

# Expected classifications
bumpy_pattern = high_variance & (df['meanGyro'] > 0.35)
braking_pattern = low_variance & (df['minForwardAccel'] < -3.0)

# Analysis
print("High variance samples (should be UNSTABLE):")
print(df[bumpy_pattern]['label'].value_counts())

print("\nLow variance + decel (should be BRAKING):")
print(df[braking_pattern]['label'].value_counts())

print("\nLow speed samples (should be NORMAL):")
print(df[low_speed]['label'].value_counts())
```

**Expected Output:**
```
High variance samples (should be UNSTABLE):
UNSTABLE_RIDE      85%
NORMAL             15%

Low variance + decel (should be BRAKING):
HARSH_BRAKING      90%
NORMAL             10%

Low speed samples (should be NORMAL):
NORMAL            100%
```

---

## 🎯 ACCEPTANCE CRITERIA

### **Must Pass (Critical):**

1. **Bumpy Road → UNSTABLE**
   - 70%+ detection rate on rough terrain
   - < 20% braking false positives

2. **True Braking → BRAKING**
   - 100% detection on smooth deceleration
   - stdAccel < 2.0 consistently

3. **Low Speed → NORMAL**
   - 100% event blocking below 10 km/h
   - Zero harsh events at 5-9 km/h

### **Should Pass (Important):**

4. **State Transitions**
   - Clean transitions between event types
   - No rapid flickering NORMAL ↔ EVENT

5. **Event Counts**
   - Braking count reduced by 60-80% on bumpy roads
   - Unstable count increased by 200-300%

### **Nice to Have (Optional):**

6. **User Experience**
   - Drivers notice fewer "false brake warnings"
   - Unstable ride alerts on rough roads make sense
   - Overall system feels more accurate

---

## 🚨 FAILURE SCENARIOS

### **If bumpy roads still trigger BRAKING:**

**Check:**
1. Line 485: Is it `stdAccel < 2.0f`? (not `> 1.0f`)
2. Logs: What is actual stdAccel value?
3. Speed: Is speed > 10 km/h?

**Debug:**
```bash
adb logcat | grep "UNSTABLE_DEBUG"
adb logcat | grep "std=.*gyro="
```

Look for: `std > 2.0` but `candidate=false` → threshold issue

---

### **If true braking NOT detected:**

**Check:**
1. Threshold: Is `minForwardAccel < -3.0`?
2. Variance: Is `stdAccel < 2.0`? (might be too strict)
3. Speed: Is braking happening above 10 km/h?

**Potential adjustment:**
```kotlin
// If 2.0 is too strict, try:
features.stdAccel < 2.5f  // Slightly more permissive
```

---

### **If low-speed events still occur:**

**Check:**
1. Line 329: Is it `10f`? (not conditional)
2. Line 527: Is speed check first priority?
3. GPS: Is speed reading accurate?

**Debug:**
```bash
adb logcat | grep "SERVICE_SPEED"
```

---

## ✅ SIGN-OFF CHECKLIST

After testing, verify:

- [ ] Bumpy road test passed (70%+ UNSTABLE)
- [ ] True braking test passed (100% BRAKING)
- [ ] Low-speed filter test passed (0% harsh events)
- [ ] Mixed scenario test passed (all types detected)
- [ ] Logs show correct stdAccel thresholds
- [ ] Event counts match expected ratios
- [ ] No compilation errors or crashes
- [ ] User experience improved (subjective)

---

## 📝 TEST REPORT TEMPLATE

```markdown
# Classification Bug Fix - Test Report

**Date:** [Date]
**Tester:** [Name]
**Device:** [Android device model]
**Build:** [Git commit hash]

## Test Results

### Test 1: Bumpy Road
- Location: [describe]
- Duration: [X minutes]
- Result: ✅/❌
- UNSTABLE detection rate: [X%]
- BRAKING false positives: [X%]
- Notes: [observations]

### Test 2: True Braking
- Location: [describe]
- Braking events: [X count]
- Result: ✅/❌
- Detection rate: [X%]
- Notes: [observations]

### Test 3: Low-Speed Filter
- Speed range: [X-Y km/h]
- Duration: [X minutes]
- Result: ✅/❌
- Harsh events detected: [X count] (should be 0)
- Notes: [observations]

### Test 4: Mixed Scenario
- Route: [describe]
- Distance: [X km]
- Result: ✅/❌
- Event sequence: [list]
- Notes: [observations]

## Event Statistics

| Event Type | Count | Expected Range | Pass/Fail |
|------------|-------|----------------|-----------|
| Harsh Accel | X | 2-8 | ✅/❌ |
| Harsh Brake | X | 2-8 | ✅/❌ |
| Unstable | X | 5-15 | ✅/❌ |

## Log Analysis

[Paste relevant log excerpts]

## Overall Assessment

✅ **PASS** / ❌ **FAIL**

**Issues Found:**
- [Issue 1]
- [Issue 2]

**Recommendations:**
- [Recommendation 1]
- [Recommendation 2]

**Sign-off:** [Name] - [Date]
```

---

## 🎯 SUCCESS CRITERIA

**Fix is validated when:**

1. Bumpy roads → UNSTABLE_RIDE (not braking) ✅
2. True braking → HARSH_BRAKING (preserved) ✅
3. Low speed → NORMAL (all events blocked) ✅
4. System stability → No crashes or errors ✅
5. User experience → Improved accuracy feedback ✅

**Status:** Ready for field testing 🚀

---

**Next Steps:**
1. Deploy build to test device
2. Execute test protocol (Tests 1-4)
3. Collect logs and statistics
4. Complete test report
5. Sign off if all criteria met

