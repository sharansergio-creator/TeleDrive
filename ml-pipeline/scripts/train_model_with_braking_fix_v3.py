"""
TeleDrive ML Pipeline - Train 1D CNN Model WITH BRAKING DETECTION FIX V3 (FINAL)
=========================================================================
CRITICAL UPDATE: Speed signal is unreliable (median change = 0.0 km/h).
This version REMOVES speed filtering and focuses on acceleration-only features.

Input  : ml-pipeline/data/raw/ride_session_*.csv
Outputs: ml-pipeline/models/driving_behavior_model_fixed_v3.keras
         ml-pipeline/models/driving_behavior_model_fixed_v3.tflite
         ml-pipeline/models/scaler_fixed_v3.json
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
BATCH_SIZE    = 64
MAX_EPOCHS    = 50
TEST_FRACTION = 0.20
NORMAL_CLASS  = 0
EVENT_CLASSES = [1, 2, 3]
NORMAL_RATIO  = 2.5
ACCEL_LIMIT   = 50.0

# BRAKING FIX PARAMETERS V3
LABEL_SHIFT_SAMPLES = 26  # Keep label alignment
BRAKING_WEIGHT_BOOST = 3.0  # Increased boost (more aggressive)
NEG_AX_THRESHOLD = 1.0  # m/s² threshold for braking detection

print("\n" + "="*80)
print("🔧 BRAKING DETECTION FIX V3 (FINAL) - ACCELERATION-ONLY APPROACH")
print("="*80)
print(f"Label shift              : +{LABEL_SHIFT_SAMPLES} samples")
print(f"Braking weight boost     : {BRAKING_WEIGHT_BOOST}x")
print(f"Neg ax threshold         : {NEG_AX_THRESHOLD} m/s²")
print(f"Speed filtering          : DISABLED (speed unreliable)")
print("="*80)


# ══════════════════════════════════════════════
# STEP 1 — LOAD SESSIONS WITH ACCELERATION FEATURES
# ══════════════════════════════════════════════
def apply_label_shift(df: pd.DataFrame, shift_samples: int = LABEL_SHIFT_SAMPLES) -> pd.DataFrame:
    """Shift braking labels forward to align with actual deceleration peak."""
    df = df.copy()
    
    braking_indices = df[df['label'] == 2].index.tolist()
    
    # Clear original braking labels
    for idx in braking_indices:
        df.at[idx, 'label'] = 0
    
    # Apply shifted braking labels
    shifted_count = 0
    for idx in braking_indices:
        new_idx = idx + shift_samples
        if new_idx < len(df):
            if df.at[new_idx, 'label'] == 0:
                df.at[new_idx, 'label'] = 2
                shifted_count += 1
    
    print(f"  Label shift: {shifted_count}/{len(braking_indices)} braking labels shifted +{shift_samples} samples")
    return df


def create_acceleration_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Create ACCELERATION-ONLY features (speed is unreliable).
    
    Focus on physics-based deceleration features using only IMU data.
    """
    df = df.copy()
    
    # 1. NEGATIVE ACCELERATION (emphasized braking)
    df['neg_ax'] = df['ax'].apply(lambda x: -x if x < -NEG_AX_THRESHOLD else 0)
    
    # 2. SMOOTHED ACCELERATION (noise reduction)
    df['ax_smooth'] = df['ax'].rolling(window=5, center=True, min_periods=1).mean()
    
    # 3. JERK (rate of acceleration change)
    df['jerk'] = df['ax'].diff().fillna(0)
    df['jerk_neg'] = df['jerk'].apply(lambda x: x if x < 0 else 0)
    
    # 4. ACCELERATION VARIANCE (instability vs smooth braking)
    df['ax_var'] = df['ax'].rolling(window=10, center=True, min_periods=1).std().fillna(0)
    
    # 5. CUMULATIVE NEGATIVE ACCELERATION (braking intensity)
    df['cum_neg_ax'] = df['neg_ax'].rolling(window=5, min_periods=1).sum()
    
    # 6. COMBINED BRAKING SIGNAL (physics composite)
    # Weighted combination of deceleration indicators
    df['brake_signal'] = (
        0.4 * df['neg_ax'] +
        0.3 * df['cum_neg_ax'] / 5.0 +  # Normalized
        0.3 * (-df['jerk_neg'])
    )
    
    return df


def load_sessions(raw_dir: Path) -> dict:
    """Load sessions with V3 acceleration-only features."""
    print("\n" + "="*60)
    print("STEP 1 — LOAD RAW SESSIONS (V3 acceleration-only)")
    print("="*60)

    csv_files = sorted(raw_dir.glob("ride_session_*.csv"))
    if not csv_files:
        raise FileNotFoundError(f"No ride_session_*.csv files found in {raw_dir}")

    sessions = {}
    total_braking_before = 0
    total_braking_after = 0
    
    for f in csv_files:
        df = pd.read_csv(f)
        
        required = ["timestamp", "ax", "ay", "az", "gx", "gy", "gz", "speed", "label"]
        missing  = [c for c in required if c not in df.columns]
        if missing:
            print(f"  [WARN] {f.name}: missing {missing} — skipped")
            continue
        
        df = df.dropna(subset=required).drop_duplicates(subset=required)
        df = df.sort_values("timestamp").reset_index(drop=True)
        df["timestamp_norm"] = (df["timestamp"] - df["timestamp"].iloc[0]) / 1e9
        
        # Count before
        total_braking_before += (df['label'] == 2).sum()
        
        # Apply label shift
        df = apply_label_shift(df, LABEL_SHIFT_SAMPLES)
        
        # Create acceleration-based features
        df = create_acceleration_features(df)
        
        # Count after (NO FILTERING - keep all braking labels)
        total_braking_after += (df['label'] == 2).sum()
        
        sessions[f.stem] = df

    total_rows = sum(len(df) for df in sessions.values())
    print(f"\n  Loaded {len(sessions)} sessions  |  {total_rows:,} total rows")
    print(f"  ✅ Label alignment applied")
    print(f"  ✅ Acceleration-based features created")
    print(f"  ✅ Braking labels preserved: {total_braking_before} → {total_braking_after} samples")

    # Row-level distribution
    all_labels = np.concatenate([df["label"].values for df in sessions.values()])
    dist = Counter(all_labels.tolist())
    print(f"\n  Row-level label distribution:")
    for k in sorted(dist):
        print(f"    {k} ({CLASS_NAMES[k]:<16}): {dist[k]:>8,}  ({dist[k]/len(all_labels)*100:.1f}%)")

    return sessions


# ══════════════════════════════════════════════
# STEP 2 — SESSION-LEVEL SPLIT (unchanged)
# ══════════════════════════════════════════════
def split_sessions(sessions: dict, test_frac: float = TEST_FRACTION, seed: int = 42) -> tuple:
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
    print(f"  Train sessions : {n_train}  ({train_rows:,} rows)")
    print(f"  Test  sessions : {n_test}   ({test_rows:,} rows)")
    
    return train_ids, test_ids


# ══════════════════════════════════════════════
# STEP 3 — WINDOW GENERATION
# ══════════════════════════════════════════════

# V3 Features (acceleration-only, NO speed)
V3_FEATURES = [
    # Base IMU sensors
    "ax", "ay", "az", "gx", "gy", "gz",
    # Braking-specific features (acceleration-based)
    "neg_ax", "ax_smooth", "jerk", "jerk_neg", "ax_var", "cum_neg_ax", "brake_signal"
]

N_FEATURES = len(V3_FEATURES)

def _majority_label(labels: np.ndarray) -> int:
    counts = Counter(labels.tolist())
    return counts.most_common(1)[0][0]


def create_windows(sessions: dict, session_ids: list,
                   window_size: int = WINDOW_SIZE,
                   stride: int = STRIDE) -> tuple:
    X_list, y_list = [], []

    for sid in session_ids:
        df = sessions[sid]
        feat = df[V3_FEATURES].values.astype(np.float32)
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
    print("STEP 3 — WINDOW GENERATION (V3 acceleration-only)")
    print("="*60)
    print(f"  Window size    : {WINDOW_SIZE}  |  Stride : {STRIDE}")
    print(f"  Features ({N_FEATURES}): {V3_FEATURES}")

    X_train_raw, y_train_raw = create_windows(sessions, train_ids)
    X_test, y_test = create_windows(sessions, test_ids)

    print(f"\n  Train windows: {len(X_train_raw):,}  shape={X_train_raw.shape}")
    print(f"  Test  windows: {len(X_test):,}  shape={X_test.shape}")

    _print_dist(y_train_raw, "Train (pre-balance)")
    _print_dist(y_test,      "Test")

    return X_train_raw, y_train_raw, X_test, y_test


def _print_dist(y: np.ndarray, label: str) -> None:
    dist  = Counter(y.tolist())
    total = len(y)
    print(f"\n  Distribution [{label}]:")
    for k in sorted(dist):
        print(f"    {k} ({CLASS_NAMES[k]:<16}): {dist[k]:>8,}  ({dist[k]/total*100:.1f}%)")


# ══════════════════════════════════════════════
# STEP 4 — BALANCE (unchanged logic)
# ══════════════════════════════════════════════
def balance_train_data(X: np.ndarray, y: np.ndarray, seed: int = 42) -> tuple:
    print("\n" + "="*60)
    print("STEP 4 — BALANCE TRAIN SET")
    print("="*60)

    dist = Counter(y.tolist())
    event_counts = [dist.get(c, 0) for c in EVENT_CLASSES]
    max_event    = max(event_counts) if event_counts else 1
    normal_target = int(max_event * NORMAL_RATIO)

    print(f"  Largest event class count : {max_event:,}")
    print(f"  NORMAL target (≤{NORMAL_RATIO}×)      : {normal_target:,}")

    rng = np.random.default_rng(seed)
    indices_by_class = {c: np.where(y == c)[0] for c in dist}
    keep = []

    for c in EVENT_CLASSES:
        idx = indices_by_class.get(c, np.array([], dtype=int))
        if len(idx) > 0:
            accel_win = X[idx, :, :3]
            clean_mask = ~(np.abs(accel_win) > ACCEL_LIMIT).any(axis=(1, 2))
            idx = idx[clean_mask]
        keep.append(idx)
        print(f"  Class {c} ({CLASS_NAMES[c]:<16}): keeping all {len(idx):,} windows")

    normal_idx = indices_by_class.get(NORMAL_CLASS, np.array([], dtype=int))
    if len(normal_idx) > 0:
        accel_win  = X[normal_idx, :, :3]
        clean_mask = ~(np.abs(accel_win) > ACCEL_LIMIT).any(axis=(1, 2))
        normal_idx = normal_idx[clean_mask]

    if len(normal_idx) > normal_target:
        chosen = rng.choice(normal_idx, size=normal_target, replace=False)
        print(f"  Class 0 (NORMAL          ): downsampled {len(normal_idx):,} → {len(chosen):,}")
    else:
        chosen = normal_idx
        print(f"  Class 0 (NORMAL          ): kept all {len(chosen):,}")

    keep.append(chosen)
    all_idx = np.concatenate(keep)
    all_idx = all_idx[rng.permutation(len(all_idx))]

    X_bal = X[all_idx]
    y_bal = y[all_idx]

    print(f"\n  ✓ Balanced train size: {len(X_bal):,} windows")
    _print_dist(y_bal, "Train (post-balance)")
    return X_bal, y_bal


# ══════════════════════════════════════════════
# STEP 5 — NORMALIZATION
# ══════════════════════════════════════════════
def step5_normalize(X_train: np.ndarray, X_test: np.ndarray, model_dir: Path):
    print("\n" + "="*60)
    print("STEP 5 — NORMALIZATION (per-feature StandardScaler)")
    print("="*60)

    F = X_train.shape[2]
    scaler = StandardScaler()
    scaler.fit(X_train.reshape(-1, F))

    X_train_norm = scaler.transform(X_train.reshape(-1, F)).reshape(X_train.shape).astype(np.float32)
    X_test_norm  = scaler.transform(X_test.reshape(-1,  F)).reshape(X_test.shape).astype(np.float32)

    print(f"  ✓ Scaler fitted on train set ({F} features)")

    scaler_payload = {"mean": scaler.mean_.tolist(), "scale": scaler.scale_.tolist()}
    model_dir.mkdir(parents=True, exist_ok=True)
    scaler_path = model_dir / "scaler_fixed_v3.json"
    scaler_path.write_text(json.dumps(scaler_payload, indent=2))
    print(f"  Saved: {scaler_path}")

    return X_train_norm, X_test_norm, scaler


# ══════════════════════════════════════════════
# STEP 6 — CLASS WEIGHTS (increased braking boost)
# ══════════════════════════════════════════════
def step6_class_weights(y_train: np.ndarray) -> dict:
    print("\n" + "="*60)
    print("STEP 6 — CLASS WEIGHTS (with aggressive braking boost)")
    print("="*60)

    classes = np.unique(y_train)
    weights = compute_class_weight(class_weight="balanced", classes=classes, y=y_train)
    class_weight_dict = {int(cls): float(w) for cls, w in zip(classes, weights)}

    if 2 in class_weight_dict:
        original = class_weight_dict[2]
        class_weight_dict[2] *= BRAKING_WEIGHT_BOOST
        print(f"  ✨ BRAKING boost: {original:.4f} → {class_weight_dict[2]:.4f}")

    print(f"\n{'Class':<6} {'Name':<18} {'Weight':>8}")
    print("-" * 35)
    for k in sorted(class_weight_dict):
        print(f"  {k:<4} {CLASS_NAMES[k]:<18} {class_weight_dict[k]:>8.4f}")

    return class_weight_dict


# ══════════════════════════════════════════════
# STEP 7 — MODEL
# ══════════════════════════════════════════════
def step7_build_model(input_shape: tuple) -> keras.Model:
    print("\n" + "="*60)
    print("STEP 7 — MODEL ARCHITECTURE")
    print("="*60)

    inputs = layers.Input(shape=input_shape)

    # Block 1
    x = layers.Conv1D(64, kernel_size=5, padding="same")(inputs)
    x = layers.BatchNormalization()(x)
    x = layers.Activation("relu")(x)
    x = layers.Dropout(0.3)(x)

    # Block 2 with residual
    residual = x
    x = layers.Conv1D(64, kernel_size=5, padding="same")(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation("relu")(x)
    x = layers.Add()([x, residual])
    x = layers.MaxPooling1D(pool_size=2)(x)
    x = layers.Dropout(0.3)(x)

    # Block 3
    x = layers.Conv1D(128, kernel_size=3, padding="same")(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation("relu")(x)
    x = layers.Dropout(0.4)(x)

    # Block 4
    x = layers.Conv1D(128, kernel_size=3, padding="same")(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation("relu")(x)
    x = layers.MaxPooling1D(pool_size=2)(x)
    x = layers.Dropout(0.4)(x)

    # Global pooling
    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dense(128, activation="relu")(x)
    x = layers.Dropout(0.5)(x)
    x = layers.Dense(64, activation="relu")(x)
    x = layers.Dropout(0.5)(x)

    outputs = layers.Dense(NUM_CLASSES, activation="softmax")(x)

    model = keras.Model(inputs, outputs, name="driving_behavior_1d_cnn_v3")

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )

    model.summary()
    print(f"\nTotal parameters: {model.count_params():,}")
    
    return model


# ══════════════════════════════════════════════
# STEP 8 — TRAINING
# ══════════════════════════════════════════════
def step8_train(model: keras.Model, X_train: np.ndarray, y_train: np.ndarray,
               class_weight_dict: dict) -> keras.callbacks.History:
    print("\n" + "="*60)
    print("STEP 8 — TRAINING")
    print("="*60)

    early_stop = keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=10, restore_best_weights=True, verbose=1)

    reduce_lr = keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss", factor=0.5, patience=5, min_lr=1e-6, verbose=1)

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
    print(f"  Best val_accuracy: {best_val_acc*100:.2f}%")
    return history


# ══════════════════════════════════════════════
# STEP 9 — EVALUATION
# ══════════════════════════════════════════════
def step9_evaluate(model: keras.Model, X_train: np.ndarray, y_train: np.ndarray,
                   X_test: np.ndarray, y_test: np.ndarray) -> dict:
    print("\n" + "="*60)
    print("STEP 9 — EVALUATION")
    print("="*60)

    train_loss, train_acc = model.evaluate(X_train, y_train, verbose=0, batch_size=256)
    test_loss,  test_acc  = model.evaluate(X_test,  y_test,  verbose=0, batch_size=256)

    print(f"\nTrain accuracy: {train_acc*100:.2f}%")
    print(f"Test accuracy:  {test_acc*100:.2f}%")

    y_pred_proba = model.predict(X_test, verbose=0, batch_size=256)
    y_pred       = np.argmax(y_pred_proba, axis=1)

    print("\n" + "="*80)
    print("CLASSIFICATION REPORT")
    print("="*80)
    target_names = [CLASS_NAMES[i] for i in range(NUM_CLASSES)]
    report_str = classification_report(y_test, y_pred, target_names=target_names, digits=4)
    print(report_str)
    
    report_dict = classification_report(y_test, y_pred, target_names=target_names, output_dict=True)

    print("\n" + "="*80)
    print("CONFUSION MATRIX")
    print("="*80)
    cm = confusion_matrix(y_test, y_pred)
    
    header = f"{'':>18}" + "".join(f"{CLASS_NAMES[i]:>16}" for i in range(NUM_CLASSES))
    print(header)
    for i in range(NUM_CLASSES):
        row_label = f"True {CLASS_NAMES[i]:>13}"
        row_vals  = "".join(f"{cm[i][j]:>16}" for j in range(NUM_CLASSES))
        print(row_label + row_vals)

    # BRAKING ANALYSIS
    print("\n" + "="*80)
    print("🎯 BRAKING DETECTION PERFORMANCE")
    print("="*80)
    
    if 2 in np.unique(y_test):
        braking_metrics = report_dict["HARSH_BRAKING"]
        braking_recall = braking_metrics['recall']
        braking_precision = braking_metrics['precision']
        braking_f1 = braking_metrics['f1-score']
        
        print(f"Recall:    {braking_recall*100:.1f}%")
        print(f"Precision: {braking_precision*100:.1f}%")
        print(f"F1-Score:  {braking_f1*100:.1f}%")
        
        if braking_recall >= 0.70:
            print("✅ TARGET MET: Braking recall ≥ 70%")
        else:
            print(f"⚠️  BELOW TARGET: {braking_recall*100:.1f}% < 70%")

    return {
        "train_acc": train_acc,
        "test_acc":  test_acc,
        "confusion_matrix": cm,
        "y_pred": y_pred,
        "y_pred_proba": y_pred_proba,
        "report": report_dict
    }


# ══════════════════════════════════════════════
# STEP 10 — EXPORT
# ══════════════════════════════════════════════
def step10_export(model: keras.Model, model_dir: Path) -> None:
    print("\n" + "="*60)
    print("STEP 10 — EXPORT MODEL ARTIFACTS")
    print("="*60)

    model_dir.mkdir(parents=True, exist_ok=True)

    keras_path = model_dir / "driving_behavior_model_fixed_v3.keras"
    model.save(keras_path)
    print(f"✓ Keras: {keras_path}  ({keras_path.stat().st_size / 1024:.1f} KB)")

    tflite_path = model_dir / "driving_behavior_model_fixed_v3.tflite"
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    tflite_path.write_bytes(tflite_bytes)
    print(f"✓ TFLite: {tflite_path}  ({tflite_path.stat().st_size / 1024:.1f} KB)")

    labels_path = model_dir / "labels.json"
    labels_payload = {str(k): v for k, v in CLASS_NAMES.items()}
    labels_path.write_text(json.dumps(labels_payload, indent=2))
    print(f"✓ Labels: {labels_path}")

    config_path = model_dir / "feature_config_v3.json"
    feature_config = {
        "version": 3,
        "window_size": WINDOW_SIZE,
        "num_features": N_FEATURES,
        "features": V3_FEATURES,
        "label_shift": LABEL_SHIFT_SAMPLES,
        "braking_weight_boost": BRAKING_WEIGHT_BOOST,
        "neg_ax_threshold": NEG_AX_THRESHOLD,
        "note": "Acceleration-only features (speed unreliable)"
    }
    config_path.write_text(json.dumps(feature_config, indent=2))
    print(f"✓ Config: {config_path}")


# ══════════════════════════════════════════════
# STEP 11 — FINAL SUMMARY
# ══════════════════════════════════════════════
def step11_summary(eval_results: dict) -> None:
    print("\n" + "█"*80)
    print("  BRAKING DETECTION FIX V3 (FINAL) - SUMMARY")
    print("█"*80)

    report = eval_results["report"]
    
    print("\n✅ V3 APPROACH (Acceleration-Only):")
    print("  1. ✅ Label shift applied (+26 samples)")
    print("  2. ✅ Acceleration-only features (7 braking-specific)")
    print("  3. ✅ Speed EXCLUDED (median change = 0.0 km/h)")
    print("  4. ✅ Aggressive class weighting (3.0x)")
    print("  5. ✅ All braking labels preserved (no filtering)")
    
    print("\n📊 PERFORMANCE:")
    for cls_name in ["NORMAL", "HARSH_ACCEL", "HARSH_BRAKING", "UNSTABLE_RIDE"]:
        if cls_name in report:
            metrics = report[cls_name]
            recall = metrics['recall']
            status = "✅" if recall >= 0.70 else ("🟡" if recall >= 0.50 else "❌")
            print(f"  {status} {cls_name:16s}: R={recall*100:.1f}% P={metrics['precision']*100:.1f}% F1={metrics['f1-score']*100:.1f}%")
    
    braking_recall = report.get("HARSH_BRAKING", {}).get("recall", 0)
    
    print("\n" + "="*80)
    if braking_recall >= 0.70:
        print("✅ ✅ ✅  SUCCESS - BRAKING DETECTION FIXED  ✅ ✅ ✅")
    elif braking_recall >= 0.50:
        print("🟡 PARTIAL SUCCESS - Braking improved but below target")
    else:
        print("❌ NEEDS MORE WORK - Consider label quality review")
    print("="*80)


# ══════════════════════════════════════════════
# MAIN PIPELINE
# ══════════════════════════════════════════════
def run_pipeline():
    print("\n" + "█"*80)
    print("  TELEDRIVE — BRAKING DETECTION FIX V3 (FINAL)")
    print("█"*80)

    sessions = load_sessions(RAW_DIR)
    train_ids, test_ids = split_sessions(sessions)
    X_train_raw, y_train_raw, X_test, y_test = step3_generate_windows(sessions, train_ids, test_ids)
    X_train_bal, y_train_bal = balance_train_data(X_train_raw, y_train_raw)
    X_train_norm, X_test_norm, scaler = step5_normalize(X_train_bal, X_test, MODEL_DIR)
    class_weight_dict = step6_class_weights(y_train_bal)
    model = step7_build_model(input_shape=(WINDOW_SIZE, N_FEATURES))
    step8_train(model, X_train_norm, y_train_bal, class_weight_dict)
    eval_results = step9_evaluate(model, X_train_norm, y_train_bal, X_test_norm, y_test)
    step10_export(model, MODEL_DIR)
    step11_summary(eval_results)

    print("\n" + "█"*80)
    print("  PIPELINE COMPLETE")
    print("█"*80 + "\n")


if __name__ == "__main__":
    run_pipeline()

