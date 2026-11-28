package com.github.mytv.bv.ui.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.repositories.VideoDetailRepository
import com.github.mytv.bv.entity.PlayerType
import com.github.mytv.bv.player.AbstractVideoPlayer
import com.github.mytv.bv.player.VideoPlayerOptions
import com.github.mytv.bv.player.impl.exo.ExoPlayerFactory
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.viewmodel.VideoPlayerV3ViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.mytv.bv.R as BvR

private val logger = KotlinLogging.logger("PlayerSupport")

/** 创建播放内核（照抄旧 VideoPlayerV3Activity / 手机 VideoPlayerActivity 的 initVideoPlayer）。 */
internal fun createVideoPlayer(context: Context): AbstractVideoPlayer {
    val apiType = Prefs.apiType
    val options = VideoPlayerOptions(
        userAgent = when (apiType) {
            ApiType.Web -> context.getString(BvR.string.video_player_user_agent_http)
            ApiType.App -> context.getString(BvR.string.video_player_user_agent_client)
        },
        referer = when (apiType) {
            ApiType.Web -> context.getString(BvR.string.video_player_referer)
            ApiType.App -> null
        },
        enableFfmpegAudioRenderer = Prefs.enableFfmpegAudioRenderer,
        enableSkipSilence = Prefs.playerSkipSilence,
        audioBalanceLevel = Prefs.playerAudioBalanceLevel,
        // 播放器自身的音量，与系统音量独立
        volume = Prefs.playerVolume
    )
    return when (Prefs.playerType) {
        PlayerType.Media3 -> ExoPlayerFactory().create(context, options)
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 一次播放请求（PlayerRoute 的参数，或详情页内嵌播放器要播放的分 P / 剧集）。
 *
 * @param startMs 开始播放的位置（毫秒），0 表示从头
 */
internal data class PlayRequest(
    val aid: Long,
    val cid: Long,
    val title: String,
    val partTitle: String,
    val epid: Int? = null,
    val seasonId: Int? = null,
    val fromSeason: Boolean = false,
    val subType: Int = 0,
    val startMs: Long = 0L,
    val playerIconIdle: String = "",
    val playerIconMoving: String = "",
)

/**
 * 续播起点（毫秒）：[playedSec] 是接口记录的播放进度（秒），[durationSec] 是这一 P / 这一集的时长（秒）。
 *
 * 进度已经到片尾时（看完，接口可能给 -1，也可能给时长本身）返回 0：默认续播的话，
 * 一打开就会判定播完直接连播下一集。时长未知时同样不续播，避免把未知进度当成有效进度。
 */
internal fun resumeStartMs(playedSec: Int, durationSec: Int): Long =
    if (playedSec > 0 && durationSec > 0 && playedSec < durationSec) {
        playedSec * 1000L
    } else {
        0L
    }

/**
 * cid 未知（0）时补全 aid / cid：番剧用 epid 查（旧手机 VideoPlayerActivity），
 * 普通视频取详情的第一个分 P（旧电视 SeasonInfo 跳回 VideoInfo 取 cid 的做法）。
 */
internal suspend fun resolveAidCid(
    aid: Long,
    cid: Long,
    epid: Int?,
    videoDetailRepository: VideoDetailRepository,
): Pair<Long, Long> {
    if (cid != 0L) return aid to cid
    return withContext(Dispatchers.IO) {
        val byEpid = epid?.takeIf { it != 0 }?.let { BiliHttpApi.getAidCidByEpid(it) }
        if (byEpid != null) return@withContext byEpid
        runCatching {
            val detail = videoDetailRepository.getVideoDetail(aid = aid, preferApiType = Prefs.apiType)
            detail.aid to detail.cid
        }.onFailure {
            logger.warn { "Resolve cid of av$aid failed: ${it.stackTraceToString()}" }
        }.getOrDefault(aid to cid)
    }
}

/**
 * 让 [VideoPlayerV3ViewModel] 播放 [request]（照抄旧 VideoPlayerV3Activity.getParamsFromIntent）。
 * [resumeMs] > 0 时记忆播放：新媒体准备后直接跳到该位置（同步弹幕），
 * 播放器起播后给出「已续播，确认从头播放」的临时提示。必须在主线程调用。
 */
internal suspend fun startPlayback(
    playerVm: VideoPlayerV3ViewModel,
    request: PlayRequest,
    videoDetailRepository: VideoDetailRepository,
    resumeMs: Long = request.startMs,
) {
    val (aid, cid) = resolveAidCid(request.aid, request.cid, request.epid, videoDetailRepository)
    val resume = resumeMs.coerceAtLeast(0L)
    logger.info { "Start playback: [aid=$aid, cid=$cid, epid=${request.epid}, resume=$resume]" }
    playerVm.title = request.title
    playerVm.partTitle = request.partTitle
    playerVm.fromSeason = request.fromSeason
    playerVm.subType = request.subType
    playerVm.playerIconIdle = request.playerIconIdle
    playerVm.playerIconMoving = request.playerIconMoving
    // 续播起点（毫秒）：播放器起播后据此提示「确认从头播放」，提示结束或用户确认后清空
    playerVm.lastPlayed = if (resume > 0L) resume.toInt() else 0
    playerVm.showBuffering = false
    // 续播位置交给 ViewModel 在 prepare() 之后落到新媒体上（pendingInitialSeekPositionMs），
    // 与互动分支切换走同一条路径：不用先起播再从 0 跳到续播点
    playerVm.loadPlayUrl(
        avid = aid,
        cid = cid,
        epid = request.epid?.takeIf { it != 0 },
        seasonId = request.seasonId?.takeIf { it != 0 },
        initialSeekPositionMs = resume.takeIf { it > 0L },
    )
}
