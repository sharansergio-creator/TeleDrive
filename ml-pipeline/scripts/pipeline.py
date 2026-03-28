from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = ROOT / "scripts"

PIPELINE_STEPS = [
    SCRIPTS_DIR / "clean_data.py",
    SCRIPTS_DIR / "create_sequences.py",
    SCRIPTS_DIR / "train_model.py",
    SCRIPTS_DIR / "convert_to_tflite.py",
]


def run_step(script_path: Path) -> None:
    print(f"\n=== Running {script_path.name} ===")
    subprocess.run([sys.executable, str(script_path)], check=True, cwd=ROOT)


def main() -> None:
    for step in PIPELINE_STEPS:
        if not step.exists():
            raise FileNotFoundError(f"Missing pipeline step: {step}")
        run_step(step)

    print("\nPipeline completed successfully.")


if __name__ == "__main__":
    main()
