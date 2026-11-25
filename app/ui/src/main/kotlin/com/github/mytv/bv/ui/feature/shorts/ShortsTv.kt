package com.github.mytv.bv.ui.feature.shorts

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.entity.ShortsAfterEnd
import com.github.mytv.bv.entity.ShortsDanmakuArea
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.player.danmaku.DanmakuLaneDensity
import com.github.mytv.bv.ui.designsystem.component.Ambient
import com.github.mytv.bv.ui.designsystem.component.BvAvatar
import com.github.mytv.bv.ui.designsystem.component.CinemaScrim
import com.github.mytv.bv.ui.designsystem.component.CommentItem
import com.github.mytv.bv.ui.designsystem.focus.BvKey
import com.github.mytv.bv.ui.designsystem.focus.bvFocus
import com.github.mytv.bv.ui.designsystem.focus.focusContainerColor
import com.github.mytv.bv.ui.designsystem.focus.focusContentColor
import com.github.mytv.bv.ui.designsystem.focus.focusRing
import com.github.mytv.bv.ui.designsystem.focus.showFocus
import com.github.mytv.bv.ui.designsystem.focus.toBvKey
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.designsystem.theme.CoverTheme
import com.github.mytv.bv.ui.designsystem.theme.LocalThemePrefs
import com.github.mytv.bv.ui.navigation.LocalTvRailReveal
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.viewmodel.ShortsViewModel
import dev.aaa1115910.biliapi.entity.Picture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// 竖屏视频的版式（原型 960×540 画布：画面 0～304，信息列 336～616，「接下来」648～912）。
// 画面宽按高度 9:16 算，剩下的宽度扣掉间距后按 280 : 264 分给两列，屏幕比例不同也不留空白。
private val SideTop = 60.dp
private val SideGap = 32.dp
private val SideEnd = 48.dp
private const val InfoShare = 280f / 544f

/** 剩下的宽度不到这么多时不放「接下来」，全部给信息列 */
private val UpNextMinRoom = 400.dp

/** 「接下来」每行：96dp 缩略图 + 12dp 间距 */
private val UpNextRowHeight = 108.dp

/** 横屏信息层 3 秒无操作后淡出 */
private const val InfoAutoHideMs = 3_000L

// ←→ 快进退（沿用旧电视播放器）：两次按键间隔小于 200ms 算连按，每连按 5 次步长加 5 秒；松开 0.6 秒后跳转
private const val SeekRepeatWindowMs = 200L
private const val SeekStepMs = 5_000L
private const val SeekCommitDelayMs = 600L

/**
 * 画面焦点节点左边让出的宽度。导航轨聚焦时各项左缘在 14～16dp，2D 焦点搜索要求 → 的候选左缘在它右边；
 * 画面贴左（x = 0）时如果焦点节点也从 0 开始，在导航轨上按 → 就回不到视频。
 */
private val StageFocusInset = 24.dp

/** 触摸：方向判定的起始距离，与 shortsSeekGesture 一致（过 10dp、横向大于竖向 1.2 倍算横滑） */
private val TouchSlop = 10.dp

/** 触摸：上下滑超过这么多才切条；左缘右滑超过这么多才呼出导航轨 */
private val SwipeThreshold = 64.dp

/** 左缘手势的起手区域（屏幕宽度的比例） */
private const val EdgeFraction = 0.12f

/** 刷刷面板：右侧 360dp，外壳同主播放器 TvMenuPanel（top / end / bottom 16，圆角 28，surfaceContainer） */
private val PanelWidth = 360.dp
private val PanelShape = RoundedCornerShape(28.dp)
private val PanelOptionShape = RoundedCornerShape(14.dp)
private val PanelCategoryShape = RoundedCornerShape(16.dp)

// 电视面板里不用触屏 Slider：不透明度、字号改为常用值单选（与主播放器电视菜单一致）
private val DanmakuOpacityPresets = listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
private val DanmakuScalePresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private val PanelOptionText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
private val PanelCategoryText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val StatChipText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
private val KeyCapText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
private val GuideKeyText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
private val KeyHintText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val UpNextTitleText = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

/** 刷刷面板左列分类，聚焦即切换 */
private enum class TvPanelCategory(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Interact(R.string.shorts_tv_cat_interact, R.drawable.ic_thumb_up_24),
    Comments(R.string.shorts_comment, R.drawable.ic_chat_bubble_24),
    Danmaku(R.string.shorts_danmaku, R.drawable.ic_subtitles_24),
    Picture(R.string.shorts_tv_cat_picture, R.drawable.ic_tune_24),
    Playback(R.string.shorts_tv_cat_playback, R.drawable.ic_play_arrow_24),
}

/**
 * 电视刷刷（设计稿「电视」一节）：整屏氛围光 + 画面 + 只读信息，所有操作收进刷刷面板。
 *
 * 布局：横屏视频 16:9 铺满整屏，信息层叠在底部渐变上，播放 3 秒无操作后淡出（暂停、快进、面板打开时常驻）；
 * 竖屏视频上下顶满、贴左（宽 = 高 × 9/16），右边依次是信息列和「接下来」。
 * 导航轨由外壳叠加，播放中自动隐藏，返回键或左缘右滑呼出。整页包在当前视频的 CoverTheme 里，换条时颜色过渡。
 *
 * 遥控器（焦点在画面上）：↑↓ / 频道± 上一条 / 下一条；←→ / 媒体快进快退 立即快退快进 5 秒，连按加速，
 * 松开 0.6 秒后跳转；确认 暂停 / 继续（快进中立即跳转）；长按确认、菜单键 打开刷刷面板，焦点停在「互动 › 点赞」；
 * 返回 依次关面板、取消快进，最后交给外壳呼出导航轨。
 *
 * 「电视横屏」模式会在手机、平板上用这套布局，所以同时接受触摸：单击暂停、上下滑切条、横拖调进度、
 * 长按开面板、点面板外关面板、左缘右滑呼出导航轨。
 */
@Composable
internal fun ShortsTv(
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    comments: ShortsComments,
    actions: ShortsActions,
    menu: ShortsMenuState,
    onPictureClick: (List<Picture>, Int) -> Unit,
) {
    val items = vm.items
    var index by rememberSaveable { mutableIntStateOf(vm.currentIndex) }
    val safeIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val current = items.getOrNull(safeIndex)
    var direction by remember { mutableIntStateOf(1) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelCategory by remember { mutableStateOf(TvPanelCategory.Interact) }
    var hintVisible by remember { mutableStateOf(!runCatching { Prefs.shortsTvHintShown }.getOrDefault(true)) }
    val stageRequester = remember { FocusRequester() }
    val likeRequester = remember { FocusRequester() }
    var stageFocused by remember { mutableStateOf(false) }
    val scrub = remember { ShortsScrub() }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalThemePrefs.current.reduceMotion
    val density = LocalDensity.current
    val revealRail = LocalTvRailReveal.current
    val danmaku = menu.danmaku

    // 优先用播放器上报的真实宽高判断横竖屏：story 流的元数据可能缺失或不准，之前会把横屏视频裁成竖屏
    val currentSlot = current?.let { pool.slotFor(it.aid) }
    val landscape = current != null && pool.isLandscapeVideo(current)
    val portrait = !landscape
    val paused = current != null && pool.currentAid == current.aid && pool.userPaused
    val playing = currentSlot?.isPlaying == true && !pool.userPaused
    // 遥控器快进退与画面横滑共用一份拖进度状态，显示统一走 ShortsScrubOverlay / ShortsScrubber
    val seeking = current != null && scrub.activeFor(current)

    var infoVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val revealInfo: () -> Unit = {
        infoVisible = true
        interactionTick++
    }

    var seekCount by remember { mutableIntStateOf(0) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var centerPending by remember { mutableStateOf(false) }
    // 长按确认打开面板后，同一次按压剩下的确认键事件（重复按下与抬起）全部吃掉：
    // 否则抬起会落到刚拿到焦点的「点赞」上，长按一次就点了赞
    var swallowCenter by remember { mutableStateOf(false) }

    // 按键回调里不能用组合时的 current：连按时两次按键之间可能还没重组
    val currentNow: () -> ShortsItem? = {
        items.getOrNull(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
    val cancelSeek: () -> Unit = {
        seekJob?.cancel()
        seekJob = null
        scrub.cancel()
    }
    val commitSeek: () -> Unit = {
        seekJob?.cancel()
        seekJob = null
        // 画面横滑拖动中由手势自己在松手时跳转
        if (scrub.active && !scrub.fromGesture) scrub.finish(pool)
    }
    val seek: (Boolean) -> Unit = { forward ->
        val item = currentNow()
        val total = item?.let { pool.durationOf(it) } ?: 0L
        if (item != null && pool.currentAid == item.aid && total > 0) {
            val now = SystemClock.uptimeMillis()
            val coefficient = if (now - lastSeekAt < SeekRepeatWindowMs) {
                seekCount++
                seekCount / 5
            } else {
                seekCount = 0
                0
            }
            lastSeekAt = now
            if (!scrub.activeFor(item)) scrub.start(item.aid, pool.positionNow(item.aid))
            val step = SeekStepMs * (1 + coefficient)
            val target = scrub.targetMs + if (forward) step else -step
            scrub.update(target.coerceIn(0L, total))
            seekJob?.cancel()
            seekJob = scope.launch {
                delay(SeekCommitDelayMs)
                commitSeek()
            }
        }
    }
    val togglePlay: () -> Unit = {
        currentNow()?.let { pool.togglePlay(it.aid) }
        revealInfo()
    }
    val go: (Int) -> Boolean = { delta ->
        val last = (items.size - 1).coerceAtLeast(0)
        val from = index.coerceIn(0, last)
        val target = (from + delta).coerceIn(0, last)
        if (target != from) {
            direction = if (delta > 0) 1 else -1
            cancelSeek()
            index = target
            true
        } else {
            false
        }
    }
    val openPanel: () -> Unit = {
        if (currentNow() != null) {
            if (scrub.active && !scrub.fromGesture) commitSeek()
            // 每次打开都停在「互动 › 点赞」：长按 OK 再按 OK 就是点赞
            panelCategory = TvPanelCategory.Interact
            panelOpen = true
        }
    }
    val dismissHint: () -> Unit = {
        hintVisible = false
        runCatching { Prefs.shortsTvHintShown = true }
    }

    // 停稳 150ms 后开始播放当前条
    LaunchedEffect(safeIndex, current?.aid, items.getOrNull(safeIndex + 1)?.aid) {
        if (current == null) return@LaunchedEffect
        delay(SettleDelayMs)
        vm.updateCurrentIndex(safeIndex)
        pool.update(safeIndex, items)
    }
    LoadCommentsEffect(current?.aid, comments)

    // 播完后「自动下一条」：翻到下一条；已经是最后一条时返回 false，由播放器池从头循环
    val latestGo by rememberUpdatedState(go)
    DisposableEffect(pool) {
        pool.onCurrentEnded = { latestGo(1) }
        onDispose { pool.onCurrentEnded = null }
    }

    // 焦点：进入页面、面板关闭后回到画面；面板打开时停在「互动 › 点赞」；首次提示显示时由提示层自己拿焦点。
    // 页面进入时的请求可能撞上转场被丢掉，重试几次，否则焦点留在导航轨上、自动隐藏不生效。
    LaunchedEffect(panelOpen, hintVisible) {
        if (hintVisible) return@LaunchedEffect
        val target = if (panelOpen) likeRequester else stageRequester
        repeat(6) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(if (panelOpen) 50L else 120L)
        }
    }

    // 横屏播放中 3 秒无操作后隐藏信息层；暂停、快进、面板打开时保持可见
    LaunchedEffect(landscape, current?.aid, interactionTick, playing, seeking, panelOpen) {
        infoVisible = true
        if (!landscape || !playing || seeking || panelOpen) return@LaunchedEffect
        delay(InfoAutoHideMs)
        infoVisible = false
    }

    // 返回键分层，每次只退一层：提示 → 面板 → 快进；都没有时不拦，交给外壳呼出导航轨。
    // 面板里楼中楼、图片预览各自的返回处理注册得更晚，优先于这里。
    BackHandler(enabled = hintVisible) { dismissHint() }
    BackHandler(enabled = !hintVisible && panelOpen) { panelOpen = false }
    BackHandler(enabled = !hintVisible && !panelOpen && seeking) { cancelSeek() }

    val onEdgeSwipe: () -> Unit = {
        revealInfo()
        revealRail?.invoke()
    }
    val onFrameTap: () -> Unit = {
        if (scrub.active && !scrub.fromGesture) commitSeek() else togglePlay()
    }
    val onFrameSwipe: (Int) -> Unit = { delta ->
        go(delta)
        revealInfo()
    }
    // 手势协程只在第一次组合时启动，回调经 State 读最新值
    val latestEdgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val latestFrameTap by rememberUpdatedState(onFrameTap)
    val latestFrameLongPress by rememberUpdatedState(openPanel)
    val latestFrameSwipe by rememberUpdatedState(onFrameSwipe)

    CoverTheme(current?.cover, forceDark = true) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .tvEdgeSwipe(enabled = !hintVisible) { latestEdgeSwipe() }
                // 不管焦点在画面还是面板里都生效的键：菜单、频道±、播放暂停；以及长按确认之后的收尾
                .onPreviewKeyEvent { event ->
                    if (hintVisible) return@onPreviewKeyEvent false
                    // 键盘 M 等同菜单键（「电视横屏」接键盘时用）
                    val key = event.toBvKey()
                        ?: if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_M) BvKey.Menu else return@onPreviewKeyEvent false
                    val down = event.type == KeyEventType.KeyDown
                    val up = event.type == KeyEventType.KeyUp
                    val repeat = event.nativeKeyEvent.repeatCount
                    if (swallowCenter && (key == BvKey.Center || key == BvKey.CenterLong)) {
                        if (key == BvKey.Center && down && repeat == 0) {
                            // 新的一次按压（上一次的抬起没收到）：不再拦
                            swallowCenter = false
                        } else {
                            if (up) swallowCenter = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (key) {
                        BvKey.Menu -> {
                            if (down && repeat == 0) {
                                if (panelOpen) panelOpen = false else openPanel()
                            }
                            true
                        }

                        BvKey.ChannelUp -> {
                            if (down) go(-1)
                            true
                        }

                        BvKey.ChannelDown -> {
                            if (down) go(1)
                            true
                        }

                        BvKey.PlayPause -> {
                            if (down && repeat == 0) togglePlay()
                            true
                        }

                        else -> false
                    }
                }
        ) {
            val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val portraitWidth = (maxHeight * 9f / 16f).coerceAtMost(maxWidth)
            val sideRoom = (maxWidth - portraitWidth - SideGap * 2 - SideEnd).coerceAtLeast(0.dp)
            val showUpNext = sideRoom >= UpNextMinRoom
            val infoX = portraitWidth + SideGap
            val infoWidth = if (showUpNext) {
                sideRoom * InfoShare
            } else {
                (maxWidth - portraitWidth - SideGap - SideEnd).coerceAtLeast(0.dp)
            }
            val upNextX = infoX + infoWidth + SideGap
            val upNextWidth = (maxWidth - upNextX - SideEnd).coerceAtLeast(0.dp)
            val upNextRows = ((maxHeight - SideTop - 24.dp) / UpNextRowHeight).toInt().coerceIn(0, 4)
            val widthSpec: FiniteAnimationSpec<Dp> =
                if (reduceMotion) snap() else MaterialTheme.motionScheme.slowSpatialSpec()
            val frameWidth by animateDpAsState(
                if (landscape) maxWidth else portraitWidth,
                widthSpec,
                label = "shortsTvFrameWidth",
            )

            // 横屏画面铺满整屏且有不透明黑底，氛围光完全不可见；只在竖屏绘制。
            // 原来的 50% surface 遮罩折进 Ambient 的 alpha（0.55 × 0.5 = 0.275），省掉一层全屏混合。
            if (portrait) Ambient(current?.cover, alpha = 0.275f)

            // 画面：横屏铺满整屏，竖屏贴左顶天立地。触摸手势挂在这里，焦点在下面单独的焦点节点上
            val slideSpec = MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
            val slidePx = with(density) { 40.dp.roundToPx() }
            Box(
                Modifier
                    .width(frameWidth)
                    .fillMaxHeight()
                    .focusRing(
                        visible = portrait && !panelOpen && showFocus(stageFocused),
                        corner = 0.dp,
                        glow = 16.dp,
                    )
                    .background(Color.Black)
                    .then(if (current != null) Modifier.shortsSeekGesture(current, pool, scrub) else Modifier)
                    .tvTouchGestures(
                        onTap = { latestFrameTap() },
                        onLongPress = { latestFrameLongPress() },
                        onSwipe = { latestFrameSwipe(it) },
                    )
            ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        val dir = direction
                        (slideInVertically(slideSpec) { dir * slidePx } + fadeIn(fadeSpec)) togetherWith fadeOut(fadeSpec)
                    },
                    contentKey = { it?.aid },
                    label = "shortsTvFrame",
                ) { item ->
                    if (item != null) {
                        Box(Modifier.fillMaxSize()) {
                            if (!item.isPortrait) Ambient(item.cover)
                            ShortsVideo(pool, item, Modifier.fillMaxSize(), crop = !pool.isLandscapeVideo(item))
                        }
                    }
                }
                // 竖屏画面常驻上下渐变（进度条可读性）；横屏的渐变在信息层里，随隐藏一起淡出
                if (portrait) CinemaScrim()
                // 弹幕层整页只有一个，放在换条动画外面（DanmakuView 离开窗口就会被释放），铺在画面矩形上；快进中淡出
                ShortsDanmakuLayer(
                    danmaku = danmaku,
                    pool = pool,
                    item = current,
                    areaFraction = danmaku.area.fraction,
                    modifier = Modifier.fillMaxSize(),
                    hidden = seeking,
                )
                if (current != null) {
                    if (portrait) {
                        ShortsScrubber(
                            pool = pool,
                            item = current,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            expanded = paused || seeking,
                            scrub = scrub,
                        )
                    }
                    ShortsPausedIndicator(visible = paused && !seeking, modifier = Modifier.align(Alignment.Center))
                    ShortsScrubOverlay(scrub = scrub, pool = pool, item = current, modifier = Modifier.align(Alignment.Center))
                }
            }

            if (current != null) {
                // 竖屏：右侧信息列（只读）和「接下来」
                AnimatedVisibility(
                    visible = portrait,
                    enter = fadeIn(fadeSpec),
                    exit = fadeOut(fadeSpec),
                    modifier = Modifier
                        .offset(x = infoX)
                        .width(infoWidth)
                        .fillMaxHeight(),
                    label = "shortsTvInfo",
                ) {
                    TvInfo(
                        item = current,
                        vm = vm,
                        comments = comments,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = SideTop, bottom = 32.dp),
                    )
                }
                if (showUpNext) {
                    val upNext = (safeIndex + 1 until minOf(items.size, safeIndex + 1 + upNextRows)).map { items[it] }
                    AnimatedVisibility(
                        visible = portrait && upNext.isNotEmpty(),
                        enter = fadeIn(fadeSpec),
                        exit = fadeOut(fadeSpec),
                        modifier = Modifier
                            .offset(x = upNextX)
                            .width(upNextWidth)
                            .fillMaxHeight(),
                        label = "shortsTvUpNext",
                    ) {
                        TvUpNext(items = upNext, modifier = Modifier.padding(top = SideTop))
                    }
                }

                // 横屏：底部信息层，播放 3 秒无操作后淡出；快进、暂停时显示
                AnimatedVisibility(
                    visible = landscape && (infoVisible || seeking || paused),
                    enter = fadeIn(fadeSpec),
                    exit = fadeOut(fadeSpec),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    label = "shortsTvLandscapeInfo",
                ) {
                    TvLandscapeInfo(
                        item = current,
                        vm = vm,
                        pool = pool,
                        scrub = scrub,
                        expanded = paused || seeking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 焦点节点：遥控器的焦点永远停在这里（信息都是只读的），按键在这里处理
            val stageLabel = stringResource(R.string.shorts_tv_stage)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = StageFocusInset)
                    .semantics { contentDescription = stageLabel }
                    .onPreviewKeyEvent { event ->
                        val key = event.toBvKey() ?: return@onPreviewKeyEvent false
                        val down = event.type == KeyEventType.KeyDown
                        val up = event.type == KeyEventType.KeyUp
                        val repeat = event.nativeKeyEvent.repeatCount
                        // 任意按键都把横屏信息层唤回，并重新计时
                        if (down && repeat == 0) revealInfo()
                        when (key) {
                            BvKey.Up -> {
                                if (down) go(-1)
                                true
                            }

                            BvKey.Down -> {
                                if (down) go(1)
                                true
                            }

                            BvKey.Left, BvKey.Rewind -> {
                                if (down) seek(false)
                                true
                            }

                            BvKey.Right, BvKey.FastForward -> {
                                if (down) seek(true)
                                true
                            }

                            BvKey.Center -> {
                                if (down && repeat == 0) {
                                    centerPending = true
                                } else if (up && centerPending) {
                                    centerPending = false
                                    if (scrub.active && !scrub.fromGesture) commitSeek() else togglePlay()
                                }
                                true
                            }

                            BvKey.CenterLong -> {
                                // 长按确认：先收到普通按下（centerPending），再收到长按；部分盒子直接发长按键码（repeat = 0）
                                if (down && (centerPending || repeat == 0)) {
                                    centerPending = false
                                    swallowCenter = true
                                    openPanel()
                                }
                                true
                            }

                            else -> false
                        }
                    }
                    .focusRequester(stageRequester)
                    .onFocusChanged { stageFocused = it.isFocused }
                    .focusable()
            )

            // 点面板外关闭面板（面板自己会接住落在它上面的触摸）
            if (panelOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onPress = { panelOpen = false }) }
                )
            }

            // 刷刷面板：从右侧滑出叠在画面上，画面不缩
            AnimatedVisibility(
                visible = panelOpen && current != null,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                    fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                label = "shortsTvPanel",
            ) {
                val item = current
                if (item != null) {
                    ShortsTvPanel(
                        video = item,
                        menu = menu,
                        actions = actions,
                        comments = comments,
                        category = panelCategory,
                        onCategoryChange = { panelCategory = it },
                        likeRequester = likeRequester,
                        onPictureClick = onPictureClick,
                        onLeave = { panelOpen = false },
                    )
                }
            }

            if (hintVisible) ShortsTvHintOverlay(onDismiss = dismissHint)
        }
    }
}

/** 横屏视频：优先用播放器上报的宽高，没有宽高时按竖屏处理（竖屏框也能完整放下画面） */
private fun ShortsPlayerPool.isLandscapeVideo(item: ShortsItem): Boolean {
    val slot = slotFor(item.aid)
    val width = slot?.videoWidth?.takeIf { it > 0 } ?: item.width
    val height = slot?.videoHeight?.takeIf { it > 0 } ?: item.height
    return height > 0 && width > height
}

/**
 * 从左缘（屏幕宽 12% 内）起手的横滑归导航轨，向右滑过 64dp 呼出（沿用原来的手势）。
 * 在 Initial 阶段判定并吃掉事件：画面的拖进度、切条手势看到已消费的事件就放弃，左缘不会误拖进度。
 */
private fun Modifier.tvEdgeSwipe(enabled: Boolean, onSwipe: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val slop = TouchSlop.toPx()
        val threshold = SwipeThreshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > size.width * EdgeFraction) return@awaitEachGesture
            var locked = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!change.pressed) {
                    if (locked) {
                        change.consume()
                        if (dx > threshold) onSwipe()
                    }
                    break
                }
                if (!locked) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                        locked = true
                    } else if (abs(dy) > slop) {
                        break
                    }
                }
                if (locked) change.consume()
            }
        }
    }

/**
 * 画面上的触摸（设计稿「电视」触摸表）：单击暂停 / 继续，上下滑切条，长按打开面板。
 * 横滑交给 [shortsSeekGesture]：两边用同一套方向判定，不管谁先看到这次移动都只有一边接；
 * 事件已被消费（拖进度锁定、左缘呼出导航轨、进度条自己的点按）就放弃。
 */
private fun Modifier.tvTouchGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = TouchSlop.toPx()
    val threshold = SwipeThreshold.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var released = false
        var vertical = false
        var aborted = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed) {
                    aborted = true
                    break
                }
                if (!change.pressed) {
                    released = true
                    break
                }
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    aborted = true
                    break
                }
                if (abs(dy) > slop) {
                    vertical = true
                    change.consume()
                    break
                }
            }
        }
        when {
            aborted -> Unit
            released -> onTap()
            vertical -> {
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    dy = change.position.y - down.position.y
                    change.consume()
                    if (!change.pressed) break
                }
                // 上滑下一条，下滑上一条
                if (dy <= -threshold) {
                    onSwipe(1)
                } else if (dy >= threshold) {
                    onSwipe(-1)
                }
            }

            else -> {
                onLongPress()
                // 长按之后到抬起的事件都吃掉，不再当作单击或滑动
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 只读信息

/** 竖屏右侧信息列（只读，焦点不需要挪过来）：UP 主、标题、Meta、互动状态、两条评论，底部一行按键提示 */
@Composable
private fun TvInfo(
    item: ShortsItem,
    vm: ShortsViewModel,
    comments: ShortsComments,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvUpRow(
                item = item,
                vm = vm,
                nameColor = MaterialTheme.colorScheme.onSurface,
                subColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.title,
                style = BvType.TitleLarge.copy(lineHeight = 27.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = item.metaLine()
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = BvType.Meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvStatChips(item = item, vm = vm)
            val preview = if (comments.loadedAid == item.aid) comments.comments.take(2) else emptyList()
            if (preview.isNotEmpty()) {
                // 屏幕矮时评论卡先被压缩，按键提示始终留在底部
                TvFittingColumn(
                    spacing = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .8f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    preview.forEach { CommentItem(comment = it, maxLines = 2) }
                }
            }
        }
        TvKeyHints(Modifier.padding(top = 12.dp))
    }
}

/**
 * 竖排，但只摆放得下的子项：每个子项按自然高度量，放不下的整条不显示（第一条总会显示，超出部分由外层裁掉）。
 *
 * 普通 Column 被压矮时，超高的子项会被居中偏移，评论配图就压到正文上了。
 */
@Composable
private fun TvFittingColumn(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val shown = ArrayList<Placeable>(measurables.size)
        var used = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(childConstraints)
            val next = if (shown.isEmpty()) placeable.height else used + gap + placeable.height
            if (shown.isNotEmpty() && next > constraints.maxHeight) break
            shown += placeable
            used = next
        }
        val width = (shown.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = used.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            shown.forEach {
                it.placeRelative(0, y)
                y += it.height + gap
            }
        }
    }
}

/** 横屏视频的全屏信息层：底部渐变上叠 UP 主 / 标题 / Meta、右侧互动状态，下面是进度条和按键提示（全部只读） */
@Composable
private fun TvLandscapeInfo(
    item: ShortsItem,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    scrub: ShortsScrub,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val meta = item.metaLine()
    Column(
        modifier
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = .55f),
                    1f to Color.Black.copy(alpha = .9f),
                )
            )
            .padding(start = 96.dp, end = 48.dp, top = 120.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvUpRow(
                    item = item,
                    vm = vm,
                    nameColor = Color.White,
                    subColor = Color.White.copy(alpha = .8f),
                )
                Text(
                    item.title,
                    style = BvType.Display.copy(fontSize = 30.sp, lineHeight = 36.sp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = BvType.Meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = Color.White.copy(alpha = .85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TvStatChips(
                item = item,
                vm = vm,
                modifier = Modifier.widthIn(max = 300.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            )
        }
        ShortsScrubber(
            pool = pool,
            item = item,
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            scrub = scrub,
            showTime = true,
        )
        TvKeyHints()
    }
}

/** 头像 + 名字 / 粉丝数；已关注时后面跟一个「已关注」标签 */
@Composable
private fun TvUpRow(
    item: ShortsItem,
    vm: ShortsViewModel,
    nameColor: Color,
    subColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BvAvatar(url = item.author.face, size = 40.dp, fallbackText = item.author.name)
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                item.author.name,
                style = BvType.Body.copy(fontWeight = FontWeight.Bold),
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.authorFans?.let { fans ->
                Text(
                    stringResource(R.string.shorts_fans, shortsCount(fans)),
                    style = BvType.Meta,
                    color = subColor,
                    maxLines = 1,
                )
            }
        }
        if (item.author.mid > 0 && vm.isFollowing(item.author.mid) == true) {
            val followed = stringResource(R.string.shorts_followed)
            TvStatChip(
                icon = painterResource(R.drawable.ic_check_24),
                text = followed,
                on = true,
                description = followed,
            )
        }
    }
}

/** 点赞 / 投币 / 收藏 / 评论 的状态（图标 + 数，不可聚焦；操作在刷刷面板里） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvStatChips(
    item: ShortsItem,
    vm: ShortsViewModel,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
) {
    val state = vm.actionStateOf(item.aid)
    val coined = state.coinCount > 0
    FlowRow(
        modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TvStatChip(
            icon = painterResource(R.drawable.ic_thumb_up_24),
            text = countOrLabel(item.likeCount, R.string.shorts_like),
            on = state.liked,
            description = stringResource(if (state.liked) R.string.shorts_tv_liked else R.string.shorts_like),
        )
        val coinText = stringResource(if (coined) R.string.shorts_tv_coined else R.string.shorts_coin)
        TvStatChip(
            icon = painterResource(R.drawable.ic_coin_24),
            text = coinText,
            on = coined,
            description = coinText,
        )
        TvStatChip(
            icon = painterResource(R.drawable.ic_star_24),
            text = countOrLabel(item.favoriteCount, R.string.shorts_favorite),
            on = state.favoured,
            description = stringResource(if (state.favoured) R.string.shorts_tv_favoured else R.string.shorts_favorite),
        )
        TvStatChip(
            icon = painterResource(R.drawable.ic_chat_bubble_24),
            text = countOrLabel(item.replyCount, R.string.shorts_comment),
            on = false,
            description = stringResource(R.string.shorts_comment),
        )
    }
}

/** 状态标签：开时 primary 底；content-desc 带上状态（「已点赞 1.2万」），方便 uiautomator 读 */
@Composable
private fun TvStatChip(icon: Painter, text: String, on: Boolean, description: String) {
    val scheme = MaterialTheme.colorScheme
    val content = if (on) scheme.onPrimary else Color.White
    val label = if (description == text) text else "$description $text"
    Row(
        Modifier
            .background(if (on) scheme.primary else Color.White.copy(alpha = .12f), CircleShape)
            .padding(start = 7.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = content)
        Text(text, style = StatChipText, color = content, maxLines = 1)
    }
}

/** 「↑↓ 切换 · ←→ 快进退 · OK 暂停 · 长按 OK ≡ 面板」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvKeyHints(modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TvKeyHint(listOf(stringResource(R.string.shorts_tv_key_updown)), stringResource(R.string.shorts_tv_hint_switch))
        TvKeyHint(listOf(stringResource(R.string.shorts_tv_key_leftright)), stringResource(R.string.shorts_tv_hint_seek))
        TvKeyHint(listOf(stringResource(R.string.shorts_tv_key_ok)), stringResource(R.string.shorts_tv_hint_pause))
        TvKeyHint(
            listOf(stringResource(R.string.shorts_tv_key_ok_long), stringResource(R.string.shorts_tv_key_menu)),
            stringResource(R.string.shorts_tv_hint_panel),
        )
    }
}

@Composable
private fun TvKeyHint(keys: List<String>, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { TvKeyCap(it) }
        Text(label, style = KeyHintText, color = Color.White.copy(alpha = .7f), maxLines = 1)
    }
}

@Composable
private fun TvKeyCap(
    text: String,
    style: TextStyle = KeyCapText,
    container: Color = Color.White.copy(alpha = .15f),
    content: Color = Color.White,
) {
    Text(
        text,
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = style,
        color = content,
        maxLines = 1,
    )
}

/** 竖屏右侧「接下来」：替代原来左侧的播放队列缩略图 */
@Composable
private fun TvUpNext(items: List<ShortsItem>, modifier: Modifier = Modifier) {
    val separator = stringResource(R.string.shorts_meta_separator)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.shorts_tv_up_next),
            style = BvType.Eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { next ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = next.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp, 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        next.title,
                        style = UpNextTitleText,
                        color = Color.White.copy(alpha = .85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        next.author.name + separator + formatShortsTime(next.duration * 1000L),
                        style = BvType.Meta,
                        color = Color.White.copy(alpha = .6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 「标签　x 点赞」/「x 点赞」/ 标签（竖屏信息列与横屏信息层共用） */
@Composable
private fun ShortsItem.metaLine(): String {
    val tags = tagsText()
    val likes = likeCount?.let { shortsCount(it) }
    return when {
        tags.isNotEmpty() && likes != null -> stringResource(R.string.shorts_tags_like, tags, likes)
        likes != null -> stringResource(R.string.shorts_like_count, likes)
        else -> tags
    }
}

// ---------------------------------------------------------------- 刷刷面板

/**
 * 刷刷面板（长按确认 / 菜单键 / 长按画面）：外壳与主播放器 TvMenuPanel 相同，
 * 左列分类（宽 96，surfaceContainerHigh，聚焦即切换），右列选项；选项来自 [ShortsMenuState]。
 */
@Composable
private fun ShortsTvPanel(
    video: ShortsItem,
    menu: ShortsMenuState,
    actions: ShortsActions,
    comments: ShortsComments,
    category: TvPanelCategory,
    onCategoryChange: (TvPanelCategory) -> Unit,
    likeRequester: FocusRequester,
    onPictureClick: (List<Picture>, Int) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = TvPanelCategory.entries
    val categoryRequesters = remember { categories.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val panelLabel = stringResource(R.string.shorts_tv_panel)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier
                .padding(top = 16.dp, end = 16.dp, bottom = 16.dp)
                .width(PanelWidth)
                .fillMaxHeight()
                .clip(PanelShape)
                .background(MaterialTheme.colorScheme.surfaceContainer, PanelShape)
                // 面板空白处也接住触摸，不让它落到下面「点面板外关闭」的那一层
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = panelLabel },
        ) {
            Column(
                Modifier
                    .width(96.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 16.dp, horizontal = 8.dp)
                    .focusRestorer(categoryRequesters.getValue(category))
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                categories.forEachIndexed { i, entry ->
                    TvPanelCategoryItem(
                        label = stringResource(entry.label),
                        icon = painterResource(entry.icon),
                        selected = entry == category,
                        onClick = {
                            onCategoryChange(entry)
                            // 确认键进入右列；触摸点分类只切换
                            if (inputModeManager.inputMode == InputMode.Keyboard) focusManager.moveFocus(FocusDirection.Right)
                        },
                        onFocused = { if (it) onCategoryChange(entry) },
                        modifier = Modifier
                            .focusRequester(categoryRequesters.getValue(entry))
                            .tvPanelEdges(first = i == 0, last = i == categories.lastIndex, trapLeft = true),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRestorer()
                    .focusGroup()
            ) {
                // 换分类时右列从顶部重新开始
                key(category) {
                    if (category == TvPanelCategory.Comments) {
                        TvPanelComments(
                            video = video,
                            comments = comments,
                            onPictureClick = onPictureClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            when (category) {
                                TvPanelCategory.Interact -> interactOptions(video, menu, actions, likeRequester, onLeave)
                                TvPanelCategory.Danmaku -> danmakuOptions(menu.danmaku)
                                TvPanelCategory.Picture -> pictureOptions(video, menu)
                                TvPanelCategory.Playback -> playbackOptions(menu.pool)
                                TvPanelCategory.Comments -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 互动：点赞（默认焦点）、投币、收藏、关注 UP、稍后再看、视频详情、UP 主页。按钮显示当前状态 */
private fun LazyListScope.interactOptions(
    video: ShortsItem,
    menu: ShortsMenuState,
    actions: ShortsActions,
    likeRequester: FocusRequester,
    onLeave: () -> Unit,
) {
    val hasUp = video.author.mid > 0
    item(key = "like") {
        val liked = menu.vm.actionStateOf(video.aid).liked
        TvPanelOption(
            text = stringResource(if (liked) R.string.shorts_tv_liked else R.string.shorts_like),
            icon = painterResource(if (liked) R.drawable.ic_thumb_up_24 else R.drawable.ic_thumb_up_outlined_24),
            active = liked,
            trailing = video.likeCount?.let { shortsCount(it) },
            onClick = { actions.like(video) },
            modifier = Modifier
                .focusRequester(likeRequester)
                .tvPanelEdges(first = true),
        )
    }
    item(key = "coin") {
        val coined = menu.vm.actionStateOf(video.aid).coinCount > 0
        TvPanelOption(
            text = stringResource(if (coined) R.string.shorts_tv_coined else R.string.shorts_coin),
            icon = painterResource(R.drawable.ic_coin_24),
            active = coined,
            trailing = video.coinCount?.let { shortsCount(it) },
            onClick = { actions.coin(video) },
        )
    }
    item(key = "favorite") {
        val favoured = menu.vm.actionStateOf(video.aid).favoured
        TvPanelOption(
            text = stringResource(if (favoured) R.string.shorts_tv_favoured else R.string.shorts_favorite),
            icon = painterResource(if (favoured) R.drawable.ic_star_24 else R.drawable.ic_star_outlined_24),
            active = favoured,
            trailing = video.favoriteCount?.let { shortsCount(it) },
            onClick = { actions.favorite(video) },
        )
    }
    if (hasUp) {
        item(key = "follow") {
            val following = menu.vm.isFollowing(video.author.mid) == true
            TvPanelOption(
                text = stringResource(if (following) R.string.shorts_followed else R.string.shorts_tv_follow_up),
                icon = painterResource(if (following) R.drawable.ic_check_24 else R.drawable.ic_person_add_24),
                active = following,
                trailing = video.author.name,
                onClick = { actions.follow(video) },
            )
        }
    }
    item(key = "toview") {
        val inToView = menu.inToView(video)
        TvPanelOption(
            text = stringResource(if (inToView) R.string.shorts_menu_toview_added else R.string.shorts_menu_toview),
            icon = painterResource(if (inToView) R.drawable.ic_watch_later_24 else R.drawable.ic_watch_later_outlined_24),
            active = inToView,
            onClick = { menu.toggleToView(video) },
        )
    }
    item(key = "detail") {
        TvPanelOption(
            text = stringResource(R.string.shorts_menu_detail),
            icon = painterResource(R.drawable.ic_info_24),
            onClick = {
                onLeave()
                actions.openVideo(video)
            },
            modifier = Modifier.tvPanelEdges(last = !hasUp),
        )
    }
    if (hasUp) {
        item(key = "space") {
            TvPanelOption(
                text = stringResource(R.string.shorts_up_page),
                icon = painterResource(R.drawable.ic_person_24),
                onClick = {
                    onLeave()
                    actions.openSpace(video.author.mid)
                },
                modifier = Modifier.tvPanelEdges(last = true),
            )
        }
    }
}

/** 弹幕：开关；区域 少 / 半屏 / 全屏；不透明度、字号（常用值单选）；密度；类型 滚动 / 顶部 / 底部 */
private fun LazyListScope.danmakuOptions(danmaku: ShortsDanmaku) {
    item(key = "dmSwitch") {
        TvPanelOption(
            text = stringResource(R.string.shorts_tv_danmaku_show),
            checked = danmaku.enabled,
            onClick = { danmaku.toggle() },
            modifier = Modifier.tvPanelEdges(first = true),
        )
    }
    panelEyebrow("dmAreaTitle", R.string.shorts_menu_danmaku_area)
    ShortsDanmakuArea.entries.forEach { area ->
        item(key = "dmArea-${area.name}") {
            TvPanelOption(
                text = area.label(),
                checked = danmaku.area == area,
                onClick = { danmaku.updateArea(area) },
            )
        }
    }
    panelEyebrow("dmOpacityTitle", R.string.shorts_tv_danmaku_opacity)
    DanmakuOpacityPresets.forEach { value ->
        item(key = "dmOpacity-$value") {
            TvPanelOption(
                text = percentLabel(value),
                checked = near(danmaku.opacity, value),
                onClick = { danmaku.updateOpacity(value) },
            )
        }
    }
    panelEyebrow("dmScaleTitle", R.string.shorts_tv_danmaku_scale)
    DanmakuScalePresets.forEach { value ->
        item(key = "dmScale-$value") {
            TvPanelOption(
                text = percentLabel(value),
                checked = near(danmaku.scale, value),
                onClick = { danmaku.updateScale(value) },
            )
        }
    }
    panelEyebrow("dmDensityTitle", R.string.shorts_menu_danmaku_density)
    DanmakuLaneDensity.entries.forEach { density ->
        item(key = "dmDensity-${density.name}") {
            TvPanelOption(
                text = density.label(),
                checked = danmaku.laneDensity == density,
                onClick = { danmaku.updateLaneDensity(density) },
            )
        }
    }
    panelEyebrow("dmTypesTitle", R.string.shorts_menu_danmaku_types)
    ShortsDanmakuTypes.forEachIndexed { i, type ->
        item(key = "dmType-${type.name}") {
            TvPanelOption(
                text = type.shortsLabel(),
                checked = danmaku.isTypeOn(type),
                onClick = { danmaku.toggleType(type) },
                modifier = Modifier.tvPanelEdges(last = i == ShortsDanmakuTypes.lastIndex),
            )
        }
    }
}

/** 画面：画质（解析出来才有）、倍速 */
private fun LazyListScope.pictureOptions(video: ShortsItem, menu: ShortsMenuState) {
    val pool = menu.pool
    val qualities = menu.qualitiesOf(video)
    if (qualities.isNotEmpty()) {
        panelEyebrow("qualityTitle", R.string.shorts_menu_quality, first = true)
        qualities.forEachIndexed { i, quality ->
            item(key = "quality-$i") {
                val context = LocalContext.current
                TvPanelOption(
                    text = quality.getDisplayName(context),
                    checked = quality == menu.qualityOf(video),
                    onClick = { pool.setQuality(quality) },
                    modifier = Modifier.tvPanelEdges(first = i == 0),
                )
            }
        }
    }
    panelEyebrow("speedTitle", R.string.shorts_menu_speed, first = qualities.isEmpty())
    val speeds = menu.speeds
    speeds.forEachIndexed { i, speed ->
        item(key = "speed-$i") {
            TvPanelOption(
                text = formatShortsSpeed(speed),
                checked = near(pool.speed, speed),
                onClick = { pool.setSpeed(speed) },
                modifier = Modifier.tvPanelEdges(first = qualities.isEmpty() && i == 0, last = i == speeds.lastIndex),
            )
        }
    }
}

/** 播放：播完后 循环本条 / 自动下一条 */
private fun LazyListScope.playbackOptions(pool: ShortsPlayerPool) {
    panelEyebrow("afterEndTitle", R.string.shorts_menu_after_end, first = true)
    val options = ShortsAfterEnd.entries
    options.forEachIndexed { i, option ->
        item(key = "afterEnd-${option.name}") {
            TvPanelOption(
                text = option.label(),
                checked = pool.afterEnd == option,
                onClick = { pool.setAfterEnd(option) },
                modifier = Modifier.tvPanelEdges(first = i == 0, last = i == options.lastIndex),
            )
        }
    }
}

/** 评论：沿用原来评论面板里的列表（每条整体可聚焦，确认键开楼中楼 / 图片预览） */
@Composable
private fun TvPanelComments(
    video: ShortsItem,
    comments: ShortsComments,
    onPictureClick: (List<Picture>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRequester = remember { FocusRequester() }
    Column(modifier.padding(top = 16.dp, start = 10.dp, end = 10.dp)) {
        val count = video.replyCount
        TvPanelEyebrow(
            if (count != null) stringResource(R.string.shorts_tab_comments, shortsCount(count))
            else stringResource(R.string.shorts_comment),
            first = true,
        )
        ShortsCommentList(
            item = video,
            comments = comments,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
            tv = true,
            firstItemRequester = firstRequester,
            onPictureClick = onPictureClick,
        )
    }
}

private fun LazyListScope.panelEyebrow(key: String, @StringRes title: Int, first: Boolean = false) {
    item(key = key, contentType = "eyebrow") { TvPanelEyebrow(stringResource(title), first) }
}

@Composable
private fun TvPanelEyebrow(text: String, first: Boolean) {
    Text(
        text,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = if (first) 0.dp else 10.dp, bottom = 6.dp),
        style = BvType.Eyebrow,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}

/**
 * 焦点不出面板：导航轨还没收起时，2D 搜索会从面板边缘的项跳到导轨上。
 * 只给边缘的项设：列表内部的上下移动、左右两列之间的移动不受影响。
 */
private fun Modifier.tvPanelEdges(first: Boolean = false, last: Boolean = false, trapLeft: Boolean = false): Modifier =
    if (!first && !last && !trapLeft) this else focusProperties {
        if (first) up = FocusRequester.Cancel
        if (last) down = FocusRequester.Cancel
        if (trapLeft) left = FocusRequester.Cancel
    }

/**
 * 面板右列的一项（原型 `.tvp-o .o`）：动作项是图标 + 文字 + 右侧小字，[active] 时图标 primary；
 * 单选 / 开关项前面是 ✓（[checked]，secondaryContainer 底）。方向键聚焦：放大 + 描边 + inverseSurface。
 */
@Composable
private fun TvPanelOption(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    active: Boolean = false,
    checked: Boolean = false,
    trailing: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val container by focusContainerColor(focused, if (checked) scheme.secondaryContainer else Color.Transparent)
    val content by focusContentColor(focused, if (checked) scheme.onSecondaryContainer else scheme.onSurface)
    val showingFocus = showFocus(focused)
    val secondary = if (showingFocus) content else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .bvFocus(scale = 1.04f, corner = 14.dp)
            .semantics { this.selected = checked || active },
        shape = PanelOptionShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (active && !showingFocus) scheme.primary else secondary,
                )

                checked -> Icon(
                    painterResource(R.drawable.ic_check_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )

                else -> Spacer(Modifier.size(20.dp))
            }
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = PanelOptionText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Text(
                    trailing,
                    modifier = Modifier.widthIn(max = 96.dp),
                    style = BvType.Meta,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 面板左列分类：图标在上、文字在下；选中 secondaryContainer，聚焦即切换 */
@Composable
private fun TvPanelCategoryItem(
    label: String,
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val container by focusContainerColor(focused, if (selected) scheme.secondaryContainer else Color.Transparent)
    val content by focusContentColor(focused, if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .bvFocus(scale = 1.04f, corner = 16.dp, onFocused = onFocused)
            .semantics { this.selected = selected },
        shape = PanelCategoryShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                label,
                style = PanelCategoryText.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

private fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.001f

private fun percentLabel(value: Float): String = "${(value * 100).roundToInt()}%"

// ---------------------------------------------------------------- 首次进入的按键提示

/**
 * 首次进入刷刷显示一次的按键提示（样式仿主播放器 RemoteControlHintOverlay；它描述的是主播放器的按键，
 * ↑ 选集、↓ 播放信息、返回退出，和刷刷对不上，所以单独写一份）。
 * 铺满整页，确认键（抬起时）、返回键或点一下关闭；关闭后焦点回到画面。
 */
@Composable
private fun ShortsTvHintOverlay(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(120L)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.surface.copy(alpha = 0.94f))
            // 按下时吞掉除返回外的按键；确认在抬起时关闭，避免抬起事件落到下面的画面上变成暂停。
            // 返回交给 BackHandler，API 33+ 的预测性返回也只走 dispatcher。
            .onPreviewKeyEvent { event ->
                when (event.toBvKey()) {
                    null, BvKey.Back -> false
                    BvKey.Center, BvKey.CenterLong -> {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }

                    else -> true
                }
            }
            .focusRequester(requester)
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.shorts_tv_guide_title),
                style = BvType.PanelTitle,
                color = scheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvGuideRow(
                    keys = listOf(
                        stringResource(R.string.shorts_tv_guide_key_updown),
                        stringResource(R.string.shorts_tv_guide_key_channel),
                    ),
                    text = stringResource(R.string.shorts_tv_guide_switch),
                )
                TvGuideRow(
                    keys = listOf(stringResource(R.string.shorts_tv_guide_key_leftright)),
                    text = stringResource(R.string.shorts_tv_guide_seek),
                )
                TvGuideRow(
                    keys = listOf(stringResource(R.string.shorts_tv_guide_key_ok)),
                    text = stringResource(R.string.shorts_tv_guide_ok),
                )
                TvGuideRow(
                    keys = listOf(
                        stringResource(R.string.shorts_tv_guide_key_ok_long),
                        stringResource(R.string.shorts_tv_guide_key_menu),
                    ),
                    text = stringResource(R.string.shorts_tv_guide_panel),
                )
                TvGuideRow(
                    keys = listOf(stringResource(R.string.shorts_tv_guide_key_back)),
                    text = stringResource(R.string.shorts_tv_guide_back),
                )
            }
            Text(
                stringResource(R.string.shorts_tv_guide_continue),
                style = BvType.Body,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvGuideRow(keys: List<String>, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(220.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            keys.forEach {
                TvKeyCap(
                    it,
                    style = GuideKeyText,
                    container = scheme.surfaceContainerHighest,
                    content = scheme.onSurface,
                )
            }
        }
        Text(
            text,
            modifier = Modifier.width(340.dp),
            style = BvType.Body,
            color = scheme.onSurface,
        )
    }
}
