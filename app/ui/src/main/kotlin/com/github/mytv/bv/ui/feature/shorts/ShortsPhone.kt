package com.github.mytv.bv.ui.feature.shorts

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.OrientationEventListener
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.entity.ShortsDanmakuArea
import com.github.mytv.bv.entity.ShortsItem
import com.github.mytv.bv.ui.designsystem.component.Ambient
import com.github.mytv.bv.ui.designsystem.component.BvAvatar
import com.github.mytv.bv.ui.designsystem.component.BvChip
import com.github.mytv.bv.ui.designsystem.component.BvIconButton
import com.github.mytv.bv.ui.designsystem.component.CinemaScrim
import com.github.mytv.bv.ui.designsystem.component.safeLazyKey
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.designsystem.theme.CoverTheme
import com.github.mytv.bv.ui.designsystem.theme.LocalThemePrefs
import com.github.mytv.bv.ui.feature.player.findActivity
import com.github.mytv.bv.ui.feature.video.CommentPicturesSheetWindow
import com.github.mytv.bv.ui.navigation.LocalBottomBarPadding
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.viewmodel.ShortsViewModel
import dev.aaa1115910.biliapi.entity.Picture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val ActionWhite = Color.White.copy(alpha = .16f)

/** 评论面板占页面高度的比例；打开时当前条的画面缩到上方剩下的 38% 继续播放 */
private const val PhoneCommentsFraction = 0.62f

/** 进度条 40dp 触摸区的下沿 = 原型底部导航栏（80dp）的上沿；导航栏更高时整体上移 navExtra */
private val PhoneBarBottom = 80.dp

/** 底部信息的下沿：进度条触摸区（40dp）上方 */
private val PhoneInfoBottom = 124.dp

/** 暂停时进度条上方多出一行「01:12 / 02:40」，底部信息跟着上移，避免压住标签行 */
private val PhoneInfoLift = 18.dp

/** 右侧操作列的下沿 */
private val PhoneActionsBottom = 150.dp
private val PhoneActionSpacing = 12.dp

/** 操作列高度：头像 + 关注 60，5 个按钮各 46 + 3 + 14（标签行高）。弹幕「少」只铺到它的上沿 */
private val PhoneActionColumnHeight = 60.dp + (63.dp + PhoneActionSpacing) * 5

/** 竖屏视频的弹幕层：从顶栏下沿（状态栏 + 52）到底部信息上沿（底部往上 200） */
private val PhoneDanmakuTop = 52.dp
private val PhoneDanmakuBottom = 200.dp

/** 全屏控制层：播放中进入全屏、切条、单击后停留的时间 */
private const val PhoneControlsMs = 3000L

/** 「UP 主页改为点头像」提示停留的时间 */
private const val PhoneSwipeHintMs = 2500L

private val PhoneSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val PhoneSidePanelShape = RoundedCornerShape(28.dp)
private val PhoneFsTopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = .67f), Color.Transparent))
private val PhoneFsBottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .73f)))

/** 横屏全屏里从右侧滑出的面板（360dp，外壳同主播放器的侧边面板） */
private enum class PhoneSidePanel { None, Comments, Menu }

/**
 * 手机刷刷（设计稿「手机」）：全屏 VerticalPager，每页包在 CoverTheme(cover, forceDark = true) 里。
 *
 * - 单击暂停（暂停时中间播放键、进度条展开），双击点赞，长按 2×，画面任意位置横滑拖进度；左滑不再进 UP 主页。
 * - 底部导航栏由外壳叠加（黑 α0.72），这里不让位；导航栏比原型（80dp）高时，底部元素整体上移差值。
 * - 评论是页面内的 62% 面板，当前条的画面缩到上方继续播放；菜单是底部面板（[ShortsMenuContent]）。
 * - 弹幕整页只有一个层（[ShortsDanmakuLayer]），放在 Pager 外面，跟着当前条摆到画面所在的位置。
 * - 横屏视频画面下方有「全屏观看」；手机横过来也自动进全屏（[PhoneFullscreen]），竖回来退出。
 */
@Composable
internal fun ShortsPhone(
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
    val scope = rememberCoroutineScope()
    val scrub = remember { ShortsScrub() }
    val reduceMotion = LocalThemePrefs.current.reduceMotion
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomBar = LocalBottomBarPadding.current
    val navExtra = (bottomBar - 80.dp).coerceAtLeast(0.dp)

    var commentsOpen by remember { mutableStateOf(false) }
    // 关闭动画期间面板还要显示这一条，所以和开关分开记
    var commentItem by remember { mutableStateOf<ShortsItem?>(null) }
    var menuItem by remember { mutableStateOf<ShortsItem?>(null) }

    // 全屏：转手机横过来自动进入（老路径），或者点「全屏观看」强制横屏进入
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fullscreenDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) { if (!landscape) fullscreenDismissed = false }
    val orientation = rememberPhoneOrientationLock()
    val current = items.getOrNull(pagerState.currentPage)
    val fullscreen = landscape && !fullscreenDismissed && current != null
    val enterFullscreen: () -> Unit = {
        fullscreenDismissed = false
        orientation.enterLandscape()
    }
    val exitFullscreen: (Boolean) -> Unit = { navigating ->
        fullscreenDismissed = true
        orientation.exit(lockPortrait = !navigating)
    }
    // 进全屏时收起竖屏的面板：菜单是独立窗口，会叠在全屏对话框上面；
    // 评论开着的话全屏里切条后，回到竖屏时面板还停在上一条。全屏里另有右侧的评论和菜单
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            menuItem = null
            commentsOpen = false
        }
    }

    // 「播完后：自动下一条」：翻到下一条；最后一条或评论开着（翻页锁住）时返回 false，由池从头循环
    DisposableEffect(pool, pagerState) {
        pool.onCurrentEnded = {
            val index = items.indexOfFirst { it.aid == pool.currentAid }
            val next = (if (index >= 0) index else pagerState.currentPage) + 1
            if (!commentsOpen && next < items.size) {
                scope.launch { pagerState.animateScrollToPage(next) }
                true
            } else {
                false
            }
        }
        onDispose { pool.onCurrentEnded = null }
    }

    // 老版本左滑进 UP 主页：第一次向左横滑时提示一次
    var swipeHintPending by remember {
        mutableStateOf(!runCatching { Prefs.shortsSwipeHintShown }.getOrDefault(true))
    }
    var swipeHintVisible by remember { mutableStateOf(false) }
    val onLeftSwipe: () -> Unit = remember {
        {
            if (swipeHintPending) {
                swipeHintPending = false
                swipeHintVisible = true
                runCatching { Prefs.shortsSwipeHintShown = true }
            }
        }
    }
    LaunchedEffect(swipeHintVisible) {
        if (swipeHintVisible) {
            delay(PhoneSwipeHintMs)
            swipeHintVisible = false
        }
    }

    BackHandler(enabled = commentsOpen && !fullscreen) { commentsOpen = false }

    val scrubbing by remember { derivedStateOf { scrub.active } }
    val spatialOffset = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            // 评论打开时锁住翻页
            userScrollEnabled = !commentsOpen,
            key = { page -> safeLazyKey(items.getOrNull(page)?.aid ?: 0L, page) },
        ) { page ->
            val item = items.getOrNull(page) ?: return@VerticalPager
            val isCurrentPage = page == pagerState.currentPage
            CoverTheme(item.cover, forceDark = true) {
                PhonePage(
                    item = item,
                    vm = vm,
                    pool = pool,
                    actions = actions,
                    menu = menu,
                    scrub = scrub,
                    statusTop = statusTop,
                    navExtra = navExtra,
                    reduceMotion = reduceMotion,
                    isCurrentPage = isCurrentPage,
                    commentsOpen = commentsOpen && isCurrentPage,
                    // 全屏对话框里另有一个画面：页面里的先撤掉，免得两个 TextureView 抢同一个播放器
                    showVideo = !fullscreen,
                    swipeHint = swipeHintPending,
                    onLeftSwipe = onLeftSwipe,
                    onComments = {
                        commentItem = item
                        commentsOpen = true
                    },
                    onCloseComments = { commentsOpen = false },
                    onMenu = { menuItem = item },
                    onFullscreen = enterFullscreen,
                )
            }
        }

        // 全屏时由全屏界面自己放弹幕层；这里不组合，DanmakuView 随之释放
        if (!fullscreen) {
            PhoneDanmaku(
                danmaku = menu.danmaku,
                pool = pool,
                item = current,
                statusTop = statusTop,
                navExtra = navExtra,
                hidden = pagerState.isScrollInProgress || scrubbing || commentsOpen,
            )
        }

        SpeedBoostPill(
            visible = pool.boosting,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusTop + 52.dp),
        )

        AnimatedVisibility(
            visible = swipeHintVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 236.dp + navExtra),
            enter = fadeIn(fastEffects),
            exit = fadeOut(fastEffects),
            label = "shortsPhoneSwipeHint",
        ) {
            Text(
                stringResource(R.string.shorts_phone_swipe_hint),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = .7f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = BvType.Label,
                color = Color.White,
            )
        }

        // 评论：页面内的 62% 面板（不是独立窗口），从底部滑入
        AnimatedVisibility(
            visible = commentsOpen,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(PhoneCommentsFraction),
            enter = if (reduceMotion) fadeIn(fastEffects) else slideInVertically(spatialOffset) { it },
            exit = if (reduceMotion) fadeOut(fastEffects) else slideOutVertically(spatialOffset) { it },
            label = "shortsPhoneComments",
        ) {
            val item = commentItem
            if (item != null) {
                CoverTheme(item.cover, forceDark = true) {
                    PhoneCommentPanel(
                        item = item,
                        comments = comments,
                        onPictureClick = onPictureClick,
                        onOpenSpace = { mid ->
                            commentsOpen = false
                            actions.openSpace(mid)
                        },
                        onDismiss = { commentsOpen = false },
                        // 外壳的底部导航栏叠在页面上（没有让页面临时隐藏它的机制），列表底部留出它的高度
                        listBottomPadding = bottomBar + 16.dp,
                        dragToDismiss = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(PhoneSheetShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
    }

    // 菜单：底部面板，分享、稍后再看、视频详情都在菜单的动作行里
    menuItem?.let { item ->
        CoverTheme(item.cover, forceDark = true) {
            ModalBottomSheet(
                onDismissRequest = { menuItem = null },
                sheetState = rememberModalBottomSheetState(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ShortsMenuContent(
                    state = menu,
                    item = item,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    // 分享、视频详情会离开当前界面：面板直接移除，不留在新页面上面
                    onAction = { menuItem = null },
                )
            }
        }
    }

    if (fullscreen && current != null) {
        PhoneFullscreen(
            item = current,
            items = items,
            pagerState = pagerState,
            vm = vm,
            pool = pool,
            comments = comments,
            actions = actions,
            menu = menu,
            scrub = scrub,
            reduceMotion = reduceMotion,
            onPictureClick = onPictureClick,
            onExit = exitFullscreen,
        )
    }
}

@Composable
private fun PhonePage(
    item: ShortsItem,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    actions: ShortsActions,
    menu: ShortsMenuState,
    scrub: ShortsScrub,
    statusTop: Dp,
    navExtra: Dp,
    reduceMotion: Boolean,
    isCurrentPage: Boolean,
    commentsOpen: Boolean,
    showVideo: Boolean,
    swipeHint: Boolean,
    onLeftSwipe: () -> Unit,
    onComments: () -> Unit,
    onCloseComments: () -> Unit,
    onMenu: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val hearts = remember { HeartPops() }
    val portrait = pool.phonePortrait(item)
    val paused = pool.currentAid == item.aid && pool.userPaused
    // 拖动中 targetMs 每帧都变，只在开始 / 结束时重组页面
    val scrubbing by remember(item.aid) { derivedStateOf { scrub.activeFor(item) } }
    val commentsSpec: FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else MaterialTheme.motionScheme.defaultSpatialSpec()
    val commentsProgress by animateFloatAsState(
        if (commentsOpen) 1f else 0f,
        commentsSpec,
        label = "shortsPhoneCommentsProgress",
    )
    var expanded by remember(item.aid) { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) expanded = false }

    val gestures = if (commentsOpen) {
        // 评论打开时上方画面只负责关闭评论
        Modifier.pointerInput(Unit) { detectTapGestures { onCloseComments() } }
    } else {
        Modifier
            .leftSwipeProbe(swipeHint, onLeftSwipe)
            .shortsGestures(item, pool, actions, hearts, reduceMotion)
            .shortsSeekGesture(item, pool, scrub)
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(gestures)
    ) {
        // 横屏视频、评论打开后完整显示的竖屏视频：两侧用氛围光，不留黑边
        if (!portrait || commentsOpen) Ambient(item.cover)
        Box(Modifier.phoneFrame(portrait) { commentsProgress }) {
            if (showVideo) {
                ShortsVideo(pool, item, Modifier.fillMaxSize(), crop = portrait && !commentsOpen)
            }
            ShortsPausedIndicator(
                visible = paused && !scrubbing,
                modifier = Modifier.align(Alignment.Center),
            )
            ShortsScrubOverlay(scrub, pool, item, Modifier.align(Alignment.Center))
        }
        CinemaScrim()

        AnimatedVisibility(
            visible = !commentsOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            label = "shortsPhoneOverlay",
        ) {
            PhoneOverlay(
                item = item,
                vm = vm,
                pool = pool,
                actions = actions,
                menu = menu,
                scrub = scrub,
                statusTop = statusTop,
                navExtra = navExtra,
                portrait = portrait,
                paused = paused,
                scrubbing = scrubbing,
                expanded = expanded,
                onToggleExpanded = { expanded = !expanded },
                onComments = onComments,
                onMenu = onMenu,
                onFullscreen = onFullscreen,
            )
        }
        HeartLayer(hearts)
    }
}

/** 画面上的顶栏、操作列、底部信息、进度条和「全屏观看」；评论打开时整体隐藏 */
@Composable
private fun PhoneOverlay(
    item: ShortsItem,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    actions: ShortsActions,
    menu: ShortsMenuState,
    scrub: ShortsScrub,
    statusTop: Dp,
    navExtra: Dp,
    portrait: Boolean,
    paused: Boolean,
    scrubbing: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onComments: () -> Unit,
    onMenu: () -> Unit,
    onFullscreen: () -> Unit,
) {
    // 拖进度时（进度条或画面横滑）顶栏、操作列和底部信息淡出，进度条留着
    val fade by animateFloatAsState(
        if (scrubbing) 0f else 1f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shortsPhoneOverlayFade",
    )
    val faded = Modifier.graphicsLayer { alpha = fade }
    val lift by animateDpAsState(
        if (paused && !scrubbing) PhoneInfoLift else 0.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "shortsPhoneInfoLift",
    )
    Box(Modifier.fillMaxSize()) {
        // 顶栏：标题 + 「弹」开关（长按开菜单）+ 搜索
        Row(
            Modifier
                .fillMaxWidth()
                .then(faded)
                .padding(top = statusTop + 2.dp, start = 16.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.shorts_title), style = BvType.PanelTitle, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneDanmakuToggle(
                    on = menu.danmaku.enabled,
                    onToggle = { menu.danmaku.toggle() },
                    onLongPress = onMenu,
                )
                BvIconButton(
                    icon = painterResource(R.drawable.ic_search_24),
                    contentDescription = stringResource(R.string.shorts_search),
                    onClick = actions::openSearch,
                    contentColor = Color.White,
                )
            }
        }

        PhoneActionColumn(
            item = item,
            vm = vm,
            actions = actions,
            onComments = onComments,
            onMenu = onMenu,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = PhoneActionsBottom + navExtra)
                .then(faded),
        )

        // 底部信息：@UP 名进主页；点标题展开 / 收起简介（不再直接进视频详情，滑动时容易误触离开刷刷）
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 76.dp, bottom = PhoneInfoBottom + navExtra)
                .offset { IntOffset(0, -lift.roundToPx()) }
                .then(faded)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.shorts_at_up, item.author.name),
                modifier = Modifier.clickable { actions.openSpace(item.author.mid) },
                style = BvType.Section,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.title,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(if (expanded) R.string.ds_collapse else R.string.ds_expand),
                    onClick = onToggleExpanded,
                ),
                style = BvType.Body.copy(lineHeight = 20.3.sp),
                color = Color.White,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                PhoneDescription(item = item, onDetail = { actions.openVideo(item) })
            }
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

        // 40dp 触摸区，放在底部信息下方、底部导航栏上方；暂停时展开成 6dp + 把手 + 时间
        ShortsScrubber(
            pool = pool,
            item = item,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = PhoneBarBottom + navExtra),
            expanded = paused || scrubbing,
            scrub = scrub,
            showTime = paused && !scrubbing,
        )

        if (!portrait) {
            PhoneFullscreenChip(
                onClick = onFullscreen,
                modifier = Modifier
                    .belowLandscapeFrame()
                    .then(faded),
            )
        }
    }
}

/** 展开的简介：描述 + 发布时间 / 播放量 +「视频详情」 */
@Composable
private fun PhoneDescription(item: ShortsItem, onDetail: () -> Unit) {
    Column(
        Modifier
            .background(Color.Black.copy(alpha = .4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val description = item.description?.trim().orEmpty()
        if (description.isNotEmpty()) {
            Text(
                description,
                style = BvType.Label.copy(fontWeight = FontWeight.Normal, lineHeight = 18.sp),
                color = Color.White.copy(alpha = .85f),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = listOfNotNull(
            item.pubdate?.takeIf { it > 0 }?.let { shortsPubdate(it) },
            item.viewCount?.let { stringResource(R.string.shorts_phone_views, shortsCount(it)) },
        )
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(stringResource(R.string.shorts_meta_separator)),
                style = BvType.Meta,
                color = Color.White.copy(alpha = .7f),
            )
        }
        Row(
            Modifier.clickable(onClick = onDetail),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.shorts_menu_detail),
                style = BvType.Label.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                painterResource(R.drawable.ic_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 横屏视频画面下方的「全屏观看」 */
@Composable
private fun PhoneFullscreenChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = .27f))
            .border(1.dp, Color.White.copy(alpha = .53f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_fullscreen_24),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.shorts_phone_fullscreen),
            style = BvType.Label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

/** 「弹」开关：单击开关弹幕，长按打开菜单（弹幕设置在菜单第一节） */
@Composable
private fun PhoneDanmakuToggle(
    on: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // content-desc 直接带状态，uiautomator 能读出开关
    val label = stringResource(if (on) R.string.shorts_danmaku_on else R.string.shorts_danmaku_off)
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShortsDanmakuGlyph(on = on)
    }
}

/**
 * 右侧操作列（原型 `.m-acts`）：头像 46（下方「+」关注）+ 点赞 / 投币 / 收藏 / 评论 / 菜单。
 * 关注和主页分开：点头像进主页，点「+」关注；分享移进菜单。
 */
@Composable
private fun PhoneActionColumn(
    item: ShortsItem,
    vm: ShortsViewModel,
    actions: ShortsActions,
    onComments: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = vm.actionStateOf(item.aid)
    val mid = item.author.mid
    val canFollow = mid > 0 && vm.isFollowing(mid) != true
    val upPage = stringResource(R.string.shorts_up_page)
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(PhoneActionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 46.dp, height = 60.dp)) {
            BvAvatar(
                url = item.author.face,
                size = 46.dp,
                fallbackText = item.author.name,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { actions.openSpace(mid) }
                    .semantics { contentDescription = upPage },
            )
            PhoneFollowBadge(
                visible = canFollow,
                onClick = { actions.follow(item) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        SideActionButton(
            icon = painterResource(R.drawable.ic_thumb_up_24),
            label = countOrLabel(item.likeCount, R.string.shorts_like),
            contentDescription = stringResource(R.string.shorts_like),
            active = state.liked,
            onClick = { actions.like(item) },
        )
        SideActionButton(
            icon = painterResource(R.drawable.ic_coin_24),
            label = stringResource(R.string.shorts_coin),
            contentDescription = stringResource(R.string.shorts_coin),
            active = state.coinCount > 0,
            onClick = { actions.coin(item) },
        )
        SideActionButton(
            icon = painterResource(R.drawable.ic_star_24),
            label = countOrLabel(item.favoriteCount, R.string.shorts_favorite),
            contentDescription = stringResource(R.string.shorts_favorite),
            active = state.favoured,
            onClick = { actions.favorite(item) },
        )
        SideActionButton(
            icon = painterResource(R.drawable.ic_chat_bubble_24),
            label = countOrLabel(item.replyCount, R.string.shorts_comment),
            contentDescription = stringResource(R.string.shorts_comment),
            active = false,
            onClick = onComments,
        )
        SideActionButton(
            icon = painterResource(R.drawable.ic_tune_24),
            label = stringResource(R.string.shorts_tab_settings),
            contentDescription = stringResource(R.string.shorts_menu),
            active = false,
            onClick = onMenu,
        )
    }
}

/**
 * 头像下的「+」关注（视觉 22dp 圆钮，触摸区放大到 30dp），关注成功后缩小消失。
 * 单独成函数：直接写在操作列的 Column 里，AnimatedVisibility 会解析成 ColumnScope 的版本而编译不过。
 */
@Composable
private fun PhoneFollowBadge(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(),
        label = "shortsPhoneFollow",
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_add_24),
                    contentDescription = stringResource(R.string.shorts_follow),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 原型 `.m-acts .b`：46dp（全屏右侧 40dp），白 α0.16 + 背景模糊（API ≥ 31），图标白；
 * 选中时 primary / onPrimary，圆形 → 圆角方形（fastSpatialSpec，减少动效时直接跳到终态）。
 */
@Composable
private fun SideActionButton(
    icon: Painter,
    label: String,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    size: Dp = 46.dp,
) {
    val reduceMotion = LocalThemePrefs.current.reduceMotion
    val cornerSpec: FiniteAnimationSpec<Dp> =
        if (reduceMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec()
    val corner by animateDpAsState(if (active) size * 0.35f else size / 2, cornerSpec, label = "shortsActionCorner")
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else ActionWhite,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shortsActionContainer",
    )
    val content by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shortsActionContent",
    )
    val density = LocalDensity.current
    val labelStyle = remember(density) {
        TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(
                color = Color.Black.copy(alpha = .6f),
                offset = Offset(0f, with(density) { 1.dp.toPx() }),
                blurRadius = with(density) { 3.dp.toPx() },
            ),
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(size)
                // 圆角动画在 Draw 阶段读取，避免每帧重组按钮内容
                .graphicsLayer {
                    shape = RoundedCornerShape(corner)
                    clip = true
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !active) Modifier.blur(6.dp) else Modifier)
                    .background(container)
            )
            Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(24.dp))
        }
        Text(label, style = labelStyle, color = Color.White, maxLines = 1)
    }
}

/**
 * 单击暂停 / 继续，双击点赞 + 爱心，长按 2 倍速（松手回到菜单里选的倍速）。
 * [onTap] 在单击后额外调用（全屏用它唤出控制层）。
 */
internal fun Modifier.shortsGestures(
    item: ShortsItem,
    pool: ShortsPlayerPool,
    actions: ShortsActions,
    hearts: HeartPops,
    reduceMotion: Boolean,
    onTap: (() -> Unit)? = null,
): Modifier = pointerInput(item.aid, reduceMotion) {
    detectTapGestures(
        onPress = {
            tryAwaitRelease()
            pool.setBoost(false)
        },
        onDoubleTap = { offset ->
            actions.likeOnly(item)
            if (!reduceMotion) hearts.add(offset)
        },
        onLongPress = {
            if (pool.currentAid == item.aid) pool.setBoost(true)
        },
        onTap = {
            pool.togglePlay(item.aid)
            onTap?.invoke()
        },
    )
}

/** 只看不吃：第一次向左横滑时提示「UP 主页改为点头像」（老版本左滑进主页） */
private fun Modifier.leftSwipeProbe(enabled: Boolean, onLeftSwipe: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val slop = 10.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // 用绝对位置：拖进度锁定后会消费事件，positionChange() 读不到位移
                val d = change.position - down.position
                if (abs(d.x) > slop && abs(d.x) > abs(d.y) * 1.2f) {
                    if (d.x < 0) onLeftSwipe()
                    break
                }
                if (abs(d.y) > slop) break
            }
        }
    }

/**
 * 页面里的画面框。竖屏视频铺满；横屏视频全宽 16:9，垂直居中再上移自身高度的 10%（下方给「全屏观看」）。
 * 评论打开（[progress] 0 → 1）时画面区域缩到上方 38%，横屏视频在其中居中。
 * 进度只在 Layout 阶段读取，动画过程中不重组。
 */
private fun Modifier.phoneFrame(portrait: Boolean, progress: () -> Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val p = progress()
    val area = (height * (1f - PhoneCommentsFraction * p)).roundToInt().coerceAtLeast(1)
    if (portrait) {
        val placeable = measurable.measure(Constraints.fixed(width, area))
        layout(width, height) { placeable.place(0, 0) }
    } else {
        val frameHeight = min(width * 9f / 16f, area.toFloat())
        val frameWidth = min(width.toFloat(), frameHeight * 16f / 9f)
        val placeable = measurable.measure(
            Constraints.fixed(
                frameWidth.roundToInt().coerceAtLeast(1),
                frameHeight.roundToInt().coerceAtLeast(1),
            )
        )
        val top = (area - frameHeight) / 2f - frameHeight * 0.1f * (1f - p)
        layout(width, height) { placeable.place((width - placeable.width) / 2, top.roundToInt()) }
    }
}

/** 「全屏观看」放在横屏视频画面下方 12dp、水平居中（与 [phoneFrame] 评论关闭时的画面位置一致） */
private fun Modifier.belowLandscapeFrame(): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val frameHeight = width * 9f / 16f
    val top = (height - frameHeight) / 2f - frameHeight * 0.1f + frameHeight + 12.dp.toPx()
    layout(width, height) { placeable.place((width - placeable.width) / 2, top.roundToInt()) }
}

/** 完整显示时的画面矩形（同 [ShortsVideo] crop = false 的算法）：全屏里的弹幕层铺在这里 */
private fun Modifier.phoneFittedVideo(pool: ShortsPlayerPool, item: ShortsItem): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val slot = pool.slotFor(item.aid)
    val vw = slot?.videoWidth?.takeIf { it > 0 } ?: item.width
    val vh = slot?.videoHeight?.takeIf { it > 0 } ?: item.height
    val ratio = if (vw > 0 && vh > 0) vw.toFloat() / vh else if (item.isPortrait) 9f / 16f else 16f / 9f
    val fitWidth = height <= 0 || ratio > width.toFloat() / height
    val w = (if (fitWidth) width.toFloat() else height * ratio).roundToInt().coerceAtLeast(1)
    val h = (if (fitWidth) width / ratio else height.toFloat()).roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
}

/** 竖屏视频：优先用播放器上报的宽高判断，元数据兜底 */
private fun ShortsPlayerPool.phonePortrait(item: ShortsItem): Boolean {
    val slot = slotFor(item.aid)
    val w = slot?.videoWidth?.takeIf { it > 0 } ?: item.width
    val h = slot?.videoHeight?.takeIf { it > 0 } ?: item.height
    return if (w > 0 && h > 0) h > w else item.isPortrait
}

/**
 * 整页唯一的弹幕层，摆到当前条画面所在的位置（不出视频框、不从按钮后面穿过）：
 * - 竖屏视频：顶栏下沿到底部信息上沿；「少」只到右侧操作列上沿，「半屏」到屏幕中线，「全屏」铺满这一层；
 * - 横屏视频：16:9 画面框（与 [phoneFrame] 相同的位置），区域比例直接用设置值。
 */
@Composable
private fun PhoneDanmaku(
    danmaku: ShortsDanmaku,
    pool: ShortsPlayerPool,
    item: ShortsItem?,
    statusTop: Dp,
    navExtra: Dp,
    hidden: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val portrait = item == null || pool.phonePortrait(item)
        val top: Dp
        val layerHeight: Dp
        val fraction: Float
        if (portrait) {
            top = statusTop + PhoneDanmakuTop
            layerHeight = (screenHeight - PhoneDanmakuBottom - navExtra - top).coerceAtLeast(1.dp)
            fraction = when (danmaku.area) {
                ShortsDanmakuArea.Few ->
                    (screenHeight - PhoneActionsBottom - navExtra - PhoneActionColumnHeight - top) / layerHeight
                ShortsDanmakuArea.Half -> (screenHeight / 2 - top) / layerHeight
                ShortsDanmakuArea.Full -> 1f
            }.coerceIn(0.15f, 1f)
        } else {
            layerHeight = maxWidth * 9f / 16f
            top = (screenHeight - layerHeight) / 2 - layerHeight * 0.1f
            fraction = danmaku.area.fraction
        }
        ShortsDanmakuLayer(
            danmaku = danmaku,
            pool = pool,
            item = item,
            areaFraction = fraction,
            modifier = Modifier
                .offset(y = top)
                .fillMaxWidth()
                .height(layerHeight),
            hidden = hidden,
        )
    }
}

/**
 * 评论面板内容（手机竖屏的 62% 页面内面板、全屏右侧面板共用）。
 *
 * 面板打开期间评论图片预览改到全屏对话框窗口显示（[CommentPicturesSheetWindow]）：
 * 页面内的预览层画在 NavHost 里，会被外壳叠加的底部导航栏压住，在全屏对话框里则完全看不到；
 * 而且预览层的返回处理比本页注册得早，留在页面里时返回键会先关评论、后关预览。
 */
@Composable
private fun PhoneCommentPanel(
    item: ShortsItem,
    comments: ShortsComments,
    onPictureClick: (List<Picture>, Int) -> Unit,
    onOpenSpace: (Long) -> Unit,
    onDismiss: () -> Unit,
    listBottomPadding: Dp,
    dragToDismiss: Boolean,
    modifier: Modifier = Modifier,
) {
    CommentPicturesSheetWindow()
    LaunchedEffect(item.aid) { comments.load(item.aid) }
    val scope = rememberCoroutineScope()
    var dragPx by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .offset { IntOffset(0, dragPx.roundToInt()) }
            .then(modifier)
            // 面板内空白处的点击不落到下面的画面（那会关闭评论）
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val header = if (dragToDismiss) {
            // 拖动条和标题向下拖超过 96dp 关闭，否则弹回
            Modifier.pointerInput(Unit) {
                val threshold = 96.dp.toPx()
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragPx > threshold) {
                            onDismiss()
                        } else {
                            scope.launch { Animatable(dragPx).animateTo(0f) { dragPx = value } }
                        }
                    },
                    onDragCancel = { dragPx = 0f },
                ) { change, amount ->
                    change.consume()
                    dragPx = (dragPx + amount).coerceAtLeast(0f)
                }
            }
        } else {
            Modifier
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(header)
        ) {
            if (dragToDismiss) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 2.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = .6f), CircleShape)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val count = item.replyCount
                Text(
                    if (count != null) stringResource(R.string.shorts_tab_comments, shortsCount(count))
                    else stringResource(R.string.shorts_comment),
                    modifier = Modifier.weight(1f),
                    style = BvType.Section,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BvIconButton(
                    icon = painterResource(R.drawable.ic_close_24),
                    contentDescription = stringResource(R.string.shorts_phone_close),
                    onClick = onDismiss,
                )
            }
        }
        ShortsCommentList(
            item = item,
            comments = comments,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = listBottomPadding),
            onOpenSpace = onOpenSpace,
            onPictureClick = onPictureClick,
        )
    }
}

// ---------------------------------------------------------------- 全屏

/**
 * 「全屏观看」的方向控制：进入时请求 SENSOR_LANDSCAPE（锁定方向也能进），退出时恢复进入前的设置。
 * 手机还横着就退出时先锁竖屏，等手机真正竖回来再恢复，否则会立刻按老路径又自动进全屏（做法同视频详情页）。
 */
@Stable
private class PhoneOrientationLock(private val activity: Activity?) {
    /** 改动 requestedOrientation 之前的值；null 表示没改过 */
    private var restoreTo by mutableStateOf<Int?>(null)

    /** 退出全屏后暂时锁着竖屏，等手机竖回来 */
    var portraitLocked by mutableStateOf(false)
        private set

    fun enterLandscape() {
        val host = activity ?: return
        if (restoreTo == null) restoreTo = host.requestedOrientation
        portraitLocked = false
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** @param lockPortrait 手机还横着时先锁竖屏；跳到别的页面前传 false，直接恢复原设置 */
    fun exit(lockPortrait: Boolean) {
        val host = activity ?: return
        val landscapeNow = host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (lockPortrait && landscapeNow) {
            if (restoreTo == null) restoreTo = host.requestedOrientation
            portraitLocked = true
            host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            restore()
        }
    }

    /** 手机竖回来了，或离开页面：恢复原来的方向设置 */
    fun restore() {
        val original = restoreTo
        restoreTo = null
        portraitLocked = false
        if (original != null) activity?.requestedOrientation = original
    }
}

@Composable
private fun rememberPhoneOrientationLock(): PhoneOrientationLock {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val lock = remember(activity) { PhoneOrientationLock(activity) }
    val portraitLocked = lock.portraitLocked
    DisposableEffect(lock, portraitLocked) {
        if (!portraitLocked) return@DisposableEffect onDispose { }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                if (orientation <= 20 || orientation >= 340) {
                    view.post { if (lock.portraitLocked) lock.restore() }
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        } else {
            lock.restore()
        }
        onDispose { listener.disable() }
    }
    // 离开刷刷（比如从全屏评论进了 UP 主页）时不把强制横屏 / 竖屏带到别的页面
    DisposableEffect(lock) {
        onDispose { lock.restore() }
    }
    return lock
}

/**
 * 横屏全屏：全屏 Dialog 盖住外壳的底部导航栏并隐藏系统栏。
 *
 * - 手势和竖屏一样：单击暂停、双击点赞、长按 2×、横滑拖进度，上下滑切条（切的是主界面的 pagerState）。
 * - 控制层（返回 + 标题 +「弹」、右侧互动、底部进度与倍速 / 画质）暂停时常驻；播放中进入全屏、切条或单击后显示 3 秒。
 * - 下一条是竖屏视频时完整显示在中间，两侧氛围光。评论、菜单从右侧滑出 360dp 面板。
 * - 返回键：先关面板，再退出全屏。
 */
@Composable
private fun PhoneFullscreen(
    item: ShortsItem,
    items: List<ShortsItem>,
    pagerState: PagerState,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    comments: ShortsComments,
    actions: ShortsActions,
    menu: ShortsMenuState,
    scrub: ShortsScrub,
    reduceMotion: Boolean,
    onPictureClick: (List<Picture>, Int) -> Unit,
    onExit: (navigating: Boolean) -> Unit,
) {
    var panel by remember { mutableStateOf(PhoneSidePanel.None) }
    // 面板关闭动画期间还要显示原来的内容，所以单独记最后打开的是哪个
    var lastPanel by remember { mutableStateOf(PhoneSidePanel.Menu) }
    Dialog(
        onDismissRequest = {
            if (panel != PhoneSidePanel.None) panel = PhoneSidePanel.None else onExit(false)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        CoverTheme(item.cover, forceDark = true) {
            PhoneFullscreenContent(
                item = item,
                items = items,
                pagerState = pagerState,
                vm = vm,
                pool = pool,
                comments = comments,
                actions = actions,
                menu = menu,
                scrub = scrub,
                reduceMotion = reduceMotion,
                panel = panel,
                shownPanel = lastPanel,
                onPanel = { next ->
                    if (next != PhoneSidePanel.None) lastPanel = next
                    panel = next
                },
                onPictureClick = onPictureClick,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun PhoneFullscreenContent(
    item: ShortsItem,
    items: List<ShortsItem>,
    pagerState: PagerState,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    comments: ShortsComments,
    actions: ShortsActions,
    menu: ShortsMenuState,
    scrub: ShortsScrub,
    reduceMotion: Boolean,
    panel: PhoneSidePanel,
    shownPanel: PhoneSidePanel,
    onPanel: (PhoneSidePanel) -> Unit,
    onPictureClick: (List<Picture>, Int) -> Unit,
    onExit: (navigating: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hearts = remember(item.aid) { HeartPops() }
    var dragY by remember { mutableFloatStateOf(0f) }
    val swiping by remember { derivedStateOf { dragY != 0f } }
    val scrubbing by remember(item.aid) { derivedStateOf { scrub.activeFor(item) } }
    val paused = pool.currentAid == item.aid && pool.userPaused
    val portrait = pool.phonePortrait(item)

    // 控制层：进入全屏、切条、单击后显示 3 秒；暂停、拖进度、面板打开时常驻
    var poke by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf(true) }
    LaunchedEffect(poke, item.aid) {
        recent = true
        delay(PhoneControlsMs)
        recent = false
    }
    val showControls = recent || paused || scrubbing || panel != PhoneSidePanel.None

    val switchBy: (Int) -> Unit = { direction ->
        val target = pagerState.currentPage + direction
        if (target in items.indices) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 手势顺序：横滑拖进度最先看到事件（锁定后吃掉），其次上下滑切条，最后单击 / 双击 / 长按
        Box(
            Modifier
                .fillMaxSize()
                .shortsGestures(item, pool, actions, hearts, reduceMotion, onTap = { poke++ })
                .phoneFullscreenSwipe(onDrag = { dragY = it }, onSwipe = switchBy)
                .shortsSeekGesture(item, pool, scrub)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = dragY * 0.4f }
            ) {
                // 竖屏视频横着看：画面居中完整显示，两侧氛围光
                if (portrait) Ambient(item.cover)
                key(item.aid) {
                    ShortsVideo(pool, item, Modifier.fillMaxSize(), crop = false)
                }
            }
            HeartLayer(hearts)
        }

        ShortsDanmakuLayer(
            danmaku = menu.danmaku,
            pool = pool,
            item = item,
            areaFraction = menu.danmaku.area.fraction,
            modifier = Modifier.phoneFittedVideo(pool, item),
            hidden = scrubbing || swiping || pagerState.isScrollInProgress,
        )
        ShortsPausedIndicator(visible = paused && !scrubbing, modifier = Modifier.align(Alignment.Center))
        ShortsScrubOverlay(scrub, pool, item, Modifier.align(Alignment.Center))

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            label = "shortsFullscreenControls",
        ) {
            PhoneFullscreenControls(
                item = item,
                vm = vm,
                pool = pool,
                actions = actions,
                menu = menu,
                scrub = scrub,
                paused = paused,
                scrubbing = scrubbing,
                onBack = { onExit(false) },
                onPanel = onPanel,
            )
        }
        SpeedBoostPill(
            visible = pool.boosting,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        )

        // 面板外点一下关闭面板（同时挡住画面手势）
        if (panel != PhoneSidePanel.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onPanel(PhoneSidePanel.None) } }
            )
        }
        AnimatedVisibility(
            visible = panel != PhoneSidePanel.None,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()) { it } +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()) { it } +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            label = "shortsFullscreenPanel",
        ) {
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(top = 16.dp, end = 16.dp, bottom = 16.dp)
                    .width(360.dp)
                    .fillMaxHeight()
                    .clip(PhoneSidePanelShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    // 面板内的空白处点击不穿透到画面手势层
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                when (shownPanel) {
                    PhoneSidePanel.Comments -> PhoneCommentPanel(
                        item = item,
                        comments = comments,
                        onPictureClick = onPictureClick,
                        onOpenSpace = { mid ->
                            // 先退出全屏、恢复方向，再跳转，免得新页面先以横屏出现
                            onExit(true)
                            actions.openSpace(mid)
                        },
                        onDismiss = { onPanel(PhoneSidePanel.None) },
                        listBottomPadding = 16.dp,
                        dragToDismiss = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 8.dp),
                    )

                    PhoneSidePanel.Menu -> PhoneFullscreenMenu(
                        item = item,
                        menu = menu,
                        onClose = { onPanel(PhoneSidePanel.None) },
                        onDetail = {
                            onExit(true)
                            actions.openVideo(item)
                        },
                    )

                    PhoneSidePanel.None -> Unit
                }
            }
        }
    }
}

/**
 * 全屏里上下滑切条：过 10dp 且竖向分量不小于横向才锁定（横滑交给 [shortsSeekGesture]），
 * 松手时位移超过 64dp 切到上一条 / 下一条。放在 shortsSeekGesture 外层：横滑锁定后那边先吃掉事件，这里看到已消费就退出。
 */
private fun Modifier.phoneFullscreenSwipe(
    onDrag: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = 10.dp.toPx()
    val threshold = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        var dy = 0f
        var locked = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (locked && abs(dy) > threshold) onSwipe(if (dy < 0) 1 else -1)
                    break
                }
                if (!locked && change.isConsumed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!locked) {
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                        locked = true
                    } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        break
                    }
                }
                if (locked) {
                    change.consume()
                    onDrag(dy)
                }
            }
        } finally {
            onDrag(0f)
        }
    }
}

/** 全屏控制层：顶部返回 + 标题 +「弹」，右侧点赞 / 投币 / 评论 / 菜单，底部进度条与倍速 / 画质 */
@Composable
private fun PhoneFullscreenControls(
    item: ShortsItem,
    vm: ShortsViewModel,
    pool: ShortsPlayerPool,
    actions: ShortsActions,
    menu: ShortsMenuState,
    scrub: ShortsScrub,
    paused: Boolean,
    scrubbing: Boolean,
    onBack: () -> Unit,
    onPanel: (PhoneSidePanel) -> Unit,
) {
    val context = LocalContext.current
    val state = vm.actionStateOf(item.aid)
    // 画面横滑拖进度时顶部和右侧淡出，底部进度条留着
    val fade by animateFloatAsState(
        if (scrubbing && scrub.fromGesture) 0f else 1f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shortsFullscreenFade",
    )
    val faded = Modifier.graphicsLayer { alpha = fade }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(faded)
                .background(PhoneFsTopScrim)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BvIconButton(
                icon = painterResource(R.drawable.ic_arrow_back_24),
                contentDescription = stringResource(R.string.shorts_phone_exit_fullscreen),
                onClick = onBack,
                contentColor = Color.White,
            )
            Text(
                item.title,
                modifier = Modifier.weight(1f),
                style = BvType.Section,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PhoneDanmakuToggle(
                on = menu.danmaku.enabled,
                onToggle = { menu.danmaku.toggle() },
                onLongPress = { onPanel(PhoneSidePanel.Menu) },
            )
        }

        if (paused && !scrubbing) {
            Text(
                stringResource(R.string.shorts_phone_fullscreen_hint),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color.Black.copy(alpha = .53f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                style = BvType.Label,
                color = Color.White,
            )
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp, top = 40.dp)
                .then(faded),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SideActionButton(
                icon = painterResource(R.drawable.ic_thumb_up_24),
                label = countOrLabel(item.likeCount, R.string.shorts_like),
                contentDescription = stringResource(R.string.shorts_like),
                active = state.liked,
                onClick = { actions.like(item) },
                size = 40.dp,
            )
            SideActionButton(
                icon = painterResource(R.drawable.ic_coin_24),
                label = stringResource(R.string.shorts_coin),
                contentDescription = stringResource(R.string.shorts_coin),
                active = state.coinCount > 0,
                onClick = { actions.coin(item) },
                size = 40.dp,
            )
            SideActionButton(
                icon = painterResource(R.drawable.ic_chat_bubble_24),
                label = countOrLabel(item.replyCount, R.string.shorts_comment),
                contentDescription = stringResource(R.string.shorts_comment),
                active = false,
                onClick = { onPanel(PhoneSidePanel.Comments) },
                size = 40.dp,
            )
            SideActionButton(
                icon = painterResource(R.drawable.ic_tune_24),
                label = stringResource(R.string.shorts_tab_settings),
                contentDescription = stringResource(R.string.shorts_menu),
                active = false,
                onClick = { onPanel(PhoneSidePanel.Menu) },
                size = 40.dp,
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(PhoneFsBottomScrim)
                .padding(start = 12.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ShortsScrubber(
                pool = pool,
                item = item,
                modifier = Modifier.weight(1f),
                expanded = true,
                scrub = scrub,
                showTime = true,
            )
            // 倍速 / 画质：点一下打开菜单；垂直方向和进度条轨道对齐
            Row(
                Modifier.padding(start = 8.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhoneFullscreenPill(formatShortsSpeed(pool.speed)) { onPanel(PhoneSidePanel.Menu) }
                pool.slotFor(item.aid)?.quality?.let { quality ->
                    PhoneFullscreenPill(quality.getShortDisplayName(context)) { onPanel(PhoneSidePanel.Menu) }
                }
            }
        }
    }
}

@Composable
private fun PhoneFullscreenPill(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = .53f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = BvType.Label.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White,
        maxLines = 1,
    )
}

/**
 * 全屏右侧的菜单。动作行自己画：「视频详情」要先退出全屏、恢复方向再跳转，
 * 共用菜单的动作行只给一个不分动作的 onAction 回调，分不出分享（留在全屏）和跳转。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneFullscreenMenu(
    item: ShortsItem,
    menu: ShortsMenuState,
    onClose: () -> Unit,
    onDetail: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val inToView = menu.inToView(item)
            BvChip(
                text = stringResource(if (inToView) R.string.shorts_menu_toview_added else R.string.shorts_menu_toview),
                selected = inToView,
                onClick = { menu.toggleToView(item) },
                icon = painterResource(R.drawable.ic_watch_later_24),
            )
            BvChip(
                text = stringResource(R.string.shorts_share),
                selected = false,
                onClick = {
                    onClose()
                    menu.actions.share(item)
                },
                icon = painterResource(R.drawable.ic_share_24),
            )
            BvChip(
                text = stringResource(R.string.shorts_menu_detail),
                selected = false,
                onClick = onDetail,
                icon = painterResource(R.drawable.ic_info_24),
            )
        }
        ShortsMenuContent(state = menu, item = item, showActions = false)
    }
}
