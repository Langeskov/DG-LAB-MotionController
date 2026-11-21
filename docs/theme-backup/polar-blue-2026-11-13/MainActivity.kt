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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.service.ControllerService
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.emergency.EmergencyScreen
import com.motioncontroller.ui.engineering.EngineeringScreen
import com.motioncontroller.ui.i18n.LanguageMode
import com.motioncontroller.ui.i18n.LanguagePreferences
import com.motioncontroller.ui.i18n.LocalUiStrings
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.uiStringsFor
import com.motioncontroller.ui.model.ConnectionMode
import com.motioncontroller.ui.model.DiagnosticsText
import com.motioncontroller.ui.operation.OperationScreen
import com.motioncontroller.ui.preflight.PreflightScreen
import com.motioncontroller.ui.theme.MotionControllerTheme
import kotlinx.coroutines.Job
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        appVersion = readAppVersion()
        languageMode = LanguagePreferences.read(this)
        requestRuntimePermissions()

        setContent {
            MotionControllerTheme {
                val strings = uiStringsFor(languageMode)
                CompositionLocalProvider(LocalUiStrings provides strings) {
                var screenOrdinal by rememberSaveable { mutableIntStateOf(Screen.PREFLIGHT.ordinal) }
                val screen = Screen.entries[screenOrdinal.coerceIn(0, Screen.entries.lastIndex)]

                // E-STOP 锁存是最高优先级页面：无论当前保存的是哪个页面、无论应用是否刚被系统重建，
                // 只要后端锁存为 true 就必须渲染全屏 EMERGENCY MODE（不能让导航状态掩盖安全状态）。
                val renderScreen = if (uiState.emergencyStopped) Screen.EMERGENCY else screen

                // 复位成功（锁存解除）后进入 STARTUP SELF-CHECK → PRE-FLIGHT，由用户重新确认启动；
                // 这里只负责导航，不启动任何控制、不发送输出。
                // 锁存生效时把保存的页面也切到 EMERGENCY（进程重建/重新打开应用时同样生效）。
                LaunchedEffect(uiState.emergencyStopped) {
                    if (uiState.emergencyStopped) {
                        screenOrdinal = Screen.EMERGENCY.ordinal
                    } else if (screenOrdinal == Screen.EMERGENCY.ordinal) {
                        screenOrdinal = Screen.PREFLIGHT.ordinal
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

                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (renderScreen) {
                        Screen.PREFLIGHT -> PreflightScreen(
                            state = uiState,
                            serviceBound = serviceBound,
                            appVersion = appVersion,
                            onSelectMode = { mode -> selectMode(mode) },
                            onCopyAddress = { copyText(strings.copyLabelAddress, uiState.address) },
                            onRetryService = { restartEngine(uiState.bindHost, uiState.port) },
                            onOpenEngineering = openEngineering,
                            onEnterOperation = enterOperation,
                        )

                        Screen.OPERATION -> OperationScreen(
                            state = uiState,
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

                        Screen.EMERGENCY -> EmergencyScreen(
                            state = uiState,
                            // 复位是完整的异步安全流程（重发停止命令 → 等 APP 回执 → 自检 → 清锁存），
                            // 不是简单地“把布尔值改回 false”。
                            onRequestReset = { service?.requestEmergencyReset() },
                            // 重复下发急停是幂等的：控制器会再次排队 A/B 归零 + clear。
                            onReissueStop = { service?.emergencyStop() },
                            // 恢复本地中继/引擎（急停期间不允许普通控制，但允许恢复链路以完成安全复位）。
                            onRetryRelay = { restartEngine(uiState.bindHost, uiState.port) },
                        )

                        Screen.ENGINEERING -> EngineeringScreen(
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
