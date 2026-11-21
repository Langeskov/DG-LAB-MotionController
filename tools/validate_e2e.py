#!/usr/bin/env python3
"""端到端验证：真实 CSV → 训练同款预处理 → scaler.json → ONNX → 分类准确率。

用途：确认 `motion_encoder.onnx` + `scaler.json` + 特征管线（与端上实现逐位对齐）
在真实数据上工作正常（不是 ~20% 的随机水平）。

依赖：numpy / pandas / onnxruntime（不需要 torch / sklearn）。
直接从 Motion-ml 导入只依赖 numpy/pandas 的模块（session_loader / time_aligner /
preprocess / feature_builder），重采样部分复刻 session_dataset.py（避免引入 torch）。

用法:
    python tools/validate_e2e.py --data Motion-ml/data/raw \
        --onnx motion_encoder.onnx --scaler app/src/main/assets/scaler.json \
        --sessions STATIONARY_20261022_100043,WALKING_20261024_141507,CYCLING_20261022_191642,CAR_20260913_112250,TRAIN_20260926_202940
    # 不给 --sessions 时每类取第一个（按文件名排序）
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
DROP_FEATURES = ["yaw", "pitch", "roll"]
TARGET_HZ = 50.0
WINDOW = 150
STRIDE = 50
MAX_GAP_MS = 200.0


def load_module(name: str, filename: str):
    path = os.path.join(ML_DIR, filename)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"无法加载 {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ── 复刻 session_dataset.py 的重采样（按段、np.interp 语义） ──────────────
def _resample_uniform(sub: pd.DataFrame, target_hz: float, time_col: str) -> pd.DataFrame:
    t = sub[time_col].to_numpy(dtype=np.float64)
    dur_s = (t[-1] - t[0]) / 1000.0
    n = int(np.floor(dur_s * target_hz)) + 1
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
            nn_idx = np.clip(np.searchsorted(t, grid, side="right") - 1, 0, len(t) - 1)
            out[col] = vals[nn_idx]
    return pd.DataFrame(out)


def resample_frame(df: pd.DataFrame, target_hz: float = TARGET_HZ, time_col: str = "timestamp",
                   max_gap_ms: float = MAX_GAP_MS) -> pd.DataFrame:
    if len(df) < 2:
        return df
    t = df[time_col].to_numpy(dtype=np.float64)
    breaks = np.where(np.diff(t) > max_gap_ms)[0] + 1
    bounds = np.concatenate([[0], breaks, [len(df)]])
    parts = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        if b - a < 2:
            if b > a:
                parts.append(df.iloc[a:b])
            continue
        parts.append(_resample_uniform(df.iloc[a:b], target_hz, time_col))
    return pd.concat(parts, ignore_index=True) if parts else df


def assign_segments(timestamps, max_gap_ms: float = MAX_GAP_MS) -> np.ndarray:
    t = np.asarray(timestamps, dtype=np.float64)
    seg = np.zeros(len(t), dtype=np.int32)
    if len(t) > 1:
        seg[1:] = np.cumsum(np.diff(t) > max_gap_ms)
    return seg


def windows_per_segment(features: np.ndarray, segments: np.ndarray) -> np.ndarray:
    change = np.where(np.diff(segments) != 0)[0] + 1
    bounds = np.concatenate([[0], change, [len(segments)]])
    out = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        if b - a < WINDOW:
            continue
        for start in range(a, b - WINDOW + 1, STRIDE):
            out.append(features[start:start + WINDOW])
    return np.stack(out).astype(np.float32) if out else np.empty((0, WINDOW, features.shape[1]), np.float32)


def window_labels_per_segment(labels: np.ndarray, segments: np.ndarray) -> np.ndarray:
    """窗口标签 = 窗口内帧级标签的多数投票（与训练侧 session_dataset._sliding_windows 一致）。"""
    change = np.where(np.diff(segments) != 0)[0] + 1
    bounds = np.concatenate([[0], change, [len(segments)]])
    out = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        if b - a < WINDOW:
            continue
        for start in range(a, b - WINDOW + 1, STRIDE):
            window = labels[start:start + WINDOW]
            window = window[window >= 0]
            out.append(int(np.bincount(window).argmax()) if len(window) else -1)
    return np.asarray(out, dtype=np.int64)


def frame_labels_of(aligned: pd.DataFrame, declared_label: str) -> np.ndarray:
    """从对齐数据取帧级标签（缺失列时回退会话标签）。"""
    if "label" in aligned.columns:
        mapped = np.array([LABEL_MAP.get(str(v).upper().strip(), -1) for v in aligned["label"]])
        if (mapped >= 0).any():
            return mapped
    fallback = LABEL_MAP.get(declared_label.upper().strip(), -1)
    return np.full(len(aligned), fallback, dtype=np.int64)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(ROOT, "Motion-ml", "data", "raw"))
    ap.add_argument("--onnx", default=os.path.join(ROOT, "motion_encoder.onnx"))
    ap.add_argument("--scaler", default=os.path.join(ROOT, "app", "src", "main", "assets", "scaler.json"))
    ap.add_argument("--sessions", default="", help="逗号分隔的 session 前缀；空=每类取第一个")
    ap.add_argument("--no-qa-gate", action="store_true", help="跳过入库 QA 门禁（默认 FAIL 会话拒评）")
    ap.add_argument("--allow-no-gnss", default="",
                    help="逗号分隔的会话前缀：故意无 GNSS 的会话（地铁/隧道）豁免覆盖率门禁")
    args = ap.parse_args()

    loader = load_module("mm_session_loader", "session_loader.py")
    aligner_mod = load_module("mm_time_aligner", "time_aligner.py")
    builder_mod = load_module("mm_feature_builder", "feature_builder.py")
    preprocess_mod = load_module("mm_preprocess", "preprocess.py")
    qa_mod = load_module("mm_session_qa", "session_qa.py")
    allow_no_gnss = {s.strip() for s in args.allow_no_gnss.split(",") if s.strip()}

    scaler = json.load(open(args.scaler, encoding="utf-8"))
    if not scaler.get("valid"):
        print("⚠ scaler.json 仍是占位（valid=false），准确率无意义", file=sys.stderr)
    mean = np.asarray(scaler["mean"], dtype=np.float64)
    scale = np.asarray(scaler["scale"], dtype=np.float64)
    scale = np.where(scale == 0.0, 1.0, scale)

    sessions = loader.SessionDataset(args.data, verbose=True).sessions
    if args.sessions.strip():
        wanted = [s.strip() for s in args.sessions.split(",") if s.strip()]
        sessions = [s for s in sessions if s.session_id in wanted]
        if not sessions:
            print(f"未找到指定 session: {wanted}", file=sys.stderr)
            return 2
    else:
        by_label = {}
        for s in sessions:
            by_label.setdefault(s.label.upper(), []).append(s)
        sessions = [by_label[k][0] for k in LABEL_NAMES if k in by_label]

    import onnxruntime as ort
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    print(f"ONNX input={iname} shape={sess.get_inputs()[0].shape}")

    aligner = aligner_mod.TimeAligner(max_gap_ms=2000.0)
    builder = builder_mod.FeatureBuilder()
    extractor = preprocess_mod.FeatureExtractor(rolling_window=10, fs=TARGET_HZ)
    feature_cols = [c for c in extractor.ALL_COLS if c not in DROP_FEATURES]

    y_true, y_pred = [], []
    for s in sessions:
        aligned, _ = aligner.align(s.imu.copy(), s.gnss.copy())
        # 与训练侧 MotionWindowDataset 同口径：在重采样后的 50Hz 流上做 QA 门禁
        aligned = resample_frame(aligned, TARGET_HZ)
        qa_result = qa_mod.evaluate_aligned(
            s.session_id, s.label, aligned,
            allow_no_gnss=s.session_id in allow_no_gnss,
        )
        if qa_result.verdict == "FAIL" and not args.no_qa_gate:
            print(f"  {s.session_id}: QA FAIL，跳过（{'；'.join(qa_result.reasons)}）")
            continue
        if qa_result.verdict == "WARN":
            print(f"  {s.session_id}: QA WARN（{'；'.join(qa_result.reasons)}）")

        aligned["_segment"] = assign_segments(aligned["timestamp"].values)
        frame_labels = frame_labels_of(aligned, s.label)
        feats, fmap, _ = builder.build(aligned)
        df = pd.DataFrame(feats, columns=fmap.columns)
        df["label"] = aligned["label"].values if "label" in aligned.columns else s.label
        df = extractor.transform(df)
        raw = df[feature_cols].to_numpy(dtype=np.float64)
        X = windows_per_segment(raw, aligned["_segment"].values)
        Y = window_labels_per_segment(frame_labels, aligned["_segment"].values)
        if len(X) != len(Y):
            raise RuntimeError(f"窗口数({len(X)})与标签数({len(Y)})不一致")
        valid = Y >= 0
        X, Y = X[valid], Y[valid]
        if len(X) == 0:
            print(f"  {s.session_id}: 窗口不足或标签无效，跳过")
            continue
        Xs = ((X - mean[None, None, :]) / scale[None, None, :]).astype(np.float32)
        preds = []
        for i in range(0, len(Xs), 256):
            preds.append(sess.run(None, {iname: Xs[i:i + 256]})[0].argmax(1))
        pred = np.concatenate(preds)
        y_true.extend(Y.tolist())
        y_pred.extend(pred.tolist())
        acc = float((pred == Y).mean())
        label_dist = {LABEL_NAMES[i]: int((Y == i).sum()) for i in np.unique(Y)}
        print(f"  {s.session_id:<28} label={s.label:<10} windows={len(pred):<5} acc={acc:.3f} "
              f"qa={qa_result.verdict} tags={label_dist}")

    y_true = np.asarray(y_true)
    y_pred = np.asarray(y_pred)
    print("\n=== 汇总 ===")
    print(f"windows={len(y_true)}  accuracy={(y_true == y_pred).mean():.4f}")
    n = len(LABEL_NAMES)
    cm = np.zeros((n, n), dtype=int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1
    header = "true\\pred  " + "".join(f"{LABEL_NAMES[j]:>11}" for j in range(n))
    print(header)
    for i in range(n):
        print(f"{LABEL_NAMES[i]:<10}" + "".join(f"{cm[i][j]:>11d}" for j in range(n)))
    for i in range(n):
        tot = cm[i].sum()
        if tot:
            print(f"  {LABEL_NAMES[i]:<10} recall={cm[i][i] / tot:.3f} (n={tot})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
