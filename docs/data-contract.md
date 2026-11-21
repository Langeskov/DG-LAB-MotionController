# 数据采集与标注契约（v1.0）

> 状态：2026-11-06 起生效；适用于 MotionRecorder（采集端）与 Motion-ml（训练/评测端）。
> 唯一实现：`Motion-ml/dataset/session_qa.py`；审计 CLI：`tools/data_qa.py`。
> 背景：2026-11-05 数据审计发现 83.12% 的 174 个窗口错误中，114 个来自「窗口内 >50% 帧无 2s 内
> GNSS 定位」、19 个来自 TRAIN 会话中的站停窗口、35 个来自疑似错标的 STATIONARY 窗口——**先修
> 采集/标注契约，再补数据**；否则新数据会重复同样的错误。

---

## 1. 总则（不可协商）

1. **标签 = 当前运动状态**，不是交通方式或出行目的。手机在车里但车停着 → `STATIONARY`，不是 `CAR`。
2. **会话主模式**写在文件名前缀（`<MODE>_<yyyyMMdd_HHmmss>_imu.csv`），**不代表全部帧的标签**；
   会话内允许帧级标签切换（如 TRAIN 行驶中停站）。
3. **训练窗口标签 = 窗口内帧级标签的多数投票**；只有帧级标签缺失/全为 UNKNOWN 时才回退会话主模式。
4. 时间轴以手机单调时钟为准：采集端用 `SensorEvent.timestamp` / `Location.elapsedRealtimeNanos`；
   对齐端用 `TimeAligner(max_gap_ms=2000)`。CSV 的 `timestamp` 是 epoch 毫秒，仅作可读时间。
5. 一个会话 = 一次连续录制的 IMU+GNSS 文件对；不能把多段不连续行程拼进同一会话。
6. 数据集分三档：`data/raw/`（候选）→ QA 通过才可训练/评测；`data/quarantine/`（明确排除，保留存档）。

---

## 2. 采集协议（MotionRecorder ≥ 1.7.0；1.6.0 及更早的录音没有 `gps_ok`/`_meta.json`，按旧数据兼容处理）

### 2.1 开录前（硬门禁，App 强制）

- [ ] **GPS 连续 LOCKED 且 accuracy < 20 m 至少 5 s**。未满足时开始按钮禁用并显示倒计时；
      服务端二次校验，绕过 UI 也拒绝开录。
- [ ] 手机放置方式固定（桌面/手持/裤袋/支架/列车座椅），同一会话内不得移动或改放置。
- [ ] 电量 ≥ 20%，剩余存储 ≥ 500 MB；保持定位开启，屏幕可熄但 App 需前台服务运行。
- [ ] 行进类（WALKING/CYCLING/CAR/TRAIN）开录时车辆/人体应已进入该状态，并保持 ≥ 5 s。

### 2.2 录制中

- [ ] 不得关闭 GPS、不得改系统时间、不得强杀进程（强杀最多丢 1 s 数据且文件仍可解析）。
- [ ] **站停必须切标签**：TRAIN/CAR 停站、等灯、堵停超过 **10 s** 时，切到 `STATIONARY`；
      恢复行驶前切回主模式。WALKING/CYCLING 同理（等灯/停留 >10 s 切 `STATIONARY`）。
- [ ] 单会话时长 5–10 min（下限 3 min）；同一场景至少 2 个不同日期的会话。
- [ ] 每类采集场景：见 §6 采集计划。

### 2.3 结束与会话元数据

- [ ] 停止录制后 App 生成 `<session>_meta.json`：
      `sessionMode / labelEvents / deviceModel / androidVersion / recorderVersion /
      imuCount / gnssCount / gpsOkCount / gpsNoFixRatio / qaVerdict / qaReasons / gnssExpected`。
- [ ] QA 判定 FAIL 的会话**不进入 `data/raw`**（删除重录）；WARN 的会话可入库但需在采集日志中记录原因。
- [ ] 无 GNSS 的故意采集（地铁地下/隧道）必须在 meta 中写 `"gnssExpected": false`，训练/评测用
      `allow_no_gnss=True` 豁免覆盖率门禁（见 §4）。
- [ ] 导出：把 `*_imu.csv`、`*_gnss.csv`、`*_meta.json` 复制到 `Motion-ml/data/raw/`，然后运行
      `python tools/data_qa.py`。

---

## 3. 标注规则（按类别）

| 会话主模式 | 必须只包含 | 允许的帧级切换 | 禁止 |
|---|---|---|---|
| STATIONARY | 手机静止（任意放置） | 无（保持静止） | 含任何移动段；桌上但被拿起走动 |
| WALKING | 步行（含上下楼） | 停留 >10s 切 STATIONARY | 跑步；骑行；手持晃动但人在移动 |
| CYCLING | 电动自行车骑行 | 等灯/停留 >10s 切 STATIONARY | 步行推车；汽车 |
| CAR | 汽车行驶 | 红绿灯/堵停 >10s 切 STATIONARY | 停车状态整段标 CAR；电动车 |
| TRAIN | 列车行驶 | 停站 >10s 切 STATIONARY；地铁地下段可整段无 GNSS（meta 标记） | 整段含大量站停仍标 TRAIN；地铁开 GNSS 时把 0 速站停当行驶 |

**标签切换操作**：录制页「当前标签」选择器 → 选 `STATIONARY`（站停）→ 恢复行驶前选回主模式。
切换事件会写入 meta 的 `labelEvents`，并逐帧写入 IMU CSV 的 `label` 列。

---

## 4. 入库 QA 门禁

实现：`Motion-ml/dataset/session_qa.py`（`QA_CONTRACT`）。CLI：`tools/data_qa.py`。
训练/评测（`MotionWindowDataset`、`tools/validate_e2e.py`、`tools/diagnose_inference.py`）默认开启门禁：
**FAIL 跳过拒训，WARN 保留但打印原因**。

| 检查项 | 阈值 | 判定 | 处置 |
|---|---|---|---|
| GNSS 无 2s 内定位帧占比 | ≤ 1% PASS / 1–5% WARN / >5% FAIL | FAIL 拒训 | 重录（开录前等锁定）；地铁/隧道需 `gnssExpected=false` 豁免 |
| 有效采样率（连续段内） | < 20 Hz FAIL | FAIL | 弃用；检查录制器/传感器 |
| 有效帧数 / 时长 | < 3000 帧 或 < 60 s FAIL | FAIL | 重录更长时间 |
| 修复前重复时间戳占比 | > 1% FAIL（**仅新录制器**，有 `_meta.json` 的会话；旧数据仅报告，训练前由 `repair_duplicate_timestamps` 修复） | FAIL | 弃用；旧数据标注 legacy |
| 未知/非法标签 | 任意 FAIL | FAIL | 修正数据 |
| STATIONARY 中位速度 | > 2 km/h FAIL（疑似错标） | FAIL | 重新标注或弃用 |
| TRAIN/CAR 中 <2 km/h 帧占比 | > 20% WARN | WARN | 下次按 §2.2 切标签；旧数据训练时窗口多数投票会自然降权 |
| 停顿墙钟占比（>200ms） | > 90% WARN | WARN | 检查录制是否异常中断 |
| 帧级标签与会话主模式不一致 | > 30% WARN | WARN | 确认文件名主模式是否选错 |

**当前口径效果**：QA 门禁替代「人工 eval 列表」——评测/训练只使用 PASS/WARN 会话；
`STATIONARY_20261022_103430`（4.9 Hz、GNSS 仅 1 行）已移入 `data/quarantine/`，不再计入准确率。

---

## 5. 当前数据审计（2026-11-06，`tools/data_qa.py`）

> 数字由 `python tools/data_qa.py` 生成；完整报告 `build/qa_report.json`（含每会话指标与原因码）。
> 旧数据（无 `_meta.json`）的「修复前重复时间戳」只报告、不作门禁；新录制器会话才按 RAW_DUP 拒收。

**总览（27 个候选 + 2 个 quarantine；口径 = 与训练一致：>200ms 断段重采样到 50Hz 后评估）**

| 结果 | 数量 | 会话 |
|---|---|---|
| PASS | 9 | CAR_20260816_113442 / CAR_20260919_100818 / CAR_20260919_165304 / CYCLING_20260807_083227 / CYCLING_20260807_193047 / WALKING_20260809_180043 / WALKING_20260809_182248 / WALKING_20261024_141507 / WALKING_20261024_143731 |
| WARN（可用，保留） | 13 | CAR_133149 / CAR_173043 / CAR_085919 / CAR_143959 / CAR_114644（站停比例高、少量无定位）/ CYCLING_082842 / CYCLING_185257 / CYCLING_191642 / CYCLING_083536（停顿占比高）/ TRAIN_085528 / TRAIN_160711（站停比例高、GNSS 4.4%）/ STATIONARY_092733 / STATIONARY_101536（GNSS 1.4%/2.6%） |
| FAIL（拒训） | 5 | CAR_20260913_112250（无定位 17.9%）、STATIONARY_20261022_100043（44.9%）、TRAIN_20260816_120745（17.7%）、TRAIN_20260926_202940（39.6%）、TRAIN_20261016_134112（6.5%+站停 38%） |
| QUARANTINE | 2 | STATIONARY_20261022_103430（4.9Hz、GNSS 仅 1 行、无定位 97.4%）、WALKING_20260912_173758（人工移出） |

**按类别可用量（PASS+WARN 的 50Hz 窗口估算）**

| 类别 | 可用会话 | 50Hz 窗口 | 备注 |
|---|---|---|---|
| CAR | 8 | ≈14,000 | 窗口量最大；4 个 WARN 有 23–50% 站停帧未标注 |
| CYCLING | 6 | ≈1,824 | 4 个 WARN 停顿占比 92–94% |
| TRAIN | 2 | ≈2,194 | 3 个老 TRAIN 会话因 GNSS 缺失/站停标注被拒 |
| WALKING | 4 | ≈1,097 | 场景单一（无上下楼/不同步速） |
| STATIONARY | 2 | ≈885 | 仅老会话 092733/101536 可用（WARN），且 77% 窗口来自一个 250Hz 老会话 |

**门禁开/关对比（离线管线，`tools/validate_e2e.py`，50Hz 重采样 + 150/50 窗口）**

| 口径 | 会话 | 窗口 | accuracy | STATIONARY 召回 | TRAIN 召回 |
|---|---|---|---|---|---|
| 门禁关（全部 27 个候选） | 27 | 22,042 | 91.53% | 0.947 (n=1073) | 0.842 (n=4031) |
| 门禁开（PASS+WARN） | 22 | 20,000 | **92.63%** | 0.975 (n=885) | 0.867 (n=2194) |

> **不可与历史 83.12% 直接比较**：历史值来自固定的 10 会话/1031 窗口（含 1 个坏会话与多个 GNSS
> 缺失会话，未开门禁）；上表是同一批 27 个候选在门禁开/关下的对照，用于量化契约收益。
> 门禁开 = 拒掉 5 个 FAIL 会话（2,042 窗口），整体准确率 +1.1pt，但 STATIONARY/TRAIN 窗口更少。

**结论**：
1. 严格按契约，旧数据中 STATIONARY 只剩 2 个 WARN 会话（885 窗口，占 4.4%）、TRAIN 只剩 2 个 WARN
   会话——**类别极不均衡，仍无法训练出均衡模型**，P0 补采 STATIONARY/TRAIN 仍是最优先。
2. 过渡方案：`train.py --no_qa_gate` 保留旧数据基线；新 P0 数据到位后改用默认（门禁开）。
3. 旧数据的 CAR/CYCLING 站停帧问题（WARN）由新录制器帧级标签解决；旧数据训练时窗口多数投票仍会
   把站停窗口标成移动类，属已知遗留误差（CAR 站停 23–50%、TRAIN 38–42%）。
4. 旧 WARN 会话的「停顿占比 >90%」是墙钟效率问题（录音里 92–98% 是停顿），不直接错分类，但补采时
   应避免把手机放置/等待时间算进会话。

---

## 6. 需要补哪些数据（按收益排序）

> 每项 = 为什么缺 / 采多少 / 怎么采 / 验收。所有新会话必须：GPS 连续 LOCKED ≥5s 后开录、帧级标签、
  导出 `_meta.json`、入库前 `python tools/data_qa.py --sessions ...` 确认 PASS（WARN 需记录原因）。

### P0 — 阻塞重训（先补，收益最大）

| # | 数据 | 数量 | 采集协议 | 验收 |
|---|---|---|---|---|
| P0-1 | **STATIONARY（全姿态）** | 8–10 个会话 | 5–10 min/个；桌面平放、手持、裤袋、车内支架、列车座椅各 ≥1；手机不移动、GNSS LOCKED≥5s、无定位帧 ≤5%；分 2–3 天采集 | data_qa PASS；中位速度 ≤2 km/h；无定位 ≤5% |
| P0-2 | **TRAIN（纯行驶）** | 3 个会话 | 5–10 min/个；高铁/城际巡航 + 进出站加减速；**停站 >10s 必须切 STATIONARY 帧标签**，会话文件名仍用 TRAIN；GNSS LOCKED 开录 | data_qa PASS；站停帧 <20%；无定位 ≤5% |
| P0-3 | **TRAIN 地铁/隧道（无 GNSS）** | 1 个会话 | 地铁地下段 5–8 min；`_meta.json` 写 `gnssExpected=false`；轨迹连续、标签为 TRAIN（停站切 STATIONARY） | 用 `--allow-no-gnss` 审计 PASS（其余检查通过） |

### P1 — 覆盖与鲁棒

| # | 数据 | 数量 | 采集协议 | 验收 |
|---|---|---|---|---|
| P1-1 | WALKING 多场景 | 3 个会话 | 3/5/6 km/h 各一段（每段 ≥3 min）；上下楼梯；手持 vs 裤袋各一段；停留 >10s 切 STATIONARY | PASS；帧标签含少量 STATIONARY 段 |
| P1-2 | CYCLING 多场景 | 3 个会话 | 15–25 km/h；含起步/刹车/等灯（等灯 >10s 切 STATIONARY）；不同路况 | PASS；无定位 ≤5% |
| P1-3 | CAR 低速拥堵 | 2 个会话 | 市区 20–40 min 真堵车/等灯；停车 >10s 切 STATIONARY；GNSS 正常 | PASS/WARN；站停帧比例记录 |
| P1-4 | CAR 隧道（无 GNSS） | 1 个会话 | 长隧道 3–5 min；`gnssExpected=false` | `--allow-no-gnss` PASS |

### P2 — 可选/锦上添花

- STATIONARY 列车座椅/地铁站台各 1 个（与 TRAIN 同行程采集，检验 TRAIN↔STATIONARY 边界）。
- WALKING 跑步对照（可选，当前任务不含 RUN 类别，不建议入库）。
- 同一路线「开/关定位」对照各 1 个（用于验证 gps_speed=0 增广效果）。

### 不需要补

- CAR 常规行驶（已有 8 个可用会话 ≈14,000 窗口，F1 已高；只补 P1-3/P1-4 边界场景）。
- 重复的「大间隙重采样」测试数据（JVM 已有合成 + 真实切片 golden）。
- 任何**开录前 GNSS 未锁定**的会话（新门禁会直接拒绝开录）。

### 目标量（补采后重新统计）

| 类别 | 目标可用窗口 | 目标会话数 |
|---|---|---|
| STATIONARY | ≥1,500 | ≥8 |
| TRAIN | ≥1,500 | ≥5 |
| WALKING | ≥1,000 | ≥6 |
| CYCLING | ≥1,200 | ≥6 |
| CAR | 保持 ≈8,000–14,000（避免进一步压垮类别平衡） | 8–10 |

---

## 7. 工具与执行流程

```powershell
# 0) 环境：numpy/pandas/sklearn 的 Python（本机 C:\ProgramData\anaconda3\python.exe）
$PY = 'C:\ProgramData\anaconda3\python.exe'

# 1) 全量审计（FAIL 会返回非零退出码，可作入库门禁；报告含每会话指标/原因码）
& $PY tools\data_qa.py --json build\qa_report.json
& $PY tools\data_qa.py --strict-dup        # 对旧会话也强制检查修复前重复时间戳

# 2) 端到端准确率（默认应用 QA 门禁；--no-qa-gate 用于旧基线/对照）
& $PY tools\validate_e2e.py
& $PY tools\validate_e2e.py --allow-no-gnss TRAIN_METRO_20261110_000000

# 3) 管线诊断（默认应用 QA 门禁；坏会话会被跳过并打印原因）
& $PY tools\diagnose_inference.py

# 4) 训练（默认门禁开；补采 STATIONARY 前的过渡期可 --no_qa_gate）
cd Motion-ml
& $PY train.py --data_dir data/raw --split_mode session
& $PY train.py --data_dir data/raw --no_qa_gate      # 过渡：保留旧数据
```

- 采集端：MotionRecorder 1.7.0 录制页标签切换 + 开录门禁 + `_meta.json`。
- 训练端：`Motion-ml/dataset/session_dataset.py`（帧级标签 + QA 门禁）；详见 Motion-ml/README.md。
- 契约变更必须同时改：`session_qa.py` 阈值、本文档、`data_qa.py` 输出、AGENTS.md。

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-05 | 首次数据审计：27 个可用会话、174 个错误归因；提出「先修契约再补数据」 |
| 2026-11-06 | v1.0：写入本契约；MotionRecorder 增加锁定门禁/帧级标签/gps_ok/QA meta；Motion-ml 增加 session_qa.py 与帧级标签；tools/data_qa.py 入库门禁；坏会话移入 quarantine |
