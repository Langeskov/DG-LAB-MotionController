#!/usr/bin/env python3
"""Evaluate a rule-based "at rest" override on top of the model predictions.

Motivation: the model is a motion-mode classifier with only 3 stationary sessions;
on a desk it can confidently predict CAR/TRAIN even when the phone is not moving.
A cheap fix is to detect "phone is motionless" from IMU energy and force STATIONARY.

This script grids thresholds on real data and reports the trade-off, so the
thresholds we hard-code in Kotlin are justified.

Usage: python tools/rest_override_eval.py
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
TOOLS = os.path.join(ROOT, "tools")
ML_DIR = os.path.join(ROOT, "Motion-ml", "dataset")
LABEL_MAP = {"STATIONARY": 0, "WALKING": 1, "CYCLING": 2, "CAR": 3, "TRAIN": 4}
NAMES = ["STATIONARY", "WALKING", "CYCLING", "CAR", "TRAIN"]
DROP = ["yaw", "pitch", "roll"]
WINDOW, STRIDE, HZ, GAP = 150, 50, 50.0, 200.0
RAW = ["gps_speed", "acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z", "roll", "pitch", "yaw"]

DEFAULT_SESSIONS = [
    "STATIONARY_20261022_100043", "STATIONARY_20261022_103430",
    "WALKING_20261024_141507", "WALKING_20261024_143731",
    "CYCLING_20261022_191642", "CYCLING_20261023_083536",
    "CAR_20260913_112250", "CAR_20260913_085919",
    "TRAIN_20260926_202940", "TRAIN_20260926_085528",
]


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ML_DIR, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def diag_module():
    spec = importlib.util.spec_from_file_location("diag", os.path.join(TOOLS, "diagnose_inference.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def energy_of(raw_window: np.ndarray) -> tuple[float, float, float]:
    acc = raw_window[:, 1:4]
    gyro = raw_window[:, 4:7]
    acc_mag = np.sqrt((acc ** 2).sum(axis=1))
    gyro_mag = np.sqrt((gyro ** 2).sum(axis=1))
    return float(gyro_mag.mean()), float(acc_mag.std()), float(acc_mag.mean())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default="")
    ap.add_argument("--data", default=os.path.join(ROOT, "Motion-ml", "data", "raw"))
    ap.add_argument("--onnx", default=os.path.join(ROOT, "motion_encoder.onnx"))
    ap.add_argument("--scaler", default=os.path.join(ROOT, "app", "src", "main", "assets", "scaler.json"))
    args = ap.parse_args()

    diag = diag_module()
    loader = load_module("mm_session_loader", "session_loader.py")
    aligner_mod = load_module("mm_time_aligner", "time_aligner.py")
    builder_mod = load_module("mm_feature_builder", "feature_builder.py")
    preproc = load_module("mm_preprocess", "preprocess.py")

    import onnxruntime as ort
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    sc = json.load(open(args.scaler, encoding="utf-8"))
    mean = np.asarray(sc["mean"], dtype=np.float64)
    scale = np.asarray(sc["scale"], dtype=np.float64)

    all_sessions = loader.SessionDataset(args.data, verbose=False).sessions
    wanted = [x.strip() for x in args.sessions.split(",") if x.strip()] or DEFAULT_SESSIONS
    sessions = [s for s in all_sessions if s.session_id in wanted]

    aligner = aligner_mod.TimeAligner(max_gap_ms=2000.0)
    builder = builder_mod.FeatureBuilder()
    extractor = preproc.FeatureExtractor(rolling_window=10, fs=HZ)

    feats_all, raw_all, labels_all = [], [], []
    for s in sessions:
        aligned, _ = aligner.align(s.imu.copy(), s.gnss.copy())
        aligned = diag.resample_frame(aligned, HZ).reset_index(drop=True)
        seg = diag.segments_of(aligned["timestamp"].to_numpy(dtype=np.float64))
        base, _, _ = builder.build(aligned)
        raw = base.astype(np.float64)
        feats = diag.features_of(raw, extractor)
        label = LABEL_MAP[s.label.upper()]
        change = np.where(np.diff(seg) != 0)[0] + 1
        for a, b in zip(np.concatenate([[0], change]), np.concatenate([change, [len(seg)]])):
            for st in range(a, b - WINDOW + 1, STRIDE):
                feats_all.append(feats[st:st + WINDOW])
                raw_all.append(raw[st:st + WINDOW])
                labels_all.append(label)
    X = np.stack(feats_all)
    R = np.stack(raw_all)
    y = np.asarray(labels_all)
    pred = diag.predict(sess, mean, scale, X, iname)
    base_acc = float((pred == y).mean())
    print(f"windows={len(y)}  baseline acc={base_acc:.4f}")
    for i, n in enumerate(NAMES):
        m = y == i
        print(f"  {n:<10} recall={float((pred[m] == i).mean()):.3f} (n={int(m.sum())})")

    gyro = np.empty(len(R))
    acc_std = np.empty(len(R))
    for i in range(len(R)):
        gyro[i], acc_std[i], _ = energy_of(R[i])

    print("\n=== energy distribution by label (median / p10 / p90) ===")
    for i, n in enumerate(NAMES):
        m = y == i
        print(f"  {n:<10} gyro={np.median(gyro[m]):.4f} [{np.percentile(gyro[m], 10):.4f},{np.percentile(gyro[m], 90):.4f}]"
              f"  acc_std={np.median(acc_std[m]):.4f} [{np.percentile(acc_std[m], 10):.4f},{np.percentile(acc_std[m], 90):.4f}]")

    print("\n=== rule: override to STATIONARY only when model says CAR/CYCLING but phone is ultra-still ===")
    print("    (TRAIN is intentionally excluded: train cabins are as quiet as a desk)")
    best = None
    for g in (0.005, 0.008, 0.010, 0.015, 0.022, 0.03, 0.05):
        for a in (0.005, 0.008, 0.012, 0.02, 0.035, 0.06):
            for classes in ((3,), (2, 3)):
                ov = np.isin(pred, classes) & (gyro < g) & (acc_std < a)
                p2 = np.where(ov, 0, pred)
                acc = float((p2 == y).mean())
                # risk: how many overridden windows were actually CAR/CYCLING/TRAIN
                hit = y[ov]
                risky = int(np.isin(hit, [2, 3, 4]).sum())
                if best is None or acc > best[0]:
                    best = (acc, g, a, classes, int(ov.sum()), risky, np.bincount(hit, minlength=5).tolist())
                if acc >= base_acc - 0.002:
                    print(f"  g={g:.3f} a={a:.3f} classes={classes}: acc={acc:.4f} "
                          f"hits={int(ov.sum())} risky(true CAR/CYC/TRAIN)={risky}")
    print(f"\nbest: acc={best[0]:.4f} g={best[1]} a={best[2]} classes={best[3]} "
          f"hits={best[4]} risky={best[5]} true_dist={best[6]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
