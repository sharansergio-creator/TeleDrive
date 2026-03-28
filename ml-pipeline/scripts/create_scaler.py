import pandas as pd
from sklearn.preprocessing import StandardScaler
import joblib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

INPUT_PATH = ROOT / "data" / "processed" / "clean_data.csv"
SCALER_PATH = ROOT / "models" / "artifacts" / "scaler.pkl"

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

df = pd.read_csv(INPUT_PATH)

X = df[FEATURES].astype("float32").values

scaler = StandardScaler()
scaler.fit(X)

SCALER_PATH.parent.mkdir(parents=True, exist_ok=True)
joblib.dump(scaler, SCALER_PATH)

print("Scaler created successfully")
print("Saved to:", SCALER_PATH)
print("Feature count:", len(scaler.mean_))