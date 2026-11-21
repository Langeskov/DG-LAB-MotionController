package com.motioncontroller.ui.preflight

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.MeterBar
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.rememberReduceMotion
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import kotlinx.coroutines.delay

private const val STAGE_DURATION_MS = 500L
private const val PROGRESS_ANIMATION_MS = 460

/** 阶段文案顺序固定（设备启动丝印），不得插入其它阶段。 */
private fun bootStageTitle(strings: UiStrings, stage: Int): String = when (stage) {
    0 -> strings.bootInitializing
    1 -> strings.bootLinkEstablished
    else -> strings.bootSystemReady
}

/**
 * 进入 OPERATION 前的启动动效（纯展示覆盖层，不触碰任何业务状态）。
 *
 * 行为约定：
 *  - 只有 `active && allowed` 才显示并推进；`allowed=false` 时既不显示，也不回调 [onFinished]
 *    （由调用方决定不启用，绝不伪造 READY）；
 *  - 阶段：INITIALIZING → LINK ESTABLISHED → SYSTEM READY，每阶段 [STAGE_DURATION_MS]（500ms），
 *    总时长 1.5s（≤1.8s）；
 *  - 点击任意位置立即结束（“TAP TO SKIP”）；
 *  - 系统“减少动态效果”开启时立即 [onFinished]；
 *  - 覆盖层不阻塞任何安全操作（本页没有急停，OPERATION 页不依赖它）。
 */
@Composable
fun BootSequence(
    active: Boolean,
    allowed: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (active && allowed) {
        BootSequenceOverlay(
            onFinished = onFinished,
            modifier = modifier,
        )
    }
}

@Composable
private fun BootSequenceOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val strings = rememberUiStrings()
    var stage by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    // 幂等结束：点击跳过与计时器到达可能同时发生，只允许回调一次。
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            finish()
            return@LaunchedEffect
        }
        delay(STAGE_DURATION_MS)
        stage = 1
        delay(STAGE_DURATION_MS)
        stage = 2
        delay(STAGE_DURATION_MS)
        finish()
    }

    val targetProgress = when (stage) {
        0 -> 0.10f
        1 -> 0.55f
        else -> 1f
    }
    val progress by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else targetProgress,
        animationSpec = tween(durationMillis = PROGRESS_ANIMATION_MS),
        label = "boot-progress",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(McColors.Background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = finish,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(text = strings.appName, color = McColors.TextDim)
            Spacer(Modifier.height(20.dp))
            Text(
                text = bootStageTitle(strings, stage),
                style = McType.title,
                color = McColors.BlueAccent,
                maxLines = 1,
            )
            Spacer(Modifier.height(16.dp))
            MeterBar(
                fraction = progress,
                modifier = Modifier.width(200.dp),
                color = McColors.BlueAccent,
                height = 4.dp,
            )
            Spacer(Modifier.height(10.dp))
            DataText(
                text = fmt(strings.bootStageProgress, "%02d".format(stage + 1)),
                style = McType.dataSmall,
                color = McColors.SteelBlueDim,
            )
            Spacer(Modifier.height(28.dp))
            SectionLabel(text = strings.tapToSkip, color = McColors.TextDim)
        }
    }
}
