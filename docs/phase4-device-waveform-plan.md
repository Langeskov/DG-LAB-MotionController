# Phase 4 — 设备波形（Device Waveform）方案与 Phase 4A 交付

- 日期：2026-11-20
- 状态：**Phase 4A 协议层完成（默认关闭）**；UI/真实设备波形联调待产品决策
- 关联：`docs/phase3-real-device-v4-audit.md` §8、`docs/phase3-evidence-2026-11-20/protocol-research-dglab-kit.md`
- 结论先行：**默认由 DG-LAB APP 负责脉冲波形（载波），本 APP 只负责“运动 → 强度 + 强度包络”。**
  t=0 AppendPulseData 仅作为**默认关闭的实验能力**保留协议层实现，不作为产品默认路径。

---

## 1. 问题：波形设置放 DG-LAB 还是本 APP？

V4 外部控制协议只有一种“改脉冲形状”的手段：`device.op` t=0 `AppendPulseData`（下发波形帧）。
除此之外，APP 的波形库/波形切换只能由用户在 DG-LAB APP 内操作，本 APP 无法通过协议“让 APP 切换到某个波形”。

| 维度 | DG-LAB APP 负责波形（推荐默认） | 本 APP 通过 t=0 接管波形 |
|---|---|---|
| 安全边界 | APP 自带舒适上限/过热保护/软启动；波形与强度闭环解耦 | 帧直接驱动脉冲，**绕过** `safetyMax/slew/EMA/死手看门狗`；强度限幅只能靠帧值自证 |
| 协议确定性 | APP 官方支持，升级风险低 | 帧语义（V2/V3 8 元帧）公开资料不完整；t=5 等缺失 |
| 许可证 | 不涉及第三方波形库 | dglab-kit 为 GPL-3.0（不可复制）；官方 BLE 协议商用需授权；帧来源必须合法可再分发 |
| 测试成本 | 现有真机验证已覆盖强度闭环 | 需要非人体/虚拟负载下的新测试矩阵；现有工程无 BLE 级回执，风险不可观测 |
| 产品价值 | 本 APP 的差异化是 IMU/GNSS→运动→强度，而不是波形编辑器 | “按运动自动切波形/随速度改波形”若确为需求，才值得接管 |
| 实现复杂度 | 低（现状即已满足） | 高（帧生成、调度、限幅、清除、UI、兼容 COYOTE_020/030） |

**推荐架构（混合）**：
1. **默认模式**：用户在 DG-LAB APP 选择“连续/呼吸”类波形作为载波；本 APP 只输出强度，并用现有
   `WaveformEngine` 做**强度包络**（只向下调制、不放大、可随时关闭）。
2. **实验模式（默认关闭）**：本 APP 具备 t=0 下发能力，用于协议研究/特殊玩法；开启前必须满足 §4 的准入条件。
3. **不建议**把“波形库/波形编辑器”作为本 APP 的默认功能；它会与 DG-LAB 的职责重叠，并把安全责任转移给本 APP。

如果产品确实需要“运动自动切换设备波形”，建议先只做**实验功能**并在 UI 上显式标注风险；在拿到合法帧来源与
非人体测试证据前，不进入默认路径。

---

## 2. Phase 4A 已交付（协议层 foundation，默认关闭）

| 交付 | 内容 |
|---|---|
| `dglab/PulseTask.kt` | `PulseFrameVersion{V2,V3}`、`PulseTaskSpec`、`PulseTaskState`、`PulseTaskStatus`、`PulseTaskSpecValidator`（帧数 ≤1200、时长 ≤120s、V2 帧长 3 / V3 帧长 8、值域 0..255 的结构校验，不解释语义） |
| `DglabLinkApi` | `startPulseTask(spec): Boolean`（默认 false）、`clearPulseTasks()`（默认 no-op）、`DglabState.pulseTasks` |
| `V4Controller` | `pulseTasksEnabled=false` 构造开关；t=0 帧构建（`s/t=0/c/d/v/ver/im`）；reqId→通道追踪；终态映射 completed/cleared/replaced/cancelled/error/timeout；急停/断连本地取消；长 TTL（覆盖帧播放/时长）；**未启用/急停/链路不可用/校验失败一律拒绝并打日志** |
| `V4Link` / `ControllerService` | 实验开关透传（默认关闭）；STOP 时调用 `clearPulseTasks()`（无任务时不产生帧） |
| 测试 | `V4ControllerPulseTaskTest` 8 个：校验器 2、默认关闭 1、急停拒绝 1、t=0 帧结构与 COMPLETED 1、终态映射 1、急停取消 1、clear 清理 1；全量 **252 单测全绿** |
| 构建 | APK 65.45MB arm64，编译通过；**App 内默认未启用，不改任何现有用户可见行为** |

本阶段**未做**（有意）：波形 Profile/编辑器 UI、任何波形库/帧预设、真实设备 t=0 播放、t=4/t=5、
自动波形联动、替换现有 `WaveformEngine`。

---

## 3. 开启实验模式前的准入条件（必须全部满足）

1. **帧来源合法**：不得复制 dglab-kit（GPL-3.0）代码/波形库；官方 BLE 协议材料商用需授权；
   自研帧需有可复现的推导依据（频段/强度/间隔），并记录来源。
2. **非人体测试**：Coyote 接虚拟负载/假负载；全程人不接触电极；每次 ≤120s、低强度、可立即 clear。
3. **语义验证**：在真机 APP 4.2.6 + COYOTE_030 上确认 `ver=2/3` 帧的字段含义、tick 消费、任务终态 reason；
   与公开资料逐项对齐，未确认项不得臆测。
4. **安全收敛**：帧值必须满足独立校验（例如强度上限由帧值推导并限制在安全范围），并把 t=0 任务与
   START/STOP、急停、断连、看门狗全部联动；急停路径必须先 `device.op.clear` 再本地清状态。
5. **可观测性**：记录 reqId、reason、slots.patch 变化；UI 必须标注“APP 层状态，不代表 BLE 物理输出确认”。

---

## 4. 后续候选工作（需产品确认方向）

- **A. 维持默认（推荐）**：完善“载波建议 + 包络预设”的 UI 文案/联动；不启用 t=0。
- **B. 实验模式 UI**：仅在 ENGINEERING 诊断页提供 t=0 测试入口（需二次确认 + 虚拟负载提示），
  不进入 OPERATION 主流程。
- **C. 完整设备波形系统**：波形 Profile/帧生成/自动联动/UI；仅在 §3 准入条件满足后启动。

---

## 5. 许可证与合规

- 本工程 MIT；Phase 4A 未引入任何第三方代码/波形数据。
- `dglab-kit` GPL-3.0：只读协议资料、独立实现；不复制其 TypeScript 代码与 `COYOTE_WAVEFORMS`。
- DG-LAB 官方 Bluetooth Protocol 仓库：公开文档 + 商用需授权；引入帧数据前必须确认授权与再分发条件。
