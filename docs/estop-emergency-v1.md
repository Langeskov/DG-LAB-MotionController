# MotionController V4 — E-STOP Emergency Interface Redesign V1（设计 + 交付报告）

> 状态：第一版完成（全屏 Emergency Mode + 动态红黑警戒边框 + 停止命令链 + 复位流程 + 双层控制锁定）。
> 验收环境：JVM 单测 221 个全绿；Android 37 x86_64 模拟器逐场景验证；**真机 Redmi K90 PM（Android 17）
> 安装 arm64 APK 后实测 E-STOP 触发 → 全屏紧急页 → 复位 → 回到 PRE-FLIGHT 全流程通过**。
> 截图见 `docs/screenshots/estop-2026-11-02/`（含真机 4 张）。

---

## Phase 1：现有项目审查（改造前）

### 1.1 当前急停实现

| 层 | 改造前实现 | 问题 |
|---|---|---|
| 触发入口 | `OperationScreen.EmergencyStopBar`（页面底部红色条） | 急停只是普通页面的一个组件，锁存后仍停留在控制页 |
| 后端触发 | `ControllerService.emergencyStop()`：mapper.reset、`link.emergencyStop()`（A/B `t=7 v=0` + clear，优先队列）、UiState 置 `emergencyStopped` | 锁存只在内存；进程被杀后丢失 |
| 控制器锁存 | `V4Controller.emergencyStopped`，`setTargetIntensity` 在锁存时拒绝非零 | 正确 |
| 状态反馈 | `DglabState.estopIssued / estopFramesQueued / estopAppAcked / estopAckReason` | 已有三阶段，但 UI 嵌在普通页面小面板里 |
| 复位 | `clearEmergencyStop()`：`link.clearEmergencyStop()` + 直接清 `UiState.emergencyStopped` | 同步、立即清锁存；不检查 APP ACK/自检；不走“请求→复位→自检”流程 |
| 普通控制锁定 | UI 上 START 按钮因 `emergencyStopped` 变灰；`ControllerService.arm()` 仅检查 `link.controlAvailable()`（控制器锁存时拒绝） | Service 层没有“锁存则拒绝 arm/改映射/改波形”的显式守卫；理论上可被其它入口调用 |
| 导航 | `MainActivity` 只有 PREFLIGHT / OPERATION / ENGINEERING 三页；无 Emergency 路由；返回键可离开锁存中的控制页 | 急停不能“占据整个应用” |
| 持久化 | 无 | 进程重建 / 应用重新打开会丢失锁存 |

### 1.2 现有数据来源（复用，未改协议）

- 控制器锁存：`DglabState.emergencyStopped`；
- 命令链：`estopIssued / estopFramesQueued / estopAppAcked / estopAckReason / lastOpResponseReason / lastError`；
- 链路：`relayRunning / appConnected / deviceReady / activeSlotId`；
- 识别自检：`MotionPipelineStatus.modelLoaded / scalerValid`；
- 强度三态：`targetIntensityA / estimateIntensityA / reportedIntensityA`。

**结论：全部状态都可从现有 `UiState` 派生，不需要改 WebSocket 协议或控制算法。**

### 1.3 可复用组件

`ui/theme`（McColors/McType/cjkTuned）、`ui/components`（McPanel/McTag/StatusLamp/WarningBanner/TacticalButton/McConfirmDialog/
EmergencyStopGlyph/rememberReduceMotion）、`ui/i18n`（UiStrings/Zh/En/LanguageMode）、`MotionDisplay/ControlModel` 等纯派生层。

### 1.4 发现的接口与安全缺口（本次已处理 / 已报告）

| # | 缺口 | 处理 |
|---|---|---|
| 1 | 复位直接清锁存，不确认停止命令送达 | 新增 `ControllerService.requestEmergencyReset()` 异步状态机：链路检查 → 补发停止命令 → 等 APP ACK（3s 超时）→ 启动自检 → 才清锁存 |
| 2 | 锁存不持久化，进程死亡丢失 | `estop_latched` 写入 SharedPreferences；`onCreate` 恢复并在引擎启动时重新置控制器锁存 |
| 3 | Service 层缺少锁存态守卫 | `arm / overrideMotionClass / returnToAuto / setManualOffset / updateMappingConfig / setWaveConfig` 在锁存时显式拒绝并打日志 |
| 4 | UI 没有独立紧急页面，返回键可退出 | 新增 `Screen.EMERGENCY`；锁存为最高优先级渲染；返回键被消费，只能通过复位离开 |
| 5 | 设备端停止确认缺失 | 如实保留 `DEVICE UNVERIFIED`；复位成功也不宣称设备已停止，PRE-FLIGHT 持续提示 |
| 6 | 重复急停/复位竞态 | 控制器 `emergencyStop()` 幂等；复位用令牌 + 单飞（in-flight）守卫，新急停使旧复位静默作废 |

---

## Phase 2：设计与交互

### 2.1 视觉

- 页面底色 `McColors.EmergencyBlack`，面板 `McColors.EmergencyPanel`，标准红 `Red`、亮红 `BrightRed`、暗红 `RedDim`。
- 三红色层次：暗红（边框底/面板）、标准红（边框/标题）、亮红（E-STOP 状态/动态强调）。
- **没有红白警示条纹**、没有全屏高亮红、无霓虹/高频闪烁；中央状态文字静止，动画只在装饰边框。
- 顶部/底部横向滚动警报条（顶部 `/// 紧急停机 ///…`、底部 `/// 急停锁存 /// 需要复位 ///`），左右 16dp 纵向
  Canvas 警戒图案（短线段 + chevron + E-STOP 方块），350ms 展开动画；`reduceMotion` 时全部退化为静态。
- CJK 排版走 `cjkTuned`，中文不做 uppercase。

### 2.2 页面结构

```
EmergencyBorder（动态边框，独立占位不覆盖内容）
└─ 顶部标题行：紧急停机 + E-STOP 标签 + APP/DEV 灯
   └─ 可滚动中央：
      1. 标题：/// 紧急停机 /// + 紧急停机已触发 + 急停锁存 + 控制会话已暂停
      2. 停止命令链：停止命令 → 控制帧 → APP 确认 → 设备确认（逐节点真实状态）
      3. 设备停机状态无法验证 警告（BLE 无回执）
      4. 链路状态：中继/APP/设备 + 强度 A（目标/估计/APP 上报）
      5. 普通操作已锁定（说明 START/接管/强度/波形/配置不可用）
      6. 复位区：RESET E-STOP（二次确认）/ 复位进度+自检清单 / 失败原因+重试 /
         链路恢复操作（重试中继、补发停止命令）
```

### 2.3 复位状态机（Service 为唯一真实来源）

```
NONE ──requestEmergencyReset()──▶ REQUESTED
REQUESTED ──(550ms 阶段停留)──▶ RESETTING
RESETTING：链路检查(服务/中继/APP/设备)
            ├─ 失败 → FAILED（锁存保持）
            └─ APP 未回执/帧未入队 → 补发 A/B 归零+clear → 等 ACK（≤3s）
                   ├─ 超时 → FAILED(STOP_COMMAND_NOT_ACKED)
                   └─ ACK → SELF_CHECK
SELF_CHECK：模型/scaler + 再次链路检查 + canClearLatch
            ├─ 失败 → FAILED（锁存保持）
            └─ 通过 → 清锁存 → NONE（UiState.emergencyStopped=false）
                       → PRE-FLIGHT（不自动启动、不发送新的输出指令）
```

- 复位失败**绝不清锁存**；失败原因通过枚举 → Zh/En 文案展示。
- 复位成功后 `estopResetCompletedAtMs`、`estopDeviceStopUnverified=true` 保留；PRE-FLIGHT 新增「急停恢复」非关键项，
  直到用户重新 START（显式接受风险）才清除。
- 复位期间再次急停：令牌递增，旧流程在每个阶段检查令牌并静默退出，由新锁存接管。

### 2.4 控制锁定（两层）

1. **UI**：锁存时只渲染 `EmergencyScreen`，返回键被消费；普通控制页面不可达。
2. **Service**：`rejectWhenEstopLatched()` 拒绝 arm/接管/归还/偏移/映射/波形；`V4Controller.setTargetIntensity`
   在锁存时拒绝非零；控制循环在 `emergencyStopped` 时不下发。

---

## Phase 3：实现

### 新增文件

| 文件 | 说明 |
|---|---|
| `service/EstopResetPolicy.kt` | 复位纯策略：链路/自检检查、是否需要补发、能否清锁存、检查清单、失败原因枚举 |
| `ui/model/EmergencyModel.kt` | 紧急页纯派生：命令链四节点状态、复位资格/阻塞原因、检查清单、Zh/En 文案映射 |
| `ui/emergency/EmergencyScreen.kt` | 全屏 Emergency Mode（中央面板 + 复位流程 + 安全文案） |
| `ui/emergency/EmergencyBorder.kt` | 动态警戒边框（横向滚动 marquee + 纵向 Canvas 图案 + 展开动画 + 减少动态降级） |

### 修改文件

| 文件 | 变更 |
|---|---|
| `service/UiState.kt` | `estopResetPhase / estopResetChecks / estopResetFailure / estopResetCompletedAtMs / estopDeviceStopUnverified / estopResetInProgress` |
| `service/ControllerService.kt` | `requestEmergencyReset()` 状态机；急停锁存持久化/恢复；Service 层锁存守卫；复位令牌与 ACK 等待；复位成功后不启动控制 |
| `MainActivity.kt` | `Screen.EMERGENCY`；锁存最高优先级渲染；复位成功 → PRE-FLIGHT；返回键消费；EmergencyScreen 接线 |
| `ui/operation/OperationScreen.kt` | E-STOP 只保留入口按钮；移除页面内嵌锁存面板/复位对话框（迁移到全屏 Emergency Mode） |
| `ui/model/PreflightModel.kt` | 复位后新增非关键「急停恢复」检查项（设备停机状态无法通过 BLE 验证） |
| `ui/theme/Color.kt` | 新增 `BrightRed / EmergencyBlack / EmergencyPanel / EmergencyStrip` |
| `ui/i18n/UiStrings.kt` + `Zh/En` | 新增 ~60 条紧急模式/复位检查/失败原因文案（i18n 完整性测试覆盖） |
| `app/build.gradle.kts` | 仅验证期临时加 x86_64，交付版已还原 arm64-v8a |

### 未改动

运动识别模型/IMU/GNSS/映射公式/安全上限/WebSocket 协议/设备控制算法/Engineering 正常页面结构均未改。

---

## Phase 4：验证

### 4.1 自动化

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **221 个，0 失败**（原 201 + 20：急停复位策略 8 / 紧急展示模型 11 / 控制器急停幂等 1） |
| 新增测试重点 | 链路缺失优先级、APP ACK≠设备停止（DEVICE 永远 UNVERIFIED）、复位资格与阻塞原因、复位阶段/失败/清单透传、急停幂等 + 锁存态普通强度指令被忽略、i18n 全键完整性 |
| `:app:assembleDebug` | 通过；APK **64.83 MB**，仅 arm64-v8a |

### 4.2 模拟器端到端（Android 37 x86_64，假 DG-LAB APP）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 正常触发急停（已 arm、APP ACK） | 立即进入全屏 Emergency Mode；链 ISSUED/QUEUED/ACKED + DEVICE UNVERIFIED |
| 2 | 重复触发急停 | 控制器幂等、锁存不解除（单测 + 后端逻辑） |
| 3 | 急停命令发送失败（无链路触发） | FRAMES/APP 显示 PENDING，不谎称已下发；复位被禁用并给出真实阻塞原因 |
| 4 | APP 已确认但设备未确认 | DEVICE 永远 UNVERIFIED；UI 无任何“已停机”暗示 |
| 5 | 急停过程中网络断开 | 先离线触发急停 → 复位禁用；恢复链路后可复位 |
| 6 | 页面切后台再返回 | 真机实测：HOME → 重新打开应用，仍停留在 Emergency Mode（`renderScreen` 由 UiState 派生） |
| 7 | 应用重新打开 / 进程被杀 | `am force-stop` 后重启：从 SharedPreferences 恢复锁存并直接进入 Emergency Mode |
| 8 | 急停状态下请求普通控制 | UI 无入口；Service 层显式拒绝（arm/接管/映射/波形） |
| 9 | 用户请求复位（APP ACK 正常） | RESETTING（补发/等待）→ SELF_CHECK（9 项清单）→ 回到 PRE-FLIGHT + 急停恢复警告 |
| 10 | 复位过程中自检失败 | 策略单测覆盖（模型/scaler 失败 → 保持锁存 + 失败原因） |
| 11 | 复位时停止命令未确认 | 假 APP `--no-ack`：补发后等待 3s → `RESET FAILED: STOP COMMAND NOT ACKED`，锁存保持；换回正常 APP 重试成功 |
| 12 | Android 减少动态效果 | `animator_duration_scale=0`：两张间隔 1.5s 截图 **像素差 0**（完全静态）；恢复后 1.2s 两张截图差 128,033 px（约 4.9%），证明边框在滚动/移动 |
| 13 | 中文界面 | 切换 Zh 后紧急页/对话框/自检项/失败原因全部中文；顶部滚动 `/// 紧急停机 ///` |

> UI 自动化注意：边框是无限动画，`uiautomator dump` 在动画开启时会报 `could not get idle state`；
> 验证时临时把动画缩放设为 0（或用 `rememberReduceMotion` 路径）即可 dump。TalkBack 不受影响
> （装饰层已 `clearAndSetSemantics`，中央内容语义正常）。

### 4.3 真机验证（Redmi K90 PM / Android 17 / 1200×2608）

- 安装最终 arm64 APK（64.83MB），真实前台服务/中继/ONNX 正常运行；
- 假 APP 通过 `adb forward` 连入真机中继（验证 UI/Service 链路，不涉及真实 BLE）；
- 真机实测通过：PRE-FLIGHT 门禁 → 进入控制 → 触发 E-STOP → 全屏紧急页（安全区/导航栏适配正确）→
  HOME 切后台再返回仍保持紧急页 → 二次确认复位 → 复位成功回到 PRE-FLIGHT（急停恢复警告）；
- 截图：`14_real_device_emergency_zh.png`、`15_real_device_reset_dialog_zh.png`、
  `16_real_device_after_reset_zh.png`、`17_real_device_preflight_ready_zh.png`、
  `18_real_device_after_background_zh.png`。
- **未验证**：真实 DG-LAB APP + 郊狼主机 BLE 链路下的设备端停止行为（本工程无回执通道）。

---

## Phase 5：交付报告

### 5.1 完成的页面与动画

- 独立全屏 Emergency Mode（锁存时 100% 占据应用，返回键不可退出）；
- 顶部/底部无缝横向滚动警报文字 + 左右纵向工业警戒图案 + 350ms 展开动画；
- 中央稳定状态面板：E-STOP LATCHED / CONTROL SESSION SUSPENDED / 停止命令链 / 设备未验证 / 链路状态 /
  普通操作锁定 / 复位区；
- 复位流程可视化：REQUESTED → RESETTING（补发停止命令 + 9 项检查清单）→ SELF_CHECK → 失败/成功；
- 减少动态效果：静态红黑边框（像素级验证 0 差异）；
- 状态变化用 `animateColorAsState` 短暂强调，无高频闪烁。

### 5.2 复用的业务逻辑

V4 控制器急停（A/B 归零 + clear、优先队列、锁存、APP ACK 处理）、链路状态、识别管线状态、
`safetyMax/slew/EMA/REST/看门狗`、i18n/主题/组件、日志/通知：全部复用，未改公式与协议。

### 5.3 仍需后端/协议改造（已如实报告，不用 UI 掩盖）

1. **设备端停止确认缺失**：V4 协议没有 BLE 级回执，`DEVICE` 永远 `UNVERIFIED`；复位成功也不代表设备物理停止。
2. **复位依赖链路在线**：APP/设备不在线时拒绝复位（因为无法送达停止命令）；这是有意收紧的安全策略，
   需要现场流程配合（先恢复 DG-LAB APP/设备连接）。如果产品希望“离线也能复位”，必须由硬件/固件提供独立停止确认。
3. **锁存持久化依赖 SharedPreferences**：清除 App 数据/卸载会绕过；更严格方案需要系统级安全存储或服务端状态。
4. **真正的异步启停**：STARTING/STOPPING 仍是命令收敛窗口；急停触发是同步状态更新（满足“不等待动画/不阻塞命令”）。

### 5.4 仅 UI 表现 / 无需误解的部分

- 动画仅呈现状态，不参与控制逻辑；边框动画与急停命令发送完全解耦。
- 复位成功后跳转 PRE-FLIGHT 是导航行为，不自动 START、不发送新的输出指令。
- 中央状态文案“控制会话已暂停”是对后端锁存的展示，不是本地状态。

### 5.5 风险 / 待解决

1. **BLE 设备确认缺失**（最高风险，长期）：无法证明设备已物理停止；需硬件/APP 侧回执或独立急停通道。
2. 复位要求链路恢复 → 极端场景（手机与设备同时失联）用户无法在 App 内复位；只能物理断电/等链路恢复。
3. 无限动画在低端设备/长期停留的功耗：已用 graphicsLayer/draw 层读取避免重组，但仍建议长期停留时进入
   减少动态/静态模式（系统设置即可触发）。
4. 未做 instrumented Compose UI 测试；自动化测试受无限动画 idle 影响，需配合 reduce-motion。
5. 本轮真机验证使用假 APP 走真实中继，未接真实 BLE 设备；真实 APP 的设备端行为仍需现场确认。
6. `uiautomator dump` 在动画开启时不可用（已知限制，测试/自动化需关闭动画）。
