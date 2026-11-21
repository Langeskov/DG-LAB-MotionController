#!/usr/bin/env python3
"""Inference diagnosis: model problem vs on-device streaming pipeline problem.

For the same real sessions compare 6 configurations:
  A  offline+gnss        training-style pipeline (features over whole segment, then windows)
  B  offline_no_gnss     A with gps_speed zeroed (indoor / no GNSS)
  C  stream250+gnss      current on-device: 250-frame history, window tail clamped (no lookahead)
  D  stream250_no_gnss   C without GNSS
  E  stream400_la+gnss   improved: 400-frame buffer + 110-frame lookahead (spectral interp ~offline)
  F  stream400_la_no_gnss E without GNSS

Usage:
  python tools/diagnose_inference.py
  python tools/diagnose_inference.py --sessions STATIONARY_20261022_100043,WALKING_20261024_141507
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys

import numpy as np
import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML_DIR = os.path.join(ROOT, "Motion-ml", "dataset")
LABEL_MAP = {"STATIONARY": 0, "WALKING": 1, "CYCLING": 2, "CAR": 3, "TRAIN": 4}
LABEL_NAMES = [k for k, _ in sorted(LABEL_MAP.items(), key=lambda kv: kv[1])]
DROP = ["yaw", "pitch", "roll"]
TARGET_HZ = 50.0
WINDOW = 150
STRIDE = 50
MAX_GAP_MS = 200.0
RAW_NAMES = ["gps_speed", "acc_x", "acc_y", "acc_z",
             "gyro_x", "gyro_y", "gyro_z", "roll", "pitch", "yaw"]

DEFAULT_SESSIONS = [
    "STATIONARY_20261022_100043", "STATIONARY_20261022_103430",
    "WALKING_20261024_141507", "WALKING_20261024_143731",
    "CYCLING_20261022_191642", "CYCLING_20261023_083536",
    "CAR_20260913_112250", "CAR_20260913_085919",
    "TRAIN_20260926_202940", "TRAIN_20260926_085528",
]


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ML_DIR, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _resample_uniform(sub: pd.DataFrame, target_hz: float, time_col: str) -> pd.DataFrame:
    t = sub[time_col].to_numpy(dtype=np.float64)
    n = int(np.floor((t[-1] - t[0]) / 1000.0 * target_hz)) + 1
    if n < 2:
        return sub
    grid = t[0] + np.arange(n) * (1000.0 / target_hz)
    out = {time_col: grid}
    for col in sub.columns:
        if col == time_col:
            continue
        s = sub[col]
        vals = s.to_numpy()
        if pd.api.types.is_numeric_dtype(s) and not pd.api.types.is_bool_dtype(s):
            out[col] = np.interp(grid, t, vals.astype(np.float64))
        else:
            nn = np.clip(np.searchsorted(t, grid, side="right") - 1, 0, len(t) - 1)
            out[col] = vals[nn]
    return pd.DataFrame(out)


def resample_frame(df: pd.DataFrame, target_hz: float = TARGET_HZ, time_col: str = "timestamp",
                   max_gap_ms: float = MAX_GAP_MS) -> pd.DataFrame:
    t = df[time_col].to_numpy(dtype=np.float64)
    breaks = np.where(np.diff(t) > max_gap_ms)[0] + 1
    bounds = np.concatenate([[0], breaks, [len(df)]])
    parts = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        if b - a < 2:
            parts.append(df.iloc[a:b])
            continue
        parts.append(_resample_uniform(df.iloc[a:b], target_hz, time_col))
    return pd.concat(parts, ignore_index=True) if parts else df


def segments_of(ts: np.ndarray) -> np.ndarray:
    seg = np.zeros(len(ts), dtype=np.int32)
    if len(ts) > 1:
        seg[1:] = np.cumsum(np.diff(ts) > MAX_GAP_MS)
    return seg


def predict(session, mean, scale, feats: np.ndarray, iname: str) -> np.ndarray:
    std = (feats - mean[None, :]) / scale[None, :]
    out = []
    for i in range(0, len(std), 512):
        out.append(session.run(None, {iname: std[i:i + 512].astype(np.float32)})[0].argmax(1))
    return np.concatenate(out) if out else np.empty(0, dtype=int)


def features_of(raw: np.ndarray, extractor) -> np.ndarray:
    df = pd.DataFrame(raw, columns=RAW_NAMES)
    df = extractor.transform(df)
    cols = [c for c in extractor.ALL_COLS if c not in DROP]
    return df[cols].to_numpy(dtype=np.float64)


def frame_labels_of(aligned, declared_label: str) -> np.ndarray:
    """帧级标签（缺失列时回退会话标签）；窗口标签按多数投票。"""
    if "label" in aligned.columns:
        mapped = np.array([LABEL_MAP.get(str(v).upper().strip(), -1) for v in aligned["label"]])
        if (mapped >= 0).any():
            return mapped
    return np.full(len(aligned), LABEL_MAP.get(declared_label.upper().strip(), -1), dtype=np.int64)


def _majority(labels_window: np.ndarray) -> int:
    labels_window = labels_window[labels_window >= 0]
    return int(np.bincount(labels_window).argmax()) if len(labels_window) else -1


def offline_windows(raw: np.ndarray, seg: np.ndarray, extractor,
                    labels: np.ndarray) -> tuple[list[np.ndarray], list[int]]:
    feats = features_of(raw, extractor)
    wins, win_labels = [], []
    change = np.where(np.diff(seg) != 0)[0] + 1
    for a, b in zip(np.concatenate([[0], change]), np.concatenate([change, [len(seg)]])):
        for s in range(a, b - WINDOW + 1, STRIDE):
            wins.append(feats[s:s + WINDOW])
            win_labels.append(_majority(labels[s:s + WINDOW]))
    return wins, win_labels


def stream_windows(raw: np.ndarray, seg: np.ndarray, extractor, labels: np.ndarray,
                   max_buffer: int, lookahead: int) -> tuple[list[np.ndarray], list[int]]:
    wins, win_labels = [], []
    change = np.where(np.diff(seg) != 0)[0] + 1
    for a, b in zip(np.concatenate([[0], change]), np.concatenate([change, [len(seg)]])):
        buf: list[np.ndarray] = []
        since = 0
        for i in range(a, b):
            buf.append(raw[i])
            since += 1
            if len(buf) > max_buffer:
                buf.pop(0)
            if since < STRIDE or len(buf) < WINDOW + lookahead:
                continue
            since = 0
            feats = features_of(np.stack(buf), extractor)
            end = len(feats) - lookahead
            wins.append(feats[end - WINDOW:end])
            buf_start = i - len(buf) + 1
            win_labels.append(_majority(labels[buf_start + end - WINDOW:buf_start + end]))
    return wins, win_labels


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default="")
    ap.add_argument("--data", default=os.path.join(ROOT, "Motion-ml", "data", "raw"))
    ap.add_argument("--onnx", default=os.path.join(ROOT, "motion_encoder.onnx"))
    ap.add_argument("--scaler", default=os.path.join(ROOT, "app", "src", "main", "assets", "scaler.json"))
    ap.add_argument("--no-qa-gate", action="store_true", help="跳过入库 QA 门禁（默认 FAIL 会话拒评）")
    ap.add_argument("--allow-no-gnss", default="",
                    help="逗号分隔的会话前缀：故意无 GNSS 的会话（地铁/隧道）豁免覆盖率门禁")
    args = ap.parse_args()

    loader = load_module("mm_session_loader", "session_loader.py")
    aligner_mod = load_module("mm_time_aligner", "time_aligner.py")
    builder_mod = load_module("mm_feature_builder", "feature_builder.py")
    preproc = load_module("mm_preprocess", "preprocess.py")
    qa_mod = load_module("mm_session_qa", "session_qa.py")
    allow_no_gnss = {s.strip() for s in args.allow_no_gnss.split(",") if s.strip()}

    import onnxruntime as ort
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    sc = json.load(open(args.scaler, encoding="utf-8"))
    mean = np.asarray(sc["mean"], dtype=np.float64)
    scale = np.asarray(sc["scale"], dtype=np.float64)

    all_sessions = loader.SessionDataset(args.data, verbose=False).sessions
    wanted = [s.strip() for s in args.sessions.split(",") if s.strip()] or DEFAULT_SESSIONS
    sessions = [s for s in all_sessions if s.session_id in wanted]
    print(f"sessions={len(sessions)}: {[s.session_id for s in sessions]}")

    aligner = aligner_mod.TimeAligner(max_gap_ms=2000.0)
    builder = builder_mod.FeatureBuilder()
    extractor = preproc.FeatureExtractor(rolling_window=10, fs=TARGET_HZ)

    configs = [
        ("A_offline", False, 0),
        ("B_offline_no_gnss", True, 0),
        ("C_stream250", False, 1),
        ("D_stream250_no_gnss", True, 1),
        ("E_stream400_la110", False, 2),
        ("F_stream400_la110_no_gnss", True, 2),
    ]
    totals = {name: [0, 0] for name, _, _ in configs}
    confusions = {name: np.zeros((5, 5), dtype=int) for name, _, _ in configs}

    for s in sessions:
        aligned_raw, _ = aligner.align(s.imu.copy(), s.gnss.copy())
        # 与训练侧 MotionWindowDataset 同口径：在重采样后的 50Hz 流上做 QA 门禁
        aligned = resample_frame(aligned_raw, TARGET_HZ).reset_index(drop=True)
        qa_result = qa_mod.evaluate_aligned(
            s.session_id, s.label, aligned,
            allow_no_gnss=s.session_id in allow_no_gnss,
        )
        if qa_result.verdict == "FAIL" and not args.no_qa_gate:
            print(f"\n== {s.session_id}: QA FAIL，跳过（{'；'.join(qa_result.reasons)}）==")
            continue
        if qa_result.verdict == "WARN":
            print(f"\n== {s.session_id}: QA WARN（{'；'.join(qa_result.reasons)}）==")

        seg = segments_of(aligned["timestamp"].to_numpy(dtype=np.float64))
        labels = frame_labels_of(aligned, s.label)
        base, _, _ = builder.build(aligned)
        raw = base.astype(np.float64)
        raw_no_gnss = raw.copy()
        raw_no_gnss[:, 0] = 0.0
        label = LABEL_MAP[s.label.upper()]
        print(f"\n== {s.session_id} label={s.label} rows={len(raw)} qa={qa_result.verdict} ==")
        for name, no_gnss, mode in configs:
            data = raw_no_gnss if no_gnss else raw
            if mode == 0:
                wins, win_labels = offline_windows(data, seg, extractor, labels)
            elif mode == 1:
                wins, win_labels = stream_windows(data, seg, extractor, labels,
                                                  max_buffer=250, lookahead=0)
            else:
                wins, win_labels = stream_windows(data, seg, extractor, labels,
                                                  max_buffer=400, lookahead=110)
            if not wins:
                continue
            wl = np.asarray(win_labels, dtype=np.int64)
            valid = wl >= 0
            wins = [w for w, keep in zip(wins, valid) if keep]
            wl = wl[valid]
            if not wins:
                continue
            pred = predict(sess, mean, scale, np.stack(wins), iname)
            totals[name][0] += int((pred == wl).sum())
            totals[name][1] += len(pred)
            for t, p in zip(wl, pred):
                confusions[name][int(t), int(p)] += 1
            print(f"  {name:<28} windows={len(pred):<6} acc={(pred == wl).mean():.3f} "
                  f"dist={np.bincount(pred, minlength=5).tolist()}")

    print("\n=== summary accuracy / windows ===")
    for name, _, _ in configs:
        c, n = totals[name]
        if n:
            print(f"  {name:<30} {c / n:.4f}  ({c}/{n})")
        else:
            print(f"  {name:<30} n/a")

    print("\n=== confusion (rows=true, cols=pred) ===")
    for name in ("A_offline", "C_stream250", "E_stream400_la110"):
        print(f"-- {name}")
        cm = confusions[name]
        print("             " + "".join(f"{lbl:>11}" for lbl in LABEL_NAMES))
        for i, lbl in enumerate(LABEL_NAMES):
            print(f"   {lbl:<10}" + "".join(f"{cm[i, j]:>11d}" for j in range(5)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
