package com.github.mytv.bv.ui.feature.shorts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.entity.ShortsAfterEnd
import com.github.mytv.bv.entity.ShortsDanmakuArea
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.player.danmaku.DanmakuLaneDensity
import com.github.mytv.bv.player.entity.DanmakuType
import com.github.mytv.bv.player.entity.Resolution
import com.github.mytv.bv.ui.designsystem.component.BvChip
import com.github.mytv.bv.ui.designsystem.component.BvSlider
import com.github.mytv.bv.ui.designsystem.component.BvSwitch
import com.github.mytv.bv.ui.designsystem.component.SegmentedRow
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.viewmodel.ShortsViewModel
import java.util.Locale

/**
 * 刷刷播放菜单（设计稿「菜单」一节）：一份模型，三种外壳。
 * 手机是底部面板（[ShortsMenuContent]），平板是信息栏的「设置」页（同一个 [ShortsMenuContent]），
 * 电视是刷刷面板（ShortsTv 里按分类展示同样的选项）。
 *
 * 选项是主播放器 PlayerMenuState 的子集，加上刷刷自己的「播完后」：
 * - 弹幕开关、区域刷刷单独记；样式（不透明度、字号、密度、类型）与主播放器共用；
 * - 画质、倍速只在本次会话有效（倍速跨条保持，长按 2× 临时叠加）；
 * - 播完后存在 Prefs.shortsAfterEnd。
 */
@Stable
internal class ShortsMenuState(
    val vm: ShortsViewModel,
    val pool: ShortsPlayerPool,
    val danmaku: ShortsDanmaku,
    val actions: ShortsActions,
) {
    val speeds: List<Float> get() = ShortsSpeeds

    fun qualitiesOf(item: ShortsItem): List<Resolution> =
        pool.slotFor(item.aid)?.availableQualities.orEmpty()

    fun qualityOf(item: ShortsItem): Resolution? = pool.slotFor(item.aid)?.quality

    fun inToView(item: ShortsItem): Boolean = vm.actionStateOf(item.aid).inToView

    fun toggleToView(item: ShortsItem) = vm.toggleToView(item.aid)
}

@Composable
internal fun rememberShortsMenuState(
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    danmaku: ShortsDanmaku,
    actions: ShortsActions,
): ShortsMenuState = remember(vm, pool, danmaku, actions) { ShortsMenuState(vm, pool, danmaku, actions) }

/** 菜单里的倍速档位（与主播放器常用档位一致） */
internal val ShortsSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 「1.0×」「0.75×」 */
internal fun formatShortsSpeed(speed: Float): String {
    val text = if (speed * 100 % 25 == 0f && speed * 10 % 1 != 0f) {
        String.format(Locale.US, "%.2f", speed)
    } else {
        String.format(Locale.US, "%.1f", speed)
    }
    return "$text×"
}

@Composable
internal fun ShortsDanmakuArea.label(): String = stringResource(
    when (this) {
        ShortsDanmakuArea.Few -> R.string.shorts_danmaku_area_few
        ShortsDanmakuArea.Half -> R.string.shorts_danmaku_area_half
        ShortsDanmakuArea.Full -> R.string.shorts_danmaku_area_full
    }
)

@Composable
internal fun DanmakuLaneDensity.label(): String = stringResource(
    when (this) {
        DanmakuLaneDensity.Sparse -> R.string.shorts_danmaku_density_sparse
        DanmakuLaneDensity.Standard -> R.string.shorts_danmaku_density_standard
        DanmakuLaneDensity.Dense -> R.string.shorts_danmaku_density_dense
    }
)

@Composable
internal fun ShortsAfterEnd.label(): String = stringResource(
    when (this) {
        ShortsAfterEnd.Loop -> R.string.shorts_after_end_loop
        ShortsAfterEnd.Next -> R.string.shorts_after_end_next
    }
)

/** 菜单里可单独开关的弹幕类型 */
internal val ShortsDanmakuTypes = listOf(DanmakuType.Rolling, DanmakuType.Top, DanmakuType.Bottom)

@Composable
internal fun DanmakuType.shortsLabel(): String = stringResource(
    when (this) {
        DanmakuType.Top -> R.string.shorts_danmaku_type_top
        DanmakuType.Bottom -> R.string.shorts_danmaku_type_bottom
        else -> R.string.shorts_danmaku_type_rolling
    }
)

/**
 * 触屏菜单内容（手机底部面板、平板信息栏「设置」页）。不自带滚动，由外壳决定。
 *
 * @param showActions 顶部「稍后再看 / 分享 / 视频详情」动作行（平板工具栏里已有分享时可以关掉）
 * @param onAction 动作执行后回调（手机用来关闭面板）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortsMenuContent(
    state: ShortsMenuState,
    item: ShortsItem,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    onAction: () -> Unit = {},
) {
    val context = LocalContext.current
    val danmaku = state.danmaku
    val pool = state.pool
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showActions) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val inToView = state.inToView(item)
                BvChip(
                    text = stringResource(if (inToView) R.string.shorts_menu_toview_added else R.string.shorts_menu_toview),
                    selected = inToView,
                    onClick = { state.toggleToView(item) },
                    icon = painterResource(R.drawable.ic_watch_later_24),
                )
                BvChip(
                    text = stringResource(R.string.shorts_share),
                    selected = false,
                    onClick = {
                        onAction()
                        state.actions.share(item)
                    },
                    icon = painterResource(R.drawable.ic_share_24),
                )
                BvChip(
                    text = stringResource(R.string.shorts_menu_detail),
                    selected = false,
                    onClick = {
                        onAction()
                        state.actions.openVideo(item)
                    },
                    icon = painterResource(R.drawable.ic_info_24),
                )
            }
        }

        MenuSection(
            title = stringResource(R.string.shorts_menu_danmaku),
            trailing = {
                BvSwitch(checked = danmaku.enabled, onCheckedChange = { danmaku.updateEnabled(it) })
            },
        ) {
            MenuLabel(stringResource(R.string.shorts_menu_danmaku_area))
            val areas = ShortsDanmakuArea.entries
            SegmentedRow(
                options = areas.map { it.label() },
                selected = areas.indexOf(danmaku.area),
                onSelect = { danmaku.updateArea(areas[it]) },
            )
            MenuLabel(stringResource(R.string.shorts_menu_danmaku_opacity, (danmaku.opacity * 100).toInt()))
            BvSlider(
                value = danmaku.opacity,
                onValueChange = { danmaku.updateOpacity(it, persist = false) },
                valueRange = 0.1f..1f,
                onValueChangeFinished = { danmaku.updateOpacity(danmaku.opacity) },
            )
            MenuLabel(stringResource(R.string.shorts_menu_danmaku_scale, (danmaku.scale * 100).toInt()))
            BvSlider(
                value = danmaku.scale,
                onValueChange = { danmaku.updateScale(it, persist = false) },
                valueRange = 0.5f..2f,
                onValueChangeFinished = { danmaku.updateScale(danmaku.scale) },
            )
            MenuLabel(stringResource(R.string.shorts_menu_danmaku_density))
            val densities = DanmakuLaneDensity.entries
            SegmentedRow(
                options = densities.map { it.label() },
                selected = densities.indexOf(danmaku.laneDensity),
                onSelect = { danmaku.updateLaneDensity(densities[it]) },
            )
            MenuLabel(stringResource(R.string.shorts_menu_danmaku_types))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortsDanmakuTypes.forEach { type ->
                    BvChip(
                        text = type.shortsLabel(),
                        selected = danmaku.isTypeOn(type),
                        onClick = { danmaku.toggleType(type) },
                    )
                }
            }
        }

        MenuSection(title = stringResource(R.string.shorts_menu_speed)) {
            val speeds = state.speeds
            SegmentedRow(
                options = speeds.map { formatShortsSpeed(it) },
                selected = speeds.indexOf(pool.speed),
                onSelect = { pool.setSpeed(speeds[it]) },
            )
        }

        val qualities = state.qualitiesOf(item)
        if (qualities.isNotEmpty()) {
            MenuSection(title = stringResource(R.string.shorts_menu_quality)) {
                val current = state.qualityOf(item)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    qualities.forEach { quality ->
                        BvChip(
                            text = quality.getShortDisplayName(context),
                            selected = quality == current,
                            onClick = { pool.setQuality(quality) },
                        )
                    }
                }
            }
        }

        MenuSection(title = stringResource(R.string.shorts_menu_after_end)) {
            val options = ShortsAfterEnd.entries
            SegmentedRow(
                options = options.map { it.label() },
                selected = options.indexOf(pool.afterEnd),
                onSelect = { pool.setAfterEnd(options[it]) },
            )
        }
    }
}

@Composable
private fun MenuSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = BvType.Section,
                color = MaterialTheme.colorScheme.onSurface,
            )
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 2.dp),
        style = BvType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
