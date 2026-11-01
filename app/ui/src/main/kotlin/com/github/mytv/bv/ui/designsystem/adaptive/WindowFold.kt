package com.github.mytv.bv.ui.designsystem.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

enum class FoldOrientation { Vertical, Horizontal }

/**
 * 当前窗口的折叠边 / 铰链。
 *
 * 只是对 [FoldingFeature] 的 Compose 快照，不判断设备型号：
 * - [separating] 表示折叠边把窗口分成两个逻辑区域（半开、双屏跨屏、物理铰链）；
 * - [occluding] 表示折叠区域完全遮挡内容（双屏的物理铰链）。
 *
 * 布局只在 [separating] 时分区；[occluding] 的区域一律留空。
 */
@Immutable
data class WindowFold(
    val orientation: FoldOrientation,
    val separating: Boolean,
    val occluding: Boolean,
    /** 折叠区域在窗口坐标中的起点（左 / 上）。 */
    val start: Dp,
    /** 折叠区域的厚度（宽 / 高），可能为 0。 */
    val thickness: Dp,
) {
    val isVertical: Boolean get() = orientation == FoldOrientation.Vertical
    val isHorizontal: Boolean get() = orientation == FoldOrientation.Horizontal

    /** 双屏跨屏：即使状态是 FLAT，也按物理铰链分区。 */
    val isDualScreen: Boolean get() = separating && occluding
}

/** 折叠边分出的两片内容区；[first] 为左 / 上，[second] 为右 / 下，[hinge] 是不可用区域。 */
@Immutable
data class FoldPanes(
    val vertical: Boolean,
    val first: Dp,
    val hinge: Dp,
    val second: Dp,
) {
    /** 两片都够宽 / 够高时才值得分区，否则保留原布局。 */
    fun isUsable(minPane: Dp = 300.dp): Boolean = first >= minPane && second >= minPane
}

val LocalWindowFold = compositionLocalOf<WindowFold?> { null }

/** 应用外壳（导航轨）占用后，NavHost 内容区左缘在窗口坐标中的偏移。 */
val LocalContentStartInset = compositionLocalOf { 0.dp }

/**
 * 监听当前 Activity 的 [WindowInfoTracker]。非折叠设备或没有折叠功能时返回 null。
 * 窗口变化（折叠 / 展开 / 双屏 / 多窗口拖动）时自动更新，不重建页面。
 */
@Composable
fun rememberWindowFold(): WindowFold? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    var feature by remember(activity) { mutableStateOf<FoldingFeature?>(null) }

    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                feature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
            }
    }

    return remember(feature, density) {
        feature?.let { fold ->
            val vertical = fold.orientation == FoldingFeature.Orientation.VERTICAL
            val bounds = fold.bounds
            WindowFold(
                orientation = if (vertical) FoldOrientation.Vertical else FoldOrientation.Horizontal,
                separating = fold.isSeparating,
                occluding = fold.occlusionType == FoldingFeature.OcclusionType.FULL,
                start = with(density) { (if (vertical) bounds.left else bounds.top).toDp() },
                thickness = with(density) {
                    (if (vertical) bounds.width() else bounds.height()).toDp()
                },
            )
        }
    }
}

/**
 * 当前窗口的分区宽度。没有分隔、或在多窗口里折叠边落在窗口外时返回 null。
 * 宽度取应用窗口（不是物理屏幕），扣除外壳导航轨占用的 [LocalContentStartInset]。
 */
@Composable
fun rememberFoldPanes(): FoldPanes? {
    val fold = LocalWindowFold.current ?: return null
    if (!fold.separating) return null
    val configuration = LocalConfiguration.current
    val startInset = LocalContentStartInset.current
    val windowWidth = configuration.screenWidthDp.dp
    val windowHeight = configuration.screenHeightDp.dp
    return remember(fold, windowWidth, windowHeight, startInset) {
        if (fold.isVertical) {
            FoldPanes(
                vertical = true,
                first = (fold.start - startInset).coerceAtLeast(0.dp),
                hinge = fold.thickness,
                second = (windowWidth - fold.start - fold.thickness).coerceAtLeast(0.dp),
            )
        } else {
            FoldPanes(
                vertical = false,
                first = fold.start.coerceAtLeast(0.dp),
                hinge = fold.thickness,
                second = (windowHeight - fold.start - fold.thickness).coerceAtLeast(0.dp),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
