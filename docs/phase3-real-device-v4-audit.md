# Phase 3 — 真实设备联调 & V4 协议能力审计报告

- 日期：2026-11-20
- 版本：MotionController 1.0（2026-11-16 snapshot，Phase 3 修改后 APK 65.45MB arm64-v8a）
- 状态：**PARTIALLY VERIFIED**（真实 DG-LAB APP + 真实 Coyote 3.0 已联调；BLE 设备端停止确认仍不可用；
  t=0 设备波形未验证）
- 关联：`AGENTS.md` §2/§3.2/§3.3/§3.8/§3.12；证据 `docs/phase3-evidence-2026-11-20/`
  （截图已按用户要求于 2026-11-20 删除，不再随仓库保留）

---

## 1. 本次结论（先看这里）

1. **真实链路已跑通**：Redmi K90 PM → ControllerService → 本地 V4 Relay（127.0.0.1:9998）→
   DG-LAB APP 4.2.6 → 真实 Coyote 3.0（COYOTE_030）。握手、`devices.snapshot`、`slots.patch`、
   `device.op`（t=3 / t=7 v=0）、`device.op.clear`、APP 断连全部在真实 APP 上验证。
2. **A/B 强度控制真实可用**：真实 APP 对每条 t=3 AddIntensity 返回 `reason=completed`，
   并通过 `slots.patch` 上报 `intensityA/intensityB`；实测 APP 上报值与发送侧估计值可以短暂不一致
   （波形包络 + 逐通道下发顺序），**不能把本地 estimate 当设备确认值**。
3. **E-STOP 语义已修正并真机验证**：A 归零 / B 归零 / clear 三条命令现在分别有独立 reqId 与回执状态；
   真实 APP 回执分别为 `cleared` / `cleared` / 无 reason（空 result），三条全部成功才算 `estopAppAcked`。
   复位 `REQUESTED → (等待 ACK) → COMPLETE` 实测约 1.1s，锁存清除、控制保持 DISARMED、Service/Relay 会话不重建。
4. **DEVICE 确认仍然不可得**：本工程没有 BLE 级停止/输出回执；`channelAStatus/BStatus` 等只是 APP
   上报的、可能过期的状态。E-STOP 的 `DEVICE UNVERIFIED` 语义保留。
5. **t=0 AppendPulseData 未实现、未验证（BLOCKED）**：公开资料支持 t=0（V2/V3 帧），但本工程设备控制层
   没有 AppendPulseData；按 Phase 3 安全约束未下发未知波形。Phase 4 需先完成合法帧来源 + 非人体负载下的
   t=0 最小协议验证。
6. **偶发「连接失败」根因**：不是 V4 握手缺陷；是 DG-LAB APP 的 V4 transport 在首次连接超时后
   用 `请求被拒绝，当前已经在做` 挡住重试（APP 自身日志），且该 APP 在 socket 断开后不会自动重连。
   详见 §10。

---

## 2. 设备与测试环境

| 项目 | 实际结果 |
|---|---|
| Android 手机型号 | Redmi K90 PM（`25102RKBEC`） |
| Android 版本 | Android 17 / API 37 |
| DG-LAB APP 版本 | 4.2.6（versionCode 62） |
| Coyote 型号 | Coyote 3.0（APP 上报 `type=COYOTE_030`，`name=COYOTE`） |
| Coyote 固件版本 | **未能读取**（APP `props.version=0`，语义不明；未做固件升级/读取） |
| 连接模式 | 同机：`ws://127.0.0.1:9998/?tid=32b5349c`（targetId 脱敏显示） |
| 实际测试环境 | 真实 DG-LAB APP + 真实 Coyote 3.0，**接虚拟输出（用户确认），非人体** |
| 中继/服务 | 本工程真实 ControllerService + 真实 V4 Relay，无 Fake APP |
| 测试时间 | 2026-11-20 22:21–22:38（Asia/Shanghai） |

真实 APP 上报的完整设备描述符（`devices.snapshot` 原文，见证据 §1）：

```json
[{"id":0,"slotId":"pVBWJ7Ve","name":"COYOTE","type":"COYOTE_030",
  "props":{"power":0,"version":0,"label":0,"intensityA":0,"intensityB":0,
           "connectState":"connected","channelAStatus":0,"channelBStatus":0,"updateValue":"0"},
  "slotState":{"markLight":"yellow","hasDevice":true,
    "channelA":{"comfortLimit":{"mode":"complex","comfortMax":80,"absoluteMax":100,
      "overheat":false,"overheatPercent":0,"autoIncr":false,"autoIncrMax":20,
      "autoIncrScope":3,"totalIncr":0},"intensityMax":100,"isMuted":false,"warmUpScale":0},
    "channelB":{...同构...}}}]
```

关键观察：
- `props.power=0`、`props.version=0` 与 APP 其他界面（电池图标）不一致 → **这两个字段当前不可作为
  设备电量/固件版本使用**（可能表示“充电中/未读取/占位 0”，公开资料未说明，不臆测）。
- `channelAStatus/BStatus=0` 按公开定义 = “无输出/过低”；结合当时输出为 0，语义合理，但仍只是 APP 状态。
- `slotState.channelA.isMuted`、`intensityMax`、`comfortLimit.*` 均真实上报；
  `comfortMax=80`、`absoluteMax=100` 与 APP 界面一致（A/B 舒适/绝对上限 80/100）。

---

## 3. 测试执行流程（实际跑通的顺序）

```text
COLD START（安装 APK / 启动 App）
  → PRE-FLIGHT（中继/模型/scaler/GNSS 通过；APP 未连接）
  → DG-LAB APP Socket 控制输入地址并连接
  → client_attached / devices.snapshot / devices.get resp   ← 真实 APP
  → OPERATION（RELAY/APP/DEVICE 三灯全绿）
  → 人工接管 WALKING（target=10）→ START
  → A/B t=3 AddIntensity + slots.patch 上报                  ← 真实 Coyote
  → STOP（A/B t=7 v=0，reason=completed）
  → E-STOP（A/B t=7 v=0 + clear；三条回执；APP 确认 已确认，DEVICE 无法验证）
  → RESET（REQUESTED→COMPLETE，约 1.1s；OPERATION RESTORED，DISARMED）
  → APP force-stop（1006；控制器归零、清空设备）
  → APP 手动重连（8ms 完成 101 握手；新 clientId）
```

---

## 4. 功能验证矩阵

结果定义：PASS / FAIL / BLOCKED / NOT TESTED。

| 项目 | 测试环境 | 结果 | 证据 |
|---|---|---|---|
| 中继连接（V4 Relay 监听/targetId/握手） | Real APP + Real Relay | PASS | 22:35:56 中继就绪；22:36:01 握手 `UA=okhttp/3.14.9`；`controller_attached` |
| 设备发现（devices.snapshot） | Real Device | PASS | 22:36:01 `COYOTE_030` + props/slotState 全文 |
| 设备注册/activeSlot | Real Device | PASS | OPERATION 设备灯绿；E-STOP 页“设备 已连接” |
| A 通道强度指令（t=3） | Real Device | PASS | 22:37:00–02 `c=0` 多条，`reason=completed`；slots.patch intensityA 变化 |
| B 通道强度指令（t=3） | Real Device | PASS | 22:37:00–02 `c=1` 多条，`reason=completed`；slots.patch intensityB 变化 |
| A/B 通道独立（无串扰） | Real Device | PASS（协议层） | 请求 `c` 与回执/上报逐条对应；A/B 上报值可不同（见 §5） |
| APP 强度上报（slots.patch） | Real APP | PASS | 真实 `{"props":{"intensityA":2,"intensityB":8}}` |
| 正常 STOP（A/B 归零） | Real Device | PASS | 22:37:16 `t=7 c=0/1 v=0`，均 `completed`；APP 侧 0 |
| E-STOP（命令链/锁存/优先队列） | Real Device | PASS | 22:37:25 A/B 归零 + clear 3 帧入队；APP ACK 已确认；锁存生效 |
| E-STOP APP ACK 语义（三条分别追踪） | Real APP | PASS | `cleared` / `cleared` / `-`；三条全 ACK 才 `estopAppAcked` |
| device.op.clear | Real APP | PASS | reqId=61 收到 resp（无 reason）；APP 清任务 |
| E-STOP 复位（RECOVERY） | Real APP + Real Device | PASS | 22:37:33 REQUESTED → 22:37:34 COMPLETE（约 1.1s）；OPERATION RESTORED，DISARMED |
| APP 断连（force-stop） | Real APP | PASS | 22:37:42 1006；控制器归零/清设备；服务 disarmed（原本已 DISARMED） |
| APP 重连 | Real APP | PASS（手动） | 手动“连接服务器”8ms 完成 101；新 clientId；targetId 不变 |
| 设备 BLE 断连/重连（slotId 变化） | Real Device | BLOCKED / NOT TESTED | 未主动断开 Coyote BLE（避免影响现场设备）；仅观察 APP force-stop 后 BLE 同时断开、APP 显示“无设备” |
| DEVICE 实际输出状态确认 | Real Device | NOT TESTED（无通道） | 本工程无 BLE 回执；`DEVICE UNVERIFIED` 保留 |
| t=0 波形兼容性 | Protocol Probe | BLOCKED | 工程未实现 AppendPulseData；无合法 V4 帧来源；未下发未知波形 |
| t=4 SetTempIntensity / t=5 SetMute | Protocol Probe | NOT TESTED | 未实现；t=5 公开资料未找到定义 |

---

## 5. 设备状态反馈能力审计（Stage 4 结论）

真实 APP 确实上报以下字段（`props` / `slotState`）：

| 字段 | 实测值示例 | 可否作为设备状态 | 说明 |
|---|---|---|---|
| `connectState` | `connected` | 可作 APP↔设备连接状态 | 断连时 APP 可能延迟更新；仍需以 `hasDevice` 交叉判断 |
| `intensityA/B` | 0 → 2 / 8 → … | **可作 APP 当前强度**，不可作 BLE 物理输出确认 | 通过 `slots.patch` 增量上报（本次约 300ms 级）；断连后可能保留旧值 |
| `power` | `0` | 当前不可用 | 与 APP 界面电池不一致；语义未明 |
| `version` | `0` | 当前不可用 | 非固件版本字符串 |
| `channelAStatus/BStatus` | `0` | 可作“APP 认为通道无输出”的辅助信息 | 定义 0=无输出/过低、1=开路、2=正常、3=输出故障、4=通道屏蔽；本次输出 0 时=0 |
| `slotState.hasDevice` | `true` | 可作 BLE 连接状态 | 与 `connectState` 联合使用 |
| `slotState.channelA/B.isMuted` | `false` | 可作静音状态 | 真实字段；本次未测试静音操作 |
| `slotState.channelA/B.comfortLimit` | comfortMax=80, absoluteMax=100 | APP 侧限幅配置 | 与 APP 界面一致；`AddIntensity` 会被舒适上限截断（历史风险 §9.2） |
| `markLight` | `yellow` | 仅指示灯颜色 | 无输出状态含义 |

**结论**：
1. `APP ACK` 只表示 APP 收到并处理了 RPC（`reason=completed/cleared` 或空 result），**不表示 BLE 设备物理停止**。
2. APP 上报强度可以用于 UI 显示“APP REPORTED INTENSITY”，但必须标注来源与新鲜度；本次
   A/B 上报值出现短暂不同（发送顺序、波形包络、APP 自身平滑共同造成），**不能反推设备真实输出相等**。
3. `channelAStatus/BStatus` 有潜力作为“通道状态”辅助显示，但本次未制造开路/故障场景验证，
   且字段来自 APP 而非 BLE 直读——**Phase 3 不据此修改 `DEVICE UNVERIFIED`**。
4. 本次未在 UI 增加新面板（Phase 3 约束）；`DglabDevice` 已扩展字段（power/version/
   channelAMuted/channelBMuted/channelAStatus/channelBStatus），供后续 Phase 使用。

---

## 6. STOP / E-STOP 语义审查与修正（Stage 3）

### 6.1 发现的问题（真实代码审查 + 公开协议对照）

1. **旧实现把任意一条 `device.op` resp 当成整条急停命令链的 ACK**（`estopAppAcked=true`），
   无法区分 A 归零 / B 归零 / clear，也没有处理 `reason=replaced/cancelled` 与错误响应。
2. `device.op` 的 resp 是**任务终态**才返回（可能延迟）；旧实现没有超时/断连语义，
   pending 超时只打日志，不会把“未确认”显式暴露。
3. 复位流程（`EstopResetPolicy`）依赖 `estopAppAcked` 清锁存，上述误判可能让“只发出一条命令”
   就被当作可复位。
4. **disarm 连续调用两次 `resetIntensity(A)` / `(B)`**：第二次 `submitPriority` 会清空写线程还没取走的
   A 通道 t=7 帧，而 estimate 已被置 0 → ticker 不会补发，存在“本地显示 0、设备仍非 0”的窗口。
5. **半开旧连接 + APP 新 clientId 重连**时，服务只用 `appConnected` 边沿 disarm；`appIds` 始终非空
   会让旧 ARMED 会话延续到新连接（用户没再点 START）。
6. 重复 E-STOP（复位重发/引擎锁存重放）时，旧命令链的 pending 未作废，迟到响应可能污染新链。

### 6.2 本次修改

| 文件 | 修改 |
|---|---|
| `dglab/DglabLinkApi.kt` | 新增 `EstopCommandKind{A_ZERO,B_ZERO,CLEAR}` / `EstopCommandAckState{PENDING,ACKED,UNCONFIRMED}` / `EstopCommandResult`；`DglabState.estopCommandResults`；`DglabDevice` 设备状态字段；`resetAllIntensities()` 默认实现 |
| `dglab/V4Controller.kt` | 急停命令链逐条追踪（pending → ACKED/UNCONFIRMED）；reason 白名单（无 reason/`completed`/`cleared`=成功，其余=UNCONFIRMED）；超时/断连显式 UNCONFIRMED；重复急停作废旧链 pending；`resetAllIntensities()` 单批优先入队；`enqueuePriorityLocked` 失败回滚 pending；首帧 `devices.snapshot`/`slots.patch` 原文诊断日志（截断） |
| `dglab/DglabDeviceRegistry.kt` | `summary()` 解析 `power/version/channelAStatus/channelBStatus/channelA.isMuted/channelB.isMuted`；未上报保持 null（不伪造 false/0） |
| `dglab/V4Link.kt` | 转发 `resetAllIntensities()` |
| `service/ControllerService.kt` | disarm 改用 `resetAllIntensities()`；APP clientId 集合变化（半开重连/新会话）强制 DISARM |
| 测试 | `FakeDglabApp` 增加 `reply/replyReason/replyError`；`V4ControllerEstopPhaseTest` 新增 5 个回归（三条全 ACK、replaced/error、超时、断连、重复急停旧链、单批双通道归零）并修改原 ACK 用例；`DglabDeviceRegistryTest` 新增 2 个字段解析用例 |

### 6.3 真实回执语义（本次实测）

| 命令 | reqId | 真实回执 |
|---|---|---|
| A 归零 `device.op t=7 c=0 v=0` | 59 | `reason=cleared` |
| B 归零 `device.op t=7 c=1 v=0` | 60 | `reason=cleared` |
| clear `device.op.clear` | 61 | 无 reason（空 result） |

即：真实 APP 不保证 `completed`，对 t=7 归零可能返回 `cleared`；对 clear RPC 返回空 result。
“无 reason”必须视为成功语义，否则真实设备永远无法完成复位。

---

## 7. 断连与恢复（Stage 5）

| 场景 | 实测 | 结论 |
|---|---|---|
| DG-LAB APP 被 force-stop | 22:37:42 `code=1006`；控制器归零、清空设备；target/estimate/reported 清理；服务处于 DISARMED | PASS |
| APP 重启后自动重连 | **不会**自动重连 socket（观察 6s+ 无尝试）；需用户手动“连接服务器/输入地址” | APP 行为，记为已知限制 |
| 手动重连 | 8ms 完成 101 握手；`assigned clientId: e29dab72`（新 clientId）；`controller attached`；targetId 保持 32b5349c | PASS |
| APP 重启后 BLE 设备 | APP 显示“无设备 请先连接设备”，需在 APP 内重新连接 Coyote；重启后约 1 分钟设备自动恢复连接（观察到设备卡片重新出现） | APP 行为 |
| 重连后旧状态 | `resetSessionLocked` 清 registry/slotOwners/activeSlot/estimate/target；reported=null；新 clientId 不复用旧会话 | PASS（代码 + 单测） |
| 半开旧连接重连 | 服务新增 clientId 集合变化检测 → 强制 DISARM（未在真机复现半开，代码审查修复） | 风险降低 |

**未验证**：真实 BLE 断连/重连时 `devices.patch`/`slots.patch` 的完整序列、slotId 变化后的 activeSlot
切换（本次未主动断开 Coyote BLE）。

---

## 8. V4 协议能力结论（Stage 6，为 Phase 4 提供依据）

| 能力 | 公开协议 | 本工程实现 | 真实 APP/设备验证 |
|---|---|---|---|
| `devices.get` / `devices.snapshot` / `devices.patch` / `slots.patch` | 有 | 有 | snapshot/slots.patch PASS；patch 未制造场景 |
| `device.op` t=3 AddIntensity | 有 | 有 | PASS（真实回执 completed） |
| `device.op` t=7 SetIntensity v=0 | 有（只接受 0） | 有 | PASS（回执 cleared） |
| `device.op.clear` | 有 | 有 | PASS（空 result） |
| `custom.action` | 有 | 只记录日志 | 未验证 |
| `device.op` t=0 AppendPulseData | 有（V2/V3 帧） | **无** | **BLOCKED** |
| `device.op` t=4 SetTempIntensity | 有 | 无 | NOT TESTED |
| `device.op` t=5 SetMute | 公开资料未找到 | 无 | NOT TESTED |
| 波形任务进度/终态反馈 | 仅终态 reason + slots.patch | — | BLOCKED（无实现） |
| V2/V3 帧逐字段语义 | 部分公开（未完整） | — | BLOCKED |

**Phase 4 准入结论**：**可以进入 Phase 4 方案设计，但不能直接进入设备波形系统开发**。
前置条件（按优先级）：
1. 确定合法且可再分发的 V4 帧来源（dglab-kit 为 GPL-3.0，不可直接复制其波形库；
   官方 BLE 仓库商用需授权）——建议自行按官方“Understanding Coyote Pulses”构造帧，或取得授权；
2. 在非人体/虚拟负载条件完成 t=0 最小协议验证（单任务、低强度、`device.op.clear` 收尾），
   记录真实 APP 的 resp reason 与 slots.patch；
3. 确认 COYOTE_020（V2）与 COYOTE_030（V3）的帧格式选择与 8 元帧语义；
4. 明确波形任务与现有强度包络（`WaveformEngine`）的互斥/叠加规则，避免与 t=3 强度闭环冲突。

本阶段明确**未做**：波形 Profile/UI/自动规则/随机编排/替换 WaveformEngine/修改包络公式。

---

## 9. 代码交付与测试

- 修改文件：见 §6.2；未改 UI 布局、主题、映射公式、safetyMax/slew/EMA、WaveformEngine 算法。
- 全量 JVM 单测：**244 个，0 失败**（原 237 + 新增 7）。新增覆盖：
  - 急停三条命令必须全部 ACK（单条/两条 resp 不算 ACK）；
  - `reason=replaced`/错误响应 → UNCONFIRMED，不冒充成功；
  - 超时（TTL）→ 三条全部 UNCONFIRMED，`estopAppAcked=false`；
  - APP 断连时未回执命令 → UNCONFIRMED，锁存保持；
  - 重复 E-STOP 时旧链迟到 resp 不得结算新链；
  - `resetAllIntensities` 单批下发 A/B 两条 t=7 v=0；
  - 设备 props/slotState 真实字段解析（power/version/channelStatus/isMuted）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`（65.45MB，arm64-v8a），已安装到 Redmi K90 PM。
- 真实设备证据：`docs/phase3-evidence-2026-11-20/`（logcat 原文摘要 + 协议调研）；截图已按用户要求删除。
- 真实设备测试与 Fake APP 测试严格区分：本报告 §4 中标 Real APP/Real Device 的行均来自真机；
  Fake APP 仅用于 JVM 回归（Phase 2 的历史结果不复用）。

---

## 10. 偶发「连接失败」事件分析（本次现场问题）

**现象**：DG-LAB APP 弹窗「连接失败 — 该二维码/服务器地址连接失败，请重新扫描二维码或输入正确的
服务器地址」；点“重新连接/再点一次”后可恢复。

**时间线**（详见 `docs/phase3-evidence-2026-11-20/app-reconnect-incident.log`）：
1. 22:27:43 本 App 被 `adb install -r` 重装；22:27:46 冷启动，relay 正常监听 9998。
2. 22:28:02 用户切到 DG-LAB APP（本 App 退后台）；APP 发起 Socket 连接。
3. 22:28:19.994 APP 日志 `connection timed out`（首次尝试超时）。
4. 22:28:21.730 APP 再次 `connecting to ws://127.0.0.1:9998/?tid=...`；
   22:28:21.738 APP 本地直接报 `connection error 请求被拒绝，当前已经在做`——**重试没有到达 relay**。
5. relay 侧：该连接 TCP 已建立并收到 236 字节 HTTP Upgrade 请求，但应用层约 58s 未读取
   （`CLOSE-WAIT 236`）；直到 22:29:19.735 本 App 被切回前台时才处理，握手完成后立即 1006
   （客户端早已放弃）。
6. 22:32:14 用户手动连接成功：8ms 完成 101 握手，`clientId=e29dab72`。

**根因判断**：
1. **直接原因（APP 侧）**：DG-LAB APP 的 V4 transport 在第一次连接超时后，其内部“连接进行中”状态没有
   及时复位，后续重试被本地拒绝（APP 自己日志 `请求被拒绝，当前已经在做`），因此表现为“连接失败”且
   必须手动重试；该 APP 在 socket 1006 后也不会自动重连（本次观察）。
2. **首次超时的诱因（本 App 侧，待进一步确认）**：在本 App 重装/冷启动后立即退后台的窗口内，
   其 loopback 连接处理出现一次约 58s 停顿（请求已 accept 未读取，切回前台后恢复）。
   随后 45 次探针（包含后台状态）均 57–97ms 完成握手，未能复现；无 ANR/崩溃，进程状态为
   foreground-service（curProcState=4、cached=false）。倾向为系统级后台调度/初始化停顿
   （MIUI 对刚安装应用的冻结/限流或 ART 初始化），而非 V4 协议缺陷。
3. 协议实现本身正常：`UA=okhttp/3.14.9 Connection=Upgrade; close` + 101 握手 + `controller_attached`
   在 relay 可用时 6–8ms 完成（§3.2 的握手头修复仍然有效）。

**规避/建议**：
- 用户侧：出现弹窗时点「重新连接」即可；避免在本 App 刚安装/刚启动的几秒内去连 Socket；
  可把本 App 加入 MIUI「无限制/自启动」白名单，减少后台冻结。
- 工程侧：保持 targetId 持久化（已实现）与 9998 固定端口 + 绑定重试（已实现）；
  不建议为此把 relay 变成前台可见页面或常驻 wakelock。
- 后续若仍频发：增加“relay 响应自检”（后台线程每 5s loopback 握一次，记录延迟与失败并在 ENGINEERING
  显示），用于区分系统冻结 vs 应用线程问题；并把该事件写入故障记录（AGENTS §10）。

---

## 11. 未解决问题 / 风险清单

1. **1:N（多 APP）语义仍不完整**：任一 APP 的 `devices.snapshot` 会整体替换全局 device registry 并重建
   slotOwners；owner 未知时 `device.op` 会广播兜底。单机 1:1 已真实验证；双机/多 APP 现场仍需专项设计
   （Phase 3 未做大重构）。
2. `devices.get` 只在 attach 后发送一次，没有超时重发；若首帧 resp 丢失，可能表现为“APP 已连但设备
   列表为空”。本次真机未复现，记为风险（§10 同类）。
3. E-STOP 复用 `emergencyStop()` 做复位前“补发停止命令”会重置命令链追踪；已通过作废旧链 pending
   降低误判，但更严格的做法是引入 chainId/reqId 绑定（Phase 4 可做）。
4. 正常 STOP 只发 A/B t=7 v=0，不发 `device.op.clear`（本工程只用 oneShot t=3/t=7；无持续任务）。
   若未来引入 t=0/t=4 持续任务，STOP 必须同时 clear。
5. 真实 BLE 设备被 APP force-stop 后的设备重连、slotId 变化、`devices.patch` 序列未测试；
   E-STOP 设备端物理停止确认仍无通道（UI 永远 `DEVICE UNVERIFIED`）。
6. 本次未验证 `t=0`；波形能力结论只能到“公开协议支持、本工程未实现”层。
7. 未做性能 profile / TalkBack / 小屏横屏回归（Phase 3 范围外）。

---

## 12. 用户现场可复现的最小步骤（真实 APP + 真设备）

1. 安装 `app-debug.apk`，保持本 App 前台，确认 PRE-FLIGHT 中继监听（记下 tid）。
2. 打开 DG-LAB APP → Socket 控制 → 输入 `ws://127.0.0.1:9998/?tid=<tid>` → 确定；
   本 App PRE-FLIGHT 应显示 APP/设备已连接。
3. 本 App 进入 OPERATION → 人工接管（选任一非 STATIONARY 类，如 WALKING）→ START；
   DG-LAB APP 与设备应出现强度变化（虚拟输出安全条件）。
4. STOP：本 App 回到待机，APP 强度归零。
5. E-STOP：进入紧急页，停止命令→控制帧→APP 确认 均应绿色；“设备确认”永远“无法验证”。
6. RESET：确认对话框 → 复位 → RECOVERY CHECK → OPERATION RESTORED（DISARMED，需重新 START）。
7. 断开测试：force-stop DG-LAB APP → 本 App 归零/显示 APP 未连接；重开 APP 手动连接 → 新 clientId，
   本 App 仍 DISARMED、target 0。

---

## 13. 许可证说明

- 本工程为 MIT；Phase 3 **没有**变更项目许可证。
- `dglab-kit` 为 GPL-3.0：本次只读其公开 README 协议资料并独立实现，**未复制代码/波形库**。
- DG-LAB 官方 Bluetooth Protocol 仓库为“公开文档 + 商用需授权”条款，非 OSI 开源；
  Phase 4 引入波形帧数据前必须确认授权与再分发条件（见 `protocol-research-dglab-kit.md` §5）。
- 本次未引入任何第三方 SDK/波形预设。

---

## 14. 证据文件

- `docs/phase3-evidence-2026-11-20/real-device-v4-logcat.log` — 真实 APP/Coyote 链路 logcat 摘要。
- `docs/phase3-evidence-2026-11-20/app-reconnect-incident.log` — 「连接失败」事件两侧日志与时间线。
- `docs/phase3-evidence-2026-11-20/protocol-research-dglab-kit.md` — 公开协议调研（含 GPL/商用限制）。
- （截图目录 `docs/screenshots/phase3-2026-11-20/` 已按用户要求于 2026-11-20 删除，不再保留。）
