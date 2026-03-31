import csv
import math
from collections import defaultdict

# Comprehensive analysis of ALL ride sessions
print('='*80)
print('COMPREHENSIVE DATA ANALYSIS - ALL RIDE SESSIONS')
print('='*80)

files = [
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_17.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_20.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_24.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_26.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_27.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_28.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_29.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_30.csv',
    r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_31.csv',
]

label_names = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}

# Aggregate statistics
all_data = []
file_stats = {}

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
                    'az': float(row['az']),
                    'gx': float(row['gx']),
                    'gy': float(row['gy']),
                    'gz': float(row['gz']),
                    'speed': float(row['speed']),
                    'label': int(row['label'])
                })

            filename = fname.split('\\')[-1]

            # File statistics
            label_dist = defaultdict(int)
            for row in data:
                label_dist[row['label']] += 1

            file_stats[filename] = {
                'total': len(data),
                'labels': dict(label_dist)
            }

            all_data.extend(data)

    except Exception as e:
        print(f'Error loading {fname}: {e}')

print(f'\nTOTAL SAMPLES ACROSS ALL SESSIONS: {len(all_data)}')
print(f'FILES ANALYZED: {len(file_stats)}')

# Overall label distribution
print('\n' + '='*80)
print('OVERALL LABEL DISTRIBUTION')
print('='*80)

overall_labels = defaultdict(int)
for row in all_data:
    overall_labels[row['label']] += 1

for label in sorted(overall_labels.keys()):
    count = overall_labels[label]
    pct = (count / len(all_data)) * 100
    print(f'{label_names[label]:12s}: {count:8d} ({pct:5.1f}%)')

# Class imbalance check
print('\n' + '='*80)
print('CLASS IMBALANCE ANALYSIS')
print('='*80)

normal_count = overall_labels[0]
accel_count = overall_labels.get(1, 0)
brake_count = overall_labels.get(2, 0)
unstable_count = overall_labels.get(3, 0)

print(f'\nNORMAL to EVENT ratio:')
print(f'  NORMAL:ACCEL     = {normal_count}:{accel_count} (1:{normal_count/max(accel_count,1):.1f})')
print(f'  NORMAL:BRAKE     = {normal_count}:{brake_count} (1:{normal_count/max(brake_count,1):.1f})')
print(f'  NORMAL:UNSTABLE  = {normal_count}:{unstable_count} (1:{normal_count/max(unstable_count,1):.1f})')

event_total = accel_count + brake_count + unstable_count
print(f'\nEvent rate: {event_total}/{len(all_data)} = {event_total/len(all_data)*100:.1f}%')

# Speed analysis by label
print('\n' + '='*80)
print('SPEED ANALYSIS BY LABEL')
print('='*80)

for label in sorted(overall_labels.keys()):
    subset = [r for r in all_data if r['label'] == label]
    if not subset:
        continue

    speeds = [r['speed'] for r in subset]
    avg_speed = sum(speeds) / len(speeds)
    min_speed = min(speeds)
    max_speed = max(speeds)

    # Speed distribution
    low_speed = sum(1 for s in speeds if s < 15)
    med_speed = sum(1 for s in speeds if 15 <= s <= 30)
    high_speed = sum(1 for s in speeds if s > 30)

    print(f'\n{label_names[label]} (n={len(subset)}):')
    print(f'  Speed: avg={avg_speed:.1f}, min={min_speed:.1f}, max={max_speed:.1f}')
    print(f'  Distribution: Low(<15)={low_speed}({low_speed/len(subset)*100:.1f}%), '
          f'Med(15-30)={med_speed}({med_speed/len(subset)*100:.1f}%), '
          f'High(>30)={high_speed}({high_speed/len(subset)*100:.1f}%)')

# Speed change validation
print('\n' + '='*80)
print('SPEED CHANGE VALIDATION (Physics Check)')
print('='*80)

window_size = 50
validation_stats = {1: {'correct': 0, 'wrong': 0}, 2: {'correct': 0, 'wrong': 0}}

for i in range(0, len(all_data) - window_size, window_size):
    window = all_data[i:i+window_size]

    labels = [r['label'] for r in window]
    label_count = defaultdict(int)
    for l in labels:
        label_count[l] += 1
    if not label_count:
        continue
    majority_label = max(label_count.items(), key=lambda x: x[1])[0]

    if majority_label not in [1, 2]:
        continue

    speed_start = window[0]['speed']
    speed_end = window[-1]['speed']
    speed_change = speed_end - speed_start

    # ACCEL should have speed INCREASE
    if majority_label == 1:
        if speed_change > 0:
            validation_stats[1]['correct'] += 1
        else:
            validation_stats[1]['wrong'] += 1

    # BRAKE should have speed DECREASE
    elif majority_label == 2:
        if speed_change < 0:
            validation_stats[2]['correct'] += 1
        else:
            validation_stats[2]['wrong'] += 1

for label in [1, 2]:
    if label not in validation_stats:
        continue
    stats = validation_stats[label]
    total = stats['correct'] + stats['wrong']
    if total == 0:
        continue
    accuracy = (stats['correct'] / total) * 100
    print(f'\n{label_names[label]} labels:')
    print(f'  Physically correct: {stats["correct"]}/{total} ({accuracy:.1f}%)')
    print(f'  Physically wrong:   {stats["wrong"]}/{total} ({100-accuracy:.1f}%)')

    if accuracy < 80:
        print(f'  >> WARNING: Low physics accuracy!')

# Low-speed false positive analysis
print('\n' + '='*80)
print('LOW-SPEED FALSE POSITIVE ANALYSIS')
print('='*80)

low_speed_accel = [r for r in all_data if r['label'] == 1 and r['speed'] < 15]
low_speed_brake = [r for r in all_data if r['label'] == 2 and r['speed'] < 15]

if low_speed_accel:
    print(f'\nACCEL at LOW SPEED (<15 km/h): {len(low_speed_accel)} samples')
    print(f'  This is {len(low_speed_accel)/accel_count*100:.1f}% of all ACCEL events')
    print(f'  >> LIKELY FALSE POSITIVES (bike not accelerating meaningfully at <15 km/h)')

if low_speed_brake:
    print(f'\nBRAKE at LOW SPEED (<15 km/h): {len(low_speed_brake)} samples')
    print(f'  This is {len(low_speed_brake)/max(brake_count,1)*100:.1f}% of all BRAKE events')
    print(f'  >> LIKELY FALSE POSITIVES (braking less meaningful at low speed)')

# High-speed event detection
print('\n' + '='*80)
print('HIGH-SPEED EVENT DETECTION')
print('='*80)

high_speed_data = [r for r in all_data if r['speed'] > 30]
high_speed_labels = defaultdict(int)
for r in high_speed_data:
    high_speed_labels[r['label']] += 1

print(f'\nHigh-speed samples (>30 km/h): {len(high_speed_data)}')
for label in sorted(high_speed_labels.keys()):
    count = high_speed_labels[label]
    pct = (count / len(high_speed_data)) * 100
    print(f'  {label_names[label]:12s}: {count:6d} ({pct:5.1f}%)')

high_speed_events = high_speed_labels.get(1, 0) + high_speed_labels.get(2, 0) + high_speed_labels.get(3, 0)
print(f'\nHigh-speed event rate: {high_speed_events/len(high_speed_data)*100:.1f}%')

# Per-file statistics
print('\n' + '='*80)
print('PER-FILE STATISTICS')
print('='*80)

for filename, stats in file_stats.items():
    print(f'\n{filename}:')
    print(f'  Total: {stats["total"]}')
    for label, count in sorted(stats['labels'].items()):
        pct = (count / stats['total']) * 100
        print(f'    {label_names[label]:12s}: {count:6d} ({pct:5.1f}%)')

# ML READINESS ASSESSMENT
print('\n' + '='*80)
print('ML READINESS ASSESSMENT')
print('='*80)

min_samples_per_class = 5000  # For reliable 1D CNN training
print(f'\nMinimum recommended samples per class: {min_samples_per_class}')
print(f'\nCurrent status:')
for label in sorted(overall_labels.keys()):
    count = overall_labels[label]
    status = 'OK' if count >= min_samples_per_class else 'INSUFFICIENT'
    shortfall = max(0, min_samples_per_class - count)
    print(f'  {label_names[label]:12s}: {count:8d} [{status}]' +
          (f' (need {shortfall} more)' if shortfall > 0 else ''))

# Check class balance for ML
max_ratio = 10  # NORMAL:EVENT ratio should not exceed 10:1
current_normal_event_ratio = normal_count / max(event_total, 1)
print(f'\nClass balance check:')
print(f'  Current NORMAL:EVENT ratio: 1:{current_normal_event_ratio:.1f}')
print(f'  Recommended max ratio: 1:{max_ratio}')
if current_normal_event_ratio > max_ratio:
    print(f'  >> WARNING: Severe class imbalance! Need more event samples or undersample NORMAL')

print('\n' + '='*80)
print('SUMMARY & RECOMMENDATIONS')
print('='*80)

ready_for_ml = all(overall_labels.get(label, 0) >= min_samples_per_class for label in [0, 1, 2, 3])
balanced = current_normal_event_ratio <= max_ratio

print(f'\nDataset ML readiness:')
print(f'  Sufficient samples: {"YES" if ready_for_ml else "NO"}')
print(f'  Balanced classes: {"YES" if balanced else "NO"}')
print(f'  Overall: {"READY" if (ready_for_ml and balanced) else "NOT READY"}')

if not ready_for_ml:
    print(f'\nAction required: Collect more event data')
if not balanced:
    print(f'\nAction required: Fix detection logic to increase event detection rate')

