from pathlib import Path

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]

INPUT_CANDIDATES = [
    ROOT / "data" / "raw" / "raw_data.csv",
    ROOT / "raw_data.csv",
]
OUTPUT_PATH = ROOT / "data" / "processed" / "clean_data.csv"

FEATURE_COLS = [
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
    "accel_mag",
    "gyro_mag",
]

KEEP_COLS = [
    "timestamp",
    *FEATURE_COLS,
    "label",
]

ALIASES = {
    "time": "timestamp",
    "event": "label",

    # Raw schema
    "peak": "peakForwardAccel",
    "min": "minForwardAccel",

    # Axis-based schema passthrough
    "ax": "accel_x",
    "ay": "accel_y",
    "az": "accel_z",
    "gx": "gyro_x",
    "gy": "gyro_y",
    "gz": "gyro_z",

    # Legacy synthetic schema support
    "accel": "accel_x",
    "brake": "accel_y",
}


input_path = next((path for path in INPUT_CANDIDATES if path.exists()), None)
if input_path is None:
    raise FileNotFoundError(
        "Input CSV not found. Tried: " + ", ".join(str(path) for path in INPUT_CANDIDATES)
    )

df = pd.read_csv(input_path)

# Normalize common legacy input headers to the required schema.
df = df.rename(columns={src: dst for src, dst in ALIASES.items() if src in df.columns})

if "timestamp" not in df.columns:
    df["timestamp"] = np.arange(len(df), dtype=np.int64)

if "label" not in df.columns:
    raise ValueError("Missing required column in input CSV: label")

# Build axis-based signals from whatever source schema is available.
if {"accel_x", "accel_y", "accel_z"}.issubset(df.columns):
    accel_x = pd.to_numeric(df["accel_x"], errors="coerce")
    accel_y = pd.to_numeric(df["accel_y"], errors="coerce")
    accel_z = pd.to_numeric(df["accel_z"], errors="coerce")
else:
    source_std_col = "stdAccel" if "stdAccel" in df.columns else "std"
    for col in ["peakForwardAccel", "minForwardAccel", source_std_col]:
        if col not in df.columns:
            raise ValueError(
                "Input CSV must contain either accel axis columns "
                "(accel_x, accel_y, accel_z) or source columns "
                "(peakForwardAccel, minForwardAccel, stdAccel/std)."
            )
    accel_x = pd.to_numeric(df["peakForwardAccel"], errors="coerce")
    accel_y = pd.to_numeric(df["minForwardAccel"], errors="coerce")
    accel_z = pd.to_numeric(df[source_std_col], errors="coerce")

if {"gyro_x", "gyro_y", "gyro_z"}.issubset(df.columns):
    gyro_x = pd.to_numeric(df["gyro_x"], errors="coerce")
    gyro_y = pd.to_numeric(df["gyro_y"], errors="coerce")
    gyro_z = pd.to_numeric(df["gyro_z"], errors="coerce")
else:
    if "meanGyro" in df.columns:
        gyro_base = pd.to_numeric(df["meanGyro"], errors="coerce")
    elif "gyro" in df.columns:
        gyro_base = pd.to_numeric(df["gyro"], errors="coerce")
    elif "gyro_z" in df.columns:
        gyro_base = pd.to_numeric(df["gyro_z"], errors="coerce")
    else:
        raise ValueError(
            "Input CSV must contain either gyro axis columns "
            "(gyro_x, gyro_y, gyro_z) or source column meanGyro."
        )

    # Split one gyro signal into three channels with lightweight, deterministic transforms.
    gyro_x = gyro_base
    gyro_y = gyro_base.diff().fillna(0.0)
    gyro_z = gyro_base.rolling(window=3, min_periods=1).mean()

clean_df = pd.DataFrame(
    {
        "timestamp": pd.to_numeric(df["timestamp"], errors="coerce"),
        "accel_x": accel_x,
        "accel_y": accel_y,
        "accel_z": accel_z,
        "gyro_x": gyro_x,
        "gyro_y": gyro_y,
        "gyro_z": gyro_z,
        "label": df["label"],
    }
)

for col in ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]:
    clean_df[col] = pd.to_numeric(clean_df[col], errors="coerce")

clean_df = clean_df.dropna(subset=["timestamp", "label", *FEATURE_COLS[:6]]).copy()
clean_df["timestamp"] = clean_df["timestamp"].astype(np.int64)

clean_df["accel_mag"] = np.sqrt(
    clean_df["accel_x"] ** 2 + clean_df["accel_y"] ** 2 + clean_df["accel_z"] ** 2
)
clean_df["gyro_mag"] = np.sqrt(
    clean_df["gyro_x"] ** 2 + clean_df["gyro_y"] ** 2 + clean_df["gyro_z"] ** 2
)

clean_df["label"] = clean_df["label"].astype(str).str.strip().str.upper()
clean_df = clean_df[clean_df["label"].ne("")].copy()
clean_df = clean_df.sort_values("timestamp").reset_index(drop=True)

print(f"Total rows after cleaning: {len(clean_df)}")
print("\nClass distribution:")
print(clean_df["label"].value_counts().to_string())
print("\nOutput feature columns:")
print(", ".join(FEATURE_COLS))

OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
clean_df[KEEP_COLS].to_csv(OUTPUT_PATH, index=False)

print(f"\nCleaned data saved to {OUTPUT_PATH}")