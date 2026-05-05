"""
TeleDrive ML Pipeline - Step 2: Clean and Validate Data
Removes noise, validates labels using physics, keeps only high-confidence samples
"""

import csv
import statistics
import math

def load_csv(filepath):
    """Load CSV file"""
    with open(filepath, 'r') as f:
        reader = csv.DictReader(f)
        return list(reader)

def clean_data(input_file, output_file):
    """Clean data: remove noise, validate labels, filter low-quality samples"""

    print("="*80)
    print("STEP 2: DATA CLEANING AND VALIDATION")
    print("="*80)

    data = load_csv(input_file)
    print(f"\nLoaded {len(data)} samples")

    cleaned_data = []

    # Filtering stats
    stats = {
        'low_speed_events': 0,
        'noise_spikes': 0,
        'physics_mismatch': 0,
        'kept': 0
    }

    # Process in windows
    window_size = 50

    for i in range(0, len(data) - window_size, window_size):
        window = data[i:i+window_size]

        # Get window properties
        labels = [int(row['label']) for row in window]
        majority_label = max(set(labels), key=labels.count)

        # Speed analysis
        speeds = [float(row['speed']) for row in window]
        speed_mean = statistics.mean(speeds)
        speed_start = speeds[0]
        speed_end = speeds[-1]
        speed_change = speed_end - speed_start

        # Sensor analysis
        ay_vals = [float(row['ay']) for row in window]
        ax_vals = [float(row['ax']) for row in window]

        # Calculate variance
        ay_std = statistics.stdev(ay_vals) if len(ay_vals) > 1 else 0
        ay_mean = statistics.mean(ay_vals)
        ay_min = min(ay_vals)
        ay_max = max(ay_vals)

        # Filtering rules
        keep_window = True
        reason = None

        # Rule 1: Low-speed harsh events (likely false positives)
        if majority_label in [1, 2]:  # HARSH_ACCEL or HARSH_BRAKE
            if speed_mean < 12:
                keep_window = False
                reason = 'low_speed_events'
                stats['low_speed_events'] += len(window)

        # Rule 2: Sensor noise spikes (unrealistic values)
        if abs(ay_min) > 25 or abs(ay_max) > 25:
            keep_window = False
            reason = 'noise_spikes'
            stats['noise_spikes'] += len(window)

        # Rule 3: Physics validation
        if majority_label == 1:  # HARSH_ACCEL
            # Speed should increase or stay constant (not drop significantly)
            if speed_change < -8:  # Speed dropped >8 km/h during acceleration
                keep_window = False
                reason = 'physics_mismatch'
                stats['physics_mismatch'] += len(window)

        elif majority_label == 2:  # HARSH_BRAKE
            # Speed should decrease or stay constant (not increase significantly)
            if speed_change > 5:  # Speed increased >5 km/h during braking
                keep_window = False
                reason = 'physics_mismatch'
                stats['physics_mismatch'] += len(window)

        # Keep or discard
        if keep_window:
            cleaned_data.extend(window)
            stats['kept'] += len(window)

    # Write cleaned data
    print(f"\nWriting cleaned dataset: {output_file}")

    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=data[0].keys())
        writer.writeheader()
        writer.writerows(cleaned_data)

    # Report statistics
    print(f"\n{'='*60}")
    print("CLEANING STATISTICS")
    print(f"{'='*60}")

    print(f"\nOriginal samples:       {len(data):8d}")
    print(f"Cleaned samples:        {stats['kept']:8d} ({100*stats['kept']/len(data):.1f}%)")
    print(f"\nRemoved:")
    print(f"  Low-speed events:     {stats['low_speed_events']:8d}")
    print(f"  Noise spikes:         {stats['noise_spikes']:8d}")
    print(f"  Physics mismatch:     {stats['physics_mismatch']:8d}")
    print(f"  Total removed:        {len(data) - stats['kept']:8d} ({100*(len(data)-stats['kept'])/len(data):.1f}%)")

    # Show new distribution
    from collections import Counter
    labels = [int(row['label']) for row in cleaned_data]
    label_counts = Counter(labels)

    print(f"\nCleaned Dataset Distribution:")
    label_names = {0: 'NORMAL', 1: 'HARSH_ACCEL', 2: 'HARSH_BRAKE', 3: 'UNSTABLE'}
    for label in sorted(label_counts.keys()):
        count = label_counts[label]
        pct = 100 * count / len(cleaned_data)
        print(f"  {label_names[label]}: {count:7d} ({pct:5.1f}%)")

    return len(cleaned_data)

if __name__ == "__main__":
    input_file = "D:/TeleDrive/ml-pipeline/data/merged_raw.csv"
    output_file = "D:/TeleDrive/ml-pipeline/data/cleaned_validated.csv"

    clean_data(input_file, output_file)

