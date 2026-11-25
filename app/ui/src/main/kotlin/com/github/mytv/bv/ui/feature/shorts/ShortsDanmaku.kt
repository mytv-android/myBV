package com.github.mytv.bv.ui.feature.shorts

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mytv.bv.entity.ShortsDanmakuArea
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.player.danmaku.DanmakuConfig
import com.github.mytv.bv.player.danmaku.DanmakuLaneDensity
import com.github.mytv.bv.player.danmaku.DanmakuView
import com.github.mytv.bv.player.danmaku.model.Danmaku
import com.github.mytv.bv.repository.loadEngineDanmakus
import com.github.mytv.bv.player.entity.DanmakuType
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.fWarn
import dev.aaa1115910.biliapi.repositories.VideoPlayRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 刷刷弹幕（设计稿「弹幕」一节）。
 *
 * - 开关和区域刷刷单独记（[Prefs.shortsDanmakuEnabled] / [Prefs.shortsDanmakuArea]），竖屏画面构图和主播放器不同；
 *   样式（不透明度、字号、密度、类型）和主播放器共用 Prefs.defaultDanmaku*。
 * - 数据按 cid 缓存最近 3 条：当前条停稳后拉取，当前条缓冲完成后预取下一条。
 *   拉取失败（多半是快速翻条触发的 -412 风控）记 30 秒：这段时间不重拉这一条，也不预取，免得越拉越被限。
 * - 渲染用 [ShortsDanmakuLayer]：每个弹幕层自己持有一个 [DanmakuView]（它离开窗口就会释放，不能在两个父容器之间搬），
 *   一个页面只放一个弹幕层，跟着当前条走。
 */
@Stable
internal class ShortsDanmaku(
    private val repository: VideoPlayRepository,
    private val scope: CoroutineScope,
    defaultArea: ShortsDanmakuArea,
) {
    companion object {
        private val logger = KotlinLogging.logger { }
        private const val CACHE_SIZE = 3
        private const val RETRY_AFTER_MS = 30_000L
        private const val FAILURE_MEMO_SIZE = 16
    }

    var enabled by mutableStateOf(runCatching { Prefs.shortsDanmakuEnabled }.getOrDefault(true))
        private set
    var area by mutableStateOf(runCatching { Prefs.shortsDanmakuArea }.getOrNull() ?: defaultArea)
        private set

    // 样式与主播放器共用
    var opacity by mutableFloatStateOf(runCatching { Prefs.defaultDanmakuOpacity }.getOrDefault(1f))
        private set
    var scale by mutableFloatStateOf(runCatching { Prefs.defaultDanmakuScale }.getOrDefault(1f))
        private set
    var laneDensity by mutableStateOf(
        runCatching { Prefs.defaultDanmakuLaneDensity }.getOrDefault(DanmakuLaneDensity.Standard)
    )
        private set
    var types by mutableStateOf(
        runCatching { Prefs.defaultDanmakuTypes }.getOrDefault(listOf(DanmakuType.All)).toSet()
    )
        private set
    private val durationFactor = runCatching { Prefs.defaultDanmakuRollingDurationFactor }.getOrDefault(1f)
    private val filterLevel = runCatching { Prefs.defaultDanmakuFilterLevel }.getOrDefault(0)

    /** 缓存有变化时加一，弹幕层据此重新装数据 */
    var version by mutableIntStateOf(0)
        private set

    private val cache = LinkedHashMap<Long, List<Danmaku>>()
    private val jobs = HashMap<Long, Job>()
    private val failedAt = LinkedHashMap<Long, Long>()
    private var lastFailureAt = Long.MIN_VALUE / 2

    fun danmakusFor(cid: Long): List<Danmaku>? = cache[cid]

    fun toggle() = updateEnabled(!enabled)

    fun updateEnabled(value: Boolean) {
        enabled = value
        runCatching { Prefs.shortsDanmakuEnabled = value }
    }

    fun updateArea(value: ShortsDanmakuArea) {
        area = value
        runCatching { Prefs.shortsDanmakuArea = value }
    }

    /** 拖动中只改界面；松手（[persist] = true）再写入设置 */
    fun updateOpacity(value: Float, persist: Boolean = true) {
        opacity = value.coerceIn(0.1f, 1f)
        if (persist) runCatching { Prefs.defaultDanmakuOpacity = opacity }
    }

    fun updateScale(value: Float, persist: Boolean = true) {
        scale = value.coerceIn(0.5f, 2f)
        if (persist) runCatching { Prefs.defaultDanmakuScale = scale }
    }

    fun updateLaneDensity(value: DanmakuLaneDensity) {
        laneDensity = value
        runCatching { Prefs.defaultDanmakuLaneDensity = value }
    }

    /** 滚动 / 顶部 / 底部 单独开关；三种都开等于「全部」 */
    fun isTypeOn(type: DanmakuType): Boolean = DanmakuType.All in types || type in types

    fun toggleType(type: DanmakuType) {
        val expanded = if (DanmakuType.All in types) {
            setOf(DanmakuType.Rolling, DanmakuType.Top, DanmakuType.Bottom)
        } else {
            types
        }
        val next = if (type in expanded) expanded - type else expanded + type
        val normalized = if (next.containsAll(listOf(DanmakuType.Rolling, DanmakuType.Top, DanmakuType.Bottom))) {
            setOf(DanmakuType.All)
        } else {
            next
        }
        types = normalized
        runCatching { Prefs.defaultDanmakuTypes = normalized.toList() }
    }

    /** 引擎配置；[areaFraction] 是本层可用高度里弹幕占的比例 */
    fun config(areaFraction: Float): DanmakuConfig {
        val allowAll = DanmakuType.All in types
        return DanmakuConfig(
            enabled = enabled,
            opacity = opacity,
            textSizeScale = (scale * 100).toInt(),
            durationMultiplier = durationFactor,
            area = areaFraction.coerceIn(0.05f, 1f),
            laneDensity = laneDensity,
            allowScroll = allowAll || DanmakuType.Rolling in types,
            allowTop = allowAll || DanmakuType.Top in types,
            allowBottom = allowAll || DanmakuType.Bottom in types,
            minLevel = filterLevel,
        )
    }

    /**
     * 拉取这一条的弹幕（已缓存或正在拉取时直接返回）。
     *
     * @param prefetch 预取下一条：刚失败过时先不预取
     */
    fun load(item: ShortsItem, prefetch: Boolean = false) {
        val cid = item.cid
        if (cid <= 0 || cache.containsKey(cid) || jobs[cid]?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (failedAt[cid]?.let { now - it < RETRY_AFTER_MS } == true) return
        if (prefetch && now - lastFailureAt < RETRY_AFTER_MS) return
        jobs[cid] = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    repository.loadEngineDanmakus(aid = item.aid, cid = cid).sorted()
                }
                cache[cid] = list
                failedAt.remove(cid)
                while (cache.size > CACHE_SIZE) {
                    val eldest = cache.keys.first()
                    cache.remove(eldest)
                }
                version++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val failed = SystemClock.elapsedRealtime()
                failedAt.remove(cid)
                failedAt[cid] = failed
                while (failedAt.size > FAILURE_MEMO_SIZE) failedAt.remove(failedAt.keys.first())
                lastFailureAt = failed
                logger.fWarn { "Load shorts danmaku failed: [av=${item.aid}] ${e.stackTraceToString()}" }
            } finally {
                jobs.remove(cid)
            }
        }
    }
}

/** 手机默认「少」，平板、电视默认半屏 */
@Composable
internal fun rememberShortsDanmaku(isPhone: Boolean): ShortsDanmaku {
    val repository: VideoPlayRepository = koinInject()
    val scope = rememberCoroutineScope()
    return remember(repository, scope) {
        ShortsDanmaku(repository, scope, if (isPhone) ShortsDanmakuArea.Few else ShortsDanmakuArea.Half)
    }
}

/**
 * 当前条停稳后拉它的弹幕；当前条缓冲完成后预取下一条。
 */
@Composable
internal fun ShortsDanmakuLoadEffect(danmaku: ShortsDanmaku, pool: ShortsPlayerPool, items: List<ShortsItem>) {
    LaunchedEffect(danmaku, pool) {
        snapshotFlow {
            val aid = pool.currentAid
            val ready = pool.currentSlot?.state == SlotState.Ready
            Triple(aid, ready, danmaku.enabled)
        }.distinctUntilChanged().collectLatest { (aid, ready, enabled) ->
            if (aid == null || !enabled) return@collectLatest
            val index = items.indexOfFirst { it.aid == aid }
            val current = items.getOrNull(index) ?: return@collectLatest
            danmaku.load(current)
            if (ready) items.getOrNull(index + 1)?.let { danmaku.load(it, prefetch = true) }
        }
    }
}

/**
 * 弹幕层：铺满传入的区域（应当就是画面所在的矩形，不出视频框、不从按钮后面穿过）。
 *
 * @param item 这一层显示哪一条；不是当前条时清空
 * @param areaFraction 弹幕占本层高度的比例，一般是 `danmaku.area.fraction` 按场景换算后的值
 * @param hidden 翻页、拖进度时淡出；重新显示时从当前位置开始，不残留旧弹幕
 */
@Composable
internal fun ShortsDanmakuLayer(
    danmaku: ShortsDanmaku,
    pool: ShortsPlayerPool,
    item: ShortsItem?,
    areaFraction: Float,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    val context = LocalContext.current
    val latestItem by rememberUpdatedState(item)
    val view = remember(context) {
        DanmakuView(context).apply {
            setPositionProvider { latestItem?.let { pool.positionNow(it.aid) } ?: 0L }
            setIsPlayingProvider { latestItem?.let { pool.isPlayingNow(it.aid) } ?: false }
            setPlaybackSpeedProvider { latestItem?.let { pool.speedNow(it.aid) } ?: 1f }
        }
    }
    DisposableEffect(view) {
        onDispose { view.release() }
    }

    val isCurrent = item != null && pool.currentAid == item.aid
    val config = danmaku.config(areaFraction)
    LaunchedEffect(view, config) { view.setConfig(config) }

    // 换条 / 数据到达：装入当前条的弹幕，从当前位置开始
    val cid = item?.cid ?: 0L
    LaunchedEffect(view, cid, isCurrent, danmaku.version) {
        val list = if (isCurrent) danmaku.danmakusFor(cid) else null
        if (list == null) {
            view.clearDanmakus()
        } else {
            view.setDanmakus(list)
            item?.let { view.notifySeek(pool.positionNow(it.aid)) }
        }
    }
    // 拖进度、快进退、循环回到开头：清掉旧位置的弹幕
    LaunchedEffect(view, pool) {
        snapshotFlow { pool.seekSerial }.collect {
            latestItem?.let { current -> view.notifySeek(pool.positionNow(current.aid)) }
        }
    }
    LaunchedEffect(view, hidden) {
        if (!hidden) latestItem?.let { current -> view.notifySeek(pool.positionNow(current.aid)) }
    }

    AndroidView(
        factory = { view },
        // 裁到本层：滚动弹幕从右边缘外进场，不裁的话会画到黑边和操作列上
        modifier = modifier.graphicsLayer {
            clip = true
            alpha = if (hidden || !isCurrent) 0f else 1f
        },
    )
}

/**
 * 命中测试时声明「与兄弟节点共享触摸」：叠在画面上的弹幕层（或它的包装层）不挡住下面的页面手势。
 *
 * Compose 命中一个子节点后，只看这个**直接子节点**有没有声明共享来决定是否继续把触摸交给它下面的兄弟。
 * AndroidView 自己声明了共享，但外面再包一层布局（如 BoxWithConstraints）就失效了，所以包装层要挂上这个。
 */
internal fun Modifier.shortsTouchPassThrough(): Modifier = this then TouchPassThroughElement

private data object TouchPassThroughElement : ModifierNodeElement<TouchPassThroughNode>() {
    override fun create(): TouchPassThroughNode = TouchPassThroughNode()
    override fun update(node: TouchPassThroughNode) = Unit
}

private class TouchPassThroughNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings(): Boolean = true
}
