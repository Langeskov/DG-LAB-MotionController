#!/usr/bin/env python3
"""从 Motion-ml 导出的 .pth checkpoint 中提取 StandardScaler 参数，生成端侧 scaler.json。

用法:
    # 有 torch 的环境（推荐）:
    python tools/extract_scaler.py --checkpoint Motion-ml/exported/motion_motion_encoder.pth
    # 无 torch 的环境会自动回退到纯标准库解析（只读 scaler 的 list，不反序列化 tensor）:
    python tools/extract_scaler.py --checkpoint Motion-ml/checkpoints/best_motion_encoder.pth

说明:
  - checkpoint 里应有 scaler_mean / scaler_scale（各 24 个 float，训练时 fit 的 StandardScaler）。
  - 输出 JSON 被 App 的 ml/Scaler.kt 读取：x' = (x - mean) / scale，scale==0 视为 1。
  - 若 checkpoint 缺失，也可以手工把两个 24 长度数组写进 scaler.json（valid=true）。
"""

from __future__ import annotations

import argparse
import json
import os
import pickle
import sys
import zipfile

FEATURE_NAMES = [
    "gps_speed", "acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z",
    "acc_magnitude", "gyro_magnitude", "speed_delta",
    "acc_rolling_mean", "acc_rolling_std",
    "gyro_rolling_mean", "gyro_rolling_std",
    "roll_rolling_mean", "roll_rolling_std", "pitch_rolling_mean",
    "vibration_score",
    "acc_bp_0.5_2", "acc_bp_2_5", "acc_bp_5_10", "acc_bp_10_25",
    "acc_centroid", "acc_dom_freq",
]

LABEL_MAP = {
    "STATIONARY": 0,
    "WALKING": 1,
    "CYCLING": 2,
    "CAR": 3,
    "TRAIN": 4,
}


class _Stub:
    """替代 torch/numpy 对象，只用于跳过 tensor 的反序列化。"""

    def __init__(self, *args, **kwargs):
        pass

    def __setstate__(self, state):
        pass

    def __call__(self, *args, **kwargs):
        return _Stub()


class _StubUnpickler(pickle.Unpickler):
    """读取 torch.save 的 zip(pickle)，把 torch/numpy 对象替换成占位符。

    只提取 dict 里的普通 Python 值（scaler_mean/scaler_scale 在 train.py 里是
    `scaler.mean_.tolist()`，即普通 list of float），不需要反序列化模型权重。
    """

    def find_class(self, module, name):
        if module.split(".")[0] in ("torch", "numpy"):
            return _Stub
        return super().find_class(module, name)

    def persistent_load(self, pid):
        return _Stub()


def _load_pickle_zip(path: str):
    with zipfile.ZipFile(path) as zf:
        pkl_names = [n for n in zf.namelist() if n.endswith("data.pkl")]
        if not pkl_names:
            raise SystemExit(f"{path}: 不是 torch.save 的 zip 归档（找不到 data.pkl）")
        with zf.open(pkl_names[0]) as fh:
            return _StubUnpickler(fh).load()


def load_checkpoint(path: str):
    """优先用 torch 加载（类型/语义最完整）；没有 torch 时用纯标准库回退。"""
    try:
        import torch  # noqa: F401

        return torch.load(path, map_location="cpu", weights_only=False), "torch"
    except Exception as exc:  # ImportError 或 torch 版本/环境问题
        print(f"[info] torch 不可用（{type(exc).__name__}: {exc}），改用纯标准库解析", file=sys.stderr)
        return _load_pickle_zip(path), "stdlib"


def main() -> int:
    ap = argparse.ArgumentParser(description="Extract StandardScaler params from Motion-ml checkpoint")
    ap.add_argument("--checkpoint", required=True, help="best_motion_encoder.pth 或 exported/motion_*.pth")
    ap.add_argument("--out", default="app/src/main/assets/scaler.json")
    args = ap.parse_args()

    if not os.path.isfile(args.checkpoint):
        print(f"checkpoint 不存在: {args.checkpoint}", file=sys.stderr)
        return 2

    ckpt, how = load_checkpoint(args.checkpoint)
    if not isinstance(ckpt, dict):
        print(f"checkpoint 顶层不是 dict（类型 {type(ckpt).__name__}），无法提取 scaler", file=sys.stderr)
        return 3

    mean = ckpt.get("scaler_mean")
    scale = ckpt.get("scaler_scale")
    if mean is None or scale is None:
        print(
            "checkpoint 中没有 scaler_mean/scaler_scale。\n"
            f"可用 keys: {sorted(str(k) for k in ckpt.keys())}",
            file=sys.stderr,
        )
        return 4

    mean = [float(x) for x in mean]
    scale = [float(x) for x in scale]
    if len(mean) != 24 or len(scale) != 24:
        print(f"维度不是 24: mean={len(mean)} scale={len(scale)}", file=sys.stderr)
        return 5
    if any(m != m for m in mean) or any(s != s for s in scale):
        print("scaler 含 NaN", file=sys.stderr)
        return 6
    if any(s <= 0 for s in scale):
        print(f"scaler_scale 存在非正值: {scale}", file=sys.stderr)
        return 7

    label_map = ckpt.get("label_map") or LABEL_MAP
    payload = {
        "valid": True,
        "mean": mean,
        "scale": scale,
        "featureNames": FEATURE_NAMES,
        "labelMap": label_map,
        "source": os.path.basename(args.checkpoint),
        "loadedVia": how,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"[ok] 写入 {args.out}（via {how}）")
    for name, m, s in zip(FEATURE_NAMES, mean, scale):
        print(f"     {name:<18} mean={m:+.4f} scale={s:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
