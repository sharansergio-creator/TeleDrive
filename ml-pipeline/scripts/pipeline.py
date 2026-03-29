# ml-pipeline/scripts/pipeline.py

import subprocess


def run_step(command):
    print(f"\n🔹 Running: {command}")
    result = subprocess.run(command, shell=True)

    if result.returncode != 0:
        raise RuntimeError(f"❌ Failed at: {command}")


def main():
    steps = [
        "python scripts/clean_data.py",
        "python scripts/create_scaler.py",
        "python scripts/create_sequences.py",
        "python scripts/train_model.py",
        "python scripts/convert_to_tflite.py",
        "python scripts/export_scaler.py",
        "python scripts/export_label.py"
    ]

    for step in steps:
        run_step(step)

    print("\n✅ Pipeline completed successfully!")


if __name__ == "__main__":
    main()