package com.motioncontroller.ui.model

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState

/**
 * 普通用户界面展示的“当前运动模式”：只暴露生效类别（AUTO = 模型稳定类别；MANUAL = 用户指定），
 * 不展示模型内部预测、置信度、完整分类结果。
 */
data class MotionDisplay(
    val classIndex: Int,
    val code: String,
    val available: Boolean,
) {
    val codeText: String get() = if (available) code else "—"
}

object MotionDisplayModel {

    /** 生效类别（人工接管优先）。 */
    fun effective(state: UiState): MotionDisplay =
        of(state.manualClass ?: state.effectiveClass, available = (state.manualClass ?: state.effectiveClass) >= 0)

    /** 模型类别（仅 ENGINEERING 使用；普通界面不得展示）。 */
    fun model(state: UiState): MotionDisplay = of(state.stableClass, available = state.stableClass >= 0)

    fun of(classIndex: Int, available: Boolean = classIndex >= 0): MotionDisplay {
        val cls = MotionClass.of(classIndex)
        return MotionDisplay(
            classIndex = if (cls == null) -1 else classIndex,
            code = cls?.name ?: "—",
            available = available && cls != null,
        )
    }
}
