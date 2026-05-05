"""
TeleDrive ML Pipeline - Feature Preprocessing Module (FIXED)
============================================================
This module contains preprocessing functions for braking detection fix.
Can be used for:
1. Training pipeline
2. Android integration (feature parity)
3. Real-time inference

CRITICAL: These functions must match EXACTLY what the model was trained on.
"""

import numpy as np
import pandas as pd
from typing import Dict, List, Tuple


class FeaturePreprocessor:
    """
    Feature preprocessing for driving behavior detection.
    
    Implements all braking detection fixes:
    - Advanced feature engineering
    - Proper handling of temporal features
    - NaN handling
    """
    
    def __init__(self, window_size: int = 50):
        self.window_size = window_size
        
        # Feature names
        self.base_features = ["ax", "ay", "az", "gx", "gy", "gz"]
        self.new_features = [
            "delta_speed",
            "neg_ax",
            "ax_rolling_mean",
            "ax_jerk",
            "ax_jerk_abs",
            "ax_speed_weighted",
            "ax_variance"
        ]
        self.all_features = self.base_features + self.new_features
        
        # Normalization parameters (loaded from scaler.json after training)
        self.feature_mean = None
        self.feature_scale = None
    
    def load_scaler(self, scaler_json_path: str):
        """Load normalization parameters from trained scaler"""
        import json
        with open(scaler_json_path, 'r') as f:
            scaler = json.load(f)
        self.feature_mean = np.array(scaler['mean'])
        self.feature_scale = np.array(scaler['scale'])
    
    def create_features_single_sample(self, 
                                     ax: float, ay: float, az: float,
                                     gx: float, gy: float, gz: float,
                                     speed: float,
                                     prev_speed: float = None,
                                     prev_ax: float = None) -> np.ndarray:
        """
        Create features for a single sample (Android real-time use).
        
        Args:
            ax, ay, az: Accelerometer readings
            gx, gy, gz: Gyroscope readings
            speed: Current speed (km/h)
            prev_speed: Previous speed (for delta calculation)
            prev_ax: Previous ax (for jerk calculation)
        
        Returns:
            Feature vector of shape (13,)
        
        NOTE: Rolling features cannot be computed from single sample.
              For real-time use, maintain a buffer of recent samples.
        """
        features = []
        
        # Base features
        features.extend([ax, ay, az, gx, gy, gz])
        
        # Delta speed
        if prev_speed is not None:
            delta_speed = speed - prev_speed
        else:
            delta_speed = 0.0
        features.append(delta_speed)
        
        # Negative ax emphasis
        neg_ax = -ax if ax < 0 else 0.0
        features.append(neg_ax)
        
        # Rolling mean (approximation: use current value for single sample)
        ax_rolling_mean = ax
        features.append(ax_rolling_mean)
        
        # Jerk
        if prev_ax is not None:
            ax_jerk = ax - prev_ax
        else:
            ax_jerk = 0.0
        features.append(ax_jerk)
        features.append(abs(ax_jerk))
        
        # Speed-weighted acceleration
        ax_speed_weighted = ax * (speed / 100.0)
        features.append(ax_speed_weighted)
        
        # Variance (approximation: 0 for single sample)
        ax_variance = 0.0
        features.append(ax_variance)
        
        return np.array(features, dtype=np.float32)
    
    def create_features_from_dataframe(self, df: pd.DataFrame) -> pd.DataFrame:
        """
        Create all features from a DataFrame.
        Use this for training and batch processing.
        
        Args:
            df: DataFrame with columns: ax, ay, az, gx, gy, gz, speed
        
        Returns:
            DataFrame with all feature columns added
        """
        df = df.copy()
        
        # Ensure sorted by time
        if 'timestamp' in df.columns:
            df = df.sort_values('timestamp').reset_index(drop=True)
        
        # 1. Delta speed
        df['delta_speed'] = df['speed'].diff().fillna(0)
        
        # 2. Negative ax emphasis
        df['neg_ax'] = np.where(df['ax'] < 0, -df['ax'], 0)
        
        # 3. Rolling mean of ax
        df['ax_rolling_mean'] = df['ax'].rolling(window=5, center=True).mean()
        df['ax_rolling_mean'] = df['ax_rolling_mean'].fillna(df['ax'])
        
        # 4. Jerk
        df['ax_jerk'] = df['ax'].diff().fillna(0)
        df['ax_jerk_abs'] = df['ax_jerk'].abs()
        
        # 5. Speed-weighted acceleration
        df['ax_speed_weighted'] = df['ax'] * (df['speed'] / 100.0)
        
        # 6. Acceleration variance
        df['ax_variance'] = df['ax'].rolling(window=10, center=True).std()
        df['ax_variance'] = df['ax_variance'].fillna(0)
        
        return df
    
    def create_features_from_window(self, window_data: np.ndarray) -> np.ndarray:
        """
        Create features from a window of raw sensor data.
        
        Args:
            window_data: Array of shape (window_size, 7) 
                        [ax, ay, az, gx, gy, gz, speed]
        
        Returns:
            Feature array of shape (window_size, 13)
        """
        # Convert to DataFrame for easier processing
        df = pd.DataFrame(window_data, columns=['ax', 'ay', 'az', 'gx', 'gy', 'gz', 'speed'])
        
        # Apply feature engineering
        df = self.create_features_from_dataframe(df)
        
        # Return only the feature columns
        return df[self.all_features].values.astype(np.float32)
    
    def normalize_features(self, features: np.ndarray) -> np.ndarray:
        """
        Normalize features using trained scaler parameters.
        
        Args:
            features: Raw features of shape (n_samples, n_features) or 
                     (window_size, n_features) or (n_windows, window_size, n_features)
        
        Returns:
            Normalized features (same shape as input)
        """
        if self.feature_mean is None or self.feature_scale is None:
            raise ValueError("Scaler not loaded. Call load_scaler() first.")
        
        original_shape = features.shape
        
        # Reshape to 2D if needed
        if len(original_shape) == 3:
            # (n_windows, window_size, n_features) -> (n_windows * window_size, n_features)
            features_2d = features.reshape(-1, original_shape[-1])
        elif len(original_shape) == 2:
            features_2d = features
        else:
            raise ValueError(f"Unexpected feature shape: {original_shape}")
        
        # Apply standardization: (x - mean) / scale
        normalized = (features_2d - self.feature_mean) / self.feature_scale
        
        # Reshape back to original
        if len(original_shape) == 3:
            normalized = normalized.reshape(original_shape)
        
        return normalized.astype(np.float32)
    
    def preprocess_for_inference(self, window_data: np.ndarray) -> np.ndarray:
        """
        Complete preprocessing pipeline for inference.
        
        Args:
            window_data: Raw window of shape (window_size, 7)
                        [ax, ay, az, gx, gy, gz, speed]
        
        Returns:
            Preprocessed window ready for model input: (1, window_size, 13)
        """
        # Create features
        features = self.create_features_from_window(window_data)
        
        # Normalize
        features_norm = self.normalize_features(features)
        
        # Add batch dimension
        return features_norm[np.newaxis, :, :]


class LabelAligner:
    """
    Handles label alignment correction for training data.
    """
    
    @staticmethod
    def shift_braking_labels(df: pd.DataFrame, shift_samples: int = 26) -> pd.DataFrame:
        """
        Shift braking labels forward to align with actual deceleration peak.
        
        IMPORTANT: Only use during training data preparation.
                  Do NOT use during inference/deployment.
        
        Args:
            df: DataFrame with 'label' column
            shift_samples: Number of samples to shift forward (default: 26)
        
        Returns:
            DataFrame with corrected labels
        """
        df = df.copy()
        df['label_original'] = df['label']  # Backup
        
        # Find all braking labels
        braking_indices = df[df['label'] == 2].index.tolist()
        
        # Clear original braking labels
        for idx in braking_indices:
            df.at[idx, 'label'] = 0  # Temporary NORMAL
        
        # Apply shifted labels
        shifted_count = 0
        for idx in braking_indices:
            new_idx = idx + shift_samples
            
            # Only shift if within bounds and target is NORMAL
            if new_idx < len(df):
                if df.at[new_idx, 'label'] == 0:
                    df.at[new_idx, 'label'] = 2
                    shifted_count += 1
        
        print(f"Label alignment: {shifted_count}/{len(braking_indices)} labels shifted +{shift_samples}")
        
        return df
    
    @staticmethod
    def verify_label_alignment(df: pd.DataFrame, window_size: int = 50):
        """
        Verify that braking labels align with actual deceleration.
        Use for quality checking training data.
        """
        braking_windows = []
        
        for idx in df[df['label'] == 2].index:
            # Get window around label
            start = max(0, idx - window_size // 2)
            end = min(len(df), idx + window_size // 2)
            window = df.iloc[start:end]
            
            if len(window) >= window_size // 2:
                mean_ax = window['ax'].mean()
                min_ax = window['ax'].min()
                braking_windows.append({
                    'mean_ax': mean_ax,
                    'min_ax': min_ax,
                    'label_idx': idx
                })
        
        if braking_windows:
            stats = pd.DataFrame(braking_windows)
            print("\nBraking label alignment check:")
            print(f"  Mean ax at braking labels: {stats['mean_ax'].mean():.3f}")
            print(f"  Min ax at braking labels:  {stats['min_ax'].mean():.3f}")
            print(f"  Expected: mean_ax < -1.0, min_ax < -2.0 for good alignment")
            
            if stats['mean_ax'].mean() < -1.0:
                print("  ✅ Labels appear well-aligned with deceleration")
            else:
                print("  ⚠️  Labels may need alignment correction")


# ============================================================================
# USAGE EXAMPLES
# ============================================================================

if __name__ == "__main__":
    print("=" * 80)
    print(" FEATURE PREPROCESSING MODULE - USAGE EXAMPLES")
    print("=" * 80)
    
    # Example 1: Batch preprocessing (training)
    print("\n📝 Example 1: Batch preprocessing for training")
    print("-" * 80)
    
    # Simulate sensor data
    np.random.seed(42)
    n_samples = 100
    sample_data = pd.DataFrame({
        'timestamp': np.arange(n_samples) * 20_000_000,  # 20ms intervals
        'ax': np.random.normal(-1.0, 0.5, n_samples),
        'ay': np.random.normal(0.0, 0.3, n_samples),
        'az': np.random.normal(9.8, 0.2, n_samples),
        'gx': np.random.normal(0.0, 0.1, n_samples),
        'gy': np.random.normal(0.0, 0.1, n_samples),
        'gz': np.random.normal(0.0, 0.1, n_samples),
        'speed': 50 + np.random.normal(0, 2, n_samples),
        'label': 0
    })
    
    preprocessor = FeaturePreprocessor()
    processed = preprocessor.create_features_from_dataframe(sample_data)
    
    print(f"Original features: {sample_data.shape[1]}")
    print(f"After engineering: {processed.shape[1]}")
    print(f"New feature columns: {preprocessor.new_features}")
    
    # Example 2: Single window preprocessing
    print("\n📝 Example 2: Window preprocessing for inference")
    print("-" * 80)
    
    window = processed[['ax', 'ay', 'az', 'gx', 'gy', 'gz', 'speed']].head(50).values
    print(f"Raw window shape: {window.shape}")
    
    features = preprocessor.create_features_from_window(window)
    print(f"Feature window shape: {features.shape}")
    print(f"Features per timestep: {features.shape[1]}")
    
    # Example 3: Label alignment
    print("\n📝 Example 3: Label alignment correction")
    print("-" * 80)
    
    # Simulate braking event
    sample_data_with_braking = sample_data.copy()
    sample_data_with_braking.loc[30:35, 'label'] = 2  # Mark as braking
    sample_data_with_braking.loc[30:50, 'ax'] = -3.0  # Actual deceleration
    
    print("Before alignment:")
    print(sample_data_with_braking[['label', 'ax']].iloc[25:55])
    
    aligned = LabelAligner.shift_braking_labels(sample_data_with_braking, shift_samples=5)
    
    print("\nAfter alignment (+5 samples):")
    print(aligned[['label', 'ax']].iloc[25:55])
    
    print("\n" + "=" * 80)
    print("✅ All examples completed successfully")
    print("=" * 80)

