import csv
import math
from collections import defaultdict

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_17.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_20.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_24.csv',
]

all_data = []
for fname in files:
    try:
        with open(fname, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                all_data.append({
                    'ax': float(row['ax']),
                    'speed': float(row['speed']),
                    'label': int(row['label'])
                })
    except:
        pass

print('='*70)
print('THRESHOLD MISMATCH ANALYSIS')
print('='*70)

# Current thresholds in code
print('\nCURRENT THRESHOLDS IN CODE:')
print('  HIGH SPEED (>30 km/h):')
print('    accelThreshold = 3.0 (peak must exceed this)')
print('    brakeThreshold = -3.0 (min must be below this)')
print('    instabilityThreshold = 0.8 (stdAccel)')
print('')

# Analyze what thresholds SHOULD be based on real data
print('='*70)
print('ACTUAL DATA PATTERNS (50-sample windows)')
print('='*70)

high_speed = [r for r in all_data if r['speed'] > 30]

# Group by label and calculate window features
label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

for label in [1, 2, 3, 0]:  # Events first, then NORMAL
    subset = [r for r in high_speed if r['label'] == label]
    if not subset:
        continue

    window_size = 50
    peaks = []
    mins = []
    stds = []

    for i in range(0, len(subset) - window_size, window_size):
        window = subset[i:i+window_size]
        ax_vals = [r['ax'] for r in window]

        peak = max(ax_vals)
        min_val = min(ax_vals)
        mean = sum(ax_vals) / len(ax_vals)
        var = sum((x - mean)**2 for x in ax_vals) / len(ax_vals)
        std = math.sqrt(var)

        peaks.append(peak)
        mins.append(min_val)
        stds.append(std)

    if not peaks:
        continue

    print(f'\n{label_names[label]} at HIGH SPEED:')
    print(f'  peakForwardAccel:')
    print(f'    avg={sum(peaks)/len(peaks):.3f}, min={min(peaks):.3f}, max={max(peaks):.3f}')
    print(f'  minForwardAccel:')
    print(f'    avg={sum(mins)/len(mins):.3f}, min={min(mins):.3f}, max={max(mins):.3f}')
    print(f'  stdAccel:')
    print(f'    avg={sum(stds)/len(stds):.3f}, min={min(stds):.3f}, max={max(stds):.3f}')

    # Check how many windows would pass current thresholds
    if label == 1:  # ACCEL
        passing = sum(1 for p in peaks if p > 3.0)
        print(f'  >> Would pass accelThreshold=3.0: {passing}/{len(peaks)} ({passing/len(peaks)*100:.1f}%)')
    elif label == 2:  # BRAKE
        passing = sum(1 for m in mins if m < -3.0)
        print(f'  >> Would pass brakeThreshold=-3.0: {passing}/{len(mins)} ({passing/len(mins)*100:.1f}%)')
    elif label == 3:  # UNSTABLE
        passing = sum(1 for s in stds if s >= 0.8)
        print(f'  >> Would pass instabilityThreshold=0.8: {passing}/{len(stds)} ({passing/len(stds)*100:.1f}%)')

# Suggest new thresholds
print('\n' + '='*70)
print('SUGGESTED THRESHOLD ADJUSTMENTS')
print('='*70)

# Calculate percentiles for ACCEL events
accel_subset = [r for r in high_speed if r['label'] == 1]
accel_peaks = []
for i in range(0, len(accel_subset) - 50, 50):
    window = accel_subset[i:i+50]
    ax_vals = [r['ax'] for r in window]
    accel_peaks.append(max(ax_vals))

if accel_peaks:
    accel_peaks_sorted = sorted(accel_peaks)
    accel_p25 = accel_peaks_sorted[len(accel_peaks_sorted)//4]
    accel_p50 = accel_peaks_sorted[len(accel_peaks_sorted)//2]

    print(f'\nACCELERATION:')
    print(f'  Current threshold: 3.0')
    print(f'  Data shows 25th percentile: {accel_p25:.2f}')
    print(f'  Data shows 50th percentile: {accel_p50:.2f}')
    print(f'  RECOMMENDED: 2.2 - 2.5 (captures real events without too much noise)')

# Calculate for BRAKE
brake_subset = [r for r in high_speed if r['label'] == 2]
brake_mins = []
for i in range(0, len(brake_subset) - 50, 50):
    window = brake_subset[i:i+50]
    ax_vals = [r['ax'] for r in window]
    brake_mins.append(min(ax_vals))

if brake_mins:
    brake_mins_sorted = sorted(brake_mins)
    brake_p25 = brake_mins_sorted[len(brake_mins_sorted)//4]
    brake_p50 = brake_mins_sorted[len(brake_mins_sorted)//2]

    print(f'\nBRAKING:')
    print(f'  Current threshold: -3.0')
    print(f'  Data shows 25th percentile: {brake_p25:.2f}')
    print(f'  Data shows 50th percentile: {brake_p50:.2f}')
    print(f'  RECOMMENDED: -2.2 to -2.5 (symmetric with accel)')

# Calculate for UNSTABLE
unstable_subset = [r for r in high_speed if r['label'] == 3]
unstable_stds = []
for i in range(0, len(unstable_subset) - 50, 50):
    window = unstable_subset[i:i+50]
    ax_vals = [r['ax'] for r in window]
    mean = sum(ax_vals) / len(ax_vals)
    var = sum((x - mean)**2 for x in ax_vals) / len(ax_vals)
    std = math.sqrt(var)
    unstable_stds.append(std)

if unstable_stds:
    unstable_stds_sorted = sorted(unstable_stds)
    unstable_p25 = unstable_stds_sorted[len(unstable_stds_sorted)//4]
    unstable_p50 = unstable_stds_sorted[len(unstable_stds_sorted)//2]

    print(f'\nUNSTABLE:')
    print(f'  Current threshold: stdAccel >= 0.8')
    print(f'  Data shows 25th percentile: {unstable_p25:.2f}')
    print(f'  Data shows 50th percentile: {unstable_p50:.2f}')
    print(f'  RECOMMENDED: Keep at 0.8 (already good, but ensure priority order)')

print('\n' + '='*70)
print('FINAL DIAGNOSIS')
print('='*70)
print('\nWHY HIGH-SPEED EVENTS ARE NOT DETECTED:')
print('')
print('1. THRESHOLDS TOO STRICT:')
print('   - accelThreshold = 3.0 is too high (real events show peaks ~2.5-6.0)')
print('   - brakeThreshold = -3.0 is too strict (real events show mins ~-2.5 to -5.0)')
print('   - Only the STRONGEST events pass, missing moderate real events')
print('')
print('2. VARIANCE CONFUSION:')
print('   - High-speed NORMAL has stdAccel avg=1.4')
print('   - High-speed ACCEL has stdAccel avg=2.6')
print('   - High-speed BRAKE has stdAccel avg=2.3')
print('   - High-speed UNSTABLE has stdAccel avg=3.2')
print('   - Events often misclassified as UNSTABLE due to higher variance')
print('')
print('3. PRIORITY ORDER ISSUE:')
print('   - If UNSTABLE check comes before ACCEL/BRAKE,')
print('   - high-variance events get labeled UNSTABLE instead')
print('')
print('SOLUTION:')
print('  1. Lower thresholds for high-speed detection')
print('  2. Improve priority order (accel/brake BEFORE unstable)')
print('  3. Add variance gating (high variance + directional = event, not unstable)')

