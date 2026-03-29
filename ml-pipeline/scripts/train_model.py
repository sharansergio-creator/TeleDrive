# ml-pipeline/scripts/train_model.py

import numpy as np
from pathlib import Path
import joblib
import random

from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import classification_report

import tensorflow as tf
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import EarlyStopping


# ================================
# REPRODUCIBILITY
# ================================
SEED = 42
np.random.seed(SEED)
tf.random.set_seed(SEED)
random.seed(SEED)


# ================================
# PATHS
# ================================
BASE_DIR = Path(__file__).resolve().parent.parent

X_PATH = BASE_DIR / "data/processed/X_seq.npy"
Y_PATH = BASE_DIR / "data/processed/y_seq.npy"

MODEL_DIR = BASE_DIR / "models"
ARTIFACTS_DIR = MODEL_DIR / "artifacts"
KERAS_DIR = MODEL_DIR / "keras"

MODEL_DIR.mkdir(parents=True, exist_ok=True)
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
KERAS_DIR.mkdir(parents=True, exist_ok=True)


# ================================
# LOAD DATA
# ================================
X = np.load(X_PATH)
y = np.load(Y_PATH)

print("STEP CHECK -----------------")
print("Labels (numeric):", np.unique(y))
print(f"Loaded X shape: {X.shape}, y shape: {y.shape}")

# 🔥 CRITICAL CHECK
num_classes = len(np.unique(y))
print("Num classes:", num_classes)
print("--------------------------------")


# ================================
# TRAIN TEST SPLIT
# ================================
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=SEED, stratify=y
)


# ================================
# SCALING
# ================================
num_samples, timesteps, features = X_train.shape

scaler = StandardScaler()

X_train = scaler.fit_transform(X_train.reshape(-1, features)).reshape(num_samples, timesteps, features)
X_test = scaler.transform(X_test.reshape(-1, features)).reshape(X_test.shape[0], timesteps, features)

joblib.dump(scaler, ARTIFACTS_DIR / "scaler.pkl")
print("Scaler saved.")


# ================================
# MODEL
# ================================
model = models.Sequential([
    layers.Input(shape=(timesteps, features)),

    layers.Conv1D(32, 3, activation='relu'),
    layers.MaxPooling1D(2),

    layers.Conv1D(64, 3, activation='relu'),
    layers.MaxPooling1D(2),

    layers.Flatten(),
    layers.Dense(64, activation='relu'),
    layers.Dense(num_classes, activation='softmax')
])

model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)


# ================================
# TRAIN
# ================================
early_stop = EarlyStopping(
    monitor='val_loss',
    patience=5,
    restore_best_weights=True
)

history = model.fit(
    X_train,
    y_train,
    validation_data=(X_test, y_test),
    epochs=30,
    batch_size=32,
    callbacks=[early_stop],
    verbose=1
)


# ================================
# EVALUATION
# ================================
loss, acc = model.evaluate(X_test, y_test)
print(f"Test Accuracy: {acc:.4f}")

y_pred = np.argmax(model.predict(X_test), axis=1)

print("\nClassification Report:")
print(classification_report(y_test, y_pred, zero_division=0))


# ================================
# SAVE MODEL
# ================================
model.save(KERAS_DIR / "model.keras")
print("Model saved.")