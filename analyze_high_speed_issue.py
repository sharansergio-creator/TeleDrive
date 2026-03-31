import csv
import math

# Analyze why high-speed events are RARE in the training data
print('='*80)
print('HIGH-SPEED DETECTION ANALYSIS')
print('='*80)

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_26.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_27.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv',
]

label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

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

        filename = fname.split('\\')[-1]
        print(f'\n{"="*80}')
        print(f'FILE: {filename}')
        print(f'{"="*80}')

        # Separate by speed and label
        high_speed = [r for r in data if r['speed'] > 30]
        med_speed = [r for r in data if 15 <= r['speed'] <= 30]
        low_speed = [r for r in data if r['speed'] < 15]

        print(f'Total samples: {len(data)}')
        print(f'  Low speed (<15):   {len(low_speed)} ({len(low_speed)/len(data)*100:.1f}%)')
        print(f'  Medium (15-30):    {len(med_speed)} ({len(med_speed)/len(data)*100:.1f}%)')
        print(f'  High speed (>30):  {len(high_speed)} ({len(high_speed)/len(data)*100:.1f}%)')

        # Label distribution at high speed
        if high_speed:
            print(f'\nAt HIGH SPEED (>30 km/h):')
            label_dist = {}
            for r in high_speed:
                label_dist[r['label']] = label_dist.get(r['label'], 0) + 1

            for label in sorted(label_dist.keys()):
                count = label_dist[label]
                pct = (count / len(high_speed)) * 100
                print(f'  {label_names[label]}: {count} ({pct:.1f}%)')

            # Check acceleration magnitudes at high speed
            high_speed_accel = [r for r in high_speed if r['label'] == 1]
            high_speed_brake = [r for r in high_speed if r['label'] == 2]
            high_speed_normal = [r for r in high_speed if r['label'] == 0]

            if high_speed_accel:
                print(f'\n  ACCEL at high speed:')
                ax_vals = [r['ax'] for r in high_speed_accel]
                ay_vals = [r['ay'] for r in high_speed_accel]
                print(f'    ax: avg={sum(ax_vals)/len(ax_vals):.3f}, max={max(ax_vals):.3f}, min={min(ax_vals):.3f}')
                print(f'    ay: avg={sum(ay_vals)/len(ay_vals):.3f}, max={max(ay_vals):.3f}, min={min(ay_vals):.3f}')

            if high_speed_brake:
                print(f'\n  BRAKE at high speed:')
                ax_vals = [r['ax'] for r in high_speed_brake]
                ay_vals = [r['ay'] for r in high_speed_brake]
                print(f'    ax: avg={sum(ax_vals)/len(ax_vals):.3f}, max={max(ax_vals):.3f}, min={min(ax_vals):.3f}')
                print(f'    ay: avg={sum(ay_vals)/len(ay_vals):.3f}, max={max(ay_vals):.3f}, min={min(ay_vals):.3f}')

            if high_speed_normal:
                # Sample 500 NORMAL at high speed
                sample = high_speed_normal[:500]
                print(f'\n  NORMAL at high speed (sample):')
                ax_vals = [r['ax'] for r in sample]
                ay_vals = [r['ay'] for r in sample]
                print(f'    ax: avg={sum(ax_vals)/len(ax_vals):.3f}, max={max(ax_vals):.3f}, min={min(ax_vals):.3f}')
                print(f'    ay: avg={sum(ay_vals)/len(ay_vals):.3f}, max={max(ay_vals):.3f}, min={min(ay_vals):.3f}')

        # Check detection rates at different speeds
        print(f'\n--- Detection Rate by Speed ---')

        for speed_range, speed_label in [('Low (<15)', low_speed), ('Med (15-30)', med_speed), ('High (>30)', high_speed)]:
            if not speed_label:
                continue
            events = sum(1 for r in speed_label if r['label'] != 0)
            total = len(speed_label)
            print(f'{speed_range}: {events}/{total} events ({events/total*100:.1f}% event rate)')

    except Exception as e:
        print(f'Error: {e}')

print('\n' + '='*80)
print('KEY FINDINGS')
print('='*80)
print('''
If event rate at HIGH SPEED is LOW (<10%):
  -> Thresholds are TOO STRICT for high-speed riding
  -> Real events exist but are classified as NORMAL

If ax/ay magnitudes at high-speed NORMAL are similar to EVENTS:
  -> Separation is poor
  -> Need better thresholds or variance-based filtering

USER OBSERVATION: "throttle shows braking, braking not detected"
  -> If training data labels are correct but LIVE is wrong...
  -> Issue is in REAL-TIME detection logic, not feature extraction
  -> Likely: thresholds, persistence, or cooldown suppression
''')

