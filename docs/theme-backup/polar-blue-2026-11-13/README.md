# POLAR BLUE 主题备份（2026-11-13 → 2026-11-14）

本目录保存 **UI V2.0 第一版（2026-11-13）** 的主题与系统栏相关源文件，用于快速回滚。
当前版本（2026-11-14 系统栏/急停转场精修）把普通背景从 `#142C40` 调整为 `#203348`，
并加入系统栏状态同步与急停退出转场。

## 备份内容

| 文件 | 原路径 | 说明 |
|---|---|---|
| `Color.kt` | `app/src/main/java/com/motioncontroller/ui/theme/Color.kt` | 旧 POLAR Token（Background `#142C40` 等） |
| `Theme.kt` | `app/src/main/java/com/motioncontroller/ui/theme/Theme.kt` | M3 深色 scheme |
| `Type.kt` | `app/src/main/java/com/motioncontroller/ui/theme/Type.kt` | 字体样式（本次未改） |
| `colors.xml` | `app/src/main/res/values/colors.xml` | 启动窗口颜色 |
| `themes.xml` | `app/src/main/res/values/themes.xml` | 窗口/系统栏基础主题 |
| `MainActivity.kt` | `app/src/main/java/com/motioncontroller/MainActivity.kt` | 旧版 edge-to-edge 系统栏处理（无退出转场） |
| `EmergencyScreen.kt` | `app/src/main/java/com/motioncontroller/ui/emergency/EmergencyScreen.kt` | 旧版急停页面（无退出转场参数） |
| `EmergencyBorder.kt` | `app/src/main/java/com/motioncontroller/ui/emergency/EmergencyBorder.kt` | 旧版边框（无 collapse/背景参数） |

## 一键回滚

在仓库根目录（PowerShell）：

```powershell
$src = 'docs/theme-backup/polar-blue-2026-11-13'
Copy-Item "$src/Color.kt"          'app/src/main/java/com/motioncontroller/ui/theme/Color.kt' -Force
Copy-Item "$src/Theme.kt"          'app/src/main/java/com/motioncontroller/ui/theme/Theme.kt' -Force
Copy-Item "$src/Type.kt"           'app/src/main/java/com/motioncontroller/ui/theme/Type.kt' -Force
Copy-Item "$src/colors.xml"        'app/src/main/res/values/colors.xml' -Force
Copy-Item "$src/themes.xml"        'app/src/main/res/values/themes.xml' -Force
Copy-Item "$src/MainActivity.kt"   'app/src/main/java/com/motioncontroller/MainActivity.kt' -Force
Copy-Item "$src/EmergencyScreen.kt" 'app/src/main/java/com/motioncontroller/ui/emergency/EmergencyScreen.kt' -Force
Copy-Item "$src/EmergencyBorder.kt" 'app/src/main/java/com/motioncontroller/ui/emergency/EmergencyBorder.kt' -Force

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --offline --no-configuration-cache
```

> 注意：回滚 `MainActivity.kt` 会同时移除急停退出转场与系统栏状态同步逻辑；
> 若只想回滚颜色而保留系统栏/转场，只复制 `Color.kt / Theme.kt / colors.xml / themes.xml` 即可。

## 旧版关键色值（用于人工核对）

- Background `#142C40`，BackgroundDeep `#0E2233`，Surface `#19344A`，SurfaceRaised `#24445B`，
  SurfaceInset `#0C2031`，Border `#34536A`，BorderStrong `#49677D`，Divider `#294255`
- IceBlue `#A6E9F8`，BlueAccent `#62CDEB`，TextPrimary `#EDF6FA`，TextSecondary `#B5C8D3`
- E-STOP：EmergencyBlack `#050506`、EmergencyPanel `#160A0A`、EmergencyRed `#E01F1F`、BrightRed `#FF3B30`
