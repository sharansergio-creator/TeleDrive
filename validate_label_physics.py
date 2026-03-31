import csv
from collections import defaultdict

# Check label assignment vs actual speed behavior in NEW datasets
print('='*80)
print('CRITICAL: LABEL ASSIGNMENT vs ACTUAL PHYSICS')
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

        # Analyze labeled ACCEL events
        accel_windows_wrong = 0
        accel_windows_correct = 0
        accel_details = []

        brake_windows_wrong = 0
        brake_windows_correct = 0
        brake_details = []

        window_size = 50
        for i in range(0, len(data) - window_size, window_size):
            window = data[i:i+window_size]

            # Get majority label
            labels = [r['label'] for r in window]
            label_count = defaultdict(int)
            for l in labels:
                label_count[l] += 1
            if not label_count:
                continue
            majority_label = max(label_count.items(), key=lambda x: x[1])[0]

            # Calculate actual physics
            speed_start = window[0]['speed']
            speed_end = window[-1]['speed']
            speed_change = speed_end - speed_start

            # Check only labeled events
            if majority_label == 1:  # Labeled as ACCEL
                if speed_change < 0:  # But speed DECREASED (wrong!)
                    accel_windows_wrong += 1
                    accel_details.append({
                        'speed_start': speed_start,
                        'speed_change': speed_change,
                        'correct': False
                    })
                else:
                    accel_windows_correct += 1
                    if len(accel_details) < 5:  # Sample a few
                        accel_details.append({
                            'speed_start': speed_start,
                            'speed_change': speed_change,
                            'correct': True
                        })

            elif majority_label == 2:  # Labeled as BRAKE
                if speed_change > 0:  # But speed INCREASED (wrong!)
                    brake_windows_wrong += 1
                    brake_details.append({
                        'speed_start': speed_start,
                        'speed_change': speed_change,
                        'correct': False
                    })
                else:
                    brake_windows_correct += 1
                    if len(brake_details) < 5:  # Sample a few
                        brake_details.append({
                            'speed_start': speed_start,
                            'speed_change': speed_change,
                            'correct': True
                        })

        total_accel = accel_windows_correct + accel_windows_wrong
        total_brake = brake_windows_correct + brake_windows_wrong

        if total_accel > 0:
            print(f'\nLABELED AS HARSH_ACCELERATION: {total_accel} windows')
            print(f'  Correct (speed increased): {accel_windows_correct} ({accel_windows_correct/total_accel*100:.1f}%)')
            print(f'  WRONG (speed decreased):   {accel_windows_wrong} ({accel_windows_wrong/total_accel*100:.1f}%)')

            if accel_windows_wrong > 0:
                print(f'\n  >> PROBLEM: {accel_windows_wrong} ACCEL labels where speed actually DECREASED!')
                for detail in [d for d in accel_details if not d['correct']][:3]:
                    print(f'     - speed {detail["speed_start"]:.1f} -> change {detail["speed_change"]:.1f} (DECEL!)')

        if total_brake > 0:
            print(f'\nLABELED AS HARSH_BRAKING: {total_brake} windows')
            print(f'  Correct (speed decreased): {brake_windows_correct} ({brake_windows_correct/total_brake*100:.1f}%)')
            print(f'  WRONG (speed increased):   {brake_windows_wrong} ({brake_windows_wrong/total_brake*100:.1f}%)')

            if brake_windows_wrong > 0:
                print(f'\n  >> PROBLEM: {brake_windows_wrong} BRAKE labels where speed actually INCREASED!')
                for detail in [d for d in brake_details if not d['correct']][:3]:
                    print(f'     - speed {detail["speed_start"]:.1f} -> change {detail["speed_change"]:.1f} (ACCEL!)')

        # Check speed ranges for events
        print(f'\n--- Speed Analysis ---')
        accel_speeds = []
        brake_speeds = []

        for i in range(len(data)):
            if data[i]['label'] == 1:
                accel_speeds.append(data[i]['speed'])
            elif data[i]['label'] == 2:
                brake_speeds.append(data[i]['speed'])

        if accel_speeds:
            avg_speed = sum(accel_speeds) / len(accel_speeds)
            print(f'ACCEL events: avg speed = {avg_speed:.1f} km/h')
            high_speed_accel = sum(1 for s in accel_speeds if s > 30)
            print(f'  At high speed (>30): {high_speed_accel} / {len(accel_speeds)} ({high_speed_accel/len(accel_speeds)*100:.1f}%)')

        if brake_speeds:
            avg_speed = sum(brake_speeds) / len(brake_speeds)
            print(f'BRAKE events: avg speed = {avg_speed:.1f} km/h')
            high_speed_brake = sum(1 for s in brake_speeds if s > 30)
            print(f'  At high speed (>30): {high_speed_brake} / {len(brake_speeds)} ({high_speed_brake/len(brake_speeds)*100:.1f}%)')

    except Exception as e:
        print(f'Error processing {fname}: {e}')
        continue

print('\n' + '='*80)
print('ROOT CAUSE SUMMARY')
print('='*80)
print('''
If ACCEL labels show speed DECREASE:
  -> Current inversion is WRONG (over-corrected)

If BRAKE labels show speed INCREASE:
  -> Current inversion is WRONG (over-corrected)

If both are mixed (some correct, some wrong):
  -> Detection logic has OTHER issues beyond axis mapping
  -> Likely: noise, thresholds, or temporal filtering problems
''')

