import joblib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCALER_PKL_PATH = ROOT / "models" / "artifacts" / "scaler.pkl"
SCALER_JSON_PATH = ROOT / "models" / "artifacts" / "scaler.json"

scaler = joblib.load(SCALER_PKL_PATH)

data = {
    "mean": scaler.mean_.tolist(),
    "scale": scaler.scale_.tolist()
}

SCALER_JSON_PATH.parent.mkdir(parents=True, exist_ok=True)
with open(SCALER_JSON_PATH, "w") as f:
    json.dump(data, f)

print("Scaler exported")