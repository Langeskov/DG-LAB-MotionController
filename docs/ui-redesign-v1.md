# MotionController V4 — UI/UX Redesign V1（设计 + 交付报告）

> 状态：第一版实现完成（PRE-FLIGHT / OPERATION / AUTO-MANUAL OVERRIDE / OUTPUT PROFILE / START-STOP / E-STOP / ENGINEERING）。
> 本文档同时作为 Phase 1 审查、Phase 2 设计与 Phase 5 交付说明。

---

## Phase 1：项目审查（改造前）

### 1.1 技术栈与结构

- Kotlin 2.2.10 / AGP 9.4.1 / Jetpack Compose（BOM 2026.02.01，Material3）/ minSdk 32。
- 单 Activity（`MainActivity`）+ 前台 `ControllerService`；UI 通过 `LocalBinder` 观察 `StateFlow<UiState>`。
- 无导航库：旧版是单页 `ControllerScreen`（纵向卡片流），所有卡片一次性展示。

### 1.2 改造前页面 ↔ 功能对应

| 旧 UI 区域（ControllerScreen.kt） | 数据来源 | 功能 | 本次去向 |
|---|---|---|---|
| 标题/状态行（服务/中继/APP/设备 4 个 chip） | `UiState.serviceRunning/link` | 连接状态 | 拆到 PRE-FLIGHT 自检 + OPERATION 顶部 + ENGINEERING CONNECTION |
| ConnectionCard（地址、端口、tid、复制、同机/双机、二维码） | `UiState.address/qrPayload/bindHost/port/link.targetId` | 连接 APP | PRE-FLIGHT（模式+地址+二维码，普通用户）；ENGINEERING CONNECTION（端口/tid/devices/诊断，工程师） |
| ControlCard（开始/暂停/急停/解除急停/状态文案） | `UiState.armed/emergencyStopped/link` | 控制会话与急停 | OPERATION START/STOP + 独立 E-STOP（含锁存阶段与复位流程） |
| MotionCard（稳定类别 + 置信度 + 5 类概率条 + 采集/模型/scalers/缓冲/推理耗时） | `UiState.stableClass/prediction/smoothedProbs/pipeline` | 运动识别 | OPERATION 只显示生效模式；全部工程细节移入 ENGINEERING MOTION |
| IntensityCard（目标/当前/APP 上报、手动偏移、安全上限、5 类基准滑杆） | `mapping/manualOffset/targetIntensity/link` | 强度映射 | OPERATION OUTPUT PROFILE（只展示当前生效映射 + 当前类偏移）；配置滑杆移入 ENGINEERING OUTPUT |
| LogCard（最近 20 条日志） | `UiState.logs` | 日志 | ENGINEERING DIAGNOSTICS（事件/错误/复制/导出） |
| 无 | — | — | 新增 PRE-FLIGHT 自检、启动动效、AUTO/MANUAL OVERRIDE、速度仪表 |

### 1.3 可直接复用的能力（未修改算法/协议/安全）

- V4 中继 + 控制器（`relay/`、`dglab/`）：连接、设备注册表、强度闭环、急停锁存、断连归零。
- 运动识别管线（`ml/`）：IMU/GNSS 采集、50Hz 重采样、24 维特征、ONNX、softmax、rest 矛盾消除。
- 映射与安全（`mapping/`）：`classBase + manualOffset`、`safetyMax`、slew、EMA、换类防抖、看门狗。
- 服务生命周期/持久化：异步引擎收敛、映射 JSON 持久化、前台通知、日志缓冲。
- 旧 UI 的 ZXing 二维码生成（`ui/QrCode.kt`）原样复用。

### 1.4 需要业务接口配合的缺口（Phase 1 结论）

| 缺口 | 影响 | 处理 |
|---|---|---|
| 没有 AUTO/MANUAL OVERRIDE 概念（映射永远用模型稳定类别） | 无法真实接管 | **已扩展** `MotionIntensityMapper.manualClassIndex` + `ControllerService.overrideMotionClass()/returnToAuto()`（只改“选哪个 classBase”，公式/安全限制不变） |
| UI 拿不到 GNSS 速度（只有 `gpsAvailable`） | 无法做主速度仪表 | **已扩展** `GnssSpeedProvider` 速度快照回调 + `MotionPipelineStatus.gpsSpeedKmh/gpsFixAgeMs/gpsEverFix` |
| 急停只有锁存布尔，没有传递阶段 | 无法区分“已发出/已入队/APP 已回/设备未确认” | **已扩展** `DglabState.estopIssued/estopFramesQueued/estopAppAcked/estopAckReason` |
| UI 拿不到发送侧估计值 | 无法区分 target / estimate / APP 上报 | **已扩展** `DglabState.estimateIntensityA/B`、`queuedOutboundCount`、`droppedOpCount`、`lastOpResponseReason` |
| 引擎启动失败只写日志 | 自检无法显示 FAIL 原因 | **已扩展** `UiState.engineError` |
| START/STOP 没有过渡态、没有防连点 | 可能重复下发 | **已扩展** `UiState.controlTransition` + service 命令防抖（500ms） |

---

## Phase 2：第一版 UI/UX 设计（手机竖屏优先）

### 2.1 三状态页面层级

```
PRE-FLIGHT ──(ENTER + ≤1.5s 启动动效)──▶ OPERATION ──(⚙)──▶ ENGINEERING
    ▲                                      │                     │
    └──────────── 关键失败才退回/停留 ──────┘◀──── BACK ─────────┘
```

- **PRE-FLIGHT**：模式（SINGLE/MULTI）、客户端连接地址复制/二维码、7 项真实自检、
  ENTER（关键项全 PASS 才可用，否则给出阻塞原因）、失败时 RETRY/ENGINEERING 入口。
- **OPERATION**：顶部状态栏（连接/AUTO-MANUAL/工程入口）→ 速度仪表 → 运动模式 →
  CONTROL AUTHORITY（接管/归还）→ OUTPUT PROFILE（可展开偏移）→ START/STOP →
  固定底部 EMERGENCY STOP。**单屏优先**，仅小屏滚动兜底；急停固定在滚动区之外。
- **ENGINEERING**：CONNECTION / MOTION / OUTPUT / DIAGNOSTICS 四个 Tab，保留原有全部工程能力。

### 2.2 视觉规范（EVA/NERV 工业终端 + 汽车仪表盘，克制）

| Token | 值 | 用途 |
|---|---|---|
| Background | `#0A0B0D` → `#101318` 竖向渐变 | 主背景 |
| Surface | `#101215`，SurfaceRaised `#171A1F` | 面板/内嵌 |
| Border | `#2A3037`（1dp 描边，2dp 方角） | 模块分隔 |
| Orange | `#FF6A00` | 主强调、可操作、AUTO 强调 |
| Purple | `#9B7BD4` | 辅助信息（AUTO 标签、工程数值） |
| Yellow | `#E8B33A` | WAITING / STALE / 警告 |
| Red | `#E01F1F` | **仅**急停与严重故障 |
| Green | `#54C98A` | PASS / 就绪（克制使用） |
| Text | `#E7E5E0` / `#9BA1A8` / `#636A73` | 三级文字层级 |

- 字体：数值/地址/日志一律 Monospace；标签无衬线大写 + 宽字距（模拟设备丝印）。
- 禁止：装饰性网格、持续扫描线、高频闪烁、粒子、影响读数动画；无第三方字体/图标依赖。
- 动效：速度数字平滑、模式淡入、状态色渐变、启动动效；全部尊重 Android「减少动态效果」。

### 2.3 关键交互语义

- **AUTO → MANUAL OVERRIDE**：点 TAKE MANUAL CONTROL 进入接管（模型继续推理但不参与控制）；
  ◀ ▶ 在现有 5 类中切换；RETURN TO AUTO 单独按钮，模型数据陈旧/暂停/未稳定时**禁用并说明原因**。
- **START/STOP**：STANDBY / STARTING / RUNNING / STOPPING / FAULT / E-STOP LATCHED 六态；
  不可启动时在按钮下方展示真实阻塞原因（服务/中继/APP/设备）；命令 500ms 防抖。
- **E-STOP**：固定底部红色独立按钮，单击立即锁存；锁存后显示
  COMMAND ISSUED → FRAMES QUEUED → APP ACK → DEVICE **UNVERIFIED**；
  RESET 必须二次确认，且只清锁存、不自动 START。
- **地图来源区分**：`COMPUTED`（软件计算）/ `SENT TARGET` / `ESTIMATED` / `APP REPORTED` 四层分别展示。

---

## Phase 3：实现结构（新文件）

```
ui/
├── theme/        Color.kt / Type.kt / Shape.kt / Theme.kt        # 工业终端设计系统
├── components/   Foundations.kt / Buttons.kt / Indicators.kt / Inputs.kt
├── model/        ConnectionMode / ControlModel / PreflightModel / SpeedModel /
│                 OutputModel / MotionDisplay / DiagnosticsText   # 纯逻辑，可 JVM 单测
├── preflight/    PreflightScreen.kt / BootSequence.kt
├── operation/    OperationScreen.kt
└── engineering/  EngineeringScreen.kt + 4 个 Tab + EngineeringCommon.kt
```

业务层（最小增补，公式/协议/安全限制未改）：

- `mapping/MotionIntensityMapper.kt`：`manualClassIndex` / `setManualClass()` / `effectiveClassIndex`；
  `recalcTarget()` 改为 `manualClass ?: stableClass`；`reset()` 结束接管会话。
- `service/UiState.kt`：`effectiveClass/manualClass/controlTransition/engineError`。
- `service/ControllerService.kt`：`overrideMotionClass/returnToAuto`、START/STOP 过渡与防抖、engineError。
- `sensor/GnssSpeedProvider.kt`：`GnssSpeedSnapshot` + `onSpeedUpdated`（2s 过期策略不变）。
- `ml/MotionPipeline*.kt`：status 暴露速度/定位年龄/是否曾有 fix。
- `dglab/DglabLinkApi.kt` + `V4Controller.kt`：急停传递阶段、estimate、发送队列/丢弃计数。

---

## Phase 4：检查结果

### 4.1 静态检查 / 单元测试

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **155 个测试，0 失败**（原 120 + 新增 35：UI 状态派生 25 / 人工接管 8 / 急停阶段 2 …） |
| `:app:compileDebugKotlin` | 通过（zero errors） |
| `:app:assembleDebug` | 通过，`app/build/outputs/apk/debug/app-debug.apk` = **64.41 MB**，仅 `arm64-v8a` |
| 新增测试覆盖 | `ControlModel`（六态/阻塞原因/归还自动守卫）、`PreflightModel`（关键项、FAIL 不伪造 READY、APP/设备 DISCONNECTED 不阻塞）、`SpeedModel`（LIVE/STALE/UNAVAILABLE）、`OutputModel`（AUTO/MANUAL、计算值/钳制值、三态强度）、`MotionIntensityMapperOverride`（接管不覆盖模型推理、REST 不覆盖接管、reset 结束会话、slew 无阶跃）、`V4ControllerEstopPhase`（issued/frames/ACK 三阶段、无链路锁存）、`DiagnosticsText`/`ConnectionMode` |

### 4.2 模拟器端到端 UI 验证（Android 37 x86_64，1080×2424）

用 Python 写的假 DG-LAB APP（`build/dev/fake_dglab_app.py`，仅本地验证、不进仓库）连接真实中继，
完整走通以下流程并逐屏截图（见 `docs/screenshots/`）：

| 场景 | 结果 |
|---|---|
| PRE-FLIGHT 单机 | 7 项自检真实呈现；地址 `ws://127.0.0.1:9998/?tid=…`；ENTER 可用 |
| PRE-FLIGHT 多机 | 切 0.0.0.0 后地址变为 `ws://10.0.2.15:9998/?tid=…`（**不是** 0.0.0.0），二维码自动展开 |
| 复制地址 | 写剪贴板 + Toast（代码路径验证） |
| OPERATION（未连 APP） | 速度 LIVE 0.0；模式 STATIONARY（REST 矛盾消除生效）；READY 状态下 START 因 APP/设备未连被禁用并显示原因 |
| OPERATION（假 APP 连接） | APP/DEVICE 灯变绿，SYSTEM READINESS=READY，START 可用 |
| START | CONTROL ENGINE → RUNNING，按钮变 STOP CONTROL，异常时显示目标/映射来源 |
| MANUAL OVERRIDE | 顶部/模式/输出三处 MENU 标识一致；切 CYCLING 后 BASE 20 / TARGET 20 / APPLIED 20，`SENT A 20 · EST A 20 · APP 20` 三值分别显示 |
| RETURN TO AUTO | 归还后恢复 AUTO/模型类别，输出仍受 slew 限制 |
| 正常 STOP | 回到 STANDBY，目标/输出归零，START 重新可用 |
| E-STOP（链路可用） | COMMAND ISSUED / FRAMES QUEUED / APP ACK 三项绿色，DEVICE 明确显示 UNVERIFIED；假 APP 收到 A/B `t=7 v=0` + `device.op.clear` 并回 `reason=cleared` |
| E-STOP（无链路） | 仍锁存，FRAMES 显示 NOT QUEUED（不谎称已下发），RESET 需二次确认且只清锁存 |
| 运行中 APP 断连 | 自动 DISARM、输出归零、READINESS=NOT READY、START 显示 APP NOT CONNECTED |
| ENGINEERING 四 Tab | 连接/识别/输出/诊断全部渲染；面板标题/长标签在修版后不再截断 |

截图清单：`docs/screenshots/01…11`（PRE-FLIGHT 单/多机、OPERATION AUTO/手动/输出、E-STOP 两态、ENGINEERING 四区）。

### 4.3 未能在真机验证的部分

- 本轮物理设备在验证过程中从 adb 掉线，最终 arm64 APK **未在真机安装复测**；
  模拟器验证的是同一份 UI/业务代码（仅额外打包 x86_64，交付版已移除）。
- 真实 DG-LAB APP + BLE 郊狼主机的设备端行为（尤其急停的 BLE 端执行结果）仍无法由本 App 确认，
  因此 UI 始终显示 `DEVICE UNVERIFIED`，这是刻意保留的安全语义。

---

## Phase 5：交付报告

### 5.1 修改/新增文件

**新增（UI）**

```
app/src/main/java/com/motioncontroller/ui/
├── theme/Shape.kt
├── components/{Foundations,Buttons,Indicators,Inputs}.kt
├── model/{ConnectionMode,ControlModel,PreflightModel,SpeedModel,
│          OutputModel,MotionDisplay,DiagnosticsText}.kt
├── preflight/{PreflightScreen,BootSequence}.kt
├── operation/OperationScreen.kt
└── engineering/{EngineeringScreen,EngineeringCommon,
                 EngineeringConnectionTab,EngineeringMotionTab,
                 EngineeringOutputTab,EngineeringDiagnosticsTab}.kt
```

**新增（测试/文档）**

- `app/src/test/java/com/motioncontroller/ui/model/{ControlModelTest, PreflightModelTest, SpeedModelTest, OutputModelTest, DiagnosticsAndConnectionModelTest}.kt`
- `app/src/test/java/com/motioncontroller/mapping/MotionIntensityMapperOverrideTest.kt`
- `app/src/test/java/com/motioncontroller/dglab/V4ControllerEstopPhaseTest.kt`
- `docs/ui-redesign-v1.md`、`docs/screenshots/`

**修改（业务层，均为最小增补，未改公式/协议/安全限制）**

| 文件 | 变更 |
|---|---|
| `ui/theme/Color.kt` / `Type.kt` / `Theme.kt` | 工业终端设计系统（强制深色，移除动态取色） |
| `MainActivity.kt` | 三状态导航、三个页面接线、剪贴板/导出、深色系统栏、返回键 |
| `service/UiState.kt` | `effectiveClass / manualClass / controlTransition / engineError` |
| `service/ControllerService.kt` | `overrideMotionClass/returnToAuto`、START/STOP 过渡 + 500ms 防抖、engineError 上报 |
| `mapping/MotionIntensityMapper.kt` | 人工接管（只换 classBase 选择），`reset()` 结束接管 |
| `sensor/GnssSpeedProvider.kt` | `GnssSpeedSnapshot` + `onSpeedUpdated`（2s 过期策略不变） |
| `ml/MotionPipelineApi.kt` / `MotionPipeline.kt` | status 暴露速度/定位年龄/everFix |
| `dglab/DglabLinkApi.kt` / `V4Controller.kt` | 急停三阶段、estimate、队列/丢弃计数、lastOpReason |
| `res/values/colors.xml` / `themes.xml` | 深色启动背景、透明系统栏 |
| 删除 `ui/ControllerScreen.kt` | 被新三页结构取代 |

### 5.2 复用了哪些原有能力

V4 中继/控制器、设备注册表、强度闭环、安全上限/slew/EMA/换类防抖、REST 矛盾消除、
死手看门狗、IMU/GNSS 采集与 2s 过期、ONNX 推理与 XNNPACK 回退、映射 JSON 持久化、
前台服务/通知、ZXing 二维码、日志缓冲：**全部原样复用**，未重写、未改参数。

### 5.3 需要进一步改造后端的部分（已指出、未用 UI 掩盖）

1. **设备端急停/断连确认**：V4 协议无 BLE 级 ACK 通道 → UI 只能显示 `UNVERIFIED`；
   若要真正“设备已确认”，需要 APP/协议侧提供回执或独立的硬件停止链路。
2. **START/STOP 真实异步状态**：当前 arm/disarm 是同步状态更新，`STARTING/STOPPING` 是
   450ms 的命令收敛窗口而非后端事件；若将来引入真正异步启停，应把状态机下沉到 Service。
3. **波形/频率/持续时间**：本工程协议层未实现（只发 AddIntensity / SetIntensity v=0 / clear），
   ENGINEERING 明确标注 NOT IMPLEMENTED，未绘制假控件。
4. **人工接管持久化**：接管是会话级状态，不写入 SharedPreferences；如需跨重启保留，需要产品确认。
5. **多 APP per-client 设备列表**：控制器仍是全局最后快照（单机 1:1 足够），多机场景需 per-client registry。

### 5.4 仅 UI 表现的部分（明确声明）

- `STARTING / STOPPING`：命令收敛窗口的视觉状态，不代表后端异步任务。
- `READINESS` 四灯：由真实 `relayRunning / appConnected / deviceReady / modelLoaded+scalerValid` 派生，
  不是额外探测。
- `DEVICE UNVERIFIED`：字面含义——没有任何设备端确认通道，不能视为“已安全”。

### 5.5 仍存在的风险 / 待解决问题

1. **真机未复测**（物理设备掉线）：arm64 APK 只完成构建；下一轮需真机安装 + 真实 DG-LAB APP 联调。
2. **急停安全边界不变**：App 层只能保证“命令发出 + APP ACK”；设备 BLE 是否执行、以及手机与设备
   直接断连时的保护，仍依赖 DG-LAB APP / 郊狼主机固件——这是既有工程风险，UI 已如实标注。
3. **Pre-flight 关键项策略变化**：model/scaler 无效现在会阻止进入 OPERATION（此前仅警告）；
   属于安全收紧，需产品确认是否接受“必须修复资产才能使用”。
4. **识别准确率**（76–84%）与无 GNSS 掉点（−6.9pt）仍是模型问题，UI 只能提示 GNSS 不可用。
5. **性能**：UiState 约 10Hz 更新，Compose 每 tick 重组；模拟器流畅，但未在真机 profile。
6. **可访问性**：主要按钮 ≥44dp、对比度高；未做 TalkBack 全量走查，动态字体超大时 OPERATION 需滚动。
7. **本地化**：界面以英文设备丝印 + 中文辅助说明为主，未引入 i18n 资源体系（跟随项目现状）。
8. **模拟器验证脚本**：`build/dev/fake_dglab_app.py` 在 gitignore 的 `build/` 下，仅作验证工具，不参与构建。

