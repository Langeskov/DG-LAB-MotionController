# MotionController UI V2.0 — POLAR BLUE 设计与交付报告

> 状态：V2.0 第一版完成（2026-11-13）。
> **2026-11-14 增补**：普通背景已从 `#142C40` 调整为 `#203348`，并加入系统栏状态同步与急停退出转场；
> 最新细节见 [`docs/ui-system-bars-emergency-transition.md`](ui-system-bars-emergency-transition.md)。
> 本文档中的旧色值表保留为当时的设计记录，颜色回滚备份在 `docs/theme-backup/polar-blue-2026-11-13/`。
> 范围：主题系统（深海蓝 + 冰蓝）、OPERATION 主界面层级重构、本地强度包络面板整理、PRE-FLIGHT / ENGINEERING 主题适配、
> 根 README 与仓库卫生。**控制算法 / V4 协议 / 映射公式 / 安全上限 / 急停状态机 / 业务逻辑均未修改。**
> 验证：JVM 单测 **232 个，0 失败**；Debug APK 构建通过；真机 Redmi K90 PM（Android 17）实际安装运行并通过
> PRE-FLIGHT → OPERATION → AUTO/MANUAL → START/STOP → 波形 → ENGINEERING → E-STOP → RESET 全流程（假 DG-LAB APP 经
> `adb forward` 接入真实中继；**真实 BLE 郊狼主机联调仍未执行**）。
> 截图：`docs/screenshots/polar-blue-2026-11-13/`。

---

## 1. 视觉方向

**Industrial Control Terminal + Modern Automotive Instrumentation + Polar Blue。**
产品是全独立的智能运动识别与设备控制终端；不复制任何汽车品牌 LOGO/商标/专属视觉元素，
品牌统一为 `MOTION CONTROLLER`，副标题 `POLAR CONTROL SYSTEM`。

- 深海蓝承担背景与面板层级，不使用大面积纯黑；
- 冰蓝/蓝色用于选中状态、重要实时数据、主要操作、滑块与活动指示；
- 绿色只用于连接/自检/状态确认；黄色用于等待/数据过期/普通警告；
- 红色只用于严重故障与 E-STOP；E-STOP 使用**独立红黑 Palette**，不跟随 POLAR BLUE；
- 字体沿用 `McType`：数值/地址/日志等宽，标签适度字距（本轮把 label 1.8→1.4、labelLarge 1.6→1.2、button 1.4→1.1，
  避免过度拉宽），中文走 `cjkTuned`；
- 面板保持 2dp 方角工业风，新增的 PRIMARY 读数块/摘要块使用 `SurfaceInset` 形成层次，不堆阴影/发光。

### 1.1 语义 Token（`ui/theme/Color.kt`）

| Token | 值 | 用途 |
|---|---|---|
| `Background` | `#142C40` | 主背景 |
| `BackgroundDeep` | `#0E2233` | 深层背景 |
| `BackgroundElevated` / `BackgroundGradientTop` | `#183850` | 主界面轻微纵向渐变 |
| `Surface` | `#19344A` | 普通面板 |
| `SurfaceRaised` | `#24445B` | 高层面板/对话框 |
| `SurfaceInset` | `#0C2031` | 输入区域/内嵌读数块 |
| `Border` / `BorderStrong` / `Divider` | `#34536A` / `#49677D` / `#294255` | 描边与分隔 |
| `IceBlue` / `IceBlueBright` | `#A6E9F8` / `#D0F5FC` | 选中、重要实时数据 |
| `BlueAccent` / `BlueDim` / `BlueGlow` | `#62CDEB` / `#397D9F` / 16% 蓝 | 主要操作、活动状态 |
| `SteelBlue` / `SteelBlueDim` | `#9DB9C9` / `#5E7F93` | 辅助信息（替代旧紫） |
| `Success` / `SuccessDim` | `#50C9A0` / `#1E5A47` | 连接、自检通过 |
| `Warning` / `WarningDim` | `#E8B65B` / `#6E5518` | 等待、过期、普通警告 |
| `Danger` / `DangerDim` | `#F04452` / `#7E1F28` | 严重故障（非普通强调） |
| `TextPrimary` / `TextSecondary` / `TextDim` | `#EDF6FA` / `#B5C8D3` / `#7A93A3` | 文本三级 |
| `TextOnAccent` / `TextOnDanger` | `#0B1A26` / `#FFFFFF` | 填充按钮文字 |
| `EmergencyBlack/Panel/Strip/Red/RedDim/BrightRed` | `#050506` / `#160A0A` / `#220707` / `#E01F1F` / `#7E1010` / `#FF3B30` | E-STOP 独立红黑 |

**兼容别名**：旧 EVA Token（`Orange/Purple/Yellow/Red/Green/…`）保留为别名并映射到 POLAR 值，
避免大范围改组件接口；本轮已把 OPERATION、WavePanel、components 默认参数、PRE-FLIGHT、ENGINEERING 迁移到语义 Token，
E-STOP 使用独立 Emergency Token。新代码禁止再新增硬编码相近颜色。

### 1.2 主题 / 系统栏

- `Theme.kt`：单一 `darkColorScheme`，primary=BlueAccent、secondary=SteelBlue、tertiary=Warning、error=Danger；
  **无浅色/深色切换、无动态取色**；
- `res/values/colors.xml` / `themes.xml`：启动窗口背景改为 `#142C40`，状态栏/导航栏透明，无冷启动白闪；
- `MainActivity` 仍使用 `enableEdgeToEdge + SystemBarStyle.dark(TRANSPARENT)`，状态栏/导航栏图标为浅色，与深蓝背景协调；
- 输入框/滑杆/开关/按钮/禁用态均检查：`McInputField` 焦点色 BlueAccent、`McSlider` 拇指/活动轨道 BlueAccent、
  `TacticalButton` 禁用态中性 Dim（E-STOP 传暗红 disabledColor/disabledContainerColor）。

---

## 2. OPERATION 主界面重构

### 2.1 信息层级（中等密度）

1. **顶部栏**：`运动控制器 / POLAR CONTROL SYSTEM` + RELAY / APP / DEVICE 三枚链路状态灯 + 工程齿轮；
   不展示地址/tid/端口。
2. **仪表区（并排）**：
   - 左：GNSS 速度（大号等宽数字 + `LIVE/STALE/UNAVAILABLE` 标签；STALE 时数字变 Warning 色、右侧不再重复同一句，
     下方整行警告说明“不是实时速度”；UNAVAILABLE 显示 `--`）；
   - 右：当前运动模式（技术英文码 + 中文名称 + AUTO/人工标签；MANUAL 时整块用 BlueAccent 强调）。
3. **CONTROL AUTHORITY**：保留 `ControllerService.overrideMotionClass()/returnToAuto()`；AUTO 显示说明 + 接管按钮；
   MANUAL 显示 ◀/▶ 类别切换 + 归还自动（归还阻塞原因原样显示，按钮高度已修正避免副标题被裁切）。
4. **OUTPUT PROFILE**：
   - **TARGET / APPLIED** 作为主读数（大号 `dataXl`，TARGET 冰蓝、APPLIED 蓝/安全色，E-STOP 时用 Danger）；
   - **BASE / OFFSET / MAPPING** 作为次级读数；
   - 安全上限以 `安全上限 60` 标签常显；
   - `下发 A / 估计 A / APP 上报` 保留为一行小字（来源不同，不混同）；
   - 展开后保留计算值/钳制值/映射/下发目标/APP 上报、手动偏移滑杆与说明；
   - 公式、钳制、slew/EMA、安全上限均未改。
5. **START / STOP**：保持在主界面，启动阻塞原因逐条可见；状态机与后端一致。
6. **INTENSITY ENVELOPE（本地强度包络）**：见第 3 节。
7. **SYSTEM READINESS**：拆分两个不同概念（见 2.2）。
8. **独立 E-STOP**：固定底部红色入口，始终可触达；锁存后由 MainActivity 切换到全屏 Emergency Mode。

### 2.2 Readiness 拆分

不再用单个含义模糊的 READY：

- `CONTROL LINK`：RELAY / APP / DEVICE（控制链路是否就绪）；
- `MOTION ENGINE`：MODEL / SCALER / GNSS（识别能力；GNSS 缺失不阻止控制，但会降低准确率，灯为 Warning/TextDim）。

整体 tag 只在两者都就绪时显示“就绪”；底层 readiness 计算规则未改，仅整理 UI 表达与命名。

### 2.3 连接状态

顶部三灯来自真实 `UiState.link`：`relayRunning / appConnected / deviceReady`。ENGINEERING 仍保留地址、tid、客户端数、
槽位、发送队列/丢弃计数、协议错误等工程字段，普通主页不展示。

---

## 3. 本地强度包络（WavePanel）

- 真实语义保持：**INTENSITY ENVELOPE / 本地强度包络合成**，不是 DG-LAB APP 的底层脉冲波形切换；
- 主界面默认紧凑摘要：状态（关闭/已启用/调制中/急停锁存）+ 波形 + 频率 + 深度 + 持续时间 + 一行诚实标注
  `本地强度包络 · 不是设备脉冲波形`；
- 点击标题展开后：开关、FLAT/SINE/TRIANGLE/SQUARE/SAWTOOTH/BURST、频率 0.1–1.5Hz、深度 0–100%、
  持续时间（持续/30s/60s/5min/自定义）、包络条与基础/调制/剩余读数；未 START 时显示“开始控制后生效”；
- 数据/持久化/公式/会话生命周期全部复用 `WaveConfig` / `WaveformEngine`，未改；
- 未来扩展预留：结构上区分 `IntensityEnvelope` 与未来的 `DevicePulseProfile`；在协议/APP/设备能力
  未单独验证前**不提供任何设备脉冲波形控件**。

---

## 4. PRE-FLIGHT / ENGINEERING / E-STOP

- **PRE-FLIGHT**：保留 SINGLE/MULTI、复制地址、二维码、7 项自检、APP 连接门禁、进入动效；
  仅做主题/排版适配（标题层级、面板统一、关键失败 Danger 描边）。连接协议/地址语义未改。
- **ENGINEERING**：四个 Tab（连接/识别/输出/诊断）与全部诊断内容保留；仅颜色/排版迁移，
  包括语言切换、诊断导出、类别基准/offset/safetyMax 等；不把工程信息塞回普通主页。
- **E-STOP**：保持独立红黑视觉：
  - `EmergencyBorder` 背景改 `EmergencyBlack`，分割线/图形用独立 `EmergencyRed/EmergencyRedDim`；
  - 紧急页所有 `McPanel` 使用 `EmergencyPanel` 底色（本轮新增 `McPanel.backgroundColor` 参数，默认 Surface，不影响普通页）；
  - 复位确认对话框使用 `EmergencyPanel` + `EmergencyBrightRed` 确认按钮（新增可选参数，默认 POLAR）；
  - 复位按钮禁用态在紧急页使用暗红 disabledColor/disabledContainerColor，避免深蓝泄漏；
  - 急停状态机、命令链、DEVICE UNVERIFIED、RESET 流程、锁存持久化均未改。

---

## 5. 交付文件

### 新增
- `README.md`（项目简介/功能/技术栈/流程/状态/截图/构建测试/限制/安全声明）
- `docs/screenshots/polar-blue-2026-11-13/`（7 张主截图 + 4 张补充截图）
- `docs/ui-redesign-v2-polar-blue.md`（本文件）

### 主要修改
- `ui/theme/Color.kt`（POLAR 语义 Token + 旧 Token 别名）、`Theme.kt`、`Type.kt`
- `res/values/colors.xml`、`res/values/themes.xml`
- `ui/operation/OperationScreen.kt`（顶部链路、仪表区、OUTPUT PROFILE、Readiness 拆分、START/STOP 布局）
- `ui/operation/WavePanel.kt`（紧凑摘要 + 展开详情 + 诚实标注）
- `ui/components/Foundations.kt`（`McPanel.backgroundColor`）、`Buttons.kt`（禁用态颜色参数、默认 accent）、
  `Indicators.kt`、`Inputs.kt`（`McConfirmDialog` 可选紧急配色）、`ui/preflight/**`、`ui/engineering/**`（主题迁移）
- `ui/emergency/EmergencyScreen.kt`、`EmergencyBorder.kt`（红黑隔离）
- `ui/i18n/UiStrings.kt` + `Zh/En`（新增品牌副标题、CONTROL LINK/MOTION ENGINE、SCALER/GNSS、安全上限、
  波形诚实标注、展开/收起详情；全部中英文齐备，i18n 键完整性单测通过）
- `.gitignore`、`.kotlin/errors` 清理、`.idea` 本机生成文件清理

未改：`ControllerService`、`UiState`、`V4Controller`、V4 WebSocket 协议、映射公式、安全上限/slew/EMA、
急停状态机、模型/特征、Motion-ml、MotionRecorder、dglab-websocket-server。

---

## 6. 实际运行验证（2026-11-13）

环境：真机 Redmi K90 PM / Android 17 / 1200×2608；真实 `ControllerService` + V4 中继 + ONNX；
DG-LAB APP 由本地假 APP（`build/dev/fake_dglab_app.py`，非仓库提交文件）经 `adb forward` 接入，
设备为假 `FAKE-COYOTE`。**这证明 UI/Service/中继链路真实工作，但不等于真实 BLE 联调。**

| 检查项 | 结果 |
|---|---|
| 冷启动 PRE-FLIGHT | ✅ 深蓝主题、自检项颜色正确；APP 未连接时 `进入控制` 禁用并显示原因 |
| SINGLE / MULTI 切换 | ✅ 地址从 `127.0.0.1` 变为局域网 IP（`192.168.x.x`），中继重启，APP 断开后恢复连接 |
| 复制地址 / 二维码 | ✅ 二维码在 POLAR 面板中显示/隐藏（截图 `tmp_qr` 过程验证） |
| APP 连接/断开 | ✅ 顶部 RELAY/APP/DEVICE 灯同步；断开后 OPERATION 门禁回 PRE-FLIGHT，诊断字段如实变化 |
| OPERATION AUTO | ✅ 速度/模式/输出/START/波形摘要/readiness 全部来自真实 UiState |
| GNSS 三态 | ✅ 无定位时显示 `已过期` + 黄色数字 + 整行警告（不伪装实时）；UNAVAILABLE/ LIVE 分支由既有模型测试覆盖 |
| AUTO → MANUAL | ✅ 接管后运动模式块与 OUTLINE 强调切换；模型继续推理但不覆盖手动类别 |
| 手动类别切换 | ✅ ◀/▶ 改变生效类别；输出映射 BASE/TARGET 随之变化 |
| 归还自动 | ✅ 按钮副标题完整显示；归还要求模型数据新鲜（既有逻辑） |
| 强度映射 | ✅ 手动偏移 +20 → TARGET 20 / 计算值 20 / 钳制 20；未 START 时 APPLIED 0（未下发） |
| 安全上限 | ✅ `安全上限 60` 常显；未改公式/钳制 |
| START / STOP | ✅ START 后 `运行中`、TARGET/APPLIED 10、`下发/估计/APP 上报 10`；STOP 后归零、回到待机 |
| 波形包络 | ✅ 摘要/展开、开/关、形状/频率/深度/持续时间控件；`调制中` 状态、包络 97%/100%；开始控制后生效 |
| 波形持久化 | ✅ 重启/重装后 `已启用` 配置保留（`mapping_json` 既有持久化） |
| ENGINEERING | ✅ 四 Tab 全部渲染；语言切换、诊断导出、输出配置可见；无旧橙色残留 |
| E-STOP 触发 | ✅ 全屏红黑紧急页；RELAY/APP/DEVICE 真实；命令链 ACKED + DEVICE UNVERIFIED |
| E-STOP 主题隔离 | ✅ 急停面板/边框/复位对话框/禁用按钮均为红黑（修复了主题重构初期的 POLAR 面板泄漏） |
| E-STOP 后台/返回 | ✅ HOME 后重新打开仍在紧急页 |
| E-STOP 进程终止/重建 | ✅ force-stop 后重启：锁存从 SharedPreferences 恢复，直接进入紧急页 |
| RESET | ✅ 二次确认 → RESETTING/SELF_CHECK → PRE-FLIGHT；不自动 START；复位成功仍显示 DEVICE UNVERIFIED 提醒 |
| 旋转 | ✅ 横屏下页面可滚动、无崩溃（布局以竖屏为主） |
| 减少动态效果 | 既有 E-STOP 边框静态化已验证（本轮未改该逻辑）；未重新做像素级复测 |

截图：`docs/screenshots/polar-blue-2026-11-13/`（01–07 为主截图，08–11 为运行/工程/进程重建补充）。

---

## 7. 测试报告

| 类别 | 结果 |
|---|---|
| JVM 单元测试 | **通过：232 个，0 失败**（协议/中继 57、ML 64、映射 46、传感器 8、UI 状态 48、服务策略 8、Example 1） |
| Debug APK 构建 | 通过（`app/build/outputs/apk/debug/app-debug.apk`，仅 arm64-v8a） |
| 模拟器运行 | 未单独执行（使用真机完成同等/更真实验证） |
| APP 连接流程 | 通过（真实中继 + 假 APP；真实 DG-LAB APP 本轮未执行） |
| 控制启停流程 | 通过 |
| AUTO / MANUAL | 通过 |
| 波形包络 | 通过（UI/状态/持久化；真实设备波形仍不可验证） |
| E-STOP / RESET | 通过（触发、锁存恢复、复位、失败路径由既有实现覆盖） |
| 真机联调（真实 BLE 设备） | **未执行**（无真实 DG-LAB APP + 郊狼主机链路） |

> 明确区分：以上“真机”指 Android 手机 + 真实前台服务/中继/ONNX；DG-LAB APP 与设备为假实现。
> 真实 BLE 设备端行为（尤其急停是否物理停止）仍需现场联调。

---

## 8. 已知限制 / 风险

1. **V4 外部协议只有强度指令**（t=3 / t=7 v=0 / clear），没有设备脉冲波形切换；UI 的“波形”只是本地强度包络。
2. **E-STOP 设备端（BLE）无回执**：APP ACK ≠ 设备物理停止；UI 永远 `DEVICE UNVERIFIED`，复位要求 APP/设备在线。
3. 真实 DG-LAB APP + 郊狼主机未在本轮联调；Mock APP 验证不等同真实设备。
4. 模型准确率约 76–84%，无 GNSS 下降明显；本轮未改模型/数据。
5. 主界面为中等密度，最低高度设备/大字号下可能需要滚动；横屏以滚动为主，未专门设计横屏双栏。
6. 旧 EVA Token 仍以别名形式存在（值已映射 POLAR），未完全删除以避免大范围接口改动；新代码应使用语义 Token。
7. 无限动画（E-STOP 边框）在 uiautomator dump 时仍会阻塞 idle；自动化需 reduce-motion（既有已知项）。
8. release 签名 / CI / versionCode 提升未做；debug APK 约 64.89MB（含 ONNX Runtime）。
9. 未做 TalkBack 全量走查与真机性能 profile。
