# E-STOP Recovery & Transition（2026-11-15）

> 本轮只修两件事：**SYSTEM REINITIALIZING 转场错位/重影** 与 **复位后自动恢复 OPERATION（保留原会话）**。
> 不重设计普通控制界面；不改 MotionPipeline / MotionIntensityMapper / WaveformEngine / V4 协议 /
> OutboundWriter / 强度映射公式 / safetyMax / slew / 急停锁存状态机。
>
> 验证：真机 Redmi K90 PM（Android 17 / API 37 / 1200×2608 / 三键 + 手势导航 / 1.3× 字体 /
> 减少动态效果）+ 本地假 DG-LAB APP（真实中继；真实 BLE 设备端仍未联调）。
> **JVM 测试 238 个，0 失败；APK 64.92 MiB，仅 arm64-v8a。**
>
> 截图与录屏：`docs/screenshots/estop-recovery-2026-11-15/`。

---

## 1. 错位/重影的实际根因

旧实现（`EstopExitContent`）的问题不是 Insets，也不是简单 offset：

1. **文字重影**：同一个中央区域里用了两个 `Crossfade`（tag 一个、title/body 一个），
   `Crossfade` 在 300–320ms 内会**同时组合并绘制旧内容和新内容**，并且两者的目标状态不完全同步
   （tag 用 `cleared`，标题用 `phase`）。所以出现 `E-STOP CLEARED` 与 `SYSTEM REINITIALIZING`、
   中文与英文标题同时叠在屏幕上的现象。
2. **恢复文字位置偏移 / 上方异常空白**：退出内容被放在 `EmergencyBorder` 的 content 槽里，
   而边框的顶部/底部警报条高度、左右栏宽度在 `collapse` 动画中持续变化 → 中央内容所在的布局槽
   跟着变大/移动，导致文字在转场中整体位移、上方出现大块空白。
3. **红黑边框尚未退出，蓝色已出现**：边框 collapse（旧 520ms）与背景色动画（旧 620ms）由
   MainActivity 同时启动；两个独立计时器叠加，转场中必然出现“红框 + 深蓝背景”。
4. **两个全屏页面并未同时渲染**；重影来自 Crossfade 层，位置问题来自边框布局槽，
   颜色割裂来自独立计时器。修复不能靠 padding/offset，必须改页面结构和转场协调方式。

---

## 2. 修改的文件及原因

| 文件 | 原因 |
|---|---|
| `ui/emergency/EstopRecoveryPhase.kt`（新增，替代 `EstopExitPhase.kt`） | 明确四阶段纯 UI 状态：`NONE/RELEASED/CHECKING/RESTORED` |
| `ui/emergency/EmergencyScreen.kt` | 删除全部退出 Crossfade；新增 `released` 稳定层，`E-STOP RELEASED` 内容独立于边框布局 |
| `ui/emergency/EmergencyBorder.kt` | 新增 `collapseDurationMs`，转场期边框快速收缩；只做装饰层，不承载恢复内容 |
| `ui/emergency/RecoveryScreen.kt`（新增） | RECOVERY CHECK 全屏稳定页面（真实检查项）；`CONTROL SESSION RESTORED` 内联提示 |
| `ui/model/RecoveryModel.kt`（新增） | 复用 `PreflightModel` 的既有门禁 + 控制会话检查；只读 UiState，不重启服务 |
| `MainActivity.kt` | 新的恢复状态机、单计时器协调、系统栏颜色、成功回 OPERATION / 失败回 PRE-FLIGHT、进程重建路由 |
| `ui/operation/OperationScreen.kt` | 可选 `recoveryBanner` 内联提示；`estopDeviceStopUnverified` 紧凑警告 Banner 持续到重新 START |
| `ui/i18n/UiStrings.kt`、`ZhUiStrings.kt`、`EnUiStrings.kt` | 更新 E-STOP RELEASED / SYSTEM REINITIALIZING / CONTROL SESSION RESTORED / 恢复检查文案 |
| `service/ControllerService.kt` | **仅修改一条日志文案**（PRE-FLIGHT → OPERATION），没有改复位/安全逻辑 |
| `app/src/test/.../RecoveryModelTest.kt`（新增 6 个） | 覆盖有效会话、APP 断连、设备未连接、UNVERIFIED、armed/latched、服务/模型未就绪 |

**未改**：ControllerService 的复位流程与锁存策略、V4 中继/WebSocket、targetId、映射公式、
波形、安全上限、slew、MotionPipeline。

---

## 3. 新的页面转场设计

### 3.1 状态机（纯 UI）

```text
E-STOP LATCHED
    │ Service 确认 emergencyStopped=true
    ▼
EMERGENCY MODE（原页面，最高优先级）
    │ Service: estopResetPhase=NONE && estopResetCompletedAtMs>0 && emergencyStopped=false
    ▼
RELEASED (280ms)
    │ 边框 collapse 240ms；marquee 冻结；背景保持 EmergencyBlack
    │ 中央稳定层：E-STOP RELEASED / 急停锁存已解除 / CONTROL OUTPUT REMAINS DISARMED
    ▼
CHECKING（真实检查，至少 450ms，最多等待 2.6s）
    │ 边框已完全销毁；POLAR BLUE 全屏稳定根布局
    │ 8 项现有会话检查（真实 UiState）
    │ 通过 → RESTORED；不通过 → PRE-FLIGHT（不伪造 CONNECTED）
    ▼
RESTORED（约 900ms 提示）
    │ OPERATION 正常页面 + 内联 CONTROL SESSION RESTORED Banner
    │ 控制 DISARMED / 目标 0；设备 UNVERIFIED 警告保留
    ▼
NONE（普通页面，等待用户主动 START）
```

- 转场只有**一个协调者**：MainActivity 的 `LaunchedEffect`。SystemBar、Border、RecoveryScreen、
  OperationScreen 都读取同一个 `EstopRecoveryPhase`，没有各自计时。
- 页面是 `when` 精确切换：同一时刻只渲染一个页面；**没有任何 Crossfade 保留旧/新页面**。
- 系统栏与根背景共用同一个 `systemBarSurface` 动画色：RELEASED 为 EmergencyBlack，
  CHECKING 起才过渡到 `#203348`；边框在进入 CHECKING 前已经销毁。
- 减少动态效果：不进入 RELEASED/CHECKING；复位成功后直接按真实检查结果切换 OPERATION / PRE-FLIGHT。

### 3.2 为什么不再出现错位/重影

| 旧问题 | 新做法 |
|---|---|
| Crossfade 双份文字 | 删除 Crossfade；阶段切换只有一份内容 |
| 内容跟随边框高度移动 | RELEASED 内容作为**独立全屏 sibling 层**，不参与 Border 布局 |
| 上下大空白 | 恢复页使用 `fillMaxSize` 的固定纵向布局：标题区 → 检查列表 → 警告，列表可滚动 |
| 红框与蓝底重叠 | RELEASED 期间底色黑；进入 CHECKING 才切换蓝色，此时 Border 已不在组合树中 |
| 系统栏单独计时 | 系统栏颜色由同一 recovery phase 驱动 |
| 中央内容与背景不同步 | 两者使用同一个 `systemBarSurface` 色值 |

---

## 4. 复位后页面恢复逻辑

- **成功条件**（全部满足）：
  1. `emergencyStopped == false`；
  2. `estopResetPhase == NONE`；
  3. `estopResetCompletedAtMs > 0`；
  4. 本进程曾观察到锁存（`estopWasLatched`，仅 `remember`，不写 savedInstanceState）；
  5. `RecoveryModel.canRestore == true`。
- `RecoveryModel` 复用 `PreflightModel`：
  - 关键项：本地服务、V4 中继、运动模型、Scaler、DG-LAB APP（既有门禁）；
  - 非关键项：GNSS、设备连接、E-STOP RECOVERY（`DEVICE UNVERIFIED`）；
  - 额外关键项：**控制会话必须 DISARMED**（防止异常 armed 时恢复）。
- `DEVICE UNVERIFIED` 表示**缺少 BLE 级停止回执，不等于 APP 断连**：不阻塞恢复 OPERATION；
  但警告必须保留，并且 START 仍会按真实门禁阻塞。
- 检查失败：
  - 不进入 OPERATION、不显示虚假 CONNECTED；
  - 不重置配对信息、不重启服务/Relay；
  - 显示真实失败项（恢复页）后回 **PRE-FLIGHT**，由既有重连/重试入口处理。
- 服务/模型/Scaler 失败：恢复页显示 FAIL/WAITING，回 PRE-FLIGHT；不能进入可启动的 READY。
- 进程重建：
  - 锁存仍为 true → 直接 Emergency（黑系统栏）；
  - 锁存 false 且保存页面为 EMERGENCY → 等服务稳定后按 `RecoveryModel` 决定 OPERATION/PRE-FLIGHT，
    **不播放转场、不依据缓存推断**。
- 复位成功不会自动 START；不会发送非零目标；不会恢复原 RUNNING 状态。

---

## 5. 原连接会话保持情况

- `requestEmergencyReset` 成功后 Service 只做：清锁存、`armed=false`、目标/当前强度 0、
  `manualClass=null`、`waveActive=false`、写 `completedAt`；**不重建 ControllerService、
  不重启 Relay、不重建 WebSocket、不改 targetId、不重新握手**。
- 本轮真机成功复位流程采样：
  - 复位前/后 service PID 均为 **20402**（未重建）；
  - 恢复检查显示 `tid 32b5349c` 不变；
  - DG-LAB APP 仍显示 `已接入客户端 · 1`，Fake APP 连接未断开；
  - `logcat` 仅见 `E-STOP RESET COMPLETE`，无 `startEngine/engine started/relay started`。
- 失败/断连时：锁存保持；RecoveryModel 显示 APP DISCONNECTED；回 PRE-FLIGHT 使用既有连接恢复入口，
  不会自动重连或覆盖用户配对信息。
- 真机进程重建测试中 APP 连接会随 Android 强制停止应用而断开，这是系统行为；
  正常复位路径不会触发服务或连接重建。

---

## 6. APK 构建结果

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：**64.92 MiB（68,075,122 bytes）**
- ABI：**仅 `arm64-v8a`**（`libandroidx.graphics.path.so` / `libonnxruntime*.so`）
- 未新增依赖；未修改 Gradle 业务配置。

---

## 7. JVM 测试结果

```text
:app:testDebugUnitTest
tests = 238
failures = 0
```

新增 `RecoveryModelTest` 6 个：
- 有效会话 + DISARMED → 可以恢复；
- APP 断连 → 拒绝并给出阻塞项；
- 设备未连接不阻塞（与既有 PRE-FLIGHT 门禁一致）；
- `DEVICE UNVERIFIED` 不等于连接失败；
- armed / 急停未解除 → 拒绝恢复；
- 服务未绑定 / 模型未就绪 → WAITING，不恢复。

---

## 8. 真机完整流程测试

环境：Redmi K90 PM / Android 17 / API 37 / 真实 V4 中继 + 假 DG-LAB APP（FAKE-COYOTE）。
**真实 BLE 设备端未联调。**

| 场景 | 结果 |
|---|---|
| 冷启动 → PRE-FLIGHT → APP 连接 → OPERATION → START → RUNNING | ✅ |
| RUNNING → E-STOP → 全屏 Emergency（黑系统栏） | ✅ |
| RESET 请求 → RESETTING → SELF_CHECK | ✅ 期间不退出、不播转场 |
| 复位成功 → E-STOP RELEASED → RECOVERY CHECK → OPERATION RESTORED → STANDBY | ✅ 无重影、无偏移、无异常空白、红框先退出 |
| 原连接保持（PID/tid/APP/Relay） | ✅ PID 20402 不变，tid `32b5349c`，APP 连接不重握手 |
| 控制状态 | ✅ DISARMED、目标/实际 0、未自动 START |
| 用户再次 START → RUNNING | ✅ |
| 复位失败（`--no-ack` Fake APP） | ✅ 停留 Emergency，失败卡片，黑系统栏，无退出动画 |
| 进程重建（锁存中） | ✅ 恢复 Emergency；APP 重连后复位 → 恢复 OPERATION |
| 减少动态效果（scale=0） | ✅ 复位成功直接切 OPERATION，无 RELEASED/CHECKING 动画 |
| 手势导航 | ✅ 恢复检查/OPERATION 均避让手势条，系统栏同步 |
| 三键导航 | ✅ 同上，系统栏/导航栏同步 |
| 1.3× 字体 | ✅ E-STOP 页可滚动；恢复 Banner 已改为纵向布局，不再与标题重叠/裁切 |
| 系统栏颜色 | ✅ RELEASED 三处 `(5,5,6)`；CHECKING/OPERATION 三处 `(36,50,70)`（真机面板色域转换） |

---

## 9. 截图与录屏

目录：`docs/screenshots/estop-recovery-2026-11-15/`

1. `01_preflight.png`
2. `02_operation_running.png`
3. `03_estop_latched.png`
4. `04_reset_dialog.png`
5. `05_estop_released.png` — E-STOP RELEASED（无边框、黑背景）
6. `06_recovery_check.png` — SYSTEM REINITIALIZING（8/8 真实检查）
7. `07_operation_restored_banner.png` — CONTROL SESSION RESTORED + 急停恢复警告
8. `08_operation_standby.png` — DISARMED STANDBY（警告保留）
9. `09_user_started_again.png` — 用户主动 START 后 RUNNING
10. `10_reset_failed_emergency.png` — 复位失败仍保持 Emergency
11. `11_process_rebuild_emergency.png` — 进程重建恢复急停
12. `12_gesture_recovery_check.png` — 手势导航恢复检查
13. `13_gesture_operation_restored.png` — 手势导航恢复 OPERATION
14. `14_font13_restored_banner.png` — 1.3× 字体下 Banner 不重叠
15. `15_font13_emergency_scrolled.png` — 1.3× 字体急停页滚动
16. `estop_recovery_final.mp4` — 真机完整恢复录屏

---

## 10. 未解决的问题 / 限制

1. **真实 BLE 设备端未联调**：仍是真实中继 + 假 APP；设备是否物理停止无法通过 BLE 验证，
   `DEVICE UNVERIFIED` 保留。
2. 恢复检查是**一次现有会话快照 + 最多 2.6s 轮询**；检查失败后自动回 PRE-FLIGHT，
   若 APP 在超时后恰好恢复连接，需要用户在 PRE-FLIGHT 再进入（不会自动跳回 OPERATION）。
3. 减少动态效果仍遵循既有 `rememberReduceMotion`：只在 App 启动时读取，运行中改系统设置需重启应用。
4. 1.3× 字体下 OPERATION 普通卡片存在既有的文字裁切（如 `STATIONARY`、START 按钮副标题），
   本轮只修复了新增的恢复 Banner，未改造普通界面排版。
5. 未增加 Compose instrumented UI 测试；验证依赖 JVM 状态测试 + 真机截图/录屏。
6. 不同 OEM 的状态栏/导航栏策略仍可能不同；本轮只在 Redmi K90 PM（API 37）验证。
