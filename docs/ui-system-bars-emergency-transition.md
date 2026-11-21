# System Bars & E-STOP Exit Transition（2026-11-14）

> 本轮只做四件事：**系统栏与急停页融合**、**急停边框与系统区域连续**、**复位成功后的退出转场**、
> **普通背景统一为 `#203348`**。不重设计信息架构，不改控制/映射/协议/急停状态机。
>
> 验证环境：真机 Redmi K90 PM（Android 17 / API 37 / 1200×2608 / 三键导航）+ Android Studio 模拟器
> Pixel_10_Pro_XL（Android 37 / x86_64 / 1344×2992）。JVM 单测 **232 个，0 失败**；
> Debug APK **64.89 MiB，仅 arm64-v8a**。DG-LAB APP 仍为本地假 APP（真实中继 + 假设备，
> **真实 BLE 设备端仍未联调**）。
>
> 截图：`docs/screenshots/system-bars-2026-11-14/`；退出转场录屏：`exit_transition_real_device.mp4`。
> 颜色回滚备份：`docs/theme-backup/polar-blue-2026-11-13/`。

---

## 1. 普通主题色更新（#203348）

| Token | 旧值（V2.0） | 新值（本轮） |
|---|---|---|
| `Background` | `#142C40` | **`#203348`** |
| `BackgroundDeep` | `#0E2233` | `#1A2B3D` |
| `Surface` | `#19344A` | `#263D52` |
| `SurfaceRaised` | `#24445B` | `#2D465C` |
| `SurfaceInset` | `#0C2031` | `#182B3D` |
| `Border` | `#34536A` | `#40586D` |
| `BorderStrong` | `#49677D` | `#52708A` |
| `Divider` | `#294255` | `#31495E` |
| `IceBlue` / `BlueAccent` / `TextPrimary` / `TextSecondary` | 不变 | `#A6E9F8` / `#62CDEB` / `#EDF6FA` / `#B5C8D3` |

- `BackgroundGradientTop` 改为与 `Background` 相同（`#203348`），OPERATION 不再出现顶部渐变与系统栏的色差接缝；
  普通页面统一使用该背景/语义面板色。
- `res/values/colors.xml` 启动窗口同步为 `#203348`，冷启动不白闪、无旧色闪现。
- E-STOP 仍使用独立红黑 Palette（`EmergencyBlack #050506` 等），**没有改成蓝色**。

---

## 2. Android System Bars 实现

### 2.1 Edge-to-edge

- `MainActivity.onCreate` 继续使用：
  ```kotlin
  enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
  )
  ```
  → 应用背景绘制到状态栏/导航栏后方，系统图标为浅色。
- 根布局改为：
  ```kotlin
  Box(
      Modifier.fillMaxSize()
          .background(systemBarSurface)   // 系统栏下方可见的同一颜色
          .safeDrawingPadding(),          // 交互内容只在这里避让 Insets（只加一次）
  )
  ```
  `background` 在 `safeDrawingPadding` 之前绘制，因此覆盖整窗（含系统栏区域）；
  内容通过 `safeDrawingPadding` 避开遮挡，**没有重复应用 Insets**。
- 急停边框/中央内容仍在 `safeDrawingPadding` 内，所以滚动警报文字不会覆盖状态栏图标、手势区域；
  系统栏区域由根背景填充为 EmergencyBlack，边框与系统区域视觉连续。

### 2.2 颜色与状态同步

`systemBarSurface` 用 `animateColorAsState` 计算：

| 页面状态 | 目标色 |
|---|---|
| NORMAL（PRE-FLIGHT / OPERATION / ENGINEERING） | `McColors.Background` `#203348` |
| EMERGENCY / RESET REQUESTED / RESETTING / SELF_CHECK / FAILED | `McColors.EmergencyBlack` `#050506` |
| 退出转场 `E-STOP CLEARED` | `EmergencyBlack` |
| 退出转场 `SYSTEM REINITIALIZING` | 约 620ms 内渐变到 `#203348` |

系统栏本身透明，颜色完全由根背景决定；急停页中央内容通过 `EmergencyScreen(backgroundColor = systemBarSurface)`
使用**同一个动画色值**，因此转场过程中系统栏与页面内容不会出现蓝色/黑色割裂。
（像素验证见第 5 节。）

### 2.3 Android 15+ / 导航栏对比度

- 在三键导航下，Android 默认会给透明导航栏加对比度遮罩，导致底部出现一条与页面背景不一致的灰色/半透明带。
  本轮显式设置：
  ```kotlin
  window.isNavigationBarContrastEnforced = false
  ```
  由应用根背景精确绘制 `#203348` 或 `EmergencyBlack`；系统图标保持浅色
  （`SystemBarStyle.dark`），在两种深色背景上对比度足够。
- 该配置在三键导航（模拟器 + 真机）与手势导航都做了截图检查，图标（时间/电量/信号、
  返回/主页/最近任务、手势条）仍可见；不是“为了透明盲目关闭”后不验证。
- 需要说明：不同 OEM 可能对导航栏/状态栏有额外主题或遮罩策略，本结论仅覆盖
  Pixel 10 Pro XL（AOSP Android 37）与 Redmi K90 PM（Android 17）。

### 2.4 系统栏保留

未进入 Immersive Mode；状态栏/导航栏、系统时间、电量/网络、返回/主页/最近任务入口、
手势导航区域均保留，仅调整颜色与 Edge-to-Edge 背景。

---

## 3. 急停边框与系统区域

`EmergencyBorder` 仍是独立占位的动态红黑边框（顶部/底部循环文字 + 左右纵向图案），本轮仅新增：

- `backgroundColor: Color`：由外部传入当前系统栏同一动画色，保证边框画布与系统栏融合；
- `collapse: Float (0..1)`：退出转场时顶部/底部警报条高度、左右栏宽度与透明度按比例收缩，
  同时冻结 marquee / 纵向循环（只收缩，不重写动画系统）；
- 原有 reduceMotion 行为保留：静态边框、完整文字与红黑配色。

布局仍保持：

```text
状态栏（EmergencyBlack，真实高度）
顶部警报条 /// 紧急停机 ///
左右警戒条 + 中央紧急内容
底部警报条
导航栏（EmergencyBlack，真实高度）
```

系统栏真实高度未压缩；滚动文字与图案不会覆盖系统图标/手势区域。

---

## 4. 急停退出转场（E-STOP CLEARED → SYSTEM REINITIALIZING → PRE-FLIGHT）

### 4.1 触发条件（只认 Service 真实状态）

`MainActivity` 的 `LaunchedEffect` 只有在以下全部成立时才启动转场：

1. 本进程内曾观察到 `uiState.emergencyStopped == true`；
2. 现在 `emergencyStopped == false`；
3. `uiState.estopResetPhase == EstopResetPhase.NONE`；
4. `uiState.estopResetCompletedAtMs > 0`（Service 确认复位流程完成）；
5. `serviceBound == true`。

点击 RESET、REQUESTED、RESETTING、SELF_CHECK、FAILED 都不会触发退出；失败时锁存保持，
继续停留在 Emergency Mode（截图 `10_reset_failed_stays_emergency.png`）。

### 4.2 阶段与时长

| 阶段 | 视觉 | 系统栏 | 时长 |
|---|---|---|---|
| `E-STOP CLEARED` | 中央显示 E-STOP CLEARED / 急停已解除；警报文字停止；边框开始收缩；保留红色强调 | EmergencyBlack | 380ms |
| `SYSTEM REINITIALIZING` | 中央切换 SYSTEM REINITIALIZING；边框淡出；背景黑 → `#203348`；强调色红 → 冰蓝 | 随背景渐变 | 560ms |
| 返回 PRE-FLIGHT | 真实 PRE-FLIGHT/自检页 | `#203348` | — |

总时长约 **940ms**（落在建议的 700–1000ms）。使用 Compose `Crossfade` / `animateColorAsState` /
`animateFloatAsState`，无新增依赖。

### 4.3 控制约束

- 转场**只是视觉**：不启动控制、不自动 START、不发送任何非零强度；
- 转场期间仍渲染 EMERGENCY 页面、返回键继续被消费；
- 转场完成后只导航到 **PRE-FLIGHT**（`E-STOP RESET → STARTUP SELF-CHECK → PRE-FLIGHT`），
  不会回到运行中的 OPERATION；
- 进程重建/重新打开应用：
  - 若锁存仍为 true → 直接 Emergency Mode；
  - 若锁存已清除 → 直接按真实状态进入 PRE-FLIGHT，**不播放退出动画、不依赖本地缓存标记**；
  - `estopWasLatched` 只用 `remember`（进程内），不写入 savedInstanceState。
- **减少动态效果**：App 启动时检测到 animator/transition scale 为 0 → 复位成功后**直接进入 PRE-FLIGHT**，
  不播放两段转场（已实测，见 5.2）。

---

## 5. 实际运行验证

### 5.1 环境

| 项 | 真机 | 模拟器 |
|---|---|---|
| 设备 | Redmi K90 PM | Pixel_10_Pro_XL (AVD) |
| Android / API | 17 / 37 | 37 |
| 分辨率 | 1200×2608 | 1344×2992 |
| 导航 | 三键（返回/主页/最近任务） | 手势；切换三键复测 |
| 构建 | arm64-v8a（最终交付 APK） | 临时 x86_64 验证构建（交付前已还原 arm64） |
| APP/设备 | 真实 V4 中继 + 本地假 DG-LAB APP（FAKE-COYOTE） | 同左 |

### 5.2 结果

| 场景 | 结果 |
|---|---|
| 普通页状态栏/导航栏 | ✅ 与 `#203348` 一致；真机像素采样系统栏与内容一致（截图色偏为面板色域转换，非应用色差） |
| 三键导航 | ✅ 图标可读；关闭 contrast enforcement 后无独立灰色/蓝色导航条；真机+模拟器均验证 |
| 手势导航 | ✅ 手势条区域与背景一致；模拟器默认手势导航验证 |
| 进入 E-STOP | ✅ 状态栏/导航栏同步为 EmergencyBlack；时间/电量/信号、返回/主页/最近任务均可见；边框从系统栏下方开始，不覆盖图标 |
| 边框滚动文字 | ✅ 未覆盖状态栏/导航栏（内容在 safeDrawingPadding 内） |
| 复位中 | ✅ REQUESTED/RESETTING/SELF_CHECK 保持 EmergencyBlack；无提前退出 |
| 复位成功转场 | ✅ CLEARED（黑底红字，边框收缩）→ REINITIALIZING（背景渐变到 #203348，边框消失）→ PRE-FLIGHT；系统栏与内容像素级同步 |
| 转场后 | ✅ 只回 PRE-FLIGHT；未自动开始控制；E-STOP RECOVERY 提示仍在 |
| 复位失败（no-ack APP） | ✅ 停留 Emergency Mode，黑系统栏，无退出转场，锁存保持 |
| 切后台再返回 | ✅ 仍为 Emergency Mode（黑系统栏） |
| 进程重建 | ✅ 锁存恢复、黑系统栏；`estop_latched=false` 时直接 PRE-FLIGHT |
| 减少动态效果 | ✅ 冷启动后 animator/transition scale=0：复位成功直接 PRE-FLIGHT，无两段转场 |
| 字体放大（1.3×） | ✅ 状态栏/导航栏正常；停止命令链自动改为纵向布局，`COMMAND/UNVERIFIED` 不再被拆成单字母；内容可滚动 |
| 不同屏幕高度 | ✅ 1344×2992 与 1200×2608 均通过 |
| 像素同步证据 | ✅ `E-STOP CLEARED` 帧：status/content/nav 均为 `(5,5,6)`；`REINITIALIZING` 帧三者均为 `(32,51,72)` |

### 5.3 截图 / 录屏

目录 `docs/screenshots/system-bars-2026-11-14/`：

1. `01_real_preflight_system_bars.png` — 真机 PRE-FLIGHT（三键导航，蓝色系统栏）
2. `02_real_operation_system_bars.png` — 真机 OPERATION（蓝色系统栏、无渐变接缝）
3. `03_real_estop_system_bars.png` — 真机 E-STOP（黑色状态栏/导航栏）
4. `04_real_estop_reset_dialog.png` — 真机复位确认（红黑对话框 + 黑系统栏）
5. `05_estop_cleared.png` — 第一阶段 E-STOP CLEARED（边框收缩中）
6. `06_system_reinitializing.png` — 第二阶段 SYSTEM REINITIALIZING（蓝底冰蓝强调）
7. `07_preflight_after_exit.png` — 转场完成回到 PRE-FLIGHT
8. `08_three_button_nav_bars.png` — 模拟器三键导航普通页
9. `09_estop_font_scale_1_3.png` — 急停页 1.3× 字体（纵向命令链）
10. `10_reset_failed_stays_emergency.png` — 复位失败仍保持紧急模式
11. `11_process_kill_restores_estop.png` — 进程重建后锁存恢复、黑系统栏
12. `12_gesture_nav_operation.png` — 模拟器手势导航 OPERATION
13. `exit_transition_real_device.mp4` — 真机退出转场录屏

---

## 6. 颜色备份 / 回滚

- 备份目录：`docs/theme-backup/polar-blue-2026-11-13/`
- 内容：旧版 `Color.kt`、`Theme.kt`、`Type.kt`、`colors.xml`、`themes.xml`、`MainActivity.kt`、
  `EmergencyScreen.kt`、`EmergencyBorder.kt` + 一键回滚 PowerShell 命令（见该目录 README）。
- 只想回滚颜色可只复制前 5 个文件；回滚 `MainActivity` 会同时移除本轮系统栏同步与退出转场。

---

## 7. 已知限制 / 未验证

1. **真实 BLE 设备联调未执行**：本轮仍是真实中继 + 假 DG-LAB APP；设备端停止状态仍为 UNVERIFIED。
2. 系统栏行为仅在 AOSP Android 37（Pixel 模拟器）与 Redmi K90 PM（Android 17）验证；
   其他 OEM 的导航栏主题/遮罩策略可能不同，`isNavigationBarContrastEnforced=false` 的效果需按机型复测。
3. 减少动态效果只在“App 启动时设置已生效”的场景验证；运行中修改系统设置后
   `rememberReduceMotion` 的缓存值不会自动刷新（与既有实现一致，重启应用后生效）。
4. 退出转场为 940ms 的固定节奏，未根据设备性能动态调整；未做 instrumented UI 测试（无限动画 idle 限制）。
5. 横屏未做专门双栏设计；大字号依赖滚动兜底。
