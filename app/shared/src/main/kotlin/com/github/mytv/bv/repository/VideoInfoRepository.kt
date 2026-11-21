package com.github.mytv.bv.repository

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.github.mytv.bv.player.entity.VideoListItem
import org.koin.core.annotation.Single

/** 互动视频的节点图上下文：详情页加载后写入，播放器的互动状态机读取 */
data class InteractivePlaybackContext(
    val aid: Long,
    val bvid: String,
    val graphVersion: Int,
)

@Single
class VideoInfoRepository {
    var videoList: List<VideoListItem> by mutableStateOf(emptyList(), referentialEqualityPolicy())
        private set

    /** Publish a complete snapshot; callers cannot mutate the currently displayed list in place. */
    fun replaceVideoList(items: List<VideoListItem>) {
        // 选集面板按 lazyKey 取 item key，接口会重复下发同一集（或 cid 为 0 的占位），
        // 重复 key 直接抛 IllegalArgumentException 把界面带走（1.log：番剧详情 800+ 集）。
        // 这里是所有选集列表的唯一出口，按 key 去重、只保留第一次出现的那个。
        val seen = HashSet<String>(items.size)
        videoList = items.filter { seen.add(it.lazyKey) }
    }

    var interactivePlaybackContext: InteractivePlaybackContext? = null

    fun updateInteractivePlaybackContext(aid: Long, bvid: String, graphVersion: Int?) {
        interactivePlaybackContext = if (bvid.isNotBlank() && graphVersion != null) {
            InteractivePlaybackContext(
                aid = aid,
                bvid = bvid,
                graphVersion = graphVersion,
            )
        } else {
            null
        }
    }

    fun clearInteractivePlaybackContext() {
        interactivePlaybackContext = null
    }
}
