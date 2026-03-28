import joblib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENCODER_PATH = ROOT / "models" / "artifacts" / "label_encoder.pkl"
LABELS_JSON_PATH = ROOT / "models" / "artifacts" / "labels.json"

encoder = joblib.load(ENCODER_PATH)

# Convert numpy int → Python int
mapping = {
    str(label): int(index)
    for label, index in zip(
        encoder.classes_,
        encoder.transform(encoder.classes_)
    )
}

LABELS_JSON_PATH.parent.mkdir(parents=True, exist_ok=True)
with open(LABELS_JSON_PATH, "w") as f:
    json.dump(mapping, f, indent=4)

print("Labels exported")