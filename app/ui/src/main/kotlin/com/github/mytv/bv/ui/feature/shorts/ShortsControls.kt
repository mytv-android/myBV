package com.github.mytv.bv.ui.feature.shorts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.ui.designsystem.theme.BvType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** 超过 3 分钟的视频，一屏宽按 3 分钟算 */
private const val SeekSpanMaxMs = 180_000L

/** 横滑多远才算拖进度（且横向分量要大于竖向的 1.2 倍） */
private val SeekSlop = 10.dp

/**
 * 拖进度的共享状态：进度条拖动、画面横滑、电视 ←→ 快进退都写这里。
 * 拖动中界面显示大号时间、淡出信息层和弹幕；松手（[finish]）才真正跳转。
 */
@Stable
internal class ShortsScrub {
    var aid by mutableLongStateOf(0L)
        private set
    var targetMs by mutableLongStateOf(-1L)
        private set

    /** 开始拖动时的位置（算「+12 秒」用） */
    var startMs by mutableLongStateOf(0L)
        private set

    /** 来自画面横滑（而不是进度条）：横滑时信息、操作列淡出 */
    var fromGesture by mutableStateOf(false)
        private set

    val active: Boolean get() = targetMs >= 0

    fun activeFor(item: ShortsItem): Boolean = active && aid == item.aid

    fun start(aid: Long, positionMs: Long, fromGesture: Boolean = false) {
        this.aid = aid
        this.startMs = positionMs
        this.targetMs = positionMs.coerceAtLeast(0L)
        this.fromGesture = fromGesture
    }

    fun update(positionMs: Long) {
        if (targetMs >= 0) targetMs = positionMs.coerceAtLeast(0L)
    }

    fun finish(pool: ShortsPlayerPool) {
        val target = targetMs
        if (target >= 0) pool.seekTo(aid, target)
        targetMs = -1L
        fromGesture = false
    }

    fun cancel() {
        targetMs = -1L
        fromGesture = false
    }
}

internal fun ShortsPlayerPool.durationOf(item: ShortsItem): Long =
    slotFor(item.aid)?.durationMs?.takeIf { it > 0 } ?: (item.duration * 1000L)

/** 「01:12」；超过 1 小时为「1:02:03」 */
internal fun formatShortsTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * 刷刷进度条（替换原来的 12dp 波浪进度条）：
 * - 静止：2dp 细线；[expanded]（暂停、按下、拖动）时 6dp + 14dp 把手，上方显示「01:12 / 02:40」。
 * - 触摸区 40dp 高，点按直接跳，横拖预览、松手跳转（写入 [scrub]）。[scrub] 为 null 时只显示（电视）。
 * - 位置只在 Draw 阶段读取，播放器每 100ms 更新进度不触发重组。
 *
 * @param overrideMs 电视快进快退的预览位置
 */
@Composable
internal fun ShortsScrubber(
    pool: ShortsPlayerPool,
    item: ShortsItem,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    scrub: ShortsScrub? = null,
    overrideMs: Long? = null,
    showTime: Boolean = expanded,
    trackColor: Color = Color.White.copy(alpha = .28f),
    progressColor: Color = MaterialTheme.colorScheme.primary,
    touchHeight: Dp = 40.dp,
) {
    val slot = pool.slotFor(item.aid)
    val dragging = scrub != null && scrub.activeFor(item) && !scrub.fromGesture
    val open = expanded || dragging || overrideMs != null
    val thickness by animateDpAsState(
        if (open) 6.dp else 2.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "shortsScrubThickness",
    )
    val thumb by animateDpAsState(
        if (open) 14.dp else 0.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "shortsScrubThumb",
    )
    val position: () -> Long = {
        val s = scrub
        when {
            s != null && s.activeFor(item) -> s.targetMs
            overrideMs != null -> overrideMs
            else -> slot?.positionMs ?: 0L
        }
    }
    val duration: () -> Long = { pool.durationOf(item) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showTime || dragging || overrideMs != null) {
            Text(
                "${formatShortsTime(position())} / ${formatShortsTime(duration())}",
                modifier = Modifier.padding(start = 2.dp),
                style = ShortsTimeStyle,
                color = Color.White,
            )
        }
        val seekModifier = if (scrub != null) {
            Modifier
                .pointerInput(item.aid, scrub) {
                    detectTapGestures { offset ->
                        val total = duration()
                        if (total > 0 && pool.currentAid == item.aid && size.width > 0) {
                            pool.seekTo(item.aid, (offset.x / size.width * total).roundToLong())
                        }
                    }
                }
                .pointerInput(item.aid, scrub) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val total = duration()
                            if (total > 0 && pool.currentAid == item.aid && size.width > 0) {
                                scrub.start(item.aid, (offset.x / size.width * total).roundToLong())
                            }
                        },
                        onDragEnd = { if (scrub.activeFor(item)) scrub.finish(pool) },
                        onDragCancel = { if (scrub.activeFor(item)) scrub.cancel() },
                    ) { change, _ ->
                        val total = duration()
                        if (scrub.activeFor(item) && size.width > 0) {
                            val x = change.position.x.coerceIn(0f, size.width.toFloat())
                            scrub.update((x / size.width * total).roundToLong())
                            change.consume()
                        }
                    }
                }
        } else {
            Modifier
        }
        val progressLabel = stringResource(R.string.shorts_progress)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(touchHeight)
                .then(seekModifier)
                .semantics { contentDescription = progressLabel }
        ) {
            val total = duration()
            val fraction = if (total > 0) (position().toFloat() / total).coerceIn(0f, 1f) else 0f
            val h = thickness.toPx()
            val r = thumb.toPx() / 2f
            val y = size.height / 2f
            val start = r
            val width = (size.width - r * 2).coerceAtLeast(0f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(start, y - h / 2),
                size = Size(width, h),
                cornerRadius = CornerRadius(h / 2, h / 2),
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(start, y - h / 2),
                    size = Size(width * fraction, h),
                    cornerRadius = CornerRadius(h / 2, h / 2),
                )
            }
            if (r > 0.5f) {
                drawCircle(color = Color.White, radius = r, center = Offset(start + width * fraction, y))
            }
        }
    }
}

private val ShortsTimeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 3f),
)

/**
 * 画面横滑拖进度：过 10dp 且横向分量大于竖向 1.2 倍才进入拖动，之后吃掉这次手势（翻页不动）；
 * 先出现竖向位移则交给翻页。一屏宽对应全片，超过 3 分钟的按 3 分钟算。
 */
internal fun Modifier.shortsSeekGesture(
    item: ShortsItem,
    pool: ShortsPlayerPool,
    scrub: ShortsScrub,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else pointerInput(item.aid, scrub) {
    val slop = SeekSlop.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        var locked = false
        var finished = false
        var dx = 0f
        var dy = 0f
        var startMs = 0L
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    if (locked) {
                        change.consume()
                        scrub.finish(pool)
                        finished = true
                    }
                    break
                }
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!locked) {
                    if (change.isConsumed) break
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                        if (pool.currentAid != item.aid || pool.durationOf(item) <= 0) break
                        locked = true
                        startMs = pool.positionNow(item.aid)
                        scrub.start(item.aid, startMs, fromGesture = true)
                    } else if (abs(dy) > slop) {
                        break
                    }
                }
                if (locked) {
                    val total = pool.durationOf(item)
                    val span = min(total, SeekSpanMaxMs).coerceAtLeast(1L)
                    val width = size.width.coerceAtLeast(1)
                    val target = startMs + (dx / width * span).roundToLong()
                    scrub.update(target.coerceIn(0L, total))
                    change.consume()
                }
            }
        } finally {
            if (locked && !finished && scrub.activeFor(item)) scrub.cancel()
        }
    }
}

/** 拖进度时画面中间的大号时间：「01:36 / 04:12」+「+12 秒」 */
@Composable
internal fun ShortsScrubOverlay(
    scrub: ShortsScrub,
    pool: ShortsPlayerPool,
    item: ShortsItem,
    modifier: Modifier = Modifier,
    overrideMs: Long? = null,
) {
    val active = scrub.activeFor(item) || overrideMs != null
    AnimatedVisibility(
        visible = active,
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        label = "shortsScrubOverlay",
    ) {
        val target = if (scrub.activeFor(item)) scrub.targetMs else (overrideMs ?: 0L)
        val from = if (scrub.activeFor(item)) scrub.startMs else (pool.slotFor(item.aid)?.positionMs ?: 0L)
        val deltaSeconds = (target - from) / 1000
        Column(
            Modifier
                .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "${formatShortsTime(target)} / ${formatShortsTime(pool.durationOf(item))}",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                stringResource(
                    if (deltaSeconds >= 0) R.string.shorts_seek_forward else R.string.shorts_seek_backward,
                    abs(deltaSeconds),
                ),
                style = BvType.Label,
                color = Color.White.copy(alpha = .85f),
            )
        }
    }
}

/** 暂停时画面中间的 72dp 播放键（只做提示，点击由整页手势处理） */
@Composable
internal fun ShortsPausedIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(initialScale = .8f),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(targetScale = .8f),
        label = "shortsPaused",
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(Color.Black.copy(alpha = .45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_play_arrow_24),
                contentDescription = stringResource(R.string.shorts_paused),
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** 「弹」字开关图标：开时实心，关时描边 + 半透明 */
@Composable
internal fun ShortsDanmakuGlyph(on: Boolean, modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 22.dp) {
    Box(
        modifier
            .size(size)
            .then(
                if (on) Modifier.background(color, RoundedCornerShape(6.dp))
                else Modifier.border(1.5.dp, color, RoundedCornerShape(6.dp)).alpha(.7f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.shorts_danmaku_glyph),
            style = TextStyle(fontSize = (size.value * 0.6f).sp, lineHeight = (size.value * 0.7f).sp, fontWeight = FontWeight.Bold),
            color = if (on) Color.Black else color,
        )
    }
}
