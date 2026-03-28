import tensorflow as tf
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

KERAS_MODEL = ROOT / "models" / "keras" / "model.keras"
TFLITE_MODEL = ROOT / "models" / "tflite" / "model.tflite"

model = tf.keras.models.load_model(KERAS_MODEL)

converter = tf.lite.TFLiteConverter.from_keras_model(model)

# Android safe configuration
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS
]

converter.optimizations = [tf.lite.Optimize.DEFAULT]

tflite_model = converter.convert()

with open(TFLITE_MODEL, "wb") as f:
    f.write(tflite_model)

print("TFLite model created:", TFLITE_MODEL)