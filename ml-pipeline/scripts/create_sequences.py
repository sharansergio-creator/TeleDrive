import numpy as np
import pandas as pd
from pathlib import Path
import joblib
from sklearn.preprocessing import LabelEncoder


ROOT = Path(__file__).resolve().parents[1]
INPUT_PATH = ROOT / "data" / "processed" / "clean_data.csv"
ENCODER_PATH = ROOT / "models" / "artifacts" / "label_encoder.pkl"
X_SEQ_PATH = ROOT / "data" / "processed" / "X_seq.npy"
Y_SEQ_PATH = ROOT / "data" / "processed" / "y_seq.npy"
SEQUENCE_LENGTH = 50
STEP_SIZE = 10

FEATURES = [
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
    "accel_mag",
    "gyro_mag",
]

# ==============================
# LOAD CLEAN DATA
# ==============================
df = pd.read_csv(INPUT_PATH)
df = df.sort_values("timestamp").reset_index(drop=True)

missing = [col for col in FEATURES + ["label"] if col not in df.columns]
if missing:
    raise ValueError(f"Missing required columns in clean data: {missing}")

# ==============================
# BUILD FEATURE/LABEL ARRAYS
# ==============================
X = df[FEATURES].astype("float32").values
y = df["label"].values

# ==============================
# LABEL ENCODING
# ==============================
encoder = LabelEncoder()
y = encoder.fit_transform(y)

ENCODER_PATH.parent.mkdir(parents=True, exist_ok=True)
joblib.dump(encoder, ENCODER_PATH)

# ==============================
# CREATE SEQUENCES (SLIDING WINDOW)
# ==============================
X_seq = []
y_seq = []

for start_idx in range(0, len(X) - SEQUENCE_LENGTH + 1, STEP_SIZE):
    end_idx = start_idx + SEQUENCE_LENGTH
    X_seq.append(X[start_idx:end_idx])
    # Use the last label in each window.
    y_seq.append(y[end_idx - 1])

if not X_seq:
    raise ValueError(
        f"Not enough rows ({len(X)}) to create sequences with "
        f"sequence_length={SEQUENCE_LENGTH}."
    )

X_seq = np.array(X_seq, dtype=np.float32)
y_seq = np.array(y_seq, dtype=np.int64)

print("Sequence shape:", X_seq.shape)
print("Labels shape:", y_seq.shape)

# ==============================
# SAVE
# ==============================
X_SEQ_PATH.parent.mkdir(parents=True, exist_ok=True)
np.save(X_SEQ_PATH, X_seq)
np.save(Y_SEQ_PATH, y_seq)

print("Sequences created")