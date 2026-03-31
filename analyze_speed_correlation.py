import csv
import math
from collections import defaultdict

# Analyze relationship between speed change and accelerometer readings
files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_26.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_27.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_28.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv',
]

print('='*80)
print('CRITICAL ANALYSIS: SPEED CHANGE vs ACCELEROMETER AXIS')
print('='*80)

for fname in files:
    try:
        data = []
        with open(fname, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                data.append({
                    'timestamp': float(row['timestamp']),
                    'ax': float(row['ax']),
                    'ay': float(row['ay']),
                    'az': float(row['az']),
                    'speed': float(row['speed']),
                    'label': int(row['label'])
                })

        if not data or len(data) < 100:
            continue

        print(f'\n{"="*80}')
        filename = fname.split('\\')[-1]
        print(f'FILE: {filename}')
        print(f'{"="*80}')
        print(f'Total samples: {len(data)}')

        # Calculate speed changes over windows
        speed_increases = []  # Acceleration events
        speed_decreases = []  # Braking events

        window_size = 50
        for i in range(0, len(data) - window_size, 10):
            window = data[i:i+window_size]

            speed_start = window[0]['speed']
            speed_end = window[-1]['speed']
            speed_change = speed_end - speed_start

            # Get accelerometer values
            ax_vals = [r['ax'] for r in window]
            ay_vals = [r['ay'] for r in window]
            az_vals = [r['az'] for r in window]

            ax_mean = sum(ax_vals) / len(ax_vals)
            ay_mean = sum(ay_vals) / len(ay_vals)
            az_mean = sum(az_vals) / len(az_vals)
            ax_peak = max(ax_vals)
            ay_peak = max(ay_vals)
            ax_min = min(ax_vals)
            ay_min = min(ay_vals)

            if speed_change > 3.0:  # Significant acceleration
                speed_increases.append({
                    'speed_change': speed_change,
                    'ax_mean': ax_mean,
                    'ay_mean': ay_mean,
                    'az_mean': az_mean,
                    'ax_peak': ax_peak,
                    'ay_peak': ay_peak,
                    'ax_min': ax_min,
                    'ay_min': ay_min,
                    'speed_start': speed_start
                })
            elif speed_change < -3.0:  # Significant deceleration
                speed_decreases.append({
                    'speed_change': speed_change,
                    'ax_mean': ax_mean,
                    'ay_mean': ay_mean,
                    'az_mean': az_mean,
                    'ax_peak': ax_peak,
                    'ay_peak': ay_peak,
                    'ax_min': ax_min,
                    'ay_min': ay_min,
                    'speed_start': speed_start
                })

        print(f'\nSPEED INCREASES (acceleration): {len(speed_increases)} windows')
        if speed_increases:
            avg_ax = sum(w['ax_mean'] for w in speed_increases) / len(speed_increases)
            avg_ay = sum(w['ay_mean'] for w in speed_increases) / len(speed_increases)
            avg_ax_peak = sum(w['ax_peak'] for w in speed_increases) / len(speed_increases)
            avg_ay_peak = sum(w['ay_peak'] for w in speed_increases) / len(speed_increases)

            print(f'  ax_mean: {avg_ax:.3f}')
            print(f'  ay_mean: {avg_ay:.3f} << FORWARD AXIS')
            print(f'  ax_peak: {avg_ax_peak:.3f}')
            print(f'  ay_peak: {avg_ay_peak:.3f}')

            # Check sign correlation
            positive_ay = sum(1 for w in speed_increases if w['ay_mean'] > 0)
            negative_ay = sum(1 for w in speed_increases if w['ay_mean'] < 0)
            print(f'  ay sign: {positive_ay} positive, {negative_ay} negative')

        print(f'\nSPEED DECREASES (braking): {len(speed_decreases)} windows')
        if speed_decreases:
            avg_ax = sum(w['ax_mean'] for w in speed_decreases) / len(speed_decreases)
            avg_ay = sum(w['ay_mean'] for w in speed_decreases) / len(speed_decreases)
            avg_ax_min = sum(w['ax_min'] for w in speed_decreases) / len(speed_decreases)
            avg_ay_min = sum(w['ay_min'] for w in speed_decreases) / len(speed_decreases)

            print(f'  ax_mean: {avg_ax:.3f}')
            print(f'  ay_mean: {avg_ay:.3f} << FORWARD AXIS')
            print(f'  ax_min: {avg_ax_min:.3f}')
            print(f'  ay_min: {avg_ay_min:.3f}')

            # Check sign correlation
            positive_ay = sum(1 for w in speed_decreases if w['ay_mean'] > 0)
            negative_ay = sum(1 for w in speed_decreases if w['ay_mean'] < 0)
            print(f'  ay sign: {positive_ay} positive, {negative_ay} negative')

        # Analyze labels vs actual speed changes
        print(f'\n--- LABEL ANALYSIS ---')
        label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

        # Check what labels are assigned during speed increases
        accel_labeled_windows = []
        brake_labeled_windows = []

        window_size = 50
        for i in range(0, len(data) - window_size, window_size):
            window = data[i:i+window_size]
            labels = [r['label'] for r in window]

            # Majority label
            label_counts = defaultdict(int)
            for l in labels:
                label_counts[l] += 1
            majority_label = max(label_counts.items(), key=lambda x: x[1])[0]

            speed_start = window[0]['speed']
            speed_end = window[-1]['speed']
            speed_change = speed_end - speed_start

            ay_vals = [r['ay'] for r in window]
            ay_mean = sum(ay_vals) / len(ay_vals)
            ay_peak = max(ay_vals)
            ay_min = min(ay_vals)

            if speed_change > 3.0:  # Real acceleration
                accel_labeled_windows.append({
                    'label': majority_label,
                    'speed_change': speed_change,
                    'ay_mean': ay_mean,
                    'ay_peak': ay_peak,
                    'ay_min': ay_min
                })
            elif speed_change < -3.0:  # Real braking
                brake_labeled_windows.append({
                    'label': majority_label,
                    'speed_change': speed_change,
                    'ay_mean': ay_mean,
                    'ay_peak': ay_peak,
                    'ay_min': ay_min
                })

        if accel_labeled_windows:
            print(f'\nWindows with REAL ACCELERATION (speed increased):')
            label_dist = defaultdict(int)
            for w in accel_labeled_windows:
                label_dist[w['label']] += 1
            for label, count in sorted(label_dist.items()):
                pct = (count / len(accel_labeled_windows)) * 100
                print(f'  Labeled as {label_names[label]}: {count} ({pct:.1f}%)')

        if brake_labeled_windows:
            print(f'\nWindows with REAL BRAKING (speed decreased):')
            label_dist = defaultdict(int)
            for w in brake_labeled_windows:
                label_dist[w['label']] += 1
            for label, count in sorted(label_dist.items()):
                pct = (count / len(brake_labeled_windows)) * 100
                print(f'  Labeled as {label_names[label]}: {count} ({pct:.1f}%)')

    except Exception as e:
        print(f'\nError processing {fname}: {e}')
        continue

print('\n' + '='*80)
print('CORRELATION ANALYSIS')
print('='*80)
print('''
EXPECTED PHYSICAL BEHAVIOR:
  - Speed INCREASES (throttle) -> ay should be POSITIVE (forward push)
  - Speed DECREASES (braking)  -> ay should be NEGATIVE (backward push)

If the correlation is OPPOSITE:
  -> AXIS INVERSION PROBLEM CONFIRMED
''')


