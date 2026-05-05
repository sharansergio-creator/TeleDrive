"""
TeleDrive -- ONE-COMMAND ML Pipeline Runner
==========================================
Usage:
    python run_pipeline.py              # full run: train --> export --> deploy to Android
    python run_pipeline.py --skip-train # skip training, re-deploy existing artifacts

What it does
------------
1.  [Train]   Run scripts/train_model.py  (skipped with --skip-train)
2.  [Version] Read models/version_history.json, increment version counter
3.  [Archive] Copy driving_behavior_model.tflite --> models/driving_model_vN.tflite
4.  [Deploy]  Copy model.tflite / scaler.json / labels.json --> Android assets
5.  [Verify]  Validate TFLite input/output shape matches contract (1,50,8) / (1,4)
6.  [History] Append new entry to version_history.json
7.  [Report]  Print final deployment summary

Requirements
------------
- Python ≥ 3.8
- tensorflow (for TFLite shape validation)
- shutil, json, subprocess -- stdlib only otherwise
"""

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

# ──────────────────────────────────────────────────────────────────────────────
# PATHS
# ──────────────────────────────────────────────────────────────────────────────
ROOT          = Path(__file__).resolve().parent           # ml-pipeline/
SCRIPTS_DIR   = ROOT / "scripts"
MODELS_DIR    = ROOT / "models"
ANDROID_ASSETS = ROOT.parent / "android-app" / "app" / "src" / "main" / "assets"

# Pipeline artifacts produced by train_model.py
TFLITE_SRC    = MODELS_DIR / "driving_behavior_model.tflite"
SCALER_SRC    = MODELS_DIR / "scaler.json"
LABELS_SRC    = MODELS_DIR / "labels.json"

# Android deploy targets
TFLITE_DEST   = ANDROID_ASSETS / "model.tflite"
SCALER_DEST   = ANDROID_ASSETS / "scaler.json"
LABELS_DEST   = ANDROID_ASSETS / "labels.json"

# Version registry
VERSION_FILE  = MODELS_DIR / "version_history.json"

# Expected model contract
EXPECTED_INPUT_SHAPE  = (1, 50, 8)   # (batch, window, features)
EXPECTED_OUTPUT_SHAPE = (1, 4)        # (batch, classes)


# ──────────────────────────────────────────────────────────────────────────────
# BANNER
# ──────────────────────────────────────────────────────────────────────────────
def banner(msg: str) -> None:
    width = 62
    print()
    print("=" * width)
    print(f"  {msg}")
    print("=" * width)


def step(n: int, msg: str) -> None:
    print(f"\n[Step {n}] {msg}")
    print("-" * 50)


# ──────────────────────────────────────────────────────────────────────────────
# STEP 1 -- TRAIN
# ──────────────────────────────────────────────────────────────────────────────
def run_training() -> None:
    train_script = SCRIPTS_DIR / "train_model.py"
    if not train_script.exists():
        sys.exit(f"ERROR: train_model.py not found at {train_script}")

    print(f"Running: {sys.executable} {train_script}")
    env = {**__import__("os").environ, "TF_CPP_MIN_LOG_LEVEL": "3", "TF_ENABLE_ONEDNN_OPTS": "0"}
    result = subprocess.run(
        [sys.executable, str(train_script)],
        cwd=str(ROOT),
        env=env,
    )
    if result.returncode != 0:
        sys.exit(f"ERROR: train_model.py exited with code {result.returncode}")

    print("\nTraining complete.")


# ──────────────────────────────────────────────────────────────────────────────
# STEP 2 -- DETERMINE NEXT VERSION
# ──────────────────────────────────────────────────────────────────────────────
def next_version() -> str:
    """Return next version string, e.g. 'v3'."""
    if not VERSION_FILE.exists():
        return "v1"
    history = json.loads(VERSION_FILE.read_text(encoding="utf-8"))
    if not history:
        return "v1"
    last = history[-1]["version"]          # e.g. "v2"
    n = int(last.lstrip("v")) + 1
    return f"v{n}"


# ──────────────────────────────────────────────────────────────────────────────
# STEP 3 -- ARCHIVE
# ──────────────────────────────────────────────────────────────────────────────
def archive_model(version: str) -> Path:
    """Copy driving_behavior_model.tflite --> driving_model_vN.tflite in models/."""
    if not TFLITE_SRC.exists():
        sys.exit(f"ERROR: TFLite source not found: {TFLITE_SRC}\n"
                 "Run without --skip-train first.")

    archive_path = MODELS_DIR / f"driving_model_{version}.tflite"
    shutil.copy2(TFLITE_SRC, archive_path)
    print(f"Archived model --> {archive_path.name}  ({archive_path.stat().st_size:,} bytes)")
    return archive_path


# ──────────────────────────────────────────────────────────────────────────────
# STEP 4 -- DEPLOY TO ANDROID ASSETS
# ──────────────────────────────────────────────────────────────────────────────
def deploy_to_android() -> None:
    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)

    for src, dest, label in [
        (TFLITE_SRC, TFLITE_DEST, "model.tflite"),
        (SCALER_SRC, SCALER_DEST, "scaler.json"),
        (LABELS_SRC, LABELS_DEST, "labels.json"),
    ]:
        if not src.exists():
            sys.exit(f"ERROR: Required artifact missing: {src}")
        shutil.copy2(src, dest)
        print(f"  Deployed  {label}  -->  {dest}  ({dest.stat().st_size:,} bytes)")


# ──────────────────────────────────────────────────────────────────────────────
# STEP 5 -- VALIDATE TFLITE CONTRACT
# ──────────────────────────────────────────────────────────────────────────────
def validate_tflite() -> dict:
    """Load the deployed model.tflite and check input/output shapes."""
    try:
        import os
        os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
        os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
        import warnings
        import tensorflow as tf
        warnings.filterwarnings("ignore", category=UserWarning, module="tensorflow")
    except ImportError:
        print("  WARNING: TensorFlow not installed -- skipping shape validation.")
        return {}

    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_DEST))
    interpreter.allocate_tensors()

    in_det  = interpreter.get_input_details()[0]
    out_det = interpreter.get_output_details()[0]
    in_shape  = tuple(in_det["shape"])
    out_shape = tuple(out_det["shape"])

    ok = True
    if in_shape != EXPECTED_INPUT_SHAPE:
        print(f"  FAIL  Input  shape: {in_shape}  expected {EXPECTED_INPUT_SHAPE}")
        ok = False
    else:
        print(f"  OK    Input  shape: {in_shape}")

    if out_shape != EXPECTED_OUTPUT_SHAPE:
        print(f"  FAIL  Output shape: {out_shape}  expected {EXPECTED_OUTPUT_SHAPE}")
        ok = False
    else:
        print(f"  OK    Output shape: {out_shape}")

    if not ok:
        sys.exit("ERROR: TFLite shape contract violation. Android WILL crash. Aborting deploy.")

    # Run a smoke inference
    import numpy as np
    dummy = np.zeros(EXPECTED_INPUT_SHAPE, dtype=np.float32)
    interpreter.set_tensor(in_det["index"], dummy)
    interpreter.invoke()
    probs = interpreter.get_tensor(out_det["index"])[0]
    print(f"  Smoke inference OK -- prob sum = {probs.sum():.6f}  (should be ~1.0)")

    return {
        "window_size":   int(in_shape[1]),
        "n_features":    int(in_shape[2]),
        "n_classes":     int(out_shape[1]),
        "tflite_bytes":  TFLITE_DEST.stat().st_size,
    }


# ──────────────────────────────────────────────────────────────────────────────
# STEP 6 -- UPDATE VERSION HISTORY
# ──────────────────────────────────────────────────────────────────────────────
def update_version_history(version: str, archive_path: Path, shape_info: dict) -> None:
    history = []
    if VERSION_FILE.exists():
        history = json.loads(VERSION_FILE.read_text(encoding="utf-8"))

    # Read scaler stats for provenance
    scaler_mean  = []
    scaler_scale = []
    if SCALER_SRC.exists():
        sc = json.loads(SCALER_SRC.read_text(encoding="utf-8"))
        scaler_mean  = sc.get("mean", [])
        scaler_scale = sc.get("scale", [])

    # Read label names
    label_names = []
    if LABELS_SRC.exists():
        lb = json.loads(LABELS_SRC.read_text(encoding="utf-8"))
        if isinstance(lb, list):
            label_names = lb
        else:  # dict {"0": "NORMAL", ...}
            label_names = [lb[str(i)] for i in range(len(lb))]

    entry = {
        "version":      version,
        "timestamp":    datetime.now(timezone.utc).isoformat(),
        "tflite_bytes": shape_info.get("tflite_bytes", TFLITE_SRC.stat().st_size if TFLITE_SRC.exists() else 0),
        "window_size":  shape_info.get("window_size", 50),
        "n_features":   shape_info.get("n_features", 8),
        "n_classes":    shape_info.get("n_classes", 4),
        "label_names":  label_names,
        "scaler_mean":  scaler_mean,
        "scaler_scale": scaler_scale,
        "archived_as":  archive_path.name,
    }
    history.append(entry)
    VERSION_FILE.write_text(
        json.dumps(history, indent=2, ensure_ascii=False),
        encoding="utf-8"
    )
    print(f"  version_history.json updated --> {len(history)} entries")


# ──────────────────────────────────────────────────────────────────────────────
# STEP 6b -- PATCH ModelHelper.kt VERSION CONSTANT
# ──────────────────────────────────────────────────────────────────────────────
def patch_model_helper_version(version: str) -> None:
    """Update MODEL_VERSION constant in ModelHelper.kt to match deployed version."""
    kt_file = (
        ROOT.parent
        / "android-app" / "app" / "src" / "main"
        / "java" / "com" / "teledrive" / "app" / "ml"
        / "ModelHelper.kt"
    )
    if not kt_file.exists():
        print(f"  WARNING: ModelHelper.kt not found at {kt_file} -- skipping patch")
        return

    text = kt_file.read_text(encoding="utf-8")
    import re
    new_text, n = re.subn(
        r'(const val MODEL_VERSION\s*=\s*)"v\d+"',
        rf'\1"{version}"',
        text,
    )
    if n == 0:
        print("  WARNING: MODEL_VERSION constant not found in ModelHelper.kt -- skipping patch")
        return

    kt_file.write_text(new_text, encoding="utf-8")
    print(f"  Patched ModelHelper.kt  MODEL_VERSION = \"{version}\"")


# ──────────────────────────────────────────────────────────────────────────────
# STEP 7 -- FINAL REPORT
# ──────────────────────────────────────────────────────────────────────────────
def final_report(version: str, skip_train: bool, shape_info: dict) -> None:
    banner("DEPLOY COMPLETE")
    print(f"  Version deployed : {version}")
    print(f"  Training skipped : {skip_train}")
    print(f"  Model shape      : input={EXPECTED_INPUT_SHAPE}  output={EXPECTED_OUTPUT_SHAPE}")
    print(f"  Android assets   : {ANDROID_ASSETS}")
    print()
    print("  Files deployed:")
    for f in [TFLITE_DEST, SCALER_DEST, LABELS_DEST]:
        tag = "OK" if f.exists() else "MISSING"
        print(f"    [{tag}]  {f.name}  ({f.stat().st_size:,} bytes)" if f.exists() else f"    [{tag}]  {f.name}")
    print()
    print("  Next step: Build the Android app and test on device.")
    banner("DONE")


# ──────────────────────────────────────────────────────────────────────────────
# MAIN
# ──────────────────────────────────────────────────────────────────────────────
def main() -> None:
    parser = argparse.ArgumentParser(
        description="TeleDrive ML Pipeline -- train --> version --> deploy to Android"
    )
    parser.add_argument(
        "--skip-train",
        action="store_true",
        help="Skip model training; re-deploy existing artifacts in models/"
    )
    args = parser.parse_args()

    banner("TELEDRIVE -- ML PIPELINE RUNNER")
    print(f"  Root        : {ROOT}")
    print(f"  Models dir  : {MODELS_DIR}")
    print(f"  Android dir : {ANDROID_ASSETS}")
    print(f"  Skip train  : {args.skip_train}")

    # ── Step 1: Train ──────────────────────────────────────────
    step(1, "Train model" + (" (SKIPPED)" if args.skip_train else ""))
    if not args.skip_train:
        run_training()
    else:
        print("  Skipping -- using existing artifacts in models/")
        if not TFLITE_SRC.exists():
            sys.exit(f"ERROR: No trained model found at {TFLITE_SRC}\n"
                     "Run without --skip-train to produce one.")

    # ── Step 2: Determine version ──────────────────────────────
    step(2, "Determine next version")
    version = next_version()
    print(f"  Next version : {version}")

    # ── Step 3: Archive ────────────────────────────────────────
    step(3, f"Archive model as driving_model_{version}.tflite")
    archive_path = archive_model(version)

    # ── Step 4: Deploy to Android ─────────────────────────────
    step(4, "Deploy artifacts to Android assets")
    deploy_to_android()

    # ── Step 5: Validate TFLite contract ──────────────────────
    step(5, "Validate TFLite input/output shape contract")
    shape_info = validate_tflite()

    # ── Step 6: Update version history ────────────────────────
    step(6, "Update version_history.json")
    update_version_history(version, archive_path, shape_info)

    # ── Step 6b: Patch ModelHelper.kt ─────────────────────────
    step(6, "Patch ModelHelper.kt  MODEL_VERSION")
    patch_model_helper_version(version)

    # ── Step 7: Final report ───────────────────────────────────
    step(7, "Final deployment report")
    final_report(version, args.skip_train, shape_info)


if __name__ == "__main__":
    main()
