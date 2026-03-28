from pathlib import Path
import joblib
import numpy as np
import tensorflow as tf
from sklearn.metrics import classification_report
from sklearn.model_selection import train_test_split


ROOT = Path(__file__).resolve().parents[1]

X_SEQ_PATH = ROOT / "data" / "processed" / "X_seq.npy"
Y_SEQ_PATH = ROOT / "data" / "processed" / "y_seq.npy"
ENCODER_PATH = ROOT / "models" / "artifacts" / "label_encoder.pkl"
KERAS_MODEL_PATH = ROOT / "models" / "keras" / "model.keras"
H5_MODEL_PATH = ROOT / "models" / "keras" / "teledrive_model.h5"

RANDOM_STATE = 42
EPOCHS = 20
BATCH_SIZE = 32

# ==============================
# LOAD SEQUENCES
# ==============================
if not X_SEQ_PATH.exists() or not Y_SEQ_PATH.exists():
    raise FileNotFoundError(
        "Missing sequence files. Run scripts/create_sequences.py first."
    )

if not ENCODER_PATH.exists():
    raise FileNotFoundError(
        "Missing label encoder file. Expected models/artifacts/label_encoder.pkl"
    )

X = np.load(X_SEQ_PATH)
y = np.load(Y_SEQ_PATH)

if X.ndim != 3:
    raise ValueError(f"X_seq must be 3D (samples, timesteps, features). Got shape {X.shape}")

if y.ndim != 1:
    raise ValueError(f"y_seq must be 1D. Got shape {y.shape}")

if len(X) != len(y):
    raise ValueError(f"X_seq and y_seq must have same length. Got {len(X)} vs {len(y)}")

encoder = joblib.load(ENCODER_PATH)
num_classes = int(len(encoder.classes_))

if num_classes < 2:
    raise ValueError("At least 2 classes are required for classification.")

timesteps = X.shape[1]
num_features = X.shape[2]

X_train, X_test, y_train, y_test = train_test_split(
    X,
    y,
    test_size=0.2,
    random_state=RANDOM_STATE,
    stratify=y,
)

# ==============================
# BUILD MODEL
# ==============================
model = tf.keras.Sequential(
    [
        tf.keras.layers.Input(shape=(timesteps, num_features)),
        tf.keras.layers.Conv1D(64, kernel_size=5, activation="relu", padding="same"),
        tf.keras.layers.MaxPooling1D(pool_size=2),
        tf.keras.layers.Conv1D(128, kernel_size=3, activation="relu", padding="same"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(num_classes, activation="softmax"),
    ]
)

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

early_stopping = tf.keras.callbacks.EarlyStopping(
    monitor="val_loss", patience=4, restore_best_weights=True
)

history = model.fit(
    X_train,
    y_train,
    validation_split=0.2,
    epochs=EPOCHS,
    batch_size=BATCH_SIZE,
    callbacks=[early_stopping],
    verbose=1,
)

# ==============================
# EVALUATE
# ==============================
y_proba = model.predict(X_test, verbose=0)
y_pred = np.argmax(y_proba, axis=1)
class_names = [str(c) for c in encoder.classes_]

print("Classification Report:")
print(
    classification_report(
        y_test,
        y_pred,
        labels=list(range(num_classes)),
        target_names=class_names,
        zero_division=0,
    )
)
test_loss, test_acc = model.evaluate(X_test, y_test, verbose=0)
print(f"Test loss: {test_loss:.4f}")
print(f"Test accuracy: {test_acc:.4f}")
print(f"Best val_loss: {min(history.history['val_loss']):.4f}")

# ==============================
# SAVE MODEL
# ==============================
KERAS_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
model.save(KERAS_MODEL_PATH)
model.save(H5_MODEL_PATH)

print(f"\nKeras model saved to {KERAS_MODEL_PATH}")
print(f"H5 model saved to {H5_MODEL_PATH}")