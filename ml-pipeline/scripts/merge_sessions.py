"""
TeleDrive ML Pipeline - Step 1: Merge All Ride Sessions
Combines all ride session CSV files into a single dataset
"""

import csv
import os

def merge_sessions(input_dir, output_file):
    """Merge all ride session CSV files into one"""

    print("="*80)
    print("STEP 1: MERGING RIDE SESSIONS")
    print("="*80)

    # Find all CSV files
    files = sorted([
        os.path.join(input_dir, f)
        for f in os.listdir(input_dir)
        if f.startswith('ride_session_') and f.endswith('.csv')
    ])

    print(f"\nFound {len(files)} ride session files")

    # Merge all files
    all_rows = []
    header = None

    for filepath in files:
        filename = os.path.basename(filepath)
        print(f"Processing: {filename}...", end=" ")

        with open(filepath, 'r') as f:
            reader = csv.DictReader(f)

            if header is None:
                header = reader.fieldnames

            rows = list(reader)
            all_rows.extend(rows)
            print(f"{len(rows)} samples")

    # Write merged file
    print(f"\nWriting merged dataset: {output_file}")
    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=header)
        writer.writeheader()
        writer.writerows(all_rows)

    print(f"[OK] Merged {len(all_rows)} total samples")

    # Show distribution
    from collections import Counter
    labels = [int(row['label']) for row in all_rows]
    label_counts = Counter(labels)

    print(f"\nMerged Dataset Distribution:")
    label_names = {0: 'NORMAL', 1: 'HARSH_ACCEL', 2: 'HARSH_BRAKE', 3: 'UNSTABLE'}
    for label in sorted(label_counts.keys()):
        count = label_counts[label]
        pct = 100 * count / len(all_rows)
        print(f"  {label_names[label]}: {count:7d} ({pct:5.1f}%)")

    return len(all_rows)

if __name__ == "__main__":
    input_dir = "D:/TeleDrive/ml-pipeline/data/raw"
    output_file = "D:/TeleDrive/ml-pipeline/data/merged_raw.csv"

    merge_sessions(input_dir, output_file)

