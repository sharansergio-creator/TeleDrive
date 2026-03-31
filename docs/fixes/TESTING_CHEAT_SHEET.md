# 🚀 DETECTION FIX v3 - TESTING CHEAT SHEET

## ⚡ QUICK DEPLOY

```bash
cd D:/TeleDrive/android-app
./gradlew installDebug
adb logcat -s PERSISTENCE_DEBUG UNSTABLE_DEBUG STATE_MACHINE
```

---

## 🧪 5 TEST SCENARIOS

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 1 | **Low speed** | Ride at 5-12 km/h | All NORMAL ✅ |
| 2 | **Smooth accel** | Throttle 15→25 km/h | HARSH_ACCELERATION ✅ |
| 3 | **Bumpy road** | 18 km/h on rough road | UNSTABLE_RIDE ✅ (not BRAKE) |
| 4 | **True braking** | Brake 20→10 km/h | HARSH_BRAKING ✅ |
| 5 | **Phone handling** | Shake phone at stop | NORMAL ✅ |

---

## 📊 SUCCESS INDICATORS

### ✅ System Working if:
- Bumpy roads show "Unstable Ride Detected"
- Low-speed (<12 km/h) shows NORMAL
- Score in 50-85 range
- Logs show `persist=false` for spikes
- Logs show `persist=true` for real events

### ❌ Issues if:
- Still getting BRAKE on bumps → Lower variance to 1.6f
- Too few events → Lower persistence to 0.3f
- Unstable not triggering → Check UNSTABLE_DEBUG logs

---

## 🔧 QUICK TUNING (If Needed)

### Too Sensitive (too many events)
```kotlin
persistenceThreshold = 0.5f        // 0.4 → 0.5 (stricter)
minSpeedForAcceleration = 18f      // 15 → 18
brakeVarianceThreshold = 1.6f      // 1.8 → 1.6
```

### Too Conservative (too few events)
```kotlin
persistenceThreshold = 0.3f        // 0.4 → 0.3 (permissive)
minSpeedForAcceleration = 12f      // 15 → 12
brakeVarianceThreshold = 2.0f      // 1.8 → 2.0
```

---

## 📍 KEY THRESHOLDS

```
Speed Gates:
  Accel:   ≥15 km/h
  Brake:   ≥12 km/h
  Unstable: ≥8 km/h

Variance:
  Braking:  < 1.8 (directional)
  Unstable: ≥ 2.0 (oscillation)

Persistence:
  Lookback: 35 samples (0.7s)
  Match:    ≥40% of windows
```

---

## 🐛 DEBUG COMMANDS

```bash
# Watch filtering activity
adb logcat -s PERSISTENCE_DEBUG | Select-String "persist=false"

# Watch unstable detection
adb logcat -s UNSTABLE_DEBUG | Select-String "confirmed=true"

# Watch state changes
adb logcat -s STATE_MACHINE | Select-String "STATE="

# Full pipeline view
adb logcat -s PERSISTENCE_DEBUG UNSTABLE_DEBUG STATE_MACHINE ML_TRAINING
```

---

## 📈 EXPECTED RESULTS (1-2 min ride)

```
Before:
  Accel: 12, Brake: 11, Unstable: 0, Score: 18 ❌

After:
  Accel: 5, Brake: 3, Unstable: 6, Score: 68 ✅
```

---

## 🎯 YOUR SPECIFIC PROBLEM

**Reported:** Bumpy road @ 10 km/h → HARSH_BRAKING  
**Fixed by:**
1. Speed gate: 10 < 12 → blocked
2. Variance: 2.3 > 1.8 → not braking
3. Unstable: 2.3 ≥ 2.0 → detected
4. Result: NORMAL (if <12 km/h) or UNSTABLE (if ≥12 km/h) ✅

---

## ✅ CHECKLIST

- [x] Code applied
- [x] Build successful
- [ ] Deployed to device ← **DO THIS NOW**
- [ ] Test bumpy road ← **YOUR SCENARIO**
- [ ] Verify UNSTABLE (not BRAKE)
- [ ] Check score is realistic
- [ ] Report results

---

**📍 Location of files:**
- Code: `D:/TeleDrive/android-app/app/src/main/java/com/teledrive/app/services/SensorService.kt`
- Docs: `D:/TeleDrive/docs/fixes/`

**🚀 Ready to deploy and test!**

---

*One-page testing guide - v3*  
*March 31, 2026*

