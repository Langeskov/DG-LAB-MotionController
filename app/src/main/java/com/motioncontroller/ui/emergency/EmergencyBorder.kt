package com.motioncontroller.ui.emergency

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncontroller.ui.components.rememberReduceMotion
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned
import kotlin.math.ceil
import kotlin.math.max

// ───────────────────────────── 尺寸 / 节奏常量 ─────────────────────────────
//
// 急停边框是「装饰层」：红黑工业警报风格（禁止红白条纹 / 霓虹 / 高频闪烁）。
// 所有节奏都走 LinearEasing 匀速循环，避免视觉抖动；全部时间常数集中在这里便于调参。

private const val EXPAND_DURATION_MS = 350
private const val TOP_SCROLL_DURATION_MS = 10_000
private const val BOTTOM_SCROLL_DURATION_MS = 11_000
private const val LEFT_RAIL_DURATION_MS = 6_000
private const val RIGHT_RAIL_DURATION_MS = 7_000

private val TOP_BAR_HEIGHT = 26.dp
private val BOTTOM_BAR_HEIGHT = 24.dp
private val RAIL_WIDTH = 16.dp

/** 横向滚动文字一份「单元」里文本与下一份之间的间隙（用 Spacer 实现，见 [AlertBar] 注释）。 */
private val MARQUEE_GAP = 28.dp

/** 纵向警戒图案的重复周期。 */
private val RAIL_UNIT_HEIGHT = 28.dp

// 深暗红底（spec 建议：RedDim 叠在 Background 上，或自绘 0xFF1A0707）
private val BAR_BACKGROUND = Color(0xFF1A0707)
private val RAIL_BACKGROUND = Color(0xFF120404)
private val RAIL_DARK = Color(0xFF0B0202)

/** 警报条 / 警戒条内侧 1dp 红色分隔线。 */
private val DIVIDER_COLOR = McColors.EmergencyRed.copy(alpha = 0.55f)

/**
 * E-STOP 全屏紧急模式的「动态警戒边框」。
 *
 * 布局是**独立占位**的（不是 overlay 覆盖在中央内容之上）：
 * ```
 * ┌─────────────────────────────┐  ← 顶部横向警报条（26dp，向左滚动 topText）
 * │▌                           ▐│
 * │▌        content()          ▐│  ← 中央业务内容完整可见、可访问
 * │▌                           ▐│
 * ├─────────────────────────────┤  ← 底部横向警报条（24dp，向右滚动 bottomText）
 * ```
 * 左右各 16dp 纵向警戒条用 Canvas 绘制循环工业图案（红黑短线段 + 箭头 + 方块）。
 *
 * 动画策略（性能约束）：
 *  - **无限循环动画**的值只在 `graphicsLayer { }` / `Canvas` 的 draw lambda 内读取，
 *    因此每帧只重绘图层 / 重画 canvas，**不触发逐帧重组**；不使用 withFrameNanos 手写循环。
 *  - **展开动画**（0 → 完整厚度，350ms）天然会改动布局尺寸，因此会在这 350ms 内重组装饰层；
 *    中央 content 的 alpha 通过 graphicsLayer 读取，不因展开而重组业务内容。
 *  - [reduceMotion] = true 时不创建任何无限动画、展开立即完成；红黑配色、完整文字、静态图案保留。
 *
 * 可访问性：警报条与两侧警戒条都挂了 `clearAndSetSemantics {}`（纯装饰，避免 TalkBack
 * 逐条朗读）；中央 content 的语义不受影响。
 *
 * 注意：本组件要求**有界高度**的父容器（内部 Column 使用了 weight(1f)）。
 * 系统栏安全区由调用方（MainActivity 的 safeDrawingPadding）负责，这里不再重复加 inset。
 *
 * @param topText 顶部警报条滚动文字（装饰用，会自动 uiCase() 处理）。
 * @param bottomText 底部警报条滚动文字，方向与顶部相反。
 * @param modifier 外部修饰符（通常 `Modifier.fillMaxSize()`）。
 * @param reduceMotion 是否减少动态效果（默认读系统「动画时长缩放」设置）。
 * @param collapse 0 = 完整边框；1 = 完全收起。用于复位成功后的退出转场（只改视觉，不改布局安全区）。
 * @param backgroundColor 边框区域的整块底色（默认 EmergencyBlack；退出转场时由调用方传入渐变色）。
 * @param content 中央业务内容；急停按钮等可交互元素应放在这里，保持可访问。
 */
@Composable
fun EmergencyBorder(
    topText: String,
    bottomText: String,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
    collapse: Float = 0f,
    collapseDurationMs: Int = 520,
    backgroundColor: Color = McColors.EmergencyBlack,
    content: @Composable BoxScope.() -> Unit,
) {
    // 首次组合后置 true → 触发 0 → 完整厚度的展开动画。只重组一次（之后尺寸动画各自独立）。
    val expandedState = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expandedState.value = true }
    val expanded = expandedState.value

    // 退出转场：边框整体向边缘收缩并淡出。只动画 scale，业务内容不参与重组。
    val collapseAnim by animateFloatAsState(
        targetValue = collapse.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else collapseDurationMs,
            easing = FastOutSlowInEasing,
        ),
        label = "estop-border-collapse",
    )
    val visible = (1f - collapseAnim).coerceIn(0f, 1f)
    val frozen = collapseAnim > 0.01f

    // 中央内容「小幅 alpha 淡入」(0.72 → 1.0)。只在 graphicsLayer lambda 内读值：
    // 动画每帧只更新图层，不会重组 content()。
    val contentAlpha = animateFloatAsState(
        targetValue = if (expanded) 1f else 0.72f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else EXPAND_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "estop-content-fade",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        AlertBar(
            text = topText,
            expanded = expanded,
            reduceMotion = reduceMotion,
            targetHeight = TOP_BAR_HEIGHT,
            heightScale = visible,
            alphaScale = visible,
            frozen = frozen,
            scrollDurationMillis = TOP_SCROLL_DURATION_MS,
            reverse = false,
            dividerOnBottom = true,
            label = "estop-banner-top",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            VerticalAlertRail(
                expanded = expanded,
                reduceMotion = reduceMotion,
                widthScale = visible,
                alphaScale = visible,
                frozen = frozen,
                durationMillis = LEFT_RAIL_DURATION_MS,
                reverse = false,
                dividerAtEnd = true,
                label = "estop-rail-left",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // reduceMotion 时直接不透明；否则读动画 State（图层级更新，无重组）。
                        alpha = if (reduceMotion) 1f else contentAlpha.value
                    },
            ) {
                content()
            }
            VerticalAlertRail(
                expanded = expanded,
                reduceMotion = reduceMotion,
                widthScale = visible,
                alphaScale = visible,
                frozen = frozen,
                durationMillis = RIGHT_RAIL_DURATION_MS,
                reverse = true,
                dividerAtEnd = false,
                label = "estop-rail-right",
            )
        }
        AlertBar(
            text = bottomText,
            expanded = expanded,
            reduceMotion = reduceMotion,
            targetHeight = BOTTOM_BAR_HEIGHT,
            heightScale = visible,
            alphaScale = visible,
            frozen = frozen,
            scrollDurationMillis = BOTTOM_SCROLL_DURATION_MS,
            reverse = true,
            dividerOnBottom = false,
            label = "estop-banner-bottom",
        )
    }
}

// ───────────────────────────── 顶部 / 底部横向警报条 ─────────────────────────────

/**
 * 横向滚动警报条（无缝 marquee）。
 *
 * 无缝原理：
 *  - 用 [rememberTextMeasurer] 测量「一份单元文本」的宽度（单元 = uiCase 文本 + 分隔点），
 *    再叠加一个固定 [MARQUEE_GAP] 的 Spacer 作为两份之间的间隙；
 *  - Row 里放 `copies` 份完全相同的单元（至少 2 份，按可见宽度自动加，保证任意宽度下都铺满）；
 *  - 用无限循环进度 `0 → 1` 驱动 `graphicsLayer.translationX = -unitWidth * progress`：
 *    位移恰好等于「一份单元」的宽度时立即回到 0，因为图案以 unit 为周期 → 无断层。
 *  - 底部条用 `translationX = -unitWidth * (1 - progress)`（反方向 + 不同周期）与顶部区分相位。
 *
 * 之所以「单元文本」以分隔点 `•` 结尾、再用 Spacer 制造间隙，而不是在文本尾部塞空格：
 * 尾随空格在部分文本排版路径里可能不计入测量宽度，导致「测量宽度 ≠ 实际渲染宽度」，
 * 循环就会逐周漂移出可见断层。末尾落在可见字符上可保证测量与渲染严格一致。
 */
@Composable
private fun AlertBar(
    text: String,
    expanded: Boolean,
    reduceMotion: Boolean,
    targetHeight: Dp,
    scrollDurationMillis: Int,
    reverse: Boolean,
    dividerOnBottom: Boolean,
    label: String,
    heightScale: Float = 1f,
    alphaScale: Float = 1f,
    frozen: Boolean = false,
) {
    val fraction = rememberExpandFraction(expanded, reduceMotion, "$label-expand")
    val barHeight = targetHeight * fraction * heightScale.coerceIn(0f, 1f)
    val alpha = (if (reduceMotion) 1f else fraction) * alphaScale.coerceIn(0f, 1f)

    // 无限滚动进度：reduceMotion 或退出转场收起时不创建/停止动画，文字保留但静止。
    val progress = rememberLoopProgressState(
        enabled = !reduceMotion && !frozen,
        durationMillis = scrollDurationMillis,
        label = "$label-marquee",
    )

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val unitText = remember(text) { marqueeUnit(text) }
    val textStyle = remember(unitText) {
        // 等宽 + 宽字距工业丝印；中文自动切紧凑字距（cjkTuned）。
        McType.dataSmall.copy(letterSpacing = 1.6.sp).cjkTuned(unitText)
    }
    val gapPx = with(density) { MARQUEE_GAP.toPx() }
    val unitWidthPx = remember(measurer, unitText, textStyle, gapPx) {
        val measured = measurer.measure(
            text = AnnotatedString(unitText),
            style = textStyle,
            softWrap = false,
            maxLines = 1,
        )
        measured.size.width.toFloat() + gapPx
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(BAR_BACKGROUND.copy(alpha = alpha))
            .clipToBounds()
            .clearAndSetSemantics { }, // 装饰层：不参与 TalkBack
    ) {
        val barWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.toFloat()
        } else {
            // 无界宽度（极罕见，如被放进横向滚动容器）：按 2 份单元铺满即可。
            unitWidthPx * 2f
        }
        val copies = remember(barWidthPx, unitWidthPx) {
            if (unitWidthPx <= 1f) 2 else max(2, ceil(barWidthPx / unitWidthPx).toInt() + 1)
        }
        val rowWidthPx = unitWidthPx * copies

        Row(
            modifier = Modifier
                // requiredWidth：允许 Row 比警报条更宽（内容溢出由父级 clipToBounds 裁剪），
                // 这样 graphicsLayer 的图层宽度覆盖全部副本，位移时不会被图层边界提前裁掉。
                .requiredWidth(with(density) { rowWidthPx.toDp() })
                .fillMaxHeight()
                .graphicsLayer {
                    // ⚠️ 每帧只读 State.value + 更新 translationX：图层级失效，不触发重组。
                    val p = progress.value
                    translationX = if (reverse) {
                        -unitWidthPx * (1f - p)
                    } else {
                        -unitWidthPx * p
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(copies) {
                Text(
                    text = unitText,
                    style = textStyle,
                    color = McColors.EmergencyRed.copy(alpha = alpha),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(MARQUEE_GAP))
            }
        }

        // 警报条内侧 1dp 红色分隔线（顶部条贴底、底部条贴顶）。
        Box(
            modifier = Modifier
                .align(if (dividerOnBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(DIVIDER_COLOR.copy(alpha = 0.55f * alpha)),
        )
    }
}

/** 横向滚动的「一份」文本：大小写处理后接一个可见分隔点，末尾不落在空白字符上（见 [AlertBar]）。 */
private fun marqueeUnit(text: String): String {
    val body = text.uiCase().trimEnd()
    return if (body.isEmpty()) "  •" else "$body   •"
}

// ───────────────────────────── 左右纵向警戒条 ─────────────────────────────

/**
 * 纵向警戒条（宽 16dp）：Canvas 绘制重复的工业警报图案（红黑短线段 / 向下箭头 / 小方块轮廓），
 * 用无限循环进度纵向平移；进度同样只在 draw lambda 内读取 → 每帧只重画，不重组。
 *
 * 平移量 = 一个图案周期（[RAIL_UNIT_HEIGHT]）的整数倍时图案完全重合 → 循环无跳变。
 */
@Composable
private fun VerticalAlertRail(
    expanded: Boolean,
    reduceMotion: Boolean,
    durationMillis: Int,
    reverse: Boolean,
    dividerAtEnd: Boolean,
    label: String,
    widthScale: Float = 1f,
    alphaScale: Float = 1f,
    frozen: Boolean = false,
) {
    val fraction = rememberExpandFraction(expanded, reduceMotion, "$label-expand")
    val railWidth = RAIL_WIDTH * fraction * widthScale.coerceIn(0f, 1f)
    val alpha = (if (reduceMotion) 1f else fraction) * alphaScale.coerceIn(0f, 1f)
    val progress = rememberLoopProgressState(
        enabled = !reduceMotion && !frozen,
        durationMillis = durationMillis,
        label = "$label-pattern",
    )

    Box(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(RAIL_BACKGROUND.copy(alpha = alpha))
            .clipToBounds()
            .clearAndSetSemantics { }, // 装饰层：不参与 TalkBack
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val unitPx = RAIL_UNIT_HEIGHT.toPx()
            val phasePx = if (reverse) unitPx * (1f - progress.value) else unitPx * progress.value
            val inset = 2.dp.toPx()
            val lineWidth = 1.5.dp.toPx()

            val accent = McColors.EmergencyRed.copy(alpha = 0.92f * alpha)
            val accentDim = McColors.EmergencyRedDim.copy(alpha = 0.85f * alpha)
            val dark = RAIL_DARK.copy(alpha = alpha)

            // 平移一个图案周期后绘制两遍（从 -unit 起画，覆盖顶部，直到超出底部）。
            translate(top = phasePx) {
                var y = -unitPx
                while (y < h) {
                    // ① 红黑交替的短线段（横向红色块）
                    drawRect(
                        color = accent,
                        topLeft = Offset(inset, y + unitPx * 0.06f),
                        size = Size(w - inset * 2f, unitPx * 0.14f),
                    )
                    // ② 暗红块
                    drawRect(
                        color = accentDim,
                        topLeft = Offset(0f, y + unitPx * 0.24f),
                        size = Size(w, unitPx * 0.10f),
                    )
                    // ③ 向下箭头（两条斜线构成 chevron）
                    val midX = w * 0.5f
                    drawLine(
                        color = accent,
                        start = Offset(inset, y + unitPx * 0.44f),
                        end = Offset(midX, y + unitPx * 0.60f),
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Square,
                    )
                    drawLine(
                        color = accent,
                        start = Offset(w - inset, y + unitPx * 0.44f),
                        end = Offset(midX, y + unitPx * 0.60f),
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Square,
                    )
                    // ④ E-STOP 小方块轮廓 + 暗红底
                    drawRect(
                        color = dark,
                        topLeft = Offset(w * 0.22f, y + unitPx * 0.70f),
                        size = Size(w * 0.56f, unitPx * 0.16f),
                    )
                    drawRect(
                        color = accentDim,
                        topLeft = Offset(w * 0.22f, y + unitPx * 0.70f),
                        size = Size(w * 0.56f, unitPx * 0.16f),
                        style = Stroke(width = lineWidth),
                    )
                    y += unitPx
                }
            }

            // 内侧 1dp 红色分隔线（左条贴右缘、右条贴左缘）。
            val dividerWidth = 1.dp.toPx()
            drawRect(
                color = DIVIDER_COLOR.copy(alpha = 0.55f * alpha),
                topLeft = Offset(if (dividerAtEnd) w - dividerWidth else 0f, 0f),
                size = Size(dividerWidth, h),
            )
        }
    }
}

// ───────────────────────────── 动画小工具 ─────────────────────────────

/**
 * 展开进度 0 → 1：[reduceMotion] = true 时直接返回 1f（边框立即完整，无展开动画）。
 *
 * 尺寸动画不可避免会在这 350ms 内重组对应装饰层，但只影响装饰层，内容层通过
 * graphicsLayer 读 alpha，不受展开影响。
 */
@Composable
private fun rememberExpandFraction(
    expanded: Boolean,
    reduceMotion: Boolean,
    label: String,
): Float {
    val animated = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else EXPAND_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = label,
    )
    return if (reduceMotion) 1f else animated.value
}

/**
 * 无限循环进度（0 → 1，LinearEasing，Restart）。
 *
 * [enabled] = false（reduceMotion）时**不创建**无限动画，直接复用固定的 0f State：
 * 视觉完全静止，也不会持续出帧耗电。
 *
 * 返回 State 而不是 Float：调用方必须把 `.value` 的读取放在 graphicsLayer / draw lambda 里，
 * 这样每帧只更新图层或重画，不触发重组。
 */
@Composable
private fun rememberLoopProgressState(
    enabled: Boolean,
    durationMillis: Int,
    label: String,
): State<Float> {
    var progress: State<Float> = remember { mutableStateOf(0f) }
    if (enabled) {
        val transition = rememberInfiniteTransition(label = label)
        progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = label,
        )
    }
    return progress
}
