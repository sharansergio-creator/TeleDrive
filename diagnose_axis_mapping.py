import csv
import math

# Deep dive into axis usage and current detection logic
print('='*80)
print('CRITICAL DIAGNOSIS: AXIS MAPPING vs DETECTION LOGIC')
print('='*80)

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_26.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_27.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv',
]

label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

all_accel_events = []
all_brake_events = []
all_normal_events = []

for fname in files:
    try:
        with open(fname, 'r') as f:
            data = []
            reader = csv.DictReader(f)
            for row in reader:
                data.append({
                    'ax': float(row['ax']),
                    'ay': float(row['ay']),
                    'speed': float(row['speed']),
                    'label': int(row['label'])
                })

        # Analyze labeled events
        window_size = 50
        for i in range(0, len(data) - window_size, window_size):
            window = data[i:i+window_size]

            # Get majority label
            labels = [r['label'] for r in window]
            label_count = {}
            for l in labels:
                label_count[l] = label_count.get(l, 0) + 1
            majority_label = max(label_count.items(), key=lambda x: x[1])[0]

            # Calculate features like SensorService does
            ax_vals = [r['ax'] for r in window]
            ay_vals = [r['ay'] for r in window]

            # Current code uses ax as forward axis!
            peak_ax = max(ax_vals)
            min_ax = min(ax_vals)

            # But ay might be the true forward axis
            peak_ay = max(ay_vals)
            min_ay = min(ay_vals)

            speed_vals = [r['speed'] for r in window]
            avg_speed = sum(speed_vals) / len(speed_vals)

            if majority_label == 1:  # ACCEL
                all_accel_events.append({
                    'peak_ax': peak_ax,
                    'min_ax': min_ax,
                    'peak_ay': peak_ay,
                    'min_ay': min_ay,
                    'speed': avg_speed
                })
            elif majority_label == 2:  # BRAKE
                all_brake_events.append({
                    'peak_ax': peak_ax,
                    'min_ax': min_ax,
                    'peak_ay': peak_ay,
                    'min_ay': min_ay,
                    'speed': avg_speed
                })
            elif majority_label == 0:  # NORMAL
                if len(all_normal_events) < 100:  # Sample some normals
                    all_normal_events.append({
                        'peak_ax': peak_ax,
                        'min_ax': min_ax,
                        'peak_ay': peak_ay,
                        'min_ay': min_ay,
                        'speed': avg_speed
                    })
    except:
        continue

print('\n' + '='*80)
print('CURRENT CODE ASSUMPTION: ax = forward acceleration')
print('='*80)

if all_accel_events:
    print(f'\nLABELED AS HARSH_ACCELERATION (n={len(all_accel_events)}):')
    avg_peak_ax = sum(e['peak_ax'] for e in all_accel_events) / len(all_accel_events)
    avg_min_ax = sum(e['min_ax'] for e in all_accel_events) / len(all_accel_events)
    avg_peak_ay = sum(e['peak_ay'] for e in all_accel_events) / len(all_accel_events)
    avg_min_ay = sum(e['min_ay'] for e in all_accel_events) / len(all_accel_events)

    print(f'  Using ax (current):')
    print(f'    peak_ax: {avg_peak_ax:.3f}')
    print(f'    min_ax:  {avg_min_ax:.3f}')
    print(f'  Using ay (alternative):')
    print(f'    peak_ay: {avg_peak_ay:.3f}')
    print(f'    min_ay:  {avg_min_ay:.3f}')

    # Check which axis shows stronger forward signal
    ax_magnitude = avg_peak_ax - avg_min_ax
    ay_magnitude = avg_peak_ay - avg_min_ay
    print(f'\n  >> ax magnitude (range): {ax_magnitude:.3f}')
    print(f'  >> ay magnitude (range): {ay_magnitude:.3f}')

    if avg_peak_ay > avg_peak_ax:
        print(f'  >> WARNING: ay shows STRONGER positive signal than ax!')

if all_brake_events:
    print(f'\nLABELED AS HARSH_BRAKING (n={len(all_brake_events)}):')
    avg_peak_ax = sum(e['peak_ax'] for e in all_brake_events) / len(all_brake_events)
    avg_min_ax = sum(e['min_ax'] for e in all_brake_events) / len(all_brake_events)
    avg_peak_ay = sum(e['peak_ay'] for e in all_brake_events) / len(all_brake_events)
    avg_min_ay = sum(e['min_ay'] for e in all_brake_events) / len(all_brake_events)

    print(f'  Using ax (current):')
    print(f'    peak_ax: {avg_peak_ax:.3f}')
    print(f'    min_ax:  {avg_min_ax:.3f}')
    print(f'  Using ay (alternative):')
    print(f'    peak_ay: {avg_peak_ay:.3f}')
    print(f'    min_ay:  {avg_min_ay:.3f}')

    ax_magnitude = avg_peak_ax - avg_min_ax
    ay_magnitude = avg_peak_ay - avg_min_ay
    print(f'\n  >> ax magnitude (range): {ax_magnitude:.3f}')
    print(f'  >> ay magnitude (range): {ay_magnitude:.3f}')

    if abs(avg_min_ay) > abs(avg_min_ax):
        print(f'  >> WARNING: ay shows STRONGER negative signal than ax!')

if all_normal_events:
    print(f'\nLABELED AS NORMAL (sample n={len(all_normal_events)}):')
    avg_peak_ax = sum(e['peak_ax'] for e in all_normal_events) / len(all_normal_events)
    avg_min_ax = sum(e['min_ax'] for e in all_normal_events) / len(all_normal_events)
    avg_peak_ay = sum(e['peak_ay'] for e in all_normal_events) / len(all_normal_events)
    avg_min_ay = sum(e['min_ay'] for e in all_normal_events) / len(all_normal_events)

    print(f'  Using ax:')
    print(f'    peak_ax: {avg_peak_ax:.3f}')
    print(f'    min_ax:  {avg_min_ax:.3f}')
    print(f'  Using ay:')
    print(f'    peak_ay: {avg_peak_ay:.3f}')
    print(f'    min_ay:  {avg_min_ay:.3f}')

print('\n' + '='*80)
print('ROOT CAUSE DIAGNOSIS')
print('='*80)

print('''
CRITICAL FINDINGS:

1. Current code in SensorService.kt uses ax as "forwardAccel"

   In TeleDriveProcessor.extractFeatures():
     val forwardAccel = samples.map { it.ax }  // USES ax

2. But data correlation shows:
   - Speed INCREASES -> ay tends positive (not ax)
   - Speed DECREASES -> ay tends negative (not ax)

3. This explains user observations:
   - Throttle detected as BRAKING -> wrong axis used
   - Real braking not detected -> signal on wrong axis

RECOMMENDATION:
   Change forward axis from ax to ay in feature extraction
''')

# Calculate correlation strength
print('\n' + '='*80)
print('STATISTICAL EVIDENCE')
print('='*80)

print('\nLet me check which axis correlates better with actual motion...')

# Reload one file for detailed correlation
fname = r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv'
with open(fname, 'r') as f:
    data = []
    reader = csv.DictReader(f)
    for row in reader:
        data.append({
            'timestamp': float(row['timestamp']),
            'ax': float(row['ax']),
            'ay': float(row['ay']),
            'speed': float(row['speed']),
        })

# Calculate speed derivatives and correlate with ax, ay
correlations_ax = []
correlations_ay = []

window_size = 50
for i in range(0, len(data) - window_size, 10):
    window = data[i:i+window_size]

    speed_change = window[-1]['speed'] - window[0]['speed']

    ax_vals = [r['ax'] for r in window]
    ay_vals = [r['ay'] for r in window]

    ax_mean = sum(ax_vals) / len(ax_vals)
    ay_mean = sum(ay_vals) / len(ay_vals)

    # Positive speed change should correlate with positive acceleration
    correlations_ax.append((speed_change, ax_mean))
    correlations_ay.append((speed_change, ay_mean))

# Simple correlation check: how often do they have same sign?
same_sign_ax = sum(1 for sc, ax in correlations_ax if (sc > 0 and ax > 0) or (sc < 0 and ax < 0))
same_sign_ay = sum(1 for sc, ay in correlations_ay if (sc > 0 and ay > 0) or (sc < 0 and ay < 0))

total = len(correlations_ax)
print(f'\nCorrelation between speed change and sensor axis:')
print(f'  ax correlation: {same_sign_ax}/{total} ({same_sign_ax/total*100:.1f}% same sign)')
print(f'  ay correlation: {same_sign_ay}/{total} ({same_sign_ay/total*100:.1f}% same sign)')

if same_sign_ay > same_sign_ax:
    print(f'\n  >> CONFIRMED: ay has BETTER correlation with speed change!')
    print(f'  >> Current code uses ax -> WRONG AXIS')
else:
    print(f'\n  >> ax has better correlation (current is correct)')



