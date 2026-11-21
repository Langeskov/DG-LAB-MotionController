#!/usr/bin/env python3
"""生成端侧特征管线的 golden vector（Python/numpy/pandas 参考实现 → JSON）。

用法（用带 numpy+pandas 的 Python 运行）:
    python tools/gen_golden.py
产出:
    app/src/test/resources/golden_features.json      合成 golden（特征 + 重采样）
    app/src/test/resources/golden_real_session.json  真实会话切片 golden（重采样 + 特征 + 分段/窗口数）
    app/src/test/resources/golden_gnss_dropout.json  GNSS 掉点（速度阶跃归零）golden

内容:
  golden_features.json:
   1. resample: 不规则时间戳（含 >200ms 停顿）的 10 维原始数据 → 50Hz 重采样参考结果
   2. features: 规则 50Hz 的 10 维原始数据 → 24 维特征参考结果（已丢弃 roll/pitch/yaw 原始列）
   3. featureNames: 24 维特征名（校验顺序）
  golden_real_session.json（2026-11-05 补，B1）:
   取真实录制会话的一段（含真实停顿/时间戳抖动/重复时间戳修复结果），原始 10 维按端上
   约定（gps_speed km/h，无定位时为 0）→ 参考重采样 → 参考 24 维特征，并给出段长/窗口数。
  golden_gnss_dropout.json（2026-11-05 补，B2）:
   300 帧 50Hz，第 150 帧 gps_speed 由 50.4 km/h 阶跃到 0（模拟 GNSS 过期归零）→ 参考 24 维特征。

参考实现直接 import Motion-ml/dataset/preprocess.py（只依赖 numpy/pandas），
重采样部分按 Motion-ml/dataset/session_dataset.py 的 resample_frame 逐行复刻；
真实会话切片用 Motion-ml/dataset/{session_loader,time_aligner}.py 对齐（与 tools/validate_e2e.py 同口径）。
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PREPROCESS = os.path.join(ROOT, "Motion-ml", "dataset", "preprocess.py")
ML_DATASET = os.path.join(ROOT, "Motion-ml", "dataset")
DATA_RAW = os.path.join(ROOT, "Motion-ml", "data", "raw")
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "golden_features.json")
OUT_REAL = os.path.join(ROOT, "app", "src", "test", "resources", "golden_real_session.json")
OUT_DROPOUT = os.path.join(ROOT, "app", "src", "test", "resources", "golden_gnss_dropout.json")

# 真实会话切片（2026-11-05 选定）：WALKING_20260809_180043，
# 166Hz 老录制器（18% 行时间戳重复、已按行序展开），切片含一次 799ms 真实停顿。
REAL_SESSION_ID = "WALKING_20260809_180043"
REAL_SLICE_START_MS = 1786269403840.0
REAL_SLICE_DURATION_MS = 12000.0

RAW_COLS = [
    "gps_speed",
    "acc_x", "acc_y", "acc_z",
    "gyro_x", "gyro_y", "gyro_z",
    "roll", "pitch", "yaw",
]
DROP_FEATURES = ["yaw", "pitch", "roll"]


def load_preprocess():
    spec = importlib.util.spec_from_file_location("mm_preprocess", PREPROCESS)
    if spec is None or spec.loader is None:
        raise SystemExit(f"无法加载 {PREPROCESS}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def reference_resample(df: pd.DataFrame, target_hz: float, max_gap_ms: float = 200.0) -> pd.DataFrame:
    """与 Motion-ml/dataset/session_dataset.py::resample_frame 完全一致。"""
    t = df["timestamp"].to_numpy(dtype=np.float64)
    breaks = np.where(np.diff(t) > max_gap_ms)[0] + 1
    bounds = np.concatenate([[0], breaks, [len(df)]])
    parts = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        if b - a < 2:
            if b > a:
                parts.append(df.iloc[a:b])
            continue
        sub = df.iloc[a:b]
        st = sub["timestamp"].to_numpy(dtype=np.float64)
        dur_s = (st[-1] - st[0]) / 1000.0
        n = int(np.floor(dur_s * target_hz)) + 1
        if n < 2:
            parts.append(sub)
            continue
        grid = st[0] + np.arange(n) * (1000.0 / target_hz)
        out = {"timestamp": grid}
        for col in sub.columns:
            if col == "timestamp":
                continue
            out[col] = np.interp(grid, st, sub[col].to_numpy(dtype=np.float64))
        parts.append(pd.DataFrame(out))
    return pd.concat(parts, ignore_index=True)


def load_ml_module(name: str, filename: str):
    """加载 Motion-ml/dataset 下只依赖 numpy/pandas 的模块（只读，不修改训练工程）。"""
    path = os.path.join(ML_DATASET, filename)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"无法加载 {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_real_session_slice() -> tuple[pd.DataFrame, str, str]:
    """按端上约定重建真实会话切片的前置数据。

    与 `tools/validate_e2e.py` 同一口径：IMU/GNSS 清洗 → TimeAligner（2s 过期）→
    **gps_speed 由 m/s 转 km/h，无定位帧置 0**（端上 GnssSpeedProvider 的输出语义），
    再按时间范围切出切片。返回 (切片 raw 表, session_id, label)。
    """
    loader = load_ml_module("mm_session_loader", "session_loader.py")
    aligner_mod = load_ml_module("mm_time_aligner", "time_aligner.py")

    infos = loader.scan_sessions(DATA_RAW)
    info = next((i for i in infos if i.prefix == REAL_SESSION_ID), None)
    if info is None:
        raise SystemExit(f"找不到真实会话 {REAL_SESSION_ID}")
    session = loader.load_session(info, repair_timestamps=True)
    aligned, _ = aligner_mod.TimeAligner(max_gap_ms=2000.0).align(
        session.imu.copy(), session.gnss.copy()
    )

    raw = pd.DataFrame({"timestamp": aligned["timestamp"].to_numpy(dtype=np.float64)})
    speed_kmh = np.nan_to_num(aligned["gps_speed"].to_numpy(dtype=np.float64), nan=0.0) * 3.6
    raw["gps_speed"] = np.where(aligned["gps_valid"].to_numpy(dtype=bool), speed_kmh, 0.0)
    for col in ["acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z", "roll", "pitch", "yaw"]:
        raw[col] = aligned[col].to_numpy(dtype=np.float64)

    t = raw["timestamp"].to_numpy(dtype=np.float64)
    a = int(np.searchsorted(t, REAL_SLICE_START_MS))
    b = int(np.searchsorted(t, REAL_SLICE_START_MS + REAL_SLICE_DURATION_MS))
    if b - a < 200:
        raise SystemExit(f"真实会话切片太短: {b - a} 行")
    slice_df = raw.iloc[a:b].reset_index(drop=True)
    return slice_df, session.session_id, session.label


def segment_lengths(timestamps: np.ndarray, max_gap_ms: float = 200.0) -> list[int]:
    """按 >200ms 停顿切段，返回每段行数（与 session_dataset.py 的分段语义一致）。"""
    t = np.asarray(timestamps, dtype=np.float64)
    if len(t) == 0:
        return []
    breaks = np.where(np.diff(t) > max_gap_ms)[0] + 1
    bounds = np.concatenate([[0], breaks, [len(t)]])
    return [int(y - x) for x, y in zip(bounds[:-1], bounds[1:])]


def windows_per_segment(lengths: list[int], window: int = 150, stride: int = 50) -> list[int]:
    """训练侧 `_sliding_windows_by_segment` 的窗口数：每段从 0 开始按 stride 滑窗。"""
    return [0 if n < window else (n - window) // stride + 1 for n in lengths]


def make_gnss_dropout(n: int = 300, step_at: int = 150, before_kmh: float = 50.4) -> pd.DataFrame:
    """50Hz 常态行驶，第 [step_at] 帧 gps_speed 阶跃归零（模拟 GNSS 过期 → 速度 0）。"""
    t = np.arange(n, dtype=np.float64) * 20.0
    gps = np.full(n, before_kmh, dtype=np.float64)
    gps[step_at:] = 0.0
    rot = np.arange(n, dtype=np.float64)
    return pd.DataFrame({
        "timestamp": t,
        "gps_speed": gps,
        # 轻微但确定性的 IMU 变化，保证频域特征非平凡
        "acc_x": 0.35 * np.sin(rot / 7.0),
        "acc_y": 0.25 * np.sin(rot / 11.0 + 1.0),
        "acc_z": 9.81 + 0.45 * np.sin(rot / 5.0 + 2.0),
        "gyro_x": 0.02 * np.sin(rot / 9.0),
        "gyro_y": 0.03 * np.sin(rot / 13.0 + 0.5),
        "gyro_z": 0.01 * np.sin(rot / 17.0),
        "roll": 4.0 + 2.0 * np.sin(rot / 40.0),
        "pitch": -2.0 + 1.5 * np.cos(rot / 55.0),
        "yaw": 1.0 * np.sin(rot / 90.0),
    })


def make_raw(n: int, seed: int) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    t = np.arange(n, dtype=np.float64) * 20.0  # 50Hz
    acc = rng.normal(0.0, 2.5, size=(n, 3))
    gyro = rng.normal(0.0, 0.4, size=(n, 3))
    # 平滑姿态 + 速度
    roll = 15.0 * np.sin(np.arange(n) / 40.0) + rng.normal(0, 0.3, n)
    pitch = -10.0 * np.cos(np.arange(n) / 55.0) + rng.normal(0, 0.3, n)
    yaw = 3.0 * np.sin(np.arange(n) / 90.0)
    speed = np.maximum(0.0, 20.0 + 10.0 * np.sin(np.arange(n) / 70.0))
    return pd.DataFrame({
        "timestamp": t,
        "gps_speed": speed,
        "acc_x": acc[:, 0], "acc_y": acc[:, 1], "acc_z": acc[:, 2],
        "gyro_x": gyro[:, 0], "gyro_y": gyro[:, 1], "gyro_z": gyro[:, 2],
        "roll": roll, "pitch": pitch, "yaw": yaw,
    })


def make_irregular() -> pd.DataFrame:
    """含 500ms 停顿的不规则采样（两段，段内单调、段间跳变），用于重采样对齐测试。

    注意：时间戳必须整体单调递增 —— 真实采集的时间轴连续，停顿只是帧间隔变大，
    不会让时间戳回退。早期版本错误地让第二段从段首重新计时，导致非单调，
    会让 numpy 的 np.interp 产生未定义行为。
    """
    rng = np.random.default_rng(7)

    def segment(start: float, n: int = 80) -> list[float]:
        t = [start]
        for _ in range(n - 1):
            step = 8.0 if rng.random() < 0.5 else 33.0
            t.append(t[-1] + step)
        return t

    t1 = segment(0.0)
    t2 = segment(t1[-1] + 500.0)  # 段间 500ms 停顿，时间轴继续往前
    t = np.array(t1 + t2, dtype=np.float64)
    n = len(t)
    acc = rng.normal(0.0, 1.0, size=(n, 3))
    gyro = rng.normal(0.0, 0.1, size=(n, 3))
    return pd.DataFrame({
        "timestamp": t,
        "gps_speed": np.linspace(0.0, 8.0, n),
        "acc_x": acc[:, 0], "acc_y": acc[:, 1], "acc_z": acc[:, 2],
        "gyro_x": gyro[:, 0], "gyro_y": gyro[:, 1], "gyro_z": gyro[:, 2],
        "roll": np.sin(t / 500.0) * 10.0,
        "pitch": np.cos(t / 600.0) * 5.0,
        "yaw": np.sin(t / 700.0) * 2.0,
    })


def to_plain(df: pd.DataFrame, cols: list[str]) -> list[list[float]]:
    arr = df[cols].to_numpy(dtype=np.float64)
    return [[round(float(v), 6) for v in row] for row in arr]


def main() -> int:
    mm = load_preprocess()
    extractor = mm.FeatureExtractor(rolling_window=10, fs=50.0)
    feature_cols = [c for c in extractor.ALL_COLS if c not in DROP_FEATURES]

    # 1) 规则 50Hz 特征参考
    raw_df = make_raw(260, seed=42)
    feats_df = extractor.transform(raw_df.copy())
    features = feats_df[feature_cols].to_numpy(dtype=np.float64)

    # 2) 重采样参考
    irr = make_irregular()
    resampled = reference_resample(irr, target_hz=50.0)
    resampled_cols = ["timestamp"] + RAW_COLS

    payload = {
        "featureNames": feature_cols,
        "features": {
            "raw": to_plain(raw_df, RAW_COLS),
            "expected": to_plain(pd.DataFrame(features, columns=feature_cols), list(feature_cols)),
        },
        "resample": {
            "raw": to_plain(irr, resampled_cols),
            "expected": to_plain(resampled, resampled_cols),
        },
    }

    # 3) 真实会话切片（B1）：真实采样抖动/重复时间戳修复结果/真实停顿 799ms
    real_raw, real_sid, real_label = load_real_session_slice()
    real_resampled = reference_resample(real_raw, target_hz=50.0)
    real_feature_df = extractor.transform(real_resampled[RAW_COLS].copy())
    real_segments = segment_lengths(real_resampled["timestamp"].to_numpy(dtype=np.float64))
    real_windows = windows_per_segment(real_segments)
    real_payload = {
        "session": {
            "id": real_sid,
            "label": real_label,
            "source": f"Motion-ml/data/raw/{real_sid}_imu.csv + {real_sid}_gnss.csv",
            "sliceStartMs": REAL_SLICE_START_MS,
            "sliceDurationMs": REAL_SLICE_DURATION_MS,
            "units": "gps_speed=km/h；无 2s 内定位的帧已置 0（端上 GnssSpeedProvider 输出语义）",
        },
        "resample": {
            "raw": to_plain(real_raw, resampled_cols),
            "expected": to_plain(real_resampled, resampled_cols),
        },
        "features": {
            "raw": to_plain(real_resampled, RAW_COLS),
            "expected": to_plain(
                pd.DataFrame(real_feature_df[feature_cols].to_numpy(dtype=np.float64), columns=feature_cols),
                list(feature_cols),
            ),
        },
        "resampledSegmentLengths": real_segments,
        "windowsPerSegment": real_windows,
    }

    # 4) GNSS 掉点 golden（B2）：第 150 帧速度阶跃 50.4 → 0 km/h
    dropout = make_gnss_dropout()
    dropout_feature_df = extractor.transform(dropout[RAW_COLS].copy())
    dropout_payload = {
        "meta": {
            "frames": int(len(dropout)),
            "stepAtFrame": 150,
            "beforeKmh": 50.4,
            "afterKmh": 0.0,
            "fs": 50.0,
        },
        "raw": to_plain(dropout, RAW_COLS),
        "expected": to_plain(
            pd.DataFrame(dropout_feature_df[feature_cols].to_numpy(dtype=np.float64), columns=feature_cols),
            list(feature_cols),
        ),
    }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f)
        f.write("\n")
    print(f"[ok] 写入 {OUT}")
    print(f"     features: {features.shape}, resample: {resampled.shape}")
    print(f"     featureNames: {feature_cols}")

    with open(OUT_REAL, "w", encoding="utf-8") as f:
        json.dump(real_payload, f)
        f.write("\n")
    print(f"[ok] 写入 {OUT_REAL}")
    print(
        f"     session={real_sid} label={real_label} slice_raw={real_raw.shape} "
        f"resampled={real_resampled.shape} segments={real_segments} windows={real_windows}"
    )

    with open(OUT_DROPOUT, "w", encoding="utf-8") as f:
        json.dump(dropout_payload, f)
        f.write("\n")
    print(f"[ok] 写入 {OUT_DROPOUT}")
    print(f"     raw={dropout.shape}, expected={dropout_feature_df.shape}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
