import csv
import math
from collections import defaultdict

# Read CSV data
data = []
with open(r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_17.csv', 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        data.append({
            'ax': float(row['ax']),
            'ay': float(row['ay']),
            'az': float(row['az']),
            'gx': float(row['gx']),
            'gy': float(row['gy']),
            'gz': float(row['gz']),
            'speed': float(row['speed']),
            'label': int(row['label'])
        })

print('='*60)
print('DATA ANALYSIS: ride_session_17.csv')
print('='*60)
print(f'Total samples: {len(data)}')

# Label distribution
label_counts = defaultdict(int)
for row in data:
    label_counts[row['label']] += 1

print('\nLabel distribution:')
label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}
for label, count in sorted(label_counts.items()):
    print(f'  {label_names.get(label, f"LABEL_{label}")}: {count}')

# Speed categories
speed_cats = {'LOW': 0, 'MED': 0, 'HIGH': 0}
for row in data:
    if row['speed'] < 15:
        speed_cats['LOW'] += 1
    elif row['speed'] < 30:
        speed_cats['MED'] += 1
    else:
        speed_cats['HIGH'] += 1

print('\nSpeed categories:')
print(f'  LOW (<15 km/h): {speed_cats["LOW"]}')
print(f'  MED (15-30 km/h): {speed_cats["MED"]}')
print(f'  HIGH (>30 km/h): {speed_cats["HIGH"]}')

# Analyze by speed and label
print('\n' + '='*60)
print('LABEL DISTRIBUTION BY SPEED CATEGORY')
print('='*60)

for speed_range, speed_label in [((0, 15), 'LOW (<15)'), ((15, 30), 'MED (15-30)'), ((30, 100), 'HIGH (>30)')]:
    subset = [r for r in data if speed_range[0] <= r['speed'] < speed_range[1]]
    if subset:
        print(f'\n{speed_label}: {len(subset)} samples')
        label_dist = defaultdict(int)
        for row in subset:
            label_dist[row['label']] += 1
        for label, count in sorted(label_dist.items()):
            pct = (count / len(subset)) * 100
            print(f'  {label_names.get(label, f"LABEL_{label}")}: {count} ({pct:.1f}%)')

# Sensor statistics by label
print('\n' + '='*60)
print('SENSOR STATISTICS BY LABEL')
print('='*60)

for label in sorted(label_counts.keys()):
    subset = [r for r in data if r['label'] == label]
    label_name = label_names.get(label, f'LABEL_{label}')

    # Calculate stats
    ax_vals = [r['ax'] for r in subset]
    ay_vals = [r['ay'] for r in subset]
    az_vals = [r['az'] for r in subset]
    gx_vals = [r['gx'] for r in subset]
    gy_vals = [r['gy'] for r in subset]
    gz_vals = [r['gz'] for r in subset]
    speed_vals = [r['speed'] for r in subset]

    def stats(vals):
        mean = sum(vals) / len(vals)
        variance = sum((x - mean)**2 for x in vals) / len(vals)
        std = math.sqrt(variance)
        return mean, std, min(vals), max(vals)

    ax_stats = stats(ax_vals)
    speed_stats = stats(speed_vals)

    print(f'\n{label_name} (n={len(subset)}):')
    print(f'  ax: mean={ax_stats[0]:.3f}, std={ax_stats[1]:.3f}, min={ax_stats[2]:.3f}, max={ax_stats[3]:.3f}')
    print(f'  speed: mean={speed_stats[0]:.3f}, min={speed_stats[2]:.3f}, max={speed_stats[3]:.3f}')

# High-speed analysis
print('\n' + '='*60)
print('HIGH SPEED (>30 km/h) DETAILED ANALYSIS')
print('='*60)

high_speed = [r for r in data if r['speed'] > 30]
print(f'\nTotal high-speed samples: {len(high_speed)}')

high_speed_labels = defaultdict(int)
for row in high_speed:
    high_speed_labels[row['label']] += 1

print('Label distribution at high speed:')
for label, count in sorted(high_speed_labels.items()):
    pct = (count / len(high_speed)) * 100
    print(f'  {label_names.get(label, f"LABEL_{label}")}: {count} ({pct:.1f}%)')

# Analyze variance patterns at high speed
print('\n' + '='*60)
print('VARIANCE PATTERNS AT HIGH SPEED')
print('='*60)

for label in sorted(high_speed_labels.keys()):
    subset = [r for r in high_speed if r['label'] == label]
    label_name = label_names.get(label, f'LABEL_{label}')

    # Calculate window-based variance (simulate 50-sample windows)
    if len(subset) >= 50:
        ax_window = [r['ax'] for r in subset[:50]]
        mean_ax = sum(ax_window) / len(ax_window)
        var_ax = sum((x - mean_ax)**2 for x in ax_window) / len(ax_window)
        std_ax = math.sqrt(var_ax)

        print(f'\n{label_name} (sample window of 50):')
        print(f'  stdAccel (ax): {std_ax:.3f}')
        print(f'  ax range: [{min(ax_window):.3f}, {max(ax_window):.3f}]')
        print(f'  peak ax: {max(ax_window):.3f}')
        print(f'  min ax: {min(ax_window):.3f}')

