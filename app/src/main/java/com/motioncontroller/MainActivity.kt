package com.motioncontroller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.service.ControllerService
import com.motioncontroller.service.EstopResetPhase
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.rememberReduceMotion
import com.motioncontroller.ui.emergency.EmergencyScreen
import com.motioncontroller.ui.emergency.EstopRecoveryPhase
import com.motioncontroller.ui.emergency.RecoveryScreen
import com.motioncontroller.ui.engineering.EngineeringScreen
import com.motioncontroller.ui.i18n.LanguageMode
import com.motioncontroller.ui.i18n.LanguagePreferences
import com.motioncontroller.ui.i18n.LocalUiStrings
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.uiStringsFor
import com.motioncontroller.ui.model.ConnectionMode
import com.motioncontroller.ui.model.DiagnosticsText
import com.motioncontroller.ui.model.RecoveryModel
import com.motioncontroller.ui.operation.OperationScreen
import com.motioncontroller.ui.preflight.PreflightScreen
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.MotionControllerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用外壳：只负责
 *  - 前台服务绑定与 UiState 收集；
 *  - 三个状态之间的导航（PRE-FLIGHT → OPERATION ↔ ENGINEERING）；
 *  - 系统交互（剪贴板、分享导出、运行时权限、edge-to-edge 深色系统栏）。
 *
 * 所有业务状态都在 [ControllerService]；本类不模拟、不缓存任何控制状态。
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { PREFLIGHT, OPERATION, ENGINEERING, EMERGENCY }

    private var service: ControllerService? = null
    private var collectJob: Job? = null
    private var uiState by mutableStateOf(UiState())
    private var serviceBound by mutableStateOf(false)
    private var appVersion: String = "1.0"

    /** 界面语言模式（SYSTEM/ZH/EN），持久化在 SharedPreferences（键 `ui_language`）。 */
    private var languageMode by mutableStateOf(LanguageMode.SYSTEM)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ControllerService.LocalBinder)?.service() ?: return
            service = svc
            serviceBound = true
            collectJob?.cancel()
            collectJob = lifecycleScope.launch {
                svc.uiState.collect { uiState = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：应用背景延伸到状态栏/导航栏后方；系统栏保持透明，颜色由页面根背景决定。
        // 系统图标始终用浅色（SystemBarStyle.dark = 深色底 + 浅色图标）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Android 10+ 的三键导航默认会给透明导航栏加对比度遮罩，导致普通页/急停页
        // 出现一条与页面背景不一致的灰色/半透明区域。这里关闭系统自动遮罩，改由
        // 应用根背景精确绘制（POLAR Blue 或 EmergencyBlack）；浅色图标在两种背景下对比度足够。
        // 手势导航不受该遮罩影响；三键导航的图标可读性在验收中单独检查。
        window.isNavigationBarContrastEnforced = false
        appVersion = readAppVersion()
        languageMode = LanguagePreferences.read(this)
        requestRuntimePermissions()

        setContent {
            MotionControllerTheme {
                val strings = uiStringsFor(languageMode)
                CompositionLocalProvider(LocalUiStrings provides strings) {
                var screenOrdinal by rememberSaveable { mutableIntStateOf(Screen.PREFLIGHT.ordinal) }
                val screen = Screen.entries[screenOrdinal.coerceIn(0, Screen.entries.lastIndex)]
                val reduceMotion = rememberReduceMotion()

                // E-STOP 复位后的恢复阶段（纯 UI；业务状态仍由 Service 唯一决定）。
                // 不用 rememberSaveable：进程重建不重放动画、不根据本地缓存推断页面。
                var recoveryOrdinal by remember { mutableIntStateOf(EstopRecoveryPhase.NONE.ordinal) }
                val recoveryPhase = EstopRecoveryPhase.entries[
                    recoveryOrdinal.coerceIn(0, EstopRecoveryPhase.entries.lastIndex)
                ]
                // 仅在本进程内记录“曾经锁存”，区分「刚复位成功」与「进程重建时本来就未锁存」。
                var estopWasLatched by remember { mutableStateOf(false) }
                val currentUiState by rememberUpdatedState(uiState)

                val recoveryVisual = recoveryPhase != EstopRecoveryPhase.NONE
                val showEmergency = uiState.emergencyStopped ||
                    recoveryPhase == EstopRecoveryPhase.RELEASED
                val showRecoveryCheck = recoveryPhase == EstopRecoveryPhase.CHECKING
                val showRestoredBanner = recoveryPhase == EstopRecoveryPhase.RESTORED

                // 进程重建时 saved screen 可能仍是 EMERGENCY，但锁存已经清除：
                // 不允许渲染“假急停页”，由下面的恢复检查决定 OPERATION / PRE-FLIGHT。
                val effectiveScreen = if (
                    screen == Screen.EMERGENCY &&
                    !uiState.emergencyStopped &&
                    !recoveryVisual
                ) {
                    Screen.PREFLIGHT
                } else {
                    screen
                }
                val renderScreen = when {
                    showEmergency || showRecoveryCheck -> Screen.EMERGENCY
                    else -> effectiveScreen
                }

                // 系统栏背景与页面根背景使用同一个动画色值：
                //  NORMAL → #203348；EMERGENCY / RESETTING / E-STOP RELEASED → EmergencyBlack；
                //  RECOVERY CHECK / RESTORED → #203348（边框已退出后才开始过渡，避免红蓝重叠）。
                val systemBarSurface by animateColorAsState(
                    targetValue = when {
                        uiState.emergencyStopped -> McColors.EmergencyBlack
                        recoveryPhase == EstopRecoveryPhase.RELEASED -> McColors.EmergencyBlack
                        else -> McColors.Background
                    },
                    animationSpec = tween(durationMillis = if (reduceMotion) 0 else 320),
                    label = "system-bar-surface",
                )

                // 复位成功（Service 已清除锁存并完成自检）→ E-STOP RELEASED
                // → RECOVERY CHECK（真实检查现有会话）→ OPERATION；失败才回 PRE-FLIGHT。
                // 请求复位/复位中的状态（REQUESTED/RESETTING/SELF_CHECK/FAILED）绝不触发退出。
                LaunchedEffect(
                    serviceBound,
                    uiState.emergencyStopped,
                    uiState.estopResetPhase,
                    uiState.estopResetCompletedAtMs,
                ) {
                    if (!serviceBound) return@LaunchedEffect
                    if (uiState.emergencyStopped) {
                        estopWasLatched = true
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                        if (screenOrdinal != Screen.EMERGENCY.ordinal) {
                            screenOrdinal = Screen.EMERGENCY.ordinal
                        }
                        return@LaunchedEffect
                    }
                    // 本进程从未见过锁存：进程重建/冷启动。具体落页交给
                    // 单独的“服务就绪后恢复” effect，避免服务尚未启动时误判为失败。
                    if (!estopWasLatched) return@LaunchedEffect
                    // 见过锁存但现在未锁存：必须等 Service 明确完成复位。
                    if (uiState.estopResetPhase != EstopResetPhase.NONE) return@LaunchedEffect
                    if (uiState.estopResetCompletedAtMs <= 0L) return@LaunchedEffect
                    if (recoveryPhase != EstopRecoveryPhase.NONE) return@LaunchedEffect

                    estopWasLatched = false

                    // 减少动态效果：只做静态状态切换，不影响业务状态。
                    if (reduceMotion) {
                        val snapshot = RecoveryModel.derive(currentUiState, serviceBound, strings)
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                        screenOrdinal = if (snapshot.canRestore) {
                            Screen.OPERATION.ordinal
                        } else {
                            Screen.PREFLIGHT.ordinal
                        }
                        return@LaunchedEffect
                    }

                    // Stage 1: E-STOP RELEASED —— 边框收缩 240ms；背景保持 EmergencyBlack。
                    recoveryOrdinal = EstopRecoveryPhase.RELEASED.ordinal
                    delay(280)
                    if (currentUiState.emergencyStopped) {
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                        return@LaunchedEffect
                    }

                    // Stage 2: RECOVERY CHECK —— 边框已完全退出后才切换 POLAR BLUE；
                    // 只读取现有 UiState（不重启服务/Relay、不重新握手），最多等待 2.6s 让状态稳定。
                    recoveryOrdinal = EstopRecoveryPhase.CHECKING.ordinal
                    var waitedMs = 0
                    var canRestore = false
                    while (waitedMs < 2600) {
                        delay(100)
                        waitedMs += 100
                        val snapshot = RecoveryModel.derive(currentUiState, serviceBound, strings)
                        if (snapshot.canRestore && waitedMs >= 450) {
                            canRestore = true
                            break
                        }
                    }
                    if (currentUiState.emergencyStopped) {
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                        return@LaunchedEffect
                    }
                    if (canRestore) {
                        // Stage 3: OPERATION RESTORED —— 控制仍为 DISARMED，只有视觉提示。
                        recoveryOrdinal = EstopRecoveryPhase.RESTORED.ordinal
                        screenOrdinal = Screen.OPERATION.ordinal
                        delay(900)
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                    } else {
                        // 真实检查失败：回启动自检/连接恢复页，不伪造成 CONNECTED。
                        recoveryOrdinal = EstopRecoveryPhase.NONE.ordinal
                        screenOrdinal = Screen.PREFLIGHT.ordinal
                    }
                }

                // 进程重建：saved screen 可能是 EMERGENCY，但锁存已清除。
                // 等服务稳定后按真实现有会话决定 OPERATION / PRE-FLIGHT，不播放转场、不猜测。
                LaunchedEffect(serviceBound, uiState.serviceRunning, uiState.engineLoading) {
                    if (!serviceBound || uiState.engineLoading) return@LaunchedEffect
                    if (uiState.emergencyStopped || recoveryVisual || estopWasLatched) return@LaunchedEffect
                    if (!uiState.serviceRunning && uiState.engineError == null) return@LaunchedEffect
                    if (screenOrdinal != Screen.EMERGENCY.ordinal) return@LaunchedEffect
                    val snapshot = RecoveryModel.derive(uiState, serviceBound, strings)
                    screenOrdinal = if (snapshot.canRestore) {
                        Screen.OPERATION.ordinal
                    } else {
                        Screen.PREFLIGHT.ordinal
                    }
                }

                // 进入 OPERATION 的唯一入口：必须在“DG-LAB APP 已连接”时成立。
                // 修复真机反馈问题1（偶发未连接就进控制页）：历史上有两条绕过门禁的路径——
                // ① PRE-FLIGHT 齿轮进设置后按返回键固定跳 OPERATION；② 进程重建恢复出旧的 OPERATION。
                // 现在所有进入 OPERATION 的跳转都过这里，未连接一律落回 PRE-FLIGHT。
                val enterOperation: () -> Unit = {
                    screenOrdinal = if (uiState.link.appConnected) {
                        Screen.OPERATION.ordinal
                    } else {
                        Screen.PREFLIGHT.ordinal
                    }
                }

                // 工程页返回语义：回到进入前的页面（PRE-FLIGHT 或 OPERATION），
                // 目标是 OPERATION 时同样过 [enterOperation] 门禁。
                var engineeringReturn by rememberSaveable {
                    mutableIntStateOf(Screen.PREFLIGHT.ordinal)
                }
                val openEngineering: () -> Unit = {
                    engineeringReturn = if (screen == Screen.OPERATION) {
                        Screen.OPERATION.ordinal
                    } else {
                        Screen.PREFLIGHT.ordinal
                    }
                    screenOrdinal = Screen.ENGINEERING.ordinal
                }
                val closeEngineering: () -> Unit = {
                    if (engineeringReturn == Screen.OPERATION.ordinal) {
                        enterOperation()
                    } else {
                        screenOrdinal = Screen.PREFLIGHT.ordinal
                    }
                }
                BackHandler(enabled = renderScreen == Screen.ENGINEERING) {
                    closeEngineering()
                }
                // 紧急模式不可用返回键退出：必须显式复位（避免用户误以为“退出了就安全了”）。
                BackHandler(enabled = renderScreen == Screen.EMERGENCY) {
                    // 消费返回事件，不做任何导航。
                }

                // 重建守卫：Android 会通过 savedInstanceState 恢复页面（进程被杀后从任务栈返回等），
                // 但恢复出来的 OPERATION 可能对应“APP 未连接”的真实链路状态。
                // 活动重建后首个稳定状态评估一次：OPERATION 且 APP 未连接 → 退回 PRE-FLIGHT；
                // 会话中途 APP 短暂掉线不会触发（评估只做一次）。
                var restoreGuardPending by remember { mutableStateOf(true) }
                LaunchedEffect(serviceBound, uiState.engineLoading, uiState.serviceRunning) {
                    if (!restoreGuardPending || !serviceBound || uiState.engineLoading) return@LaunchedEffect
                    if (!uiState.serviceRunning && uiState.engineError == null) return@LaunchedEffect
                    restoreGuardPending = false
                    if (renderScreen == Screen.OPERATION && !uiState.link.appConnected) {
                        screenOrdinal = Screen.PREFLIGHT.ordinal
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        // 根背景覆盖整个窗口（包括状态栏/导航栏后方），系统栏透明 → 颜色连续。
                        .background(systemBarSurface)
                        .safeDrawingPadding(),
                ) {
                    when {
                        showEmergency -> EmergencyScreen(
                            state = uiState,
                            onRequestReset = { service?.requestEmergencyReset() },
                            onReissueStop = { service?.emergencyStop() },
                            onRetryRelay = { restartEngine(uiState.bindHost, uiState.port) },
                            released = recoveryPhase == EstopRecoveryPhase.RELEASED,
                            backgroundColor = systemBarSurface,
                        )

                        showRecoveryCheck -> RecoveryScreen(
                            state = uiState,
                            serviceBound = serviceBound,
                            backgroundColor = systemBarSurface,
                        )

                        renderScreen == Screen.PREFLIGHT -> PreflightScreen(
                            state = uiState,
                            serviceBound = serviceBound,
                            appVersion = appVersion,
                            onSelectMode = { mode -> selectMode(mode) },
                            onCopyAddress = { copyText(strings.copyLabelAddress, uiState.address) },
                            onRetryService = { restartEngine(uiState.bindHost, uiState.port) },
                            onOpenEngineering = openEngineering,
                            onEnterOperation = enterOperation,
                        )

                        renderScreen == Screen.OPERATION -> OperationScreen(
                            state = uiState,
                            // 恢复成功后的短暂提示，放在 OPERATION 正常内容流中，不覆盖标题/链路栏。
                            recoveryBanner = showRestoredBanner,
                            onArm = { service?.arm() },
                            onDisarm = { service?.disarm() },
                            onEmergencyStop = { service?.emergencyStop() },
                            onTakeManualControl = { index -> service?.overrideMotionClass(index) },
                            onSelectManualClass = { index -> service?.overrideMotionClass(index) },
                            onReturnToAuto = {
                                val ok = service?.returnToAuto() ?: false
                                if (!ok) toast(strings.toastReturnToAutoRejected)
                            },
                            onManualOffset = { service?.setManualOffset(it) },
                            onWaveConfig = { cfg -> service?.setWaveConfig(cfg) },
                            onOpenEngineering = openEngineering,
                        )

                        renderScreen == Screen.EMERGENCY -> EmergencyScreen(
                            state = uiState,
                            // 复位是完整的异步安全流程（重发停止命令 → 等 APP 回执 → 自检 → 清锁存），
                            // 不是简单地“把布尔值改回 false”。
                            onRequestReset = { service?.requestEmergencyReset() },
                            // 重复下发急停是幂等的：控制器会再次排队 A/B 归零 + clear。
                            onReissueStop = { service?.emergencyStop() },
                            // 恢复本地中继/引擎（急停期间不允许普通控制，但允许恢复链路以完成安全复位）。
                            onRetryRelay = { restartEngine(uiState.bindHost, uiState.port) },
                            backgroundColor = systemBarSurface,
                        )

                        renderScreen == Screen.ENGINEERING -> EngineeringScreen(
                            state = uiState,
                            appVersion = appVersion,
                            languageMode = languageMode,
                            onLanguageModeChange = { mode -> applyLanguageMode(mode) },
                            onBack = closeEngineering,
                            onCopyAddress = { copyText(strings.copyLabelAddress, uiState.address) },
                            onRestartRelay = { host, port -> restartEngine(host, port) },
                            onApplyMapping = { cfg: MappingConfig -> service?.updateMappingConfig(cfg) },
                            onManualOffset = { service?.setManualOffset(it) },
                            onCopyLogs = {
                                copyText(
                                    strings.shareSubjectLogs,
                                    uiState.logs.takeLast(120).joinToString("\n"),
                                )
                            },
                            onExportDiagnostics = {
                                shareText(
                                    strings.shareSubjectDiagnostics,
                                    DiagnosticsText.build(uiState, appVersion),
                                )
                            },
                        )
                    }
                }
                }
            }
        }

        val intent = Intent(this, ControllerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, ControllerService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        collectJob?.cancel()
        collectJob = null
        serviceBound = false
        runCatching { unbindService(connection) }
    }

    // ───────────────────────── 导航 / 服务操作 ─────────────────────────

    private fun selectMode(mode: ConnectionMode) {
        val currentMode = ConnectionMode.ofBindHost(uiState.bindHost)
        if (currentMode == mode && uiState.serviceRunning && !uiState.engineLoading) return
        restartEngine(mode.bindHost, uiState.port)
    }

    /**
     * 重启中继/引擎（模式切换、端口变更、启动失败重试共用）。
     * service.startEngine 在已有 desiredEngine 时会直接返回，因此必须先 stopEngine。
     */
    private fun restartEngine(host: String, port: Int) {
        val svc = service ?: return
        svc.stopEngine()
        svc.startEngine(host, port)
    }

    // ───────────────────────── 系统交互 ─────────────────────────

    /** 当前语言模式对应的文案（组合之外的回调/Toast 用）。 */
    private fun currentStrings(): UiStrings = uiStringsFor(languageMode)

    /** 切换界面语言：立即生效（状态驱动重组）并持久化。（不叫 setLanguageMode：会与属性 setter JVM 签名冲突） */
    private fun applyLanguageMode(mode: LanguageMode) {
        languageMode = mode
        LanguagePreferences.write(this, mode)
    }

    private fun copyText(label: String, text: String) {
        if (text.isBlank()) {
            toast(currentStrings().toastNothingToCopy)
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(currentStrings().toastCopied)
    }

    private fun shareText(subject: String, body: String) {
        if (body.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, currentStrings().shareChooserDiagnostics))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun readAppVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    private fun requestRuntimePermissions() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (required.isNotEmpty()) {
            permissionLauncher.launch(required.toTypedArray())
        }
    }
}
