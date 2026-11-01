package com.github.mytv.bv.ui.feature.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.adaptive.FoldPanes
import com.github.mytv.bv.ui.designsystem.component.ActionButtonGroup
import com.github.mytv.bv.ui.designsystem.component.ActionItem
import com.github.mytv.bv.ui.designsystem.component.BvAvatar
import com.github.mytv.bv.ui.designsystem.component.BvIconButton
import com.github.mytv.bv.ui.designsystem.component.SectionHeader
import com.github.mytv.bv.ui.designsystem.component.WavyProgress
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.viewmodel.CommentViewModel
import com.github.mytv.bv.viewmodel.VideoPlayerV3ViewModel
import kotlinx.coroutines.delay

/**
 * 桌面半开（tabletop）详情：上半屏是画面，下半屏是进度 / 播放控制与补充内容。
 *
 * 竖向分区由 [VideoDetailTablet] 处理；这里只用于横向折叠边。
 * 上半屏高度直接取真实折叠边位置（减去状态栏），中间 [FoldPanes.hinge] 留空。
 * 底部控制条读当前播放内核的进度，与内嵌播放器的控制层保持同一播放实例。
 */
@Composable
internal fun VideoDetailTabletop(
    ui: DetailUi,
    follow: DetailFollow?,
    actionItems: List<ActionItem>,
    currentCid: Long,
    commentVm: CommentViewModel,
    callbacks: DetailCallbacks,
    panes: FoldPanes,
    playerVm: VideoPlayerV3ViewModel,
    onEnterFullScreen: () -> Unit,
    playerSlot: @Composable (Modifier) -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topHeight = (panes.first - statusTop).coerceAtLeast(180.dp)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(topHeight)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            playerSlot(Modifier.fillMaxHeight())
        }
        // 横向折叠边 / 铰链：留空，不绘制内容
        Spacer(Modifier.fillMaxWidth().height(panes.hinge))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabletopControlBar(playerVm = playerVm, onEnterFullScreen = onEnterFullScreen)
            Row(
                Modifier
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TabletopInfoPane(
                    ui = ui,
                    follow = follow,
                    actionItems = actionItems,
                    currentCid = currentCid,
                    callbacks = callbacks,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    CommentSortChips(commentVm)
                    DetailCommentList(
                        commentVm = commentVm,
                        onShowPictures = callbacks.onShowPictures,
                        onOpenReplies = callbacks.onOpenReplies,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        showSortChips = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabletopInfoPane(
    ui: DetailUi,
    follow: DetailFollow?,
    actionItems: List<ActionItem>,
    currentCid: Long,
    callbacks: DetailCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BvIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.video_detail_back),
                onClick = callbacks.onBack,
            )
            Text(
                stringResource(R.string.video_detail_title),
                style = TabletHeaderStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DetailTitleBlock(
            ui = ui,
            titleStyle = BvType.TitleLarge,
            onTagClick = callbacks.onTagClick,
        )
        if (ui.seasons.size > 1) {
            DetailSeasonChips(ui, callbacks.onSeasonSelect, contentPadding = PaddingValues(0.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BvAvatar(
                url = ui.upFace,
                size = 44.dp,
                fallbackText = ui.upName,
            )
            Column(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    ui.upName,
                    style = BvType.Body.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.metaTablet.isNotEmpty()) {
                    Text(
                        ui.metaTablet,
                        style = BvType.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (follow != null && follow.followed != null) {
                DetailFollowButton(follow = follow, onClick = callbacks.onFollowClick)
            }
        }
        ActionButtonGroup(actionItems, Modifier.fillMaxWidth())
        ui.rows.forEach { row ->
            if (ui.rows.size > 1 || row.action != null) {
                SectionHeader(
                    title = row.title,
                    action = row.action,
                    onAction = { callbacks.onOpenEpisodes(row) },
                )
            }
            DetailEpisodeChips(
                row = row,
                currentCid = currentCid,
                onPlay = { episode -> callbacks.onPlay(row, episode) },
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}

/**
 * 下半屏控制条：进度、播放 / 暂停与全屏。直接操作内嵌播放器的同一实例，
 * 所以进度、弹幕与画质切换和画面上的控制层完全同步。
 */
@Composable
private fun TabletopControlBar(
    playerVm: VideoPlayerV3ViewModel,
    onEnterFullScreen: () -> Unit,
) {
    val player = playerVm.videoPlayer
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var playing by remember(player) { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (player != null) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(500)
        }
    }

    val progress = {
        if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BvIconButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.video_detail_pause else R.string.video_detail_play
            ),
            onClick = {
                // 以播放内核的实时状态为准：轮询的 playing 可能落后一次点击
                val target = player
                if (target != null) {
                    if (target.isPlaying) target.pause() else target.start()
                    playing = target.isPlaying
                }
            },
        )
        WavyProgress(
            progress = progress,
            modifier = Modifier.weight(1f),
            playing = playing,
            onSeek = { fraction ->
                if (duration > 0L) player?.seekTo((duration * fraction).toLong())
            },
            onSeekFinished = {
                playerVm.danmakuView?.notifySeek(player?.currentPosition ?: 0L)
            },
        )
        Text(
            "${formatDuration(position)} / ${formatDuration(duration)}",
            style = BvType.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BvIconButton(
            icon = Icons.Filled.Fullscreen,
            contentDescription = stringResource(R.string.video_detail_fullscreen),
            onClick = onEnterFullScreen,
        )
    }
}
