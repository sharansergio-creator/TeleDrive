import csv
import math

# Deep investigation: Are labels inverted or is detection logic broken?
print('='*80)
print('CRITICAL INVESTIGATION: LABEL INVERSION OR DETECTION FAILURE?')
print('='*80)

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_27.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_31.csv',
]

label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

for fname in files:
    data = []
    with open(fname, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            data.append({
                'timestamp': float(row['timestamp']),
                'ax': float(row['ax']),
                'ay': float(row['ay']),
                'speed': float(row['speed']),
                'label': int(row['label'])
            })

    filename = fname.split('\\')[-1]
    print(f'\n{"="*80}')
    print(f'FILE: {filename}')
    print(f'{"="*80}')

    # Analyze windows with ACCEL label
    accel_windows = []
    brake_windows = []

    window_size = 50
    for i in range(0, len(data) - window_size, window_size):
        window = data[i:i+window_size]

        labels = [r['label'] for r in window]
        if not labels:
            continue

        # Majority label
        from collections import Counter
        label_counts = Counter(labels)
        majority_label = label_counts.most_common(1)[0][0]

        speed_start = window[0]['speed']
        speed_end = window[-1]['speed']
        speed_change = speed_end - speed_start

        # Get ax, ay values
        ax_vals = [r['ax'] for r in window]
        ay_vals = [r['ay'] for r in window]

        ax_mean = sum(ax_vals) / len(ax_vals)
        ay_mean = sum(ay_vals) / len(ay_vals)
        ax_peak = max(ax_vals)
        ay_peak = max(ay_vals)
        ax_min = min(ax_vals)
        ay_min = min(ay_vals)

        if majority_label == 1:  # Labeled as ACCEL
            accel_windows.append({
                'speed_start': speed_start,
                'speed_change': speed_change,
                'ax_mean': ax_mean,
                'ay_mean': ay_mean,
                'ax_peak': ax_peak,
                'ay_peak': ay_peak
            })
        elif majority_label == 2:  # Labeled as BRAKE
            brake_windows.append({
                'speed_start': speed_start,
                'speed_change': speed_change,
                'ax_mean': ax_mean,
                'ay_mean': ay_mean,
                'ax_min': ax_min,
                'ay_min': ay_min
            })

    if accel_windows:
        print(f'\nWINDOWS LABELED AS ACCEL: {len(accel_windows)}')

        # Check speed changes
        speed_inc = sum(1 for w in accel_windows if w['speed_change'] > 1.0)
        speed_dec = sum(1 for w in accel_windows if w['speed_change'] < -1.0)
        speed_flat = len(accel_windows) - speed_inc - speed_dec

        print(f'  Speed INCREASED: {speed_inc} ({speed_inc/len(accel_windows)*100:.1f}%)')
        print(f'  Speed DECREASED: {speed_dec} ({speed_dec/len(accel_windows)*100:.1f}%)')
        print(f'  Speed FLAT:      {speed_flat} ({speed_flat/len(accel_windows)*100:.1f}%)')

        # Average values
        avg_speed_change = sum(w['speed_change'] for w in accel_windows) / len(accel_windows)
        avg_ax_mean = sum(w['ax_mean'] for w in accel_windows) / len(accel_windows)
        avg_ay_mean = sum(w['ay_mean'] for w in accel_windows) / len(accel_windows)
        avg_ax_peak = sum(w['ax_peak'] for w in accel_windows) / len(accel_windows)
        avg_ay_peak = sum(w['ay_peak'] for w in accel_windows) / len(accel_windows)

        print(f'\n  Average speed change: {avg_speed_change:.2f} km/h')
        print(f'  Average ax_mean: {avg_ax_mean:.3f}')
        print(f'  Average ay_mean: {avg_ay_mean:.3f}')
        print(f'  Average ax_peak: {avg_ax_peak:.3f}')
        print(f'  Average ay_peak: {avg_ay_peak:.3f}')

        if avg_speed_change < 0:
            print(f'\n  >> CRITICAL: ACCEL labels show NEGATIVE speed change!')
            print(f'  >> This indicates labels are INVERTED or logic is reversed')

    if brake_windows:
        print(f'\nWINDOWS LABELED AS BRAKE: {len(brake_windows)}')

        # Check speed changes
        speed_inc = sum(1 for w in brake_windows if w['speed_change'] > 1.0)
        speed_dec = sum(1 for w in brake_windows if w['speed_change'] < -1.0)
        speed_flat = len(brake_windows) - speed_inc - speed_dec

        print(f'  Speed INCREASED: {speed_inc} ({speed_inc/len(brake_windows)*100:.1f}%)')
        print(f'  Speed DECREASED: {speed_dec} ({speed_dec/len(brake_windows)*100:.1f}%)')
        print(f'  Speed FLAT:      {speed_flat} ({speed_flat/len(brake_windows)*100:.1f}%)')

        # Average values
        avg_speed_change = sum(w['speed_change'] for w in brake_windows) / len(brake_windows)
        avg_ax_mean = sum(w['ax_mean'] for w in brake_windows) / len(brake_windows)
        avg_ay_mean = sum(w['ay_mean'] for w in brake_windows) / len(brake_windows)
        avg_ax_min = sum(w['ax_min'] for w in brake_windows) / len(brake_windows)
        avg_ay_min = sum(w['ay_min'] for w in brake_windows) / len(brake_windows)

        print(f'\n  Average speed change: {avg_speed_change:.2f} km/h')
        print(f'  Average ax_mean: {avg_ax_mean:.3f}')
        print(f'  Average ay_mean: {avg_ay_mean:.3f}')
        print(f'  Average ax_min: {avg_ax_min:.3f}')
        print(f'  Average ay_min: {avg_ay_min:.3f}')

        if avg_speed_change > 0:
            print(f'\n  >> CRITICAL: BRAKE labels show POSITIVE speed change!')
            print(f'  >> This indicates labels are INVERTED or logic is reversed')

print('\n' + '='*80)
print('DIAGNOSIS')
print('='*80)
print('''
If ACCEL shows negative speed change AND BRAKE shows positive speed change:
  -> Labels are COMPLETELY INVERTED (ACCEL and BRAKE swapped)

If both show random/flat speed changes:
  -> Detection logic is broken (not detecting real events)

If sensor values don't correlate with labels:
  -> Axis interpretation is wrong OR thresholds are broken
''')

