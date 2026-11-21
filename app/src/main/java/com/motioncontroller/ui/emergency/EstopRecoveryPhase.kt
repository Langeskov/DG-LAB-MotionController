package com.motioncontroller.ui.emergency

/**
 * 急停复位成功后的**纯 UI 恢复阶段**（业务状态仍以 ControllerService 为唯一真实来源）。
 *
 * NONE → RELEASED（E-STOP RELEASED：marquee 停止、红黑边框收缩，背景保持 EmergencyBlack）
 *      → CHECKING（SYSTEM REINITIALIZING：边框已完全退出；POLAR BLUE 恢复检查，检查现有会话，不重启服务）
 *      → RESTORED（CONTROL SESSION RESTORED：进入 OPERATION，控制保持 DISARMED）
 *      → NONE（恢复成功，普通页面正常交互）
 *
 * 该枚举不参与控制/急停状态判断；进程重建时不得由本地缓存推断页面。
 */
enum class EstopRecoveryPhase {
    NONE,
    RELEASED,
    CHECKING,
    RESTORED,
}
