package com.github.mytv.bv.ui.feature.video

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.entity.reply.Comment
import dev.aaa1115910.biliapi.entity.reply.CommentSort
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.adaptive.BvInputMode
import com.github.mytv.bv.ui.designsystem.adaptive.LocalDeviceProfile
import com.github.mytv.bv.ui.designsystem.component.BvChip
import com.github.mytv.bv.ui.designsystem.component.CommentItem
import com.github.mytv.bv.ui.designsystem.component.commentCardAction
import com.github.mytv.bv.ui.designsystem.component.tvConfirmClick
import com.github.mytv.bv.ui.designsystem.component.CommentQrCodeDialog
import com.github.mytv.bv.ui.designsystem.component.LoadMoreIndicator
import com.github.mytv.bv.ui.designsystem.component.safeLazyKey
import com.github.mytv.bv.ui.designsystem.component.commentQrLink
import com.github.mytv.bv.ui.designsystem.focus.bvFocus
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.imageviewer.previewer.ImagePreviewer
import com.github.mytv.bv.ui.imageviewer.previewer.ImagePreviewerState
import com.github.mytv.bv.ui.imageviewer.previewer.VerticalDragType
import com.github.mytv.bv.ui.imageviewer.previewer.rememberPreviewerState
import com.github.mytv.bv.util.MAX_IMAGE_DIMENSION
import com.github.mytv.bv.viewmodel.CommentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 最热 / 最新切换（BvChip）。 */
@Composable
internal fun CommentSortChips(commentVm: CommentViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BvChip(
            text = stringResource(R.string.video_detail_sort_hot),
            selected = commentVm.commentSort == CommentSort.Hot,
            onClick = {
                if (commentVm.commentSort != CommentSort.Hot) {
                    scope.launch { commentVm.switchCommentSort(CommentSort.Hot) }
                }
            },
        )
        BvChip(
            text = stringResource(R.string.video_detail_sort_time),
            selected = commentVm.commentSort == CommentSort.Time,
            onClick = {
                if (commentVm.commentSort != CommentSort.Time) {
                    scope.launch { commentVm.switchCommentSort(CommentSort.Time) }
                }
            },
        )
    }
}

/**
 * 评论列表（CommentViewModel）：顶部最热 / 最新标签，下拉刷新，快到底部时加载更多；
 * 有回复的评论点「回复 N」打开回复抽屉。
 */
@Composable
internal fun DetailCommentList(
    commentVm: CommentViewModel,
    onShowPictures: (List<Picture>, Int) -> Unit,
    onOpenReplies: (Comment) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    itemSpacing: Dp = 14.dp,
    showSortChips: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val touchInput = LocalDeviceProfile.current.input == BvInputMode.Touch
    var qrLink by remember { mutableStateOf<String?>(null) }
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last > listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, commentVm.comments.size, commentVm.commentId) {
        if (shouldLoadMore && commentVm.commentId != 0L && commentVm.hasMoreComments && !commentVm.updatingComments) {
            commentVm.loadMoreComment()
        }
    }
    PullToRefreshBox(
        isRefreshing = commentVm.refreshingComments,
        onRefresh = { scope.launch { commentVm.refreshComments() } },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (showSortChips) {
                item(key = "sort") { CommentSortChips(commentVm) }
            }
            itemsIndexed(
                items = commentVm.comments,
                key = { index, comment -> safeLazyKey(comment.rpid, index) },
                contentType = { _, _ -> "comment" },
            ) { _, comment ->
                CommentItem(
                    comment = comment,
                    onPictureClick = onShowPictures,
                    onReplyClick = if (comment.repliesCount > 0) {
                        { onOpenReplies(comment) }
                    } else null,
                    onLongClick = if (touchInput) {
                        { commentQrLink(comment.content)?.let { qrLink = it } }
                    } else null,
                )
            }
            if (commentVm.updatingComments) {
                item(key = "loading") { LoadMoreIndicator() }
            } else if (commentVm.comments.isEmpty() && !commentVm.refreshingComments) {
                item(key = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.video_detail_comments_empty),
                            style = BvType.Small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "nav") { Box(Modifier.navigationBarsPadding()) }
        }
    }
    qrLink?.let { link ->
        CommentQrCodeDialog(link = link, onDismiss = { qrLink = null })
    }
}

/**
 * 回复抽屉：由旧手机 ReplySheetScaffold / Replies 改写（数据走 CommentViewModel 的 replies），
 * 根评论 + 「相关回复共 N 条」+ 排序切换 + 回复列表。
 */
@Composable
internal fun RepliesSheet(
    commentVm: CommentViewModel,
    onShowPictures: (List<Picture>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 抽屉是独立窗口：抽屉打开期间，图片预览要改到它上面的窗口里
    CommentPicturesSheetWindow()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val touchInput = LocalDeviceProfile.current.input == BvInputMode.Touch
    var qrLink by remember { mutableStateOf<String?>(null) }
    val firstRequester = remember { FocusRequester() }
    // 电视端打开回复抽屉后把焦点送进内容，否则方向键仍在背后的页面上
    LaunchedEffect(commentVm.replyRootComment, touchInput) {
        if (touchInput) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { firstRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(80)
        }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last > listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, commentVm.replies.size) {
        if (shouldLoadMore && commentVm.hasMoreReplies && !commentVm.updatingReplies && !commentVm.refreshingReplies) {
            commentVm.loadMoreReplies()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            commentVm.replyRootComment?.let { root ->
                item(key = "root") {
                    val rootAction = commentCardAction(root, onReplyClick = null, onPictureClick = onShowPictures)
                    CommentItem(
                        comment = root,
                        modifier = if (touchInput) Modifier else Modifier
                            .focusRequester(firstRequester)
                            .bvFocus(scale = 1f, corner = 16.dp)
                            // 电视端确认键打开根评论的图片预览
                            .tvConfirmClick(rootAction)
                            .clickable(onClick = rootAction)
                            .padding(8.dp),
                        maxLines = Int.MAX_VALUE,
                        onPictureClick = onShowPictures,
                        onLongClick = if (touchInput) {
                            { commentQrLink(root.content)?.let { qrLink = it } }
                        } else null,
                    )
                }
            }
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.video_detail_replies_title, commentVm.rpCount),
                        style = BvType.Section,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BvChip(
                            text = stringResource(R.string.video_detail_sort_hot),
                            selected = commentVm.replySort == CommentSort.Hot,
                            onClick = {
                                if (commentVm.replySort != CommentSort.Hot) {
                                    scope.launch { commentVm.switchReplySort(CommentSort.Hot) }
                                }
                            },
                        )
                        BvChip(
                            text = stringResource(R.string.video_detail_sort_time),
                            selected = commentVm.replySort == CommentSort.Time,
                            onClick = {
                                if (commentVm.replySort != CommentSort.Time) {
                                    scope.launch { commentVm.switchReplySort(CommentSort.Time) }
                                }
                            },
                        )
                    }
                }
            }
            itemsIndexed(
                items = commentVm.replies,
                key = { index, reply -> safeLazyKey(reply.rpid, index) },
                contentType = { _, _ -> "reply" },
            ) { _, reply ->
                val replyAction = commentCardAction(reply, onReplyClick = null, onPictureClick = onShowPictures)
                CommentItem(
                    comment = reply,
                    modifier = if (touchInput) Modifier else Modifier
                        .bvFocus(scale = 1f, corner = 16.dp)
                        // 电视端确认键打开图片预览
                        .tvConfirmClick(replyAction)
                        .clickable(onClick = replyAction)
                        .padding(8.dp),
                    onPictureClick = onShowPictures,
                    onLongClick = if (touchInput) {
                        { commentQrLink(reply.content)?.let { qrLink = it } }
                    } else null,
                )
            }
            if (commentVm.updatingReplies || commentVm.refreshingReplies) {
                item(key = "loading") { LoadMoreIndicator() }
            }
            item(key = "nav") { Box(Modifier.navigationBarsPadding()) }
        }
    }
    qrLink?.let { link ->
        CommentQrCodeDialog(link = link, onDismiss = { qrLink = null })
    }
}

/**
 * 打开回复抽屉前同步重置回复状态（避免抽屉先按旧的 rpid 加载），之后调用 refreshReplies 加载。
 */
internal fun CommentViewModel.prepareReplies(comment: Comment) {
    rpid = comment.rpid
    rpCount = comment.repliesCount
    replyRootComment = comment
    replies.clear()
    hasMoreReplies = false
}

/** 评论图片预览（沿用旧手机的 ImagePreviewer）。 */
@Stable
internal class CommentPictures(
    val previewer: ImagePreviewerState,
    val pictures: SnapshotStateList<Picture>,
) {
    /**
     * 当前盖在页面上的面板窗口个数（ModalBottomSheet 是独立的对话框窗口，见 [CommentPicturesSheetWindow]）。
     * 预览层平时画在页面窗口里，有面板窗口时会被压在面板下面，所以此时改到全屏对话框窗口里显示。
     */
    internal var sheetWindows by mutableIntStateOf(0)
}

/** 由 [CommentPicturePreviewerHost] 提供给页面内容，面板窗口据此登记自己。 */
private val LocalCommentPictures = staticCompositionLocalOf<CommentPictures?> { null }

/**
 * 在 ModalBottomSheet 里能打开评论图片时调用（面板显示期间保持在组合里）：
 * 面板存在期间，评论图片预览改到全屏对话框窗口显示，否则图片会出现在面板下层。
 */
@Composable
internal fun CommentPicturesSheetWindow() {
    val state = LocalCommentPictures.current ?: return
    DisposableEffect(state) {
        state.sheetWindows++
        onDispose { state.sheetWindows-- }
    }
}

@Composable
internal fun rememberCommentPictures(): CommentPictures {
    val pictures = remember { mutableStateListOf<Picture>() }
    val previewer = rememberPreviewerState(
        verticalDragType = VerticalDragType.UpAndDown,
        pageCount = { pictures.size },
        getKey = { index -> pictures.getOrNull(index)?.key ?: index },
    )
    return remember(previewer, pictures) { CommentPictures(previewer, pictures) }
}

/** 显示图片预览层，并处理返回键关闭。放在页面最上层。 */
@Composable
internal fun CommentPicturePreviewer(state: CommentPictures) {
    val context = LocalContext.current
    val previewer = state.previewer
    val closeScope = rememberCoroutineScope()
    var backProgress by remember { mutableFloatStateOf(0f) }
    // 预览取图的上限：屏幕两倍，单边再夹到 4096（跟全局位图拦截器同源）
    val previewSize = remember(context) {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 2).coerceAtMost(MAX_IMAGE_DIMENSION)
        val height = (metrics.heightPixels * 2).coerceAtMost(MAX_IMAGE_DIMENSION)
        width to height
    }
    // 手势返回时跟随进度缩小淡出，松手后再交给预览器自己的关闭动画
    PredictiveBackHandler(previewer.canClose || previewer.animating) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            backProgress = 0f
            if (previewer.canClose) previewer.close()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    // 部分盒子的返回键让 PredictiveBackHandler 的进度流不完成（表现为按一次返回没反应，
    // 要连按几次才退出）。经典 BackHandler 直接关闭：组合在其后，分发优先级更高，
    // 手势返回的进度动画被它接管后表现为直接关闭，可接受
    BackHandler(enabled = previewer.canClose) {
        closeScope.launch { runCatching { previewer.close() } }
    }
    Box(Modifier.fillMaxSize()) {
        ImagePreviewer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - 0.08f * backProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.15f * backProgress
                },
            state = previewer,
            imageLoader = { index ->
                val url = state.pictures.getOrNull(index)?.url
                // 预览按「屏幕两倍」取图：原图（Size.ORIGINAL）在超长图上会超过盒子的
                // 显示列表上限直接崩，两倍于屏幕对 fit 显示和几倍缩放都够用
                val request = remember(url, previewSize) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(previewSize.first, previewSize.second)
                        .build()
                }
                rememberAsyncImagePainter(request)
            },
        )
    }
}

/**
 * 图片预览宿主：包住页面内容（预览层是它的兄弟覆盖层，同时它是内容里焦点节点的祖先）。
 * 预览打开期间在预览阶段拦截遥控器按键（见 [commentPictureKeys]）。
 * 内容里有 ModalBottomSheet 登记了面板窗口（[CommentPicturesSheetWindow]）时，预览层改到全屏对话框窗口里显示。
 */
@Composable
internal fun CommentPicturePreviewerHost(
    state: CommentPictures,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxSize()
            .commentPictureKeys(state, scope)
    ) {
        CompositionLocalProvider(LocalCommentPictures provides state) {
            content()
        }
        if (state.sheetWindows > 0) {
            CommentPicturePreviewerDialog(state, scope)
        } else {
            CommentPicturePreviewer(state)
        }
    }
}

/**
 * 面板窗口打开时的预览层：只在预览打开 / 动画期间显示一个全屏、无遮暗的对话框窗口。
 * 对话框比面板晚创建，窗口层级在面板之上。按键在对话框窗口里，所以这里也挂上同样的按键处理并拿到焦点。
 */
@Composable
private fun CommentPicturePreviewerDialog(state: CommentPictures, scope: CoroutineScope) {
    val previewer = state.previewer
    val active = previewer.visible || previewer.visibleTarget != null || previewer.animating
    if (!active) return
    Dialog(
        // 返回键先交给预览层自己的 BackHandler；打开动画途中按返回会落到这里，能关时再关
        onDismissRequest = {
            if (previewer.canClose) scope.launch { runCatching { previewer.close() } }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setDimAmount(0f)
            }
            onDispose { }
        }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(focusRequester) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
        Box(
            Modifier
                .fillMaxSize()
                .commentPictureKeys(state, scope)
                .focusRequester(focusRequester)
                .focusable()
        ) {
            CommentPicturePreviewer(state)
        }
    }
}

/**
 * 预览打开期间拦截遥控器按键：左右翻页、确认 / 返回键关闭，其余方向 / 菜单键
 * 吞掉——不拦截的话这些键会漏给背后的播放器 / 页面，在预览后面快进甚至闪退。
 * API < 33 的盒子把返回键当按键下发，这里直接消费并关闭，不再依赖 BackHandler 链。
 */
private fun Modifier.commentPictureKeys(state: CommentPictures, scope: CoroutineScope): Modifier {
    val previewer = state.previewer
    return onPreviewKeyEvent { event ->
        if (!previewer.visible && previewer.visibleTarget != true) return@onPreviewKeyEvent false
        val keyCode = event.nativeKeyEvent.keyCode
        val left = keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
        val right = keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        val center = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == 763
        val back = keyCode == AndroidKeyEvent.KEYCODE_BACK
        val swallow = left || right || center || back ||
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == AndroidKeyEvent.KEYCODE_MENU
        when (event.type) {
            KeyEventType.KeyDown -> when {
                // 翻页：到头就停在当前页
                left || right -> {
                    val target = previewer.currentPage + if (right) 1 else -1
                    if (target in 0 until state.pictures.size) {
                        scope.launch { runCatching { previewer.animateScrollToPage(target) } }
                    }
                    true
                }

                // 返回 / 确认键关闭预览
                center || back -> {
                    if (previewer.canClose) scope.launch { runCatching { previewer.close() } }
                    true
                }

                swallow -> true
                else -> false
            }

            // 抬起也吞掉，避免背后的播放器把确认键抬起当播放 / 暂停
            KeyEventType.KeyUp -> swallow
            else -> false
        }
    }
}

/** 打开图片预览（第 [index] 张）。 */
internal suspend fun CommentPictures.show(pictures: List<Picture>, index: Int) {
    this.pictures.clear()
    this.pictures.addAll(pictures)
    previewer.open(index)
}
