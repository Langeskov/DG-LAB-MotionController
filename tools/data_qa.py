#!/usr/bin/env python3
"""数据质量审计 / 入库门禁 CLI —— 采集与标注契约的执行器。

用法:
    python tools/data_qa.py                         # 审计 Motion-ml/data/raw
    python tools/data_qa.py --json build/qa_report.json
    python tools/data_qa.py --fail-on warn          # 有 WARN 即非零退出（默认仅 FAIL 非零）
    python tools/data_qa.py --sessions CAR_20260913_112250,WALKING_20260809_180043

判定逻辑的唯一实现在 Motion-ml/dataset/session_qa.py（训练侧 QA 门禁复用同一模块），
本工具只负责：遍历数据目录 → TimeAligner 对齐 → 调用 evaluate_aligned → 汇总/输出。
另外单独报告「修复前重复时间戳占比」和「50Hz 重采样后窗口数」供采集计划使用。

退出码: 0 = 无 FAIL（或未达 --fail-on 级别）；1 = 存在达到阈值的会话。
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML_DIR = os.path.join(ROOT, "Motion-ml", "dataset")
DEFAULT_DATA = os.path.join(ROOT, "Motion-ml", "data", "raw")
DEFAULT_QUARANTINE = os.path.join(ROOT, "Motion-ml", "data", "quarantine")
DEFAULT_JSON = os.path.join(ROOT, "build", "qa_report.json")

TARGET_HZ = 50.0
MAX_GAP_MS = 200.0
WINDOW = 150
STRIDE = 50


def load_module(name: str, filename: str):
    path = os.path.join(ML_DIR, filename)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"无法加载 {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ── 与 session_dataset.py::resample_frame 等价的轻量实现（避免 import torch） ──
def resample_frame(df: pd.DataFrame, target_hz: float = TARGET_HZ,
                   time_col: str = "timestamp", max_gap_ms: float = MAX_GAP_MS) -> pd.DataFrame:
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
        sub = df.iloc[a:b]
        st = sub[time_col].to_numpy(dtype=np.float64)
        n = int(np.floor((st[-1] - st[0]) / 1000.0 * target_hz)) + 1
        if n < 2:
            parts.append(sub)
            continue
        grid = st[0] + np.arange(n) * (1000.0 / target_hz)
        out = {time_col: grid}
        for col in sub.columns:
            if col == time_col:
                continue
            s = sub[col]
            if pd.api.types.is_numeric_dtype(s) and not pd.api.types.is_bool_dtype(s):
                out[col] = np.interp(grid, st, s.to_numpy(dtype=np.float64))
            else:
                nn_idx = np.clip(np.searchsorted(st, grid, side="right") - 1, 0, len(st) - 1)
                out[col] = s.to_numpy()[nn_idx]
        parts.append(pd.DataFrame(out))
    return pd.concat(parts, ignore_index=True) if parts else df


def windows_per_segment(timestamps: np.ndarray, window: int = WINDOW,
                        stride: int = STRIDE, max_gap_ms: float = MAX_GAP_MS) -> list[int]:
    t = np.asarray(timestamps, dtype=np.float64)
    if len(t) == 0:
        return []
    breaks = np.where(np.diff(t) > max_gap_ms)[0] + 1
    bounds = np.concatenate([[0], breaks, [len(t)]])
    counts = []
    for a, b in zip(bounds[:-1], bounds[1:]):
        n = b - a
        counts.append(0 if n < window else (n - window) // stride + 1)
    return counts


def raw_duplicate_ratio(imu_path: str) -> float:
    """修复前的重复时间戳占比（新录制器应为 0；旧数据用于说明修复代价）。"""
    try:
        ts = pd.read_csv(imu_path, usecols=["timestamp"])["timestamp"]
    except (ValueError, KeyError):
        return 0.0
    n = len(ts)
    if n == 0:
        return 0.0
    return float(max(0.0, 1.0 - ts.nunique(dropna=True) / n))


def audit_session(loader, aligner, qa, info, data_dir: str, strict_dup: bool) -> dict:
    session = loader.load_session(info, repair_timestamps=True)
    aligned, align_stats = aligner.align(session.imu.copy(), session.gnss.copy())
    dup = raw_duplicate_ratio(info.imu_path)

    # 与训练侧 `MotionWindowDataset` 同口径：先按 >200ms 断段重采样到 50Hz，再在
    # **实际送入模型的 50Hz 流**上评估 GNSS 覆盖率（低采样率会话不重采样，避免凭空插值）。
    timestamps = aligned["timestamp"].to_numpy(dtype=np.float64)
    source_hz = 0.0
    if len(timestamps) > 2:
        dt_ms = np.median(np.diff(timestamps))
        source_hz = 1000.0 / dt_ms if dt_ms > 0 else 0.0
    min_source_hz = 20.0
    if source_hz >= min_source_hz:
        aligned_eval = resample_frame(aligned, TARGET_HZ)
    else:
        aligned_eval = aligned

    # 新录制器（有 _meta.json）才把「修复前重复时间戳」纳入门禁；
    # 旧数据只有 repair 后的可用性，raw dup 仅作 legacy 报告（--strict-dup 可强制检查）。
    meta_path = os.path.join(data_dir, f"{info.prefix}_meta.json")
    has_meta = os.path.exists(meta_path)
    meta = {}
    if has_meta:
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            meta = {}
    gate_dup = (strict_dup or has_meta) and dup > 0.0
    gnss_expected = bool(meta.get("gnssExpected", True))

    result = qa.evaluate_aligned(
        info.prefix, info.label, aligned_eval,
        raw_duplicate_ratio=dup if gate_dup else None,
        allow_no_gnss=not gnss_expected,
    )

    # 50Hz 重采样后的窗口数（供采集计划估算）
    resampled = resample_frame(aligned, TARGET_HZ)
    ts = resampled["timestamp"].to_numpy(dtype=np.float64)
    seg_windows = windows_per_segment(ts)
    row = {
        "session_id": info.prefix,
        "declared_label": info.label,
        "verdict": result.verdict,
        "reasons": list(result.reasons),
        "raw_rows": int(len(session.imu)),
        "gnss_rows": int(len(session.gnss)),
        "source_hz": round(source_hz, 1),
        "resampled_rows": int(len(aligned_eval)),
        "raw_duplicate_ratio": round(dup, 4),
        "has_meta": has_meta,
        "recorder_version": meta.get("recorderVersion"),
        "gnss_expected": gnss_expected,
        "windows_50hz": int(sum(seg_windows)),
        "segments_50hz": len(seg_windows),
        "metrics": {k: (round(v, 4) if isinstance(v, float) else v)
                    for k, v in result.metrics.items()},
    }
    return row


def audit_quarantine(loader, qa, quarantine_dir: str) -> list[dict]:
    if not os.path.isdir(quarantine_dir):
        return []
    out = []
    infos = loader.scan_sessions(quarantine_dir)
    for info in infos:
        out.append({
            "session_id": info.prefix,
            "declared_label": info.label,
            "verdict": "QUARANTINED",
            "reasons": ["QUARANTINED 人工移出训练/评测集"],
            "raw_rows": None,
            "gnss_rows": None,
            "raw_duplicate_ratio": None,
            "windows_50hz": None,
            "segments_50hz": None,
            "metrics": {},
        })
    return out


def fmt_metrics(row: dict) -> str:
    m = row.get("metrics") or {}
    parts = [
        f"{row['session_id']:<30}",
        f"{row['verdict']:<6}",
        f"rows={row['raw_rows'] if row['raw_rows'] is not None else '-':>7}",
        f"hz={m.get('effective_hz', '-')}",
        f"nofix={m.get('gps_no_fix_ratio', '-')}",
        f"dup={row['raw_duplicate_ratio']}",
        f"speed_med={m.get('speed_median_kmh', '-')}",
        f"stand={m.get('standing_ratio', '-')}",
        f"windows={row['windows_50hz']}",
    ]
    return " ".join(str(p) for p in parts)


def main() -> int:
    ap = argparse.ArgumentParser(description="Motion 数据质量审计 / 入库门禁")
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--quarantine", default=DEFAULT_QUARANTINE)
    ap.add_argument("--json", default=DEFAULT_JSON)
    ap.add_argument("--sessions", default="", help="逗号分隔的 session 前缀（默认全部）")
    ap.add_argument("--strict-dup", action="store_true",
                    help="对无 _meta.json 的旧会话也按 raw 重复时间戳门禁检查（默认仅报告）")
    ap.add_argument("--fail-on", choices=["none", "warn", "fail"], default="fail")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    loader = load_module("mm_session_loader", "session_loader.py")
    aligner_mod = load_module("mm_time_aligner", "time_aligner.py")
    qa = load_module("mm_session_qa", "session_qa.py")

    infos = loader.scan_sessions(args.data)
    if args.sessions.strip():
        wanted = {s.strip() for s in args.sessions.split(",") if s.strip()}
        infos = [i for i in infos if i.prefix in wanted]
    if not infos:
        print(f"未找到会话: {args.data}", file=sys.stderr)
        return 2

    aligner = aligner_mod.TimeAligner(max_gap_ms=2000.0)
    rows = []
    for i, info in enumerate(infos, 1):
        if not args.quiet:
            print(f"[{i}/{len(infos)}] auditing {info.prefix} ...", file=sys.stderr)
        try:
            rows.append(audit_session(loader, aligner, qa, info, args.data, args.strict_dup))
        except Exception as exc:  # 单个会话损坏不应中断整批审计
            rows.append({
                "session_id": info.prefix,
                "declared_label": info.label,
                "verdict": "FAIL",
                "reasons": [f"AUDIT_ERROR {type(exc).__name__}: {exc}"],
                "raw_rows": None, "gnss_rows": None, "raw_duplicate_ratio": None,
                "windows_50hz": None, "segments_50hz": None, "metrics": {},
            })
    quarantined = audit_quarantine(loader, qa, args.quarantine)

    counts = {"PASS": 0, "WARN": 0, "FAIL": 0}
    for r in rows:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    by_label: dict[str, dict] = {}
    for r in rows:
        if r["verdict"] == "FAIL":
            continue
        label = (r["declared_label"] or "UNKNOWN").upper()
        agg = by_label.setdefault(label, {"sessions": 0, "windows": 0})
        agg["sessions"] += 1
        agg["windows"] += int(r["windows_50hz"] or 0)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "data_dir": os.path.abspath(args.data),
        "contract": getattr(qa, "QA_CONTRACT", {}),
        "summary": {
            "total": len(rows),
            "verdicts": counts,
            "usable_by_label": by_label,
            "quarantined": len(quarantined),
        },
        "sessions": rows,
        "quarantine": quarantined,
    }
    os.makedirs(os.path.dirname(args.json), exist_ok=True)
    with open(args.json, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"{'session':<30} {'verdict':<6} {'rows':>7} hz nofix dup speed_med stand windows")
    for r in sorted(rows, key=lambda x: (x["verdict"], x["session_id"])):
        print(fmt_metrics(r))
        for reason in r["reasons"]:
            print(f"    - {reason}")
    print(f"\n汇总: total={len(rows)} PASS={counts['PASS']} WARN={counts['WARN']} "
          f"FAIL={counts['FAIL']} quarantined={len(quarantined)}")
    for label, agg in sorted(by_label.items()):
        print(f"  {label:<10} usable_sessions={agg['sessions']:<3} "
              f"50hz_windows≈{agg['windows']}")
    print(f"报告: {os.path.abspath(args.json)}")

    if args.fail_on == "none":
        return 0
    if counts["FAIL"] > 0:
        return 1
    if args.fail_on == "warn" and counts["WARN"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
