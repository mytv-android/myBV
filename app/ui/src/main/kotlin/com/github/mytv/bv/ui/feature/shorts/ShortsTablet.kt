package com.github.mytv.bv.ui.feature.shorts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.entity.ShortsDanmakuArea
import com.github.mytv.bv.entity.ShortsFold
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.entity.ShortsLayout
import com.github.mytv.bv.entity.ShortsRect
import com.github.mytv.bv.entity.ShortsZone
import com.github.mytv.bv.entity.ShortsZoneKind
import com.github.mytv.bv.entity.resolveShortsLayout
import com.github.mytv.bv.ui.designsystem.adaptive.LocalContentStartInset
import com.github.mytv.bv.ui.designsystem.adaptive.LocalWindowFold
import com.github.mytv.bv.ui.designsystem.component.Ambient
import com.github.mytv.bv.ui.designsystem.component.BvAvatar
import com.github.mytv.bv.ui.designsystem.component.BvButton
import com.github.mytv.bv.ui.designsystem.component.BvButtonStyle
import com.github.mytv.bv.ui.designsystem.component.BvIconButton
import com.github.mytv.bv.ui.designsystem.component.BvIconButtonStyle
import com.github.mytv.bv.ui.designsystem.component.BvTabRow
import com.github.mytv.bv.ui.designsystem.component.CinemaScrim
import com.github.mytv.bv.ui.designsystem.component.VideoRow
import com.github.mytv.bv.ui.designsystem.component.safeLazyKey
import com.github.mytv.bv.ui.designsystem.focus.BvKey
import com.github.mytv.bv.ui.designsystem.focus.toBvKey
import com.github.mytv.bv.ui.designsystem.theme.BvTheme
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.designsystem.theme.CoverTheme
import com.github.mytv.bv.ui.designsystem.theme.LocalBvDark
import com.github.mytv.bv.ui.designsystem.theme.LocalThemePrefs
import com.github.mytv.bv.viewmodel.ShortsViewModel
import dev.aaa1115910.biliapi.entity.Picture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 键盘 ← → 每次（含按住时的连发）快退 / 快进的步长 */
private const val TabletKeySeekStepMs = 5_000L

/** 键盘快进退松开多久后才真正跳转；这段时间内再按会接着累计 */
private const val TabletKeySeekCommitMs = 600L

/** 按住 ← → 时连发的最小间隔 */
private const val TabletKeySeekRepeatMs = 120L

/** 滚轮一格一条：触控板一次滑动会连发很多滚动事件，这段时间内只算一次 */
private const val TabletWheelThrottleMs = 400L

/** 控制区上下滑多远算切条 */
private val TabletSwipeThreshold = 56.dp

/** 判断区域是否贴着容器边缘的误差（布局结果取整后可能差零点几） */
private const val TabletEdgeSlop = 0.5f

/** 信息栏标签：评论 / 相关 / 设置 */
private const val TabletTabComments = 0
private const val TabletTabRelated = 1
private const val TabletTabSettings = 2
private val TabletAllTabs = listOf(TabletTabComments, TabletTabRelated, TabletTabSettings)

/** 设置页可以放进这些区域；都没有时评论区自己带上「相关 / 设置」 */
private val TabletSettingsHosts = setOf(
    ShortsZoneKind.InfoTabs,
    ShortsZoneKind.Info,
    ShortsZoneKind.InfoAndComments,
    ShortsZoneKind.InfoStripWithComments,
)

/** 画面上叠加按钮的底色（原型 `.g-oc` / `.g-ud`） */
private val TabletCoverButtonColor = Color.White.copy(alpha = .16f)
private val TabletFloatButtonColor = Color.Black.copy(alpha = .44f)

private val TabletCoverLabelStyle = TextStyle(
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 3f),
)

/**
 * 平板 / 折叠屏 / 双屏刷刷（设计稿「大屏与折叠」）：不再是固定的「9:16 视频框 + 两侧工具栏」，
 * 而是每条视频按 [resolveShortsLayout] 先按折痕切区、再让画面贴满所在区域，剩下的空间放信息、评论或控制。
 *
 * - [VerticalPager] 铺满整个容器，每一页按它自己那条视频的布局摆画面（不加圆角和边距）；
 *   画面以外的区域只为当前条画一次，叠在 Pager 上面，翻页时跟着当前条切换。
 * - 画面区域强制深色；信息栏、评论区跟随全局明暗。折痕 / 铰链区域留黑，画面和按钮都不压上去。
 * - 展开、折叠、半开切换只是容器尺寸和折痕变化，靠重组重新摆放；Pager 和播放器不重建，当前条继续播放。
 * - 弹幕整页只有一个弹幕层，摆在当前条的画面矩形上。
 * - 键盘：空格 / K 暂停，← → 快退快进 5 秒，↑ ↓ PgUp PgDn 切条，滚轮一格一条，
 *   D 弹幕，L 点赞，C 评论，M（或画面上右键）设置，F 横屏视频全屏，Esc 依次退出全屏、关闭评论、离开设置页。
 */
@Composable
internal fun ShortsTablet(
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    comments: ShortsComments,
    actions: ShortsActions,
    menu: ShortsMenuState,
    onPictureClick: (List<Picture>, Int) -> Unit,
) {
    val items = vm.items
    val pagerState = rememberPagerState(
        initialPage = vm.currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    ) { items.size }
    PagerSettleEffect(pagerState, vm, pool)
    val current = items.getOrNull(pagerState.currentPage)
    LoadCommentsEffect(current?.aid, comments)

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = LocalThemePrefs.current.reduceMotion
    val pageSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val latestPageSpec by rememberUpdatedState(pageSpec)
    val scrub = remember { ShortsScrub() }
    val rootRequester = remember { FocusRequester() }

    var tab by rememberSaveable { mutableIntStateOf(TabletTabComments) }
    var tabBeforeSettings by rememberSaveable { mutableIntStateOf(TabletTabComments) }
    // 「侧栏」打开：单区横屏视频让出右侧 400 放评论；桌面模式在下半屏的控制区；其余没有位置时叠在右侧
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var keySeekJob by remember { mutableStateOf<Job?>(null) }
    var lastKeySeekAt by remember { mutableLongStateOf(0L) }
    var escapeConsumed by remember { mutableStateOf(false) }
    var measuredOrigin by remember { mutableStateOf<Offset?>(null) }

    val goTo: (Int) -> Unit = remember(pagerState, scope) {
        { page: Int ->
            val last = pagerState.pageCount - 1
            if (last >= 0) {
                scope.launch {
                    pagerState.animateScrollToPage(page.coerceIn(0, last), animationSpec = latestPageSpec)
                }
            }
        }
    }
    // 翻页动画进行中连按时以目标页为基准，按几下就走几条
    val go: (Int) -> Unit = remember(pagerState, goTo) {
        { delta: Int ->
            val base = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
            goTo(base + delta)
        }
    }

    // 「播完后：自动下一条」：翻到下一条；已经是最后一条时返回 false，由播放器池从头循环
    DisposableEffect(pool, pagerState, goTo) {
        val onEnded: () -> Boolean = {
            val next = pagerState.currentPage + 1
            if (next < pagerState.pageCount) {
                goTo(next)
                true
            } else {
                false
            }
        }
        pool.onCurrentEnded = onEnded
        onDispose {
            // 切到手机 / 电视布局时新布局可能已经换上了自己的回调，只清掉自己的
            if (pool.onCurrentEnded === onEnded) {
                pool.onCurrentEnded = null
            }
        }
    }

    // 整页接收键盘：进入时把焦点放到页面上。转场中的请求可能被丢掉，重试几帧
    LaunchedEffect(rootRequester) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { rootRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(120)
        }
    }

    // 换条时放弃上一条还没提交的快进退
    LaunchedEffect(current?.aid) {
        keySeekJob?.cancel()
        if (scrub.active && scrub.aid != current?.aid) scrub.cancel()
    }

    val fold = LocalWindowFold.current
    val startInset = LocalContentStartInset.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { measuredOrigin = it.positionInWindow() }
    ) {
        val containerW = maxWidth.value
        val containerH = maxHeight.value
        // 折痕和系统栏都是窗口坐标：换算成容器坐标。首帧还没量到位置时，先按外壳导航轨的宽度估计
        val origin = measuredOrigin
        val originX = origin?.let { with(density) { it.x.toDp().value } } ?: startInset.value
        val originY = origin?.let { with(density) { it.y.toDp().value } } ?: 0f
        val stage = TabletStage(
            width = containerW,
            height = containerH,
            fold = fold?.takeIf { it.separating }?.let { f ->
                ShortsFold(
                    vertical = f.isVertical,
                    start = f.start.value - (if (f.isVertical) originX else originY),
                    thickness = f.thickness.value,
                    occluding = f.occluding,
                )
            },
            insets = TabletInsets(
                left = (safe.calculateLeftPadding(LayoutDirection.Ltr).value - originX).coerceAtLeast(0f),
                top = (safe.calculateTopPadding().value - originY).coerceAtLeast(0f),
                right = safe.calculateRightPadding(LayoutDirection.Ltr).value,
                bottom = safe.calculateBottomPadding().value,
            ),
        )

        val currentSize = current?.let { pool.tabletVideoSize(it) } ?: IntSize.Zero
        val baseLayout = stage.layoutFor(currentSize, commentsOpen = false)
        val layout = if (commentsOpen) stage.layoutFor(currentSize, commentsOpen = true) else baseLayout
        val currentLandscape = !currentSize.isPortraitVideo()
        val fullscreenOn = fullscreen && current != null
        val shown = if (fullscreenOn) stage.fullscreen(layout) else layout

        // 哪些标签有固定位置；没有位置的标签打开侧栏时放进侧栏（控制区或右侧叠加面板）
        val hosted = layout.hostedTabs()
        val panelTabs = TabletAllTabs.filter { it !in hosted }
        val panelVisible = commentsOpen && !fullscreenOn && tab in panelTabs
        val sideVisible = !fullscreenOn && commentsOpen && (panelVisible || layout != baseLayout)
        val commentsVisible = !fullscreenOn &&
            (layout.showsTab(TabletTabComments, tab) || (panelVisible && tab == TabletTabComments))
        val settingsVisible = !fullscreenOn &&
            (layout.showsTab(TabletTabSettings, tab) || (panelVisible && tab == TabletTabSettings))

        val selectTab: (Int) -> Unit = { target ->
            if (target == TabletTabSettings && tab != TabletTabSettings) tabBeforeSettings = tab
            tab = target
        }
        val showTab: (Int) -> Unit = { target ->
            fullscreen = false
            selectTab(target)
            if (target !in baseLayout.hostedTabs()) {
                commentsOpen = true
            }
        }
        // 关闭评论：收起侧栏；评论是信息栏里的一个标签时切到「相关」（两栏、折痕另一侧的评论区一直显示）
        val hideComments: () -> Unit = {
            commentsOpen = false
            if (tab == TabletTabComments) {
                tab = TabletTabRelated
            }
        }
        val leaveSettings: () -> Unit = {
            if (panelVisible && tab == TabletTabSettings) commentsOpen = false
            tab = tabBeforeSettings.takeIf { it != TabletTabSettings } ?: TabletTabComments
        }
        val toggleComments: () -> Unit = {
            if (commentsVisible) hideComments() else showTab(TabletTabComments)
        }
        val toggleSettings: () -> Unit = {
            if (settingsVisible) leaveSettings() else showTab(TabletTabSettings)
        }
        val openSettings: () -> Unit = { showTab(TabletTabSettings) }
        val latestOpenSettings by rememberUpdatedState(openSettings)
        val toggleFullscreen: () -> Unit = {
            if (fullscreen) {
                fullscreen = false
            } else if (current != null && currentLandscape) {
                fullscreen = true
            }
        }
        // Esc：依次退出全屏、关闭评论（侧栏）、离开设置页；都没有时不处理，交给系统当返回键
        val escape: () -> Boolean = {
            when {
                fullscreen -> {
                    fullscreen = false
                    true
                }

                sideVisible -> {
                    commentsOpen = false
                    true
                }

                settingsVisible -> {
                    leaveSettings()
                    true
                }

                else -> false
            }
        }
        BackHandler(enabled = fullscreenOn || sideVisible) {
            if (fullscreen) {
                fullscreen = false
            } else {
                commentsOpen = false
            }
        }

        // ← →：用 ShortsScrub 累计目标位置（进度条展开、画面中间显示目标时间），松开 0.6 秒后才跳转
        val seekByKey: (Boolean) -> Unit = { forward ->
            val item = current
            if (item != null && pool.currentAid == item.aid) {
                val total = pool.durationOf(item)
                if (total > 0) {
                    keySeekJob?.cancel()
                    if (!scrub.activeFor(item)) scrub.start(item.aid, pool.positionNow(item.aid))
                    val step = if (forward) TabletKeySeekStepMs else -TabletKeySeekStepMs
                    scrub.update((scrub.targetMs + step).coerceIn(0L, total))
                }
            }
        }
        val releaseSeekKey: () -> Unit = {
            keySeekJob?.cancel()
            if (scrub.active && !scrub.fromGesture) {
                keySeekJob = scope.launch {
                    delay(TabletKeySeekCommitMs)
                    if (scrub.active && !scrub.fromGesture) scrub.finish(pool)
                }
            }
        }
        // 冒泡阶段处理：焦点在滑块、输入框等子组件里时它们先处理，没消费的才轮到这里
        val onKey: (KeyEvent) -> Boolean = handler@{ event ->
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@handler false
            val item = current ?: return@handler false
            val down = event.type == KeyEventType.KeyDown
            val up = event.type == KeyEventType.KeyUp
            val first = down && event.nativeKeyEvent.repeatCount == 0
            val bv = event.toBvKey()
            when {
                event.key == Key.Escape -> {
                    // 按下时决定是否处理，抬起时保持一致，否则系统会把没处理的 Esc 补发成返回键
                    if (first) escapeConsumed = escape()
                    escapeConsumed
                }

                event.key == Key.Spacebar || event.key == Key.K || bv == BvKey.PlayPause -> {
                    if (first) pool.togglePlay(item.aid)
                    true
                }

                bv == BvKey.Left || bv == BvKey.Rewind || bv == BvKey.Right || bv == BvKey.FastForward -> {
                    val forward = bv == BvKey.Right || bv == BvKey.FastForward
                    if (down) {
                        // 按住时系统每秒连发约 20 次：限一下频率，否则一秒就跳过一分多钟
                        val at = event.nativeKeyEvent.eventTime
                        if (first || at - lastKeySeekAt >= TabletKeySeekRepeatMs) {
                            lastKeySeekAt = at
                            seekByKey(forward)
                        }
                    } else if (up) {
                        releaseSeekKey()
                    }
                    true
                }

                // BvKey 把 PgUp / PgDn 归到频道键
                bv == BvKey.Up || bv == BvKey.ChannelUp -> {
                    if (first) go(-1)
                    true
                }

                bv == BvKey.Down || bv == BvKey.ChannelDown -> {
                    if (first) go(1)
                    true
                }

                event.key == Key.D -> {
                    if (first) menu.danmaku.toggle()
                    true
                }

                event.key == Key.L -> {
                    if (first) actions.like(item)
                    true
                }

                event.key == Key.C -> {
                    if (first) toggleComments()
                    true
                }

                event.key == Key.M || bv == BvKey.Menu -> {
                    if (first) openSettings()
                    true
                }

                event.key == Key.F -> {
                    if (first) toggleFullscreen()
                    true
                }

                else -> false
            }
        }

        val ui = TabletUi(
            vm = vm,
            pool = pool,
            comments = comments,
            actions = actions,
            menu = menu,
            scrub = scrub,
            items = items,
            currentPage = pagerState.currentPage,
            pageCount = pagerState.pageCount,
            tab = tab,
            commentsVisible = commentsVisible,
            settingsVisible = settingsVisible,
            onPictureClick = onPictureClick,
            go = go,
            goTo = goTo,
            onSelectTab = selectTab,
            onToggleComments = toggleComments,
            onToggleSettings = toggleSettings,
            onClosePanel = { commentsOpen = false },
            onFullscreen = if (currentLandscape) toggleFullscreen else null,
        )

        Box(
            Modifier
                .fillMaxSize()
                .onKeyEvent(onKey)
                .focusRequester(rootRequester)
                .focusable()
        ) {
            BvTheme(forceDark = true) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .tabletMouse(
                            onPress = { runCatching { rootRequester.requestFocus() } },
                            onSecondaryClick = { latestOpenSettings() },
                        )
                        .tabletWheel(PointerEventPass.Initial, go),
                    beyondViewportPageCount = 1,
                    key = { page -> safeLazyKey(items.getOrNull(page)?.aid ?: 0L, page) },
                ) { page ->
                    val item = items.getOrNull(page) ?: return@VerticalPager
                    // 每一页按它自己那条视频的方向摆画面；画面以外的区域只为当前条画（在 Pager 外面）
                    val size = pool.tabletVideoSize(item)
                    val pageLayout = stage.layoutFor(size, commentsOpen).let {
                        if (fullscreenOn) stage.fullscreen(it) else it
                    }
                    CoverTheme(item.cover, forceDark = true) {
                        TabletPage(
                            item = item,
                            ui = ui,
                            layout = pageLayout,
                            stage = stage,
                            ambient = fullscreenOn && size.isPortraitVideo(),
                            reduceMotion = reduceMotion,
                        )
                    }
                }

                // 整页只有一个弹幕层（DanmakuView 离开窗口就释放，不能在 Pager 页之间搬），摆在当前条的画面上
                val area = menu.danmaku.area
                ShortsDanmakuLayer(
                    danmaku = menu.danmaku,
                    pool = pool,
                    item = current,
                    // 铺满时信息叠在画面下方，全屏弹幕让出底部
                    areaFraction = if (shown.cover && area == ShortsDanmakuArea.Full) 0.8f else area.fraction,
                    modifier = Modifier.tabletPlace(shown.tabletDanmakuRect(currentSize, stage)),
                    hidden = pagerState.isScrollInProgress || scrub.active,
                )

                if (current != null) {
                    CoverTheme(current.cover, forceDark = true) {
                        TabletVideoChrome(
                            layout = shown,
                            stage = stage,
                            ui = ui,
                            fullscreen = fullscreenOn,
                            onExitFullscreen = { fullscreen = false },
                        )
                        // 控制区要和画面一样深色：进度条的时间、把手是白色的
                        val controls = if (fullscreenOn) null else layout.zone(ShortsZoneKind.Controls)
                        if (controls != null) {
                            TabletControls(
                                zone = controls,
                                item = current,
                                ui = ui,
                                stage = stage,
                                panelTabs = panelTabs,
                                panelOpen = panelVisible,
                            )
                        }
                    }
                }
            }

            // 信息栏、评论区跟随全局明暗，颜色取当前视频封面
            if (current != null && !fullscreenOn) {
                CoverTheme(current.cover) {
                    layout.zones.forEach { zone ->
                        if (zone.kind != ShortsZoneKind.Controls) {
                            TabletZoneBox(zone = zone, layout = layout, item = current, ui = ui, stage = stage)
                        }
                    }
                }
            }

            // 铰链（双屏的物理遮挡）留黑：翻页时相邻页的画面不能从它下面露出来
            shown.hinge?.let { hinge ->
                Box(
                    Modifier
                        .tabletPlace(hinge)
                        .background(Color.Black)
                )
            }

            // 没有固定位置放评论 / 设置时（铺满的竖屏、窄的横屏），从右侧叠一个面板
            val panelWidth = minOf(400f, containerW * 0.9f)
            AnimatedVisibility(
                visible = panelVisible && layout.zone(ShortsZoneKind.Controls) == null,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                    fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                label = "shortsTabletPanel",
            ) {
                current?.let { item ->
                    CoverTheme(item.cover) {
                        TabletOverlayPanel(
                            item = item,
                            ui = ui,
                            tabs = panelTabs,
                            width = panelWidth.dp,
                            edges = stage.edgesOf(ShortsRect(containerW - panelWidth, 0f, panelWidth, containerH)),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 布局

/** 系统栏 / 刘海在容器四边的占用（dp） */
@Immutable
private data class TabletInsets(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** 容器尺寸、容器坐标里的折痕和系统栏占用 */
@Immutable
private data class TabletStage(
    val width: Float,
    val height: Float,
    val fold: ShortsFold?,
    val insets: TabletInsets,
) {
    fun layoutFor(size: IntSize, commentsOpen: Boolean): ShortsLayout =
        resolveShortsLayout(width, height, fold, size.width, size.height, commentsOpen)

    /** 全屏：画面铺满整个容器（分区时只铺满第一片，仍然不跨折痕），其余区域隐藏 */
    fun fullscreen(layout: ShortsLayout): ShortsLayout {
        val area = if (layout.split) layout.videoArea else ShortsRect(0f, 0f, width, height)
        return layout.copy(video = area, videoArea = area, cover = false, zones = emptyList())
    }

    /** 画面和区域背景贴边铺满；贴着容器哪条边，里面的文字和按钮就在那条边让出系统栏 */
    fun edgesOf(rect: ShortsRect): PaddingValues = PaddingValues.Absolute(
        if (rect.x <= TabletEdgeSlop) insets.left.dp else 0.dp,
        if (rect.y <= TabletEdgeSlop) insets.top.dp else 0.dp,
        if (rect.right >= width - TabletEdgeSlop) insets.right.dp else 0.dp,
        if (rect.bottom >= height - TabletEdgeSlop) insets.bottom.dp else 0.dp,
    )
}

/** 视频宽高：优先用播放器上报的值（元数据可能缺失或不准），没有时用元数据兜底（与电视端相同） */
private fun ShortsPlayerPool.tabletVideoSize(item: ShortsItem): IntSize {
    val slot = slotFor(item.aid)
    return IntSize(
        slot?.videoWidth?.takeIf { it > 0 } ?: item.width,
        slot?.videoHeight?.takeIf { it > 0 } ?: item.height,
    )
}

/** 未知宽高按横屏处理，与 [resolveShortsLayout]、[ShortsItem.isPortrait] 一致 */
private fun IntSize.isPortraitVideo(): Boolean = width > 0 && height > width

/** 按容器坐标摆放（折痕是物理位置，不随布局方向镜像） */
private fun Modifier.tabletPlace(rect: ShortsRect): Modifier =
    absoluteOffset(rect.x.dp, rect.y.dp)
        .size(rect.width.coerceAtLeast(0f).dp, rect.height.coerceAtLeast(0f).dp)

/** 弹幕铺在真正有画面的矩形上：完整显示时去掉黑边，贴顶时让出状态栏 */
private fun ShortsLayout.tabletDanmakuRect(size: IntSize, stage: TabletStage): ShortsRect {
    val ratio = if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 16f / 9f
    val frame = if (cover) video else video.tabletFit(ratio)
    val top = if (frame.y <= TabletEdgeSlop) stage.insets.top else 0f
    return ShortsRect(frame.x, frame.y + top, frame.width, (frame.height - top).coerceAtLeast(0f))
}

private fun ShortsRect.tabletFit(ratio: Float): ShortsRect {
    if (width <= 0f || height <= 0f || ratio <= 0f) return this
    return if (width / height > ratio) {
        val w = height * ratio
        ShortsRect(x + (width - w) / 2f, y, w, height)
    } else {
        val h = width / ratio
        ShortsRect(x, y + (height - h) / 2f, width, h)
    }
}

/** 某个区域里可以切换的标签 */
private fun ShortsLayout.tabsOf(kind: ShortsZoneKind): List<Int> = when (kind) {
    ShortsZoneKind.InfoTabs, ShortsZoneKind.InfoStripWithComments -> TabletAllTabs
    ShortsZoneKind.Info, ShortsZoneKind.InfoAndComments -> listOf(TabletTabRelated, TabletTabSettings)
    // 评论区本来只放评论；这套布局里没有别的地方放「相关 / 设置」时由它带上（原型 cmtOnly 面板）
    ShortsZoneKind.Comments ->
        if (zones.any { it.kind in TabletSettingsHosts }) listOf(TabletTabComments) else TabletAllTabs

    ShortsZoneKind.Actions, ShortsZoneKind.InfoStrip, ShortsZoneKind.Controls -> emptyList()
}

/** 这套布局里有固定位置的标签（两栏的右栏一直显示评论） */
private fun ShortsLayout.hostedTabs(): Set<Int> = buildSet {
    zones.forEach { zone ->
        addAll(tabsOf(zone.kind))
        if (zone.kind == ShortsZoneKind.InfoAndComments) add(TabletTabComments)
    }
}

/** 区域里实际显示的标签：选中的标签不在这个区域时显示它的第一个 */
private fun tabletEffectiveTab(tabs: List<Int>, tab: Int): Int = if (tab in tabs) tab else tabs.first()

/** [target] 标签此刻是否在某个区域里显示着 */
private fun ShortsLayout.showsTab(target: Int, tab: Int): Boolean = zones.any { zone ->
    if (target == TabletTabComments && zone.kind == ShortsZoneKind.InfoAndComments) {
        true
    } else {
        val tabs = tabsOf(zone.kind)
        tabs.isNotEmpty() && target in tabs && tabletEffectiveTab(tabs, tab) == target
    }
}

// ---------------------------------------------------------------- 手势

/** 区域盖在 Pager 上面：空白处的点按、拖动不能漏到下面（否则会暂停或翻页） */
private fun Modifier.tabletBlockPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * 滚轮一格一条，[TabletWheelThrottleMs] 内只算一次。
 * 画面上用 Initial 阶段抢在 Pager 自己的滚轮滚动之前处理；区域里用 Main 阶段，列表能滚时让列表先滚。
 */
private fun Modifier.tabletWheel(pass: PointerEventPass, onStep: (Int) -> Unit): Modifier =
    pointerInput(pass, onStep) {
        var last = 0L
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val dy = change.scrollDelta.y
                if (dy == 0f) continue
                change.consume()
                if (last != 0L && change.uptimeMillis - last < TabletWheelThrottleMs) continue
                last = change.uptimeMillis
                onStep(if (dy > 0f) 1 else -1)
            }
        }
    }

/**
 * 画面上的鼠标：按下时把键盘焦点拿回页面；右键打开设置页，并吃掉这次按键，
 * 不触发单击暂停、横滑拖进度和翻页。
 */
private fun Modifier.tabletMouse(onPress: () -> Unit, onSecondaryClick: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val secondary = event.buttons.isSecondaryPressed
                if (event.type == PointerEventType.Press) {
                    onPress()
                    if (secondary) onSecondaryClick()
                }
                if (secondary) event.changes.forEach { it.consume() }
            }
        }
    }

/** 控制区上下滑切条（控制区叠在 Pager 上面，收不到 Pager 自己的拖动） */
private fun Modifier.tabletSwipePager(onStep: (Int) -> Unit): Modifier = pointerInput(onStep) {
    val threshold = TabletSwipeThreshold.toPx()
    var total = 0f
    detectVerticalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (abs(total) > threshold) onStep(if (total < 0f) 1 else -1)
            total = 0f
        },
        onDragCancel = { total = 0f },
    ) { change, dragAmount ->
        total += dragAmount
        change.consume()
    }
}

// ---------------------------------------------------------------- 当前条的状态与回调

/** 信息栏、评论区、控制区、画面叠加层共用的状态与回调（都针对当前条） */
private class TabletUi(
    val vm: ShortsViewModel,
    val pool: ShortsPlayerPool,
    val comments: ShortsComments,
    val actions: ShortsActions,
    val menu: ShortsMenuState,
    val scrub: ShortsScrub,
    val items: List<ShortsItem>,
    val currentPage: Int,
    val pageCount: Int,
    val tab: Int,
    val commentsVisible: Boolean,
    val settingsVisible: Boolean,
    val onPictureClick: (List<Picture>, Int) -> Unit,
    val go: (Int) -> Unit,
    val goTo: (Int) -> Unit,
    val onSelectTab: (Int) -> Unit,
    val onToggleComments: () -> Unit,
    val onToggleSettings: () -> Unit,
    val onClosePanel: () -> Unit,
    /** 横屏视频才有；竖屏视频为 null */
    val onFullscreen: (() -> Unit)?,
)

// ---------------------------------------------------------------- 画面

/**
 * Pager 的一页：画面贴在 [layout] 的画面矩形上。铺满（cover）时信息叠在画面上（左下 @UP / 标题 / 标签，
 * 右侧竖排操作列，同手机）；有控制区时画面上只留标题，操作和进度都在控制区。
 */
@Composable
private fun TabletPage(
    item: ShortsItem,
    ui: TabletUi,
    layout: ShortsLayout,
    stage: TabletStage,
    ambient: Boolean,
    reduceMotion: Boolean,
) {
    val pool = ui.pool
    val scrub = ui.scrub
    val hearts = remember(item.aid) { HeartPops() }
    val paused = pool.currentAid == item.aid && pool.userPaused
    val withControls = layout.zone(ShortsZoneKind.Controls) != null
    // 横滑拖进度时信息和操作列淡出，让出画面
    val chromeAlpha by animateFloatAsState(
        if (scrub.activeFor(item) && scrub.fromGesture) 0f else 1f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shortsTabletChrome",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .tabletPlace(layout.video)
                .background(Color.Black)
                .shortsGestures(item, pool, ui.actions, hearts, reduceMotion)
                .shortsSeekGesture(item, pool, scrub)
        ) {
            // 全屏里的竖屏视频居中，两侧氛围光
            if (ambient) Ambient(item.cover)
            ShortsVideo(pool, item, Modifier.fillMaxSize(), crop = layout.cover)
            if (layout.cover) CinemaScrim()
            ShortsPausedIndicator(
                visible = paused && !scrub.activeFor(item),
                modifier = Modifier.align(Alignment.Center),
            )
            ShortsScrubOverlay(
                scrub = scrub,
                pool = pool,
                item = item,
                modifier = Modifier.align(Alignment.Center),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(stage.edgesOf(layout.video))
            ) {
                if (layout.cover) {
                    TabletCoverInfo(
                        item = item,
                        ui = ui,
                        minimal = withControls,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = chromeAlpha },
                    )
                }
                if (!withControls) {
                    ShortsScrubber(
                        pool = pool,
                        item = item,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        expanded = paused,
                        scrub = scrub,
                    )
                }
            }
            HeartLayer(hearts)
        }
    }
}

/** 当前条画面上的浮层：2× 提示、上一条 / 下一条（贴画面右缘，不占位）、退出全屏 */
@Composable
private fun TabletVideoChrome(
    layout: ShortsLayout,
    stage: TabletStage,
    ui: TabletUi,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
) {
    Box(
        Modifier
            .tabletPlace(layout.video)
            .padding(stage.edgesOf(layout.video))
    ) {
        SpeedBoostPill(
            visible = ui.pool.boosting,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        )
        // 铺满时右侧是操作列，切条靠上下滑、滚轮和方向键（原型同样只在非铺满时显示）
        if (!layout.cover) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabletFloatButton(
                    icon = painterResource(R.drawable.ic_keyboard_arrow_up_24),
                    description = stringResource(R.string.shorts_prev),
                    enabled = ui.currentPage > 0,
                    onClick = { ui.go(-1) },
                )
                TabletFloatButton(
                    icon = painterResource(R.drawable.ic_keyboard_arrow_down_24),
                    description = stringResource(R.string.shorts_next),
                    enabled = ui.currentPage < ui.pageCount - 1,
                    onClick = { ui.go(1) },
                )
            }
        }
        if (fullscreen) {
            TabletFloatButton(
                icon = painterResource(R.drawable.ic_fullscreen_exit_24),
                description = stringResource(R.string.shorts_tablet_exit_fullscreen),
                enabled = true,
                onClick = onExitFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun TabletFloatButton(
    icon: Painter,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BvIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else .38f),
        size = 38.dp,
        iconSize = 22.dp,
        enabled = enabled,
        containerColor = TabletFloatButtonColor,
        contentColor = Color.White,
    )
}

/** 铺满时叠在画面上的信息：左下 @UP / 标题（点击展开简介）/ 标签，右侧竖排操作列 */
@Composable
private fun TabletCoverInfo(
    item: ShortsItem,
    ui: TabletUi,
    minimal: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .weight(1f)
                .padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.shorts_at_up, item.author.name),
                modifier = Modifier.clickable { ui.actions.openSpace(item.author.mid) },
                style = BvType.Section,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TabletTitle(
                item = item,
                actions = ui.actions,
                style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                color = Color.White,
                secondary = Color.White.copy(alpha = .85f),
                collapsedLines = 2,
            )
            val tags = item.tagsText()
            if (tags.isNotEmpty()) {
                Text(
                    tags,
                    style = BvType.Label.copy(fontWeight = FontWeight.Normal),
                    color = Color.White.copy(alpha = .85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!minimal) {
            TabletCoverActions(
                item = item,
                ui = ui,
                modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/** 画面上的竖排操作列（原型 `.g-oc`）：头像 +「+」关注、点赞、投币、收藏、评论、弹幕、设置 */
@Composable
private fun TabletCoverActions(item: ShortsItem, ui: TabletUi, modifier: Modifier = Modifier) {
    val state = ui.vm.actionStateOf(item.aid)
    val danmaku = ui.menu.danmaku
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TabletAvatarFollow(item = item, ui = ui, size = 46.dp)
        TabletCoverAction(
            icon = painterResource(R.drawable.ic_thumb_up_24),
            label = countOrLabel(item.likeCount, R.string.shorts_like),
            description = stringResource(R.string.shorts_like),
            active = state.liked,
            onClick = { ui.actions.like(item) },
        )
        TabletCoverAction(
            icon = painterResource(R.drawable.ic_coin_24),
            label = stringResource(R.string.shorts_coin),
            description = stringResource(R.string.shorts_coin),
            active = state.coinCount > 0,
            onClick = { ui.actions.coin(item) },
        )
        TabletCoverAction(
            icon = painterResource(R.drawable.ic_star_24),
            label = countOrLabel(item.favoriteCount, R.string.shorts_favorite),
            description = stringResource(R.string.shorts_favorite),
            active = state.favoured,
            onClick = { ui.actions.favorite(item) },
        )
        TabletCoverAction(
            icon = painterResource(R.drawable.ic_chat_bubble_24),
            label = countOrLabel(item.replyCount, R.string.shorts_comment),
            description = stringResource(R.string.shorts_comment),
            active = ui.commentsVisible,
            onClick = ui.onToggleComments,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            TabletDanmakuToggle(
                on = danmaku.enabled,
                onClick = { danmaku.toggle() },
                size = 46.dp,
                containerColor = TabletCoverButtonColor,
                glyphColor = Color.White,
            )
            Text(stringResource(R.string.shorts_danmaku), style = TabletCoverLabelStyle, color = Color.White, maxLines = 1)
        }
        TabletCoverAction(
            icon = painterResource(R.drawable.ic_tune_24),
            label = stringResource(R.string.shorts_tab_settings),
            description = stringResource(R.string.shorts_menu),
            active = ui.settingsVisible,
            onClick = ui.onToggleSettings,
        )
    }
}

@Composable
private fun TabletCoverAction(
    icon: Painter,
    label: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BvIconButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            size = 46.dp,
            shape = if (active) RoundedCornerShape(16.dp) else CircleShape,
            containerColor = if (active) MaterialTheme.colorScheme.primary else TabletCoverButtonColor,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
        Text(label, style = TabletCoverLabelStyle, color = Color.White, maxLines = 1)
    }
}

/** 头像（点击进主页）+ 下方「+」关注；已关注时不显示「+」 */
@Composable
private fun TabletAvatarFollow(item: ShortsItem, ui: TabletUi, size: Dp) {
    Box(Modifier.padding(bottom = 8.dp)) {
        BvAvatar(
            url = item.author.face,
            size = size,
            fallbackText = item.author.name,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { ui.actions.openSpace(item.author.mid) },
        )
        if (item.author.mid > 0 && ui.vm.isFollowing(item.author.mid) != true) {
            BvIconButton(
                icon = painterResource(R.drawable.ic_add_24),
                contentDescription = stringResource(R.string.shorts_follow),
                onClick = { ui.actions.follow(item) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 10.dp),
                style = BvIconButtonStyle.Filled,
                size = 22.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/** 「弹」字开关：content-desc 写明当前状态（弹幕已开 / 已关） */
@Composable
private fun TabletDanmakuToggle(
    on: Boolean,
    onClick: () -> Unit,
    size: Dp,
    containerColor: Color,
    glyphColor: Color,
) {
    val description = stringResource(if (on) R.string.shorts_danmaku_on else R.string.shorts_danmaku_off)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ShortsDanmakuGlyph(on = on, color = glyphColor)
    }
}

/**
 * 跟随全局明暗的「弹」字颜色。开启时字是黑色的（[ShortsDanmakuGlyph]），
 * 浅色主题下底色要用浅色（primaryContainer），否则黑字压在深色底上看不见。
 */
@Composable
private fun tabletGlyphColor(on: Boolean): Color = when {
    !on -> MaterialTheme.colorScheme.onSurfaceVariant
    LocalBvDark.current -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.primaryContainer
}

// ---------------------------------------------------------------- 画面以外的区域

/** 画面以外的一块区域（控制区单独画）：铺满区域矩形，内容按贴边情况让出系统栏 */
@Composable
private fun TabletZoneBox(
    zone: ShortsZone,
    layout: ShortsLayout,
    item: ShortsItem,
    ui: TabletUi,
    stage: TabletStage,
) {
    val tabs = layout.tabsOf(zone.kind)
    Box(
        Modifier
            .tabletPlace(zone.rect)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .tabletBlockPointer()
            .padding(stage.edgesOf(zone.rect))
    ) {
        when (zone.kind) {
            ShortsZoneKind.Actions -> TabletActionsColumn(item = item, ui = ui, modifier = Modifier.fillMaxSize())

            ShortsZoneKind.InfoTabs, ShortsZoneKind.Info -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 评论在另一片（Info）时，信息头里不再放评论按钮
                TabletInfoHeader(item = item, ui = ui, showComments = zone.kind == ShortsZoneKind.InfoTabs)
                TabletTabbed(item = item, ui = ui, tabs = tabs)
            }

            ShortsZoneKind.InfoAndComments -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TabletInfoHeader(item = item, ui = ui, showComments = false)
                    TabletTabbed(item = item, ui = ui, tabs = tabs)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    TabletTabbed(item = item, ui = ui, tabs = listOf(TabletTabComments))
                }
            }

            ShortsZoneKind.Comments -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            ) {
                TabletTabbed(item = item, ui = ui, tabs = tabs)
            }

            ShortsZoneKind.InfoStrip -> Box(
                Modifier
                    .fillMaxSize()
                    .tabletWheel(PointerEventPass.Main, ui.go),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 信息条可能很矮：展开简介后在条内滚动
                TabletStrip(
                    item = item,
                    ui = ui,
                    wide = zone.rect.width >= 900f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            ShortsZoneKind.InfoStripWithComments -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TabletStrip(item = item, ui = ui, wide = zone.rect.width >= 900f)
                TabletTabbed(item = item, ui = ui, tabs = tabs)
            }

            // 控制区要强制深色，在画面那一层里单独画
            ShortsZoneKind.Controls -> Unit
        }
    }
}

/** 信息头：UP 主 + 关注、标题（点击展开简介）、Meta、操作按钮 */
@Composable
private fun TabletInfoHeader(item: ShortsItem, ui: TabletUi, showComments: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabletUpRow(item = item, ui = ui, modifier = Modifier.fillMaxWidth())
        TabletTitle(
            item = item,
            actions = ui.actions,
            style = TextStyle(fontSize = 17.sp, lineHeight = 23.8.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            secondary = MaterialTheme.colorScheme.onSurfaceVariant,
            collapsedLines = 3,
        )
        val meta = item.tabletMeta()
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = BvType.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TabletActionRow(item = item, ui = ui, showComments = showComments)
    }
}

/** 横向信息条（原型 `.blk.strip`）：UP 主、标题、操作按钮（含「评论」开关）；窄时操作按钮换到第二行 */
@Composable
private fun TabletStrip(item: ShortsItem, ui: TabletUi, wide: Boolean, modifier: Modifier = Modifier) {
    if (wide) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabletUpRow(item = item, ui = ui, modifier = Modifier.widthIn(max = 280.dp))
            TabletStripTitle(item = item, ui = ui, collapsedLines = 2, modifier = Modifier.weight(1f))
            TabletActionRow(item = item, ui = ui, showComments = true)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletUpRow(item = item, ui = ui, modifier = Modifier.widthIn(max = 260.dp))
                TabletStripTitle(item = item, ui = ui, collapsedLines = 1, modifier = Modifier.weight(1f))
            }
            TabletActionRow(item = item, ui = ui, showComments = true)
        }
    }
}

@Composable
private fun TabletStripTitle(item: ShortsItem, ui: TabletUi, collapsedLines: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TabletTitle(
            item = item,
            actions = ui.actions,
            style = BvType.BodyBold,
            color = MaterialTheme.colorScheme.onSurface,
            secondary = MaterialTheme.colorScheme.onSurfaceVariant,
            collapsedLines = collapsedLines,
        )
        val meta = item.tabletMeta()
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = BvType.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabletUpRow(item: ShortsItem, ui: TabletUi, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BvAvatar(
            url = item.author.face,
            size = 44.dp,
            fallbackText = item.author.name,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { ui.actions.openSpace(item.author.mid) },
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.author.name,
                style = BvType.Body.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.authorFans?.let { fans ->
                Text(
                    stringResource(R.string.shorts_fans, shortsCount(fans)),
                    style = BvType.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (item.author.mid > 0) {
            val following = ui.vm.isFollowing(item.author.mid) == true
            BvButton(
                text = stringResource(if (following) R.string.shorts_followed else R.string.shorts_follow),
                onClick = { ui.actions.follow(item) },
                style = if (following) BvButtonStyle.Tonal else BvButtonStyle.Filled,
                icon = if (following) painterResource(R.drawable.ic_check_24) else painterResource(R.drawable.ic_add_24),
                height = 36.dp,
                horizontalPadding = 16.dp,
            )
        }
    }
}

/**
 * 标题：点击展开简介，展开后里面有「视频详情」。不再点标题直接跳走（滑动时容易误触离开刷刷）。
 */
@Composable
private fun TabletTitle(
    item: ShortsItem,
    actions: ShortsActions,
    style: TextStyle,
    color: Color,
    secondary: Color,
    collapsedLines: Int,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(item.aid) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            item.title,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = BvType.Small,
                    color = secondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BvButton(
                text = stringResource(R.string.shorts_menu_detail),
                onClick = { actions.openVideo(item) },
                style = BvButtonStyle.Tonal,
                icon = painterResource(R.drawable.ic_info_24),
                height = 32.dp,
                horizontalPadding = 14.dp,
            )
        }
    }
}

/** 「1.2万 点赞 · 356 评论 · 今天 05:12」 */
@Composable
private fun ShortsItem.tabletMeta(): String = listOfNotNull(
    likeCount?.let { stringResource(R.string.shorts_like_count, shortsCount(it)) },
    replyCount?.let { stringResource(R.string.shorts_reply_count, shortsCount(it)) },
    pubdate?.let { shortsPubdate(it) },
).joinToString(stringResource(R.string.shorts_meta_separator))

/** 操作按钮行（原型 `.g-acts`）：点赞、投币、收藏、评论、分享、弹幕、设置、全屏；窄时自动换行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletActionRow(
    item: ShortsItem,
    ui: TabletUi,
    showComments: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = ui.vm.actionStateOf(item.aid)
    val danmaku = ui.menu.danmaku
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabletZoneAction(
            icon = painterResource(R.drawable.ic_thumb_up_24),
            description = stringResource(R.string.shorts_like),
            active = state.liked,
            onClick = { ui.actions.like(item) },
        )
        TabletZoneAction(
            icon = painterResource(R.drawable.ic_coin_24),
            description = stringResource(R.string.shorts_coin),
            active = state.coinCount > 0,
            onClick = { ui.actions.coin(item) },
        )
        TabletZoneAction(
            icon = painterResource(R.drawable.ic_star_24),
            description = stringResource(R.string.shorts_favorite),
            active = state.favoured,
            onClick = { ui.actions.favorite(item) },
        )
        if (showComments) {
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_chat_bubble_24),
                description = stringResource(R.string.shorts_comment),
                active = ui.commentsVisible,
                onClick = ui.onToggleComments,
            )
        }
        TabletZoneAction(
            icon = painterResource(R.drawable.ic_share_24),
            description = stringResource(R.string.shorts_share),
            active = false,
            onClick = { ui.actions.share(item) },
        )
        TabletDanmakuToggle(
            on = danmaku.enabled,
            onClick = { danmaku.toggle() },
            size = 40.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            glyphColor = tabletGlyphColor(danmaku.enabled),
        )
        TabletZoneAction(
            icon = painterResource(R.drawable.ic_tune_24),
            description = stringResource(R.string.shorts_menu),
            active = ui.settingsVisible,
            onClick = ui.onToggleSettings,
        )
        ui.onFullscreen?.let { onFullscreen ->
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_fullscreen_24),
                description = stringResource(R.string.shorts_tablet_fullscreen),
                active = false,
                onClick = onFullscreen,
            )
        }
    }
}

/** 区域里的操作按钮：40dp 圆形 surfaceContainerHighest，选中 primary + 圆角 13 */
@Composable
private fun TabletZoneAction(
    icon: Painter,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
) {
    BvIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        style = if (active) BvIconButtonStyle.Filled else BvIconButtonStyle.Tonal,
        size = size,
        shape = if (active) RoundedCornerShape(13.dp) else CircleShape,
    )
}

/** 右侧剩余宽度不到 300 时的竖排操作列（原型 `.blk.side`） */
@Composable
private fun TabletActionsColumn(item: ShortsItem, ui: TabletUi, modifier: Modifier = Modifier) {
    val state = ui.vm.actionStateOf(item.aid)
    val danmaku = ui.menu.danmaku
    Box(
        modifier.tabletWheel(PointerEventPass.Main, ui.go),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TabletAvatarFollow(item = item, ui = ui, size = 44.dp)
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_thumb_up_24),
                description = stringResource(R.string.shorts_like),
                active = state.liked,
                onClick = { ui.actions.like(item) },
                size = 44.dp,
            )
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_coin_24),
                description = stringResource(R.string.shorts_coin),
                active = state.coinCount > 0,
                onClick = { ui.actions.coin(item) },
                size = 44.dp,
            )
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_star_24),
                description = stringResource(R.string.shorts_favorite),
                active = state.favoured,
                onClick = { ui.actions.favorite(item) },
                size = 44.dp,
            )
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_chat_bubble_24),
                description = stringResource(R.string.shorts_comment),
                active = ui.commentsVisible,
                onClick = ui.onToggleComments,
                size = 44.dp,
            )
            TabletDanmakuToggle(
                on = danmaku.enabled,
                onClick = { danmaku.toggle() },
                size = 44.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                glyphColor = tabletGlyphColor(danmaku.enabled),
            )
            TabletZoneAction(
                icon = painterResource(R.drawable.ic_tune_24),
                description = stringResource(R.string.shorts_menu),
                active = ui.settingsVisible,
                onClick = ui.onToggleSettings,
                size = 44.dp,
            )
            ui.onFullscreen?.let { onFullscreen ->
                TabletZoneAction(
                    icon = painterResource(R.drawable.ic_fullscreen_24),
                    description = stringResource(R.string.shorts_tablet_fullscreen),
                    active = false,
                    onClick = onFullscreen,
                    size = 44.dp,
                )
            }
        }
    }
}

/**
 * 标签行 + 内容（占满剩下的高度）。只有一个标签时显示成标题；[onClose] 非空时右侧放关闭按钮（侧栏）。
 */
@Composable
private fun ColumnScope.TabletTabbed(
    item: ShortsItem,
    ui: TabletUi,
    tabs: List<Int>,
    onClose: (() -> Unit)? = null,
) {
    if (tabs.isEmpty()) return
    val selected = tabletEffectiveTab(tabs, ui.tab)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (tabs.size > 1) {
            BvTabRow(
                tabs = tabs.map { tabletTabLabel(it, item) },
                selected = tabs.indexOf(selected),
                onSelect = { index -> ui.onSelectTab(tabs[index]) },
                modifier = Modifier.weight(1f),
                horizontalPadding = 0.dp,
            )
        } else {
            Text(
                tabletTabLabel(tabs.first(), item),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                style = BvType.Section,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (onClose != null) {
            BvIconButton(
                icon = painterResource(R.drawable.ic_close_24),
                contentDescription = stringResource(R.string.shorts_tablet_close),
                onClick = onClose,
            )
        }
    }
    TabletTabContent(
        tab = selected,
        item = item,
        ui = ui,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    )
}

@Composable
private fun tabletTabLabel(tab: Int, item: ShortsItem): String = when (tab) {
    TabletTabComments -> item.replyCount?.let { stringResource(R.string.shorts_tab_comments, shortsCount(it)) }
        ?: stringResource(R.string.shorts_comment)

    TabletTabRelated -> stringResource(R.string.shorts_tab_related)
    else -> stringResource(R.string.shorts_tab_settings)
}

@Composable
private fun TabletTabContent(tab: Int, item: ShortsItem, ui: TabletUi, modifier: Modifier) {
    when (tab) {
        TabletTabComments -> ShortsCommentList(
            item = item,
            comments = ui.comments,
            modifier = modifier,
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            onOpenSpace = ui.actions::openSpace,
            onPictureClick = ui.onPictureClick,
        )

        TabletTabRelated -> TabletRelated(ui = ui, modifier = modifier)

        // 「设置」页就是播放菜单（菜单内容不自带滚动）
        else -> ShortsMenuContent(
            state = ui.menu,
            item = item,
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp),
        )
    }
}

/** 「相关」：刷刷队列里接下来的视频，点击跳到那一条 */
@Composable
private fun TabletRelated(ui: TabletUi, modifier: Modifier = Modifier) {
    val base = ui.currentPage + 1
    val upcoming = ui.items.drop(base).take(20)
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = upcoming,
            key = { index, entry -> safeLazyKey(entry.aid, index) },
            contentType = { _, _ -> "video" },
        ) { index, next ->
            VideoRow(
                data = next.toCardData(),
                onClick = { ui.goTo(base + index) },
            )
        }
    }
}

/**
 * 折痕下半屏的控制区（原型 `.blk.ctrl`，桌面模式 / 双屏竖握）：大号进度条、上一条 / 播放 / 下一条、操作按钮、评论入口。
 * 手不用去够立起来的那半屏；这片区域上下滑同样切条。打开评论 / 设置时整片换成侧栏内容。
 */
@Composable
private fun TabletControls(
    zone: ShortsZone,
    item: ShortsItem,
    ui: TabletUi,
    stage: TabletStage,
    panelTabs: List<Int>,
    panelOpen: Boolean,
) {
    val pool = ui.pool
    Box(
        Modifier
            .tabletPlace(zone.rect)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .tabletBlockPointer()
            .padding(stage.edgesOf(zone.rect))
    ) {
        if (panelOpen) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp),
            ) {
                TabletTabbed(item = item, ui = ui, tabs = panelTabs, onClose = ui.onClosePanel)
            }
        } else {
            val playing = pool.currentAid == item.aid && !pool.userPaused
            Column(
                Modifier
                    .fillMaxSize()
                    .tabletSwipePager(ui.go)
                    .tabletWheel(PointerEventPass.Main, ui.go)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShortsScrubber(
                    pool = pool,
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    expanded = true,
                    scrub = ui.scrub,
                    touchHeight = 48.dp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BvIconButton(
                        icon = painterResource(R.drawable.ic_keyboard_arrow_up_24),
                        contentDescription = stringResource(R.string.shorts_prev),
                        onClick = { ui.go(-1) },
                        style = BvIconButtonStyle.Tonal,
                        size = 52.dp,
                        iconSize = 28.dp,
                        enabled = ui.currentPage > 0,
                    )
                    BvIconButton(
                        icon = painterResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24),
                        contentDescription = stringResource(if (playing) R.string.shorts_tablet_pause else R.string.shorts_tablet_play),
                        onClick = { pool.togglePlay(item.aid) },
                        size = 72.dp,
                        iconSize = 36.dp,
                        shape = RoundedCornerShape(24.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    BvIconButton(
                        icon = painterResource(R.drawable.ic_keyboard_arrow_down_24),
                        contentDescription = stringResource(R.string.shorts_next),
                        onClick = { ui.go(1) },
                        style = BvIconButtonStyle.Tonal,
                        size = 52.dp,
                        iconSize = 28.dp,
                        enabled = ui.currentPage < ui.pageCount - 1,
                    )
                }
                TabletActionRow(item = item, ui = ui, showComments = false)
                BvButton(
                    text = tabletTabLabel(TabletTabComments, item),
                    onClick = ui.onToggleComments,
                    style = BvButtonStyle.Tonal,
                    icon = painterResource(R.drawable.ic_chat_bubble_24),
                )
            }
        }
    }
}

/** 没有固定位置放评论 / 设置时的右侧叠加面板 */
@Composable
private fun TabletOverlayPanel(
    item: ShortsItem,
    ui: TabletUi,
    tabs: List<Int>,
    width: Dp,
    edges: PaddingValues,
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .tabletBlockPointer()
            .padding(edges)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp),
    ) {
        TabletTabbed(item = item, ui = ui, tabs = tabs, onClose = ui.onClosePanel)
    }
}
