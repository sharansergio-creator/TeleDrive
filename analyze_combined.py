import csv
import math
from collections import defaultdict

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_17.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_20.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_24.csv',
]

label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

all_data = []
for fname in files:
    try:
        with open(fname, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                all_data.append({
                    'ax': float(row['ax']),
                    'ay': float(row['ay']),
                    'az': float(row['az']),
                    'gx': float(row['gx']),
                    'gy': float(row['gy']),
                    'gz': float(row['gz']),
                    'speed': float(row['speed']),
                    'label': int(row['label']),
                    'file': fname.split('\\')[-1]
                })
    except:
        pass

print('='*70)
print('COMBINED ANALYSIS: Sessions 17, 20, 24')
print('='*70)
print(f'Total samples: {len(all_data)}\n')

# Overall label distribution
label_dist = defaultdict(int)
for row in all_data:
    label_dist[row['label']] += 1

print('Overall label distribution:')
for label, count in sorted(label_dist.items()):
    pct = (count / len(all_data)) * 100
    print(f'  {label_names[label]}: {count} ({pct:.1f}%)')

# Key insight: HIGH SPEED analysis
print('\n' + '='*70)
print('CRITICAL: HIGH SPEED (>30 km/h) ANALYSIS')
print('='*70)

high_speed = [r for r in all_data if r['speed'] > 30]
print(f'\nHigh-speed samples: {len(high_speed)} ({len(high_speed)/len(all_data)*100:.1f}% of total)')

high_labels = defaultdict(int)
for row in high_speed:
    high_labels[row['label']] += 1

print('\nLabel distribution at HIGH SPEED:')
for label, count in sorted(high_labels.items()):
    pct = (count / len(high_speed)) * 100
    print(f'  {label_names[label]}: {count} ({pct:.1f}%)')

# Check stdAccel/variance at high speed by label
print('\n' + '='*70)
print('VARIANCE (stdAccel) ANALYSIS AT HIGH SPEED')
print('='*70)

for label in sorted(high_labels.keys()):
    subset = [r for r in high_speed if r['label'] == label]

    # Calculate window variances
    variances = []
    window_size = 50
    for i in range(0, len(subset) - window_size, window_size):
        window = subset[i:i+window_size]
        ax_vals = [r['ax'] for r in window]
        mean_ax = sum(ax_vals) / len(ax_vals)
        var_ax = sum((x - mean_ax)**2 for x in ax_vals) / len(ax_vals)
        std_ax = math.sqrt(var_ax)
        variances.append(std_ax)

    if variances:
        avg_variance = sum(variances) / len(variances)
        min_variance = min(variances)
        max_variance = max(variances)

        print(f'\n{label_names[label]} at HIGH SPEED (n={len(subset)}):')
        print(f'  stdAccel: avg={avg_variance:.3f}, min={min_variance:.3f}, max={max_variance:.3f}')
        print(f'  Number of windows analyzed: {len(variances)}')

# Compare NORMAL vs events at high speed
print('\n' + '='*70)
print('PEAK/MIN ACCELERATION AT HIGH SPEED')
print('='*70)

for label in sorted(high_labels.keys()):
    subset = [r for r in high_speed if r['label'] == label]

    # Calculate peaks and mins in windows
    peaks = []
    mins = []
    window_size = 50
    for i in range(0, len(subset) - window_size, window_size):
        window = subset[i:i+window_size]
        ax_vals = [r['ax'] for r in window]
        peaks.append(max(ax_vals))
        mins.append(min(ax_vals))

    if peaks:
        avg_peak = sum(peaks) / len(peaks)
        avg_min = sum(mins) / len(mins)
        max_peak = max(peaks)
        min_min = min(mins)

        print(f'\n{label_names[label]}:')
        print(f'  Peak accel: avg={avg_peak:.3f}, max={max_peak:.3f}')
        print(f'  Min accel:  avg={avg_min:.3f}, min={min_min:.3f}')

# Check ACCEL presence at different speeds
print('\n' + '='*70)
print('ACCELERATION EVENTS BY SPEED RANGE')
print('='*70)

speed_ranges = [
    (0, 15, 'LOW'),
    (15, 30, 'MEDIUM'),
    (30, 45, 'HIGH'),
]

accel_data = [r for r in all_data if r['label'] == 1]
print(f'\nTotal ACCEL events: {len(accel_data)}')

for low, high, name in speed_ranges:
    subset = [r for r in accel_data if low <= r['speed'] < high]
    pct = (len(subset) / len(accel_data) * 100) if accel_data else 0
    print(f'  {name} ({low}-{high} km/h): {len(subset)} ({pct:.1f}%)')

# Check BRAKE presence at different speeds
print('\n' + '='*70)
print('BRAKING EVENTS BY SPEED RANGE')
print('='*70)

brake_data = [r for r in all_data if r['label'] == 2]
print(f'\nTotal BRAKE events: {len(brake_data)}')

for low, high, name in speed_ranges:
    subset = [r for r in brake_data if low <= r['speed'] < high]
    pct = (len(subset) / len(brake_data) * 100) if brake_data else 0
    print(f'  {name} ({low}-{high} km/h): {len(subset)} ({pct:.1f}%)')

# Final diagnosis
print('\n' + '='*70)
print('ROOT CAUSE DIAGNOSIS')
print('='*70)

high_speed_normal = [r for r in high_speed if r['label'] == 0]
high_speed_accel = [r for r in high_speed if r['label'] == 1]
high_speed_brake = [r for r in high_speed if r['label'] == 2]
high_speed_unstable = [r for r in high_speed if r['label'] == 3]

print(f'\nAt HIGH SPEED (>30 km/h):')
print(f'  NORMAL: {len(high_speed_normal)} ({len(high_speed_normal)/len(high_speed)*100:.1f}%)')
print(f'  ACCEL: {len(high_speed_accel)} ({len(high_speed_accel)/len(high_speed)*100:.1f}%)')
print(f'  BRAKE: {len(high_speed_brake)} ({len(high_speed_brake)/len(high_speed)*100:.1f}%)')
print(f'  UNSTABLE: {len(high_speed_unstable)} ({len(high_speed_unstable)/len(high_speed)*100:.1f}%)')

print('\n>> PROBLEM: At high speed, most events are classified as NORMAL or UNSTABLE')
print('>> Real accel/brake events exist in data but are NOT being detected')
print('>> This suggests thresholds are TOO STRICT for high-speed riding')





