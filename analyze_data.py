import pandas as pd
import numpy as np

# Load dataset
df = pd.read_csv(r'D:\TeleDrive\ml-pipeline\data\raw\ride_session_17.csv')

print('='*60)
print('DATA ANALYSIS: ride_session_17.csv')
print('='*60)
print(f'Total samples: {len(df)}')
print(f'\nLabel distribution:')
print(df['label'].value_counts())

# Speed categories
df['speed_category'] = pd.cut(df['speed'], bins=[0, 15, 30, 100], labels=['LOW (<15)', 'MED (15-30)', 'HIGH (>30)'])

print(f'\n\nSpeed distribution:')
print(df['speed'].describe())
print(f'\nSpeed categories:')
print(df['speed_category'].value_counts())

# Analyze by speed category and label
print(f'\n\n{"="*60}')
print('LABEL DISTRIBUTION BY SPEED CATEGORY')
print('='*60)
for cat in ['LOW (<15)', 'MED (15-30)', 'HIGH (>30)']:
    subset = df[df['speed_category'] == cat]
    if len(subset) > 0:
        print(f'\n{cat}: {len(subset)} samples')
        print(subset['label'].value_counts())

# Analyze sensor values by label
print(f'\n\n{"="*60}')
print('SENSOR STATISTICS BY LABEL')
print('='*60)

for label in sorted(df['label'].unique()):
    subset = df[df['label'] == label]
    label_name = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}.get(label, f'LABEL_{label}')

    print(f'\n{label_name} (n={len(subset)}):')
    print(f'  ax: mean={subset["ax"].mean():.3f}, std={subset["ax"].std():.3f}, min={subset["ax"].min():.3f}, max={subset["ax"].max():.3f}')
    print(f'  ay: mean={subset["ay"].mean():.3f}, std={subset["ay"].std():.3f}')
    print(f'  az: mean={subset["az"].mean():.3f}, std={subset["az"].std():.3f}')
    print(f'  gx: mean={subset["gx"].mean():.3f}, std={subset["gx"].std():.3f}')
    print(f'  gy: mean={subset["gy"].mean():.3f}, std={subset["gy"].std():.3f}')
    print(f'  gz: mean={subset["gz"].mean():.3f}, std={subset["gz"].std():.3f}')
    print(f'  speed: mean={subset["speed"].mean():.3f}, min={subset["speed"].min():.3f}, max={subset["speed"].max():.3f}')

# Analyze HIGH SPEED events specifically
print(f'\n\n{"="*60}')
print('HIGH SPEED (>30 km/h) DETAILED ANALYSIS')
print('='*60)

high_speed = df[df['speed'] > 30]
print(f'\nTotal high-speed samples: {len(high_speed)}')
print(f'Label distribution at high speed:')
print(high_speed['label'].value_counts())

# Check stdAccel and gyro for high-speed events
for label in sorted(high_speed['label'].unique()):
    subset = high_speed[high_speed['label'] == label]
    label_name = {0: 'NORMAL', 1: 'ACCEL', 2: 'BRAKE', 3: 'UNSTABLE'}.get(label, f'LABEL_{label}')

    # Calculate std and gyro magnitude
    ax_vals = subset['ax'].values
    ay_vals = subset['ay'].values
    az_vals = subset['az'].values

    print(f'\n{label_name} at HIGH SPEED (n={len(subset)}):')
    print(f'  ax range: [{subset["ax"].min():.3f}, {subset["ax"].max():.3f}]')
    print(f'  ay range: [{subset["ay"].min():.3f}, {subset["ay"].max():.3f}]')
    print(f'  az range: [{subset["az"].min():.3f}, {subset["az"].max():.3f}]')

    # Check gyro magnitude
    gyro_mag = np.sqrt(subset['gx']**2 + subset['gy']**2 + subset['gz']**2)
    print(f'  gyro_mag: mean={gyro_mag.mean():.3f}, max={gyro_mag.max():.3f}')

    # Check accel variance (simulating window stdAccel)
    if len(subset) >= 50:
        # Sample a window
        window_ax = subset['ax'].iloc[:50]
        window_std = window_ax.std()
        print(f'  Sample window stdAccel: {window_std:.3f}')

