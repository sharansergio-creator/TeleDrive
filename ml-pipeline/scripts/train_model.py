"""
TeleDrive ML Pipeline - Train 1D CNN Model  (Session-Based Split)
=================================================================
Fixes data leakage by splitting at SESSION level before windowing.

No session is present in both train and test sets, so overlapping
windows can never leak information across the split boundary.

Input  : ml-pipeline/data/raw/ride_session_*.csv
Outputs: ml-pipeline/models/driving_behavior_model.keras
         ml-pipeline/models/driving_behavior_model.tflite
         ml-pipeline/models/scaler.json
         ml-pipeline/models/labels.json
"""

import json
import numpy as np
import pandas as pd
from pathlib import Path
from collections import Counter

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler
from sklearn.utils.class_weight import compute_class_weight
from sklearn.metrics import classification_report, confusion_matrix

print(f"TensorFlow version : {tf.__version__}")

# ──────────────────────────────────────────────
# CONFIG
# ──────────────────────────────────────────────
ROOT          = Path(__file__).resolve().parents[1]
RAW_DIR       = ROOT / "data" / "raw"
MODEL_DIR     = ROOT / "models"

CLASS_NAMES   = {0: "NORMAL", 1: "HARSH_ACCEL", 2: "HARSH_BRAKING", 3: "UNSTABLE_RIDE"}
NUM_CLASSES   = 4
WINDOW_SIZE   = 50
STRIDE        = 1
N_FEATURES    = 8
BATCH_SIZE    = 64
MAX_EPOCHS    = 50
TEST_FRACTION = 0.20      # fraction of sessions held out for test
NORMAL_CLASS  = 0
EVENT_CLASSES = [1, 2, 3]
NORMAL_RATIO  = 2.5       # downsample NORMAL to ≤ 2.5× largest event class on train
ACCEL_LIMIT   = 50.0      # ±50 m/s² extreme outlier threshold
FEATURES      = ["ax", "ay", "az", "gx", "gy", "gz", "speed", "timestamp_norm"]

# Reference accuracy from the OLD (leaky) pipeline — stored for comparison
OLD_TEST_ACC  = 95.03
OLD_MACRO_F1  = 0.9471



# ══════════════════════════════════════════════
# STEP 1 — LOAD SESSIONS
# ══════════════════════════════════════════════
def load_sessions(raw_dir: Path) -> dict:
    """
    Load every ride_session_*.csv into a dict keyed by session_id.
    Each DataFrame gets a timestamp_norm column (seconds from its own t0).
    """
    print("\n" + "="*60)
    print("STEP 1 — LOAD RAW SESSIONS")
    print("="*60)

    csv_files = sorted(raw_dir.glob("ride_session_*.csv"))
    if not csv_files:
        raise FileNotFoundError(f"No ride_session_*.csv files found in {raw_dir}")

    sessions = {}
    for f in csv_files:
        df = pd.read_csv(f)
        # Validate required columns
        required = ["timestamp", "ax", "ay", "az", "gx", "gy", "gz", "speed", "label"]
        missing  = [c for c in required if c not in df.columns]
        if missing:
            print(f"  [WARN] {f.name}: missing {missing} — skipped")
            continue
        # Drop rows with nulls or duplicates within the session
        df = df.dropna(subset=required).drop_duplicates(subset=required)
        df = df.sort_values("timestamp").reset_index(drop=True)
        # Session-relative timestamp in seconds (preserves temporal structure)
        df["timestamp_norm"] = (df["timestamp"] - df["timestamp"].iloc[0]) / 1e9
        sessions[f.stem] = df

    total_rows = sum(len(df) for df in sessions.values())
    print(f"  Loaded {len(sessions)} sessions  |  {total_rows:,} total rows")

    # Row-level distribution across ALL sessions
    all_labels = np.concatenate([df["label"].values for df in sessions.values()])
    dist = Counter(all_labels.tolist())
    print(f"\n  Row-level label distribution (all sessions):")
    for k in sorted(dist):
        print(f"    {k} ({CLASS_NAMES[k]:<16}): {dist[k]:>8,}  ({dist[k]/len(all_labels)*100:.1f}%)")

    return sessions


# ══════════════════════════════════════════════
# STEP 2 — SESSION-LEVEL SPLIT
# ══════════════════════════════════════════════
def split_sessions(sessions: dict, test_frac: float = TEST_FRACTION,
                   seed: int = 42) -> tuple:
    """
    Randomly partition session IDs into train / test groups.
    No session ever appears in both groups — zero window leakage.
    """
    print("\n" + "="*60)
    print("STEP 2 — SESSION-LEVEL SPLIT")
    print("="*60)

    session_ids = sorted(sessions.keys())
    n_total     = len(session_ids)
    n_test      = max(1, round(n_total * test_frac))
    n_train     = n_total - n_test

    rng = np.random.default_rng(seed)
    shuffled = list(rng.permutation(session_ids))
    train_ids = shuffled[:n_train]
    test_ids  = shuffled[n_train:]

    train_rows = sum(len(sessions[s]) for s in train_ids)
    test_rows  = sum(len(sessions[s]) for s in test_ids)

    print(f"  Total sessions : {n_total}")
    print(f"  Train sessions : {n_train}  ({train_rows:,} rows)  → {[s for s in train_ids]}")
    print(f"  Test  sessions : {n_test}   ({test_rows:,} rows)  → {[s for s in test_ids]}")
    print(f"\n  ✓ No session overlap between train and test")
    return train_ids, test_ids


# ══════════════════════════════════════════════
# STEP 3 — WINDOW GENERATION (SESSION-SAFE)
# ══════════════════════════════════════════════
def _majority_label(labels: np.ndarray) -> int:
    counts = Counter(labels.tolist())
    return counts.most_common(1)[0][0]


def create_windows(sessions: dict, session_ids: list,
                   window_size: int = WINDOW_SIZE,
                   stride: int = STRIDE) -> tuple:
    """
    Build sliding windows independently per session.
    Windows NEVER cross session boundaries → no temporal leakage.
    """
    X_list, y_list = [], []

    for sid in session_ids:
        df = sessions[sid]
        feat = df[FEATURES].values.astype(np.float32)
        lbl  = df["label"].values.astype(np.int32)
        n    = len(feat)
        for start in range(0, n - window_size + 1, stride):
            end = start + window_size
            X_list.append(feat[start:end])
            y_list.append(_majority_label(lbl[start:end]))

    X = np.array(X_list, dtype=np.float32)
    y = np.array(y_list, dtype=np.int32)
    return X, y


def step3_generate_windows(sessions: dict, train_ids: list, test_ids: list) -> tuple:
    print("\n" + "="*60)
    print("STEP 3 — WINDOW GENERATION  (per session, no cross-boundary)")
    print("="*60)
    print(f"  Window size : {WINDOW_SIZE}  |  Stride : {STRIDE}")
    print(f"  Features    : {FEATURES}")
    print(f"  Labeling    : MAJORITY LABEL")

    print("\n  Windowing TRAIN sessions...")
    X_train_raw, y_train_raw = create_windows(sessions, train_ids)

    print(f"  Windowing TEST sessions...")
    X_test, y_test = create_windows(sessions, test_ids)

    print(f"\n  Train windows (raw, pre-balance) : {len(X_train_raw):,}  shape={X_train_raw.shape}")
    print(f"  Test  windows (final)            : {len(X_test):,}  shape={X_test.shape}")

    _print_dist(y_train_raw, "Train (pre-balance)")
    _print_dist(y_test,      "Test  (no balancing)")

    return X_train_raw, y_train_raw, X_test, y_test


def _print_dist(y: np.ndarray, label: str) -> None:
    dist  = Counter(y.tolist())
    total = len(y)
    print(f"\n  Distribution [{label}]:")
    for k in sorted(dist):
        print(f"    {k} ({CLASS_NAMES[k]:<16}): {dist[k]:>8,}  ({dist[k]/total*100:.1f}%)")


# ══════════════════════════════════════════════
# STEP 4 — BALANCE TRAIN ONLY
# ══════════════════════════════════════════════
def balance_train_data(X: np.ndarray, y: np.ndarray, seed: int = 42) -> tuple:
    """
    Downsample NORMAL class only on the TRAIN set.
    Test set is NEVER touched.
    Rules:
      - Keep ALL event windows (classes 1, 2, 3)
      - Downsample class 0 to ≤ NORMAL_RATIO × max_event_count
      - DO NOT oversample / generate synthetic data
    """
    print("\n" + "="*60)
    print("STEP 4 — BALANCE TRAIN SET  (test set untouched)")
    print("="*60)

    dist = Counter(y.tolist())
    event_counts = [dist.get(c, 0) for c in EVENT_CLASSES]
    max_event    = max(event_counts) if event_counts else 1
    normal_target = int(max_event * NORMAL_RATIO)

    print(f"  Largest event class count : {max_event:,}")
    print(f"  NORMAL target (≤{NORMAL_RATIO}×)      : {normal_target:,}")
    print(f"  Current NORMAL count      : {dist.get(NORMAL_CLASS, 0):,}")

    rng = np.random.default_rng(seed)
    indices_by_class = {c: np.where(y == c)[0] for c in dist}
    keep = []

    for c in EVENT_CLASSES:
        idx = indices_by_class.get(c, np.array([], dtype=int))

        # Also remove extreme accel outliers from event classes
        if len(idx) > 0:
            accel_win = X[idx, :, :3]
            clean_mask = ~(np.abs(accel_win) > ACCEL_LIMIT).any(axis=(1, 2))
            idx = idx[clean_mask]

        keep.append(idx)
        print(f"  Class {c} ({CLASS_NAMES[c]:<16}): keeping all {len(idx):,} windows")

    normal_idx = indices_by_class.get(NORMAL_CLASS, np.array([], dtype=int))
    # Remove extreme accel outliers from NORMAL too
    if len(normal_idx) > 0:
        accel_win  = X[normal_idx, :, :3]
        clean_mask = ~(np.abs(accel_win) > ACCEL_LIMIT).any(axis=(1, 2))
        normal_idx = normal_idx[clean_mask]

    if len(normal_idx) > normal_target:
        chosen = rng.choice(normal_idx, size=normal_target, replace=False)
        print(f"  Class 0 (NORMAL          ): downsampled {len(normal_idx):,} → {len(chosen):,}")
    else:
        chosen = normal_idx
        print(f"  Class 0 (NORMAL          ): kept all {len(chosen):,} (already ≤ target)")

    keep.append(chosen)
    all_idx = np.concatenate(keep)
    all_idx = all_idx[rng.permutation(len(all_idx))]   # shuffle

    X_bal = X[all_idx]
    y_bal = y[all_idx]

    print(f"\n  ✓ Balanced train size: {len(X_bal):,} windows")
    _print_dist(y_bal, "Train (post-balance)")
    return X_bal, y_bal


# ══════════════════════════════════════════════
# STEP 5 — NORMALIZATION  (unchanged)
# ══════════════════════════════════════════════
def step5_normalize(X_train: np.ndarray, X_test: np.ndarray, model_dir: Path):
    print("\n" + "="*60)
    print("STEP 5 — NORMALIZATION  (StandardScaler, fit on train only)")
    print("="*60)

    F = X_train.shape[2]
    scaler = StandardScaler()
    scaler.fit(X_train.reshape(-1, F))

    X_train_norm = scaler.transform(X_train.reshape(-1, F)).reshape(X_train.shape).astype(np.float32)
    X_test_norm  = scaler.transform(X_test.reshape(-1,  F)).reshape(X_test.shape).astype(np.float32)

    print(f"  ✓ Scaler fitted on train set only")
    print(f"  Feature means  : {np.round(scaler.mean_, 4).tolist()}")
    print(f"  Feature scales : {np.round(scaler.scale_, 4).tolist()}")

    scaler_payload = {"mean": scaler.mean_.tolist(), "scale": scaler.scale_.tolist()}
    model_dir.mkdir(parents=True, exist_ok=True)
    scaler_path = model_dir / "scaler.json"
    scaler_path.write_text(json.dumps(scaler_payload, indent=2))
    print(f"  Saved scaler   : {scaler_path}")

    return X_train_norm, X_test_norm, scaler


# ══════════════════════════════════════════════
# STEP 6 — CLASS WEIGHTS
# ══════════════════════════════════════════════
def step6_class_weights(y_train: np.ndarray) -> dict:
    print("\n" + "="*60)
    print("STEP 6 — CLASS WEIGHTS")
    print("="*60)

    classes = np.unique(y_train)
    weights = compute_class_weight(class_weight="balanced",
                                   classes=classes,
                                   y=y_train)
    class_weight_dict = {int(cls): float(w) for cls, w in zip(classes, weights)}

    print(f"{'Class':<6} {'Name':<18} {'Weight':>8}")
    print("-" * 35)
    for k in sorted(class_weight_dict):
        print(f"  {k:<4} {CLASS_NAMES[k]:<18} {class_weight_dict[k]:>8.4f}")

    return class_weight_dict


# ══════════════════════════════════════════════
# STEP 7 — MODEL ARCHITECTURE  (unchanged)
# ══════════════════════════════════════════════
def step7_build_model(input_shape: tuple) -> keras.Model:
    """
    Lightweight 1D CNN — 3 conv blocks, GlobalAveragePooling, one dense head.
    Designed for mobile inference: <500 K parameters.
    """
    print("\n" + "="*60)
    print("STEP 7 — MODEL ARCHITECTURE")
    print("="*60)

    model = keras.Sequential([
        layers.Input(shape=input_shape),

        # Block 1
        layers.Conv1D(32, kernel_size=3, padding="same", activation="relu"),
        layers.BatchNormalization(),
        layers.MaxPooling1D(pool_size=2),

        # Block 2
        layers.Conv1D(64, kernel_size=3, padding="same", activation="relu"),
        layers.BatchNormalization(),
        layers.MaxPooling1D(pool_size=2),

        # Block 3
        layers.Conv1D(128, kernel_size=3, padding="same", activation="relu"),
        layers.BatchNormalization(),
        layers.GlobalAveragePooling1D(),

        # Dense head
        layers.Dense(64, activation="relu"),
        layers.Dropout(0.3),

        layers.Dense(NUM_CLASSES, activation="softmax"),
    ], name="driving_behavior_1d_cnn")

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )

    model.summary()
    total_params = model.count_params()
    print(f"\nTotal parameters : {total_params:,}")
    return model


# ══════════════════════════════════════════════
# STEP 8 — TRAINING  (unchanged architecture)
# ══════════════════════════════════════════════
def step8_train(model: keras.Model,
               X_train: np.ndarray, y_train: np.ndarray,
               class_weight_dict: dict) -> keras.callbacks.History:
    print("\n" + "="*60)
    print("STEP 8 — TRAINING")
    print("="*60)
    print(f"Epochs (max)   : {MAX_EPOCHS}")
    print(f"Batch size     : {BATCH_SIZE}")
    print(f"Early stopping : patience=5, restore_best_weights=True")
    print(f"ReduceLROnPlat : patience=3, factor=0.5, min_lr=1e-5")

    early_stop = keras.callbacks.EarlyStopping(
        monitor="val_loss",
        patience=5,
        restore_best_weights=True,
        verbose=1,
    )

    reduce_lr = keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss",
        factor=0.5,
        patience=3,
        min_lr=1e-5,
        verbose=1,
    )

    history = model.fit(
        X_train, y_train,
        validation_split=0.15,
        epochs=MAX_EPOCHS,
        batch_size=BATCH_SIZE,
        class_weight=class_weight_dict,
        callbacks=[early_stop, reduce_lr],
        verbose=1,
    )

    epochs_run = len(history.history["loss"])
    best_val_acc = max(history.history["val_accuracy"])
    print(f"\n✓ Training finished after {epochs_run} epochs")
    print(f"  Best val_accuracy : {best_val_acc*100:.2f}%")
    return history


# ══════════════════════════════════════════════
# STEP 9 — EVALUATION  (on held-out test sessions only)
# ══════════════════════════════════════════════
def step9_evaluate(model: keras.Model,
                   X_train: np.ndarray, y_train: np.ndarray,
                   X_test: np.ndarray,  y_test: np.ndarray) -> dict:
    print("\n" + "="*60)
    print("STEP 9 — EVALUATION  (held-out test sessions)")
    print("="*60)

    train_loss, train_acc = model.evaluate(X_train, y_train, verbose=0, batch_size=256)
    test_loss,  test_acc  = model.evaluate(X_test,  y_test,  verbose=0, batch_size=256)

    print(f"\nTraining accuracy : {train_acc*100:.2f}%   (loss: {train_loss:.4f})")
    print(f"Test accuracy     : {test_acc*100:.2f}%   (loss: {test_loss:.4f})")

    y_pred_proba = model.predict(X_test, verbose=0, batch_size=256)
    y_pred       = np.argmax(y_pred_proba, axis=1)

    print("\n--- Classification Report ---")
    target_names = [CLASS_NAMES[i] for i in range(NUM_CLASSES)]
    report_str = classification_report(y_test, y_pred, target_names=target_names, digits=4)
    print(report_str)

    print("--- Confusion Matrix ---")
    cm = confusion_matrix(y_test, y_pred)
    # Pretty-print with class labels
    header = f"{'':>18}" + "".join(f"{CLASS_NAMES[i]:>16}" for i in range(NUM_CLASSES))
    print(header)
    for i in range(NUM_CLASSES):
        row_label = f"True {CLASS_NAMES[i]:>13}"
        row_vals  = "".join(f"{cm[i][j]:>16}" for j in range(NUM_CLASSES))
        print(row_label + row_vals)

    return {
        "train_acc": train_acc,
        "test_acc":  test_acc,
        "confusion_matrix": cm,
        "y_pred": y_pred,
        "y_pred_proba": y_pred_proba,
    }


# ══════════════════════════════════════════════
# STEP 10 — ANALYSIS
# ══════════════════════════════════════════════
def step10_analysis(y_test: np.ndarray, eval_results: dict) -> None:
    print("\n" + "="*60)
    print("STEP 10 — ANALYSIS")
    print("="*60)

    from sklearn.metrics import classification_report
    y_pred = eval_results["y_pred"]
    report = classification_report(
        y_test, y_pred,
        target_names=[CLASS_NAMES[i] for i in range(NUM_CLASSES)],
        output_dict=True,
    )

    f1_scores  = {i: report[CLASS_NAMES[i]]["f1-score"]  for i in range(NUM_CLASSES)}
    recall     = {i: report[CLASS_NAMES[i]]["recall"]    for i in range(NUM_CLASSES)}
    precision  = {i: report[CLASS_NAMES[i]]["precision"] for i in range(NUM_CLASSES)}

    best  = max(f1_scores, key=f1_scores.get)
    worst = min(f1_scores, key=f1_scores.get)

    print(f"\n  Best  class (F1): {best} ({CLASS_NAMES[best]})  → F1={f1_scores[best]:.4f}")
    print(f"  Worst class (F1): {worst} ({CLASS_NAMES[worst]}) → F1={f1_scores[worst]:.4f}")

    # HARSH_BRAKING (class 2)
    hb_recall = recall[2]
    hb_ok = hb_recall >= 0.70
    print(f"\n  HARSH_BRAKING recall  : {hb_recall:.4f}  "
          f"→ {'✓ ACCEPTABLE (≥0.70)' if hb_ok else '✗ LOW — safety-critical, needs improvement'}")

    # UNSTABLE confusion
    cm = eval_results["confusion_matrix"]
    total_unstable = cm[3].sum()
    confused_away  = total_unstable - cm[3][3]
    if total_unstable > 0:
        print(f"\n  UNSTABLE_RIDE confusion: {confused_away} / {total_unstable} windows "
              f"misclassified ({confused_away/total_unstable*100:.1f}%)")
        if confused_away / total_unstable > 0.3:
            print("    ⚠ UNSTABLE is heavily confused with other classes — "
                  "more real UNSTABLE samples recommended")
        else:
            print("    ✓ UNSTABLE confusion is within acceptable range")
    else:
        print("  UNSTABLE_RIDE: no test samples present")

    print(f"\n  Summary:")
    for i in range(NUM_CLASSES):
        print(f"    {i} ({CLASS_NAMES[i]:<16})  "
              f"P={precision[i]:.4f}  R={recall[i]:.4f}  F1={f1_scores[i]:.4f}")


# ══════════════════════════════════════════════
# STEP 11 — COMPARISON  (OLD leaky vs NEW session-based)
# ══════════════════════════════════════════════
def step11_comparison(new_test_acc: float, new_macro_f1: float) -> None:
    print("\n" + "="*60)
    print("STEP 11 — COMPARISON: OLD (leaky) vs NEW (session-based)")
    print("="*60)

    print(f"\n  {'Metric':<22} {'OLD (leaky)':<16} {'NEW (session-based)'}")
    print(f"  {'-'*58}")
    print(f"  {'Test accuracy':<22} {OLD_TEST_ACC:<16.2f} {new_test_acc*100:.2f}")
    print(f"  {'Macro F1':<22} {OLD_MACRO_F1:<16.4f} {new_macro_f1:.4f}")

    delta_acc = new_test_acc * 100 - OLD_TEST_ACC
    print(f"\n  Accuracy delta : {delta_acc:+.2f}%")

    print("\n  WHY accuracy dropped (expected):")
    print("    1. OLD split: windows randomly mixed → model 'memorises' nearby")
    print("       windows from the same session that appear in both train & test.")
    print("    2. With stride=1 on merged data, consecutive windows overlap by")
    print("       49/50 timesteps → near-identical samples on both sides of split.")
    print("    3. NEW split: entire sessions withheld → model must GENERALISE to")
    print("       unseen driving patterns, producing an honest accuracy estimate.")
    print("\n  The NEW accuracy is the figure you should report and trust.")
    if new_test_acc * 100 >= 80:
        print("  ✓ Model generalises well across sessions.")
    else:
        print("  ⚠ Model struggles to generalise — collect more diverse sessions.")


# ══════════════════════════════════════════════
# STEP 12 — EXPORT
# ══════════════════════════════════════════════
def step12_export(model: keras.Model, model_dir: Path) -> None:
    print("\n" + "="*60)
    print("STEP 12 — EXPORT MODEL ARTIFACTS")
    print("="*60)

    model_dir.mkdir(parents=True, exist_ok=True)

    # 1. Keras format
    keras_path = model_dir / "driving_behavior_model.keras"
    model.save(keras_path)
    print(f"✓ Keras model saved : {keras_path}  "
          f"({keras_path.stat().st_size / 1024:.1f} KB)")

    # 2. TFLite (float32 — compatible with Android TFLite interpreter directly)
    tflite_path = model_dir / "driving_behavior_model.tflite"
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    tflite_path.write_bytes(tflite_bytes)
    print(f"✓ TFLite model saved: {tflite_path}  "
          f"({tflite_path.stat().st_size / 1024:.1f} KB)")

    # 3. labels.json
    labels_path = model_dir / "labels.json"
    labels_payload = {str(k): v for k, v in CLASS_NAMES.items()}
    labels_path.write_text(json.dumps(labels_payload, indent=2))
    print(f"✓ Labels saved      : {labels_path}")

    # scaler.json already saved in step3


# ══════════════════════════════════════════════
# STEP 13 — ANDROID INTEGRATION CHECK
# ══════════════════════════════════════════════
def step13_android_check(X_test_norm: np.ndarray,
                         y_test: np.ndarray,
                         model_dir: Path,
                         scaler: StandardScaler) -> None:
    print("\n" + "="*60)
    print("STEP 10 — ANDROID INTEGRATION CHECK")
    print("="*60)

    # Load TFLite model and run inference
    tflite_path = str(model_dir / "driving_behavior_model.tflite")
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()

    in_details  = interpreter.get_input_details()
    out_details = interpreter.get_output_details()

    in_shape  = in_details[0]["shape"]
    in_dtype  = in_details[0]["dtype"]
    out_shape = out_details[0]["shape"]

    print(f"  TFLite input  shape : {in_shape}   dtype: {in_dtype}")
    print(f"  TFLite output shape : {out_shape}")

    # Android expects (1, 50, 8) float32
    assert list(in_shape) == [1, WINDOW_SIZE, N_FEATURES], (
        f"Input shape mismatch! Expected [1, {WINDOW_SIZE}, {N_FEATURES}], got {in_shape}")
    assert in_dtype == np.float32, f"Input dtype must be float32, got {in_dtype}"
    assert list(out_shape) == [1, NUM_CLASSES], (
        f"Output shape mismatch! Expected [1, {NUM_CLASSES}], got {out_shape}")

    print(f"\n✓ Input  shape OK  : (1, {WINDOW_SIZE}, {N_FEATURES})")
    print(f"✓ Input  dtype OK  : float32")
    print(f"✓ Output shape OK  : (1, {NUM_CLASSES})")

    # Example inference — take 1 sample from each class
    print(f"\n  --- Example inference (1 sample per class) ---")
    for cls in range(NUM_CLASSES):
        idx_list = np.where(y_test == cls)[0]
        if len(idx_list) == 0:
            print(f"  Class {cls}: no test sample available")
            continue

        sample = X_test_norm[idx_list[0]]                      # (50, 8)
        tensor = sample[np.newaxis, :, :].astype(np.float32)   # (1, 50, 8)

        interpreter.set_tensor(in_details[0]["index"], tensor)
        interpreter.invoke()
        probs = interpreter.get_tensor(out_details[0]["index"])[0]  # (4,)

        pred_cls  = int(np.argmax(probs))
        correct   = "✓" if pred_cls == cls else "✗"
        prob_str  = "  ".join(f"p({CLASS_NAMES[i]})={probs[i]:.4f}" for i in range(NUM_CLASSES))
        print(f"  {correct} True={CLASS_NAMES[cls]:<16} Pred={CLASS_NAMES[pred_cls]:<16}  |  {prob_str}")


# ══════════════════════════════════════════════
# STEP 14 — FINAL VERDICT
# ══════════════════════════════════════════════
def step14_verdict(eval_results: dict, y_test: np.ndarray) -> None:
    print("\n" + "="*60)
    print("STEP 14 — FINAL VERDICT")
    print("="*60)

    from sklearn.metrics import classification_report
    y_pred = eval_results["y_pred"]
    test_acc = eval_results["test_acc"]
    report = classification_report(
        y_test, y_pred,
        target_names=[CLASS_NAMES[i] for i in range(NUM_CLASSES)],
        output_dict=True,
    )

    f1_scores = {i: report[CLASS_NAMES[i]]["f1-score"] for i in range(NUM_CLASSES)}
    macro_f1  = report["macro avg"]["f1-score"]
    recall    = {i: report[CLASS_NAMES[i]]["recall"] for i in range(NUM_CLASSES)}
    worst_cls = min(f1_scores, key=f1_scores.get)

    # Rating thresholds
    if macro_f1 >= 0.85:
        rating = "GOOD"
    elif macro_f1 >= 0.70:
        rating = "OK"
    else:
        rating = "BAD"

    print(f"\n  Test accuracy   : {test_acc*100:.2f}%")
    print(f"  Macro F1        : {macro_f1:.4f}")
    print(f"  Model rating    : {rating}")

    print(f"\n  Class needing improvement   : {worst_cls} ({CLASS_NAMES[worst_cls]})  "
          f"F1={f1_scores[worst_cls]:.4f}")

    # Collect more data?
    need_more = any(recall[i] < 0.65 for i in range(NUM_CLASSES))
    print(f"  Collect more data?          : {'YES' if need_more else 'NO'}")
    if need_more:
        for i in range(NUM_CLASSES):
            if recall[i] < 0.65:
                print(f"    → Class {i} ({CLASS_NAMES[i]}) recall={recall[i]:.4f} < 0.65 "
                      f"— gather more labelled samples")
    else:
        print("    → All classes recall ≥ 0.65. Current dataset is sufficient.")

    print(f"\n  Deployment advice:")
    if rating == "GOOD":
        print("    Model is production-ready. Deploy to Android.")
    elif rating == "OK":
        print("    Model is usable but not production-grade.")
        print("    Collect ≥500 extra samples for the weakest class before final release.")
    else:
        print("    Model is too weak. Do NOT deploy.")
        print("    Root causes: insufficient data for minority classes or label noise.")


# ══════════════════════════════════════════════
# MAIN PIPELINE
# ══════════════════════════════════════════════
def run_pipeline():
    print("\n" + "█"*60)
    print("  TELEDRIVE — 1D CNN TRAINING PIPELINE  (Session-Based Split)")
    print("█"*60)

    # ── Step 1: load raw sessions ─────────────────────────────
    sessions = load_sessions(RAW_DIR)

    # ── Step 2: session-level split ──────────────────────────
    train_ids, test_ids = split_sessions(sessions)

    # ── Step 3: window generation (per session, no leakage) ──
    X_train_raw, y_train_raw, X_test, y_test = step3_generate_windows(
        sessions, train_ids, test_ids)

    # ── Step 4: balance TRAIN only ───────────────────────────
    X_train_bal, y_train_bal = balance_train_data(X_train_raw, y_train_raw)

    # ── Step 5: normalisation (fit on train only) ─────────────
    X_train_norm, X_test_norm, scaler = step5_normalize(
        X_train_bal, X_test, MODEL_DIR)

    # ── Step 6: class weights ────────────────────────────────
    class_weight_dict = step6_class_weights(y_train_bal)

    # ── Step 7: build model ──────────────────────────────────
    model = step7_build_model(input_shape=(WINDOW_SIZE, N_FEATURES))

    # ── Step 8: train ────────────────────────────────────────
    step8_train(model, X_train_norm, y_train_bal, class_weight_dict)

    # ── Step 9: evaluate ─────────────────────────────────────
    eval_results = step9_evaluate(model, X_train_norm, y_train_bal,
                                  X_test_norm, y_test)

    # ── Collect macro F1 for comparison step ─────────────────
    from sklearn.metrics import classification_report as _cr
    _report = _cr(y_test, eval_results["y_pred"],
                  target_names=[CLASS_NAMES[i] for i in range(NUM_CLASSES)],
                  output_dict=True)
    new_macro_f1 = _report["macro avg"]["f1-score"]

    # ── Step 10: analysis ────────────────────────────────────
    step10_analysis(y_test, eval_results)

    # ── Step 11: comparison old vs new ───────────────────────
    step11_comparison(eval_results["test_acc"], new_macro_f1)

    # ── Step 12: export artifacts ────────────────────────────
    step12_export(model, MODEL_DIR)

    # ── Step 13: android integration check ───────────────────
    step13_android_check(X_test_norm, y_test, MODEL_DIR, scaler)

    # ── Step 14: final verdict ───────────────────────────────
    step14_verdict(eval_results, y_test)

    print("\n" + "█"*60)
    print("  PIPELINE COMPLETE")
    print("█"*60 + "\n")


if __name__ == "__main__":
    run_pipeline()
